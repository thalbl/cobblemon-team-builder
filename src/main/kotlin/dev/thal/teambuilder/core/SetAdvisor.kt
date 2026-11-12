package dev.thal.teambuilder.core

import dev.thal.teambuilder.core.ProfileBuilder.Companion.DRAWBACK_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.DOUBLES_SUPPORT
import dev.thal.teambuilder.core.ProfileBuilder.Companion.HAZARD_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.NEUTRAL_SETUP
import dev.thal.teambuilder.core.ProfileBuilder.Companion.PHYSICAL_SETUP
import dev.thal.teambuilder.core.ProfileBuilder.Companion.PIVOT_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.PROTECT_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.RECOVERY_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.REDIRECTION_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.REMOVAL_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.SCREEN_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.SETUP_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.SPECIAL_SETUP
import dev.thal.teambuilder.core.ProfileBuilder.Companion.SPEED_CONTROL_MOVES
import dev.thal.teambuilder.core.ProfileBuilder.Companion.STATUS_MOVES
import kotlin.math.max
import kotlin.math.min

enum class MoveStatus {
    /** Ja esta entre os 4 golpes atuais. */
    KNOWN,
    /** Ja aprendido, basta trocar no menu de golpes. */
    SWAP,
    /** A especie aprende, mas este exemplar ainda nao tem (ou o jogador nao tem o Pokemon). */
    LEARN,
}

/** Funcao de um membro dentro do time. Muda golpes, item, natureza e EVs sugeridos. */
enum class SetRole(val id: String) {
    PHYSICAL("physical"),
    SPECIAL("special"),
    SETUP("setup"),
    PIVOT("pivot"),
    TANK("tank"),
    SUPPORT("support");

    fun label(lang: Lang): String = lang.t("role.$id")

    companion object {
        fun of(id: String?): SetRole? = entries.firstOrNull { it.id == id }
    }
}

data class MoveAdvice(
    val id: String,
    val status: MoveStatus,
    /** Codigo de como se aprende ("lv:36", "tm", "egg"...). Vazio se desconhecido. */
    val method: String,
    /** Por que este golpe (ja traduzido), quando a escolha veio do time ou da funcao. */
    val note: String,
)

data class SetAdvice(
    val role: SetRole,
    val moves: List<MoveAdvice>,
    /** Itens do mais para o menos indicado. */
    val items: List<String>,
    val ability: String,
    val nature: String,
    /** HP/Atk/Def/SpA/SpD/Spe. */
    val evs: List<Int>,
    /** Diferencas entre o exemplar do jogador e o set sugerido. */
    val notes: List<String>,
    /** true = baseado em uso real no Smogon; false = heuristica. */
    val fromMeta: Boolean,
    /** Atributos (0..5) cujo IV vale subir, do mais para o menos urgente. So para Pokemon do jogador. */
    val ivPriority: List<Int> = emptyList(),
    /** Atributos (0..5) que este set nao usa: o IV deles nao importa. */
    val ivUnused: List<Int> = emptyList(),
)

/** O que se sabe de um companheiro: a funcao dele e os golpes do set dele. */
data class MateSet(val profile: Profile, val role: SetRole, val moves: List<String>)

/** Uma funcao possivel para um membro, com o set que ela daria. */
data class RoleChoice(val role: SetRole, val recommended: Boolean, val advice: SetAdvice)

/**
 * Sugere golpes, item, habilidade, natureza e EVs para um Pokemon dentro de um time.
 *
 * Cada membro tem uma funcao ([SetRole]): a recomendada sai dos stats, do set mais usado no
 * Smogon e do que o resto do time ja faz. O set mistura o uso real (so com golpes que ele
 * aprende no Cobblemon) com o que a funcao pede e o que os companheiros ainda nao cobrem.
 */
class SetAdvisor(
    private val moves: Map<String, MoveInfo>,
    @Suppress("unused") private val scorer: TeamScorer,
    private val format: Format = Format.SINGLES,
) {
    private val doubles get() = format == Format.DOUBLES

    private val supportMoves: Set<String> =
        HAZARD_MOVES + REMOVAL_MOVES + SCREEN_MOVES + (STATUS_MOVES - setOf("knockoff", "protect", "substitute", "nuzzle")) +
            (if (format == Format.DOUBLES) SPEED_CONTROL_MOVES + REDIRECTION_MOVES + DOUBLES_SUPPORT else emptySet())

    /** O que o resto do time ja faz. */
    private inner class Context(val mates: List<MateSet>) {
        val empty = mates.isEmpty()
        private val all = mates.flatMapTo(HashSet()) { it.moves }
        val coverage: Int = mates.fold(0) { acc, m ->
            val types = m.moves.mapNotNull { moves[it] }
                .filter { it.category != MoveCategory.STATUS && it.power >= 60 }.map { it.type } + m.profile.candidate.types
            acc or TypeChart.superEffectiveMask(types)
        }
        val hazards = all.any { it in HAZARD_MOVES }
        val removal = all.any { it in REMOVAL_MOVES }
        val speedControl = all.any { it in SPEED_CONTROL_MOVES }
        val roles = mates.map { it.role }
        val physical = mates.any { it.role == SetRole.PHYSICAL || (it.role == SetRole.SETUP && physicalSide(it.profile)) }
        val special = mates.any { it.role == SetRole.SPECIAL || (it.role == SetRole.SETUP && !physicalSide(it.profile)) }
    }

    // ----------------------------------------------------------------- api

    /** Versao simples: o resto do time com a funcao recomendada e os golpes provaveis. */
    fun advise(p: Profile, team: List<Profile>, lang: Lang = Lang.EN): SetAdvice {
        val mates = team.filter { it !== p }.map { MateSet(it, recommend(it, emptyList()), it.likelyMoves) }
        return adviseWith(p, mates, null, lang)
    }

    /** Set de [p] na funcao [role] (ou na recomendada), sabendo o que os [mates] fazem. */
    fun adviseWith(p: Profile, mates: List<MateSet>, role: SetRole?, lang: Lang = Lang.EN): SetAdvice {
        val ctx = Context(mates)
        val r = role?.takeIf { it in roles(p) } ?: recommend(p, mates)
        val physical = when (r) {
            SetRole.PHYSICAL -> true
            SetRole.SPECIAL -> false
            else -> physicalSide(p)
        }
        val picks = pickMoves(p, r, physical, ctx, lang)
        val ids = picks.map { it.first }
        val (nature, evs) = spread(p, r, physical)
        val ability = p.meta?.abilities?.keys?.firstOrNull { p.candidate.abilities.isEmpty() || it in p.candidate.abilities || p.isOwned }
            ?: p.ability

        val notes = ArrayList<String>()
        p.candidate.owned?.let { mine ->
            if (mine.nature != nature) notes += lang.t("note.current_nature", mine.nature)
            if (ability.isNotEmpty() && mine.ability != ability) notes += lang.t("note.current_ability", mine.ability)
        }
        val (priority, unused) = ivAdvice(p, r, evs, ids)
        return SetAdvice(
            role = r,
            moves = picks.map { (id, note) -> MoveAdvice(id, status(p, id), p.candidate.learnMethods[id].orEmpty(), note) },
            items = items(p, r, ids),
            ability = ability,
            nature = nature,
            evs = evs,
            notes = notes,
            fromMeta = p.meta != null && p.meta.moves.isNotEmpty(),
            ivPriority = priority,
            ivUnused = unused,
        )
    }

    /** Todas as funcoes possiveis de [p], cada uma com o seu set; a recomendada vem marcada. */
    fun choices(p: Profile, mates: List<MateSet>, lang: Lang = Lang.EN): List<RoleChoice> {
        val best = recommend(p, mates)
        return roles(p).map { r -> RoleChoice(r, r == best, adviseWith(p, mates, r, lang)) }
    }

    /** Funcoes que fazem sentido para [p], pelos golpes que ele aprende e pelos stats. */
    fun roles(p: Profile): List<SetRole> {
        val legal = p.candidate.legalMoves
        val phys = damagingLegal(p, MoveCategory.PHYSICAL)
        val spec = damagingLegal(p, MoveCategory.SPECIAL)
        val physical = physicalSide(p)
        val noData = moves.isEmpty() || legal.isEmpty()
        val out = ArrayList<SetRole>()
        if (phys >= 2 || (noData && physical)) out += SetRole.PHYSICAL
        if (spec >= 2 || (noData && !physical)) out += SetRole.SPECIAL
        if (legal.any { setupFits(it, physical) } && (phys >= 2 || spec >= 2)) out += SetRole.SETUP
        if (legal.any { it in PIVOT_MOVES }) out += SetRole.PIVOT
        val bulk = bulk(p)
        if (bulk >= 400 || (bulk >= 330 && legal.any { it in RECOVERY_MOVES })) out += SetRole.TANK
        if (legal.count { it in supportMoves } >= 2) out += SetRole.SUPPORT
        if (out.isEmpty()) out += if (physical) SetRole.PHYSICAL else SetRole.SPECIAL
        return out
    }

    /** A funcao que mais combina com [p] e com o que falta ao time. */
    fun recommend(p: Profile, mates: List<MateSet>): SetRole {
        val available = roles(p)
        if (available.size == 1) return available[0]
        val ctx = Context(mates)
        val meta = metaRole(p)
        val legal = p.candidate.legalMoves
        val atk = stat(p, StatKey.ATK)
        val spa = stat(p, StatKey.SPA)
        val spe = stat(p, StatKey.SPE)
        val recovery = legal.any { it in RECOVERY_MOVES }

        fun score(r: SetRole): Double {
            var s = when (r) {
                SetRole.PHYSICAL -> atk / 100.0 + spe / 200.0
                SetRole.SPECIAL -> spa / 100.0 + spe / 200.0
                // Setup e mais arriscado: so passa na frente quando o uso real ou o time pedem.
                SetRole.SETUP -> max(atk, spa) / 100.0 + spe / 200.0 - 0.25
                SetRole.PIVOT -> 0.6 + spe / 250.0
                SetRole.TANK -> bulk(p) / 450.0 + if (recovery) 0.25 else 0.0
                SetRole.SUPPORT -> bulk(p) / 550.0 + 0.12 * min(4, legal.count { it in supportMoves })
            }
            if (r == meta) s += 0.6
            if (!ctx.empty) {
                // Dois membros na mesma funcao rendem menos que funcoes variadas.
                s -= 0.25 * ctx.roles.count { it == r }
                when (r) {
                    SetRole.SUPPORT -> {
                        if (!doubles && !ctx.hazards && legal.any { it in HAZARD_MOVES }) s += 0.35
                        if (doubles && !ctx.speedControl && legal.any { it in SPEED_CONTROL_MOVES }) s += 0.35
                    }
                    SetRole.TANK -> if (SetRole.TANK !in ctx.roles) s += 0.15
                    SetRole.PHYSICAL -> if (!ctx.physical) s += 0.2
                    SetRole.SPECIAL -> if (!ctx.special) s += 0.2
                    SetRole.PIVOT -> if (SetRole.PIVOT !in ctx.roles) s += 0.1
                    SetRole.SETUP -> if (if (physicalSide(p)) ctx.physical else ctx.special) s -= 0.1
                }
            }
            return s
        }
        return available.maxBy(::score)
    }

    // -------------------------------------------------------------- golpes

    private class Cand(val id: String, val info: MoveInfo?, val base: Double, val note: String) {
        val damaging: Boolean get() = info != null && info.category != MoveCategory.STATUS
    }

    /** Ate 4 pares (golpe, motivo). */
    private fun pickMoves(p: Profile, role: SetRole, physical: Boolean, ctx: Context, lang: Lang): List<Pair<String, String>> {
        val legal = p.candidate.legalMoves
        val usage = p.meta?.moves.orEmpty().filter { legal.isEmpty() || it.key in legal }
        val pool: Collection<String> = legal.ifEmpty { usage.keys }
        val preferred = if (physical) MoveCategory.PHYSICAL else MoveCategory.SPECIAL
        val attacker = role == SetRole.PHYSICAL || role == SetRole.SPECIAL || role == SetRole.SETUP
        val types = p.candidate.types
        val stabMask = TypeChart.superEffectiveMask(types)

        val cands = ArrayList<Cand>()
        for (id in pool) {
            val info = moves[id]
            val freq = usage[id] ?: 0.0
            var s = freq * 1.2
            var note = ""
            if (info == null) {
                if (freq > 0) cands += Cand(id, null, s, "")
                continue
            }
            val damaging = info.category != MoveCategory.STATUS
            if (damaging) {
                val useful = info.power >= 60 || (info.priority > 0 && info.power >= 40) || id in PIVOT_MOVES || id == "fakeout"
                if (!useful && freq < 0.2) continue
                if (id in DRAWBACK_MOVES && freq < 0.2) continue
                if (info.category != preferred && freq < 0.3) s -= if (attacker) 0.8 else 0.4
                val stab = info.type in types
                s += min(info.power, 150) / 100.0 * (if (stab) 1.5 else 1.0) * damageWeight(role)
                if (stab) note = lang.t("note.stab")
                if (info.priority > 0 && info.power >= 40 && attacker) s += 0.15
                if (doubles && info.spread) {
                    s += 0.25
                    note = lang.t("note.spread")
                }
            }
            val (bonus, why) = roleBonus(id, info, role, physical, ctx, lang)
            if (!damaging && freq <= 0.0 && bonus <= 0.0) continue
            s += bonus
            if (why.isNotEmpty()) note = why
            cands += Cand(id, info, s, note)
        }

        val need = when (role) {
            SetRole.PHYSICAL, SetRole.SPECIAL -> 3
            SetRole.SETUP, SetRole.PIVOT -> 2
            SetRole.TANK, SetRole.SUPPORT -> 1
        }
        val minDamaging = min(need, cands.count { it.damaging })
        val chosen = ArrayList<Pair<Cand, String>>()
        while (chosen.size < 4) {
            val taken = chosen.map { it.first }
            val damagingSoFar = taken.count { it.damaging }
            val mustDamage = 4 - chosen.size <= minDamaging - damagingSoFar
            val coveredByChosen = taken.filter { it.damaging && (it.info?.power ?: 0) >= 60 }
                .fold(0) { acc, c -> acc or TypeChart.superEffectiveMask(listOf(c.info!!.type)) }

            var best: Cand? = null
            var bestScore = Double.NEGATIVE_INFINITY
            var bestNote = ""
            for (c in cands) {
                if (taken.any { it.id == c.id }) continue
                if (mustDamage && !c.damaging) continue
                if (c.id in SETUP_MOVES && taken.any { it.id in SETUP_MOVES }) continue
                if (c.id in RECOVERY_MOVES && taken.any { it.id in RECOVERY_MOVES }) continue
                if (c.id in PROTECT_MOVES && taken.any { it.id in PROTECT_MOVES }) continue
                var s = c.base
                var note = c.note
                val info = c.info
                if (info != null && c.damaging) {
                    if (info.power >= 60 && info.type !in types) {
                        val fresh = TypeChart.superEffectiveMask(listOf(info.type)) and
                            ctx.coverage.inv() and stabMask.inv() and coveredByChosen.inv()
                        val n = Integer.bitCount(fresh)
                        if (n > 0) {
                            s += 0.15 * min(3, n)
                            if (note.isEmpty()) note = lang.t("note.covers", Lang.types(TypeChart.typesIn(fresh)))
                        }
                    }
                    val sameType = taken.filter { it.damaging && it.info?.type == info.type }
                    if (sameType.isNotEmpty()) {
                        val priorityPair = info.priority > 0 || sameType.any { (it.info?.priority ?: 0) > 0 }
                        s -= if (priorityPair) 0.3 else 0.5
                    }
                }
                if (c.id in HAZARD_MOVES && taken.any { it.id in HAZARD_MOVES }) s -= 0.6
                if (c.id in SCREEN_MOVES && taken.any { it.id in SCREEN_MOVES }) s -= 0.3
                if (s > bestScore) {
                    best = c
                    bestScore = s
                    bestNote = note
                }
            }
            if (best == null) break
            chosen += best to bestNote
        }
        return chosen.map { (c, note) -> c.id to note }
    }

    private fun damageWeight(role: SetRole) = when (role) {
        SetRole.PHYSICAL, SetRole.SPECIAL -> 0.55
        SetRole.SETUP -> 0.5
        SetRole.PIVOT -> 0.4
        SetRole.TANK -> 0.25
        SetRole.SUPPORT -> 0.2
    }

    /** Quanto a funcao e o time pedem este golpe, e por que. */
    private fun roleBonus(id: String, info: MoveInfo?, role: SetRole, physical: Boolean, ctx: Context, lang: Lang): Pair<Double, String> {
        val support = role == SetRole.SUPPORT
        val tank = role == SetRole.TANK
        val pivot = role == SetRole.PIVOT
        val setup = role == SetRole.SETUP
        val status = info?.category == MoveCategory.STATUS
        return when {
            id in HAZARD_MOVES -> when {
                ctx.hazards -> -0.3 to ""
                doubles -> 0.0 to ""
                else -> (if (support) 0.9 else if (tank) 0.5 else 0.35) to
                    lang.t(if (ctx.empty) "note.hazards" else "note.no_hazards")
            }
            id in REMOVAL_MOVES -> when {
                ctx.removal -> -0.3 to ""
                doubles -> 0.0 to ""
                else -> (if (support) 0.6 else if (tank || pivot) 0.4 else 0.2) to
                    lang.t(if (ctx.empty) "note.removal" else "note.no_removal")
            }
            id in RECOVERY_MOVES ->
                (if (tank) 0.9 else if (support) 0.5 else if (pivot) 0.3 else if (setup) 0.2 else 0.0) to lang.t("note.recovery")
            id in SETUP_MOVES -> when {
                !setupFits(id, physical) -> -0.5 to ""
                setup -> 1.2 to lang.t("note.setup")
                tank && id in setOf("curse", "calmmind", "bulkup", "irondefense") -> 0.3 to lang.t("note.setup")
                role == SetRole.PHYSICAL || role == SetRole.SPECIAL -> 0.15 to lang.t("note.setup")
                else -> 0.0 to ""
            }
            id in PIVOT_MOVES -> (if (pivot) 0.9 else 0.15) to lang.t("note.pivot")
            id in SCREEN_MOVES -> (if (support) 0.5 else 0.0) to lang.t("note.screens")
            doubles && id in PROTECT_MOVES -> 0.9 to lang.t("note.protect")
            doubles && id == "fakeout" -> 0.7 to lang.t("note.fakeout")
            doubles && id in SPEED_CONTROL_MOVES ->
                (if (ctx.speedControl) 0.1 else if (support) 0.7 else 0.4) to lang.t("note.speed_control")
            doubles && id in REDIRECTION_MOVES -> (if (support) 0.6 else 0.0) to lang.t("note.redirection")
            doubles && id in DOUBLES_SUPPORT -> (if (support) 0.3 else 0.0) to ""
            status && id in STATUS_MOVES ->
                (if (support) 0.6 else if (tank) 0.45 else if (pivot) 0.25 else 0.0) to lang.t("note.status")
            else -> 0.0 to ""
        }
    }

    private fun setupFits(id: String, physical: Boolean) =
        id in NEUTRAL_SETUP || (physical && id in PHYSICAL_SETUP) || (!physical && id in SPECIAL_SETUP)

    private fun status(p: Profile, id: String): MoveStatus {
        val mine = p.candidate.owned ?: return MoveStatus.LEARN
        return when (id) {
            in mine.currentMoves -> MoveStatus.KNOWN
            in mine.accessibleMoves -> MoveStatus.SWAP
            else -> MoveStatus.LEARN
        }
    }

    // ------------------------------------------------- item, natureza e EVs

    /** A funcao que o set mais usado no Smogon cumpre. */
    private fun metaRole(p: Profile): SetRole? {
        val meta = p.meta ?: return null
        val top = meta.moves.filter { it.value >= 0.3 }.keys
        if (meta.moves.any { it.key in SETUP_MOVES && it.value >= 0.4 }) return SetRole.SETUP
        val spread = meta.spreads.firstOrNull()?.evs
        if (spread != null && spread.size == 6) {
            if (defensive(spread)) return if (top.count { it in supportMoves } >= 2) SetRole.SUPPORT else SetRole.TANK
            if (spread[1] >= 200 || spread[3] >= 200) return if (spread[1] >= spread[3]) SetRole.PHYSICAL else SetRole.SPECIAL
        }
        if (meta.moves.any { it.key in PIVOT_MOVES && it.value >= 0.5 }) return SetRole.PIVOT
        return null
    }

    private fun defensive(e: List<Int>) = e[0] >= 200 && (e[2] >= 150 || e[4] >= 150)

    private fun items(p: Profile, role: SetRole, ids: List<String>): List<String> {
        val hasStatus = ids.any { moves[it]?.category == MoveCategory.STATUS }
        val metaItems = p.meta?.items?.keys.orEmpty().filter { !(hasStatus && it.startsWith("choice")) }
        if (metaItems.isNotEmpty() && role == metaRole(p)) return metaItems.take(2)
        val screens = ids.any { it in SCREEN_MOVES }
        val defaults = when (role) {
            SetRole.PHYSICAL -> if (hasStatus) listOf("lifeorb", "leftovers") else listOf("choiceband", "choicescarf")
            SetRole.SPECIAL -> if (hasStatus) listOf("lifeorb", "leftovers") else listOf("choicespecs", "choicescarf")
            SetRole.SETUP -> if (doubles) listOf("lifeorb", "sitrusberry") else listOf("lifeorb", "leftovers")
            SetRole.PIVOT -> if (hasStatus) listOf("heavydutyboots", "leftovers") else listOf("assaultvest", "heavydutyboots")
            SetRole.TANK -> if (doubles) listOf("sitrusberry", "leftovers") else listOf("leftovers", "rockyhelmet")
            SetRole.SUPPORT -> when {
                screens -> listOf("lightclay", "leftovers")
                doubles -> listOf("sitrusberry", "mentalherb")
                else -> listOf("leftovers", "heavydutyboots")
            }
        }
        // Um item de uso real que combine com a funcao vira a alternativa.
        val alt = metaItems.firstOrNull { it !in defaults && !(it.startsWith("choice") && role !in setOf(SetRole.PHYSICAL, SetRole.SPECIAL)) }
        return if (alt != null) listOf(defaults[0], alt) else defaults
    }

    private fun spread(p: Profile, role: SetRole, physical: Boolean): Pair<String, List<Int>> {
        val fit = p.meta?.spreads?.firstOrNull { it.evs.size == 6 && compatible(it.evs, role, physical) }
        if (fit != null) return fit.nature to fit.evs
        val fast = stat(p, StatKey.SPE) >= 90
        val attack = if (physical) listOf(0, 252, 0, 0, 4, 252) else listOf(0, 0, 0, 252, 4, 252)
        fun tank(): Pair<String, List<Int>> =
            if (stat(p, StatKey.DEF) >= stat(p, StatKey.SPD)) (if (physical) "impish" else "bold") to listOf(252, 0, 252, 0, 4, 0)
            else (if (physical) "careful" else "calm") to listOf(252, 0, 4, 0, 252, 0)
        return when (role) {
            SetRole.PHYSICAL -> (if (fast) "jolly" else "adamant") to attack
            SetRole.SPECIAL -> (if (fast) "timid" else "modest") to attack
            SetRole.SETUP ->
                (if (physical) (if (fast) "jolly" else "adamant") else (if (fast) "timid" else "modest")) to attack
            SetRole.PIVOT ->
                if (stat(p, StatKey.SPE) >= 80) (if (physical) "jolly" else "timid") to listOf(252, 0, 4, 0, 0, 252) else tank()
            SetRole.TANK, SetRole.SUPPORT -> tank()
        }
    }

    private fun compatible(e: List<Int>, role: SetRole, physical: Boolean): Boolean = when (role) {
        SetRole.PHYSICAL -> e[1] >= 200
        SetRole.SPECIAL -> e[3] >= 200
        SetRole.SETUP -> if (physical) e[1] >= 200 else e[3] >= 200
        SetRole.PIVOT -> e[5] >= 200 || defensive(e)
        SetRole.TANK, SetRole.SUPPORT -> defensive(e)
    }

    // ------------------------------------------------------------------ IVs

    /**
     * Ordem para subir os IVs do exemplar do jogador: pesa cada atributo pelo quanto o set
     * investe nele e pelo quanto falta para 31. Atributos que o set nao usa ficam de fora.
     */
    private fun ivAdvice(p: Profile, role: SetRole, evs: List<Int>, ids: List<String>): Pair<List<Int>, List<Int>> {
        val mine = p.candidate.owned ?: return emptyList<Int>() to emptyList()
        val infos = ids.mapNotNull { moves[it] }
        val usesPhysical = infos.any { it.category == MoveCategory.PHYSICAL } || (infos.isEmpty() && role == SetRole.PHYSICAL)
        val usesSpecial = infos.any { it.category == MoveCategory.SPECIAL } || (infos.isEmpty() && role == SetRole.SPECIAL)
        val w = DoubleArray(6) { evs.getOrElse(it) { 0 } / 252.0 }
        w[0] = max(w[0], 0.4)
        w[2] = max(w[2], 0.25)
        w[4] = max(w[4], 0.25)
        w[1] = if (usesPhysical) max(w[1], 0.5) else 0.0
        w[3] = if (usesSpecial) max(w[3], 0.5) else 0.0
        w[5] = max(w[5], if (role == SetRole.TANK || role == SetRole.SUPPORT) 0.1 else 0.3)
        // Gyro Ball e Trick Room querem o Pokemon lento.
        if (evs.getOrElse(5) { 0 } == 0 && ids.any { it == "gyroball" || it == "trickroom" }) w[5] = 0.0

        val ivs = StatKey.entries.map { mine.ivs[it] ?: 0 }
        val unused = (0 until 6).filter { w[it] == 0.0 }
        val priority = (0 until 6).filter { w[it] > 0.0 && ivs[it] < 31 }
            .sortedByDescending { w[it] * (31 - ivs[it]) }
        return priority to unused
    }

    // --------------------------------------------------------------- stats

    private fun stat(p: Profile, key: StatKey) = p.candidate.baseStats[key] ?: 0

    private fun physicalSide(p: Profile) = stat(p, StatKey.ATK) >= stat(p, StatKey.SPA)

    private fun bulk(p: Profile) = 2 * stat(p, StatKey.HP) + stat(p, StatKey.DEF) + stat(p, StatKey.SPD)

    private fun damagingLegal(p: Profile, category: MoveCategory) = p.candidate.legalMoves.count { id ->
        val m = moves[id]
        m != null && m.category == category && m.power >= 60 && id !in DRAWBACK_MOVES
    }
}

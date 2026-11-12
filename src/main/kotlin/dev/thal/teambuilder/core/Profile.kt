package dev.thal.teambuilder.core

import kotlin.math.min
import kotlin.math.sqrt

enum class MoveCategory { PHYSICAL, SPECIAL, STATUS }

/** Singles (1v1) ou duplas (2v2): muda os pesos da nota e os golpes valorizados. */
enum class Format { SINGLES, DOUBLES }

data class MoveInfo(
    val id: String,
    val type: String,
    val category: MoveCategory,
    val power: Int,
    val priority: Int,
    /** Acerta os dois oponentes (ou todos) numa batalha dupla. */
    val spread: Boolean = false,
)

/** Algo que pode ocupar um slot: um Pokemon do jogador ou uma especie/forma qualquer. */
data class Candidate(
    val id: String,
    val speciesId: String,
    val name: String,
    val types: List<String>,
    val abilities: List<String>,
    val baseStats: Map<StatKey, Int>,
    val legalMoves: Set<String>,
    val owned: OwnedPokemon? = null,
    val learnMethods: Map<String, String> = emptyMap(),
    val legendary: Boolean = false,
    /** Preenchido quando o candidato e uma mega: id da forma base ("charizard"). */
    val megaOf: String? = null,
    /** Identificador da especie ("cobblemon:charizard") e aspectos, para desenhar o modelo. */
    val species: String = "",
    val aspects: Set<String> = emptySet(),
)

fun OwnedPokemon.toCandidate() = Candidate(
    id = id, speciesId = speciesId, name = displayName, types = types,
    abilities = listOf(ability), baseStats = baseStats, legalMoves = legalMoves, owned = this,
    learnMethods = learnMethods, legendary = legendary, species = species, aspects = aspects,
)

/** Caracteristicas de um Pokemon que a nota do time valoriza. */
enum class Role {
    PHYSICAL, SPECIAL, FAST, BULKY, HAZARDS, REMOVAL, PIVOT, PRIORITY, SETUP, RECOVERY,
    // So pesam em duplas.
    SPEED_CONTROL, FAKE_OUT, REDIRECTION, INTIMIDATE, SPREAD;

    fun label(lang: Lang): String = lang.t("tag.${name.lowercase()}")
}

/** Tudo que o motor precisa saber de um candidato, calculado uma unica vez. */
class Profile(
    val candidate: Candidate,
    val meta: MetaEntry?,
    val ability: String,
    /** 0..1: forca individual (tier/uso no Smogon, ou stats base quando nao ha dados). */
    val strength: Double,
    /** Dano recebido de cada tipo de ataque, indexado como [TypeChart.TYPES]. */
    val defense: DoubleArray,
    /** Tipos defensores que ele acerta com vantagem. */
    val coverageMask: Int,
    val roles: Set<Role>,
    /** Golpes que ele provavelmente usa, do mais para o menos comum. */
    val likelyMoves: List<String>,
) {
    val id: String get() = candidate.id
    val speciesId: String get() = candidate.speciesId
    val name: String get() = candidate.name
    val isOwned: Boolean get() = candidate.owned != null
    val isMega: Boolean get() = candidate.megaOf != null

    override fun toString() = name
}

class ProfileBuilder(
    private val meta: MetaSnapshot,
    private val moves: Map<String, MoveInfo>,
    private val format: Format = Format.SINGLES,
) {
    fun build(c: Candidate): Profile {
        val entry = meta[c.id] ?: meta[c.speciesId]
        // Em batalha a mega usa a habilidade da forma mega, nao a do exemplar.
        val ability = c.abilities.firstOrNull()?.takeIf { c.megaOf != null }
            ?: c.owned?.ability
            ?: entry?.abilities?.keys?.firstOrNull { c.abilities.isEmpty() || it in c.abilities }
            ?: c.abilities.firstOrNull()
            ?: ""
        val likely = likelyMoves(c, entry)
        return Profile(
            candidate = c,
            meta = entry,
            ability = ability,
            strength = strength(c, entry),
            defense = TypeChart.defensiveProfile(c.types, ability),
            coverageMask = TypeChart.superEffectiveMask(attackTypes(c, likely)),
            roles = roles(c, likely, ability),
            likelyMoves = likely,
        )
    }

    private fun strength(c: Candidate, entry: MetaEntry?): Double {
        val bst = c.baseStats.values.sum()
        val base = if (entry != null) {
            val tierBase = TIER_BASE.getOrElse(entry.tier) { 0.3 }
            tierBase * (0.55 + 0.45 * min(1.0, sqrt(entry.usage / 0.04)))
        } else {
            ((bst - 350) / 250.0).coerceIn(0.0, 1.0) * 0.55
        }
        // Um exemplar com IVs ruins vale um pouco menos que um perfeito.
        val quality = c.owned?.let { 0.9 + 0.1 * it.ivTotal / 186.0 } ?: 1.0
        return base * quality
    }

    private fun likelyMoves(c: Candidate, entry: MetaEntry?): List<String> {
        if (entry != null) {
            val fromMeta = entry.moves.filter { it.value >= 0.10 }.keys
                .filter { c.legalMoves.isEmpty() || it in c.legalMoves }
            if (fromMeta.isNotEmpty()) return fromMeta
        }
        // Sem dados competitivos: melhor golpe de cada tipo proprio + utilitarios que ele aprende.
        val atk = c.baseStats[StatKey.ATK] ?: 0
        val spa = c.baseStats[StatKey.SPA] ?: 0
        val preferred = if (atk >= spa) MoveCategory.PHYSICAL else MoveCategory.SPECIAL
        val known = c.legalMoves.mapNotNull { moves[it] }
        val stabs = c.types.mapNotNull { type ->
            known.filter { it.type == type && it.category == preferred && it.power >= 60 }
                .maxByOrNull { it.power }?.id
        }
        val utility = c.legalMoves.filter { it in UTILITY_MOVES }
        return stabs + utility
    }

    private fun attackTypes(c: Candidate, likely: List<String>): Set<String> {
        val fromMoves = likely.mapNotNull { moves[it] }
            .filter { it.category != MoveCategory.STATUS && it.power >= 60 }
            .mapTo(HashSet()) { it.type }
        // Todo Pokemon competitivo carrega golpes dos proprios tipos.
        return fromMoves + c.types
    }

    private fun roles(c: Candidate, likely: List<String>, ability: String): Set<Role> {
        val s = c.baseStats
        val roles = HashSet<Role>()
        val damaging = likely.mapNotNull { moves[it] }.filter { it.category != MoveCategory.STATUS }
        val physical = damaging.count { it.category == MoveCategory.PHYSICAL }
        val special = damaging.count { it.category == MoveCategory.SPECIAL }
        val atk = s[StatKey.ATK] ?: 0
        val spa = s[StatKey.SPA] ?: 0
        if (physical + special == 0) {
            roles += if (atk >= spa) Role.PHYSICAL else Role.SPECIAL
        } else {
            if (physical >= special) roles += Role.PHYSICAL
            if (special >= physical) roles += Role.SPECIAL
        }
        if ((s[StatKey.SPE] ?: 0) >= 100) roles += Role.FAST
        val bulk = 2 * (s[StatKey.HP] ?: 0) + (s[StatKey.DEF] ?: 0) + (s[StatKey.SPD] ?: 0)
        if (bulk >= 380) roles += Role.BULKY
        if (likely.any { it in HAZARD_MOVES }) roles += Role.HAZARDS
        if (likely.any { it in REMOVAL_MOVES }) roles += Role.REMOVAL
        if (likely.any { it in PIVOT_MOVES }) roles += Role.PIVOT
        if (likely.any { it in SETUP_MOVES }) roles += Role.SETUP
        if (likely.any { it in RECOVERY_MOVES }) roles += Role.RECOVERY
        if (damaging.any { it.priority > 0 } || likely.any { it in PRIORITY_MOVES }) roles += Role.PRIORITY

        // Duplas: o snapshot de uso pode ser de singles, entao tambem conta o que ele aprende.
        val doublesPool = if (format == Format.DOUBLES) likely + c.legalMoves.filter { it in DOUBLES_KEY_MOVES } else likely
        if (doublesPool.any { it in SPEED_CONTROL_MOVES }) roles += Role.SPEED_CONTROL
        if ("fakeout" in doublesPool) roles += Role.FAKE_OUT
        if (doublesPool.any { it in REDIRECTION_MOVES }) roles += Role.REDIRECTION
        if (ability == "intimidate") roles += Role.INTIMIDATE
        if (doublesPool.mapNotNull { moves[it] }.any { it.spread && it.category != MoveCategory.STATUS && it.power >= 60 }) {
            roles += Role.SPREAD
        }
        return roles
    }

    companion object {
        /** Indice = tier do snapshot (0 = Ubers ... 6 = ZU). */
        private val TIER_BASE = listOf(1.0, 0.9, 0.75, 0.65, 0.55, 0.45, 0.35)

        val HAZARD_MOVES = setOf("stealthrock", "spikes", "toxicspikes", "stickyweb", "ceaselessedge", "stoneaxe")
        val REMOVAL_MOVES = setOf("rapidspin", "defog", "mortalspin", "tidyup", "courtchange")
        val PIVOT_MOVES = setOf("uturn", "voltswitch", "flipturn", "partingshot", "teleport", "chillyreception", "batonpass", "shedtail")
        val SETUP_MOVES = setOf(
            "swordsdance", "nastyplot", "dragondance", "calmmind", "quiverdance", "bulkup", "shellsmash",
            "irondefense", "agility", "rockpolish", "shiftgear", "tailglow", "coil", "curse", "bellydrum",
            "victorydance", "trailblaze", "torchsong", "growth", "workup", "autotomize", "tidyup",
        )
        val RECOVERY_MOVES = setOf(
            "recover", "roost", "softboiled", "slackoff", "moonlight", "morningsun", "synthesis", "wish",
            "milkdrink", "shoreup", "strengthsap", "rest", "healorder", "junglehealing", "leechseed", "painsplit",
        )
        val PRIORITY_MOVES = setOf(
            "extremespeed", "suckerpunch", "machpunch", "bulletpunch", "iceshard", "aquajet", "shadowsneak",
            "grassyglide", "quickattack", "vacuumwave", "jetpunch", "thunderclap", "accelerock", "firstimpression",
        )
        val UTILITY_MOVES = HAZARD_MOVES + REMOVAL_MOVES + PIVOT_MOVES + SETUP_MOVES + RECOVERY_MOVES

        /** Setup que favorece cada lado; os neutros servem para os dois. */
        val PHYSICAL_SETUP = setOf("swordsdance", "dragondance", "bulkup", "shiftgear", "coil", "bellydrum", "victorydance", "curse", "howl", "honeclaws", "tidyup")
        val SPECIAL_SETUP = setOf("nastyplot", "calmmind", "quiverdance", "tailglow", "geomancy", "growth", "takeheart")
        val NEUTRAL_SETUP = setOf("shellsmash", "agility", "rockpolish", "autotomize", "workup")

        val STATUS_MOVES = setOf(
            "willowisp", "thunderwave", "toxic", "spore", "sleeppowder", "yawn", "glare", "stunspore",
            "encore", "taunt", "trick", "switcheroo", "haze", "clearsmog", "roar", "whirlwind", "destinybond",
            "healbell", "aromatherapy", "wish", "protect", "substitute", "knockoff", "nuzzle",
        )
        val SCREEN_MOVES = setOf("reflect", "lightscreen", "auroraveil")
        val PROTECT_MOVES = setOf("protect", "detect", "spikyshield", "kingsshield", "banefulbunker", "silktrap", "burningbulwark", "obstruct")
        val SPEED_CONTROL_MOVES = setOf("tailwind", "trickroom", "icywind", "electroweb", "thunderwave", "bulldoze", "rocktomb", "glare", "nuzzle", "scaryface")
        val REDIRECTION_MOVES = setOf("followme", "ragepowder", "allyswitch")
        val DOUBLES_SUPPORT = setOf("helpinghand", "wideguard", "quickguard", "beatup", "pollenpuff", "lifedew", "coaching", "afteryou")
        val DOUBLES_KEY_MOVES = SPEED_CONTROL_MOVES + REDIRECTION_MOVES + DOUBLES_SUPPORT + PROTECT_MOVES + "fakeout"

        /** Golpes fortes no papel, mas com custo que as heuristicas nao sabem avaliar. */
        val DRAWBACK_MOVES = setOf(
            "explosion", "selfdestruct", "mistyexplosion", "finalgambit", "focuspunch", "dreameater", "lastresort",
            "hyperbeam", "gigaimpact", "frenzyplant", "blastburn", "hydrocannon", "rockwrecker", "roaroftime",
            "prismaticlaser", "eternabeam", "meteorassault", "skyattack", "solarbeam", "solarblade", "skullbash",
            "razorwind", "bounce", "fly", "dig", "dive", "futuresight", "doomdesire", "belch", "synchronoise",
            "steelroller", "burnup", "doubleshock", "snore", "naturalgift", "fling", "meteorbeam", "geomancy",
            "freezeshock", "iceburn", "shadowforce", "phantomforce", "electroshot", "hyperspacefury",
        )
    }
}

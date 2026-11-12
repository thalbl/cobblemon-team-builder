package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import dev.thal.teambuilder.Config
import dev.thal.teambuilder.core.BuildOptions
import dev.thal.teambuilder.core.ChecklistItemView
import dev.thal.teambuilder.core.CoreView
import dev.thal.teambuilder.core.Cores
import dev.thal.teambuilder.core.PairView
import dev.thal.teambuilder.core.CounterView
import dev.thal.teambuilder.core.MoveStatus
import dev.thal.teambuilder.core.ThreatView
import dev.thal.teambuilder.core.TrainerEnemyView
import dev.thal.teambuilder.core.TrainerSummary
import dev.thal.teambuilder.core.TrainerView
import dev.thal.teambuilder.core.BuilderRequest
import dev.thal.teambuilder.core.BuilderState
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.FormView
import dev.thal.teambuilder.core.Lang
import dev.thal.teambuilder.core.MateSet
import dev.thal.teambuilder.core.MoveView
import dev.thal.teambuilder.core.NameResolver
import dev.thal.teambuilder.core.PickView
import dev.thal.teambuilder.core.Profile
import dev.thal.teambuilder.core.RaidBossInfo
import dev.thal.teambuilder.core.RaidPickView
import dev.thal.teambuilder.core.RaidView
import dev.thal.teambuilder.core.RoleView
import dev.thal.teambuilder.core.SearchResult
import dev.thal.teambuilder.core.SetAdvice
import dev.thal.teambuilder.core.SetRole
import dev.thal.teambuilder.core.SetView
import dev.thal.teambuilder.core.SlotView
import dev.thal.teambuilder.core.Source
import dev.thal.teambuilder.core.StatKey
import dev.thal.teambuilder.core.SwapView
import dev.thal.teambuilder.core.TeamBuilderEngine
import dev.thal.teambuilder.core.TeamView
import dev.thal.teambuilder.core.TypeBalanceView
import dev.thal.teambuilder.core.TypeChart
import dev.thal.teambuilder.core.toCandidate
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Junta catalogo, PC do jogador e motor. Usado pelos comandos e pela tela. */
object TeamBuilderService {
    const val TEAM_SIZE = 6
    private const val UUID_PREFIX = "uuid:"

    /** Quantos times completos a aba Times sugere em cada coluna. */
    const val SUGGESTED_TEAMS = 5
    private val SEARCH_LIMIT get() = Config.current.searchResults.coerceIn(2, 20)
    private val VITAMINS = listOf("hpup", "protein", "iron", "calcium", "zinc", "carbos")

    /** Opcoes de cada jogador; valem ate o servidor reiniciar. */
    private val playerOptions = ConcurrentHashMap<UUID, BuildOptions>()

    fun options(player: ServerPlayer): BuildOptions = playerOptions[player.uuid] ?: Config.current.let { c ->
        BuildOptions(
            allowLegendaries = c.defaultLegendaries, allowMegas = c.defaultMegas,
            format = if (c.defaultDoubles) Format.DOUBLES else Format.SINGLES,
        )
    }

    fun setOptions(
        player: ServerPlayer, legendaries: Boolean? = null, megas: Boolean? = null, doubles: Boolean? = null,
    ): BuildOptions {
        val current = options(player)
        val updated = current.copy(
            allowLegendaries = legendaries ?: current.allowLegendaries,
            allowMegas = megas ?: current.allowMegas,
            format = doubles?.let { if (it) Format.DOUBLES else Format.SINGLES } ?: current.format,
        )
        playerOptions[player.uuid] = updated
        return updated
    }

    /** Idioma do cliente do jogador (portugues ou ingles). */
    fun lang(player: ServerPlayer): Lang = Lang.of(runCatching { player.clientInformation().language() }.getOrNull())

    class Session(val catalog: Catalog, val owned: List<Profile>, val options: BuildOptions, val lang: Lang) {
        val engine: TeamBuilderEngine = catalog.engine(options, lang)

        /** Pokemon reais do jogador, sem as variantes mega. */
        val real: List<Profile> get() = owned.filter { !it.isMega }
        val party: List<Profile> get() = real.filter { it.candidate.owned?.source == Source.PARTY }

        /**
         * Resolve nomes digitados. Prefere um exemplar do jogador que ainda nao esteja
         * em [taken]; senao usa a especie do catalogo. Devolve tambem os nomes nao reconhecidos.
         */
        fun resolveNames(text: String, taken: List<Profile> = emptyList()): Pair<List<Profile>, List<String>> {
            val found = ArrayList<Profile>()
            val unknown = ArrayList<String>()
            for (match in catalog.resolver.resolveAll(text)) {
                val id = match.id
                if (id == null) {
                    unknown += match.raw
                    continue
                }
                val used = (taken + found).mapNotNullTo(HashSet()) { it.candidate.owned?.uuid }
                val mine = owned.filter { it.candidate.owned?.uuid !in used }
                val profile = mine.firstOrNull { it.id == id }
                    ?: mine.firstOrNull { it.speciesId == id && !it.isMega }
                    ?: catalog.all[id]
                if (profile != null) found += profile else unknown += match.raw
            }
            return found to unknown
        }

        fun byKey(key: String): Profile? =
            if (key.startsWith(UUID_PREFIX)) owned.firstOrNull { key(it) == key } else catalog.all[key]
    }

    fun session(player: ServerPlayer): Session {
        val options = options(player)
        val catalog = Catalog.get(options.format)
        val owned = ArrayList<Profile>()
        for (mine in StorageReader.read(player)) {
            owned += catalog.profiles.build(mine.toCandidate())
            // Cada Pokemon do jogador tambem pode entrar como a sua mega.
            for (mega in catalog.megasBySpecies[mine.speciesId].orEmpty()) {
                owned += catalog.profiles.build(
                    mega.candidate.copy(owned = mine, legalMoves = mine.legalMoves, learnMethods = mine.learnMethods)
                )
            }
        }
        return Session(catalog, owned, options, lang(player))
    }

    // ------------------------------------------------------------------ raid

    /** Chefe pedido: detectado pelo Raid Dens quando [query] esta vazio, senao montado do texto. */
    fun raidBoss(player: ServerPlayer, catalog: Catalog, query: String, lang: Lang = lang(player)): Pair<RaidBossInfo?, String?> {
        if (query.isBlank()) {
            val boss = RaidLookup.detect(player, catalog)
            return when {
                boss != null -> boss to null
                !RaidLookup.installed -> null to lang.t("raid.not_installed")
                else -> null to lang.t("raid.none_nearby")
            }
        }
        val boss = RaidLookup.manual(query, catalog)
        return boss to if (boss == null) lang.t("raid.unknown", query) else null
    }

    fun raidView(s: Session, boss: RaidBossInfo): RaidView {
        fun picks(pool: Collection<Profile>) = s.catalog.raids.rank(boss, pool.filter(s.engine::allowed), 6).map {
            RaidPickView(slot(it.pick, s), it.score, it.move.orEmpty(), it.moveReady, it.effectiveness, it.worstTaken)
        }
        return RaidView(
            boss = boss.name, types = boss.types, teraType = boss.teraType.orEmpty(), moves = boss.moves,
            stars = boss.stars, feature = boss.feature, partySize = boss.partySize,
            species = boss.species, aspects = boss.aspects.toList(),
            pcPicks = picks(s.owned), allPicks = picks(s.catalog.all.values),
        )
    }

    // ----------------------------------------------------------------- estado

    /** Calcula tudo que a tela mostra para o time pedido. */
    fun state(player: ServerPlayer, request: BuilderRequest): BuilderState {
        if (request.legendaries != null || request.megas != null || request.doubles != null) {
            setOptions(player, request.legendaries, request.megas, request.doubles)
        }
        var s = session(player)
        val lang = s.lang
        val team = ArrayList<Profile>()
        if (request.useParty) team += s.party else team += request.keys.mapNotNull(s::byKey)

        // Funcao escolhida por chave; quem entrar agora usa a recomendada.
        val chosenByKey = HashMap<String, SetRole>()
        if (!request.useParty) {
            request.keys.forEachIndexed { i, k -> SetRole.of(request.roles.getOrNull(i))?.let { chosenByKey[k] = it } }
        }

        var error: String? = null
        var notice: String? = null
        val add = request.add?.trim().orEmpty()
        if (add.isNotEmpty()) {
            val (found, unknown) = s.resolveNames(add, team)
            for (p in found) {
                when {
                    team.size >= TEAM_SIZE -> error = lang.t("error.team_full", TEAM_SIZE)
                    team.any { it.speciesId == p.speciesId } -> error = lang.t("error.already_in_team", p.name)
                    else -> team += p
                }
            }
            if (unknown.isNotEmpty()) error = lang.t("error.unknown_names", unknown.joinToString())
        }

        if (request.apply) {
            val (ok, message) = applyTeam(player, team, lang)
            if (ok) notice = message else error = message
            // O party mudou: relê o armazenamento para atualizar quem esta onde.
            val keys = team.map(::key)
            s = session(player)
            team.clear()
            team += keys.mapNotNull(s::byKey)
        }

        var raid: RaidView? = null
        if (request.raid != null) {
            val (boss, problem) = raidBoss(player, s.catalog, request.raid, lang)
            if (boss != null) raid = raidView(s, boss) else error = problem
        }

        val trainer = request.trainer?.let { trainerView(s, team, it) }
        if (request.trainer != null && trainer == null) error = lang.t("trainer.unknown")

        val engine = s.engine
        val everything = s.catalog.all.values
        val b = s.catalog.scorer.score(team)
        val full = team.size >= TEAM_SIZE
        val normal = raid == null && trainer == null
        val threats = if (!normal || team.isEmpty()) emptyList() else {
            val list = s.catalog.threats.threats(team, everything.filter(engine::allowed), Config.current.threats.coerceIn(1, 20))
            val top = list.firstOrNull()?.score ?: 1.0
            list.map { t ->
                ThreatView(
                    slot(t.threat, s), (100 * t.score / top).toInt(), t.weak.map { it.name }, t.walls.map { it.name },
                    t.answers.map { it.name }, Lang.types(t.hittingTypes),
                )
            }
        }

        fun teams(pool: Collection<Profile>) =
            if (full || !normal) emptyList()
            else engine.complete(team, pool, SUGGESTED_TEAMS, width = 32).filter { it.added.isNotEmpty() }.map { t ->
                TeamView(t.members.map(::key), t.added.map { slot(it, s) }, t.breakdown.total)
            }

        fun swaps(pool: Collection<Profile>) =
            if (team.size < 2 || !normal) emptyList()
            else engine.suggestSwaps(team, pool, 5).map { SwapView(key(it.out), it.out.name, slot(it.pick, s), it.gain, it.reasons) }

        fun picks(pool: Collection<Profile>) =
            if (full || !normal) emptyList()
            else engine.suggestNext(team, pool, 5).map { PickView(slot(it.pick, s), it.gain, it.reasons) }

        val chosen = team.map { chosenByKey[key(it)] ?: chosenByKey[baseKey(it, s)] }
        val sets = teamSets(s, team, chosen, inventory(player))
        // Duplas do 2v2, com os golpes dos sets sugeridos.
        val plan = if (s.options.format != Format.DOUBLES || trainer != null || raid != null) null
            else s.catalog.pairs.plan(team, sets.map { set -> set.moves.map { it.id } }, lang)
        return BuilderState(
            team = team.map { slot(it, s, withForms = true) },
            sets = sets,
            total = b.total, strength = b.strength, defense = b.defense,
            offense = b.offense, roles = b.roles, synergy = b.synergy,
            weaknesses = engine.weaknesses(team),
            typeBalance = TypeChart.TYPES.mapIndexed { i, type ->
                val (weak, resist) = s.catalog.scorer.exposure(team, i)
                TypeBalanceView(type, weak, resist, team.map { it.defense[i] })
            },
            pcPicks = picks(s.owned), allPicks = picks(everything),
            pcTeams = teams(s.owned), allTeams = teams(everything),
            pcSwaps = swaps(s.owned), allSwaps = swaps(everything),
            ownedCount = s.real.size,
            raid = raid,
            legendaries = s.options.allowLegendaries,
            megas = s.options.allowMegas,
            doubles = s.options.format == Format.DOUBLES,
            cores = Cores.active(team).map { c ->
                CoreView(c.core.id, c.core.label(lang), c.setters.map { it.name }, c.abusers.map { it.name })
            },
            coresScore = b.cores,
            threats = threats,
            pairs = plan?.pairs.orEmpty().mapIndexed { k, p -> PairView(p.a, p.b, p.score, k == 0, p.good, p.bad) },
            pairMatrix = plan?.matrix.orEmpty(),
            trainer = trainer,
            error = error,
            notice = notice,
        )
    }

    /**
     * Sets de todo o time. Em tres passos, para que a funcao de cada membro influencie os
     * golpes dos outros (ex.: se alguem ja poe hazards, os demais nao precisam):
     *  1. funcao escolhida (ou a recomendada pelos stats) e golpes provaveis de cada um;
     *  2. recomendacao refeita sabendo os sets dos companheiros;
     *  3. sets finais, e o set que cada funcao alternativa daria.
     */
    private fun teamSets(s: Session, team: List<Profile>, chosen: List<SetRole?>, inventory: Map<String, Int>): List<SetView> {
        if (team.isEmpty()) return emptyList()
        val advisor = s.catalog.advisor
        val lang = s.lang
        fun mates(i: Int, roles: List<SetRole>, moves: List<List<String>>) =
            team.indices.filter { it != i }.map { MateSet(team[it], roles[it], moves[it]) }

        val roles1 = team.mapIndexed { i, p -> chosen[i] ?: advisor.recommend(p, emptyList()) }
        val likely = team.map { it.likelyMoves }
        val sets1 = team.indices.map { i -> advisor.adviseWith(team[i], mates(i, roles1, likely), roles1[i], lang) }
        val moves1 = sets1.map { a -> a.moves.map { it.id } }

        val recommended = team.indices.map { i -> advisor.recommend(team[i], mates(i, roles1, moves1)) }
        val roles2 = team.indices.map { i -> chosen[i] ?: recommended[i] }
        val moves2 = team.indices.map { i ->
            if (roles2[i] == sets1[i].role) moves1[i]
            else advisor.adviseWith(team[i], mates(i, roles1, moves1), roles2[i], lang).moves.map { it.id }
        }

        return team.indices.map { i ->
            val m = mates(i, roles2, moves2)
            val options = advisor.roles(team[i]).map { r -> r to advisor.adviseWith(team[i], m, r, lang) }
            val final = options.firstOrNull { it.first == roles2[i] }?.second ?: advisor.adviseWith(team[i], m, roles2[i], lang)
            val views = options.map { (r, a) ->
                RoleView(r.id, r.label(lang), r == recommended[i], a.nature, a.evs, a.moves.map { it.id }, a.items.firstOrNull().orEmpty())
            }
            setView(final, s.catalog, lang, team[i], views).copy(checklist = checklist(team[i], final, inventory, lang))
        }
    }

    // -------------------------------------------------------------- checklist

    /** Itens no inventario do jogador, por id sem separadores ("timidmint" -> 2). */
    private fun inventory(player: ServerPlayer): Map<String, Int> {
        val out = HashMap<String, Int>()
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (!stack.isEmpty) out.merge(StorageReader.itemId(stack), stack.count, Int::plus)
        }
        return out
    }

    /** "charizardmegax" -> "charizarditex"; "gyaradosmega" -> "gyaradosite". */
    private fun megaStone(p: Profile): String {
        val suffix = p.id.substringAfter("mega", "").takeIf { it == "x" || it == "y" }.orEmpty()
        return p.speciesId + "ite" + suffix
    }

    /** O que falta para o exemplar ficar igual ao set: golpes, natureza, habilidade, item, EVs e IVs. */
    private fun checklist(p: Profile, a: SetAdvice, inv: Map<String, Int>, lang: Lang): List<ChecklistItemView> {
        val mine = p.candidate.owned ?: return listOf(ChecklistItemView("obtain", p.id, "missing"))
        fun have(id: String) = (inv[id] ?: 0) > 0
        fun state(done: Boolean, available: Boolean) = if (done) "done" else if (available) "have" else "missing"
        val out = ArrayList<ChecklistItemView>()
        for (m in a.moves) {
            val st = when (m.status) {
                MoveStatus.KNOWN -> "done"
                MoveStatus.SWAP -> "have"
                MoveStatus.LEARN -> "missing"
            }
            out += ChecklistItemView("move", m.id, st, if (m.status == MoveStatus.LEARN) StorageReader.methodText(m.method, lang) else "")
        }
        if (a.nature.isNotEmpty()) {
            val mint = a.nature + "mint"
            out += ChecklistItemView("nature", a.nature, state(mine.nature == a.nature, have(mint)), mint)
        }
        if (a.ability.isNotEmpty()) {
            out += ChecklistItemView("ability", a.ability, state(mine.ability == a.ability, have("abilitycapsule") || have("abilitypatch")))
        }
        if (p.isMega) {
            val stone = megaStone(p)
            out += ChecklistItemView("mega", stone, state(mine.heldItem == stone, have(stone)))
        } else {
            a.items.firstOrNull()?.let { item -> out += ChecklistItemView("item", item, state(mine.heldItem == item, have(item))) }
        }
        for (i in 0 until 6) {
            val target = a.evs.getOrElse(i) { 0 }
            val current = mine.evs[StatKey.entries[i]] ?: 0
            if (target == 0 && current == 0) continue
            val st = when {
                kotlin.math.abs(current - target) <= 4 -> "done"
                current > target -> "info"
                have(VITAMINS[i]) -> "have"
                else -> "missing"
            }
            out += ChecklistItemView("ev", "$i", st, "$current/$target")
        }
        for (i in a.ivPriority.take(3)) {
            out += ChecklistItemView("iv", "$i", "info", "${mine.ivs[StatKey.entries[i]] ?: 0}")
        }
        return out
    }

    // ------------------------------------------------------------ treinadores

    private fun trainerView(s: Session, team: List<Profile>, id: String): TrainerView? {
        val t = Trainers.all[id] ?: return null
        val enemies = t.team.mapNotNull { m -> Trainers.enemy(m, s.catalog) }
        val battles = s.catalog.battles
        val answers = battles.answers(enemies, team)
        fun picks(pool: Collection<Profile>) = battles.rank(enemies, pool.filter(s.engine::allowed), 6).map { c ->
            CounterView(slot(c.pick, s), c.score, c.handles.map { enemies[it].profile.name })
        }
        return TrainerView(
            id = t.id, name = t.name, type = t.typeLabel,
            enemies = enemies.mapIndexed { i, e ->
                TrainerEnemyView(
                    slot(e.profile, s).copy(
                        level = e.level, info = s.lang.t("info.level_short", e.level),
                        ability = e.ability.ifEmpty { e.profile.ability },
                    ),
                    e.level, e.moves, e.ability, e.item, e.nature, answers[i]?.first?.name.orEmpty(),
                )
            },
            pcPicks = picks(s.owned), allPicks = picks(s.catalog.all.values),
        )
    }

    /** Treinadores que combinam com o texto (ou os importantes, sem texto), com o time em miniatura. */
    fun searchTrainers(player: ServerPlayer, query: String): SearchResult {
        val catalog = Catalog.get(options(player).format)
        val trainers = Trainers.search(query).map { t ->
            TrainerSummary(
                t.id, t.name, t.typeLabel, t.maxLevel,
                t.team.mapNotNull { m -> Trainers.enemy(m, catalog) }.map { e ->
                    val c = e.profile.candidate
                    SlotView("", e.profile.name, c.types, emptyList(), false, e.level, c.species, c.aspects.toList())
                },
            )
        }
        return SearchResult(query, kind = "trainer", trainers = trainers)
    }

    fun setView(
        a: SetAdvice, catalog: Catalog, lang: Lang, p: Profile? = null, options: List<RoleView> = emptyList(),
    ) = SetView(
        moves = a.moves.map {
            val info = catalog.moves[it.id]
            MoveView(
                it.id, it.status.name, StorageReader.methodText(it.method, lang), it.note,
                info?.type.orEmpty(), info?.category?.name.orEmpty(), info?.power ?: 0,
            )
        },
        items = a.items, ability = a.ability, nature = a.nature, evs = a.evs,
        notes = a.notes, fromMeta = a.fromMeta,
        role = a.role.id, roleLabel = a.role.label(lang), roleOptions = options,
        ivs = p?.candidate?.owned?.let { mine -> StatKey.entries.map { mine.ivs[it] ?: 0 } }.orEmpty(),
        ivPriority = a.ivPriority, ivUnused = a.ivUnused,
    )

    // ------------------------------------------------------- aplicar ao party

    /**
     * Coloca no party os Pokemon do jogador que estao em [team], na ordem do time.
     * Quem sai do party vai para o PC. Pokemon do catalogo (que o jogador nao tem) sao ignorados.
     */
    fun applyTeam(player: ServerPlayer, team: List<Profile>, lang: Lang): Pair<Boolean, String> {
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) return false to lang.t("apply.in_battle")
        val wanted = team.mapNotNull { it.candidate.owned?.uuid }.distinct().map(UUID::fromString)
        if (wanted.isEmpty()) return false to lang.t("apply.none_owned")
        val party = Cobblemon.storage.getParty(player)
        val pc = Cobblemon.storage.getPC(player)
        var moved = 0
        for (id in wanted) {
            if (party.any { it.uuid == id }) continue
            val mon = pc.firstOrNull { it.uuid == id } ?: continue
            if (party.occupied() >= TEAM_SIZE) {
                val out = party.firstOrNull { it.uuid !in wanted } ?: break
                if (out.entity != null) out.recall()
                if (!party.remove(out)) break
                if (!pc.add(out)) {
                    party.add(out)
                    return false to lang.t("apply.pc_full")
                }
            }
            if (!pc.remove(mon)) continue
            if (!party.add(mon)) {
                pc.add(mon)
                continue
            }
            moved++
        }
        // Mesma ordem dos slots da tela.
        for ((i, id) in wanted.withIndex()) {
            val j = party.toGappyList().indexOfFirst { it?.uuid == id }
            if (j >= 0 && j != i) party.swap(i, j)
        }
        return true to lang.t("apply.done", moved)
    }

    // ------------------------------------------------------------------ busca

    /** Pokemon cujo nome combina com o que o jogador esta digitando: os dele primeiro. */
    fun search(player: ServerPlayer, query: String): SearchResult {
        val q = NameResolver.normalize(query)
        if (q.length < 2) return SearchResult(query)
        val s = session(player)

        fun rank(p: Profile): Int? {
            val name = NameResolver.normalize(p.name)
            return when {
                name.startsWith(q) || p.id.startsWith(q) -> 0
                q in name || q in p.id -> 1
                else -> null
            }
        }

        val mine = s.real.mapNotNull { p -> rank(p)?.let { it to p } }
            .sortedWith(compareBy<Pair<Int, Profile>> { it.first }.thenByDescending { it.second.candidate.owned?.level ?: 0 })
            .map { it.second }
            .take(SEARCH_LIMIT / 2)
        val fuzzy = s.catalog.resolver.resolveOne(query)
        val catalog = s.catalog.all.values
            .mapNotNull { p -> (rank(p) ?: if (p.id == fuzzy) 2 else null)?.let { it to p } }
            .sortedWith(
                compareBy<Pair<Int, Profile>> { it.first }
                    .thenBy { it.second.isMega }
                    .thenByDescending { it.second.meta?.usage ?: 0.0 }
                    .thenBy { it.second.name.length }
            )
            .map { it.second }
            .take(SEARCH_LIMIT - mine.size)
        return SearchResult(query, (mine + catalog).map { slot(it, s) })
    }

    // ------------------------------------------------------------------ views

    /** Um Pokemon do jogador e identificado pelo uuid; a variante mega leva tambem o id da forma. */
    fun key(p: Profile): String {
        val mine = p.candidate.owned ?: return p.id
        return UUID_PREFIX + mine.uuid + if (p.isMega) "|${p.id}" else ""
    }

    /** Chave da forma normal: a funcao escolhida acompanha o Pokemon quando ele vira mega. */
    private fun baseKey(p: Profile, s: Session): String {
        val mine = p.candidate.owned
            ?: return if (p.isMega) s.catalog.baseBySpecies[p.speciesId]?.id ?: p.speciesId else p.id
        return UUID_PREFIX + mine.uuid
    }

    private fun slot(p: Profile, s: Session, withForms: Boolean = false) = SlotView(
        key = key(p),
        name = p.name,
        types = p.candidate.types,
        roles = p.roles.map { it.label(s.lang) },
        owned = p.isOwned,
        level = p.candidate.owned?.level ?: 0,
        species = p.candidate.species,
        aspects = p.candidate.aspects.toList(),
        info = info(p, s.lang),
        mega = p.isMega,
        forms = if (withForms) forms(p, s) else emptyList(),
        inParty = p.candidate.owned?.source == Source.PARTY,
        ability = p.ability,
    )

    /** Forma normal + megas da especie, para alternar direto no slot. */
    private fun forms(p: Profile, s: Session): List<FormView> {
        val megas = s.catalog.megasBySpecies[p.speciesId].orEmpty()
        if (megas.isEmpty()) return emptyList()
        val mine = p.candidate.owned
        val baseKey = if (mine != null) UUID_PREFIX + mine.uuid else (if (p.isMega) s.catalog.baseBySpecies[p.speciesId]?.id else p.id) ?: return emptyList()
        val out = ArrayList<FormView>()
        out += FormView(baseKey, s.lang.t("form.base"), false)
        for (mega in megas.sortedBy { it.id }) {
            val key = if (mine != null) "$UUID_PREFIX${mine.uuid}|${mega.id}" else mega.id
            out += FormView(key, megaLabel(mega), true)
        }
        return out
    }

    /** "Charizard-Mega-X" -> "Mega X". */
    private fun megaLabel(mega: Profile): String {
        val raw = mega.name.substringAfter('-', "Mega").replace('-', ' ').replace('_', ' ').trim()
        return raw.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun info(p: Profile, lang: Lang): String {
        val mine = p.candidate.owned
        if (mine != null) return lang.t("info.owned", mine.level, mine.ivTotal, mine.nature)
        val meta = p.meta ?: return lang.t("info.no_usage")
        return lang.t("info.usage", meta.format.removePrefix("gen9"), "%.1f".format(meta.usage * 100))
    }
}

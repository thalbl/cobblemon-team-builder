package dev.thal.teambuilder.core

data class Suggestion(val pick: Profile, val gain: Double, val reasons: List<String>)

data class TeamResult(val members: List<Profile>, val added: List<Profile>, val breakdown: Breakdown)

data class Swap(val out: Profile, val pick: Profile, val gain: Double, val reasons: List<String>)

data class BuildOptions(
    /**
     * Desligado: nao sugere lendarios/miticos (nem os do jogador) nem Pokemon de tier Ubers
     * que ele nao possui. Quem ja esta no time nunca e removido por esta opcao.
     */
    val allowLegendaries: Boolean = false,
    /** Sugere megas, no maximo uma por time. */
    val allowMegas: Boolean = true,
    val teamSize: Int = 6,
    val format: Format = Format.SINGLES,
)

/**
 * Motor de sugestao. Toda decisao sai de uma unica nota de time ([TeamScorer]):
 * sugerir o proximo slot, completar o time e propor trocas sao so formas diferentes
 * de comparar notas.
 */
class TeamBuilderEngine(
    private val scorer: TeamScorer,
    private val options: BuildOptions = BuildOptions(),
    private val lang: Lang = Lang.EN,
) {
    /** Melhores opcoes para o proximo slot, com o ganho de nota e o motivo. */
    fun suggestNext(team: List<Profile>, pool: Collection<Profile>, limit: Int = 5): List<Suggestion> {
        val base = scorer.score(team).total
        return usable(team, pool)
            .map { it to scorer.score(team + it).total - base }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (p, gain) -> Suggestion(p, gain, reasons(team, p)) }
    }

    /**
     * Completa o time ate [BuildOptions.teamSize] e devolve ate [count] times diferentes entre si.
     * Usa beam search: a cada slot mantem os [width] melhores times parciais.
     */
    fun complete(team: List<Profile>, pool: Collection<Profile>, count: Int = 3, width: Int = 24): List<TeamResult> {
        if (team.size >= options.teamSize) return listOf(TeamResult(team, emptyList(), scorer.score(team)))
        // Reduz o pool aos candidatos mais promissores para o time atual.
        val base = scorer.score(team).total
        val shortlist = usable(team, pool)
            .sortedByDescending { scorer.score(team + it).total - base }
            .take(200)

        var beam = listOf(team)
        while (beam.first().size < options.teamSize) {
            val next = HashMap<Set<String>, Pair<List<Profile>, Double>>()
            for (partial in beam) {
                for (p in shortlist) {
                    if (!fits(partial, p)) continue
                    val grown = partial + p
                    val key = grown.mapTo(HashSet()) { it.id }
                    if (key !in next) next[key] = grown to scorer.score(grown).total
                }
            }
            if (next.isEmpty()) break // pool pequeno demais para preencher todos os slots
            beam = next.values.sortedByDescending { it.second }.take(width).map { it.first }
        }

        // Escolhe times variados: cada novo time pode repetir no maximo metade das adicoes dos anteriores.
        val slots = beam.first().size - team.size
        val chosen = ArrayList<List<Profile>>()
        for (maxOverlap in listOf(slots / 2, slots - 1)) {
            for (candidate in beam) {
                if (chosen.size >= count) break
                if (candidate in chosen) continue
                val added = candidate.drop(team.size).mapTo(HashSet()) { it.id }
                val tooSimilar = chosen.any { other ->
                    other.drop(team.size).count { it.id in added } > maxOverlap
                }
                if (!tooSimilar) chosen += candidate
            }
        }
        return chosen.map { TeamResult(it, it.drop(team.size), scorer.score(it)) }
    }

    /** Trocas de um membro por um candidato do pool que mais aumentam a nota do time. */
    fun suggestSwaps(team: List<Profile>, pool: Collection<Profile>, limit: Int = 5): List<Swap> {
        val base = scorer.score(team).total
        val swaps = ArrayList<Swap>()
        for ((i, out) in team.withIndex()) {
            val rest = team.filterIndexed { j, _ -> j != i }
            for (p in usable(rest, pool)) {
                if (p.id == out.id) continue
                val gain = scorer.score(rest + p).total - base
                if (gain > 0.25) swaps += Swap(out, p, gain, emptyList())
            }
        }
        // Cada candidato aparece uma vez so, na troca em que rende mais.
        return swaps.sortedByDescending { it.gain }
            .distinctBy { it.pick.id }
            .take(limit)
            .map { s -> s.copy(reasons = reasons(team.filter { it !== s.out }, s.pick)) }
    }

    /** Tipos de ataque em que o time tem mais fracos do que resistentes. */
    fun weaknesses(team: List<Profile>): List<String> =
        (0 until TypeChart.COUNT).filter { t ->
            val (weak, resist) = scorer.exposure(team, t)
            weak >= 2 && weak > resist
        }.map { TypeChart.TYPES[it] }

    /** O candidato pode ser sugerido com as opcoes atuais? */
    fun allowed(p: Profile): Boolean {
        if (p.isMega && !options.allowMegas) return false
        if (options.allowLegendaries) return true
        return !p.candidate.legendary && (p.isOwned || p.meta?.tier != 0)
    }

    /** Sem especie repetida e com no maximo uma mega. */
    private fun fits(team: List<Profile>, p: Profile): Boolean =
        team.none { it.speciesId == p.speciesId } && !(p.isMega && team.any { it.isMega })

    private fun usable(team: List<Profile>, pool: Collection<Profile>): List<Profile> =
        pool.filter { allowed(it) && fits(team, it) }

    /** Explica em poucas frases o que [pick] acrescenta a [team]. */
    private fun reasons(team: List<Profile>, pick: Profile): List<String> {
        val out = ArrayList<String>()
        val after = team + pick

        val fixes = ArrayList<String>()
        val stacks = ArrayList<String>()
        for (t in 0 until TypeChart.COUNT) {
            val (weakBefore, resistBefore) = scorer.exposure(team, t)
            val (weakAfter, resistAfter) = scorer.exposure(after, t)
            if (weakBefore > resistBefore && pick.defense[t] < 1.0) fixes += TypeChart.TYPES[t]
            if (pick.defense[t] > 1.0 && weakAfter - resistAfter >= 2) stacks += TypeChart.TYPES[t]
        }
        if (fixes.isNotEmpty()) out += lang.t("reason.resists", Lang.types(fixes))

        val newCoverage = pick.coverageMask and scorer.coverage(team).inv()
        if (team.isNotEmpty() && newCoverage != 0) {
            out += lang.t("reason.hits", Lang.types(TypeChart.typesIn(newCoverage)))
        }

        val teamRoles = team.flatMapTo(HashSet()) { it.roles }
        val notable = if (options.format == Format.DOUBLES) NOTABLE_ROLES + DOUBLES_ROLES else NOTABLE_ROLES
        val newRoles = pick.roles.filter { it !in teamRoles && it in notable }
        if (newRoles.isNotEmpty()) out += lang.t("reason.brings", newRoles.joinToString(", ") { it.label(lang) })

        team.mapNotNull { mate -> (scorer.lift(mate, pick) ?: scorer.lift(pick, mate))?.let { mate to it } }
            .filter { it.second >= 1.5 }
            .maxByOrNull { it.second }
            ?.let { (mate, lift) -> out += lang.t("reason.partner", mate.name, "%.1f".format(lift)) }

        if (stacks.isNotEmpty()) out += lang.t("reason.stacks", Lang.types(stacks))
        return out
    }

    private companion object {
        val NOTABLE_ROLES = setOf(
            Role.HAZARDS, Role.REMOVAL, Role.PIVOT, Role.PRIORITY, Role.SETUP, Role.FAST, Role.BULKY,
        )
        val DOUBLES_ROLES = setOf(Role.SPEED_CONTROL, Role.FAKE_OUT, Role.REDIRECTION, Role.INTIMIDATE, Role.SPREAD)
    }
}

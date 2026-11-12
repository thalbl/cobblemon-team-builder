package dev.thal.teambuilder.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Pesos da nota. Ficam juntos aqui para facilitar o ajuste fino depois dos testes no jogo. */
data class Weights(
    val strength: Double = 10.0,
    /** Multiplica (fracos - resistentes)^2 para cada tipo de ataque em que o time esta exposto. */
    val weakness: Double = 1.5,
    /** Bonus por tipo de ataque em que o time tem pelo menos um resistente/imune. */
    val resistCovered: Double = 0.25,
    /** Bonus por tipo defensor que o time acerta com vantagem. */
    val coverage: Double = 0.5,
    val hazards: Double = 2.0,
    val removal: Double = 1.5,
    val pivot: Double = 1.0,
    val priority: Double = 0.75,
    val setup: Double = 0.75,
    val fast: Double = 1.0,
    val bulky: Double = 1.0,
    val oneSidedOffense: Double = 2.0,
    val synergy: Double = 1.5,
    // Duplas
    val speedControl: Double = 0.0,
    val fakeOut: Double = 0.0,
    val redirection: Double = 0.0,
    val intimidate: Double = 0.0,
    val spread: Double = 0.0,
    /** Por membro que aproveita um nucleo ativo (clima, terreno, Trick Room), ate 3. */
    val core: Double = 0.75,
) {
    companion object {
        val SINGLES = Weights()

        /** Em 2v2 hazards valem pouco; controle de velocidade, Fake Out e golpes em area valem muito. */
        val DOUBLES = Weights(
            hazards = 0.3, removal = 0.0, pivot = 0.5, priority = 0.5, setup = 0.5, fast = 0.75,
            speedControl = 2.0, fakeOut = 1.5, redirection = 1.0, intimidate = 1.25, spread = 0.75, core = 1.0,
        )

        /** Pesos lidos do arquivo de configuracao; sem eles valem os padroes acima. */
        @Volatile
        var configured: Map<Format, Weights> = emptyMap()

        fun of(format: Format) = configured[format] ?: if (format == Format.DOUBLES) DOUBLES else SINGLES
    }
}

data class Breakdown(
    val strength: Double,
    val defense: Double,
    val offense: Double,
    val roles: Double,
    val synergy: Double,
    val cores: Double = 0.0,
) {
    val total: Double get() = strength + defense + offense + roles + synergy + cores
}

class TeamScorer(private val meta: MetaSnapshot, val weights: Weights = Weights()) {

    fun score(team: List<Profile>): Breakdown = Breakdown(
        strength = team.sumOf { it.strength } * weights.strength,
        defense = defense(team),
        offense = Integer.bitCount(coverage(team)) * weights.coverage,
        roles = roles(team),
        synergy = synergy(team),
        cores = Cores.active(team).sumOf { it.value } * weights.core,
    )

    /** (fracos, resistentes ou imunes) do time contra o tipo de ataque [attackType]. */
    fun exposure(team: List<Profile>, attackType: Int): Pair<Int, Int> {
        var weak = 0
        var resist = 0
        for (p in team) {
            val m = p.defense[attackType]
            if (m > 1.0) weak++ else if (m < 1.0) resist++
        }
        return weak to resist
    }

    fun coverage(team: List<Profile>): Int = team.fold(0) { acc, p -> acc or p.coverageMask }

    private fun defense(team: List<Profile>): Double {
        var total = 0.0
        for (t in 0 until TypeChart.COUNT) {
            val (weak, resist) = exposure(team, t)
            val net = max(0, weak - resist)
            total -= net * net * weights.weakness
            if (resist > 0) total += weights.resistCovered
        }
        return total
    }

    private fun roles(team: List<Profile>): Double {
        val w = weights
        fun count(role: Role) = team.count { role in it.roles }
        var total = 0.0
        if (count(Role.HAZARDS) > 0) total += w.hazards
        if (count(Role.REMOVAL) > 0) total += w.removal
        total += min(count(Role.PIVOT), 2) * w.pivot * 0.75
        if (count(Role.PRIORITY) > 0) total += w.priority
        if (count(Role.SETUP) > 0) total += w.setup
        total += min(count(Role.FAST), 2) * w.fast
        total += min(count(Role.BULKY), 2) * w.bulky
        if (team.size >= 3 && (count(Role.PHYSICAL) == 0 || count(Role.SPECIAL) == 0)) total -= w.oneSidedOffense
        if (count(Role.SPEED_CONTROL) > 0) total += w.speedControl
        if (count(Role.FAKE_OUT) > 0) total += w.fakeOut
        if (count(Role.REDIRECTION) > 0) total += w.redirection
        if (count(Role.INTIMIDATE) > 0) total += w.intimidate
        total += min(count(Role.SPREAD), 2) * w.spread
        return total
    }

    private fun synergy(team: List<Profile>): Double {
        var total = 0.0
        for (i in team.indices) for (j in i + 1 until team.size) total += pairSynergy(team[i], team[j])
        return total * weights.synergy
    }

    /** Media do log do lift nas duas direcoes, limitada para um par nao dominar a nota. */
    fun pairSynergy(a: Profile, b: Profile): Double {
        val lifts = listOfNotNull(lift(a, b), lift(b, a))
        if (lifts.isEmpty()) return 0.0
        return lifts.map { ln(it).coerceIn(-1.0, 1.5) }.average()
    }

    fun lift(a: Profile, b: Profile): Double? =
        meta.teammateLift(a.id, b.id) ?: meta.teammateLift(a.speciesId, b.speciesId)
}

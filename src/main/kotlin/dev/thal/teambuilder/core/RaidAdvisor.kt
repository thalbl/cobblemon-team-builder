package dev.thal.teambuilder.core

import kotlin.math.max
import kotlin.math.sqrt

/** O chefe de uma raid, reduzido ao que importa para escolher counters. */
data class RaidBossInfo(
    val name: String,
    /** Tipos da forma do chefe (sem tera). */
    val types: List<String>,
    /** Tipo tera, quando o chefe esta terastalizado: passa a ser o unico tipo defensivo. */
    val teraType: String? = null,
    val moves: List<String> = emptyList(),
    val ability: String = "",
    val stars: Int = 0,
    val feature: String = "",
    val partySize: Int = 0,
    val species: String = "",
    val aspects: Set<String> = emptySet(),
)

data class RaidPick(
    val pick: Profile,
    /** 0..100, relativo ao melhor da lista. */
    val score: Int,
    val move: String?,
    val moveReady: Boolean,
    val effectiveness: Double,
    val worstTaken: Double,
)

/**
 * Ordena candidatos contra um chefe de raid. Diferente do time de singles, aqui cada
 * Pokemon e avaliado sozinho: quanto dano causa ao chefe e o quanto aguenta os golpes dele.
 */
class RaidAdvisor(private val moves: Map<String, MoveInfo>) {

    fun rank(boss: RaidBossInfo, pool: Collection<Profile>, limit: Int = 6): List<RaidPick> {
        val tera = boss.teraType?.takeIf { TypeChart.indexOf(it) >= 0 }
        val bossDefense = TypeChart.defensiveProfile(tera?.let(::listOf) ?: boss.types, boss.ability)
        val attackTypes = bossAttackTypes(boss, tera)

        val scored = pool.mapNotNull { p -> evaluate(p, bossDefense, attackTypes) }
            .sortedByDescending { it.first }
            // um exemplar por especie: o melhor entre repetidos e entre forma base/mega
            .distinctBy { it.second.pick.speciesId }
            .take(limit)
        val top = scored.firstOrNull()?.first ?: return emptyList()
        return scored.map { (value, pick) -> pick.copy(score = (100 * value / top).toInt()) }
    }

    /** Valor bruto de [p] contra um oponente (dano causado x quanto aguenta), sem normalizar. */
    fun matchup(boss: RaidBossInfo, p: Profile): Pair<Double, RaidPick>? {
        val tera = boss.teraType?.takeIf { TypeChart.indexOf(it) >= 0 }
        val defense = TypeChart.defensiveProfile(tera?.let(::listOf) ?: boss.types, boss.ability)
        return evaluate(p, defense, bossAttackTypes(boss, tera))
    }

    private fun bossAttackTypes(boss: RaidBossInfo, tera: String?): List<Int> {
        val types = boss.moves.mapNotNull { moves[it] }
            .filter { it.category != MoveCategory.STATUS }
            .mapTo(LinkedHashSet()) { it.type }
        // Tera Blast assume o tipo tera.
        if (tera != null && "terablast" in boss.moves) types += tera
        if (types.isEmpty()) {
            types += boss.types
            if (tera != null) types += tera
        }
        return types.map(TypeChart::indexOf).filter { it >= 0 }
    }

    private fun evaluate(p: Profile, bossDefense: DoubleArray, attackTypes: List<Int>): Pair<Double, RaidPick>? {
        val c = p.candidate
        val level = c.owned?.level ?: 100
        fun stat(key: StatKey) = (2 * (c.baseStats[key] ?: 0) + 31) * level / 100 + 5
        val atk = stat(StatKey.ATK)
        val spa = stat(StatKey.SPA)

        fun value(m: MoveInfo): Double {
            val t = TypeChart.indexOf(m.type)
            if (t < 0) return 0.0
            val stab = if (m.type in c.types) 1.5 else 1.0
            return m.power * stab * bossDefense[t] * (if (m.category == MoveCategory.PHYSICAL) atk else spa)
        }

        fun best(ids: Collection<String>): MoveInfo? = ids.mapNotNull { moves[it] }
            .filter { it.category != MoveCategory.STATUS && it.power >= 40 && it.id !in ProfileBuilder.DRAWBACK_MOVES }
            .maxByOrNull(::value)

        val owned = c.owned
        val ready = owned?.let { best(it.currentMoves + it.accessibleMoves) }
        val any = best(c.legalMoves)
        // Para um Pokemon do jogador, so sugere aprender golpe novo se o ganho for claro.
        val (move, moveReady) = when {
            owned == null -> any to false
            ready != null && (any == null || value(any) <= value(ready) * 1.3) -> ready to true
            any != null -> any to false
            else -> ready to true
        }

        val offense: Double
        val effectiveness: Double
        if (move != null) {
            offense = value(move)
            effectiveness = bossDefense[TypeChart.indexOf(move.type)]
        } else {
            // Sem dados de golpes: assume um golpe de 80 de poder do melhor tipo proprio.
            effectiveness = c.types.map(TypeChart::indexOf).filter { it >= 0 }.maxOfOrNull { bossDefense[it] } ?: 1.0
            offense = 80 * 1.5 * effectiveness * max(atk, spa)
        }
        if (offense <= 0.0) return null

        val taken = attackTypes.map { p.defense[it] }
        val worst = taken.maxOrNull() ?: 1.0
        val average = if (taken.isEmpty()) 1.0 else taken.average()
        val hp = (2 * (c.baseStats[StatKey.HP] ?: 0) + 31) * level / 100 + level + 10
        val bulk = hp * (stat(StatKey.DEF) + stat(StatKey.SPD)) / 2.0
        val survivability = bulk / max(worst * 0.6 + average * 0.4, 0.1)

        val total = offense * sqrt(survivability)
        return total to RaidPick(p, 0, move?.id, moveReady || owned == null, effectiveness, worst)
    }
}

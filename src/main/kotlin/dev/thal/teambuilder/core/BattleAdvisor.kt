package dev.thal.teambuilder.core

/** Um Pokemon do time de um treinador adversario. */
data class EnemyMon(
    val profile: Profile,
    val level: Int,
    val moves: List<String>,
    val ability: String,
    val item: String = "",
    val nature: String = "",
)

data class CounterPick(
    val pick: Profile,
    /** 0..100, relativo ao melhor da lista. */
    val score: Int,
    /** Indices dos adversarios que ele enfrenta bem. */
    val handles: List<Int>,
)

/**
 * Escolhe counters contra um time inteiro (ginasios, Elite 4, campeoes). Cada candidato e
 * comparado a cada adversario como numa raid; a nota final e a media de quanto ele rende
 * contra cada um, em relacao ao melhor candidato para aquele adversario.
 */
class BattleAdvisor(private val raids: RaidAdvisor) {

    private fun asBoss(e: EnemyMon) = RaidBossInfo(
        name = e.profile.name, types = e.profile.candidate.types, moves = e.moves,
        ability = e.ability.ifEmpty { e.profile.ability },
    )

    /** Matriz [candidato][adversario] com o valor bruto de cada confronto. */
    private fun values(enemies: List<EnemyMon>, pool: List<Profile>): Array<DoubleArray> {
        val bosses = enemies.map(::asBoss)
        return Array(pool.size) { i -> DoubleArray(bosses.size) { j -> raids.matchup(bosses[j], pool[i])?.first ?: 0.0 } }
    }

    fun rank(enemies: List<EnemyMon>, pool: Collection<Profile>, limit: Int = 6): List<CounterPick> {
        if (enemies.isEmpty()) return emptyList()
        val list = pool.toList()
        val v = values(enemies, list)
        val best = DoubleArray(enemies.size) { j -> list.indices.maxOfOrNull { v[it][j] } ?: 0.0 }
        val scored = list.indices.map { i ->
            val shares = enemies.indices.map { j -> if (best[j] > 0) v[i][j] / best[j] else 0.0 }
            Triple(list[i], shares.average(), shares.withIndex().filter { it.value >= 0.75 }.map { it.index })
        }
            .sortedByDescending { it.second }
            .distinctBy { it.first.speciesId }
            .take(limit)
        val top = scored.firstOrNull()?.second?.takeIf { it > 0 } ?: return emptyList()
        return scored.map { (p, s, handles) -> CounterPick(p, (100 * s / top).toInt(), handles) }
    }

    /** Para cada adversario, o membro do [team] que melhor o enfrenta e quanto (0..100 do melhor possivel no time). */
    fun answers(enemies: List<EnemyMon>, team: List<Profile>): List<Pair<Profile, Double>?> {
        if (team.isEmpty()) return enemies.map { null }
        val v = values(enemies, team)
        return enemies.indices.map { j ->
            val i = team.indices.maxByOrNull { v[it][j] } ?: return@map null
            if (v[i][j] <= 0) null else team[i] to v[i][j]
        }
    }
}

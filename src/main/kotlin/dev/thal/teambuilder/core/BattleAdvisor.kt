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
 * Quanto o time esta pronto para um treinador.
 *
 * @param percent 0..100, media de [coverage]
 * @param coverage para cada adversario, 0..1: quao bem o melhor membro do time o enfrenta
 * @param teamLevel nivel medio dos Pokemon do jogador no time (0 se nao ha nenhum)
 * @param enemyLevel nivel do Pokemon mais forte do treinador
 */
data class Readiness(val percent: Int, val coverage: List<Double>, val teamLevel: Int, val enemyLevel: Int) {
    /** Adversarios que o time nao responde bem. */
    val unanswered: List<Int> get() = coverage.indices.filter { coverage[it] < WEAK }

    companion object {
        const val WEAK = 0.35
    }
}

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
        return Array(pool.size) { i -> DoubleArray(bosses.size) { j -> raids.matchup(bosses[j], pool[i], enemies[j].level)?.first ?: 0.0 } }
    }

    /**
     * Cobertura de um adversario por um membro: golpe que ele tem contra o adversario, o pior que
     * leva dele e a diferenca de nivel. Valores absolutos (nao relativos ao melhor do PC), para
     * dizer "pronto" ou "nao pronto" mesmo quando nenhum Pokemon do jogador serve.
     */
    private fun coverage(e: EnemyMon, p: Profile): Double {
        val pick = raids.matchup(asBoss(e), p, e.level)?.second ?: return 0.0
        val offense = when {
            pick.effectiveness >= 2.0 -> 1.0
            pick.effectiveness >= 1.0 -> 0.65
            pick.effectiveness > 0.0 -> 0.3
            else -> 0.0
        }
        val defense = when {
            pick.worstTaken <= 0.5 -> 1.0
            pick.worstTaken <= 1.0 -> 0.8
            pick.worstTaken <= 2.0 -> 0.5
            else -> 0.25
        }
        val level = p.candidate.owned?.level ?: e.level
        val ratio = if (e.level > 0) (level.toDouble() / e.level).coerceAtMost(1.0) else 1.0
        return offense * defense * ratio * ratio
    }

    fun readiness(enemies: List<EnemyMon>, team: List<Profile>): Readiness? {
        if (enemies.isEmpty() || team.isEmpty()) return null
        val coverage = enemies.map { e -> team.maxOf { coverage(e, it) } }
        val levels = team.mapNotNull { it.candidate.owned?.level }
        return Readiness(
            percent = (100 * coverage.average()).toInt(),
            coverage = coverage,
            teamLevel = if (levels.isEmpty()) 0 else levels.average().toInt(),
            enemyLevel = enemies.maxOf { it.level },
        )
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

package dev.thal.teambuilder.core

data class Threat(
    val threat: Profile,
    val score: Double,
    /** Membros que levam super efetivo de algum golpe dele. */
    val weak: List<Profile>,
    /** Membros que resistem a todos os golpes dele. */
    val walls: List<Profile>,
    /** Membros que acertam ele super efetivo. */
    val answers: List<Profile>,
    /** Tipos de ataque dele que acertam alguem do time em cheio. */
    val hittingTypes: List<String>,
)

/**
 * O contrario da analise de defesa: os Pokemon fortes do meta que o time nao segura.
 * Uma ameaca bate super efetivo em varios membros, quase ninguem resiste e poucos
 * conseguem revidar com vantagem.
 */
class ThreatAnalyzer(private val moves: Map<String, MoveInfo>) {

    private fun attackTypes(p: Profile): List<Int> {
        val fromMoves = p.likelyMoves.mapNotNull { moves[it] }
            .filter { it.category != MoveCategory.STATUS && it.power >= 60 }
            .map { it.type }
        return (fromMoves + p.candidate.types).distinct().map(TypeChart::indexOf).filter { it >= 0 }
    }

    fun threats(team: List<Profile>, pool: Collection<Profile>, limit: Int = 8, consider: Int = 150): List<Threat> {
        if (team.isEmpty()) return emptyList()
        val teamAttacks = team.associateWith(::attackTypes)
        val inTeam = team.mapTo(HashSet()) { it.speciesId }
        return pool.asSequence()
            .filter { it.meta != null && it.speciesId !in inTeam }
            .sortedByDescending { it.strength }
            .distinctBy { it.speciesId }
            .take(consider)
            .mapNotNull { t ->
                val attacks = attackTypes(t)
                if (attacks.isEmpty()) return@mapNotNull null
                val weak = team.filter { m -> attacks.any { m.defense[it] >= 2.0 } }
                val walls = team.filter { m -> attacks.all { m.defense[it] < 1.0 } }
                val answers = team.filter { m -> teamAttacks.getValue(m).any { t.defense[it] >= 2.0 } }
                val pressure = 0.6 * weak.size - 0.7 * walls.size - 0.5 * answers.size + if (answers.isEmpty()) 1.0 else 0.0
                if (pressure <= 0) return@mapNotNull null
                val hitting = attacks.filter { a -> team.any { it.defense[a] >= 2.0 } }.map { TypeChart.TYPES[it] }
                Threat(t, t.strength * pressure, weak, walls, answers, hitting)
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }
}

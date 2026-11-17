package dev.thal.teambuilder.core

/** Velocidade real de um Pokemon, como nos jogos: base, IV, EV, nivel, natureza e Choice Scarf. */
object Speed {
    private val PLUS = setOf("timid", "hasty", "jolly", "naive")
    private val MINUS = setOf("brave", "relaxed", "quiet", "sassy")

    fun stat(base: Int, level: Int, iv: Int = 31, ev: Int = 0, nature: String = "", item: String = ""): Int {
        val raw = (2 * base + iv + ev / 4) * level / 100 + 5
        val n = nature.substringAfter(':').lowercase()
        var s = when (n) {
            in PLUS -> raw * 11 / 10
            in MINUS -> raw * 9 / 10
            else -> raw
        }
        if (item == "choicescarf") s = s * 3 / 2
        return s
    }

    /** Velocidade de um membro do time: o exemplar do jogador, ou a especie no [level] dado (IV 31, sem EV). */
    fun of(p: Profile, level: Int): Int {
        val base = p.candidate.baseStats[StatKey.SPE] ?: 0
        val mine = p.candidate.owned ?: return stat(base, level)
        return stat(base, mine.level, mine.ivs[StatKey.SPE] ?: 31, mine.evs[StatKey.SPE] ?: 0, mine.nature, mine.heldItem)
    }

    fun of(e: EnemyMon): Int =
        stat(e.profile.candidate.baseStats[StatKey.SPE] ?: 0, e.level, e.speIv, e.speEv, e.nature, e.item)
}

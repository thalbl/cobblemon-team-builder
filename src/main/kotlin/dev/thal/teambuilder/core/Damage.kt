package dev.thal.teambuilder.core

/** Natureza: atributo que sobe 10% e o que desce 10%. */
object Natures {
    private val table: Map<String, Pair<StatKey, StatKey>> = mapOf(
        "lonely" to (StatKey.ATK to StatKey.DEF), "brave" to (StatKey.ATK to StatKey.SPE),
        "adamant" to (StatKey.ATK to StatKey.SPA), "naughty" to (StatKey.ATK to StatKey.SPD),
        "bold" to (StatKey.DEF to StatKey.ATK), "relaxed" to (StatKey.DEF to StatKey.SPE),
        "impish" to (StatKey.DEF to StatKey.SPA), "lax" to (StatKey.DEF to StatKey.SPD),
        "timid" to (StatKey.SPE to StatKey.ATK), "hasty" to (StatKey.SPE to StatKey.DEF),
        "jolly" to (StatKey.SPE to StatKey.SPA), "naive" to (StatKey.SPE to StatKey.SPD),
        "modest" to (StatKey.SPA to StatKey.ATK), "mild" to (StatKey.SPA to StatKey.DEF),
        "quiet" to (StatKey.SPA to StatKey.SPE), "rash" to (StatKey.SPA to StatKey.SPD),
        "calm" to (StatKey.SPD to StatKey.ATK), "gentle" to (StatKey.SPD to StatKey.DEF),
        "sassy" to (StatKey.SPD to StatKey.SPE), "careful" to (StatKey.SPD to StatKey.SPA),
    )

    /** 1.1, 0.9 ou 1.0. Aceita "timid" ou "cobblemon:timid". */
    fun multiplier(nature: String, stat: StatKey): Double {
        val (up, down) = table[nature.substringAfter(':').lowercase()] ?: return 1.0
        return when (stat) {
            up -> 1.1
            down -> 0.9
            else -> 1.0
        }
    }
}

/** Atributos reais, com as formulas dos jogos. */
object StatCalc {
    fun stat(key: StatKey, base: Int, level: Int, iv: Int = 31, ev: Int = 0, nature: String = ""): Int {
        val core = (2 * base + iv + ev / 4) * level / 100
        if (key == StatKey.HP) return if (base == 1) 1 else core + level + 10
        return ((core + 5) * Natures.multiplier(nature, key)).toInt()
    }

    fun all(base: Map<StatKey, Int>, level: Int, ivs: Map<StatKey, Int>, evs: Map<StatKey, Int>, nature: String): Map<StatKey, Int> =
        StatKey.entries.associateWith { stat(it, base[it] ?: 0, level, ivs[it] ?: 31, evs[it] ?: 0, nature) }
}

/** Dano de um golpe em % do HP do alvo (sem critico, item ou clima). */
data class DamageRange(val move: String, val min: Int, val max: Int) {
    /** Quantos golpes para derrubar: no melhor e no pior caso. */
    val hits: IntRange get() = hitsFor(max)..hitsFor(min)

    private fun hitsFor(pct: Int) = if (pct <= 0) 99 else (100 + pct - 1) / pct
}

/**
 * Estimativa de dano com a formula dos jogos: nivel, poder, ataque/defesa reais, STAB e
 * efetividade de tipo (com habilidades como Levitate). Sem critico, item, clima ou bonus de habilidade.
 */
class DamageCalc(private val moves: Map<String, MoveInfo>) {

    class Fighter(
        val name: String,
        val types: List<String>,
        val ability: String,
        val level: Int,
        val stats: Map<StatKey, Int>,
        val moves: Collection<String>,
    )

    /** Um membro do time: o exemplar do jogador, ou a especie no [level] dado (IV 31, sem EV). */
    fun fighter(p: Profile, level: Int): Fighter {
        val c = p.candidate
        val mine = c.owned
        if (mine != null) {
            return Fighter(
                p.name, c.types, mine.ability.ifEmpty { p.ability }, mine.level,
                StatCalc.all(c.baseStats, mine.level, mine.ivs, mine.evs, mine.nature),
                (mine.currentMoves + mine.accessibleMoves).distinct(),
            )
        }
        return Fighter(p.name, c.types, p.ability, level, StatCalc.all(c.baseStats, level, emptyMap(), emptyMap(), ""), p.likelyMoves)
    }

    fun fighter(e: EnemyMon): Fighter {
        val c = e.profile.candidate
        val ivs = e.ivs.ifEmpty { mapOf(StatKey.SPE to e.speIv) }
        val evs = e.evs.ifEmpty { mapOf(StatKey.SPE to e.speEv) }
        return Fighter(
            e.profile.name, c.types, e.ability.ifEmpty { e.profile.ability }, e.level,
            StatCalc.all(c.baseStats, e.level, ivs, evs, e.nature),
            e.moves.ifEmpty { e.profile.likelyMoves },
        )
    }

    /** Golpe de [att] que mais tira de [def], em % do HP; null se nenhum golpe de dano serve. */
    fun best(att: Fighter, def: Fighter): DamageRange? {
        val defense = TypeChart.defensiveProfile(def.types, def.ability.ifEmpty { null })
        val hp = def.stats[StatKey.HP]?.takeIf { it > 0 } ?: return null
        var best: DamageRange? = null
        for (id in att.moves) {
            val m = moves[id] ?: continue
            if (m.category == MoveCategory.STATUS || m.power <= 0) continue
            val t = TypeChart.indexOf(m.type)
            if (t < 0) continue
            val eff = defense[t]
            if (eff <= 0.0) continue
            val physical = m.category == MoveCategory.PHYSICAL
            val a = att.stats[if (physical) StatKey.ATK else StatKey.SPA] ?: continue
            val d = (def.stats[if (physical) StatKey.DEF else StatKey.SPD] ?: continue).coerceAtLeast(1)
            val base = ((2 * att.level / 5 + 2) * m.power * a / d) / 50 + 2
            val stab = if (m.type in att.types) 1.5 else 1.0
            val max = (base * stab * eff).toInt()
            val min = ((base * 85 / 100) * stab * eff).toInt()
            val range = DamageRange(id, 100 * min / hp, 100 * max / hp)
            if (best == null || range.max > best.max) best = range
        }
        return best
    }
}

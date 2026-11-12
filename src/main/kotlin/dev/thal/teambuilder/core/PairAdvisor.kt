package dev.thal.teambuilder.core

import kotlin.math.ln

/** Uma dupla do 2v2 com a nota e os motivos (bons e ruins), ja traduzidos. */
data class PairAnalysis(
    val a: Int,
    val b: Int,
    val score: Double,
    val good: List<String>,
    val bad: List<String>,
)

/** O time dividido em duplas; [lead] e o indice em [pairs] da dupla sugerida para abrir. */
data class PairPlan(
    val pairs: List<PairAnalysis>,
    val lead: Int,
    /** Nota de cada par do time, [i][j] (simetrica; a diagonal fica 0). */
    val matrix: List<List<Double>>,
)

/**
 * Avalia duplas para o 2v2: quem cobre as fraquezas de quem, quem usa golpes que acertam
 * o parceiro, quem da cobertura (Fake Out, puxar ataques) para um atacante, nucleos de clima
 * e Trick Room, velocidade e o que o Smogon diz sobre a dupla. Depois divide o time nas
 * duplas de maior soma e escolhe a abertura.
 *
 * Golpes aparecem nos textos como `<id>` (ex.: `<earthquake>`); o cliente troca pelo nome traduzido.
 */
class PairAdvisor(private val moves: Map<String, MoveInfo>, private val scorer: TeamScorer) {

    private fun spe(p: Profile) = p.candidate.baseStats[StatKey.SPE] ?: 0
    private fun offense(p: Profile) = maxOf(p.candidate.baseStats[StatKey.ATK] ?: 0, p.candidate.baseStats[StatKey.SPA] ?: 0)

    /** Atacante que ganha muito com um turno livre: tem setup ou bate muito forte. */
    private fun wantsCover(p: Profile, moves: List<String>) =
        moves.any { it in ProfileBuilder.SETUP_MOVES } || offense(p) >= 120

    fun pair(a: Profile, b: Profile, movesA: List<String>, movesB: List<String>, lang: Lang, ai: Int = 0, bi: Int = 1): PairAnalysis {
        val good = ArrayList<String>()
        val bad = ArrayList<String>()
        var score = 0.0

        // 1. Defesa: um segura o que o outro nao aguenta; os dois fracos ao mesmo tipo e o pior caso.
        val shared = ArrayList<String>()
        val covered = ArrayList<String>()
        var cover = 0.0
        for (t in 0 until TypeChart.COUNT) {
            val da = a.defense[t]
            val db = b.defense[t]
            when {
                da >= 2.0 && db >= 2.0 -> {
                    score -= if (da >= 4.0 || db >= 4.0) 0.8 else 0.6
                    shared += TypeChart.TYPES[t]
                }
                (da >= 2.0 && db < 1.0) || (db >= 2.0 && da < 1.0) -> {
                    cover += if (da == 0.0 || db == 0.0) 0.35 else 0.25
                    covered += TypeChart.TYPES[t]
                }
            }
        }
        score += minOf(cover, 1.2)
        if (covered.isNotEmpty()) good += lang.t("pair.covers", Lang.types(covered))
        if (shared.isNotEmpty()) bad += lang.t("pair.shared_weak", Lang.types(shared))

        // 2. Ataque: juntos acertam com vantagem tipos que nenhum dos dois acerta sozinho.
        val union = Integer.bitCount(a.coverageMask or b.coverageMask)
        val gain = union - maxOf(Integer.bitCount(a.coverageMask), Integer.bitCount(b.coverageMask))
        score += minOf(gain * 0.08, 0.8)
        if (gain >= 4) good += lang.t("pair.coverage", union)

        // 3. Golpes que acertam todo mundo em campo, inclusive o parceiro.
        for ((user, mate, list) in listOf(Triple(a, b, movesA), Triple(b, a, movesB))) {
            for (id in list) {
                val m = moves[id] ?: continue
                if (!m.hitsAlly || m.category == MoveCategory.STATUS || m.power < 60) continue
                val t = TypeChart.indexOf(m.type)
                if (t < 0) continue
                val mult = if (mate.ability == "telepathy") 0.0 else mate.defense[t]
                when {
                    mult == 0.0 -> {
                        score += 0.7
                        good += lang.t("pair.spread_safe", "<$id>", mate.name)
                    }
                    mult >= 1.0 -> {
                        score -= 0.6 * minOf(mult, 2.0)
                        bad += lang.t("pair.spread_hits", "<$id>", user.name, mate.name)
                    }
                    else -> score -= 0.15
                }
            }
        }

        // 4. Suporte cobrindo um atacante.
        for ((sup, atk, supMoves, atkMoves) in listOf(
            Quad(a, b, movesA, movesB), Quad(b, a, movesB, movesA),
        )) {
            if (!wantsCover(atk, atkMoves)) continue
            when {
                supMoves.any { it in ProfileBuilder.REDIRECTION_MOVES } -> {
                    score += 0.7
                    good += lang.t("pair.redirect", sup.name, atk.name)
                }
                "fakeout" in supMoves -> {
                    score += 0.6
                    good += lang.t("pair.fakeout", sup.name, atk.name)
                }
                "helpinghand" in supMoves -> {
                    score += 0.3
                    good += lang.t("pair.helping", sup.name, atk.name)
                }
            }
        }
        if ("fakeout" in movesA && "fakeout" in movesB) score -= 0.2
        if (a.ability == "intimidate" && b.ability == "intimidate") score -= 0.1

        // 5. Nucleos: quem arma clima, terreno ou Trick Room ao lado de quem aproveita.
        for (core in Core.entries) {
            for ((setter, user) in listOf(a to b, b to a)) {
                if (!Cores.sets(setter, core)) continue
                val abuse = Cores.abuse(user, core)
                if (abuse <= 0) continue
                score += 0.8 * abuse
                good += lang.t("pair.core", setter.name, core.label(lang), user.name)
            }
        }

        // 6. Velocidade: Trick Room quer parceiro lento; Tailwind, um que ja seja razoavel.
        for ((setter, mate, list) in listOf(Triple(a, b, movesA), Triple(b, a, movesB))) {
            if ("trickroom" in list && spe(mate) >= 100) {
                score -= 0.4
                bad += lang.t("pair.too_fast", mate.name)
            }
            if ("tailwind" in list && spe(mate) in 60..110) {
                score += 0.3
                good += lang.t("pair.tailwind", setter.name, mate.name)
            }
        }

        // 7. Smogon: duplas que aparecem juntas mais do que o esperado.
        val synergy = scorer.pairSynergy(a, b)
        score += 0.4 * synergy
        val lift = listOfNotNull(scorer.lift(a, b), scorer.lift(b, a)).maxOrNull()
        if (lift != null && lift >= 1.5 && ln(lift) > 0) good += lang.t("pair.meta", "%.1f".format(lift))

        return PairAnalysis(ai, bi, score, good, bad)
    }

    /** Nota extra para abrir a partida: quem controla o primeiro turno. */
    private fun leadBonus(p: Profile, list: List<String>): Double {
        var bonus = 0.0
        if ("fakeout" in list) bonus += 0.3
        if (list.any { it == "tailwind" || it == "trickroom" || it == "icywind" || it == "electroweb" }) bonus += 0.25
        if (p.ability == "intimidate") bonus += 0.15
        if (Core.entries.any { it != Core.TRICK_ROOM && p.ability in it.setterAbilities }) bonus += 0.25
        return bonus
    }

    /**
     * Divide o time nas duplas com a maior soma de notas. Com numero impar, um fica de fora.
     * [movesOf] sao os golpes de cada membro (os do set sugerido, se houver).
     */
    fun plan(team: List<Profile>, movesOf: List<List<String>>, lang: Lang): PairPlan? {
        if (team.size < 2) return null
        val n = team.size
        val all = Array(n) { arrayOfNulls<PairAnalysis>(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val p = pair(team[i], team[j], movesOf.getOrElse(i) { team[i].likelyMoves }, movesOf.getOrElse(j) { team[j].likelyMoves }, lang, i, j)
            all[i][j] = p
            all[j][i] = p
        }

        var best: List<PairAnalysis> = emptyList()
        var bestSum = Double.NEGATIVE_INFINITY
        fun search(left: List<Int>, acc: List<PairAnalysis>, sum: Double) {
            if (left.size < 2) {
                if (sum > bestSum) {
                    bestSum = sum
                    best = acc
                }
                return
            }
            val first = left[0]
            // Numero impar: o primeiro tambem pode ser quem fica sem dupla.
            if (left.size % 2 == 1) search(left.drop(1), acc, sum)
            for (k in 1 until left.size) {
                val p = all[first][left[k]]!!
                search(left.filterIndexed { idx, _ -> idx != 0 && idx != k }, acc + p, sum + p.score)
            }
        }
        search((0 until n).toList(), emptyList(), 0.0)

        val lead = best.indices.maxByOrNull { k ->
            val p = best[k]
            p.score + leadBonus(team[p.a], movesOf.getOrElse(p.a) { team[p.a].likelyMoves }) +
                leadBonus(team[p.b], movesOf.getOrElse(p.b) { team[p.b].likelyMoves })
        } ?: 0
        val ordered = listOfNotNull(best.getOrNull(lead)) + best.filterIndexed { k, _ -> k != lead }
        val matrix = (0 until n).map { i -> (0 until n).map { j -> all[i][j]?.score ?: 0.0 } }
        return PairPlan(ordered, 0, matrix)
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

package dev.thal.teambuilder.core

/** Um jogador do grupo da raid e os Pokemon que ele pode levar. */
data class RaidMember(val id: String, val name: String, val pool: List<Profile>)

enum class RaidRole { ATTACK, SUPPORT }

/**
 * Papel de um jogador na raid.
 *
 * @param pick o Pokemon sugerido (null se o jogador nao tem nenhum que sirva)
 * @param supportMoves golpes de suporte que ele usaria (so para [RaidRole.SUPPORT])
 * @param learn golpes de suporte que ele ainda precisa aprender
 * @param avoided tipo de golpe do chefe que este atacante evita repetir (fraqueza de outro atacante)
 */
data class RaidAssignment(
    val member: RaidMember,
    val role: RaidRole,
    val pick: RaidPick?,
    val supportMoves: List<String> = emptyList(),
    val learn: List<String> = emptyList(),
    val avoided: String? = null,
)

/**
 * Divide os papeis de uma raid em grupo. No Cobblemon Raid Dens cada jogador luta na propria
 * batalha, mas mudancas de atributo, telas e clima sao sincronizadas entre elas. Por isso vale ter
 * alguem de suporte (Reflect/Light Screen, baixar o ataque ou a defesa do chefe) enquanto os
 * outros batem, e os atacantes nao devem ser todos fracos ao mesmo golpe do chefe.
 */
class RaidGroupAdvisor(private val moves: Map<String, MoveInfo>, private val raids: RaidAdvisor) {

    private enum class Effect { SCREEN_PHYSICAL, SCREEN_SPECIAL, DROP_ATK, DROP_SPA, DROP_DEF, DROP_SPD }

    /** Golpes de suporte cujo efeito vale para todos os jogadores da raid. */
    private val support: Map<String, List<Effect>> = mapOf(
        "reflect" to listOf(Effect.SCREEN_PHYSICAL),
        "lightscreen" to listOf(Effect.SCREEN_SPECIAL),
        "charm" to listOf(Effect.DROP_ATK),
        "featherdance" to listOf(Effect.DROP_ATK),
        "growl" to listOf(Effect.DROP_ATK),
        "playnice" to listOf(Effect.DROP_ATK),
        "lunge" to listOf(Effect.DROP_ATK),
        "breakingswipe" to listOf(Effect.DROP_ATK),
        "tropkick" to listOf(Effect.DROP_ATK),
        "nobleroar" to listOf(Effect.DROP_ATK, Effect.DROP_SPA),
        "partingshot" to listOf(Effect.DROP_ATK, Effect.DROP_SPA),
        "tickle" to listOf(Effect.DROP_ATK, Effect.DROP_DEF),
        "eerieimpulse" to listOf(Effect.DROP_SPA),
        "confide" to listOf(Effect.DROP_SPA),
        "snarl" to listOf(Effect.DROP_SPA),
        "strugglebug" to listOf(Effect.DROP_SPA),
        "mysticalfire" to listOf(Effect.DROP_SPA),
        "spiritbreak" to listOf(Effect.DROP_SPA),
        "screech" to listOf(Effect.DROP_DEF),
        "metalsound" to listOf(Effect.DROP_SPD),
        "faketears" to listOf(Effect.DROP_SPD),
        "acidspray" to listOf(Effect.DROP_SPD),
    )

    fun isSupportMove(id: String) = id in support

    /** Quanto cada efeito ajuda contra este chefe: telas e quedas de ataque so contra a categoria que ele usa. */
    private fun weights(boss: RaidBossInfo): Map<Effect, Double> {
        val cats = boss.moves.mapNotNull { moves[it]?.category }.filter { it != MoveCategory.STATUS }.toSet()
        val physical = cats.isEmpty() || MoveCategory.PHYSICAL in cats
        val special = cats.isEmpty() || MoveCategory.SPECIAL in cats
        return mapOf(
            Effect.SCREEN_PHYSICAL to if (physical) 1.0 else 0.0,
            Effect.SCREEN_SPECIAL to if (special) 1.0 else 0.0,
            Effect.DROP_ATK to if (physical) 0.9 else 0.0,
            Effect.DROP_SPA to if (special) 0.9 else 0.0,
            Effect.DROP_DEF to 0.8,
            Effect.DROP_SPD to 0.8,
        )
    }

    private class SupportOption(val pick: RaidPick, val score: Double, val ready: List<String>, val learn: List<String>)

    /** Melhor Pokemon de suporte do jogador: golpes uteis (os que ele ja sabe valem mais) e quanto aguenta. */
    private fun bestSupport(boss: RaidBossInfo, pool: List<Profile>, w: Map<Effect, Double>): SupportOption? {
        var best: SupportOption? = null
        for (p in pool) {
            val pick = raids.matchup(boss, p)?.second ?: continue
            val owned = p.candidate.owned
            val known = owned?.let { (it.currentMoves + it.accessibleMoves).toSet() } ?: p.candidate.legalMoves
            fun value(ids: Collection<String>) = ids.flatMap { support[it].orEmpty() }.distinct().sumOf { w[it] ?: 0.0 }
            val ready = support.keys.filter { it in known && value(listOf(it)) > 0 }
            val learn = support.keys.filter { it !in known && it in p.candidate.legalMoves && value(listOf(it)) > 0 }
            val moveValue = value(ready) + 0.4 * value(learn - ready.toSet())
            if (moveValue <= 0) continue
            val bulk = when {
                pick.worstTaken <= 0.5 -> 1.2
                pick.worstTaken <= 1.0 -> 1.0
                pick.worstTaken <= 2.0 -> 0.6
                else -> 0.3
            }
            val score = moveValue * bulk
            if (best == null || score > best.score) best = SupportOption(pick, score, ready.take(3), learn.take(2))
        }
        return best
    }

    fun assign(boss: RaidBossInfo, members: List<RaidMember>): List<RaidAssignment> {
        if (members.isEmpty()) return emptyList()
        val w = weights(boss)
        val attackTypes = raids.attackTypes(boss)
        // Valor absoluto de cada candidato de cada jogador.
        val options = members.associateWith { m ->
            m.pool.mapNotNull { p -> raids.matchup(boss, p) }.sortedByDescending { it.first }
        }
        val bestAttack = members.associateWith { options[it]?.firstOrNull()?.first ?: 0.0 }
        val topAttack = bestAttack.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

        // Quem fica no suporte: bom suporte e, de preferencia, o atacante mais fraco do grupo.
        val supportsWanted = when {
            members.size >= 4 -> 2
            members.size >= 2 -> 1
            else -> 0
        }
        val supportOptions = members.associateWith { bestSupport(boss, it.pool, w) }
        val supporters = members
            .filter { supportOptions[it] != null }
            .sortedByDescending { m -> supportOptions.getValue(m)!!.score * (1.5 - (bestAttack[m] ?: 0.0) / topAttack) }
            .take(supportsWanted)
            .toSet()

        // Atacantes, do mais forte ao mais fraco; cada um evita a fraqueza dos anteriores.
        val takenWeak = HashSet<Int>()
        val attack = HashMap<RaidMember, RaidAssignment>()
        for (m in members.filter { it !in supporters }.sortedByDescending { bestAttack[it] ?: 0.0 }) {
            val list = options[m].orEmpty()
            fun weak(p: Profile) = attackTypes.filter { p.defense[it] > 1.0 }.toSet()
            val chosen = list.maxByOrNull { (value, pick) -> if (weak(pick.pick).any { it in takenWeak }) value * 0.7 else value }
            val first = list.firstOrNull()
            val avoided = if (chosen != null && first != null && chosen !== first) {
                weak(first.second.pick).firstOrNull { it in takenWeak }?.let { TypeChart.TYPES[it] }
            } else null
            chosen?.let { takenWeak += weak(it.second.pick) }
            attack[m] = RaidAssignment(m, RaidRole.ATTACK, chosen?.second, avoided = avoided)
        }
        return members.map { m ->
            attack[m] ?: supportOptions[m]!!.let { s -> RaidAssignment(m, RaidRole.SUPPORT, s.pick, s.ready, s.learn) }
        }
    }
}

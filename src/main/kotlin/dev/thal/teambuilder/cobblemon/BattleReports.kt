package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import dev.thal.teambuilder.TeamBuilderMod
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerPlayer

/**
 * Depois de perder para um treinador importante do RCT (lider, Elite 4, campeao, rival...), manda
 * no chat o que faltou: quem do time dele ficou sem resposta, a diferenca de nivel e um botao que
 * abre o Team Builder ja com o plano contra ele.
 */
object BattleReports {

    fun register() {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.LOW) { event ->
            try {
                onVictory(event.battle, event.winners, event.losers)
            } catch (e: Throwable) {
                TeamBuilderMod.LOGGER.warn("Relatorio de batalha falhou", e)
            }
        }
    }

    /** Id do treinador do Radical Cobblemon Trainers por tras do ator, por reflexao. */
    fun rctTrainerId(actor: BattleActor): String? {
        if (!actor.javaClass.name.startsWith("com.gitlab.srcmc")) return null
        val entity = (actor as? EntityBackedBattleActor<*>)?.entity ?: return null
        val method = entity.javaClass.methods.firstOrNull { it.name == "getTrainerId" && it.parameterCount == 0 } ?: return null
        return method.invoke(entity) as? String
    }

    private fun onVictory(battle: PokemonBattle, winners: List<BattleActor>, losers: List<BattleActor>) {
        val trainerId = winners.firstNotNullOfOrNull(::rctTrainerId) ?: return
        val trainer = Trainers.all[trainerId] ?: return
        if (!trainer.important) return
        for (actor in losers) {
            val used = actor.pokemonList.mapTo(HashSet()) { it.originalPokemon.uuid.toString() }
            for (id in actor.getPlayerUUIDs()) {
                val player = battle.players.firstOrNull { it.uuid == id } ?: continue
                report(player, trainer, used)
            }
        }
    }

    private fun report(player: ServerPlayer, trainer: Trainer, used: Set<String>) {
        val s = TeamBuilderService.session(player)
        val lang = s.lang
        val team = s.real.filter { it.candidate.owned?.uuid in used }
        val enemies = trainer.team.mapNotNull { Trainers.enemy(it, s.catalog) }
        val r = s.catalog.battles.readiness(enemies, team)

        val title = Component.literal("Team Builder").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(lang.t("report.lost") + " ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(trainer.name).withStyle(ChatFormatting.YELLOW))
        if (trainer.typeLabel.isNotEmpty()) title.append(Component.literal(" (${trainer.typeLabel})").withStyle(ChatFormatting.GRAY))
        player.sendSystemMessage(title)

        if (r != null) {
            val color = when {
                r.percent >= 70 -> ChatFormatting.GREEN
                r.percent >= 40 -> ChatFormatting.GOLD
                else -> ChatFormatting.RED
            }
            player.sendSystemMessage(
                Component.literal(" " + lang.t("report.readiness_prefix") + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("${r.percent}%").withStyle(color))
                    .append(Component.literal(" " + lang.t("report.readiness_suffix")).withStyle(ChatFormatting.GRAY))
            )
            val missing = r.unanswered.map { enemies[it].profile.name }
            if (missing.isNotEmpty()) {
                player.sendSystemMessage(
                    Component.literal(" " + lang.t("report.unanswered") + " ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(missing.joinToString(", ")).withStyle(ChatFormatting.RED))
                )
            }
            if (r.teamLevel in 1 until r.enemyLevel) {
                player.sendSystemMessage(
                    Component.literal(" " + lang.t("report.levels", r.teamLevel, r.enemyLevel)).withStyle(ChatFormatting.GRAY)
                )
            }
        }

        val button = Component.literal("[" + lang.t("report.open", trainer.name) + "]").withStyle { style ->
            style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/teambuilder plan ${trainer.id}"))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(lang.t("report.open_tip"))))
        }
        player.sendSystemMessage(Component.literal(" ").append(button))
    }
}

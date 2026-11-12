package dev.thal.teambuilder.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.thal.teambuilder.Config
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.core.SnapshotBuilder
import dev.thal.teambuilder.cobblemon.Catalog
import dev.thal.teambuilder.cobblemon.StorageReader
import dev.thal.teambuilder.cobblemon.TeamBuilderService
import dev.thal.teambuilder.core.Breakdown
import dev.thal.teambuilder.core.BuildOptions
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.Lang
import dev.thal.teambuilder.core.NameResolver
import dev.thal.teambuilder.core.Profile
import dev.thal.teambuilder.core.Source
import dev.thal.teambuilder.core.TeamBuilderEngine
import dev.thal.teambuilder.core.TeamResult
import dev.thal.teambuilder.text.SetText
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

/**
 * Comandos de texto, alternativa a tela (tecla H):
 *   /teambuilder pc                    lista o que o mod enxerga no party e no PC
 *   /teambuilder meta <pokemon>        dados competitivos do snapshot
 *   /teambuilder suggest <pokemon...>  melhores opcoes para o proximo slot
 *   /teambuilder complete <pokemon...> 3 times completos (PC x todo o Cobblemon)
 *   /teambuilder analyze               nota do party atual e trocas sugeridas
 *   /teambuilder sets [pokemon...]     golpes, item, natureza e EVs sugeridos
 *   /teambuilder raid [chefe [tipo]]   melhores Pokemon contra o chefe de raid
 *   /teambuilder option legendaries|megas|doubles true|false
 */
object TeamBuilderCommand {

    private val metaResolver by lazy { NameResolver(TeamBuilderMod.meta.ids) }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun names() = Commands.argument("pokemon", StringArgumentType.greedyString())
            .suggests(suggestNames { Catalog.get().resolver })
        val metaName = Commands.argument("pokemon", StringArgumentType.greedyString())
            .suggests(suggestNames { metaResolver })
        dispatcher.register(
            Commands.literal("teambuilder")
                .then(Commands.literal("pc").executes(::listOwned))
                .then(Commands.literal("meta").then(metaName.executes(::showMeta)))
                .then(Commands.literal("suggest").then(names().executes(::suggest)))
                .then(Commands.literal("complete").then(names().executes(::complete)))
                .then(Commands.literal("analyze").executes(::analyze))
                .then(
                    Commands.literal("raid").executes { raid(it, "") }
                        .then(
                            Commands.argument("boss", StringArgumentType.greedyString())
                                .suggests(suggestNames { Catalog.get().resolver })
                                .executes { raid(it, StringArgumentType.getString(it, "boss")) }
                        )
                )
                .then(
                    Commands.literal("option")
                        .then(Commands.literal("legendaries").then(onOff { p, v -> TeamBuilderService.setOptions(p, legendaries = v) }))
                        .then(Commands.literal("megas").then(onOff { p, v -> TeamBuilderService.setOptions(p, megas = v) }))
                        .then(Commands.literal("doubles").then(onOff { p, v -> TeamBuilderService.setOptions(p, doubles = v) }))
                )
                .then(
                    Commands.literal("reload")
                        .requires { it.hasPermission(Config.current.adminPermissionLevel) }
                        .executes(::reload)
                )
                .then(
                    Commands.literal("snapshot")
                        .requires { it.hasPermission(Config.current.adminPermissionLevel) }
                        .executes(::snapshotStatus)
                        .then(
                            Commands.literal("update")
                                .executes { snapshotUpdate(it, null, false) }
                                .then(Commands.literal("doubles").executes { snapshotUpdate(it, null, true) })
                                .then(
                                    Commands.argument("month", StringArgumentType.word())
                                        .executes { snapshotUpdate(it, StringArgumentType.getString(it, "month"), false) }
                                        .then(Commands.literal("doubles").executes { snapshotUpdate(it, StringArgumentType.getString(it, "month"), true) })
                                )
                        )
                        .then(Commands.literal("reset").executes(::snapshotReset))
                )
                .then(
                    Commands.literal("sets").executes { sets(it, null) }
                        .then(names().executes { sets(it, StringArgumentType.getString(it, "pokemon")) })
                )
        )
    }

    private fun CommandContext<CommandSourceStack>.lang(): Lang =
        source.player?.let(TeamBuilderService::lang) ?: Lang.EN

    private fun onOff(apply: (ServerPlayer, Boolean) -> BuildOptions) =
        Commands.argument("value", BoolArgumentType.bool()).executes { ctx ->
            val o = apply(ctx.source.playerOrException, BoolArgumentType.getBool(ctx, "value"))
            val l = ctx.lang()
            fun yn(v: Boolean) = if (v) "§a" + l.t("common.yes") else "§c" + l.t("common.no")
            ctx.reply(
                "§6Team Builder§r: " + l.t("option.legendaries") + " " + yn(o.allowLegendaries) + "§r, " +
                    l.t("option.megas") + " " + yn(o.allowMegas) + "§r, " +
                    l.t("option.doubles") + " " + yn(o.format == Format.DOUBLES)
            )
            1
        }

    private fun reload(ctx: CommandContext<CommandSourceStack>): Int {
        Config.load()
        TeamBuilderMod.reloadMeta()
        ctx.reply("§6Team Builder§r: " + ctx.lang().t("cmd.reloaded"))
        return 1
    }

    private fun snapshotStatus(ctx: CommandContext<CommandSourceStack>): Int {
        val l = ctx.lang()
        for (doubles in listOf(false, true)) {
            val meta = TeamBuilderMod.metaFor(if (doubles) Format.DOUBLES else Format.SINGLES)
            val custom = java.nio.file.Files.exists(TeamBuilderMod.snapshotFile(doubles))
            ctx.reply("§6" + (if (doubles) "2v2" else "1v1") + "§r: " + l.t("cmd.snapshot_status", meta.month, meta.size,
                l.t(if (custom) "cmd.snapshot_downloaded" else "cmd.snapshot_bundled")))
        }
        ctx.reply("§7" + l.t("cmd.snapshot_help"))
        return 1
    }

    @Volatile
    private var updating = false

    /** Baixa as estatisticas do Smogon numa thread separada para nao travar o servidor. */
    private fun snapshotUpdate(ctx: CommandContext<CommandSourceStack>, month: String?, doubles: Boolean): Int {
        val l = ctx.lang()
        if (updating) {
            ctx.reply("§c" + l.t("cmd.snapshot_busy"))
            return 0
        }
        updating = true
        val source = ctx.source
        val server = source.server
        fun say(text: String) = server.execute { source.sendSuccess({ Component.literal(text) }, false) }
        ctx.reply("§6Team Builder§r: " + l.t("cmd.snapshot_start", month ?: l.t("cmd.snapshot_latest")))
        Thread({
            try {
                val json = SnapshotBuilder.build(month, doubles) { say("§7  $it") }
                val file = TeamBuilderMod.snapshotFile(doubles)
                java.nio.file.Files.createDirectories(file.parent)
                java.nio.file.Files.writeString(file, json.toString())
                server.execute {
                    TeamBuilderMod.reloadMeta()
                    val meta = TeamBuilderMod.metaFor(if (doubles) Format.DOUBLES else Format.SINGLES)
                    source.sendSuccess({ Component.literal("§a" + l.t("cmd.snapshot_done", meta.month, meta.size)) }, true)
                }
            } catch (e: Exception) {
                TeamBuilderMod.LOGGER.error("Falha ao atualizar o snapshot", e)
                say("§c" + l.t("cmd.snapshot_failed", e.message ?: e.javaClass.simpleName))
            } finally {
                updating = false
            }
        }, "team-builder-snapshot").apply { isDaemon = true }.start()
        return 1
    }

    /** Apaga os snapshots baixados e volta aos que vem no jar. */
    private fun snapshotReset(ctx: CommandContext<CommandSourceStack>): Int {
        for (doubles in listOf(false, true)) java.nio.file.Files.deleteIfExists(TeamBuilderMod.snapshotFile(doubles))
        TeamBuilderMod.reloadMeta()
        ctx.reply("§6Team Builder§r: " + ctx.lang().t("cmd.snapshot_reset"))
        return 1
    }

    /** Melhores Pokemon contra o chefe de raid (detectado, ou informado como "pokemon [tipo tera]"). */
    private fun raid(ctx: CommandContext<CommandSourceStack>, query: String): Int {
        val player = ctx.source.playerOrException
        val s = TeamBuilderService.session(player)
        val (boss, problem) = TeamBuilderService.raidBoss(player, s.catalog, query, s.lang)
        if (boss == null) {
            ctx.reply("§c$problem")
            return 0
        }
        val view = TeamBuilderService.raidView(s, boss)
        ctx.source.sendSuccess({ SetText.raidChat(view, s.lang) }, false)
        return 1
    }

    /** Golpes, item, natureza e EVs sugeridos para cada membro (party atual se [names] for null). */
    private fun sets(ctx: CommandContext<CommandSourceStack>, names: String?): Int {
        val s = session(ctx, names) ?: return 0
        val l = s.base.lang
        if (s.team.isEmpty()) {
            ctx.reply("§c" + l.t("cmd.nothing_to_analyze"))
            return 0
        }
        for (p in s.team) {
            val set = TeamBuilderService.setView(s.catalog.advisor.advise(p, s.team, l), s.catalog, l, p)
            ctx.source.sendSuccess({ SetText.chatLines(p.name, p.isOwned, set, l) }, false)
        }
        return s.team.size
    }

    private fun listOwned(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.playerOrException
        val l = ctx.lang()
        val owned = StorageReader.read(player)
        val meta = TeamBuilderMod.meta
        val withMeta = owned.count { meta[it.id] != null || meta[it.speciesId] != null }

        ctx.reply("§6Team Builder§r: " + l.t("cmd.pc_summary", owned.count { it.source == Source.PARTY }, owned.count { it.source == Source.PC }, withMeta))
        for (p in owned.sortedWith(compareBy({ it.source }, { -it.baseStatTotal })).take(40)) {
            val tag = if (p.source == Source.PARTY) "§a[party]" else "§7[pc]"
            val usage = (meta[p.id] ?: meta[p.speciesId])?.let { " §8${it.format} ${pct(it.usage)}" } ?: ""
            ctx.reply("$tag §f${p.displayName} §7${l.t("info.level_short", p.level)} ${p.types.joinToString("/")} " +
                "BST ${p.baseStatTotal} IV ${p.ivTotal}/186 ${p.nature}$usage")
        }
        if (owned.size > 40) ctx.reply("§7" + l.t("cmd.and_more", owned.size - 40))
        return owned.size
    }

    private fun showMeta(ctx: CommandContext<CommandSourceStack>): Int {
        val raw = StringArgumentType.getString(ctx, "pokemon")
        val l = ctx.lang()
        val meta = TeamBuilderMod.meta
        val id = metaResolver.resolveOne(raw)
        val e = id?.let { meta[it] }
        if (id == null || e == null) {
            ctx.reply("§c" + l.t("cmd.no_meta", raw, meta.month))
            return 0
        }
        ctx.reply("§6${e.name}§r — ${e.format}, ${l.t("cmd.usage")} ${pct(e.usage)} (snapshot ${meta.month})")
        ctx.reply("§7${l.t("cmd.moves")}:§r " + e.moves.entries.take(8).joinToString { "${it.key} ${pct(it.value)}" })
        ctx.reply("§7${l.t("cmd.items")}:§r " + e.items.entries.take(4).joinToString { "${it.key} ${pct(it.value)}" })
        ctx.reply("§7${l.t("cmd.abilities")}:§r " + e.abilities.entries.joinToString { "${it.key} ${pct(it.value)}" })
        e.spreads.firstOrNull()?.let {
            ctx.reply("§7Spread:§r ${it.nature} ${it.evs.joinToString("/")} (${pct(it.freq)})")
        }
        val mates = e.teammates.keys
            .mapNotNull { mate -> meta.teammateLift(id, mate)?.let { mate to it } }
            .sortedByDescending { it.second }
            .take(8)
        ctx.reply("§7${l.t("cmd.partners")}:§r " + mates.joinToString { "${it.first} x${"%.1f".format(it.second)}" })
        ctx.reply("§7Counters:§r " + e.counters.keys.take(6).joinToString())
        return 1
    }

    /** Catalogo, Pokemon do jogador e o time pedido (ou o party, se [names] for null). */
    private class Session(
        val catalog: Catalog, val owned: List<Profile>, val team: List<Profile>,
        val engine: TeamBuilderEngine, val base: TeamBuilderService.Session,
    )

    private fun session(ctx: CommandContext<CommandSourceStack>, names: String?): Session? {
        val s = TeamBuilderService.session(ctx.source.playerOrException)
        val l = s.lang
        val team = if (names == null) s.party else {
            val (found, unknown) = s.resolveNames(names)
            if (unknown.isNotEmpty()) {
                ctx.reply("§c" + l.t("cmd.unknown_names", unknown.joinToString()))
                return null
            }
            ctx.reply("§8" + l.t("cmd.understood", found.joinToString { it.id }))
            found
        }
        if (team.size > TeamBuilderService.TEAM_SIZE) {
            ctx.reply("§c" + l.t("error.team_full", TeamBuilderService.TEAM_SIZE))
            return null
        }
        return Session(s.catalog, s.owned, team, s.engine, s)
    }

    /** Autocomplete do ultimo nome digitado. */
    private fun suggestNames(resolver: () -> NameResolver) = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remaining
        val cut = typed.lastIndexOfAny(charArrayOf(' ', ',')) + 1
        val sub = builder.createOffset(builder.start + cut)
        resolver().completions(typed.substring(cut)).forEach(sub::suggest)
        sub.buildFuture()
    }

    private fun reasons(list: List<String>): Component {
        val out = Component.empty()
        list.forEachIndexed { i, r ->
            if (i > 0) out.append(Component.literal("; ").withStyle(ChatFormatting.GRAY))
            out.append(SetText.rich(r, ChatFormatting.GRAY))
        }
        return out
    }

    private fun suggest(ctx: CommandContext<CommandSourceStack>): Int {
        val s = session(ctx, StringArgumentType.getString(ctx, "pokemon")) ?: return 0
        val l = s.base.lang
        if (s.team.size >= 6) {
            ctx.reply("§c" + l.t("cmd.team_full_use_analyze"))
            return 0
        }
        val engine = s.engine
        ctx.reply("§6" + l.t("cmd.next_slot_for") + "§r ${s.team.joinToString(" + ") { it.name }}")
        for ((title, pool) in listOf(l.t("column.pc") to s.owned, l.t("column.all") to s.catalog.all.values)) {
            ctx.reply("§e$title:")
            val picks = engine.suggestNext(s.team, pool, 5)
            if (picks.isEmpty()) ctx.reply("§7  " + l.t("empty.no_candidates"))
            for (p in picks) {
                val line = Component.literal("  ").append(Component.literal(p.pick.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" +${"%.1f".format(p.gain)} ").withStyle(ChatFormatting.GREEN))
                    .append(reasons(p.reasons))
                ctx.source.sendSuccess({ line }, false)
            }
        }
        return 1
    }

    private fun complete(ctx: CommandContext<CommandSourceStack>): Int {
        val s = session(ctx, StringArgumentType.getString(ctx, "pokemon")) ?: return 0
        val l = s.base.lang
        val engine = s.engine
        ctx.reply("§6" + l.t("cmd.teams_from") + "§r ${s.team.joinToString(" + ") { it.name }}")
        for ((title, pool) in listOf(l.t("column.pc") to s.owned, l.t("column.all") to s.catalog.all.values)) {
            ctx.reply("§e$title:")
            val teams = engine.complete(s.team, pool, TeamBuilderService.SUGGESTED_TEAMS, width = 32)
            for ((i, t) in teams.withIndex()) ctx.reply("  §f${i + 1}. ${describe(t, l)}")
        }
        return 1
    }

    private fun analyze(ctx: CommandContext<CommandSourceStack>): Int {
        val s = session(ctx, null) ?: return 0
        val l = s.base.lang
        if (s.team.isEmpty()) {
            ctx.reply("§c" + l.t("cmd.party_empty"))
            return 0
        }
        val engine = s.engine
        val b = s.catalog.scorer.score(s.team)
        ctx.reply("§6" + l.t("cmd.current_party") + "§r: ${s.team.joinToString { it.name }}")
        ctx.reply("§7" + fmt(b, l))
        val weak = engine.weaknesses(s.team)
        if (weak.isEmpty()) {
            ctx.reply("§a" + l.t("cmd.no_stacked_weakness"))
        } else {
            val line = Component.literal(l.t("cmd.stacked_weakness") + ": ").withStyle(ChatFormatting.RED).append(SetText.rich(Lang.types(weak)))
            ctx.source.sendSuccess({ line }, false)
        }
        for (p in s.team) {
            ctx.reply("  §f${p.name} §7${p.roles.joinToString { it.label(l) }}")
        }
        val swaps = engine.suggestSwaps(s.team, s.owned.filter { it.candidate.owned?.source == Source.PC }, 5)
        if (swaps.isEmpty()) {
            ctx.reply("§7" + l.t("cmd.no_swaps"))
        } else {
            ctx.reply("§e" + l.t("cmd.swaps") + ":")
            for (sw in swaps) {
                val line = Component.literal("  ").append(Component.literal(sw.out.name).withStyle(ChatFormatting.RED))
                    .append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(sw.pick.name).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" +${"%.1f".format(sw.gain)} ").withStyle(ChatFormatting.GREEN))
                    .append(reasons(sw.reasons))
                ctx.source.sendSuccess({ line }, false)
            }
        }
        return 1
    }

    private fun describe(t: TeamResult, l: Lang): String {
        val added = t.added.joinToString(", ") { it.name }
        return "$added §7(${l.t("label.score_lower")} ${"%.1f".format(t.breakdown.total)})"
    }

    private fun fmt(b: Breakdown, l: Lang) = l.t("cmd.score_breakdown",
        "%.1f".format(b.total), "%.1f".format(b.strength), "%.1f".format(b.defense),
        "%.1f".format(b.offense), "%.1f".format(b.roles), "%.1f".format(b.synergy))

    private fun CommandContext<CommandSourceStack>.reply(text: String) {
        source.sendSuccess({ Component.literal(text) }, false)
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)
}

package dev.thal.teambuilder.text

import dev.thal.teambuilder.core.Lang
import dev.thal.teambuilder.core.RaidPickView
import dev.thal.teambuilder.core.RaidView
import dev.thal.teambuilder.core.SetView
import net.minecraft.ChatFormatting
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/**
 * Transforma sets e raids em texto de jogo. Golpes, habilidades, naturezas, itens e tipos
 * usam as traducoes do proprio Cobblemon; o resto vem dos arquivos de idioma do mod.
 */
object SetText {
    val STAT_NAMES = listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")

    /** Id estilo Showdown ("choiceband") -> item registrado ("cobblemon:choice_band"). */
    private val itemsByShowdownId by lazy {
        // Em empates entre mods o item do proprio Cobblemon vence: ele entra por ultimo.
        BuiltInRegistries.ITEM.keySet()
            .sortedBy { if (it.namespace == "cobblemon") 1 else 0 }
            .associateBy({ it.path.replace("_", "") }, { BuiltInRegistries.ITEM.get(it) })
    }

    /** Cor de cada tipo, para escrever o nome dele em tooltips e no chat. */
    private val TYPE_COLORS = mapOf(
        "normal" to 0xA8A77A, "fire" to 0xEE8130, "water" to 0x6390F0, "electric" to 0xF7D02C,
        "grass" to 0x7AC74C, "ice" to 0x96D9D6, "fighting" to 0xC22E28, "poison" to 0xA33EA1,
        "ground" to 0xE2BF65, "flying" to 0xA98FF3, "psychic" to 0xF95587, "bug" to 0xA6B91A,
        "rock" to 0xB6A136, "ghost" to 0x735797, "dragon" to 0x6F35FC, "dark" to 0x705746,
        "steel" to 0xB7B7CE, "fairy" to 0xD685AD,
    )

    fun move(id: String): MutableComponent = Component.translatableWithFallback("cobblemon.move.$id", id)
    fun ability(id: String): MutableComponent = Component.translatableWithFallback("cobblemon.ability.$id", id)
    fun nature(id: String): MutableComponent = Component.translatableWithFallback("cobblemon.nature.$id", id)
    fun item(id: String): MutableComponent =
        itemsByShowdownId[id]?.description?.copy() ?: Component.literal(id)

    /** Nome do tipo, colorido. */
    fun type(name: String): MutableComponent {
        val out = Component.translatableWithFallback("cobblemon.type.$name", name.replaceFirstChar { it.uppercase() })
        TYPE_COLORS[name]?.let { out.withStyle { s -> s.withColor(TextColor.fromRgb(it)) } }
        return out
    }

    /** Texto do servidor com "{fire}" trocado pelo nome colorido do tipo (tooltips e chat). */
    fun rich(text: String, style: ChatFormatting? = null): MutableComponent {
        val out = Component.empty()
        var last = 0
        var previousWasType = false
        for (m in Lang.TYPE_TOKEN.findAll(text)) {
            if (m.range.first > last) {
                out.append(plain(text.substring(last, m.range.first), style))
                previousWasType = false
            }
            if (previousWasType) out.append(plain("/", ChatFormatting.DARK_GRAY))
            out.append(type(m.groupValues[1]))
            previousWasType = true
            last = m.range.last + 1
        }
        if (last < text.length) out.append(plain(text.substring(last), style))
        return out
    }

    private val MOVE_TOKEN = Regex("<([a-z0-9]+)>")

    /** Troca "<earthquake>" pelo nome traduzido do golpe (o servidor so conhece o id). */
    fun moves(text: String): String = MOVE_TOKEN.replace(text) { m ->
        val id = m.groupValues[1]
        net.minecraft.locale.Language.getInstance().getOrDefault("cobblemon.move.$id", id.replaceFirstChar { it.uppercase() })
    }

    /** "{fire}{flying}" -> "fire/flying", para lugares que so aceitam texto puro. */
    fun flat(text: String): String = Lang.TYPE_TOKEN.replace(text) { it.groupValues[1] }.replace("}{", "/")

    private fun plain(text: String, style: ChatFormatting?) =
        Component.literal(text).let { if (style != null) it.withStyle(style) else it }

    private fun gray(text: String) = Component.literal(text).withStyle(ChatFormatting.GRAY)

    private fun status(m: dev.thal.teambuilder.core.MoveView, lang: Lang): MutableComponent = when (m.status) {
        "KNOWN" -> Component.literal(" " + lang.t("move.known")).withStyle(ChatFormatting.GREEN)
        "SWAP" -> Component.literal(" " + lang.t("move.swap_long")).withStyle(ChatFormatting.YELLOW)
        else -> Component.literal(" " + if (m.method.isEmpty()) lang.t("move.learn") else lang.t("move.learn_how", m.method))
            .withStyle(ChatFormatting.GOLD)
    }

    fun evs(evs: List<Int>): String =
        evs.withIndex().filter { it.value > 0 }.joinToString(" / ") { "${it.value} ${STAT_NAMES.getOrElse(it.index) { "?" }}" }

    /** Linhas do set, uma por elemento. */
    private fun setLines(set: SetView, owned: Boolean, lang: Lang): List<Component> {
        val lines = ArrayList<Component>()
        if (set.roleLabel.isNotEmpty()) lines += gray(lang.t("label.role") + ": ").append(Component.literal(set.roleLabel).withStyle(ChatFormatting.AQUA))
        for (m in set.moves) {
            val line = Component.literal("- ").append(move(m.id))
            if (owned) line.append(status(m, lang)) else if (m.method.isNotEmpty()) line.append(gray(" (${m.method})"))
            if (m.note.isNotEmpty()) line.append(gray(" · ")).append(rich(m.note, ChatFormatting.GRAY))
            lines += line
        }
        if (set.items.isNotEmpty()) {
            val line = gray(lang.t("label.item") + ": ").append(item(set.items[0]).withStyle(ChatFormatting.WHITE))
            set.items.drop(1).forEach { line.append(gray(" " + lang.t("label.or") + " ")).append(item(it).withStyle(ChatFormatting.WHITE)) }
            lines += line
        }
        if (set.ability.isNotEmpty()) lines += gray(lang.t("label.ability") + ": ").append(ability(set.ability).withStyle(ChatFormatting.WHITE))
        if (set.nature.isNotEmpty()) {
            lines += gray(lang.t("label.nature") + ": ").append(nature(set.nature).withStyle(ChatFormatting.WHITE))
                .append(gray("  EVs: ")).append(Component.literal(evs(set.evs)).withStyle(ChatFormatting.WHITE))
        }
        if (set.ivPriority.isNotEmpty()) {
            lines += gray(lang.t("label.iv_priority") + ": ")
                .append(Component.literal(set.ivPriority.joinToString(" > ") { STAT_NAMES[it] }).withStyle(ChatFormatting.WHITE))
        }
        for (note in set.notes) lines += Component.literal("! $note").withStyle(ChatFormatting.YELLOW)
        if (!set.fromMeta) lines += gray(lang.t("set.heuristic_long"))
        return lines
    }

    fun multiplier(m: Double): String = when {
        m == 0.0 -> "0x"
        m == m.toInt().toDouble() -> "${m.toInt()}x"
        else -> "${m}x"
    }

    /** "Charizard 5* · tera fire" */
    fun raidHeader(r: RaidView, lang: Lang): MutableComponent {
        val out = Component.literal(lang.t("raid.title") + ": ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(r.boss).withStyle(ChatFormatting.WHITE))
        if (r.stars > 0) out.append(gray(" ${r.stars}*"))
        if (r.teraType.isNotEmpty()) {
            out.append(Component.literal(" tera ").withStyle(ChatFormatting.AQUA)).append(type(r.teraType))
        } else {
            out.append(" ").append(rich(Lang.types(r.types)))
            if (r.feature.isNotEmpty() && r.feature != "default") out.append(gray(" (${r.feature})"))
        }
        return out
    }

    fun raidMoves(r: RaidView, lang: Lang): MutableComponent {
        val out = gray(lang.t("raid.boss_moves") + ": ")
        if (r.moves.isEmpty()) return out.append(gray(lang.t("raid.unknown_moves")))
        r.moves.forEachIndexed { i, id ->
            if (i > 0) out.append(gray(", "))
            out.append(move(id).withStyle(ChatFormatting.WHITE))
        }
        return out
    }

    /** "92%  Surf 2x" */
    fun raidPickSummary(p: RaidPickView): MutableComponent {
        val out = Component.literal("${p.score}%  ").withStyle(ChatFormatting.GRAY)
        if (p.move.isNotEmpty()) out.append(move(p.move).withStyle(ChatFormatting.WHITE)).append(" ")
        return out.append(Component.literal(multiplier(p.effectiveness)).withStyle(effectColor(p.effectiveness, true)))
    }

    fun raidPickTooltip(p: RaidPickView, lang: Lang): Component {
        val out = Component.literal(p.slot.name)
            .append(gray(if (p.slot.owned) " " + lang.t("tip.yours_level", p.slot.level) else " " + lang.t("tip.not_yours")))
            .append("\n").append(rich(Lang.types(p.slot.types)))
        out.append("\n").append(gray(lang.t("raid.attacks_with") + ": "))
        if (p.move.isNotEmpty()) out.append(move(p.move)) else out.append(lang.t("raid.own_type_moves"))
        out.append(Component.literal(" (${multiplier(p.effectiveness)})").withStyle(effectColor(p.effectiveness, true)))
        if (p.slot.owned && !p.moveReady) out.append(Component.literal(" " + lang.t("raid.must_learn")).withStyle(ChatFormatting.GOLD))
        out.append("\n").append(gray(lang.t("raid.worst_taken") + ": "))
            .append(Component.literal(multiplier(p.worstTaken)).withStyle(effectColor(p.worstTaken, false)))
        return out.append("\n\n").append(gray(lang.t("tip.click_add")))
    }

    fun effectColor(m: Double, attacking: Boolean): ChatFormatting = when {
        m > 1.0 -> if (attacking) ChatFormatting.GREEN else ChatFormatting.RED
        m < 1.0 -> if (attacking) ChatFormatting.RED else ChatFormatting.GREEN
        else -> ChatFormatting.WHITE
    }

    /** Versao para o chat (comando /teambuilder raid). */
    fun raidChat(r: RaidView, lang: Lang): Component {
        val out = raidHeader(r, lang).append("\n").append(raidMoves(r, lang))
        if (r.partySize > 0) out.append("\n").append(gray(lang.t("raid.party_size", r.partySize)))
        for ((title, picks) in listOf(lang.t("column.pc") to r.pcPicks, lang.t("column.all") to r.allPicks)) {
            out.append("\n").append(Component.literal("$title:").withStyle(ChatFormatting.YELLOW))
            if (picks.isEmpty()) out.append("\n").append(gray("  " + lang.t("empty.no_candidates")))
            for (p in picks) {
                out.append("\n  ").append(Component.literal(p.slot.name).withStyle(if (p.slot.owned) ChatFormatting.GREEN else ChatFormatting.WHITE))
                    .append(" ").append(raidPickSummary(p))
                    .append(gray(" · " + lang.t("raid.takes") + " ")).append(Component.literal(multiplier(p.worstTaken)).withStyle(effectColor(p.worstTaken, false)))
                if (p.slot.owned && !p.moveReady) out.append(Component.literal(" (" + lang.t("raid.must_learn") + ")").withStyle(ChatFormatting.GOLD))
            }
        }
        return out
    }

    /** Versao para o chat (comando /teambuilder sets). */
    fun chatLines(name: String, owned: Boolean, set: SetView, lang: Lang): Component {
        val out = Component.literal(name).withStyle(ChatFormatting.GOLD)
        for (line in setLines(set, owned, lang)) out.append("\n").append(line)
        return out
    }
}

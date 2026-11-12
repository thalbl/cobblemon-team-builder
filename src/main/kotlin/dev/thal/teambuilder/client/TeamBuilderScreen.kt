package dev.thal.teambuilder.client

import dev.thal.teambuilder.core.BuilderRequest
import dev.thal.teambuilder.core.BuilderState
import dev.thal.teambuilder.core.FormView
import dev.thal.teambuilder.core.Lang
import dev.thal.teambuilder.core.MoveView
import dev.thal.teambuilder.core.PickView
import dev.thal.teambuilder.core.RaidPickView
import dev.thal.teambuilder.core.RaidView
import dev.thal.teambuilder.core.RoleView
import dev.thal.teambuilder.core.SearchRequest
import dev.thal.teambuilder.core.SearchResult
import dev.thal.teambuilder.core.SetView
import dev.thal.teambuilder.core.SlotView
import dev.thal.teambuilder.core.SwapView
import dev.thal.teambuilder.core.TeamView
import dev.thal.teambuilder.net.Network
import dev.thal.teambuilder.net.RequestPayload
import dev.thal.teambuilder.net.SearchPayload
import dev.thal.teambuilder.text.SetText
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tela do construtor de time.
 *
 *   cabecalho   nota do time e opcoes (aplicar, salvar, party, limpar, 2v2, lendarios, megas)
 *   slots       os 6 Pokemon, com modelo e funcao; clicar seleciona, o X remove
 *   abas        Sugestoes | Times | Analise | Raid | Salvos
 *   busca       cards dos Pokemon que combinam com o que esta sendo digitado
 *   painel      formas, funcao, set sugerido (golpes, item, natureza, EVs) e prioridade de IV
 *
 * Nada de time e calculado aqui: cada acao pede um novo [BuilderState] ao servidor. O desenho e
 * refeito inteiro a cada frame, o que deixa as animacoes simples: tudo e funcao do tempo.
 */
class TeamBuilderScreen : Screen(Component.literal("Team Builder")) {
    private enum class Tab(val key: String) {
        SUGGEST("tab.suggest"), TEAMS("tab.teams"), ANALYSIS("tab.analysis"), RAID("tab.raid"), TRAINERS("tab.trainers"), SAVED("tab.saved")
    }

    private val lang: Lang get() = Lang.of(Minecraft.getInstance().languageManager.selected)
    private fun t(key: String, vararg args: Any?): String = lang.t(key, *args)

    private var state: BuilderState? = null
    private var waiting = false
    private var localError: String? = null
    private var localNotice: String? = null
    private var input: EditBox? = null

    private var tab = Tab.SUGGEST
    private var selected = 0

    /** null = modo normal; senao o texto do chefe ("" = detectar pela raid proxima). */
    private var raidQuery: String? = null

    /** Treinador do RCT selecionado na aba Treinadores, e a lista da busca de treinadores. */
    private var trainerId: String? = null
    private var trainerList: SearchResult? = null

    /** Analise: false = trocas sugeridas, true = ameacas ao time. */
    /** Parte de baixo da Analise: 0 = trocas, 1 = ameacas, 2 = duplas (so no 2v2). */
    private var analysisMode = 0

    /** Painel da direita: false = set sugerido, true = o que falta para montar o set. */
    private var showChecklist = false

    /** Funcao escolhida pelo jogador, pela chave da forma normal do slot. */
    private val roleChoice = HashMap<String, String>()

    // Busca enquanto digita
    private var search: SearchResult? = null
    private var searchSent = ""
    private var lastTyped = ""
    private var typedAt = 0L

    // Animacao
    private var lastFrame = Util.getMillis()
    private var dt = 0f
    private var contentAt = Util.getMillis()
    private val born = HashMap<String, Long>()
    private var advisorKey = ""
    private var advisorAt = 0L
    private val smooth = HashMap<String, Float>()
    private val hits = ArrayList<Hit>()

    /** Tooltips da tabela de tipos; entram por ultimo para ficar por cima dos cards. */
    private val typeTips = ArrayList<Hit>()

    /** Listas com rolagem desenhadas neste frame: chave, area e quanto da para rolar. */
    private class ScrollArea(val key: String, val x: Int, val y: Int, val w: Int, val h: Int, val max: Int)
    private val scrollAreas = ArrayList<ScrollArea>()
    private val scrolls = HashMap<String, Float>()
    private val scrollShown = HashMap<String, Float>()

    private val keys: List<String> get() = state?.team?.map { it.key } ?: emptyList()

    // ------------------------------------------------------------------ dados

    fun accept(newState: BuilderState) {
        val now = Util.getMillis()
        val before = keys.toSet()
        val arrived = newState.team.filter { it.key !in before }
        for (slot in arrived) born[slot.key] = now
        // Quem acabou de entrar vira o slot inspecionado.
        if (arrived.size == 1) selected = newState.team.indexOfFirst { it.key == arrived[0].key }
        selected = selected.coerceIn(0, max(0, newState.team.size - 1))

        if (newState.raid == null) raidQuery = null
        if (newState.trainer == null) trainerId = null
        if (newState.error == null && arrived.isNotEmpty()) clearInput()
        // Um time diferente traz outras sugestoes: as listas voltam ao topo.
        if (newState.team.map { it.key } != state?.team?.map { it.key }) {
            scrolls.clear()
            scrollShown.clear()
        }
        state = newState
        waiting = false
        contentAt = now
    }

    fun acceptSearch(result: SearchResult) {
        if (result.query != typed()) return
        if (result.kind == "trainer") trainerList = result else search = result
    }

    private fun request(
        keys: List<String> = this.keys,
        add: String? = null,
        useParty: Boolean = false,
        legendaries: Boolean? = null,
        megas: Boolean? = null,
        doubles: Boolean? = null,
        apply: Boolean = false,
    ) {
        if (!ClientPlayNetworking.canSend(RequestPayload.TYPE)) {
            localError = t("error.no_server")
            return
        }
        waiting = true
        localError = null
        localNotice = null
        val roles = keys.map { roleChoice[baseKeyOf(it)] }
        val body = BuilderRequest(keys, add, useParty, legendaries, megas, doubles, raidQuery, roles, apply, trainerId)
        ClientPlayNetworking.send(RequestPayload(Network.GSON.toJson(body)))
    }

    /** Chave da forma normal do slot: a funcao escolhida segue o Pokemon quando ele vira mega. */
    private fun baseKeyOf(key: String): String {
        if (key.startsWith("uuid:")) return key.substringBefore('|')
        val slot = state?.team?.firstOrNull { it.key == key }
        return slot?.forms?.firstOrNull { !it.mega }?.key ?: key
    }

    private fun typed(): String = input?.value?.trim().orEmpty()

    private fun clearInput() {
        input?.value = ""
        search = null
    }

    private fun addTyped() {
        val text = typed()
        if (text.isNotEmpty()) request(add = text)
    }

    private fun openTab(next: Tab) {
        if (next == tab) return
        val leaving = tab == Tab.RAID || (tab == Tab.TRAINERS && trainerId != null)
        tab = next
        contentAt = Util.getMillis()
        // A busca muda de sentido (Pokemon x treinadores): refaz para o texto atual.
        sendSearch(typed())
        when {
            next == Tab.RAID -> {
                // Entrar na aba ja tenta achar a raid em que o jogador esta.
                raidQuery = ""
                trainerId = null
                request()
            }
            leaving -> {
                raidQuery = null
                trainerId = null
                request()
            }
        }
    }

    private fun sendSearch(text: String) {
        searchSent = text
        if (!ClientPlayNetworking.canSend(SearchPayload.TYPE)) return
        when {
            tab == Tab.TRAINERS -> ClientPlayNetworking.send(SearchPayload(Network.GSON.toJson(SearchRequest(text, "trainer"))))
            text.length >= 2 && text.none { it in ",;+/" } -> ClientPlayNetworking.send(SearchPayload(Network.GSON.toJson(SearchRequest(text))))
            else -> search = null
        }
    }

    private fun chooseTrainer(id: String) {
        trainerId = id
        contentAt = Util.getMillis()
        request()
    }

    private fun chooseRole(slot: SlotView, role: String) {
        roleChoice[baseKeyOf(slot.key)] = role
        request()
    }

    /** Troca a forma do slot; como so cabe uma mega por batalha, outra mega do time volta ao normal. */
    private fun switchForm(slot: SlotView, form: FormView) {
        if (form.key == slot.key) return
        val team = state?.team ?: return
        val next = team.map { other ->
            when {
                other.key == slot.key -> form.key
                form.mega && other.mega -> other.forms.firstOrNull { !it.mega }?.key ?: other.key
                else -> other.key
            }
        }
        request(keys = next)
    }

    private fun pickSearch(slot: SlotView) {
        val s = state ?: return
        if (tab == Tab.RAID && s.raid == null) {
            raidQuery = slot.name
            clearInput()
            request()
            return
        }
        when {
            keys.size >= 6 -> localError = t("error.team_full", 6)
            slot.species.isNotEmpty() && s.team.any { it.species == slot.species } -> localError = t("error.already_in_team", slot.name)
            else -> {
                clearInput()
                request(keys = keys + slot.key)
            }
        }
    }

    private fun saveTeam(s: BuilderState) {
        if (s.team.isEmpty()) {
            localError = t("saved.empty_team")
            return
        }
        val name = typed().ifEmpty { t("saved.default_name", SavedTeams.teams.size + 1) }
        val members = s.team.mapIndexed { i, slot ->
            val set = s.sets.getOrNull(i)
            SavedMember(
                slot.key, slot.name, slot.species, slot.aspects, slot.types, slot.owned,
                roleChoice[baseKeyOf(slot.key)], set?.roleLabel.orEmpty(),
            )
        }
        SavedTeams.add(SavedTeam(name, members, s.total, s.doubles, System.currentTimeMillis()))
        clearInput()
        localError = null
        localNotice = t("saved.done", name)
    }

    private fun loadSaved(team: SavedTeam) {
        for (m in team.members) {
            val base = if (m.key.startsWith("uuid:")) m.key.substringBefore('|') else m.key
            if (m.role != null) roleChoice[base] = m.role else roleChoice.remove(base)
        }
        val doubles = state?.doubles
        request(keys = team.members.map { it.key }, doubles = if (doubles != team.doubles) team.doubles else null)
        openTab(Tab.SUGGEST)
    }

    // ----------------------------------------------------------------- layout

    private val pad get() = 6
    private val bodyTop get() = 68
    private val bodyBottom get() = height - 6
    private val rightWidth get() = (width * 0.34f).toInt().coerceIn(160, 230)
    private val leftWidth get() = width - pad * 2 - rightWidth - 4
    private val rightX get() = pad + leftWidth + 4
    private val inputX get() = pad + leftWidth - 88

    override fun init() {
        val box = EditBox(font, inputX, bodyTop + 1, 72, 12, Component.literal("Pokémon"))
        box.setMaxLength(200)
        box.setHint(Component.literal(t("input.hint")).withStyle(ChatFormatting.DARK_GRAY))
        box.value = input?.value.orEmpty()
        addWidget(box)
        input = box
        if (state == null && !waiting && localError == null) request(useParty = true)
    }

    override fun isPauseScreen() = false

    override fun tick() {
        super.tick()
        val text = typed()
        val time = now()
        if (text != lastTyped) {
            lastTyped = text
            typedAt = time
        }
        // Espera o jogador parar de digitar um instante antes de buscar.
        if (text != searchSent && time - typedAt >= 120) sendSearch(text)
    }

    private fun searchResults(): List<SlotView> {
        if (tab == Tab.TRAINERS) return emptyList()
        val s = search ?: return emptyList()
        val text = typed()
        return if (text.length >= 2 && s.query == text) s.results else emptyList()
    }

    // ------------------------------------------------------------- animacao

    private fun now() = Util.getMillis()

    /** Valor que persegue [target] suavemente; usado para hover, seletores e barras. */
    private fun smooth(key: String, target: Float, speed: Float = 14f): Float {
        val current = smooth[key] ?: target
        val next = current + (target - current) * min(1f, dt * speed)
        smooth[key] = next
        return next
    }

    private fun easeOut(t: Float): Float {
        val k = 1f - t.coerceIn(0f, 1f)
        return 1f - k * k * k
    }

    /** 0..1 da entrada do item [index] da lista atual; cada item entra um pouco depois do anterior. */
    private fun enter(index: Int): Float = easeOut((now() - contentAt - index * 28L) / 190f)

    /** Pulso lento 0..1, para destacar o que esta selecionado ou esperando escolha. */
    private fun pulse(periodMs: Float = 1400f): Float = (sin(now() / periodMs * 2 * PI).toFloat() + 1f) / 2f

    private fun click() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }

    // ---------------------------------------------------------------- render

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val time = now()
        dt = ((time - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = time
        hits.clear()
        typeTips.clear()
        scrollAreas.clear()

        val ui = Ui(graphics, font, mouseX, mouseY, partialTick, hits)
        ui.rect(0, 0, width, height, Palette.DIM)

        val s = state
        if (s == null) {
            val message = localError ?: (t("status.reading") + ".".repeat(1 + ((time / 350) % 3).toInt()))
            ui.textCentered(message, width / 2, height / 2 - 4, if (localError != null) Palette.BAD else Palette.TEXT)
            return
        }

        // A lista da busca fica por cima de tudo: os modelos que estariam embaixo dela nao sao desenhados.
        PokemonModels.occluder = dropdownRect()
        header(ui, s)
        slots(ui, s)
        tabs(ui, s)
        val top = bodyTop + 17
        when (tab) {
            Tab.SUGGEST -> suggestions(ui, s, top)
            Tab.TEAMS -> teams(ui, s, top)
            Tab.ANALYSIS -> analysis(ui, s, top)
            Tab.RAID -> raid(ui, s, top)
            Tab.TRAINERS -> trainers(ui, s, top)
            Tab.SAVED -> saved(ui, top)
        }
        advisor(ui, s)
        searchDropdown(ui, s)
        input?.render(graphics, mouseX, mouseY, partialTick)

        hits += typeTips
        hits.lastOrNull { it.tooltip != null && it.contains(mouseX.toDouble(), mouseY.toDouble()) }?.tooltip?.let {
            graphics.renderTooltip(font, font.split(it, 220), mouseX, mouseY)
        }
    }

    // ------------------------------------------------------------- cabecalho

    private fun header(ui: Ui, s: BuilderState) {
        val y = 4
        ui.panel(pad, y, width - pad * 2, 16)
        ui.text("TEAM BUILDER", pad + 6, y + 4, Palette.GOLD)

        var x = pad + 6 + ui.width("TEAM BUILDER") + 10
        if (s.team.isNotEmpty()) {
            val label = t("label.score")
            val shown = smooth("score", s.total.toFloat(), 6f)
            ui.text(label, x, y + 4, Palette.MUTED)
            val value = "%.1f".format(shown)
            ui.text(value, x + ui.width(label) + 4, y + 4, Palette.TEXT)
            val w = ui.width(label) + 4 + ui.width(value)
            ui.hit(
                x, y, w, 16,
                Component.literal(t("tip.score", "%.1f".format(s.total), "%.1f".format(s.strength), "%.1f".format(s.defense),
                    "%.1f".format(s.offense), "%.1f".format(s.roles), "%.1f".format(s.synergy), "%.1f".format(s.coresScore))),
            )
            x += w + 10
        }
        // Nucleos ativos (chuva, Trick Room...): quem ativa e quem aproveita.
        for (c in s.cores) {
            val cw = ui.width(c.label)
            ui.rect(x - 3, y + 3, cw + 6, 11, Palette.alpha(Palette.mix(Palette.CARD, Palette.CYAN, 0.25f), 1f))
            ui.text(c.label, x, y + 4, Palette.CYAN)
            ui.hit(
                x - 3, y, cw + 6, 16,
                Component.literal(t("core.title", c.label)).withStyle(ChatFormatting.AQUA)
                    .append("\n").append(Component.literal(t("core.setters", c.setters.joinToString())).withStyle(ChatFormatting.GRAY))
                    .append("\n").append(Component.literal(t("core.abusers", c.abusers.joinToString().ifEmpty { "-" })).withStyle(ChatFormatting.GRAY))
                    .append("\n\n").append(Component.literal(t("core.hint")).withStyle(ChatFormatting.DARK_GRAY)),
            )
            x += cw + 10
        }

        // Opcoes, da direita para a esquerda.
        var right = width - pad - 3
        right = toggle(ui, right, y + 2, "Megas", s.megas, t("tip.megas")) { request(megas = !s.megas) }
        right = toggle(ui, right, y + 2, t("option.legendaries_ui"), s.legendaries, t("tip.legendaries")) {
            request(legendaries = !s.legendaries)
        }
        right = toggle(ui, right, y + 2, "2v2", s.doubles, t("tip.doubles")) { request(doubles = !s.doubles) }
        right = button(ui, right, y + 2, t("btn.clear"), t("tip.clear")) {
            roleChoice.clear()
            request(keys = emptyList())
        }
        right = button(ui, right, y + 2, t("btn.party"), t("tip.party")) {
            roleChoice.clear()
            request(useParty = true)
        }
        right = button(ui, right, y + 2, t("btn.save"), t("tip.save"), Palette.CYAN) { saveTeam(s) }
        right = button(ui, right, y + 2, t("btn.apply"), t("tip.apply"), Palette.OWNED) {
            if (s.team.none { it.owned }) localError = t("apply.none_owned") else request(apply = true)
        }

        val room = right - x - 4
        val error = localError ?: s.error
        val notice = localNotice ?: s.notice
        when {
            error != null -> ui.text(ui.fit(error, room), x, y + 4, Palette.BAD)
            waiting -> ui.text(t("status.calculating") + ".".repeat(1 + ((now() / 300) % 3).toInt()), x, y + 4, Palette.FAINT)
            notice != null -> ui.text(ui.fit(notice, room), x, y + 4, Palette.GOOD)
        }
    }

    /** Botao de texto alinhado pela direita; devolve o x livre a esquerda dele. */
    private fun button(ui: Ui, right: Int, y: Int, label: String, tip: String, color: Int = Palette.TEXT, onClick: () -> Unit): Int {
        val w = ui.width(label) + 8
        val x = right - w
        val hover = smooth("btn:$label", if (ui.over(x, y, w, 12)) 1f else 0f)
        ui.panel(x, y, w, 12, Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), Palette.mix(Palette.EDGE, color, hover))
        ui.text(label, x + 4, y + 2, color)
        ui.hit(x, y, w, 12, Component.literal(tip), onClick)
        return x - 3
    }

    /** Interruptor com rotulo; o botao desliza entre desligado e ligado. */
    private fun toggle(ui: Ui, right: Int, y: Int, label: String, on: Boolean, tip: String, onClick: () -> Unit): Int {
        val w = ui.width(label) + 22
        val x = right - w
        val hover = smooth("tgl:$label", if (ui.over(x, y, w, 12)) 1f else 0f)
        val k = smooth("tglv:$label", if (on) 1f else 0f)
        ui.panel(x, y, w, 12, Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), Palette.EDGE)
        ui.text(label, x + 4, y + 2, Palette.mix(Palette.MUTED, Palette.TEXT, k))
        val trackX = x + w - 16
        ui.rect(trackX, y + 4, 12, 4, Palette.mix(Palette.FAINT, Palette.GOOD, k))
        ui.rect(trackX + (k * 8).toInt(), y + 3, 4, 6, Palette.TEXT)
        ui.hit(x, y, w, 12, Component.literal("$label: ${if (on) t("common.yes") else t("common.no")}\n\n$tip"), onClick)
        return x - 3
    }

    // ------------------------------------------------------------------ slots

    private fun slots(ui: Ui, s: BuilderState) {
        val y = 24
        val h = 40
        val gap = 3
        val w = (width - pad * 2 - gap * 5) / 6
        val nextEmpty = s.team.size
        for (i in 0 until 6) {
            val x = pad + i * (w + gap)
            val slot = s.team.getOrNull(i)
            if (slot == null) {
                val target = i == nextEmpty
                val glow = if (target) 0.35f + 0.65f * pulse() else 0f
                ui.panel(x, y, w, h, Palette.alpha(Palette.CARD, 0.45f), Palette.mix(Palette.PANEL_EDGE, Palette.OWNED, glow))
                ui.textCentered("+", x + w / 2, y + 11, Palette.mix(Palette.FAINT, Palette.OWNED, glow))
                ui.textCentered(if (target) t("slot.next") else t("slot.empty"), x + w / 2, y + 24, Palette.FAINT, 0.75f)
                ui.hit(x, y, w, h, Component.literal(t("slot.empty_tip", i + 1))) { openTab(Tab.SUGGEST); setFocused(input) }
                continue
            }

            val set = s.sets.getOrNull(i)
            val isSelected = i == selected
            val hover = smooth("slot:$i", if (ui.over(x, y, w, h)) 1f else 0f)
            // Quem acabou de entrar no time "salta" por um instante.
            val age = (now() - (born[slot.key] ?: 0L)) / 320f
            val pop = if (age < 1f) sin(age * PI).toFloat() else 0f
            val accent = accentOf(slot)
            val edge = when {
                isSelected -> Palette.mix(Palette.GOLD, Palette.TEXT, pulse() * 0.35f)
                else -> Palette.mix(Palette.EDGE, accent, max(hover, pop))
            }
            ui.panel(x, y, w, h, Palette.mix(Palette.CARD, Palette.CARD_HOVER, max(hover, pop)), edge)
            if (isSelected) ui.rect(x + 1, y + h - 3, w - 2, 2, Palette.GOLD)

            PokemonModels.draw(
                ui.g, "slot:${slot.key}", slot.species, slot.aspects, x + 3, y + 5, 28,
                partialTick = if (isSelected || hover > 0.5f) ui.partialTick else 0f,
                zoom = 1f + 0.08f * hover + 0.25f * pop,
            )
            val tx = x + 34
            ui.text(ui.fit(slot.name, w - 36 - 9), tx, y + 4, accent)
            val afterTypes = typeIcons(ui, slot.types, slot.ability, slot.name, tx, y + 15)
            if (slot.mega) ui.text("M", afterTypes + 1, y + 16, Palette.GOLD, 0.75f)
            if (slot.inParty) ui.rect(x + 3, y + 3, 3, 3, Palette.OWNED)
            val pairIdx = if (s.doubles) pairOf(s, i) else -1
            if (pairIdx >= 0) {
                // Faixa na cor da dupla; a letra diz qual (A = abertura).
                val pc = pairColors[pairIdx % pairColors.size]
                ui.rect(x + 1, y + 1, 2, h - 2, pc)
                ui.text(('A' + pairIdx).toString(), x + 4, y + h - 9, pc, 0.75f)
            }
            val roleText = set?.roleLabel?.takeIf { it.isNotEmpty() } ?: slot.roles.take(2).joinToString(", ")
            ui.text(ui.fit(roleText, w - 36, 0.75f), tx, y + 28, if (set != null) Palette.CYAN else Palette.MUTED, 0.75f)

            ui.hit(x, y, w, h, slotTooltip(slot, set)) { selected = i }

            // X de remover, visivel ao passar o mouse.
            val closeOver = ui.over(x + w - 10, y + 1, 9, 9)
            if (hover > 0.05f) ui.text("x", x + w - 8, y + 2, Palette.alpha(if (closeOver) Palette.BAD else Palette.MUTED, hover))
            ui.hit(x + w - 10, y + 1, 9, 9, Component.literal(t("slot.remove", slot.name))) {
                request(keys = keys.filter { it != slot.key })
            }
        }
    }

    private fun slotTooltip(slot: SlotView, set: SetView?): Component {
        val out = Component.literal(slot.name)
            .append(Component.literal("  " + if (slot.owned) t("tip.yours") else t("tip.not_yours")).withStyle(ChatFormatting.GRAY))
            .append("\n").append(Component.literal(slot.info).withStyle(ChatFormatting.GRAY))
        if (slot.inParty) out.append("\n").append(Component.literal(t("tip.in_party")).withStyle(ChatFormatting.GREEN))
        if (set != null && set.roleLabel.isNotEmpty()) {
            out.append("\n").append(Component.literal(t("label.role") + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(set.roleLabel).withStyle(ChatFormatting.AQUA))
        }
        out.append("\n").append(Component.literal(slot.roles.joinToString(", ")).withStyle(ChatFormatting.DARK_GRAY))
        return out.append("\n\n").append(Component.literal(t("tip.click_set")).withStyle(ChatFormatting.GRAY))
    }

    // ------------------------------------------------------------------- abas

    private fun tabs(ui: Ui, s: BuilderState) {
        val y = bodyTop
        var x = pad
        var activeX = x
        var activeW = 0
        for (tb in Tab.entries) {
            val label = t(tb.key)
            val w = ui.width(label) + 12
            val active = tb == tab
            val hover = smooth("tab:${tb.name}", if (ui.over(x, y, w, 14)) 1f else 0f)
            val color = when {
                active -> tabColor(tb)
                else -> Palette.mix(Palette.MUTED, Palette.TEXT, hover)
            }
            ui.rect(x, y, w, 14, Palette.alpha(Palette.CARD, 0.5f + 0.5f * max(hover, if (active) 1f else 0f)))
            ui.text(label, x + 6, y + 3, color)
            if (tb == Tab.SAVED && SavedTeams.teams.isNotEmpty()) {
                ui.text("${SavedTeams.teams.size}", x + w - 5, y + 1, Palette.FAINT, 0.6f)
            }
            ui.hit(x, y, w, 14, null) { openTab(tb) }
            if (active) {
                activeX = x
                activeW = w
            }
            x += w + 2
        }
        // O sublinhado desliza ate a aba ativa.
        val lineX = smooth("tabline:x", activeX.toFloat(), 18f).toInt()
        val lineW = smooth("tabline:w", activeW.toFloat(), 18f).toInt()
        ui.rect(lineX, y + 13, lineW, 2, tabColor(tab))

        // Campo de nome + adicionar, no fim da linha das abas.
        val addX = pad + leftWidth - 14
        val hover = smooth("btn:add", if (ui.over(addX, y + 1, 14, 12)) 1f else 0f)
        ui.panel(addX, y + 1, 14, 12, Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), Palette.mix(Palette.EDGE, Palette.OWNED, hover))
        ui.textCentered("+", addX + 7, y + 3, Palette.OWNED)
        ui.hit(addX, y + 1, 14, 12, Component.literal(t("tip.add"))) { addTyped() }
    }

    private fun tabColor(tb: Tab) = when (tb) {
        Tab.RAID, Tab.TRAINERS -> Palette.RAID
        Tab.SAVED -> Palette.CYAN
        else -> Palette.GOLD
    }

    // --------------------------------------------------------------- colunas

    private fun columnWidth() = (leftWidth - 4) / 2

    private fun columnTitles(ui: Ui, s: BuilderState, y: Int) {
        val w = columnWidth()
        ui.text(t("column.pc_upper"), pad, y, Palette.OWNED, 0.75f)
        ui.textRight(t("column.count", s.ownedCount), pad + w, y, Palette.FAINT, 0.75f)
        ui.text(t("column.all_upper"), pad + w + 4, y, Palette.CATALOG, 0.75f)
    }

    /** Fundo de um card com animacao de entrada e de hover. Devolve (x animado, alfa, hover). */
    private fun card(ui: Ui, key: String, index: Int, x: Int, y: Int, w: Int, h: Int, accent: Int, tip: Component?, onClick: (() -> Unit)?): Triple<Int, Float, Float> {
        val tt = enter(index)
        val ax = x + ((1f - tt) * 10f).toInt()
        val hover = smooth("card:$key", if (ui.over(x, y, w, h)) 1f else 0f)
        ui.panel(
            ax, y, w, h,
            Palette.alpha(Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), tt),
            Palette.alpha(Palette.mix(Palette.EDGE, accent, hover), tt),
        )
        if (hover > 0.02f) ui.rect(ax, y, 2, h, Palette.alpha(accent, hover * tt))
        ui.hit(x, y, w, h, tip, onClick)
        return Triple(ax, tt, hover)
    }

    private fun accentOf(slot: SlotView) = if (slot.owned) Palette.OWNED else Palette.CATALOG

    private fun empty(ui: Ui, x: Int, y: Int, text: String) = ui.text(text, x + 2, y + 4, Palette.FAINT, 0.75f)

    private fun reasonsTip(title: MutableComponent, info: String, reasons: List<String>, hint: String): Component {
        if (info.isNotEmpty()) title.append("\n").append(Component.literal(info).withStyle(ChatFormatting.GRAY))
        for (reason in reasons) title.append("\n").append(Component.literal("- ").withStyle(ChatFormatting.GRAY)).append(SetText.rich(reason, ChatFormatting.GRAY))
        return title.append("\n\n").append(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY))
    }

    // -------------------------------------------------------------- sugestoes

    private fun suggestions(ui: Ui, s: BuilderState, top: Int) {
        columnTitles(ui, s, top)
        val w = columnWidth()
        if (s.team.size >= 6) {
            ui.text(t("full.title"), pad + 2, top + 14, Palette.TEXT)
            ui.text(t("full.suggest_hint"), pad + 2, top + 26, Palette.MUTED, 0.75f)
            return
        }
        pickColumn(ui, "pc", pad, top + 9, w, s.pcPicks)
        pickColumn(ui, "all", pad + w + 4, top + 9, w, s.allPicks)
    }

    private fun pickColumn(ui: Ui, column: String, x: Int, top: Int, w: Int, picks: List<PickView>) {
        if (picks.isEmpty()) return empty(ui, x, top, t("empty.no_suggestions"))
        val h = min(31, (bodyBottom - top) / picks.size - 2)
        for ((i, p) in picks.withIndex()) {
            val y = top + i * (h + 2)
            val tip = reasonsTip(
                Component.literal(p.slot.name).append(Component.literal("  +%.1f".format(p.gain)).withStyle(ChatFormatting.GREEN)),
                p.slot.info, p.reasons, t("tip.click_add"),
            )
            val (ax, a, hover) = card(ui, "$column:${p.slot.key}", i, x, y, w, h, accentOf(p.slot), tip) { request(keys = keys + p.slot.key) }
            val box = h - 5
            if (a > 0.6f) {
                PokemonModels.draw(ui.g, "pick:$column:${p.slot.key}", p.slot.species, p.slot.aspects, ax + 3, y + 3, box, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.12f * hover)
            }
            val tx = ax + box + 7
            val gain = "+%.1f".format(p.gain)
            ui.textRight(gain, ax + w - 4, y + 3, Palette.alpha(Palette.GOOD, a))
            ui.text(ui.fit(p.slot.name, w - box - 12 - ui.width(gain)), tx, y + 3, Palette.alpha(accentOf(p.slot), a))
            typeIcons(ui, p.slot.types, p.slot.ability, p.slot.name, tx, y + 13, a)
            if (h >= 29) {
                ui.rich(p.reasons.firstOrNull().orEmpty(), tx + 22, y + 15, Palette.alpha(Palette.MUTED, a), 0.75f, w - box - 10 - 22)
                ui.rich(p.reasons.getOrNull(1).orEmpty(), tx, y + 23, Palette.alpha(Palette.FAINT, a), 0.75f, w - box - 10)
            }
        }
    }

    // ------------------------------------------------------------------ times

    private fun teams(ui: Ui, s: BuilderState, top: Int) {
        columnTitles(ui, s, top)
        val w = columnWidth()
        if (s.team.size >= 6) {
            ui.text(t("full.title"), pad + 2, top + 14, Palette.TEXT)
            ui.text(t("full.teams_hint"), pad + 2, top + 26, Palette.MUTED, 0.75f)
            return
        }
        teamColumn(ui, "pc", pad, top + 9, w, s.pcTeams)
        teamColumn(ui, "all", pad + w + 4, top + 9, w, s.allTeams)
    }

    private fun teamColumn(ui: Ui, column: String, x: Int, top: Int, w: Int, teams: List<TeamView>) {
        if (teams.isEmpty()) return empty(ui, x, top, t("empty.cannot_complete"))
        // Os cartoes encolhem um pouco para caber; se ainda faltar altura, a coluna rola.
        val gap = 3
        val viewH = bodyBottom - top
        val h = ((viewH + gap) / teams.size - gap).coerceIn(38, 46)
        val contentH = teams.size * (h + gap) - gap
        val offset = scrolled(ui, "teams:$column", x, top, w, viewH, contentH)
        if (offset != null) {
            ui.g.enableScissor(x - 2, top, x + w + 2, top + viewH)
            ui.clip = intArrayOf(x - 2, top, x + w + 2, top + viewH)
        }
        val shift = offset ?: 0
        for ((i, tm) in teams.withIndex()) {
            val y = top + i * (h + gap) - shift
            if (y + h <= top || y >= top + viewH) continue
            val tip = Component.literal(t("teams.option", i + 1)).append(Component.literal("  " + t("label.score_lower") + " %.1f".format(tm.total)).withStyle(ChatFormatting.GREEN))
            for (m in tm.added) tip.append("\n").append(Component.literal("+ ${m.name}").withStyle(if (m.owned) ChatFormatting.GREEN else ChatFormatting.AQUA))
            tip.append("\n\n").append(Component.literal(t("tip.click_use_team")).withStyle(ChatFormatting.DARK_GRAY))

            val accent = if (column == "pc") Palette.OWNED else Palette.CATALOG
            val (ax, a, hover) = card(ui, "team:$column:$i", i, x, y, w, h, accent, tip) { request(keys = tm.keys) }
            ui.text(t("teams.option", i + 1), ax + 5, y + 4, Palette.alpha(Palette.TEXT, a))
            ui.textRight(t("label.score_lower") + " %.1f".format(tm.total), ax + w - 5, y + 4, Palette.alpha(Palette.GOOD, a))
            if (a < 0.6f || tm.added.isEmpty()) continue
            val box = min(min(26, h - 18), (w - 10) / tm.added.size - 2)
            for ((j, m) in tm.added.withIndex()) {
                // Ao passar o mouse os membros "acenam" em sequencia.
                val wave = hover * (0.5f + 0.5f * sin(now() / 160.0 - j * 0.9).toFloat())
                PokemonModels.draw(ui.g, "team:$column:$i:${m.key}", m.species, m.aspects, ax + 5 + j * (box + 2), y + 14, box, zoom = 1f + 0.14f * wave)
            }
        }
        if (offset != null) {
            ui.clip = null
            ui.g.disableScissor()
            scrollBar(ui, "teams:$column", x + w + 1, top, viewH, contentH, shift)
        }
    }

    /**
     * Registra uma lista com rolagem e devolve quanto ela esta rolada (em pixels, suavizado),
     * ou null quando tudo cabe e nao precisa rolar.
     */
    private fun scrolled(ui: Ui, key: String, x: Int, y: Int, w: Int, viewH: Int, contentH: Int): Int? {
        val max = contentH - viewH
        if (max <= 0) {
            scrolls.remove(key)
            scrollShown.remove(key)
            return null
        }
        scrollAreas += ScrollArea(key, x, y, w, viewH, max)
        val target = (scrolls[key] ?: 0f).coerceIn(0f, max.toFloat())
        scrolls[key] = target
        val shown = scrollShown[key] ?: target
        val next = shown + (target - shown) * 0.35f
        scrollShown[key] = if (kotlin.math.abs(target - next) < 0.5f) target else next
        return scrollShown.getValue(key).toInt()
    }

    /** Barra fina ao lado da lista, com a alca proporcional ao que esta visivel. */
    private fun scrollBar(ui: Ui, key: String, x: Int, y: Int, viewH: Int, contentH: Int, offset: Int) {
        val hover = smooth("scroll:$key", if (ui.over(x - 6, y, 9, viewH)) 1f else 0f)
        ui.rect(x, y, 2, viewH, Palette.alpha(Palette.EDGE, 0.6f))
        val handle = max(12, viewH * viewH / contentH)
        val hy = y + ((viewH - handle) * offset.toFloat() / (contentH - viewH)).toInt()
        ui.rect(x, hy, 2, handle, Palette.mix(Palette.MUTED, Palette.GOLD, hover))
    }

    // ---------------------------------------------------------------- analise

    private fun analysis(ui: Ui, s: BuilderState, top: Int) {
        if (s.team.isEmpty()) return empty(ui, pad, top, t("analysis.empty"))
        ui.text(t("analysis.defense"), pad, top, Palette.MUTED, 0.75f)
        ui.textRight(t("analysis.legend"), pad + leftWidth, top, Palette.FAINT, 0.75f)

        val cols = 9
        val gap = 2
        val cw = (leftWidth - gap * (cols - 1)) / cols
        val ch = 21
        for ((i, b) in s.typeBalance.withIndex()) {
            val x = pad + (i % cols) * (cw + gap)
            val y = top + 9 + (i / cols) * (ch + gap)
            val net = b.resist - b.weak
            val exposed = b.weak >= 2 && b.weak > b.resist
            val tt = enter(i / 3)
            val tint = when {
                exposed -> Palette.mix(Palette.CARD, Palette.BAD, 0.22f + 0.12f * pulse(1100f))
                net > 0 -> Palette.mix(Palette.CARD, Palette.GOOD, 0.12f)
                else -> Palette.CARD
            }
            ui.panel(x, y, cw, ch, Palette.alpha(tint, tt), Palette.alpha(if (exposed) Palette.BAD else Palette.PANEL_EDGE, tt))
            ui.types(listOf(b.type), x + 3, y + 2, tt)
            val label = if (net > 0) "+$net" else "$net"
            ui.textRight(label, x + cw - 3, y + 3, Palette.alpha(if (net > 0) Palette.GOOD else if (net < 0) Palette.BAD else Palette.FAINT, tt))
            // Marcas de quem e imune e de quem leva 4x, para ver de longe sem abrir o tooltip.
            val immune = b.multipliers.count { it == 0.0 }
            val quad = b.multipliers.count { it >= 4.0 }
            var mx = x + 3
            if (immune > 0) {
                val m = "0x$immune"
                ui.text(m, mx, y + 13, Palette.alpha(Palette.CYAN, tt), 0.6f)
                mx += ui.width(m, 0.6f) + 3
            }
            if (quad > 0) ui.text("4x$quad", mx, y + 13, Palette.alpha(Palette.BAD, tt), 0.6f)
            ui.hit(
                x, y, cw, ch,
                typeBalanceTip(s, b),
            )
        }

        val swapTop = top + 9 + 2 * (ch + gap) + 5
        val w = columnWidth()
        // Alterna entre trocas sugeridas e ameacas ao time.
        var tx = pad
        val modes = listOf(0 to t("analysis.swaps"), 1 to t("analysis.threats") + " (${s.threats.size})") +
            (if (s.doubles && s.pairs.isNotEmpty()) listOf(2 to t("analysis.pairs")) else emptyList())
        if (modes.none { it.first == analysisMode }) analysisMode = 0
        for ((threatMode, label) in modes) {
            val active = analysisMode == threatMode
            val lw = ui.width(label, 0.75f) + 8
            val hover = smooth("amode:$threatMode", if (ui.over(tx, swapTop - 2, lw, 10)) 1f else 0f)
            ui.panel(
                tx, swapTop - 2, lw, 10,
                if (active) Palette.mix(Palette.CARD, Palette.GOLD, 0.2f) else Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover),
                if (active) Palette.GOLD else Palette.EDGE,
            )
            ui.text(label, tx + 4, swapTop, if (active) Palette.GOLD else Palette.mix(Palette.MUTED, Palette.TEXT, hover), 0.75f)
            val modeTip = when (threatMode) { 1 -> "analysis.threats_tip"; 2 -> "analysis.pairs_tip"; else -> "analysis.swaps_tip" }
            ui.hit(tx, swapTop - 2, lw, 10, Component.literal(t(modeTip))) {
                analysisMode = threatMode
                contentAt = now()
            }
            tx += lw + 3
        }
        if (analysisMode == 1) {
            threatColumns(ui, s, swapTop + 11)
            return
        }
        if (analysisMode == 2) {
            pairColumns(ui, s, swapTop + 11)
            return
        }
        ui.text(t("column.pc"), tx + 3, swapTop, Palette.OWNED, 0.75f)
        ui.text(t("column.all"), pad + w + 4, swapTop, Palette.CATALOG, 0.75f)
        swapColumn(ui, "pc", pad, swapTop + 11, w, s.pcSwaps)
        swapColumn(ui, "all", pad + w + 4, swapTop + 11, w, s.allSwaps)
    }

    /** Cor de cada dupla: a abertura em dourado, as do banco em ciano e lilas. */
    private val pairColors = intArrayOf(Palette.GOLD, Palette.CYAN, 0xFFC59BFF.toInt())

    /** Indice da dupla de quem esta no slot [i], ou -1. */
    private fun pairOf(s: BuilderState, i: Int): Int = s.pairs.indexOfFirst { it.a == i || it.b == i }

    private fun pairTip(s: BuilderState, p: dev.thal.teambuilder.core.PairView): Component {
        val a = s.team.getOrNull(p.a)?.name.orEmpty()
        val b = s.team.getOrNull(p.b)?.name.orEmpty()
        val out = Component.literal("$a + $b").withStyle(if (p.lead) ChatFormatting.GOLD else ChatFormatting.WHITE)
            .append(Component.literal("  " + t("label.score_lower") + " %.1f".format(p.score)).withStyle(if (p.score >= 0) ChatFormatting.GREEN else ChatFormatting.RED))
        if (p.lead) out.append("\n").append(Component.literal(t("pair.lead_tip")).withStyle(ChatFormatting.GOLD))
        for (r in p.good) out.append("\n").append(Component.literal("+ ").withStyle(ChatFormatting.GREEN)).append(SetText.rich(SetText.moves(r), ChatFormatting.GRAY))
        for (r in p.bad) out.append("\n").append(Component.literal("- ").withStyle(ChatFormatting.RED)).append(SetText.rich(SetText.moves(r), ChatFormatting.GRAY))
        return out
    }

    /** Duplas do 2v2: cartoes a esquerda (abertura primeiro) e a nota de todos os pares a direita. */
    private fun pairColumns(ui: Ui, s: BuilderState, top: Int) {
        if (s.pairs.isEmpty()) return empty(ui, pad, top, t("pair.none"))
        val w = columnWidth()
        val h = 34
        val best = s.pairs.maxOf { it.score }.coerceAtLeast(0.1)
        for ((i, p) in s.pairs.withIndex()) {
            val a = s.team.getOrNull(p.a) ?: continue
            val b = s.team.getOrNull(p.b) ?: continue
            val y = top + i * (h + 2)
            if (y + h > bodyBottom) break
            val color = pairColors[i % pairColors.size]
            val (ax, al, hover) = card(ui, "pair:${a.key}:${b.key}", i, pad, y, w, h, color, pairTip(s, p), null)
            if (al > 0.6f) {
                val tick = if (hover > 0.5f) ui.partialTick else 0f
                PokemonModels.draw(ui.g, "pairA:${a.key}", a.species, a.aspects, ax + 3, y + 4, 24, tick, zoom = 1f + 0.1f * hover)
                PokemonModels.draw(ui.g, "pairB:${b.key}", b.species, b.aspects, ax + 25, y + 4, 24, tick, zoom = 1f + 0.1f * hover)
            }
            val tx = ax + 52
            val tag = if (p.lead) t("pair.lead") else t("pair.back")
            ui.textRight(tag, ax + w - 4, y + 3, Palette.alpha(color, al), 0.75f)
            ui.text(ui.fit("${a.name} + ${b.name}", ax + w - tx - 8 - ui.width(tag, 0.75f)), tx, y + 3, Palette.alpha(Palette.TEXT, al))
            // Barra da nota, relativa a melhor dupla do time.
            val barW = w - 52 - 34
            val fill = ((p.score / best).coerceIn(0.0, 1.0) * barW).toInt()
            val scoreColor = if (p.score >= 0) Palette.GOOD else Palette.BAD
            ui.rect(tx, y + 13, barW, 3, Palette.alpha(Palette.EDGE, al))
            ui.rect(tx, y + 13, fill, 3, Palette.alpha(scoreColor, al))
            ui.text("%.1f".format(p.score), tx + barW + 3, y + 11, Palette.alpha(scoreColor, al), 0.75f)
            // Um problema tem prioridade; sem problemas, o primeiro ponto forte.
            val line = p.bad.firstOrNull()?.let { it to Palette.BAD } ?: p.good.firstOrNull()?.let { it to Palette.MUTED }
            if (line != null) ui.rich(SetText.moves(line.first), tx, y + 21, Palette.alpha(line.second, al), 0.75f, w - 56)
        }
        pairMatrix(ui, s, pad + w + 4, top, w)
    }

    /** Nota de todos os pares do time; as duplas escolhidas ficam com a borda na cor delas. */
    private fun pairMatrix(ui: Ui, s: BuilderState, x: Int, top: Int, w: Int) {
        val n = s.team.size
        if (n < 2 || s.pairMatrix.size != n) return
        ui.text(t("pair.matrix"), x, top, Palette.MUTED, 0.75f)
        val head = 20
        val gy = top + 10
        val cell = min(22, min((w - head) / n, (bodyBottom - gy - head) / n))
        if (cell < 10) return
        val all = s.pairMatrix.flatten()
        val hi = all.maxOrNull()?.coerceAtLeast(0.1) ?: 1.0
        val lo = all.minOrNull()?.coerceAtMost(-0.1) ?: -1.0
        val icon = min(18, cell)
        for (i in 0 until n) {
            val slot = s.team[i]
            val pi = pairOf(s, i)
            val c = if (pi >= 0) pairColors[pi % pairColors.size] else Palette.FAINT
            PokemonModels.draw(ui.g, "mxr:${slot.key}", slot.species, slot.aspects, x, gy + head + i * cell + (cell - icon) / 2, icon)
            PokemonModels.draw(ui.g, "mxc:${slot.key}", slot.species, slot.aspects, x + head + i * cell + (cell - icon) / 2, gy, icon)
            ui.rect(x + head - 3, gy + head + i * cell + 2, 2, cell - 4, c)
            ui.rect(x + head + i * cell + 2, gy + head - 3, cell - 4, 2, c)
        }
        for (i in 0 until n) for (j in 0 until n) {
            val cx = x + head + j * cell
            val cy = gy + head + i * cell
            if (i == j) {
                ui.panel(cx, cy, cell - 1, cell - 1, Palette.alpha(Palette.CARD, 0.4f), Palette.PANEL_EDGE)
                continue
            }
            val v = s.pairMatrix[i][j]
            val tint = if (v >= 0) Palette.mix(Palette.CARD, Palette.GOOD, (0.15 + 0.6 * v / hi).toFloat().coerceIn(0f, 0.75f))
                else Palette.mix(Palette.CARD, Palette.BAD, (0.15 + 0.6 * v / lo).toFloat().coerceIn(0f, 0.75f))
            val chosen = s.pairs.indexOfFirst { (it.a == i && it.b == j) || (it.a == j && it.b == i) }
            val edge = if (chosen >= 0) pairColors[chosen % pairColors.size] else Palette.PANEL_EDGE
            ui.panel(cx, cy, cell - 1, cell - 1, tint, edge)
            ui.textCentered("%.1f".format(v), cx + cell / 2, cy + (cell - 6) / 2, if (v >= 0) Palette.TEXT else Palette.BAD, 0.6f)
            val tip = Component.literal("${s.team[i].name} + ${s.team[j].name}").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + t("label.score_lower") + " %.1f".format(v)).withStyle(if (v >= 0) ChatFormatting.GREEN else ChatFormatting.RED))
            if (chosen >= 0) tip.append("\n").append(Component.literal(t(if (chosen == 0) "pair.lead" else "pair.back")).withStyle(ChatFormatting.GOLD))
            ui.hit(cx, cy, cell - 1, cell - 1, tip)
        }
    }

    /** Ameacas do meta ao time, em duas colunas. */
    private fun threatColumns(ui: Ui, s: BuilderState, top: Int) {
        if (s.threats.isEmpty()) return empty(ui, pad, top, t("threat.none"))
        val w = columnWidth()
        val h = 30
        val rows = max(1, (bodyBottom - top + 2) / (h + 2))
        for ((i, th) in s.threats.take(rows * 2).withIndex()) {
            val x = pad + (i / rows) * (w + 4)
            val y = top + (i % rows) * (h + 2)
            val tip = Component.literal(th.slot.name).withStyle(ChatFormatting.RED)
                .append("\n").append(Component.literal(th.slot.info).withStyle(ChatFormatting.GRAY))
            if (th.hitting.isNotEmpty()) {
                tip.append("\n").append(Component.literal(t("threat.hitting") + " ").withStyle(ChatFormatting.GRAY)).append(SetText.rich(th.hitting))
            }
            tip.append("\n").append(Component.literal(t("threat.weak_list", th.weak.joinToString().ifEmpty { "-" })).withStyle(ChatFormatting.GRAY))
            tip.append("\n").append(Component.literal(t("threat.walls_list", th.walls.joinToString().ifEmpty { "-" })).withStyle(ChatFormatting.GRAY))
            tip.append("\n").append(
                Component.literal(t("threat.answers_list", th.answers.joinToString().ifEmpty { "-" }))
                    .withStyle(if (th.answers.isEmpty()) ChatFormatting.RED else ChatFormatting.GREEN)
            )
            tip.append("\n\n").append(Component.literal(t("threat.hint")).withStyle(ChatFormatting.DARK_GRAY))
            val (ax, a, hover) = card(ui, "threat:${th.slot.key}", i, x, y, w, h, Palette.BAD, tip, null)
            if (a > 0.6f) {
                PokemonModels.draw(ui.g, "threat:${th.slot.key}", th.slot.species, th.slot.aspects, ax + 3, y + 3, 24, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.12f * hover)
            }
            val tx = ax + 31
            val score = "${th.score}%"
            ui.textRight(score, ax + w - 4, y + 3, Palette.alpha(Palette.BAD, a), 0.75f)
            val after = typeIcons(ui, th.slot.types, th.slot.ability, th.slot.name, tx, y + 2, a)
            ui.text(ui.fit(th.slot.name, ax + w - after - 10 - ui.width(score, 0.75f)), after + 3, y + 3, Palette.alpha(Palette.TEXT, a))
            ui.rich(t("threat.hits", th.weak.size, th.hitting), tx, y + 13, Palette.alpha(Palette.MUTED, a), 0.75f, w - 35)
            val answer = if (th.answers.isEmpty()) t("threat.no_answer") else t("threat.answers", th.answers.joinToString())
            ui.text(ui.fit(answer, w - 35, 0.75f), tx, y + 21, Palette.alpha(if (th.answers.isEmpty()) Palette.BAD else Palette.OWNED, a), 0.75f)
        }
    }

    private fun swapColumn(ui: Ui, column: String, x: Int, top: Int, w: Int, swaps: List<SwapView>) {
        if (swaps.isEmpty()) return empty(ui, x, top, t("empty.no_swaps"))
        val h = 24
        val fits = max(1, (bodyBottom - top + 2) / (h + 2))
        for ((i, sw) in swaps.take(fits).withIndex()) {
            val y = top + i * (h + 2)
            val tip = reasonsTip(
                Component.literal(t("swap.title", sw.outName, sw.pick.name)).append(Component.literal("  +%.1f".format(sw.gain)).withStyle(ChatFormatting.GREEN)),
                "", sw.reasons, t("tip.click_swap"),
            )
            val (ax, a, hover) = card(ui, "swap:$column:${sw.outKey}:${sw.pick.key}", i, x, y, w, h, accentOf(sw.pick), tip) {
                request(keys = keys.map { if (it == sw.outKey) sw.pick.key else it })
            }
            val out = state?.team?.firstOrNull { it.key == sw.outKey }
            if (a > 0.6f) {
                if (out != null) PokemonModels.draw(ui.g, "swapout:$column:${sw.outKey}:$i", out.species, out.aspects, ax + 3, y + 2, 20, zoom = 1f - 0.15f * hover)
                PokemonModels.draw(ui.g, "swapin:$column:${sw.pick.key}", sw.pick.species, sw.pick.aspects, ax + 36, y + 2, 20, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.15f * hover)
            }
            // A seta anda para a direita quando o mouse esta em cima.
            ui.text(">", ax + 26 + (hover * 2 * pulse(500f)).toInt(), y + 8, Palette.alpha(Palette.GOLD, a))
            val gain = "+%.1f".format(sw.gain)
            ui.textRight(gain, ax + w - 4, y + 3, Palette.alpha(Palette.GOOD, a))
            ui.text(ui.fit(sw.pick.name, w - 64 - ui.width(gain)), ax + 60, y + 3, Palette.alpha(accentOf(sw.pick), a))
            ui.text(ui.fit(t("swap.out", sw.outName), w - 64, 0.75f), ax + 60, y + 14, Palette.alpha(Palette.MUTED, a), 0.75f)
        }
    }

    // ------------------------------------------------------------------- raid

    private fun raid(ui: Ui, s: BuilderState, top: Int) {
        val r = s.raid
        if (r == null) {
            ui.text(t("raid.none_selected"), pad + 2, top + 2, Palette.TEXT)
            ui.text(t("raid.help_detect"), pad + 2, top + 15, Palette.MUTED, 0.75f)
            ui.text(t("raid.help_type"), pad + 2, top + 24, Palette.MUTED, 0.75f)
            var right = pad + leftWidth
            right = button(ui, right, top + 38, t("raid.use_typed"), t("raid.use_typed_tip"), Palette.RAID) {
                val text = typed()
                if (text.isNotEmpty()) {
                    raidQuery = text
                    request()
                }
            }
            button(ui, right, top + 38, t("raid.detect"), t("raid.detect_tip"), Palette.RAID) {
                raidQuery = ""
                request()
            }
            return
        }
        raidBanner(ui, r, top)
        val listTop = top + 37
        val w = columnWidth()
        ui.text(t("column.pc_upper"), pad, listTop, Palette.OWNED, 0.75f)
        if (r.partySize > 0) ui.textRight(t("raid.bring", r.partySize), pad + w, listTop, Palette.FAINT, 0.75f)
        ui.text(t("column.all_upper"), pad + w + 4, listTop, Palette.CATALOG, 0.75f)
        raidColumn(ui, "pc", pad, listTop + 9, w, r.pcPicks)
        raidColumn(ui, "all", pad + w + 4, listTop + 9, w, r.allPicks)
    }

    private fun raidBanner(ui: Ui, r: RaidView, top: Int) {
        val h = 34
        ui.panel(pad, top, leftWidth, h, Palette.mix(Palette.CARD, Palette.RAID, 0.10f + 0.05f * pulse(1800f)), Palette.RAID)
        PokemonModels.draw(ui.g, "raidboss:${r.species}", r.species, r.aspects, pad + 4, top + 3, 28, ui.partialTick, yaw = 35f + 12f * sin(now() / 900.0).toFloat())
        val tx = pad + 38
        ui.text(r.boss, tx, top + 4, Palette.TEXT)
        var x = tx + ui.width(r.boss) + 5
        if (r.stars > 0) {
            val stars = "*".repeat(r.stars)
            ui.text(stars, x, top + 4, Palette.GOLD)
            x += ui.width(stars) + 5
        }
        if (r.teraType.isNotEmpty()) {
            ui.text("TERA", x, top + 5, Palette.CYAN, 0.75f)
            ui.types(listOf(r.teraType), x + ui.width("TERA", 0.75f) + 3, top + 3)
        } else {
            x = ui.types(r.types, x, top + 3)
            if (r.feature.isNotEmpty() && r.feature != "default") ui.text(r.feature, x + 3, top + 5, Palette.MUTED, 0.75f)
        }
        ui.text(SetText.raidMoves(r, lang), tx, top + 16, Palette.MUTED, 0.75f)
        val defends = t("raid.defends_as") + " "
        ui.text(defends, tx, top + 25, Palette.FAINT, 0.75f)
        ui.types(if (r.teraType.isNotEmpty()) listOf(r.teraType) else r.types, tx + ui.width(defends, 0.75f), top + 23)

        button(ui, pad + leftWidth - 4, top + 3, t("raid.change"), t("raid.change_tip"), Palette.RAID) {
            raidQuery = null
            request()
        }
    }

    private fun raidColumn(ui: Ui, column: String, x: Int, top: Int, w: Int, picks: List<RaidPickView>) {
        if (picks.isEmpty()) return empty(ui, x, top, t("empty.no_candidates"))
        val h = 24
        val fits = max(1, (bodyBottom - top + 2) / (h + 2))
        val full = keys.size >= 6
        for ((i, p) in picks.take(fits).withIndex()) {
            val y = top + i * (h + 2)
            val (ax, a, hover) = card(ui, "raid:$column:${p.slot.key}", i, x, y, w, h, accentOf(p.slot), SetText.raidPickTooltip(p, lang)) {
                if (!full && p.slot.key !in keys) request(keys = keys + p.slot.key)
            }
            if (a > 0.6f) {
                PokemonModels.draw(ui.g, "raid:$column:${p.slot.key}", p.slot.species, p.slot.aspects, ax + 3, y + 2, 20, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.15f * hover)
            }
            val tx = ax + 27
            val score = "${p.score}%"
            ui.textRight(score, ax + w - 4, y + 3, Palette.alpha(Palette.RAID, a))
            ui.text(ui.fit(p.slot.name, w - 34 - ui.width(score)), tx, y + 3, Palette.alpha(accentOf(p.slot), a))

            // Barra da nota cresce ate o valor.
            val barW = w - 31
            val fill = smooth("raidbar:$column:${p.slot.key}", p.score / 100f, 5f)
            ui.rect(tx, y + h - 4, barW, 2, Palette.alpha(Palette.PANEL_EDGE, a))
            ui.rect(tx, y + h - 4, (barW * fill).toInt(), 2, Palette.alpha(Palette.RAID, a))

            val line = Component.empty()
            if (p.move.isNotEmpty()) line.append(SetText.move(p.move)).append(" ")
            line.append(Component.literal(SetText.multiplier(p.effectiveness)).withStyle(if (p.effectiveness > 1) ChatFormatting.GREEN else ChatFormatting.GRAY))
            line.append(Component.literal("  " + t("raid.takes") + " " + SetText.multiplier(p.worstTaken)).withStyle(if (p.worstTaken > 1) ChatFormatting.RED else ChatFormatting.DARK_GRAY))
            ui.text(line, tx, y + 13, Palette.alpha(Palette.MUTED, a), 0.75f)
        }
    }

    // ------------------------------------------------------------ treinadores

    private fun trainers(ui: Ui, s: BuilderState, top: Int) {
        val tv = s.trainer
        if (tv == null) trainerPicker(ui, top) else trainerDetail(ui, s, tv, top)
    }

    /** Lista de treinadores do RCT; o campo de nome filtra. */
    private fun trainerPicker(ui: Ui, top: Int) {
        ui.text(t("trainer.pick"), pad, top, Palette.RAID, 0.75f)
        ui.textRight(t("trainer.search_hint"), pad + leftWidth, top, Palette.FAINT, 0.75f)
        val list = trainerList?.trainers.orEmpty()
        if (list.isEmpty()) {
            ui.text(t(if (trainerList == null) "status.calculating" else "trainer.none"), pad + 2, top + 14, Palette.TEXT)
            if (trainerList != null) ui.text(t("trainer.none_hint"), pad + 2, top + 26, Palette.MUTED, 0.75f)
            return
        }
        val w = columnWidth()
        val h = 38
        val rows = max(1, (bodyBottom - top - 9) / (h + 3))
        for ((i, tr) in list.take(rows * 2).withIndex()) {
            val x = pad + (i % 2) * (w + 4)
            val y = top + 9 + (i / 2) * (h + 3)
            val tip = Component.literal(tr.name).withStyle(ChatFormatting.GOLD)
            if (tr.type.isNotEmpty()) tip.append(Component.literal("  " + tr.type).withStyle(ChatFormatting.GRAY))
            for (m in tr.members) tip.append("\n").append(Component.literal("${m.name}  ${t("info.level_short", m.level)}").withStyle(ChatFormatting.GRAY))
            tip.append("\n\n").append(Component.literal(t("trainer.click")).withStyle(ChatFormatting.DARK_GRAY))
            val (ax, a, hover) = card(ui, "trainer:${tr.id}", i, x, y, w, h, Palette.RAID, tip) { chooseTrainer(tr.id) }
            val level = t("info.level_short", tr.level)
            ui.textRight(level, ax + w - 4, y + 3, Palette.alpha(Palette.GOLD, a), 0.75f)
            ui.text(ui.fit(tr.name, w - 60), ax + 5, y + 3, Palette.alpha(Palette.TEXT, a))
            if (tr.type.isNotEmpty()) ui.text(ui.fit(tr.type, w - 10, 0.75f), ax + 5 + min(ui.width(tr.name), w - 60) + 5, y + 4, Palette.alpha(Palette.RAID, a), 0.75f)
            if (a > 0.6f && tr.members.isNotEmpty()) {
                val box = min(20, (w - 10) / tr.members.size - 2)
                for ((j, m) in tr.members.withIndex()) {
                    val wave = hover * (0.5f + 0.5f * sin(now() / 160.0 - j * 0.9).toFloat())
                    PokemonModels.draw(ui.g, "trainer:${tr.id}:$j", m.species, m.aspects, ax + 5 + j * (box + 2), y + 14, box, zoom = 1f + 0.14f * wave)
                }
            }
        }
    }

    /** Time do treinador, quem do seu time responde a cada um, e os melhores counters. */
    private fun trainerDetail(ui: Ui, s: BuilderState, tv: dev.thal.teambuilder.core.TrainerView, top: Int) {
        ui.panel(pad, top, leftWidth, 18, Palette.mix(Palette.CARD, Palette.RAID, 0.10f), Palette.RAID)
        ui.text(tv.name, pad + 5, top + 5, Palette.TEXT)
        if (tv.type.isNotEmpty()) ui.text(tv.type, pad + 10 + ui.width(tv.name), top + 6, Palette.RAID, 0.75f)
        var right = button(ui, pad + leftWidth - 3, top + 3, t("raid.change"), t("trainer.change_tip"), Palette.RAID) {
            trainerId = null
            request()
        }
        if (tv.pcPicks.isNotEmpty()) {
            button(ui, right, top + 3, t("trainer.build"), t("trainer.build_tip"), Palette.OWNED) {
                request(keys = tv.pcPicks.take(6).map { it.slot.key })
            }
        }

        // Time adversario.
        val ey = top + 21
        val gap = 3
        val ew = (leftWidth - gap * 5) / 6
        val eh = 40
        for ((i, e) in tv.enemies.withIndex()) {
            val x = pad + i * (ew + gap)
            val answered = e.answer.isNotEmpty()
            val tip = Component.literal(e.slot.name).withStyle(ChatFormatting.WHITE)
                .append(Component.literal("  " + t("info.level_short", e.level)).withStyle(ChatFormatting.GOLD))
                .append("\n").append(SetText.rich(Lang.types(e.slot.types)))
            if (e.moves.isNotEmpty()) {
                tip.append("\n").append(Component.literal(t("label.moves") + ": ").withStyle(ChatFormatting.GRAY))
                e.moves.forEachIndexed { k, m ->
                    if (k > 0) tip.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
                    tip.append(SetText.move(m))
                }
            }
            if (e.item.isNotEmpty()) tip.append("\n").append(Component.literal(t("label.item") + ": ").withStyle(ChatFormatting.GRAY)).append(SetText.item(e.item))
            if (e.ability.isNotEmpty()) tip.append("\n").append(Component.literal(t("label.ability") + ": ").withStyle(ChatFormatting.GRAY)).append(SetText.ability(e.ability))
            if (e.nature.isNotEmpty()) tip.append("\n").append(Component.literal(t("label.nature") + ": ").withStyle(ChatFormatting.GRAY)).append(SetText.nature(e.nature))
            tip.append("\n\n").append(
                Component.literal(if (answered) t("trainer.answer", e.answer) else t("trainer.no_answer"))
                    .withStyle(if (answered) ChatFormatting.GREEN else ChatFormatting.RED)
            )
            val (ax, a, hover) = card(ui, "enemy:${tv.id}:$i", i, x, ey, ew, eh, Palette.RAID, tip, null)
            if (a > 0.6f) {
                PokemonModels.draw(ui.g, "enemy:${tv.id}:$i", e.slot.species, e.slot.aspects, ax + 3, ey + 3, 22, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.12f * hover)
            }
            ui.text(ui.fit(e.slot.name, ew - 30, 0.75f), ax + 27, ey + 4, Palette.alpha(Palette.TEXT, a), 0.75f)
            ui.text(t("info.level_short", e.level), ax + 27, ey + 12, Palette.alpha(Palette.GOLD, a), 0.75f)
            typeIcons(ui, e.slot.types, e.slot.ability, e.slot.name, ax + 27, ey + 19, a)
            val answer = if (answered) e.answer else if (s.team.isEmpty()) "" else t("trainer.no_answer_short")
            ui.text(ui.fit(answer, ew - 6, 0.75f), ax + 3, ey + eh - 9, Palette.alpha(if (answered) Palette.OWNED else Palette.BAD, a), 0.75f)
        }

        // Counters.
        val listTop = ey + eh + 4
        val w = columnWidth()
        ui.text(t("column.pc_upper"), pad, listTop, Palette.OWNED, 0.75f)
        ui.text(t("column.all_upper"), pad + w + 4, listTop, Palette.CATALOG, 0.75f)
        counterColumn(ui, "pc", pad, listTop + 9, w, tv.pcPicks)
        counterColumn(ui, "all", pad + w + 4, listTop + 9, w, tv.allPicks)
    }

    private fun counterColumn(ui: Ui, column: String, x: Int, top: Int, w: Int, picks: List<dev.thal.teambuilder.core.CounterView>) {
        if (picks.isEmpty()) return empty(ui, x, top, t("empty.no_candidates"))
        val h = 24
        val fits = max(1, (bodyBottom - top + 2) / (h + 2))
        for ((i, p) in picks.take(fits).withIndex()) {
            val y = top + i * (h + 2)
            val tip = Component.literal(p.slot.name).append(Component.literal("  ${p.score}%").withStyle(ChatFormatting.GOLD))
                .append("\n").append(Component.literal(p.slot.info).withStyle(ChatFormatting.GRAY))
                .append("\n").append(Component.literal(t("trainer.handles", p.handles.joinToString().ifEmpty { "-" })).withStyle(ChatFormatting.GRAY))
                .append("\n\n").append(Component.literal(t("tip.click_add")).withStyle(ChatFormatting.DARK_GRAY))
            val (ax, a, hover) = card(ui, "counter:$column:${p.slot.key}", i, x, y, w, h, accentOf(p.slot), tip) {
                if (keys.size < 6 && p.slot.key !in keys) request(keys = keys + p.slot.key)
            }
            if (a > 0.6f) {
                PokemonModels.draw(ui.g, "counter:$column:${p.slot.key}", p.slot.species, p.slot.aspects, ax + 3, y + 2, 20, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.15f * hover)
            }
            val tx = ax + 27
            val score = "${p.score}%"
            ui.textRight(score, ax + w - 4, y + 3, Palette.alpha(Palette.RAID, a))
            ui.text(ui.fit(p.slot.name, w - 34 - ui.width(score)), tx, y + 3, Palette.alpha(accentOf(p.slot), a))
            ui.text(ui.fit(t("trainer.handles", p.handles.joinToString().ifEmpty { "-" }), w - 32, 0.75f), tx, y + 13, Palette.alpha(Palette.MUTED, a), 0.75f)
            val barW = w - 31
            val fill = smooth("counterbar:$column:${p.slot.key}", p.score / 100f, 5f)
            ui.rect(tx, y + h - 3, barW, 1, Palette.alpha(Palette.PANEL_EDGE, a))
            ui.rect(tx, y + h - 3, (barW * fill).toInt(), 1, Palette.alpha(Palette.RAID, a))
        }
    }

    // ----------------------------------------------------------------- salvos

    private fun saved(ui: Ui, top: Int) {
        val teams = SavedTeams.teams
        ui.text(t("saved.title"), pad, top, Palette.CYAN, 0.75f)
        ui.textRight(t("saved.hint"), pad + leftWidth, top, Palette.FAINT, 0.75f)
        if (teams.isEmpty()) {
            ui.text(t("saved.none"), pad + 2, top + 14, Palette.TEXT)
            ui.text(t("saved.none_hint"), pad + 2, top + 26, Palette.MUTED, 0.75f)
            return
        }
        val h = 40
        val cols = 2
        val w = columnWidth()
        val dateFormat = SimpleDateFormat("dd/MM HH:mm")
        val rows = max(1, (bodyBottom - top - 9) / (h + 3))
        for ((i, team) in teams.take(rows * cols).withIndex()) {
            val x = pad + (i % cols) * (w + 4)
            val y = top + 9 + (i / cols) * (h + 3)
            val tip = Component.literal(team.name).withStyle(ChatFormatting.AQUA)
                .append(Component.literal("  " + t("label.score_lower") + " %.1f".format(team.total)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(if (team.doubles) "  2v2" else "  1v1").withStyle(ChatFormatting.GRAY))
            for (m in team.members) {
                tip.append("\n").append(Component.literal(m.name).withStyle(if (m.owned) ChatFormatting.GREEN else ChatFormatting.AQUA))
                if (m.roleLabel.isNotEmpty()) tip.append(Component.literal(" · ${m.roleLabel}").withStyle(ChatFormatting.GRAY))
            }
            tip.append("\n\n").append(Component.literal(t("saved.load_tip")).withStyle(ChatFormatting.DARK_GRAY))
            val (ax, a, hover) = card(ui, "saved:$i:${team.savedAt}", i, x, y, w, h, Palette.CYAN, tip) { loadSaved(team) }
            ui.text(ui.fit(team.name, w - 90), ax + 5, y + 4, Palette.alpha(Palette.TEXT, a))
            ui.textRight("%.1f".format(team.total) + (if (team.doubles) " · 2v2" else ""), ax + w - 14, y + 4, Palette.alpha(Palette.GOOD, a), 0.75f)
            ui.text(dateFormat.format(Date(team.savedAt)), ax + 5, y + 31, Palette.alpha(Palette.FAINT, a), 0.6f)
            if (a > 0.6f && team.members.isNotEmpty()) {
                val box = min(22, (w - 10) / team.members.size - 2)
                for ((j, m) in team.members.withIndex()) {
                    val wave = hover * (0.5f + 0.5f * sin(now() / 160.0 - j * 0.9).toFloat())
                    PokemonModels.draw(ui.g, "saved:$i:$j:${m.key}", m.species, m.aspects, ax + 5 + j * (box + 2), y + 13, box, zoom = 1f + 0.14f * wave)
                }
            }
            // X para apagar.
            val closeOver = ui.over(ax + w - 10, y + 1, 9, 9)
            ui.text("x", ax + w - 8, y + 2, Palette.alpha(if (closeOver) Palette.BAD else Palette.FAINT, max(0.4f, hover) * a))
            ui.hit(ax + w - 10, y + 1, 9, 9, Component.literal(t("saved.delete", team.name))) { SavedTeams.remove(i) }
        }
    }

    // ------------------------------------------------------------------ busca

    private val searchRowH get() = 22

    /** x0, y0, x1, y1 da lista da busca, ou null se ela esta fechada. */
    private fun dropdownRect(): IntArray? {
        val results = searchResults()
        if (results.isEmpty()) return null
        val w = min(leftWidth, 210)
        val x = pad + leftWidth - w
        val y0 = bodyTop + 15
        return intArrayOf(x, y0, x + w, y0 + results.size * searchRowH + 4)
    }

    /** Cards dos Pokemon que combinam com o texto digitado, logo abaixo do campo. */
    private fun searchDropdown(ui: Ui, s: BuilderState) {
        val results = searchResults()
        val rect = dropdownRect() ?: return
        val x = rect[0]
        val w = rect[2] - rect[0]
        val rowH = searchRowH
        val y0 = rect[1]
        val h = rect[3] - rect[1]
        val k = smooth("search:open", 1f, 20f)
        ui.panel(x, y0, w, h, Palette.alpha(Palette.PANEL, k), Palette.alpha(Palette.OWNED, k))
        // Bloqueia cliques no que esta por baixo.
        ui.hit(x, y0, w, h, null) {}
        val raidMode = tab == Tab.RAID && s.raid == null
        for ((i, slot) in results.withIndex()) {
            val y = y0 + 2 + i * rowH
            val hover = smooth("search:${slot.key}", if (ui.over(x + 2, y, w - 4, rowH - 1)) 1f else 0f)
            val taken = slot.species.isNotEmpty() && s.team.any { it.species == slot.species }
            ui.rect(x + 2, y, w - 4, rowH - 1, Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover))
            if (hover > 0.02f) ui.rect(x + 2, y, 2, rowH - 1, Palette.alpha(accentOf(slot), hover))
            // Recorta no proprio card para um modelo grande nao invadir a linha vizinha.
            ui.g.enableScissor(x + 2, y, x + 24, y + rowH - 1)
            PokemonModels.draw(ui.g, "search:${slot.key}", slot.species, slot.aspects, x + 5, y + 2, 17, if (hover > 0.5f) ui.partialTick else 0f, zoom = 1f + 0.08f * hover, onTop = true)
            ui.g.disableScissor()
            val tx = x + 26
            val tag = when {
                taken -> t("search.in_team")
                slot.owned -> t("search.yours_level", slot.level)
                else -> ""
            }
            ui.text(ui.fit(slot.name, w - 32 - ui.width(tag, 0.75f)), tx, y + 2, if (taken) Palette.FAINT else accentOf(slot))
            ui.textRight(tag, x + w - 5, y + 3, if (taken) Palette.FAINT else Palette.OWNED, 0.75f)
            val after = typeIcons(ui, slot.types, slot.ability, slot.name, tx, y + 11, onTop = true)
            ui.text(ui.fit(slot.info, x + w - after - 8, 0.75f), after + 3, y + 12, Palette.FAINT, 0.75f)
            val tip = Component.literal(slot.name).append("\n").append(Component.literal(slot.info).withStyle(ChatFormatting.GRAY))
                .append("\n\n").append(Component.literal(if (raidMode) t("search.use_as_boss") else t("tip.click_add")).withStyle(ChatFormatting.DARK_GRAY))
            ui.hit(x + 2, y, w - 4, rowH - 1, tip) { pickSearch(slot) }
        }
    }

    // ----------------------------------------------------------- painel de set

    private fun advisor(ui: Ui, s: BuilderState) {
        val x = rightX
        val y = bodyTop
        val w = rightWidth
        val h = bodyBottom - y
        ui.panel(x, y, w, h)

        val slot = s.team.getOrNull(selected)
        val set = s.sets.getOrNull(selected)
        if (slot == null || set == null) {
            ui.text(t("advisor.title"), x + 6, y + 6, Palette.MUTED, 0.75f)
            ui.text(t("advisor.empty1"), x + 6, y + 20, Palette.FAINT, 0.75f)
            ui.text(t("advisor.empty2"), x + 6, y + 29, Palette.FAINT, 0.75f)
            return
        }
        // Trocar de slot refaz a entrada do painel.
        if (slot.key != advisorKey) {
            advisorKey = slot.key
            advisorAt = now()
        }
        val tt = easeOut((now() - advisorAt) / 240f)
        val accent = accentOf(slot)

        // Cabecalho: modelo girando devagar + identificacao.
        ui.rect(x + 1, y + 1, w - 2, 46, Palette.alpha(Palette.mix(Palette.CARD, accent, 0.10f), tt))
        PokemonModels.draw(ui.g, "advisor:${slot.key}", slot.species, slot.aspects, x + 5, y + 4, 38, ui.partialTick, yaw = 35f + 22f * sin(now() / 1300.0).toFloat(), zoom = 0.85f + 0.15f * tt)
        val tx = x + 48
        ui.text(ui.fit(slot.name, w - 52), tx, y + 5, Palette.alpha(accent, tt))
        typeIcons(ui, slot.types, slot.ability, slot.name, tx, y + 16, tt)
        ui.text(ui.fit(slot.info, w - 52, 0.75f), tx, y + 28, Palette.alpha(Palette.MUTED, tt), 0.75f)
        ui.text(ui.fit(slot.roles.joinToString(", "), w - 52, 0.75f), tx, y + 37, Palette.alpha(Palette.FAINT, tt), 0.75f)

        var cy = y + 50
        cy += advisorToggle(ui, x + 6, cy, set, tt)
        if (slot.forms.size > 1) cy += formChips(ui, x + 6, cy, w - 12, slot, tt)
        // 2v2: com quem ele faz dupla (hover mostra os motivos).
        val pairIdx = if (s.doubles) pairOf(s, selected) else -1
        val pair = s.pairs.getOrNull(pairIdx)
        val mate = pair?.let { s.team.getOrNull(if (it.a == selected) it.b else it.a) }
        if (pair != null && mate != null) {
            val label = t(if (pair.lead) "pair.with_lead" else "pair.with", mate.name)
            ui.text(label, x + 6, cy + 1, Palette.alpha(pairColors[pairIdx % pairColors.size], tt), 0.75f)
            ui.hit(x + 6, cy, ui.width(label, 0.75f), 9, pairTip(s, pair))
            cy += 11
        }
        if (set.roleOptions.isNotEmpty()) cy += roleChips(ui, x + 6, cy, w - 12, slot, set, tt)
        if (showChecklist) {
            checklist(ui, x + 6, cy, w - 12, set, tt)
            return
        }

        // Item e habilidade.
        val half = (w - 12 - 3) / 2
        infoBox(ui, x + 6, cy, half, t("advisor.item"), set.items.firstOrNull()?.let(SetText::item), Palette.GOLD, tt,
            if (set.items.size > 1) Component.literal(t("advisor.alternative") + ": ").append(SetText.item(set.items[1])) else null)
        infoBox(ui, x + 6 + half + 3, cy, half, t("advisor.ability"), set.ability.takeIf { it.isNotEmpty() }?.let(SetText::ability), Palette.CYAN, tt, null)
        cy += 25

        // Natureza e EVs.
        val natureLabel = t("advisor.nature")
        ui.text(natureLabel, x + 6, cy, Palette.alpha(Palette.FAINT, tt), 0.75f)
        if (set.nature.isNotEmpty()) ui.text(SetText.nature(set.nature), x + 6 + ui.width(natureLabel, 0.75f) + 4, cy - 1, Palette.alpha(Palette.TEXT, tt))
        if (set.notes.isNotEmpty()) {
            ui.textRight("!", x + w - 7, cy - 1, Palette.alpha(Palette.GOLD, 0.6f + 0.4f * pulse(900f)))
            ui.hit(x + 6, cy - 2, w - 12, 10, Component.literal(t("advisor.differs") + "\n" + set.notes.joinToString("\n") { "- $it" }))
        }
        cy += 10
        evRow(ui, x + 6, cy, w - 12, set.evs, tt)
        cy += 22
        if (set.ivs.size == 6) {
            ivRow(ui, x + 6, cy, w - 12, set, tt)
            cy += 25
        }

        // Golpes.
        ui.text(t("advisor.moves"), x + 6, cy, Palette.alpha(Palette.FAINT, tt), 0.75f)
        if (!set.fromMeta) ui.textRight(t("set.heuristic"), x + w - 6, cy, Palette.alpha(Palette.FAINT, tt), 0.75f)
        cy += 8
        val rowH = min(20, (bodyBottom - 3 - cy) / max(1, set.moves.size))
        for ((i, m) in set.moves.withIndex()) {
            moveRow(ui, x + 6, cy + i * rowH, w - 12, rowH - 1, m, slot.owned, i)
        }
    }

    /** "SET | FALTA (n)": alterna o painel entre o set sugerido e o checklist. */
    private fun advisorToggle(ui: Ui, x: Int, y: Int, set: SetView, tt: Float): Int {
        val pending = set.checklist.count { it.state == "missing" || it.state == "have" }
        var cx = x
        for ((mode, label) in listOf(false to t("advisor.tab_set"), true to t("advisor.tab_check", pending))) {
            val active = showChecklist == mode
            val lw = ui.width(label, 0.75f) + 8
            val hover = smooth("adv:$mode", if (ui.over(cx, y, lw, 11)) 1f else 0f)
            ui.panel(
                cx, y, lw, 11,
                Palette.alpha(if (active) Palette.mix(Palette.CARD, Palette.GOLD, 0.2f) else Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), tt),
                Palette.alpha(if (active) Palette.GOLD else Palette.EDGE, tt),
            )
            val color = when {
                active -> Palette.GOLD
                mode && pending > 0 -> Palette.mix(Palette.GOLD, Palette.TEXT, 0.4f)
                else -> Palette.mix(Palette.MUTED, Palette.TEXT, hover)
            }
            ui.text(label, cx + 4, y + 2, Palette.alpha(color, tt), 0.75f)
            ui.hit(cx, y, lw, 11, Component.literal(t(if (mode) "advisor.tab_check_tip" else "advisor.tab_set_tip"))) { showChecklist = mode }
            cx += lw + 3
        }
        return 14
    }

    /** O que falta para o seu exemplar ficar igual ao set, com o que ja esta no inventario. */
    private fun checklist(ui: Ui, x: Int, y: Int, w: Int, set: SetView, tt: Float) {
        val items = set.checklist
        if (items.isEmpty()) {
            ui.text(t("check.empty"), x, y + 2, Palette.alpha(Palette.FAINT, tt), 0.75f)
            return
        }
        val rowH = min(15, max(10, (bodyBottom - 3 - y) / items.size))
        val names = SetText.STAT_NAMES
        for ((i, item) in items.withIndex()) {
            val ry = y + i * rowH
            if (ry + rowH > bodyBottom - 2) break
            val a = enter(i) * tt
            val (mark, color) = when (item.state) {
                "done" -> "✔" to Palette.GOOD
                "have" -> "●" to Palette.CYAN
                "missing" -> "✘" to Palette.GOLD
                else -> "•" to Palette.MUTED
            }
            val label: MutableComponent = when (item.kind) {
                "move" -> SetText.move(item.id)
                "nature" -> Component.literal(t("check.nature") + " ").append(SetText.nature(item.id))
                "ability" -> SetText.ability(item.id)
                "item", "mega" -> SetText.item(item.id)
                "ev" -> Component.literal("EV " + names.getOrElse(item.id.toIntOrNull() ?: 0) { "?" })
                "iv" -> Component.literal("IV " + names.getOrElse(item.id.toIntOrNull() ?: 0) { "?" })
                else -> Component.literal(t("check.obtain"))
            }
            val detail = when (item.kind) {
                "move" -> when (item.state) { "done" -> t("move.known"); "have" -> t("move.swap"); else -> item.detail.ifEmpty { t("move.learn") } }
                "nature" -> when (item.state) { "done" -> t("check.ok"); "have" -> t("check.use_mint"); else -> t("check.need_mint") }
                "ability" -> when (item.state) { "done" -> t("check.ok"); "have" -> t("check.use_capsule"); else -> t("check.need_capsule") }
                "item", "mega" -> when (item.state) { "done" -> t("check.holding"); "have" -> t("check.in_bag"); else -> t("check.get_it") }
                "ev" -> item.detail + when (item.state) { "have" -> " · " + t("check.vitamin"); "info" -> " · " + t("check.ev_over"); else -> "" }
                "iv" -> "${item.detail} → 31"
                else -> ""
            }
            val hover = smooth("check:$i", if (ui.over(x, ry, w, rowH - 1)) 1f else 0f)
            ui.rect(x, ry, w, rowH - 1, Palette.alpha(Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), a))
            ui.text(mark, x + 3, ry + (rowH - 9) / 2, Palette.alpha(color, a))
            val dw = ui.width(detail, 0.75f)
            ui.textRight(detail, x + w - 3, ry + (rowH - 6) / 2, Palette.alpha(color, a), 0.75f)
            val room = w - 16 - dw - 6
            ui.text(label, x + 13, ry + (rowH - 8) / 2, Palette.alpha(if (item.state == "done") Palette.MUTED else Palette.TEXT, a), if (ui.width(label) > room) 0.75f else 1f)
            val tip = label.copy().append("\n").append(Component.literal(t("check.state_${item.state}")).withStyle(ChatFormatting.GRAY))
            if (detail.isNotEmpty()) tip.append("\n").append(Component.literal(detail).withStyle(ChatFormatting.GRAY))
            if (item.kind == "nature" && item.state != "done" && item.detail.isNotEmpty()) {
                tip.append("\n").append(Component.literal(t("check.item_needed") + " ").withStyle(ChatFormatting.DARK_GRAY)).append(SetText.item(item.detail))
            }
            ui.hit(x, ry, w, rowH - 1, tip)
        }
    }

    /** Desenha os icones de tipo e, no hover, a tabela de defesa daquele Pokemon. */
    private fun typeIcons(
        ui: Ui, types: List<String>, ability: String, name: String, x: Int, y: Int, opacity: Float = 1f, onTop: Boolean = false,
    ): Int {
        val end = ui.types(types, x, y, opacity)
        if (opacity < 0.6f || end <= x) return end
        val o = PokemonModels.occluder
        if (!onTop && o != null && x < o[2] && end > o[0] && y < o[3] && y + Ui.TYPE_ICON > o[1]) return end
        typeTips += Hit(x, y, end - x, Ui.TYPE_ICON, typeChartTip(types, ability, name), null)
        return end
    }

    private fun multiplierColor(m: Double): ChatFormatting = when {
        m == 0.0 -> ChatFormatting.AQUA
        m >= 4.0 -> ChatFormatting.DARK_RED
        m > 1.0 -> ChatFormatting.RED
        m < 1.0 -> ChatFormatting.GREEN
        else -> ChatFormatting.GRAY
    }

    private fun multiplierText(m: Double): String = when (m) {
        0.0 -> t("chart.immune")
        0.25 -> "¼x"
        0.5 -> "½x"
        else -> SetText.multiplier(m)
    }

    /** "Leva 4x: ...  2x: ...  Resiste: ...  Imune: ..." com os tipos coloridos. */
    private fun typeChartTip(types: List<String>, ability: String, name: String): Component {
        val profile = dev.thal.teambuilder.core.TypeChart.defensiveProfile(types, ability.ifEmpty { null })
        val plain = dev.thal.teambuilder.core.TypeChart.defensiveProfile(types, null)
        val out = Component.literal(t("chart.title", name)).withStyle(ChatFormatting.GOLD)
        val groups = listOf(
            "chart.x4" to { m: Double -> m >= 4.0 },
            "chart.x2" to { m: Double -> m > 1.0 && m < 4.0 },
            "chart.x05" to { m: Double -> m > 0.25 && m < 1.0 },
            "chart.x025" to { m: Double -> m > 0.0 && m <= 0.25 },
            "chart.x0" to { m: Double -> m == 0.0 },
        )
        val colors = listOf(ChatFormatting.DARK_RED, ChatFormatting.RED, ChatFormatting.GREEN, ChatFormatting.DARK_GREEN, ChatFormatting.AQUA)
        for ((g, pair) in groups.withIndex()) {
            val (key, test) = pair
            val hit = dev.thal.teambuilder.core.TypeChart.TYPES.filterIndexed { i, _ -> test(profile[i]) }
            if (hit.isEmpty()) continue
            out.append("\n").append(Component.literal(t(key) + " ").withStyle(colors[g]))
            hit.forEachIndexed { k, type ->
                if (k > 0) out.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                out.append(SetText.type(type))
            }
        }
        val changed = dev.thal.teambuilder.core.TypeChart.TYPES.indices.any { profile[it] != plain[it] }
        if (changed && ability.isNotEmpty()) {
            out.append("\n").append(Component.literal(t("chart.ability") + " ").withStyle(ChatFormatting.DARK_GRAY))
                .append(SetText.ability(ability).withStyle(ChatFormatting.GRAY))
        }
        return out
    }

    /** Tooltip de um tipo na analise: quanto cada membro leva, do pior para o melhor. */
    private fun typeBalanceTip(s: BuilderState, b: dev.thal.teambuilder.core.TypeBalanceView): Component {
        val out = Component.literal(t("analysis.type_attacks") + " ").append(SetText.type(b.type))
            .append(Component.literal("\n" + t("analysis.type_counts", b.weak, b.resist)).withStyle(ChatFormatting.GRAY))
        val members = s.team.indices.mapNotNull { i -> b.multipliers.getOrNull(i)?.let { s.team[i].name to it } }
            .sortedByDescending { it.second }
        if (members.isNotEmpty()) out.append("\n")
        for ((name, m) in members) {
            out.append("\n").append(Component.literal(multiplierText(m)).withStyle(multiplierColor(m)))
                .append(Component.literal("  $name").withStyle(if (m == 1.0) ChatFormatting.GRAY else ChatFormatting.WHITE))
        }
        return out
    }

    /** Linha de chips clicaveis; devolve a altura usada. */
    private fun <T> chips(
        ui: Ui, x: Int, y: Int, w: Int, label: String, items: List<T>, tt: Float,
        text: (T) -> String, active: (T) -> Boolean, highlight: (T) -> Boolean, tip: (T) -> Component, onClick: (T) -> Unit,
    ): Int {
        val labelW = ui.width(label, 0.75f) + 4
        ui.text(label, x, y + 2, Palette.alpha(Palette.FAINT, tt), 0.75f)
        var cx = x + labelW
        var cy = y
        for ((i, item) in items.withIndex()) {
            val str = text(item)
            val cw = ui.width(str, 0.75f) + 7
            if (cx + cw > x + w && cx > x + labelW) {
                cx = x + labelW
                cy += 12
            }
            val on = active(item)
            val hover = smooth("chip:$label:$i", if (ui.over(cx, cy, cw, 11)) 1f else 0f)
            val fill = when {
                on -> Palette.mix(Palette.CARD, Palette.GOLD, 0.22f + 0.08f * pulse(1600f))
                else -> Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover)
            }
            val edge = when {
                on -> Palette.GOLD
                highlight(item) -> Palette.mix(Palette.EDGE, Palette.GOOD, 0.7f)
                else -> Palette.mix(Palette.EDGE, Palette.TEXT, hover * 0.4f)
            }
            ui.panel(cx, cy, cw, 11, Palette.alpha(fill, tt), Palette.alpha(edge, tt))
            val color = when {
                on -> Palette.GOLD
                highlight(item) -> Palette.GOOD
                else -> Palette.mix(Palette.MUTED, Palette.TEXT, hover)
            }
            ui.text(str, cx + 4, cy + 2, Palette.alpha(color, tt), 0.75f)
            ui.hit(cx, cy, cw, 11, tip(item)) { onClick(item) }
            cx += cw + 2
        }
        return cy - y + 14
    }

    private fun formChips(ui: Ui, x: Int, y: Int, w: Int, slot: SlotView, tt: Float): Int =
        chips(
            ui, x, y, w, t("advisor.form"), slot.forms, tt,
            text = { it.label },
            active = { it.key == slot.key },
            highlight = { false },
            tip = { f ->
                Component.literal(f.label).withStyle(if (f.mega) ChatFormatting.GOLD else ChatFormatting.WHITE)
                    .append("\n").append(Component.literal(if (f.mega) t("form.mega_tip") else t("form.base_tip")).withStyle(ChatFormatting.GRAY))
            },
            onClick = { f -> switchForm(slot, f) },
        )

    private fun roleChips(ui: Ui, x: Int, y: Int, w: Int, slot: SlotView, set: SetView, tt: Float): Int =
        chips(
            ui, x, y, w, t("advisor.role"), set.roleOptions, tt,
            text = { (if (it.recommended) "★ " else "") + it.label },
            active = { it.id == set.role },
            highlight = { it.recommended },
            tip = ::roleTooltip,
            onClick = { r -> chooseRole(slot, r.id) },
        )

    /** Tooltip de uma funcao: natureza, EVs, item e golpes que ela daria. */
    private fun roleTooltip(r: RoleView): Component {
        val out = Component.literal(r.label).withStyle(ChatFormatting.GOLD)
        if (r.recommended) out.append(Component.literal("  ★ " + t("role.recommended")).withStyle(ChatFormatting.GREEN))
        out.append("\n").append(Component.literal(t("label.nature") + ": ").withStyle(ChatFormatting.GRAY)).append(SetText.nature(r.nature))
        out.append("\n").append(Component.literal("EVs: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(SetText.evs(r.evs)))
        if (r.item.isNotEmpty()) out.append("\n").append(Component.literal(t("label.item") + ": ").withStyle(ChatFormatting.GRAY)).append(SetText.item(r.item))
        if (r.moves.isNotEmpty()) {
            out.append("\n").append(Component.literal(t("label.moves") + ": ").withStyle(ChatFormatting.GRAY))
            r.moves.forEachIndexed { i, m ->
                if (i > 0) out.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
                out.append(SetText.move(m))
            }
        }
        return out.append("\n\n").append(Component.literal(t("role.click_tip")).withStyle(ChatFormatting.DARK_GRAY))
    }

    private fun infoBox(ui: Ui, x: Int, y: Int, w: Int, label: String, value: Component?, color: Int, tt: Float, tip: Component?) {
        ui.panel(x, y, w, 22, Palette.alpha(Palette.CARD, tt), Palette.alpha(Palette.PANEL_EDGE, tt))
        ui.text(label, x + 4, y + 3, Palette.alpha(Palette.FAINT, tt), 0.75f)
        val text = value ?: Component.literal("-")
        val scale = if (ui.width(text) > w - 8) 0.75f else 1f
        ui.text(text, x + 4, y + 11, Palette.alpha(color, tt), scale)
        if (tip != null) ui.hit(x, y, w, 22, tip)
    }

    private fun evRow(ui: Ui, x: Int, y: Int, w: Int, evs: List<Int>, tt: Float) {
        val names = SetText.STAT_NAMES
        val gap = 2
        val cw = (w - gap * 5) / 6
        for (i in 0 until 6) {
            val value = evs.getOrElse(i) { 0 }
            val cx = x + i * (cw + gap)
            val used = value > 0
            ui.panel(cx, y, cw, 19, Palette.alpha(Palette.CARD, tt), Palette.alpha(if (used) Palette.OWNED else Palette.PANEL_EDGE, tt))
            // A barra de fundo sobe ate a proporcao do EV.
            val level = smooth("ev:$i", value / 252f, 7f)
            val barH = (17 * level).toInt()
            ui.rect(cx + 1, y + 18 - barH, cw - 2, barH, Palette.alpha(Palette.mix(Palette.CARD, Palette.OWNED, 0.35f), tt))
            ui.textCentered(names[i], cx + cw / 2, y + 2, Palette.alpha(if (used) Palette.TEXT else Palette.FAINT, tt), 0.75f)
            ui.textCentered("$value", cx + cw / 2, y + 10, Palette.alpha(if (used) Palette.OWNED else Palette.FAINT, tt), 0.75f)
        }
        ui.hit(x, y, w, 19, Component.literal("EVs: " + SetText.evs(evs)))
    }

    /** IVs atuais com a ordem em que vale subir cada um (1 = primeiro). */
    private fun ivRow(ui: Ui, x: Int, y: Int, w: Int, set: SetView, tt: Float) {
        val names = SetText.STAT_NAMES
        ui.text(t("iv.title"), x, y, Palette.alpha(Palette.FAINT, tt), 0.75f)
        val order = if (set.ivPriority.isEmpty()) t("iv.all_good") else set.ivPriority.take(3).joinToString(" > ") { names[it] }
        ui.textRight(order, x + w, y, Palette.alpha(if (set.ivPriority.isEmpty()) Palette.GOOD else Palette.GOLD, tt), 0.75f)
        val top = y + 8
        val gap = 2
        val cw = (w - gap * 5) / 6
        for (i in 0 until 6) {
            val iv = set.ivs[i]
            val cx = x + i * (cw + gap)
            val unused = i in set.ivUnused
            val rank = set.ivPriority.indexOf(i)
            val color = when {
                unused -> Palette.FAINT
                iv >= 31 -> Palette.GOOD
                iv >= 20 -> Palette.TEXT
                else -> Palette.BAD
            }
            val edge = if (rank in 0..2) Palette.mix(Palette.PANEL_EDGE, Palette.GOLD, 1f - rank * 0.3f) else Palette.PANEL_EDGE
            ui.panel(cx, top, cw, 14, Palette.alpha(Palette.CARD, tt), Palette.alpha(edge, tt))
            ui.text(names[i], cx + 2, top + 4, Palette.alpha(Palette.FAINT, tt), 0.6f)
            ui.textRight(if (unused) "-" else "$iv", cx + cw - 2, top + 3, Palette.alpha(color, tt), 0.75f)
            if (rank >= 0) ui.text("${rank + 1}", cx + 2, top - 1, Palette.alpha(Palette.GOLD, tt), 0.5f)
        }
        val tip = Component.literal(t("iv.tip_title")).withStyle(ChatFormatting.GOLD)
        if (set.ivPriority.isEmpty()) {
            tip.append("\n").append(Component.literal(t("iv.all_good_long")).withStyle(ChatFormatting.GREEN))
        } else {
            set.ivPriority.forEachIndexed { n, i ->
                tip.append("\n").append(Component.literal("${n + 1}. ${names[i]}  ${set.ivs[i]} → 31").withStyle(ChatFormatting.WHITE))
            }
        }
        if (set.ivUnused.isNotEmpty()) {
            tip.append("\n").append(Component.literal(t("iv.unused", set.ivUnused.joinToString(", ") { names[it] })).withStyle(ChatFormatting.DARK_GRAY))
        }
        tip.append("\n\n").append(Component.literal(t("iv.how")).withStyle(ChatFormatting.GRAY))
        ui.hit(x, y, w, 22, tip)
    }

    private fun moveRow(ui: Ui, x: Int, y: Int, w: Int, h: Int, m: MoveView, owned: Boolean, index: Int) {
        val tt = enter(index + 2)
        val hover = smooth("move:$index", if (ui.over(x, y, w, h)) 1f else 0f)
        ui.panel(x, y, w, h, Palette.alpha(Palette.mix(Palette.CARD, Palette.CARD_HOVER, hover), tt), Palette.alpha(Palette.PANEL_EDGE, tt))

        val (status, color) = when {
            !owned -> (m.method.ifEmpty { t("move.learn") }) to Palette.CATALOG
            m.status == "KNOWN" -> t("move.known") to Palette.GOOD
            m.status == "SWAP" -> t("move.swap") to Palette.CYAN
            else -> (m.method.ifEmpty { t("move.learn") }) to Palette.GOLD
        }
        ui.textRight(status, x + w - 3, y + 3, Palette.alpha(color, tt), 0.75f)

        var tx = x + 3
        if (m.type.isNotEmpty()) tx = ui.types(listOf(m.type), tx, y + 2, tt) + 1
        val name = SetText.move(m.id)
        val room = w - (tx - x) - ui.width(status, 0.75f) - 8
        ui.text(name, tx, y + 3, Palette.alpha(Palette.TEXT, tt), if (ui.width(name) > room) 0.75f else 1f)

        if (h >= 18) {
            val detail = buildString {
                when (m.category) {
                    "PHYSICAL" -> append(t("move.physical"))
                    "SPECIAL" -> append(t("move.special"))
                    "STATUS" -> append(t("move.status"))
                }
                if (m.power > 0) append(" ${m.power}")
                if (m.note.isNotEmpty()) append(if (isEmpty()) m.note else " · ${m.note}")
            }
            ui.rich(detail, x + 3, y + 12, Palette.alpha(Palette.MUTED, tt), 0.75f, w - 6)
        }

        val tip = name.copy()
        if (m.note.isNotEmpty()) tip.append("\n").append(SetText.rich(m.note, ChatFormatting.GRAY))
        tip.append("\n").append(
            Component.literal(
                when {
                    !owned -> t("move.how", m.method.ifEmpty { t("move.unknown_method") })
                    m.status == "KNOWN" -> t("move.known_long")
                    m.status == "SWAP" -> t("move.swap_long")
                    else -> t("move.must_learn", m.method.ifEmpty { t("move.unknown_method") })
                }
            ).withStyle(ChatFormatting.GRAY)
        )
        ui.hit(x, y, w, h, tip)
    }

    // ---------------------------------------------------------------- entrada

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val area = scrollAreas.lastOrNull {
            mouseX >= it.x - 2 && mouseX < it.x + it.w + 4 && mouseY >= it.y && mouseY < it.y + it.h
        }
        if (area != null && scrollY != 0.0) {
            scrolls[area.key] = ((scrolls[area.key] ?: 0f) - scrollY.toFloat() * 24f).coerceIn(0f, area.max.toFloat())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (button != 0) return false
        // As areas registradas por ultimo ficam por cima (ex.: o X dentro do slot, a busca).
        val hit = hits.lastOrNull { it.onClick != null && it.contains(mouseX, mouseY) } ?: return false
        click()
        hit.onClick?.invoke()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input?.isFocused == true) {
            val text = typed()
            val results = searchResults()
            when {
                text.isEmpty() -> {}
                tab == Tab.RAID && state?.raid == null -> {
                    raidQuery = text
                    request()
                }
                tab == Tab.TRAINERS -> {
                    if (state?.trainer == null) trainerList?.trainers?.firstOrNull()?.let { chooseTrainer(it.id) }
                }
                // Um nome so: usa o primeiro card da busca, que ja mostra o que vai entrar.
                results.isNotEmpty() && text.none { it in ",;+/" } -> pickSearch(results.first())
                else -> addTyped()
            }
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && searchResults().isNotEmpty()) {
            clearInput()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        PokemonModels.clear()
        super.removed()
    }
}

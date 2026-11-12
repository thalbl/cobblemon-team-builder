package dev.thal.teambuilder.client

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.gui.TypeIcon
import com.cobblemon.mod.common.client.gui.drawProfilePokemon
import com.cobblemon.mod.common.client.render.models.blockbench.FloatingState
import com.cobblemon.mod.common.entity.PoseType
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.util.math.fromEulerXYZDegrees
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.core.Lang
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.joml.Vector3f

/** Cores da tela: paineis escuros azulados como os menus do Cobblemon, com poucos acentos. */
object Palette {
    const val DIM = 0x8C0B0E12.toInt()
    const val PANEL = 0xE61B2026.toInt()
    const val PANEL_EDGE = 0xFF39424C.toInt()
    const val CARD = 0xF0242A32.toInt()
    const val CARD_HOVER = 0xF0313A45.toInt()
    const val EDGE = 0xFF465260.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val MUTED = 0xFF9AA5B1.toInt()
    const val FAINT = 0xFF64707D.toInt()

    /** Pokemon do jogador. */
    const val OWNED = 0xFF6FE3A5.toInt()

    /** Especies do catalogo que o jogador nao tem. */
    const val CATALOG = 0xFF79B8FF.toInt()

    /** Selecao e destaque. */
    const val GOLD = 0xFFFFD166.toInt()
    const val RAID = 0xFFFF9F5A.toInt()
    const val GOOD = 0xFF6FE3A5.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    const val CYAN = 0xFF6FD8E8.toInt()

    fun alpha(color: Int, a: Float): Int {
        val base = (color ushr 24) and 0xFF
        return ((base * a.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0xFFFFFF)
    }

    fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * k).toInt() and 0xFF
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** Uma area clicavel e/ou com tooltip, registrada enquanto a tela e desenhada. */
class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val tooltip: Component?, val onClick: (() -> Unit)?) {
    fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
}

/** Primitivas de desenho de um quadro. A tela e redesenhada inteira a cada frame. */
class Ui(
    val g: GuiGraphics,
    val font: Font,
    val mouseX: Int,
    val mouseY: Int,
    val partialTick: Float,
    private val hits: MutableList<Hit>,
) {
    fun over(x: Int, y: Int, w: Int, h: Int) = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if ((color ushr 24) != 0 && w > 0 && h > 0) g.fill(x, y, x + w, y + h, color)
    }

    fun frame(x: Int, y: Int, w: Int, h: Int, color: Int) {
        rect(x, y, w, 1, color)
        rect(x, y + h - 1, w, 1, color)
        rect(x, y + 1, 1, h - 2, color)
        rect(x + w - 1, y + 1, 1, h - 2, color)
    }

    fun panel(x: Int, y: Int, w: Int, h: Int, fill: Int = Palette.PANEL, edge: Int = Palette.PANEL_EDGE) {
        rect(x, y, w, h, fill)
        frame(x, y, w, h, edge)
    }

    fun width(text: String, scale: Float = 1f): Int = (font.width(text) * scale).toInt()
    fun width(text: Component, scale: Float = 1f): Int = (font.width(text) * scale).toInt()

    /** Corta o texto para caber em [maxWidth] pixels. */
    fun fit(text: String, maxWidth: Int, scale: Float = 1f): String {
        val limit = (maxWidth / scale).toInt()
        if (limit <= 0) return ""
        if (font.width(text) <= limit) return text
        return font.plainSubstrByWidth(text, (limit - font.width("..")).coerceAtLeast(0)) + ".."
    }

    fun text(text: String, x: Int, y: Int, color: Int = Palette.TEXT, scale: Float = 1f, shadow: Boolean = false) =
        text(Component.literal(text), x, y, color, scale, shadow)

    fun text(text: Component, x: Int, y: Int, color: Int = Palette.TEXT, scale: Float = 1f, shadow: Boolean = false) {
        // O Minecraft trata alfa quase zero como opaco; texto invisivel simplesmente nao e desenhado.
        if ((color ushr 24) < 8) return
        if (scale == 1f) {
            g.drawString(font, text, x, y, color, shadow)
            return
        }
        val pose = g.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        g.drawString(font, text, 0, 0, color, shadow)
        pose.popPose()
    }

    fun textRight(text: String, right: Int, y: Int, color: Int = Palette.TEXT, scale: Float = 1f) =
        text(text, right - width(text, scale), y, color, scale)

    fun textCentered(text: String, centerX: Int, y: Int, color: Int = Palette.TEXT, scale: Float = 1f) =
        text(text, centerX - width(text, scale) / 2, y, color, scale)

    /** Icones de tipo do proprio Cobblemon (9x9 cada). Devolve o x logo depois do ultimo. */
    fun types(types: List<String>, x: Int, y: Int, opacity: Float = 1f): Int {
        var cx = x
        for (name in types) {
            val type = ElementalTypes.get(name) ?: continue
            TypeIcon(cx, y, type, small = true, opacity = opacity).render(g)
            cx += TYPE_ICON + 1
        }
        return cx
    }

    /** Um pedaco de texto rico: texto comum ou um icone de tipo. */
    private sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Type(val name: String) : Piece
    }

    private fun pieces(text: String): List<Piece> {
        val out = ArrayList<Piece>()
        var last = 0
        for (m in Lang.TYPE_TOKEN.findAll(text)) {
            val name = m.groupValues[1]
            if (ElementalTypes.get(name) == null) continue
            if (m.range.first > last) out += Piece.Text(text.substring(last, m.range.first))
            out += Piece.Type(name)
            last = m.range.last + 1
        }
        if (last < text.length) out += Piece.Text(text.substring(last))
        return out
    }

    fun richWidth(text: String, scale: Float = 1f): Int = pieces(text).sumOf {
        when (it) {
            is Piece.Text -> width(it.text, scale)
            is Piece.Type -> ((TYPE_ICON + 1) * scale).toInt()
        }
    }

    /**
     * Texto em que "{fire}" vira o icone do tipo, como em "cobre {dragon}". Corta com ".."
     * quando passa de [maxWidth]. Devolve o x logo depois do que foi desenhado.
     */
    fun rich(text: String, x: Int, y: Int, color: Int = Palette.TEXT, scale: Float = 1f, maxWidth: Int = Int.MAX_VALUE): Int {
        if ((color ushr 24) < 8) return x
        val opacity = ((color ushr 24) and 0xFF) / 255f
        val icon = ((TYPE_ICON + 1) * scale).toInt()
        val right = x + maxWidth
        var cx = x
        for (piece in pieces(text)) {
            when (piece) {
                is Piece.Text -> {
                    val w = width(piece.text, scale)
                    if (cx + w > right) {
                        text(fit(piece.text, right - cx, scale), cx, y, color, scale)
                        return right
                    }
                    text(piece.text, cx, y, color, scale)
                    cx += w
                }
                is Piece.Type -> {
                    if (cx + icon > right) {
                        if (cx + width("..", scale) <= right) text("..", cx, y, color, scale)
                        return right
                    }
                    val type = ElementalTypes.get(piece.name) ?: continue
                    val pose = g.pose()
                    pose.pushPose()
                    pose.translate(cx.toFloat(), y - scale, 0f)
                    pose.scale(scale, scale, 1f)
                    TypeIcon(0, 0, type, small = true, opacity = opacity).render(g)
                    pose.popPose()
                    cx += icon
                }
            }
        }
        return cx
    }

    /** Registra uma area clicavel/com tooltip e diz se o mouse esta sobre ela. */
    fun hit(x: Int, y: Int, w: Int, h: Int, tooltip: Component? = null, onClick: (() -> Unit)? = null): Boolean {
        hits += Hit(x, y, w, h, tooltip, onClick)
        return over(x, y, w, h)
    }

    companion object {
        const val TYPE_ICON = 9
    }
}

/** Desenha os modelos dos Pokemon com o renderizador de GUI do Cobblemon (o mesmo do PC). */
object PokemonModels {
    private val states = HashMap<String, FloatingState>()
    private val species = HashMap<String, Species?>()
    private val broken = HashSet<String>()

    /**
     * Area coberta por um painel flutuante (a lista da busca), como x0, y0, x1, y1.
     * Modelos 3D ficam a frente de qualquer painel 2D, entao quem esta por baixo dela nao e desenhado.
     */
    var occluder: IntArray? = null

    /**
     * @param key identifica esta instancia na tela, para a animacao continuar entre frames
     * @param partialTick 0 deixa o modelo parado; o delta do frame faz ele animar
     * @param onTop desenha mesmo dentro de [occluder] (para o proprio painel flutuante)
     */
    fun draw(
        g: GuiGraphics, key: String, speciesId: String, aspects: List<String>,
        x: Int, y: Int, size: Int, partialTick: Float = 0f, yaw: Float = 35f, zoom: Float = 1f,
        onTop: Boolean = false,
    ): Boolean {
        if (speciesId.isEmpty() || speciesId in broken) return false
        val o = occluder
        if (!onTop && o != null && x - 3 < o[2] && x + size + 3 > o[0] && y - 3 < o[3] && y + size + 3 > o[1]) return false
        val resolved = species.getOrPut(speciesId) {
            runCatching { PokemonSpecies.getByIdentifier(ResourceLocation.parse(speciesId)) }.getOrNull()
        } ?: return false
        val state = states.getOrPut(key) { FloatingState() }

        val pose = g.pose()
        g.enableScissor(x - 3, y - 3, x + size + 3, y + size + 3)
        pose.pushPose()
        try {
            // Mesmas proporcoes dos slots do PC do Cobblemon: caixa de 25px, escala 2.5, modelo a 4.5.
            pose.translate(x + size / 2.0, y - (zoom - 1f) * size / 2.0, 0.0)
            val scale = size / 10f * zoom
            pose.scale(scale, scale, 1f)
            drawProfilePokemon(
                RenderablePokemon(resolved, aspects.toSet()),
                pose,
                Quaternionf().fromEulerXYZDegrees(Vector3f(13f, yaw, 0f)),
                PoseType.PROFILE,
                state,
                partialTick,
                4.5f,
            )
        } catch (e: Throwable) {
            // Um modelo que falha nao pode derrubar a tela: marca e nao tenta de novo.
            broken += speciesId
            TeamBuilderMod.LOGGER.warn("Nao consegui desenhar o modelo de {}", speciesId, e)
        } finally {
            pose.popPose()
            g.disableScissor()
        }
        return true
    }

    fun clear() {
        states.clear()
        species.clear()
        broken.clear()
    }
}

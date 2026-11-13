package dev.thal.teambuilder.client

import com.google.gson.GsonBuilder
import dev.thal.teambuilder.TeamBuilderMod
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * O que o jogador ja viu do tutorial. Fica no cliente
 * (config/cobblemon_team_builder/client.json), entao vale para qualquer mundo ou servidor.
 */
object ClientPrefs {
    private class Data(var tutorialDone: Boolean = false, var tabsSeen: MutableSet<String> = HashSet())

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = FabricLoader.getInstance().configDir.resolve("cobblemon_team_builder").resolve("client.json")
    private var loaded: Data? = null

    private fun data(): Data {
        loaded?.let { return it }
        val out = try {
            if (Files.exists(file)) gson.fromJson(Files.readString(file), Data::class.java) ?: Data() else Data()
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui ler {}", file, e)
            Data()
        }
        // O Gson pode deixar campos nulos quando o arquivo e antigo ou foi editado a mao.
        @Suppress("SENSELESS_COMPARISON")
        if (out.tabsSeen == null) out.tabsSeen = HashSet()
        loaded = out
        return out
    }

    var tutorialDone: Boolean
        get() = data().tutorialDone
        set(value) {
            data().tutorialDone = value
            write()
        }

    fun tabSeen(tab: String) = tab in data().tabsSeen

    fun markTab(tab: String) {
        if (data().tabsSeen.add(tab)) write()
    }

    private fun write() {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(data()))
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui gravar {}", file, e)
        }
    }
}

/**
 * Um passo do tutorial: destaca a area [anchor] da tela (registrada enquanto ela e desenhada)
 * e mostra um balao com o titulo e o texto das chaves "tutorial.<key>.title" e ".text".
 * Sem area (ou se ela nao estiver na tela agora), o balao aparece no centro.
 */
data class TutorialStep(val key: String, val anchor: String?)

object TutorialSteps {
    /** Tour geral, na primeira vez que a tela abre. */
    val main = listOf(
        TutorialStep("welcome", null),
        TutorialStep("slots", "slots"),
        TutorialStep("score", "score"),
        TutorialStep("buttons", "buttons"),
        TutorialStep("tabs", "tabs"),
        TutorialStep("search", "search"),
        TutorialStep("advisor", "advisor"),
        TutorialStep("help", "help"),
    )

    /** Dica curta de cada aba, na primeira vez que ela e aberta. */
    fun forTab(tab: String): List<TutorialStep> = when (tab) {
        "SUGGEST" -> listOf(TutorialStep("suggest", "body"))
        "TEAMS" -> listOf(TutorialStep("teams", "body"))
        "ANALYSIS" -> listOf(TutorialStep("analysis_types", "analysis_types"), TutorialStep("analysis_modes", "analysis_modes"))
        "RAID" -> listOf(TutorialStep("raid", "body"))
        "TRAINERS" -> listOf(TutorialStep("trainers", "body"))
        "SAVED" -> listOf(TutorialStep("saved", "body"))
        else -> emptyList()
    }
}

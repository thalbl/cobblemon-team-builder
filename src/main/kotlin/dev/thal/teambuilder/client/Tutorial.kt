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
    /**
     * @param tabsSeen formato antigo (ate a 0.8.0): abas cuja dica ja apareceu, contam como versao 1
     * @param tabVersions versao da dica de cada aba que o jogador ja viu
     */
    private class Data(
        var tutorialDone: Boolean = false,
        var tabsSeen: MutableSet<String> = HashSet(),
        var tabVersions: MutableMap<String, Int> = HashMap(),
    )

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
        @Suppress("SENSELESS_COMPARISON")
        if (out.tabVersions == null) out.tabVersions = HashMap()
        loaded = out
        return out
    }

    var tutorialDone: Boolean
        get() = data().tutorialDone
        set(value) {
            data().tutorialDone = value
            write()
        }

    /** Versao da dica de [tab] que o jogador ja viu (0 = nunca viu). */
    fun seenVersion(tab: String): Int = data().tabVersions[tab] ?: if (tab in data().tabsSeen) 1 else 0

    fun markTab(tab: String, version: Int) {
        if (seenVersion(tab) >= version) return
        data().tabVersions[tab] = version
        write()
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

    /**
     * Versao atual da dica de cada aba. Ao mudar o texto de uma dica (recurso novo), sobe a versao:
     * quem ja tinha visto a antiga ve a nova uma vez, marcada como novidade.
     */
    fun version(tab: String): Int = when (tab) {
        "SUGGEST" -> 3 // 0.6.0 evolucoes do PC, 0.8.0 "aqui X%"
        "RAID" -> 2 // 0.7.0 aba GRUPO
        "TRAINERS" -> 3 // 0.6.0 rota e % pronto, 0.8.0 relatorio da derrota
        else -> 1
    }

    /** Dica curta de cada aba, na primeira vez que ela e aberta. TRAINER_DETAIL = um treinador escolhido. */
    fun forTab(tab: String): List<TutorialStep> = when (tab) {
        "SUGGEST" -> listOf(TutorialStep("suggest", "body"))
        "TEAMS" -> listOf(TutorialStep("teams", "body"))
        "ANALYSIS" -> listOf(TutorialStep("analysis_types", "analysis_types"), TutorialStep("analysis_modes", "analysis_modes"))
        "RAID" -> listOf(TutorialStep("raid", "body"))
        "TRAINERS" -> listOf(TutorialStep("trainers", "body"))
        "TRAINER_DETAIL" -> listOf(
            TutorialStep("trainer_ready", "trainer_header"),
            TutorialStep("trainer_enemies", "trainer_enemies"),
            TutorialStep("trainer_counters", "trainer_counters"),
        )
        "SAVED" -> listOf(TutorialStep("saved", "body"))
        else -> emptyList()
    }
}

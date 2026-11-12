package dev.thal.teambuilder

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.Weights
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** Opcoes do servidor, em config/cobblemon_team_builder/config.json. */
data class TeamBuilderConfig(
    /** Opcoes iniciais de cada jogador (ele pode mudar na tela). */
    val defaultLegendaries: Boolean = false,
    val defaultMegas: Boolean = true,
    val defaultDoubles: Boolean = false,
    /** Quantos cards a busca mostra. */
    val searchResults: Int = 8,
    /** Quantas ameacas a analise lista. */
    val threats: Int = 8,
    /** Nivel de permissao para /teambuilder snapshot e /teambuilder reload (2 = op comum). */
    val adminPermissionLevel: Int = 2,
    /** Pesos da nota do time; veja a documentacao de cada campo em core/TeamScorer.kt. */
    val weightsSingles: Weights = Weights.SINGLES,
    val weightsDoubles: Weights = Weights.DOUBLES,
)

object Config {
    private val gson = GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create()

    val dir: Path get() = FabricLoader.getInstance().configDir.resolve("cobblemon_team_builder")
    private val file: Path get() = dir.resolve("config.json")

    @Volatile
    var current: TeamBuilderConfig = TeamBuilderConfig()
        private set

    /**
     * Le o arquivo, completa campos que faltam com os padroes (assim versoes novas do mod
     * nao zeram pesos novos) e regrava o arquivo ja completo.
     */
    fun load(): TeamBuilderConfig {
        val defaults = gson.toJsonTree(TeamBuilderConfig()).asJsonObject
        val merged = try {
            if (Files.exists(file)) merge(defaults, JsonParser.parseString(Files.readString(file)).asJsonObject) else defaults
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.error("config.json invalido; usando os padroes", e)
            defaults
        }
        val config = gson.fromJson(merged, TeamBuilderConfig::class.java)
        current = config
        Weights.configured = mapOf(Format.SINGLES to config.weightsSingles, Format.DOUBLES to config.weightsDoubles)
        try {
            Files.createDirectories(dir)
            Files.writeString(file, gson.toJson(merged))
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui gravar {}", file, e)
        }
        return config
    }

    private fun merge(base: JsonObject, over: JsonObject): JsonObject {
        val out = base.deepCopy()
        for ((key, value) in over.entrySet()) {
            val current: JsonElement? = out.get(key)
            if (current != null && current.isJsonObject && value.isJsonObject) {
                out.add(key, merge(current.asJsonObject, value.asJsonObject))
            } else if (current != null) {
                out.add(key, value)
            }
        }
        return out
    }
}

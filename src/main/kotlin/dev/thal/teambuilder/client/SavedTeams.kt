package dev.thal.teambuilder.client

import com.google.gson.GsonBuilder
import dev.thal.teambuilder.TeamBuilderMod
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

data class SavedMember(
    val key: String = "",
    val name: String = "",
    val species: String = "",
    val aspects: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val owned: Boolean = false,
    /** Funcao escolhida (id de SetRole) ou null para a recomendada. */
    val role: String? = null,
    val roleLabel: String = "",
)

data class SavedTeam(
    val name: String = "",
    val members: List<SavedMember> = emptyList(),
    val total: Double = 0.0,
    val doubles: Boolean = false,
    val savedAt: Long = 0L,
)

/**
 * Times salvos para consulta. Ficam no cliente, em config/cobblemon_team_builder/saved_teams.json,
 * entao valem para qualquer mundo ou servidor.
 */
object SavedTeams {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = FabricLoader.getInstance().configDir.resolve("cobblemon_team_builder").resolve("saved_teams.json")

    private var loaded: MutableList<SavedTeam>? = null

    val teams: List<SavedTeam> get() = list()

    private fun list(): MutableList<SavedTeam> {
        loaded?.let { return it }
        val out = try {
            if (Files.exists(file)) {
                gson.fromJson(Files.readString(file), Array<SavedTeam>::class.java)?.filterNotNull()?.toMutableList() ?: ArrayList()
            } else ArrayList()
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui ler os times salvos", e)
            ArrayList()
        }
        loaded = out
        return out
    }

    fun add(team: SavedTeam) {
        list().add(0, team)
        write()
    }

    fun remove(index: Int) {
        val l = list()
        if (index in l.indices) {
            l.removeAt(index)
            write()
        }
    }

    private fun write() {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(list()))
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui gravar os times salvos", e)
        }
    }
}

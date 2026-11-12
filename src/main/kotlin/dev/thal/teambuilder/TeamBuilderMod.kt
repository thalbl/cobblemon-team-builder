package dev.thal.teambuilder

import dev.thal.teambuilder.cobblemon.Catalog
import dev.thal.teambuilder.cobblemon.Trainers
import dev.thal.teambuilder.command.TeamBuilderCommand
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.MetaLoader
import dev.thal.teambuilder.core.MetaSnapshot
import dev.thal.teambuilder.net.Network
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object TeamBuilderMod : ModInitializer {
    const val MOD_ID = "cobblemon_team_builder"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    var meta: MetaSnapshot = MetaSnapshot.EMPTY
        private set

    /** Snapshot de duplas; sem ele o modo 2v2 usa o de singles com pesos de duplas. */
    var doublesMeta: MetaSnapshot = MetaSnapshot.EMPTY
        private set

    fun metaFor(format: Format): MetaSnapshot =
        if (format == Format.DOUBLES && doublesMeta.size > 0) doublesMeta else meta

    /** Snapshot baixado pelo comando /teambuilder snapshot update; tem prioridade sobre o do jar. */
    fun snapshotFile(doubles: Boolean): Path = Config.dir.resolve("meta").resolve(if (doubles) "doubles.json" else "singles.json")

    override fun onInitialize() {
        Config.load()
        reloadMeta()
        Network.registerCommon()

        // Especies, golpes e treinadores vem de datapacks: refaz tudo a cada (re)carga.
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            Catalog.invalidate()
            Trainers.load(server.resourceManager)
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, _ ->
            Catalog.invalidate()
            Trainers.load(server.resourceManager)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            TeamBuilderCommand.register(dispatcher)
        }
    }

    /** Le os snapshots (o baixado, se houver; senao o que vem no jar) e descarta o catalogo. */
    fun reloadMeta() {
        meta = load(false)
        doublesMeta = load(true)
        LOGGER.info("Snapshots: singles {} ({} Pokemon), duplas {} ({} Pokemon)", meta.month, meta.size, doublesMeta.month, doublesMeta.size)
        Catalog.invalidate()
    }

    private fun load(doubles: Boolean): MetaSnapshot {
        val file = snapshotFile(doubles)
        if (Files.exists(file)) {
            try {
                return Files.newBufferedReader(file).use(MetaLoader::parse)
            } catch (e: Exception) {
                LOGGER.error("Snapshot baixado {} invalido; usando o do jar", file, e)
            }
        }
        return try {
            MetaLoader.loadBundled(if (doubles) MetaLoader.DOUBLES_PATH else MetaLoader.RESOURCE_PATH)
        } catch (e: Exception) {
            LOGGER.error("Falha ao ler o snapshot; sugestoes usarao so a heuristica", e)
            MetaSnapshot.EMPTY
        }
    }
}

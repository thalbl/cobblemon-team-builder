package dev.thal.teambuilder

import dev.thal.teambuilder.command.TeamBuilderCommand
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.MetaLoader
import dev.thal.teambuilder.core.MetaSnapshot
import dev.thal.teambuilder.net.Network
import net.fabricmc.api.ModInitializer
import dev.thal.teambuilder.cobblemon.Catalog
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

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

    override fun onInitialize() {
        meta = try {
            MetaLoader.loadBundled()
        } catch (e: Exception) {
            LOGGER.error("Falha ao ler o snapshot de meta; sugestoes usarao so a heuristica", e)
            MetaSnapshot.EMPTY
        }
        LOGGER.info("Snapshot de meta {}: {} Pokemon", meta.month, meta.size)
        doublesMeta = try {
            MetaLoader.loadBundled(MetaLoader.DOUBLES_PATH)
        } catch (e: Exception) {
            LOGGER.error("Falha ao ler o snapshot de duplas", e)
            MetaSnapshot.EMPTY
        }
        if (doublesMeta.size > 0) LOGGER.info("Snapshot de duplas {}: {} Pokemon", doublesMeta.month, doublesMeta.size)

        Network.registerCommon()

        // Especies e golpes vem de datapacks: o catalogo precisa ser refeito a cada (re)carga.
        ServerLifecycleEvents.SERVER_STARTED.register { Catalog.invalidate() }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { _, _, _ -> Catalog.invalidate() }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            TeamBuilderCommand.register(dispatcher)
        }
    }
}

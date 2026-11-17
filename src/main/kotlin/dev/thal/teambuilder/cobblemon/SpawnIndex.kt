package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnPools
import com.cobblemon.mod.common.api.spawning.TimeRange
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail
import com.cobblemon.mod.common.pokemon.Species
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.StorageReader.showdownId
import dev.thal.teambuilder.core.SpawnEntry
import dev.thal.teambuilder.core.SpawnTable
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

/**
 * Regras de spawn natural do Cobblemon (inclusive as dos datapacks do modpack), lidas uma vez e
 * guardadas como [SpawnTable]. Refeito quando o servidor recarrega os datapacks.
 */
object SpawnIndex {
    @Volatile
    private var table: SpawnTable? = null

    fun invalidate() {
        table = null
    }

    fun get(): SpawnTable = table ?: build().also { table = it }

    /** Bioma onde o jogador esta ("minecraft:plains") e a hora do dia (0..23999). */
    fun where(player: ServerPlayer): Pair<String, Int> {
        val level = player.serverLevel()
        val biome = level.getBiome(player.blockPosition()).unwrapKey().map { it.location().toString() }.orElse("")
        return biome to (level.dayTime % 24000).toInt()
    }

    private fun speciesOf(name: String): Species? =
        if (':' in name) runCatching { PokemonSpecies.getByIdentifier(ResourceLocation.parse(name)) }.getOrNull()
        else PokemonSpecies.getByName(name.lowercase())

    private fun build(): SpawnTable {
        val out = ArrayList<SpawnEntry>()
        var failed = 0
        for (detail in CobblemonSpawnPools.WORLD_SPAWN_POOL) {
            if (detail !is PokemonSpawnDetail) continue
            try {
                if (!detail.isModDependencySatisfied()) continue
                val props = detail.pokemon
                val species = props.species?.let(::speciesOf) ?: continue
                val form = species.getForm(props.aspects)
                val conditions = detail.conditions
                // Sem condicao de bioma em alguma alternativa: vale em qualquer bioma.
                val anyBiome = conditions.isEmpty() || conditions.any { it.biomes.isNullOrEmpty() }
                val timeRange = conditions.firstNotNullOfOrNull { it.timeRange }
                val timeName = timeRange?.let { tr ->
                    TimeRange.timeRanges.entries.firstOrNull { it.value.ranges == tr.ranges }?.key
                }.orEmpty()
                val levels = detail.levelRange ?: (props.level?.let { it..it } ?: 1..100)
                out += SpawnEntry(
                    formId = showdownId(form.showdownId()),
                    speciesId = showdownId(species.showdownId()),
                    type = detail.spawnablePositionType.name,
                    bucket = detail.bucket.name,
                    bucketWeight = detail.bucket.weight.toDouble(),
                    weight = detail.weight.toDouble(),
                    biomes = detail.validBiomes.mapTo(HashSet()) { it.toString() },
                    anyBiome = anyBiome,
                    minLevel = levels.first,
                    maxLevel = levels.last,
                    times = timeRange?.ranges.orEmpty(),
                    timeName = timeName,
                )
            } catch (e: Throwable) {
                failed++
                if (failed <= 3) TeamBuilderMod.LOGGER.debug("Spawn ignorado: {}", detail.id, e)
            }
        }
        TeamBuilderMod.LOGGER.info("Spawns naturais: {} regras ({} ignoradas)", out.size, failed)
        return SpawnTable(out)
    }
}

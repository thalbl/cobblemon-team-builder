package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.StorageReader.showdownId
import dev.thal.teambuilder.core.RaidBossInfo
import dev.thal.teambuilder.core.TypeChart
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

/**
 * Descobre qual chefe de raid o jogador esta enfrentando, usando o mod Cobblemon Raid Dens.
 * Tudo por reflexao: o Raid Dens e opcional e nao entra como dependencia de compilacao.
 */
object RaidLookup {
    private const val RAID_HELPER = "com.necro.raid.dens.common.raids.helpers.RaidHelper"
    private const val SEARCH_RADIUS = 8

    val installed: Boolean by lazy { FabricLoader.getInstance().isModLoaded("cobblemonraiddens") }

    /** O chefe da raid em que o jogador esta, ou do cristal de raid mais proximo. */
    fun detect(player: ServerPlayer, catalog: Catalog): RaidBossInfo? {
        if (!installed) return null
        return try {
            (activeRaidBoss(player) ?: nearestCrystalBoss(player))?.let { toInfo(it, catalog) }
        } catch (e: Throwable) {
            TeamBuilderMod.LOGGER.warn("Nao consegui ler a raid pelo Cobblemon Raid Dens", e)
            null
        }
    }

    /** Monta um chefe a partir de texto: "charizard", "charizard fire", "great tusk fada". */
    fun manual(text: String, catalog: Catalog): RaidBossInfo? {
        val words = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val tera = typeFromWord(words.last()).takeIf { words.size > 1 }
        val name = (if (tera != null) words.dropLast(1) else words).joinToString(" ")
        val profile = catalog.resolver.resolveOne(name)?.let { catalog.all[it] } ?: return null
        return RaidBossInfo(
            name = profile.name,
            types = profile.candidate.types,
            teraType = tera,
            moves = profile.likelyMoves,
            ability = profile.ability,
            feature = if (tera != null) "tera" else "",
            species = profile.candidate.species,
            aspects = profile.candidate.aspects,
        )
    }

    private fun typeFromWord(word: String): String? {
        val w = showdownId(word)
        return w.takeIf { TypeChart.indexOf(it) >= 0 } ?: PORTUGUESE_TYPES[w]
    }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(target, *args)

    private fun activeRaidBoss(player: ServerPlayer): Any? {
        val raids = Class.forName(RAID_HELPER).getField("ACTIVE_RAIDS").get(null) as? Map<*, *> ?: return null
        val mine = raids.values.filterNotNull().firstOrNull { call(it, "isPlayerIn", player) == true } ?: return null
        return call(mine, "getRaidBoss")
    }

    private fun nearestCrystalBoss(player: ServerPlayer): Any? {
        val level = player.level()
        val origin = player.blockPosition()
        var best: Any? = null
        var bestDistance = Double.MAX_VALUE
        for (pos in BlockPos.betweenClosed(origin.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS), origin.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            val entity = level.getBlockEntity(pos) ?: continue
            if (entity.javaClass.methods.none { it.name == "getRaidBoss" && it.parameterCount == 0 }) continue
            val boss = call(entity, "getRaidBoss") ?: continue
            val distance = pos.distSqr(origin)
            if (distance < bestDistance) {
                bestDistance = distance
                best = boss
            }
        }
        return best
    }

    private fun optional(target: Any, name: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)

    private fun toInfo(boss: Any, catalog: Catalog): RaidBossInfo? {
        // "getBoss" guarda so os ajustes do chefe (tera, golpes...) e muitas vezes nem tem especie:
        // criar o Pokemon a partir dele sorteava uma especie diferente a cada leitura. As
        // propriedades completas vem de "getBossProperties", e a especie exibida no cristal fica
        // fixa em "getDisplaySpeciesIdentifier".
        val base = (optional(boss, "getBossProperties") ?: call(boss, "getBoss")) as? PokemonProperties ?: return null
        val properties = base.copy()
        val displayId = optional(boss, "getDisplaySpeciesIdentifier")?.toString()
        if (!displayId.isNullOrBlank()) properties.species = displayId
        @Suppress("UNCHECKED_CAST")
        val displayAspects = (optional(boss, "getDisplayAspects") as? Set<String>).orEmpty()
        val pokemon = properties.create()
        val feature = (call(boss, "getFeature") as? String).orEmpty().lowercase()
        val raidType = call(boss, "getType")?.toString()?.lowercase().orEmpty()
        val stars = (call(boss, "getTier") as? Enum<*>)?.let { it.ordinal + 1 } ?: 0
        val speciesId = showdownId(pokemon.species.showdownId())

        // Raids "mega" usam a tipagem da forma mega; raids "tera" trocam a tipagem pelo tipo da raid.
        val megaForm = if (feature.startsWith("mega")) catalog.all[speciesId + feature.replace("_", "")] else null
        val tera = when {
            feature == "tera" && TypeChart.indexOf(raidType) >= 0 -> raidType
            else -> properties.teraType?.lowercase()?.takeIf { TypeChart.indexOf(it) >= 0 }
        }
        return RaidBossInfo(
            name = megaForm?.name ?: pokemon.species.name,
            types = megaForm?.candidate?.types ?: pokemon.form.types.map { it.showdownId.lowercase() },
            teraType = tera,
            moves = pokemon.moveSet.getMoves().map { showdownId(it.name) },
            ability = megaForm?.ability ?: showdownId(pokemon.ability.name),
            stars = stars,
            feature = feature,
            partySize = (call(boss, "getRaidPartySize") as? Int) ?: 0,
            species = megaForm?.candidate?.species ?: pokemon.species.resourceIdentifier.toString(),
            aspects = megaForm?.candidate?.aspects ?: displayAspects.ifEmpty { pokemon.aspects.toSet() },
        )
    }

    private val PORTUGUESE_TYPES = mapOf(
        "fogo" to "fire", "agua" to "water", "planta" to "grass", "grama" to "grass", "eletrico" to "electric",
        "gelo" to "ice", "lutador" to "fighting", "veneno" to "poison", "venenoso" to "poison", "terra" to "ground",
        "terrestre" to "ground", "voador" to "flying", "psiquico" to "psychic", "inseto" to "bug", "pedra" to "rock",
        "fantasma" to "ghost", "dragao" to "dragon", "sombrio" to "dark", "noturno" to "dark", "aco" to "steel",
        "metal" to "steel", "fada" to "fairy",
    )
}

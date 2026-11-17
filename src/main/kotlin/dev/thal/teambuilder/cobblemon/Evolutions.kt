package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution
import com.cobblemon.mod.common.pokemon.requirements.FriendshipRequirement
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.StorageReader.showdownId
import dev.thal.teambuilder.core.OwnedPokemon
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * Em que cada Pokemon do jogador pode evoluir, ate duas etapas a frente (Machop -> Machoke -> Machamp).
 * O resultado depende so da especie e dos aspectos (formas regionais seguem na evolucao), entao fica em cache.
 */
object Evolutions {
    /**
     * @param formId id da forma final no catalogo ("machamp", "ninetalesalola")
     * @param method como fazer a primeira evolucao: "lv:28", "item:cobblemon:fire_stone", "trade", "friendship", "other"
     * @param stages 1 = evolucao direta, 2 = evolucao da evolucao
     */
    data class Step(val formId: String, val method: String, val stages: Int)

    private const val MAX_STAGES = 2
    private val cache = ConcurrentHashMap<String, List<Step>>()

    fun clear() = cache.clear()

    fun of(mine: OwnedPokemon): List<Step> =
        cache.getOrPut(mine.species + "|" + mine.aspects.sorted().joinToString(",")) {
            try {
                val species = speciesOf(mine.species) ?: return@getOrPut emptyList()
                val out = ArrayList<Step>()
                walk(species, mine.aspects, null, 1, out)
                out.distinctBy { it.formId }
            } catch (e: Throwable) {
                TeamBuilderMod.LOGGER.debug("Nao consegui ler as evolucoes de {}", mine.species, e)
                emptyList()
            }
        }

    private fun walk(species: Species, aspects: Set<String>, firstMethod: String?, stage: Int, out: MutableList<Step>) {
        for (evo in species.evolutions) {
            val result = evo.result
            val next = result.species?.let(::speciesOf) ?: continue
            // A evolucao mantem os aspectos (Vulpix de Alola vira Ninetales de Alola), a menos que defina os seus.
            val nextAspects = result.aspects.ifEmpty { aspects }
            val form = next.getForm(nextAspects)
            val method = firstMethod ?: method(evo)
            out += Step(showdownId(form.showdownId()), method, stage)
            if (stage < MAX_STAGES) walk(next, nextAspects, method, stage + 1, out)
        }
    }

    private fun speciesOf(name: String): Species? =
        if (':' in name) runCatching { PokemonSpecies.getByIdentifier(ResourceLocation.parse(name)) }.getOrNull()
        else PokemonSpecies.getByName(name.lowercase())

    private fun method(evo: Evolution): String {
        val level = evo.requirements.filterIsInstance<LevelRequirement>().firstOrNull()?.minLevel
        if (evo is ItemInteractionEvolution) {
            val item = runCatching {
                evo.requiredContext.items().flatMap { set -> set.stream().findFirst() }
                    .map { BuiltInRegistries.ITEM.getKey(it.value()).toString() }.orElse(null)
            }.getOrNull()
            if (item != null) return "item:$item"
        }
        if (evo is TradeEvolution) return "trade"
        if (level != null && level > 1) return "lv:$level"
        if (evo.requirements.any { it is FriendshipRequirement }) return "friendship"
        return "other"
    }
}

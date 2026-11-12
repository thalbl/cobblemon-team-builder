package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.StorageReader.showdownId
import dev.thal.teambuilder.core.BuildOptions
import dev.thal.teambuilder.core.Candidate
import dev.thal.teambuilder.core.Format
import dev.thal.teambuilder.core.Lang
import dev.thal.teambuilder.core.Weights
import dev.thal.teambuilder.core.MoveCategory
import dev.thal.teambuilder.core.MoveInfo
import dev.thal.teambuilder.core.NameResolver
import dev.thal.teambuilder.core.Profile
import dev.thal.teambuilder.core.ProfileBuilder
import dev.thal.teambuilder.core.RaidAdvisor
import dev.thal.teambuilder.core.SetAdvisor
import dev.thal.teambuilder.core.StatKey
import dev.thal.teambuilder.core.TeamBuilderEngine
import dev.thal.teambuilder.core.TeamScorer

/**
 * Tudo que o motor precisa e que vem dos dados do Cobblemon: golpes e todas as
 * especies/formas implementadas. Montado sob demanda e descartado quando o servidor
 * (re)carrega os datapacks.
 */
class Catalog private constructor(
    val profiles: ProfileBuilder,
    /** Todas as especies/formas jogaveis, por id estilo Showdown. */
    val all: Map<String, Profile>,
    val scorer: TeamScorer,
    val advisor: SetAdvisor,
    val raids: RaidAdvisor,
    val moves: Map<String, MoveInfo>,
    val format: Format,
) {
    /** Formas mega de cada especie, para oferecer a mega dos Pokemon que o jogador ja tem. */
    val megasBySpecies: Map<String, List<Profile>> =
        all.values.filter { it.isMega }.groupBy { it.speciesId }

    val resolver = NameResolver(all.keys)

    val threats = dev.thal.teambuilder.core.ThreatAnalyzer(moves)
    val battles = dev.thal.teambuilder.core.BattleAdvisor(raids)

    fun engine(options: BuildOptions = BuildOptions(), lang: Lang = Lang.EN) = TeamBuilderEngine(scorer, options, lang)

    companion object {
        /** Formas que so existem em batalha ou em eventos nao ocupam slot de time. */
        private val EXCLUDED_LABELS = setOf("gmax", "primal", "totem")

        private val cached = java.util.concurrent.ConcurrentHashMap<Format, Catalog>()

        fun get(format: Format = Format.SINGLES): Catalog = cached.getOrPut(format) { build(format) }

        fun invalidate() {
            cached.clear()
        }

        private fun build(format: Format): Catalog {
            val meta = TeamBuilderMod.metaFor(format)
            val moves = Moves.all().associate { m ->
                val id = showdownId(m.name)
                id to MoveInfo(
                    id = id,
                    type = m.elementalType.showdownId.lowercase(),
                    category = when (m.damageCategory.name.lowercase()) {
                        "physical" -> MoveCategory.PHYSICAL
                        "special" -> MoveCategory.SPECIAL
                        else -> MoveCategory.STATUS
                    },
                    power = m.power.toInt(),
                    priority = m.priority,
                    spread = m.target.name.startsWith("all"),
                )
            }
            val builder = ProfileBuilder(meta, moves, format)
            val all = LinkedHashMap<String, Profile>()
            for (species in PokemonSpecies.implemented) {
                val forms = (listOf(species.standardForm) + species.forms)
                    .filter { form -> form.labels.none { it.lowercase() in EXCLUDED_LABELS } }
                for (form in forms) {
                    val candidate = toCandidate(species, form)
                    all.putIfAbsent(candidate.id, builder.build(candidate))
                }
            }
            TeamBuilderMod.LOGGER.info("Catalogo {}: {} formas, {} golpes", format, all.size, moves.size)
            val scorer = TeamScorer(meta, Weights.of(format))
            return Catalog(builder, all, scorer, SetAdvisor(moves, scorer, format), RaidAdvisor(moves), moves, format)
        }

        private fun toCandidate(species: Species, form: FormData): Candidate {
            val isStandard = form === species.standardForm || form.name.equals("normal", ignoreCase = true)
            val stats = HashMap<StatKey, Int>()
            for (stat in Stats.PERMANENT) {
                val key = StatKey.fromShowdownId(stat.showdownId) ?: continue
                stats[key] = form.baseStats[stat] ?: 0
            }
            return Candidate(
                id = showdownId(form.showdownId()),
                speciesId = showdownId(species.showdownId()),
                name = if (isStandard) species.name else "${species.name}-${form.name}",
                types = form.types.map { it.showdownId.lowercase() },
                abilities = form.abilities.map { showdownId(it.template.name) },
                baseStats = stats,
                legalMoves = form.moves.getAllLegalMoves().mapTo(HashSet()) { showdownId(it.name) },
                learnMethods = StorageReader.learnMethods(form.moves),
                legendary = StorageReader.isLegendary(species.labels),
                species = species.resourceIdentifier.toString(),
                aspects = form.aspects.toSet(),
                megaOf = if (form.labels.any { it.equals("mega", ignoreCase = true) }) showdownId(species.showdownId()) else null,
            )
        }
    }
}

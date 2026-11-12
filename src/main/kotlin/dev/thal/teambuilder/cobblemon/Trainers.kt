package dev.thal.teambuilder.cobblemon

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.StorageReader.showdownId
import dev.thal.teambuilder.core.EnemyMon
import dev.thal.teambuilder.core.NameResolver
import net.minecraft.server.packs.resources.ResourceManager

/** Um Pokemon do time de um treinador, como esta no datapack. */
data class TrainerMon(
    val species: String,
    val aspects: Set<String>,
    val mega: Boolean,
    val level: Int,
    val moves: List<String>,
    val ability: String,
    val item: String,
    val nature: String,
)

data class Trainer(
    val id: String,
    val name: String,
    /** Tipo do treinador no RCT ("kanto_league") e o nome dele ("Elite Four"). */
    val type: String,
    val typeLabel: String,
    val team: List<TrainerMon>,
) {
    val maxLevel: Int get() = team.maxOfOrNull { it.level } ?: 0

    /** Lideres, Elite 4, campeoes, rivais e equipes vilas vem antes dos treinadores comuns. */
    val important: Boolean get() = type.isNotEmpty() && type != "normal"
}

/**
 * Times dos treinadores do Radical Cobblemon Trainers (RCT), lidos dos datapacks carregados:
 * data/rctmod/trainers/<id>.json, com o tipo em mobs/trainers/single/<id>.json.
 */
object Trainers {
    private const val NAMESPACE = "rctmod"

    @Volatile
    var all: Map<String, Trainer> = emptyMap()
        private set

    fun load(resources: ResourceManager) {
        all = try {
            read(resources)
        } catch (e: Throwable) {
            TeamBuilderMod.LOGGER.warn("Nao consegui ler os treinadores do RCT", e)
            emptyMap()
        }
        if (all.isNotEmpty()) TeamBuilderMod.LOGGER.info("Treinadores do RCT: {}", all.size)
    }

    private fun json(resources: ResourceManager, folder: String): Map<String, JsonObject> {
        val out = HashMap<String, JsonObject>()
        for ((loc, res) in resources.listResources(folder) { it.namespace == NAMESPACE && it.path.endsWith(".json") }) {
            val id = loc.path.removePrefix("$folder/").removeSuffix(".json")
            try {
                res.openAsReader().use { out[id] = JsonParser.parseReader(it).asJsonObject }
            } catch (e: Exception) {
                TeamBuilderMod.LOGGER.debug("Treinador invalido {}", loc, e)
            }
        }
        return out
    }

    /** {"literal": "Misty"} ou {"translate": "..."} -> texto. */
    private fun text(el: JsonElement?, fallback: String): String {
        if (el == null || el.isJsonNull) return fallback
        if (el.isJsonPrimitive) return el.asString
        val o = el.asJsonObject
        return (o["literal"] ?: o["text"] ?: o["translate"])?.asString ?: fallback
    }

    private fun strings(el: JsonElement?): List<String> = when {
        el == null || el.isJsonNull -> emptyList()
        el.isJsonArray -> el.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
        else -> listOf(el.asString)
    }

    private fun read(resources: ResourceManager): Map<String, Trainer> {
        val typeNames = json(resources, "trainer_types").mapValues { (id, o) -> text(o["name"], id) }
        val mobs = json(resources, "mobs/trainers/single")
        val out = LinkedHashMap<String, Trainer>()
        for ((id, o) in json(resources, "trainers")) {
            val team = o.getAsJsonArray("team")?.mapNotNull { el ->
                val m = el.asJsonObject
                val species = m["species"]?.asString ?: return@mapNotNull null
                TrainerMon(
                    species = species,
                    aspects = strings(m["aspects"]).toSet(),
                    mega = m.getAsJsonObject("gimmicks")?.get("mega")?.asBoolean == true,
                    level = m["level"]?.asInt ?: 50,
                    moves = strings(m["moveset"]).map(::showdownId),
                    ability = showdownId(m["ability"]?.asString.orEmpty()),
                    item = strings(m["heldItem"]).firstOrNull()?.substringAfter(':')?.let(::showdownId).orEmpty(),
                    nature = m["nature"]?.asString.orEmpty(),
                )
            }.orEmpty()
            if (team.isEmpty()) continue
            val type = mobs[id]?.get("type")?.asString.orEmpty()
            out[id] = Trainer(id, text(o["name"], id), type, typeNames[type].orEmpty(), team)
        }
        return out
    }

    private val REGIONAL = mapOf("alolan" to "alola", "galarian" to "galar", "hisuian" to "hisui", "paldean" to "paldea")

    /** O Pokemon do treinador no catalogo: forma regional e mega pelos aspectos, gimmicks e item. */
    fun enemy(m: TrainerMon, catalog: Catalog): EnemyMon? {
        val base = showdownId(m.species.substringAfter(':'))
        val regional = m.aspects.firstNotNullOfOrNull { REGIONAL[it.lowercase()] }
        var profile = (regional?.let { catalog.all[base + it] }) ?: catalog.all[base]
            ?: catalog.resolver.resolveOne(m.species)?.let { catalog.all[it] }
            ?: return null
        if (m.mega) {
            val megas = catalog.megasBySpecies[profile.speciesId].orEmpty()
            val bySuffix = when {
                m.item.endsWith("x") -> megas.firstOrNull { it.id.endsWith("x") }
                m.item.endsWith("y") -> megas.firstOrNull { it.id.endsWith("y") }
                else -> null
            }
            (bySuffix ?: megas.firstOrNull())?.let { profile = it }
        }
        return EnemyMon(profile, m.level, m.moves, m.ability, m.item, m.nature)
    }

    /** Busca por nome, id ou tipo; sem texto, os treinadores importantes do mais fraco ao mais forte. */
    fun search(query: String, limit: Int = 12): List<Trainer> {
        val q = NameResolver.normalize(query)
        val list = all.values
        if (q.isEmpty()) {
            return list.filter { it.important }.sortedWith(compareBy({ it.maxLevel }, { it.name })).take(limit * 4)
        }
        fun rank(t: Trainer): Int? {
            val name = NameResolver.normalize(t.name)
            return when {
                name.startsWith(q) -> 0
                q in name || q in t.id -> 1
                q in NameResolver.normalize(t.typeLabel) || q in t.type -> 2
                else -> null
            }
        }
        return list.mapNotNull { t -> rank(t)?.let { it to t } }
            .sortedWith(compareBy<Pair<Int, Trainer>> { it.first }.thenByDescending { it.second.important }.thenBy { it.second.maxLevel })
            .map { it.second }
            .take(limit)
    }
}

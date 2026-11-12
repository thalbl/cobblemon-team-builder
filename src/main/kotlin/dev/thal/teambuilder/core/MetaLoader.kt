package dev.thal.teambuilder.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader

/** Le o JSON gerado por tools/build_snapshot.py. */
object MetaLoader {
    const val RESOURCE_PATH = "/data/cobblemon_team_builder/meta/singles.json"
    const val DOUBLES_PATH = "/data/cobblemon_team_builder/meta/doubles.json"

    fun loadBundled(path: String = RESOURCE_PATH): MetaSnapshot {
        val stream = MetaLoader::class.java.getResourceAsStream(path)
            ?: return MetaSnapshot.EMPTY
        return stream.reader(Charsets.UTF_8).use(::parse)
    }

    fun parse(reader: Reader): MetaSnapshot {
        val root = JsonParser.parseReader(reader).asJsonObject
        val formatUsage = root.getAsJsonObject("formatUsage").entrySet().associate { (fmt, usage) ->
            fmt to usage.asJsonObject.toDoubleMap()
        }
        val species = root.getAsJsonObject("species").entrySet().associate { (id, el) ->
            val o = el.asJsonObject
            id to MetaEntry(
                name = o["name"].asString,
                format = o["format"].asString,
                tier = o["tier"].asInt,
                usage = o["usage"].asDouble,
                moves = o.getAsJsonObject("moves").toDoubleMap(),
                items = o.getAsJsonObject("items").toDoubleMap(),
                abilities = o.getAsJsonObject("abilities").toDoubleMap(),
                spreads = o.getAsJsonArray("spreads").map { s ->
                    val so = s.asJsonObject
                    Spread(
                        nature = so["nature"].asString,
                        evs = so.getAsJsonArray("evs").map { it.asInt },
                        freq = so["freq"].asDouble,
                    )
                },
                teammates = o.getAsJsonObject("teammates").toDoubleMap(),
                counters = o.getAsJsonObject("counters").toDoubleMap(),
            )
        }
        return MetaSnapshot(root["month"].asString, formatUsage, species)
    }

    // LinkedHashMap preserva a ordem do arquivo, que ja vem do mais para o menos usado.
    private fun JsonObject.toDoubleMap(): Map<String, Double> =
        entrySet().associateTo(LinkedHashMap()) { (k, v) -> k to v.asDouble }
}

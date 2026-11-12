package dev.thal.teambuilder.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.round

/**
 * Gera o snapshot de meta direto das estatisticas de uso do Smogon (arquivos "chaos").
 * Mesma logica de tools/build_snapshot.py, para atualizar os dados sem recompilar o mod.
 */
object SnapshotBuilder {
    private const val BASE = "https://www.smogon.com/stats/"
    private const val UA = "Mozilla/5.0 (compatible; team-builder-snapshot)"
    private const val CUTOFF = 1500
    private const val MIN_RAW = 30

    /** Formato e "tier" (0 = mais forte). A ordem desempata. */
    val SINGLES = listOf(
        "gen9nationaldex" to 1, "gen9nationaldexubers" to 0, "gen9nationaldexuu" to 2,
        "gen9ou" to 1, "gen9ubers" to 0, "gen9uu" to 2, "gen9ru" to 3, "gen9nu" to 4, "gen9pu" to 5, "gen9zu" to 6,
    )
    val DOUBLES = listOf(
        "gen9doublesou" to 1, "gen9doublesubers" to 0, "gen9doublesuu" to 2, "gen9nationaldexdoubles" to 1,
    )

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
    }

    private fun fetch(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).timeout(Duration.ofMinutes(5)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IllegalStateException("HTTP ${response.statusCode()} em $url")
        return response.body()
    }

    fun latestMonth(): String {
        val months = Regex("href=\"(\\d{4}-\\d{2})/\"").findAll(fetch(BASE)).map { it.groupValues[1] }.toSortedSet()
        return months.lastOrNull() ?: throw IllegalStateException("Nenhum mes listado em $BASE")
    }

    private fun vgcFormats(month: String): List<Pair<String, Int>> =
        Regex("href=\"(gen9vgc[a-z0-9]+)-$CUTOFF\\.json\"").findAll(fetch("$BASE$month/chaos/"))
            .map { it.groupValues[1] }.filter { "bo3" !in it }.distinct().map { it to 1 }.toList()

    /**
     * Baixa e monta o snapshot. [log] recebe o progresso, um formato por vez.
     * Formatos que nao existem no mes sao pulados.
     */
    fun build(month: String?, doubles: Boolean, log: (String) -> Unit = {}): JsonObject {
        val m = month ?: latestMonth()
        val formats = if (doubles) DOUBLES + runCatching { vgcFormats(m) }.getOrDefault(emptyList()) else SINGLES
        val species = LinkedHashMap<String, JsonObject>()
        val usageOf = HashMap<String, Double>()
        val formatUsage = JsonObject()
        for ((fmt, tier) in formats) {
            val data = try {
                JsonParser.parseString(fetch("$BASE$m/chaos/$fmt-$CUTOFF.json")).asJsonObject.getAsJsonObject("data")
            } catch (e: Exception) {
                log("$fmt: ${e.message}")
                continue
            }
            log("$fmt: ${data.size()}")
            val usage = JsonObject()
            for ((name, el) in data.entrySet()) {
                val u = el.asJsonObject["usage"].asDouble
                if (u >= 0.0005) usage.addProperty(toId(name), round5(u))
            }
            formatUsage.add(fmt, usage)
            for ((name, el) in data.entrySet()) {
                val e = el.asJsonObject
                if (e["Raw count"].asDouble < MIN_RAW) continue
                val id = toId(name)
                val u = e["usage"].asDouble
                // fica com o formato em que o Pokemon e mais usado
                if (u > (usageOf[id] ?: -1.0)) {
                    buildEntry(name, e, fmt, tier)?.let {
                        species[id] = it
                        usageOf[id] = u
                    }
                }
            }
        }
        val out = JsonObject()
        out.addProperty("month", m)
        out.addProperty("cutoff", CUTOFF)
        out.add("formatUsage", formatUsage)
        out.add("species", JsonObject().also { o -> species.forEach { (k, v) -> o.add(k, v) } })
        return out
    }

    fun toId(name: String): String = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun round5(v: Double) = round(v * 100000) / 100000
    private fun round4(v: Double) = round(v * 10000) / 10000

    private fun top(d: JsonObject?, weight: Double, n: Int, minFrac: Double = 0.01): JsonObject {
        val out = JsonObject()
        if (d == null) return out
        d.entrySet().asSequence()
            .map { (k, v) -> toId(k) to v.asDouble / weight }
            .filter { it.first.isNotEmpty() && it.first != "nothing" && it.second >= minFrac }
            .sortedByDescending { it.second }
            .take(n)
            .forEach { (k, f) -> out.addProperty(k, round4(f)) }
        return out
    }

    /** Uma entrada do snapshot a partir dos dados "chaos" de um Pokemon. */
    internal fun buildEntry(name: String, e: JsonObject, fmt: String, tier: Int): JsonObject? {
        val weight = e.getAsJsonObject("Abilities")?.entrySet()?.sumOf { it.value.asDouble } ?: 0.0
        if (weight <= 0) return null

        val spreads = JsonArray()
        e.getAsJsonObject("Spreads")?.entrySet()?.sortedByDescending { it.value.asDouble }?.take(4)?.forEach { (s, v) ->
            val freq = v.asDouble / weight
            if (freq < 0.02) return@forEach
            val parts = s.split(":")
            if (parts.size != 2) return@forEach
            val o = JsonObject()
            o.addProperty("nature", parts[0].lowercase())
            o.add("evs", JsonArray().also { a -> parts[1].split("/").forEach { a.add(it.toInt()) } })
            o.addProperty("freq", round4(freq))
            spreads.add(o)
        }

        val counters = JsonObject()
        e.getAsJsonObject("Checks and Counters")?.entrySet()
            ?.mapNotNull { (other, c) ->
                val o = c.asJsonObject
                if (o["n"].asDouble < 20) return@mapNotNull null
                val score = o["p"].asDouble - 4 * o["d"].asDouble // mesma formula do Smogon
                if (score > 0.5) toId(other) to score else null
            }
            ?.sortedByDescending { it.second }?.take(12)
            ?.forEach { (k, v) -> counters.addProperty(k, round(v * 1000) / 1000) }

        val out = JsonObject()
        out.addProperty("name", name)
        out.addProperty("format", fmt)
        out.addProperty("tier", tier)
        out.addProperty("usage", round5(e["usage"].asDouble))
        out.add("moves", top(e.getAsJsonObject("Moves"), weight, 14))
        out.add("items", top(e.getAsJsonObject("Items"), weight, 5))
        out.add("abilities", top(e.getAsJsonObject("Abilities"), weight, 3))
        out.add("spreads", spreads)
        // P(parceiro no time | este Pokemon no time)
        out.add("teammates", top(e.getAsJsonObject("Teammates"), weight, 25, 0.03))
        out.add("counters", counters)
        return out
    }
}

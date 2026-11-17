package dev.thal.teambuilder.core

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Uma regra de spawn natural do Cobblemon, reduzida ao que importa para estimar a chance.
 *
 * @param formId forma que aparece ("vulpixalola")
 * @param type onde aparece: "grounded" (terra), "submerged" (dentro d'agua), "surface" (na agua), "seafloor"...
 * @param bucket raridade ("common", "uncommon", "rare", "ultra-rare") e o peso dela
 * @param biomes ids dos biomas validos; [anyBiome] quando a regra nao restringe bioma
 * @param times faixas de horario (ticks 0..23999) em que vale; vazio = qualquer hora
 * @param timeName nome da faixa ("night", "day"...), so para exibir
 */
class SpawnEntry(
    val formId: String,
    val speciesId: String,
    val type: String,
    val bucket: String,
    val bucketWeight: Double,
    val weight: Double,
    val biomes: Set<String>,
    val anyBiome: Boolean = false,
    val minLevel: Int = 1,
    val maxLevel: Int = 100,
    val times: List<IntRange> = emptyList(),
    val timeName: String = "",
) {
    fun inBiome(biome: String) = anyBiome || biome in biomes
    fun atTime(time: Int?) = time == null || times.isEmpty() || times.any { time in it }
}

/**
 * Chance de um spawn ser de certa forma. O Cobblemon sorteia primeiro a raridade (pelo peso de
 * cada balde) e depois a regra dentro dela (pelo peso da regra), entre as que valem no lugar.
 * Condicoes finas (altura, luz, ceu, estruturas) e multiplicadores ficam de fora: e uma estimativa.
 */
class SpawnTable(val entries: List<SpawnEntry>) {
    private val byForm: Map<String, List<SpawnEntry>> = entries.groupBy { it.formId }
    private val bySpecies: Map<String, List<SpawnEntry>> = entries.groupBy { it.speciesId }

    /** Por balde: peso do balde e soma dos pesos das regras que valem no lugar e na hora. */
    private class Totals(val buckets: Map<String, Pair<Double, Double>>) {
        val bucketSum = buckets.values.sumOf { it.first }
    }
    private val totals = ConcurrentHashMap<String, Totals>()

    private fun totals(type: String, biome: String, time: Int?): Totals {
        if (totals.size > 20000) totals.clear()
        // A hora so muda o resultado pelas faixas que a contem: a chave usa quais faixas valem.
        val timeKey = if (time == null) "*" else entries.asSequence()
            .filter { it.type == type && it.times.isNotEmpty() }.map { it.timeName to it.atTime(time) }.distinct()
            .filter { it.second }.map { it.first }.sorted().joinToString(",")
        return totals.getOrPut("$type|$biome|$timeKey") {
            val map = HashMap<String, Pair<Double, Double>>()
            for (e in entries) {
                if (e.type != type || !e.inBiome(biome) || !e.atTime(time)) continue
                val (bw, total) = map[e.bucket] ?: (e.bucketWeight to 0.0)
                map[e.bucket] = bw to total + e.weight
            }
            Totals(map)
        }
    }

    /** Regras da forma; sem regra propria, a forma padrao usa as da especie. */
    fun of(formId: String, speciesId: String): List<SpawnEntry> =
        byForm[formId] ?: bySpecies[speciesId]?.filter { it.formId == speciesId }.orEmpty()

    /** Chance (0..1) de um spawn de [type] em [biome], na hora [time] (null = qualquer hora), ser uma das [mine]. */
    fun chance(mine: List<SpawnEntry>, biome: String, type: String, time: Int? = null): Double {
        val valid = mine.filter { it.type == type && it.inBiome(biome) && it.atTime(time) }
        if (valid.isEmpty()) return 0.0
        val t = totals(type, biome, time)
        if (t.bucketSum <= 0) return 0.0
        val seen = Collections.newSetFromMap(IdentityHashMap<SpawnEntry, Boolean>())
        var out = 0.0
        for (e in valid) {
            if (!seen.add(e)) continue
            val (bw, total) = t.buckets[e.bucket] ?: continue
            if (total > 0) out += (bw / t.bucketSum) * e.weight / total
        }
        return out
    }

    /** Biomas onde a forma mais aparece (qualquer hora), com o tipo de lugar e a chance. */
    fun bestBiomes(mine: List<SpawnEntry>, limit: Int = 4): List<Triple<String, String, Double>> {
        val out = ArrayList<Triple<String, String, Double>>()
        for (type in mine.map { it.type }.distinct()) {
            val biomes = mine.filter { it.type == type && !it.anyBiome }.flatMapTo(HashSet()) { it.biomes }
            for (b in biomes) out += Triple(b, type, chance(mine, b, type))
        }
        return out.sortedByDescending { it.third }.distinctBy { it.first }.take(limit)
    }
}

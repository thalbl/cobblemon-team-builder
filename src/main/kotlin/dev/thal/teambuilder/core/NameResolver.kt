package dev.thal.teambuilder.core

/**
 * Transforma o que o jogador digita em ids conhecidos. Aceita maiusculas, espacos,
 * hifens, nomes de varias palavras ("Great Tusk"), formas regionais em qualquer ordem
 * ("alolan ninetales"), prefixos ("rilla") e pequenos erros de digitacao.
 */
class NameResolver(ids: Collection<String>) {
    private val ids: List<String> = ids.distinct().sorted()
    private val known: Set<String> = this.ids.toHashSet()

    data class Match(val raw: String, val id: String?)

    /** Resolve um texto com um ou mais nomes, separados por virgula ou so por espaco. */
    fun resolveAll(text: String): List<Match> {
        if (text.any { it in SEPARATORS }) {
            return text.split(*SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
                .map { Match(it, resolveOne(it)) }
        }
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<Match>()
        var i = 0
        while (i < tokens.size) {
            // Tenta primeiro o nome mais longo: "great tusk" antes de "great".
            var matched = false
            for (len in minOf(3, tokens.size - i) downTo 2) {
                val words = tokens.subList(i, i + len)
                val id = exact(words)
                if (id != null) {
                    out += Match(words.joinToString(" "), id)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                out += Match(tokens[i], resolveOne(tokens[i]))
                i++
            }
        }
        return out
    }

    /** Resolve um unico nome, do criterio mais estrito ao mais tolerante. */
    fun resolveOne(raw: String): String? {
        val words = raw.trim().split(Regex("[\\s_]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        exact(words)?.let { return it }
        val q = normalize(words.joinToString(""))
        if (q.length < 2) return null
        ids.filter { it.startsWith(q) }.minByOrNull { it.length }?.let { return it }
        if (q.length >= 4) ids.filter { q in it }.minByOrNull { it.length }?.let { return it }
        if (q.length >= 5) {
            val limit = if (q.length >= 8) 2 else 1
            ids.map { it to distance(q, it, limit) }
                .filter { it.second <= limit }
                .minByOrNull { it.second }
                ?.let { return it.first }
        }
        return null
    }

    /** Ids que comecam com o que foi digitado, para o autocomplete. */
    fun completions(partial: String, limit: Int = 40): List<String> {
        val q = normalize(partial)
        return ids.asSequence().filter { it.startsWith(q) }.take(limit).toList()
    }

    private fun exact(words: List<String>): String? {
        val parts = words.map { REGIONAL[normalize(it)] ?: normalize(it) }
        val joined = parts.joinToString("")
        if (joined in known) return joined
        // "alolan ninetales" -> "ninetalesalola"
        if (parts.size > 1) {
            val rotated = (parts.drop(1) + parts.first()).joinToString("")
            if (rotated in known) return rotated
        }
        return null
    }

    companion object {
        private val SEPARATORS = charArrayOf(',', ';', '+', '/')
        private val REGIONAL = mapOf(
            "alolan" to "alola", "galarian" to "galar", "hisuian" to "hisui", "paldean" to "paldea",
        )

        fun normalize(raw: String): String = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

        /** Distancia de edicao; devolve limit + 1 assim que passa do limite. */
        internal fun distance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    rowMin = minOf(rowMin, cur[j])
                }
                if (rowMin > limit) return limit + 1
                prev = cur
            }
            return prev[b.length]
        }
    }
}

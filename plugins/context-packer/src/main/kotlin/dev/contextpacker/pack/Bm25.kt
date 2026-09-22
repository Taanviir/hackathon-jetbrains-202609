package dev.contextpacker.pack

import kotlin.math.ln

/** Keyword ranking over full source. It's the baseline Jev has to beat, and half of the re-rank pool. */
object Bm25 {
    private val WORD = Regex("[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\\d+")

    /** Splits camelCase and snake_case, so `cachedContentTokenCount` matches "token count". */
    fun tokens(text: String): List<String> =
        WORD.findAll(text).map { it.value.lowercase() }.filter { it.length > 1 }.toList()

    fun rank(query: String, docs: Map<String, String>, k1: Double = 1.2, b: Double = 0.75): List<String> {
        if (docs.isEmpty()) return emptyList()
        val counts = docs.mapValues { (path, text) -> tokens("$path $text").groupingBy { it }.eachCount() }
        val lengths = counts.mapValues { it.value.values.sum() }
        val avg = lengths.values.average()
        val df = HashMap<String, Int>()
        counts.values.forEach { c -> c.keys.forEach { df.merge(it, 1, Int::plus) } }
        val n = docs.size
        val terms = tokens(query).toSet()
        val scores = counts.mapValues { (path, c) ->
            val len = lengths.getValue(path)
            terms.sumOf { t ->
                val tf = c[t] ?: return@sumOf 0.0
                val d = df.getValue(t)
                ln(1 + (n - d + 0.5) / (d + 0.5)) * tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avg))
            }
        }
        return docs.keys.sortedWith(compareByDescending<String> { scores.getValue(it) }.thenBy { it })
    }
}

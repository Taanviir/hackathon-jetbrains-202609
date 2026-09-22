package dev.contextpacker.pack

import org.junit.Assert.assertEquals
import org.junit.Test

class Bm25Test {
    @Test
    fun `splits camelCase so identifiers match plain words`() {
        assertEquals(listOf("cached", "content", "token", "count"), Bm25.tokens("cachedContentTokenCount"))
        assertEquals(listOf("http", "client", "retry", "after", "ms"), Bm25.tokens("HTTPClient_retry-after-ms"))
    }

    @Test
    fun `ranks the file that mentions the task's terms first`() {
        val docs = mapOf(
            "src/Colors.kt" to "object Colors { val red = 1 }",
            "src/RetryPolicy.kt" to "class RetryPolicy { fun nextDelay(attempt: Int) = backoff(attempt) }",
            "src/Http.kt" to "class Http { fun send() = retry { } }",
        )
        assertEquals("src/RetryPolicy.kt", Bm25.rank("add exponential backoff to retry policy", docs).first())
    }

    @Test
    fun `empty corpus ranks nothing`() {
        assertEquals(emptyList<String>(), Bm25.rank("anything", emptyMap()))
    }

    @Test
    fun `tied results are reproducible regardless of project traversal order`() {
        val docs = linkedMapOf("Z.kt" to "same content", "A.kt" to "same content")
        assertEquals(listOf("A.kt", "Z.kt"), Bm25.rank("unmatched", docs))
        assertEquals(Bm25.rank("unmatched", docs), Bm25.rank("unmatched", docs.entries.reversed().associate { it.toPair() }))
    }
}

package dev.contextpacker.laya

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in wire/inference smoke, not a retrieval quality benchmark. Never contacts a cloud model. */
class LayaLiveTest {
    @Test
    fun `Kotlin adapter scores two files using the running local model`() = runBlocking {
        assumeTrue("Set LAYA_SMOKE=1 after starting the local server", System.getenv("LAYA_SMOKE") == "1")
        val scorer = LayaRelevance()
        val scores = scorer.score("Add a retry delay after HTTP failures", listOf(
            "Retry.kt" to "class Retry { fun afterFailure() { Thread.sleep(1000) } }",
            "Color.kt" to "enum class Color { RED, GREEN, BLUE }",
        ))
        assertEquals(setOf("Retry.kt", "Color.kt"), scores.keys)
        assertTrue(scores.values.all { it.isFinite() && it in 0.0..1.0 })
        assertEquals(2, scorer.calls.size)
        assertTrue(scorer.calls.all { it.error == null && it.usageKnown && it.inputTokens > 0 })
        println("Laya live wire smoke: model=${scorer.model}, scores=$scores, tokens=${scorer.calls.sumOf { it.inputTokens }}")
    }
}

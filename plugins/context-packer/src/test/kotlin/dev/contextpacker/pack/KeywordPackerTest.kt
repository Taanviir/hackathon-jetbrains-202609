package dev.contextpacker.pack

import dev.contextpacker.DecisionProvider
import dev.contextpacker.keywordReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class KeywordPackerTest {
    @Test
    fun `ranks the full corpus and returns ordinal ranks without model scores`() {
        val noise = (1..80).associate { "src/Noise$it.kt" to "class Noise$it { fun calculateBalance() = 0 }" }
        val docs = noise + ("src/RetryTimeoutBudget.kt" to "class RetryTimeoutBudget { fun backoffRetry() = 1 }") +
            ("src/test/RetryTimeoutBudgetTest.kt" to "test retry timeout budget")

        val result = KeywordPacker.pack("retry timeout budget", docs)

        assertEquals(82, result.candidates)
        assertEquals("src/RetryTimeoutBudget.kt", result.files.first().path)
        assertEquals((1..20).toList(), result.files.map { it.bm25Rank })
        assertEquals(20, result.files.size)
        assertTrue(result.files.all { it.relevance == 0.0 && it.score == 0.0 })
        assertTrue(result.files.first { it.path.endsWith("RetryTimeoutBudgetTest.kt") }.isTest)
        assertEquals(0, result.failedBatches)
        assertEquals(0, result.pass2Ms)
    }

    @Test
    fun `keyword report has no model requests tokens or API fee`() {
        val result = KeywordPacker.pack("retry", mapOf("src/Retry.kt" to "fun retry() {}"))
        val report = keywordReport(result, "an agent over MCP", collectMs = 5)

        assertEquals(DecisionProvider.KEYWORDS, report.provider)
        assertEquals(1, report.scoredCandidates)
        assertEquals(0, report.jevCalls)
        assertEquals(0L, report.inputTokens)
        assertEquals(0, report.failedCalls)
        assertTrue(report.usageKnown)
        assertEquals(0.0, report.costUsd!!, 0.0)
        assertEquals(5 + result.totalMs, report.totalMs)
    }

    @Test
    fun `cancellation stops full corpus ranking`() {
        val docs = (1..100).associate { "src/File$it.kt" to "fun retry$it() {}" }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            KeywordPacker.pack("retry", docs, checkCancelled = {
                if (++checks > 4) throw CancellationException("cancelled")
            })
        }
        assertTrue(checks in 5..10)
    }
}

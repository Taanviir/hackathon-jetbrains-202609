package dev.contextpacker.pack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PackerTest {
    private val docs = (0 until 250).map { i -> FileDoc("src/F$i.kt", "sketch $i", "text of file $i") } +
        FileDoc("src/RetryPolicy.kt", "sketch retry", "class RetryPolicy { fun backoff() }") +
        FileDoc("src/jvmTest/RetryPolicyTest.kt", "sketch retry test", "class RetryPolicyTest { backoff }")

    /** Pretends to be Jev: only the retry files are relevant, and it records every batch it gets. */
    private class FakeScorer(val failOn: (List<Pair<String, String>>) -> Boolean = { false }) : RelevanceScorer {
        val batches = CopyOnWriteArrayList<List<Pair<String, String>>>()
        override suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double> {
            batches += items
            if (failOn(items)) error("boom")
            return items.associate { (path, _) -> path to if ("Retry" in path) 0.9 else 0.01 }
        }
    }

    @Test
    fun `pass 1 batches sketches, pass 2 re-reads only the pool in full`() = runBlocking {
        val scorer = FakeScorer()
        val result = Packer(scorer, PackConfig(batch = 100, pool = 10, perCall = 6)).pack("add backoff to retry", docs)

        val pass1 = scorer.batches.filter { batch -> batch.all { it.second.startsWith("sketch") } }
        val pass2 = scorer.batches - pass1.toSet()
        assertEquals(listOf(100, 100, 52), pass1.map { it.size }.sortedDescending())  // batch set explicitly
        assertTrue("pass 2 sends full text", pass2.flatten().all { it.second.startsWith("path: ") })
        assertTrue("pass 2 batches are small", pass2.all { it.size <= 6 })
        assertTrue("pool is BM25 top 10 plus sketch top 10, deduplicated", pass2.flatten().size in 10..20)
        assertEquals(252, result.candidates)
        assertEquals(0, result.failedBatches)
    }

    @Test
    fun `relevant files come first and tests are flagged`() = runBlocking {
        val result = Packer(FakeScorer(), PackConfig(pool = 10)).pack("add backoff to retry", docs)
        val top = result.files.take(2).map { it.path }.toSet()
        assertEquals(setOf("src/RetryPolicy.kt", "src/jvmTest/RetryPolicyTest.kt"), top)
        assertTrue(result.files.first { it.path.endsWith("RetryPolicyTest.kt") }.isTest)
        assertFalse(result.files.first { it.path == "src/RetryPolicy.kt" }.isTest)
    }

    @Test
    fun `a failed batch costs its files, not the whole pack`() = runBlocking {
        val scorer = FakeScorer(failOn = { batch -> batch.any { it.first == "src/F0.kt" && it.second.startsWith("sketch") } })
        val result = Packer(scorer, PackConfig(pool = 10)).pack("add backoff to retry", docs)
        assertEquals(1, result.failedBatches)
        assertEquals("src/RetryPolicy.kt", result.files.first().path)
    }

    @Test
    fun `when Jev answers nothing at all, the pack fails instead of passing off BM25 as Jev`() = runBlocking {
        try {
            Packer(FakeScorer(failOn = { true }), PackConfig(pool = 10)).pack("add backoff to retry", docs)
            org.junit.Assert.fail("expected the pack to fail")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `missing or unexpected scorer paths count as failed batches`() = runBlocking {
        val candidates = listOf(FileDoc("src/A.kt", "sketch A", "full A"), FileDoc("src/B.kt", "sketch B", "full B"))
        for (badResponse in listOf(emptyMap(), mapOf("src/Unknown.kt" to 0.9))) {
            val scorer = RelevanceScorer { _, items ->
                if (items.single().second == "sketch A") badResponse
                else items.associate { it.first to 0.9 }
            }
            val result = Packer(scorer, PackConfig(batch = 1, pool = 2, perCall = 1)).pack("change A", candidates)
            assertEquals(1, result.failedBatches)
            assertEquals(setOf("src/A.kt", "src/B.kt"), result.files.map { it.path }.toSet())
        }
    }

    @Test
    fun `all incomplete scorer responses fail the pack`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                Packer(RelevanceScorer { _, _ -> emptyMap() }, PackConfig(batch = 1))
                    .pack("change A", listOf(FileDoc("src/A.kt", "sketch A", "full A")))
            }
        }
        assertTrue(error.message.orEmpty().contains("exactly the requested paths"))
    }

    @Test
    fun `invalid probabilities count as failed batches`() = runBlocking {
        val candidates = listOf(FileDoc("src/A.kt", "sketch A", "full A"), FileDoc("src/B.kt", "sketch B", "full B"))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            val scorer = RelevanceScorer { _, items ->
                items.associate { (path, text) -> path to if (path == "src/A.kt" && text == "sketch A") invalid else 0.9 }
            }
            val result = Packer(scorer, PackConfig(batch = 1, pool = 2, perCall = 1)).pack("change A", candidates)
            assertEquals("score $invalid", 1, result.failedBatches)
            assertTrue(result.files.all { it.relevance in 0.0..1.0 && it.score.isFinite() })
        }
    }

    @Test
    fun `a failed full source batch has zero relevance and is reported`() = runBlocking {
        val candidates = listOf(FileDoc("src/A.kt", "sketch A", "full A"), FileDoc("src/B.kt", "sketch B", "full B"))
        val scorer = RelevanceScorer { _, items ->
            if (items.single().first == "src/A.kt" && items.single().second.startsWith("path: ")) error("boom")
            items.associate { it.first to 0.9 }
        }
        val result = Packer(scorer, PackConfig(batch = 1, pool = 2, perCall = 1)).pack("change A", candidates)
        assertEquals(1, result.failedBatches)
        assertEquals(0.0, result.files.first { it.path == "src/A.kt" }.relevance, 0.0)
        assertEquals(0.9, result.files.first { it.path == "src/B.kt" }.relevance, 0.0)
    }

    @Test
    fun `cancellation propagates even when other batches can score`() {
        val candidates = listOf(FileDoc("src/A.kt", "sketch A", "full A"), FileDoc("src/B.kt", "sketch B", "full B"))
        val scorer = RelevanceScorer { _, items ->
            if (items.single().first == "src/A.kt") throw CancellationException("cancelled")
            items.associate { it.first to 0.9 }
        }
        val error = assertThrows(CancellationException::class.java) {
            runBlocking { Packer(scorer, PackConfig(batch = 1)).pack("change A", candidates) }
        }
        assertEquals("cancelled", error.message)
    }

    @Test
    fun `invalid config and inputs fail before scoring`() {
        val scorer = FakeScorer()
        val invalidConfigs = listOf(
            PackConfig(batch = 0), PackConfig(pool = 0), PackConfig(perCall = 0),
            PackConfig(fullChars = 0), PackConfig(keep = 0),
            PackConfig(bm25Weight = -0.1), PackConfig(bm25Weight = Double.NaN),
            PackConfig(bm25Weight = Double.POSITIVE_INFINITY),
        )
        invalidConfigs.forEach { config ->
            assertThrows(IllegalArgumentException::class.java) { Packer(scorer, config) }
        }
        val packer = Packer(scorer)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { packer.pack("  ", docs) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { packer.pack("change A", listOf(FileDoc(" ", "sketch", "full"))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { packer.pack("change A", listOf(docs[0], docs[0])) }
        }
        assertTrue(scorer.batches.isEmpty())
    }

    @Test
    fun `test paths are recognised across layouts`() {
        listOf("a/src/test/kotlin/X.kt", "a/src/jvmTest/kotlin/X.kt", "a/integration-tests/X.kt", "a/src/FooTest.kt", "a/FooSpec.kt")
            .forEach { assertTrue(it, Packer.isTest(it)) }
        listOf("a/src/main/kotlin/Retry.kt", "a/src/commonMain/kotlin/Latest.kt").forEach { assertFalse(it, Packer.isTest(it)) }
    }
}

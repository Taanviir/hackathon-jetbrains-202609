package dev.contextpacker.pack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf(100, 100, 52), pass1.map { it.size }.sortedDescending())
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
    fun `test paths are recognised across layouts`() {
        listOf("a/src/test/kotlin/X.kt", "a/src/jvmTest/kotlin/X.kt", "a/integration-tests/X.kt", "a/src/FooTest.kt", "a/FooSpec.kt")
            .forEach { assertTrue(it, Packer.isTest(it)) }
        listOf("a/src/main/kotlin/Retry.kt", "a/src/commonMain/kotlin/Latest.kt").forEach { assertFalse(it, Packer.isTest(it)) }
    }
}

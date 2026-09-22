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
    fun `stage 3 reorders only the top files by the comparative choice`() = runBlocking {
        val prefersTest = ChoiceScorer { _, items -> items.associate { (p, _) -> p to if (p.endsWith("Test.kt")) 0.95 else 0.05 / items.size } }
        val without = Packer(FakeScorer(), PackConfig(pool = 10)).pack("add backoff to retry", docs)
        val with = Packer(FakeScorer(), PackConfig(pool = 10), chooser = prefersTest).pack("add backoff to retry", docs)
        assertEquals("src/RetryPolicy.kt", without.files.first().path)
        assertEquals("src/jvmTest/RetryPolicyTest.kt", with.files.first().path)
        assertEquals(without.files.map { it.path }.toSet(), with.files.map { it.path }.toSet())
    }

    @Test
    fun `a failed stage 3 keeps the order and counts as one failed batch`() = runBlocking {
        val broken = ChoiceScorer { _, _ -> error("choice down") }
        val without = Packer(FakeScorer(), PackConfig(pool = 10)).pack("add backoff to retry", docs)
        val with = Packer(FakeScorer(), PackConfig(pool = 10), chooser = broken).pack("add backoff to retry", docs)
        assertEquals(without.files.map { it.path }, with.files.map { it.path })
        assertEquals(1, with.failedBatches)
    }

    @Test
    fun `confident roles label the top files, unsure or unrelated ones stay unlabelled`() = runBlocking {
        val roler = RoleScorer { _, items ->
            items.associate { (p, _) ->
                p to when {
                    p.endsWith("RetryPolicy.kt") -> mapOf("edit" to 0.9, "unrelated" to 0.1)
                    p.endsWith("Test.kt") -> mapOf("test" to 0.45, "edit" to 0.4, "unrelated" to 0.15)
                    else -> mapOf("unrelated" to 0.95, "edit" to 0.05)
                }
            }
        }
        val result = Packer(FakeScorer(), PackConfig(pool = 10), roler = roler).pack("add backoff to retry", docs)
        assertEquals("edit", result.files.first { it.path == "src/RetryPolicy.kt" }.role)
        assertEquals(null, result.files.first { it.path.endsWith("RetryPolicyTest.kt") }.role)  // 0.45 < 0.5
        assertTrue(result.files.filter { it.path.startsWith("src/F") }.all { it.role == null })
    }

    @Test
    fun `preview scores only BM25's shortlist, in one full-source pass`() = runBlocking {
        val scorer = FakeScorer()
        val result = Packer(scorer, PackConfig(previewPool = 30)).preview("add backoff to retry", docs)
        assertTrue(result.preview)
        assertTrue("no sketches in a preview", scorer.batches.flatten().none { it.second.startsWith("sketch") })
        assertEquals(30, scorer.batches.flatten().size)
        assertEquals("src/RetryPolicy.kt", result.files.first().path)
    }

    @Test
    fun `test paths are recognised across layouts`() {
        listOf("a/src/test/kotlin/X.kt", "a/src/jvmTest/kotlin/X.kt", "a/integration-tests/X.kt", "a/src/FooTest.kt", "a/FooSpec.kt")
            .forEach { assertTrue(it, Packer.isTest(it)) }
        listOf("a/src/main/kotlin/Retry.kt", "a/src/commonMain/kotlin/Latest.kt").forEach { assertFalse(it, Packer.isTest(it)) }
    }
}

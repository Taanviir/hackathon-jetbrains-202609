package dev.contextpacker.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One candidate file: a short structural sketch for the wide pass, full text for the narrow one. */
data class FileDoc(val path: String, val sketch: String, val text: String)

data class PackConfig(
    /** Sketches per Jev call in pass 1. 100 fits the ~32k-token state limit; 150 doesn't. */
    val batch: Int = 100,
    /** How many of BM25's and pass 1's top files each go into the re-rank pool. */
    val pool: Int = 60,
    /** Full-source files per Jev call in pass 2. */
    val perCall: Int = 6,
    val fullChars: Int = 6_000,
    /** Weight of BM25 position as a tie-breaker when Jev scores files alike. */
    val bm25Weight: Double = 0.15,
    val keep: Int = 20,
)

data class PackedFile(
    val path: String,
    /** Jev's pass-2 P(relevant), on full source. */
    val relevance: Double,
    /** What the list is sorted by: relevance plus the BM25 tie-break. */
    val rankScore: Double,
    val bm25Rank: Int?,
    val isTest: Boolean,
)

data class PackResult(
    val task: String,
    val files: List<PackedFile>,
    val candidates: Int,
    val pass1Ms: Long,
    val pass2Ms: Long,
    val totalMs: Long,
    val failedBatches: Int,
)

/** Scores a batch of (path, text) pairs against a task in a single call. */
fun interface RelevanceScorer {
    suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/**
 * The pipeline measured in spike/RESULTS.md. Jev alone on sketches loses to keyword search, but
 * as a re-ranker over a pooled shortlist, reading full source, it beats it by ~30% recall@10.
 */
class Packer(private val scorer: RelevanceScorer, private val config: PackConfig = PackConfig()) {

    suspend fun pack(task: String, docs: List<FileDoc>, onProgress: (String) -> Unit = {}): PackResult = coroutineScope {
        val started = System.nanoTime()
        onProgress("Scoring ${docs.size} files")
        val bm25 = async(Dispatchers.Default) { Bm25.rank(task, docs.associate { it.path to it.text }) }
        val pass1 = scoreAll(task, docs.map { it.path to it.sketch }, config.batch)
        val bm25Ranked = bm25.await()
        val pass1Done = System.nanoTime()

        val byPath = docs.associateBy { it.path }
        val bySketch = pass1.scores.keys.sortedByDescending { pass1.scores.getValue(it) }
        val pool = (bm25Ranked.take(config.pool) + bySketch.take(config.pool)).distinct()
        onProgress("Reading ${pool.size} shortlisted files in full")
        val pass2 = scoreAll(task, pool.map { it to "path: $it\n${byPath.getValue(it).text.take(config.fullChars)}" }, config.perCall)
        val done = System.nanoTime()

        val bm25Pos = bm25Ranked.withIndex().associate { it.value to it.index }
        val files = pool.map { path ->
            val relevance = pass2.scores[path] ?: 0.0
            val pos = bm25Pos[path]
            PackedFile(
                path = path,
                relevance = relevance,
                rankScore = relevance + config.bm25Weight / (1 + (pos ?: 999) / 10.0),
                bm25Rank = pos?.plus(1),
                isTest = isTest(path),
            )
        }.sortedByDescending { it.rankScore }.take(config.keep)

        PackResult(
            task = task,
            files = files,
            candidates = docs.size,
            pass1Ms = (pass1Done - started) / 1_000_000,
            pass2Ms = (done - pass1Done) / 1_000_000,
            totalMs = (done - started) / 1_000_000,
            failedBatches = pass1.failed + pass2.failed,
        )
    }

    private class Scores(val scores: Map<String, Double>, val failed: Int)

    /** One failed batch costs its files a score of zero, not the whole pack. */
    private suspend fun scoreAll(task: String, items: List<Pair<String, String>>, groupSize: Int): Scores = coroutineScope {
        val parts = items.chunked(groupSize).map { group ->
            async { runCatching { scorer.score(task, group) } }
        }.awaitAll()
        val scores = HashMap<String, Double>()
        parts.forEach { part -> part.getOrNull()?.let(scores::putAll) }
        items.forEach { (path, _) -> scores.putIfAbsent(path, 0.0) }
        Scores(scores, parts.count { it.isFailure })
    }

    companion object {
        private val TEST_DIR = Regex("(^|/)[\\w-]*[tT]est[\\w-]*/")
        private val TEST_FILE = Regex("(Test|Tests|Spec|IT)\\.[A-Za-z]+$")

        fun isTest(path: String) = TEST_DIR.containsMatchIn(path) || TEST_FILE.containsMatchIn(path)
    }
}

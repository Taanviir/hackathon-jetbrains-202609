package dev.contextpacker.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException

/** One candidate file: a short structural sketch for the wide pass, full text for the narrow one. */
data class FileDoc(val path: String, val sketch: String, val text: String)

data class PackConfig(
    /** Sketches per Jev call in pass 1, as measured. 100 also fits the ~32k-token state limit; 150 doesn't. */
    val batch: Int = 60,
    /** How many of BM25's and pass 1's top files each go into the re-rank pool. */
    val pool: Int = 60,
    /** Full-source files per Jev call in pass 2. */
    val perCall: Int = 6,
    val fullChars: Int = 6_000,
    /** Weight of BM25 position against Jev's score. 1.0 was chosen on the dev split; see spike/RESULTS.md. */
    val bm25Weight: Double = 1.0,
    val keep: Int = 20,
)

data class PackedFile(
    val path: String,
    /** Jev's pass-2 P(relevant), on full source. */
    val relevance: Double,
    /** What the list is sorted by: relevance plus the BM25 tie-break, scaled back to 0..1. */
    val score: Double,
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

/** Scores a batch in one call. Return exactly one finite probability in [0, 1] per requested path. */
fun interface RelevanceScorer {
    suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/**
 * The pipeline measured in spike/RESULTS.md. Jev alone on sketches loses to keyword search, but as a
 * re-ranker over a pooled shortlist, reading full source and fused with BM25, it lifts recall@10 on
 * held-out tasks from 0.53 to 0.69.
 */
class Packer(private val scorer: RelevanceScorer, private val config: PackConfig = PackConfig()) {

    init {
        require(config.batch > 0 && config.pool > 0 && config.perCall > 0 && config.fullChars > 0 && config.keep > 0) {
            "Batch, pool, perCall, fullChars, and keep must be positive"
        }
        require(config.bm25Weight.isFinite() && config.bm25Weight >= 0.0) {
            "bm25Weight must be finite and non-negative"
        }
    }

    suspend fun pack(task: String, docs: List<FileDoc>, onProgress: (String) -> Unit = {}): PackResult = coroutineScope {
        require(task.isNotBlank()) { "Task must not be blank" }
        require(docs.all { it.path.isNotBlank() }) { "File paths must not be blank" }
        require(docs.map { it.path }.toSet().size == docs.size) { "File paths must be unique" }
        val started = System.nanoTime()
        onProgress("Scoring ${docs.size} files")
        val bm25 = async(Dispatchers.Default) { Bm25.rank(task, docs.associate { it.path to it.text }) }
        val pass1 = scoreAll(task, docs.map { it.path to it.sketch }, config.batch)
        val bm25Ranked = bm25.await()
        val pass1Done = System.nanoTime()

        val byPath = docs.associateBy { it.path }
        val bySketch = pass1.scores.keys.sortedWith(compareByDescending<String> { pass1.scores.getValue(it) }.thenBy { it })
        val pool = (bm25Ranked.take(config.pool) + bySketch.take(config.pool)).distinct()
        onProgress("Scoring source excerpts from ${pool.size} shortlisted files")
        val pass2 = scoreAll(task, pool.map { it to "path: $it\n${byPath.getValue(it).text.take(config.fullChars)}" }, config.perCall)
        val done = System.nanoTime()

        val bm25Pos = bm25Ranked.withIndex().associate { it.value to it.index }
        val files = pool.map { path ->
            val relevance = pass2.scores[path] ?: 0.0
            val pos = bm25Pos[path]
            PackedFile(
                path = path,
                relevance = relevance,
                score = (relevance + config.bm25Weight / (1 + (pos ?: 999) / 10.0)) / (1 + config.bm25Weight),
                bm25Rank = pos?.plus(1),
                isTest = isTest(path),
            )
        }.sortedWith(compareByDescending<PackedFile> { it.score }.thenBy { it.path }).take(config.keep)

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

    /**
     * One failed batch costs its files a score of zero, not the whole pack. If every batch fails,
     * Jev isn't answering at all, and quietly returning a keyword-only ranking would be a lie.
     */
    private suspend fun scoreAll(task: String, items: List<Pair<String, String>>, groupSize: Int): Scores = coroutineScope {
        val parts = items.chunked(groupSize).map { group ->
            async {
                try {
                    val response = scorer.score(task, group)
                    val expected = group.mapTo(HashSet()) { it.first }
                    require(response.keys == expected) { "Scorer must return exactly the requested paths" }
                    require(response.values.all { it.isFinite() && it in 0.0..1.0 }) {
                        "Scorer probabilities must be finite and within [0, 1]"
                    }
                    Result.success(response)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        if (parts.isNotEmpty() && parts.all { it.isFailure }) throw parts.first().exceptionOrNull()!!
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

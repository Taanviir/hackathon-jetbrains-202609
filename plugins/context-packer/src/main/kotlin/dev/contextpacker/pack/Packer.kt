package dev.contextpacker.pack

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

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
    /** Stage 3: how many of the top files one comparative Jev `choice` reorders, and how much its probability counts. */
    val stage3K: Int = 10,
    val stage3Weight: Double = 2.0,
    /** Start BM25's full-source pass while sketch scoring is still running. Laya uses sequential passes. */
    val overlapPasses: Boolean = true,
)

data class PackedFile(
    val path: String,
    /** Model pass-2 P(relevant); unused (0.0) for keyword-only ranking. */
    val relevance: Double,
    /** Model-mode fused score in 0..1; unused (0.0) for keyword-only ranking. */
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
    val stage3Ms: Long = 0,
)

/** Scores a batch in one call. Return exactly one finite probability in [0, 1] per requested path. */
fun interface RelevanceScorer {
    suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/** A provider-wide failure: cancel the pack rather than retrying the same outage for every file. */
open class ScorerUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Picks among a batch of files in a single call, so the scores are relative: a comparison, not a verdict each. */
fun interface ChoiceScorer {
    suspend fun choose(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/**
 * The pipeline measured in spike/RESULTS.md. Jev alone on sketches loses to keyword search, but as a
 * re-ranker over a pooled shortlist, reading full source and fused with BM25, it lifts recall@10 on
 * held-out tasks from 0.53 to 0.69.
 */
class Packer(
    private val scorer: RelevanceScorer,
    private val config: PackConfig = PackConfig(),
    private val chooser: ChoiceScorer? = null,
) {

    init {
        require(config.batch > 0 && config.pool > 0 && config.perCall > 0 && config.fullChars > 0 && config.keep > 0) {
            "Batch, pool, perCall, fullChars, and keep must be positive"
        }
        require(config.bm25Weight.isFinite() && config.bm25Weight >= 0.0) {
            "bm25Weight must be finite and non-negative"
        }
        require(config.stage3K > 0) { "stage3K must be positive" }
        require(config.stage3Weight.isFinite() && config.stage3Weight >= 0.0) {
            "stage3Weight must be finite and non-negative"
        }
        require((1.0 + config.bm25Weight + config.stage3Weight).isFinite()) {
            "Combined ranking weights must stay finite"
        }
    }

    suspend fun pack(task: String, docs: List<FileDoc>, onProgress: (String) -> Unit = {}): PackResult = coroutineScope {
        require(task.isNotBlank()) { "Task must not be blank" }
        require(docs.all { it.path.isNotBlank() }) { "File paths must not be blank" }
        require(docs.map { it.path }.toSet().size == docs.size) { "File paths must be unique" }
        val started = System.nanoTime()
        onProgress("Scoring ${docs.size} files")
        val byPath = docs.associateBy { it.path }
        fun full(paths: List<String>) = paths.map { it to "path: $it\n${byPath.getValue(it).text.take(config.fullChars)}" }

        // BM25 is local and can run while pass 1 scores sketches.
        val pass1Job = async { scoreAll(task, docs.map { it.path to it.sketch }, config.batch) }
        val bm25Ranked = async(Dispatchers.Default) {
            Bm25.rank(task, docs.associate { it.path to it.text }, checkCancelled = { ensureActive() })
        }.await()
        val bm25Pool = bm25Ranked.take(config.pool)
        val pass2a = if (config.overlapPasses) async { scoreAll(task, full(bm25Pool), config.perCall, failIfAll = false) } else null
        val pass1 = pass1Job.await()
        val pass1Done = System.nanoTime()

        val bySketch = pass1.scores.keys.sortedWith(compareByDescending<String> { pass1.scores.getValue(it) }.thenBy { it })
        val extra = bySketch.take(config.pool).filterNot { it in bm25Pool.toSet() }
        val pool = bm25Pool + extra
        onProgress("Scoring source excerpts from ${pool.size} shortlisted files")
        val pass2 = if (config.overlapPasses) {
            val pass2b = if (extra.isEmpty()) Scores(emptyMap(), 0) else scoreAll(task, full(extra), config.perCall, failIfAll = false)
            val pass2aDone = pass2a!!.await()
            val combined = Scores(
                pass2aDone.scores + pass2b.scores,
                pass2aDone.failed + pass2b.failed,
                pass2aDone.batches + pass2b.batches,
                pass2aDone.firstFailure ?: pass2b.firstFailure,
            )
            if (combined.batches > 0 && combined.failed == combined.batches) throw combined.firstFailure!!
            combined
        } else {
            // Keep every Laya scoring call after pass 1, in the original combined-pool order.
            scoreAll(task, full(pool), config.perCall)
        }
        val relevance = pass2.scores
        val pass2Done = System.nanoTime()

        val bm25Pos = bm25Ranked.withIndex().associate { it.value to it.index }
        val fused = pool.associateWith { path ->
            (relevance[path] ?: 0.0) + config.bm25Weight / (1 + (bm25Pos[path] ?: 999) / 10.0)
        }
        val ranked = pool.sortedWith(compareByDescending<String> { fused.getValue(it) }.thenBy { it })

        // Stage 3: one comparative choice over the top K reorders them. If it fails, the order above stands.
        val top = ranked.take(config.stage3K)
        var choiceFailed = false
        val choice = chooser?.takeIf { top.size >= 2 }?.let { c ->
            onProgress("Comparing the top ${top.size}")
            try {
                c.choose(task, full(top)).also { result ->
                    require(result.keys == top.toSet()) { "Chooser must return exactly the requested paths" }
                    require(result.values.all { it.isFinite() && it in 0.0..1.0 }) {
                        "Chooser probabilities must be finite and within [0, 1]"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: ScorerUnavailableException) {
                throw e
            } catch (e: Exception) {
                choiceFailed = true
                null
            }
        }
        val done = System.nanoTime()

        val scale = 1 + config.bm25Weight + if (choice == null) 0.0 else config.stage3Weight
        val files = pool.map { path ->
            val pos = bm25Pos[path]
            PackedFile(
                path = path,
                relevance = relevance[path] ?: 0.0,
                score = (fused.getValue(path) + config.stage3Weight * (choice?.get(path) ?: 0.0)) / scale,
                bm25Rank = pos?.plus(1),
                isTest = isTest(path),
            )
        }.sortedWith(compareByDescending<PackedFile> { it.score }.thenBy { it.path }).take(config.keep)

        PackResult(
            task = task,
            files = files,
            candidates = docs.size,
            pass1Ms = (pass1Done - started) / 1_000_000,
            pass2Ms = (pass2Done - pass1Done) / 1_000_000,
            stage3Ms = (done - pass2Done) / 1_000_000,
            totalMs = (done - started) / 1_000_000,
            failedBatches = pass1.failed + pass2.failed + if (choiceFailed) 1 else 0,
        )
    }

    private class Scores(
        val scores: Map<String, Double>,
        val failed: Int,
        val batches: Int = 0,
        val firstFailure: Throwable? = null,
    )

    /**
     * One failed batch costs its files a score of zero, not the whole pack. If every batch fails,
     * Jev isn't answering at all, and quietly returning a keyword-only ranking would be a lie.
     */
    private suspend fun scoreAll(
        task: String,
        items: List<Pair<String, String>>,
        groupSize: Int,
        failIfAll: Boolean = true,
    ): Scores = coroutineScope {
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
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: ScorerUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        val firstFailure = parts.firstOrNull { it.isFailure }?.exceptionOrNull()
        if (failIfAll && parts.isNotEmpty() && parts.all { it.isFailure }) throw firstFailure!!
        val scores = HashMap<String, Double>()
        parts.forEach { part -> part.getOrNull()?.let(scores::putAll) }
        items.forEach { (path, _) -> scores.putIfAbsent(path, 0.0) }
        Scores(scores, parts.count { it.isFailure }, parts.size, firstFailure)
    }

    companion object {
        private val TEST_DIR = Regex("(^|/)[\\w-]*[tT]est[\\w-]*/")
        private val TEST_FILE = Regex("(Test|Tests|Spec|IT)\\.[A-Za-z]+$")

        fun isTest(path: String) = TEST_DIR.containsMatchIn(path) || TEST_FILE.containsMatchIn(path)
    }
}

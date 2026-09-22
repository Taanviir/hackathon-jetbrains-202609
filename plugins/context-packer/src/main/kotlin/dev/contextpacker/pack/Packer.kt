package dev.contextpacker.pack

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    /** Stage 3: how many of the top files one comparative Jev `choice` reorders, and how much its probability counts. */
    val stage3K: Int = 10,
    val stage3Weight: Double = 2.0,
    /** Search-as-you-type scores only BM25's top files, in one pass. */
    val previewPool: Int = 30,
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
    /** Jev's label for why the file is here (edit, test, example, dependency), when it's confident. */
    val role: String? = null,
    val roleConfidence: Double? = null,
)

data class PackResult(
    val task: String,
    val files: List<PackedFile>,
    val candidates: Int,
    val pass1Ms: Long,
    val pass2Ms: Long,
    val stage3Ms: Long,
    val totalMs: Long,
    val failedBatches: Int,
    /** A quick lite-mode ranking while the task is still being typed, not the measured pipeline. */
    val preview: Boolean = false,
)

/** Scores a batch of (path, text) pairs against a task in a single call, each file on its own. */
fun interface RelevanceScorer {
    suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/** Picks among a batch of files in a single call, so the scores are relative: a comparison, not a verdict each. */
fun interface ChoiceScorer {
    suspend fun choose(task: String, items: List<Pair<String, String>>): Map<String, Double>
}

/** Labels each file with the role it plays in the task, as a probability per role. */
fun interface RoleScorer {
    suspend fun roles(task: String, items: List<Pair<String, String>>): Map<String, Map<String, Double>>
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
    private val roler: RoleScorer? = null,
) {

    suspend fun pack(task: String, docs: List<FileDoc>, onProgress: (String) -> Unit = {}): PackResult = coroutineScope {
        val started = System.nanoTime()
        onProgress("Scoring ${docs.size} files")
        val byPath = docs.associateBy { it.path }
        fun full(paths: List<String>) = paths.map { it to "path: $it\n${byPath.getValue(it).text.take(config.fullChars)}" }

        // BM25 is instant, so its half of the pool goes to pass 2 while pass 1 is still reading sketches.
        // Only a total pass-1 failure means Jev isn't answering; later passes degrade instead of failing the pack.
        val pass1Job = async { scoreAll(task, docs.map { it.path to it.sketch }, config.batch, throwIfAllFail = true) }
        val bm25Ranked = async(Dispatchers.Default) { Bm25.rank(task, docs.associate { it.path to it.text }) }.await()
        val bm25Pool = bm25Ranked.take(config.pool)
        val pass2a = async { scoreAll(task, full(bm25Pool), config.perCall) }
        val pass1 = pass1Job.await()
        val pass1Done = System.nanoTime()

        val bySketch = pass1.scores.keys.sortedByDescending { pass1.scores.getValue(it) }
        val inBm25Pool = bm25Pool.toSet()
        val extra = bySketch.take(config.pool).filterNot { it in inBm25Pool }
        val pool = bm25Pool + extra
        onProgress("Reading ${pool.size} shortlisted files in full")
        val pass2b = if (extra.isEmpty()) Scores(emptyMap(), 0) else scoreAll(task, full(extra), config.perCall)
        val pass2 = pass2a.await()
        val relevance = pass2.scores + pass2b.scores
        val pass2Done = System.nanoTime()

        val bm25Pos = bm25Ranked.withIndex().associate { it.value to it.index }
        val fused = pool.associateWith { path ->
            (relevance[path] ?: 0.0) + config.bm25Weight / (1 + (bm25Pos[path] ?: 999) / 10.0)
        }
        val ranked = pool.sortedByDescending { fused.getValue(it) }

        // Stage 3: one comparative choice over the top K reorders them. If it fails, the order above stands.
        // Role labels are a separate call in parallel, so they can't shift the measured stage-3 answer.
        val top = ranked.take(config.stage3K)
        onProgress("Comparing the top ${top.size}")
        val picksJob = chooser?.let { c -> async { runCatching { c.choose(task, full(top)) } } }
        val rolesJob = roler?.let { r -> async { runCatching { r.roles(task, full(top)) } } }
        val picks = picksJob?.await()
        val roles = rolesJob?.await()
        val choice = picks?.getOrNull().orEmpty()
        val roleOf = roles?.getOrNull().orEmpty()
        val done = System.nanoTime()

        val scale = 1 + config.bm25Weight + if (choice.isEmpty()) 0.0 else config.stage3Weight
        val files = pool.map { path ->
            val pos = bm25Pos[path]
            PackedFile(
                path = path,
                relevance = relevance[path] ?: 0.0,
                score = (fused.getValue(path) + config.stage3Weight * (choice[path] ?: 0.0)) / scale,
                bm25Rank = pos?.plus(1),
                isTest = isTest(path),
                role = roleOf[path]?.maxByOrNull { it.value }?.takeIf { it.value >= ROLE_MIN && it.key != "unrelated" }?.key,
                roleConfidence = roleOf[path]?.maxOfOrNull { it.value },
            )
        }.sortedByDescending { it.score }.take(config.keep)

        PackResult(
            task = task,
            files = files,
            candidates = docs.size,
            pass1Ms = (pass1Done - started) / 1_000_000,
            pass2Ms = (pass2Done - pass1Done) / 1_000_000,
            stage3Ms = (done - pass2Done) / 1_000_000,
            totalMs = (done - started) / 1_000_000,
            failedBatches = pass1.failed + pass2.failed + pass2b.failed +
                (if (picks?.isFailure == true) 1 else 0) + (if (roles?.isFailure == true) 1 else 0),
        )
    }

    /**
     * Lite mode for search-as-you-type: BM25's top files, one Jev pass on full source, fused. About five calls and
     * a second or so. Marked as a preview: it isn't the measured pipeline, and pressing Pack runs that.
     */
    suspend fun preview(task: String, docs: List<FileDoc>): PackResult = coroutineScope {
        val started = System.nanoTime()
        val byPath = docs.associateBy { it.path }
        val bm25Ranked = async(Dispatchers.Default) { Bm25.rank(task, docs.associate { it.path to it.text }) }.await()
        val pool = bm25Ranked.take(config.previewPool)
        val pass = scoreAll(task, pool.map { it to "path: $it\n${byPath.getValue(it).text.take(config.fullChars)}" }, config.perCall,
            throwIfAllFail = true)
        val done = System.nanoTime()
        val files = pool.mapIndexed { i, path ->
            val relevance = pass.scores[path] ?: 0.0
            PackedFile(path, relevance, (relevance + config.bm25Weight / (1 + i / 10.0)) / (1 + config.bm25Weight), i + 1, isTest(path))
        }.sortedByDescending { it.score }.take(config.keep)
        PackResult(task, files, docs.size, pass1Ms = 0, pass2Ms = (done - started) / 1_000_000, stage3Ms = 0,
            totalMs = (done - started) / 1_000_000, failedBatches = pass.failed, preview = true)
    }

    private class Scores(val scores: Map<String, Double>, val failed: Int)

    /**
     * One failed batch costs its files a score of zero, not the whole pack. If every batch fails,
     * Jev isn't answering at all, and quietly returning a keyword-only ranking would be a lie.
     */
    private suspend fun scoreAll(
        task: String,
        items: List<Pair<String, String>>,
        groupSize: Int,
        throwIfAllFail: Boolean = false,
    ): Scores = coroutineScope {
        val parts = items.chunked(groupSize).map { group ->
            async { runCatching { scorer.score(task, group) } }
        }.awaitAll()
        if (throwIfAllFail && parts.isNotEmpty() && parts.all { it.isFailure }) throw parts.first().exceptionOrNull()!!
        val scores = HashMap<String, Double>()
        parts.forEach { part -> part.getOrNull()?.let(scores::putAll) }
        items.forEach { (path, _) -> scores.putIfAbsent(path, 0.0) }
        Scores(scores, parts.count { it.isFailure })
    }

    companion object {
        /** A role label shows only when Jev puts at least this much probability on it. */
        const val ROLE_MIN = 0.5
        private val TEST_DIR = Regex("(^|/)[\\w-]*[tT]est[\\w-]*/")
        private val TEST_FILE = Regex("(Test|Tests|Spec|IT)\\.[A-Za-z]+$")

        fun isTest(path: String) = TEST_DIR.containsMatchIn(path) || TEST_FILE.containsMatchIn(path)
    }
}

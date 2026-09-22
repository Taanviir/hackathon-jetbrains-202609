package dev.contextpacker

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.contextpacker.jev.JevBackend
import dev.contextpacker.jev.JevClient
import dev.contextpacker.jev.JevRelevance
import dev.contextpacker.laya.LayaRelevance
import dev.contextpacker.pack.Bm25
import dev.contextpacker.pack.Candidates
import dev.contextpacker.pack.FileDoc
import dev.contextpacker.pack.KeywordPacker
import dev.contextpacker.pack.PackResult
import dev.contextpacker.pack.PackConfig
import dev.contextpacker.pack.Packer
import dev.contextpacker.pack.RegexSketcher
import dev.contextpacker.pack.Sketcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

data class PackReport(
    val result: PackResult,
    /** Who asked: the tool window, or an agent over MCP. */
    val source: String,
    val sketchMs: Long,
    val jevCalls: Int,
    val inputTokens: Long,
    /** HTTP/response-envelope failures, including retries; scorer failures are result.failedBatches. */
    val failedCalls: Int,
    val jevModel: String,
    val provider: DecisionProvider = DecisionProvider.JEV,
    val scoredCandidates: Int = result.candidates,
    val usageKnown: Boolean = true,
    val cachedRequests: Int = 0,
    val cachedInputTokens: Long = 0,
) {
    /** API fee estimate only. Local hardware/electricity are not included. */
    val costUsd: Double? get() = when {
        provider == DecisionProvider.LAYA || provider == DecisionProvider.KEYWORDS -> 0.0
        usageKnown -> inputTokens * 0.042 / 1_000_000
        else -> null
    }
    val totalMs get() = sketchMs + result.totalMs
}

/** The keyword-only report contract has no model usage, request ledger, or probability estimate. */
internal fun keywordReport(result: PackResult, source: String, collectMs: Long) = PackReport(
    result = result,
    source = source,
    sketchMs = collectMs,
    jevCalls = 0,
    inputTokens = 0,
    failedCalls = 0,
    jevModel = "Full-corpus BM25 keywords (local; no model)",
    provider = DecisionProvider.KEYWORDS,
    scoredCandidates = result.candidates,
    usageKnown = true,
)

@Service(Service.Level.PROJECT)
class ContextPackerService(private val project: Project, val scope: CoroutineScope) {
    private data class Cached(val stamp: Long, val doc: FileDoc)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val packLock = Mutex()
    private val settings get() = project.service<PackerSettings>()
    var provider: DecisionProvider
        get() = settings.provider
        set(value) { settings.provider = value }

    /**
     * Pass 1 reads the spike's regex sketches by default: they're what every number in
     * spike/RESULTS.md was measured with, and they take under a second for 2,000 files. Structure
     * View sketches read better but cost ~19 ms a file cold (Kotlin analysis), 40 s+ on a fresh IDE,
     * and are unmeasured. CONTEXT_PACKER_PSI_SKETCH=1 turns them on.
     */
    private val psiSketches = System.getenv("CONTEXT_PACKER_PSI_SKETCH") == "1"
    @Volatile private var jev: JevClient? = null
    private val laya by lazy { LayaRelevance() }

    /** Jev input tokens spent in this IDE session, against a cap so a looping agent can't drain an account. */
    val sessionTokens get() = jev?.calls?.sumOf { it.inputTokens.toLong() } ?: 0L
    private val sessionBudget = System.getenv("CONTEXT_PACKER_TOKEN_BUDGET")?.toLongOrNull() ?: 20_000_000L

    private val listenerLock = Any()
    private val listeners = mutableListOf<(PackReport) -> Unit>()

    /** The latest pack, including agent requests made before the tool window opened. */
    @Volatile var lastReport: PackReport? = null
        private set

    /** Register and optionally replay the latest report without a subscribe/replay race. */
    fun onPack(replayLast: Boolean = false, listener: (PackReport) -> Unit): () -> Unit {
        synchronized(listenerLock) {
            listeners += listener
            if (replayLast) lastReport?.let { report -> notifyListener(listener, report) }
        }
        return { synchronized(listenerLock) { listeners.remove(listener) } }
    }

    private fun notifyListener(listener: (PackReport) -> Unit, report: PackReport) {
        try {
            listener(report)
        } catch (e: Exception) {
            thisLogger().warn("Pack report listener failed", e)
        }
    }

    private fun publish(report: PackReport): PackReport {
        synchronized(listenerLock) {
            lastReport = report
            listeners.toList().forEach { notifyListener(it, report) }
        }
        return report
    }

    suspend fun pack(
        task: String,
        source: String = "tool window",
        requestedProvider: DecisionProvider? = null,
        onProgress: (String) -> Unit = {},
    ): PackReport = packLock.withLock {
        require(task.isNotBlank()) { "Describe a coding task before packing context." }
        require(task.length <= 8_000) { "Keep the task description under 8,000 characters." }
        val selectedProvider = requestedProvider ?: provider
        require(selectedProvider != DecisionProvider.LAYA || task.length <= LayaRelevance.MAX_TASK_CHARS) {
            "Laya has a small input window. Keep the task under ${LayaRelevance.MAX_TASK_CHARS} characters."
        }
        if (selectedProvider == DecisionProvider.KEYWORDS) {
            onProgress("Reading project source files")
            val started = System.nanoTime()
            val texts = collectKeywordTexts()
            val collectMs = (System.nanoTime() - started) / 1_000_000
            onProgress("Ranking all ${texts.size} files with local keywords")
            val result = withContext(Dispatchers.Default) {
                KeywordPacker.pack(task, texts, checkCancelled = { ensureActive() })
            }
            thisLogger().info("pack: ${texts.size} files · collect ${collectMs} ms · full-corpus BM25 ${result.totalMs} ms · no model calls")
            return@withLock publish(keywordReport(result, source, collectMs))
        }
        val client = if (selectedProvider == DecisionProvider.JEV) jevClient() else null
        if (client != null && sessionTokens >= sessionBudget) throw BudgetExceededException(sessionTokens, sessionBudget)
        val ledger = client?.calls ?: laya.calls
        val before = ledger.size
        onProgress("Sketching project files")
        val started = System.nanoTime()
        val docs = collectDocs()
        val sketchMs = (System.nanoTime() - started) / 1_000_000
        val prefilterStarted = System.nanoTime()
        // Laya is a small local encoder: bound CPU work and report this lexical prefilter openly.
        val scoringDocs = if (selectedProvider == DecisionProvider.LAYA && docs.size > LayaRelevance.MAX_CANDIDATES) {
            val paths = Bm25.rank(task, docs.associate { it.path to it.text }).take(LayaRelevance.MAX_CANDIDATES)
            val byPath = docs.associateBy { it.path }
            paths.map(byPath::getValue)
        } else docs
        val prefilterMs = (System.nanoTime() - prefilterStarted) / 1_000_000
        val packer = if (client != null) {
            val jevScorer = JevRelevance(client)
            Packer(jevScorer, chooser = jevScorer)
        } else Packer(
            laya, PackConfig(
                batch = 1, pool = 20, perCall = 1, fullChars = LayaRelevance.MAX_EXCERPT_CHARS,
                overlapPasses = false,
            ),
        )
        val scored = packer.pack(task, scoringDocs, onProgress)
        val result = scored.copy(
            candidates = docs.size, pass1Ms = scored.pass1Ms + prefilterMs, totalMs = scored.totalMs + prefilterMs,
        )
        val calls = ledger.drop(before)
        val slowest = calls.maxOfOrNull { it.ms } ?: 0
        thisLogger().info(
            "pack: ${docs.size} files · sketch ${sketchMs} ms · pass1+bm25 ${result.pass1Ms} ms · pass2 ${result.pass2Ms} ms · " +
                "stage3 ${result.stage3Ms} ms · ${calls.size} ${selectedProvider.name} HTTP attempts, slowest ${slowest} ms, " +
                "${calls.count { it.error != null }} request errors, ${result.failedBatches} incomplete scoring batches · " +
                "${calls.sumOf { it.inputTokens }} tokens, session ${sessionTokens} · cache ${cache.size}",
        )
        PackReport(
            result = result,
            source = source,
            sketchMs = sketchMs,
            jevCalls = calls.size,
            inputTokens = calls.sumOf { it.inputTokens.toLong() },
            failedCalls = calls.count { it.error != null },
            jevModel = client?.let { "${it.model} via ${it.backend.name.lowercase()}" } ?: "Laya ${laya.model} (local, short excerpts)",
            provider = selectedProvider,
            scoredCandidates = scoringDocs.size,
            usageKnown = calls.all { it.usageKnown },
            cachedRequests = calls.count { it.cacheHit && it.error == null },
            cachedInputTokens = calls.filter { it.cacheHit && it.error == null }.sumOf { it.cachedInputTokens.toLong() },
        ).let(::publish)
    }

    /** Fills the sketch cache without calling Jev, so the first real pack skips the sketching step. */
    suspend fun warm() {
        if (provider == DecisionProvider.KEYWORDS) return
        val started = System.nanoTime()
        val docs = collectDocs()
        thisLogger().info("warm-up: sketched ${docs.size} files in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Full text of each path, for building a prompt out of the picks. */
    suspend fun texts(paths: List<String>): Map<String, String> {
        val base = project.guessProjectDir() ?: return emptyMap()
        return readAction {
            paths.mapNotNull { path ->
                val file = safeFile(base, path) ?: return@mapNotNull null
                if (file.isDirectory || file.fileType.isBinary || file.length > Candidates.MAX_BYTES) return@mapNotNull null
                val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
                    ?: loadSource(file) ?: return@mapNotNull null
                path to text
            }.toMap()
        }
    }

    fun fileFor(path: String): VirtualFile? = project.guessProjectDir()?.let { safeFile(it, path) }

    private fun safeFile(base: VirtualFile, path: String): VirtualFile? {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || ':' in normalized || normalized.split('/').any { it == ".." }) return null
        val file = base.findFileByRelativePath(normalized) ?: return null
        return file.takeIf { VfsUtilCore.isAncestor(base, it, true) }
    }

    /**
     * TypeSafe's own API by default: ~2.5 s a pack. Vercel AI Gateway only with `JEV_BACKEND=gateway`,
     * or when no TypeSafe key is set; it serves the same model but rate-limits hard (30-60 s a pack).
     */
    private suspend fun jevClient(): JevClient {
        jev?.let { return it }
        val client = withContext(Dispatchers.IO) {
            val wantGateway = System.getenv("JEV_BACKEND")?.equals("gateway", ignoreCase = true) == true
            val typesafe = Keys.TYPESAFE.get().takeUnless { wantGateway }
            val gateway = Keys.GATEWAY.get()
            when {
                typesafe != null -> JevClient(typesafe, JevBackend.TYPESAFE)
                gateway != null -> JevClient(gateway, JevBackend.GATEWAY, concurrency = 2)
                else -> throw MissingKeyException(Keys.TYPESAFE, Keys.GATEWAY)
            }
        }
        return client.also { jev = it }
    }

    /** Many short read actions in parallel, never one long one, so typing is never blocked. */
    private suspend fun collectDocs(): List<FileDoc> = coroutineScope {
        val files = smartReadAction(project) { Candidates.collect(project) }
        val activePaths = files.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(activePaths)
        val base = project.guessProjectDir()
        files.chunked(16).map { chunk ->
            async(Dispatchers.Default) { readAction { chunk.mapNotNull { docFor(it, base) } } }
        }.awaitAll().flatten()
    }

    /** Read full source for BM25 without inserting empty sketches into the model-mode cache. */
    private suspend fun collectKeywordTexts(): Map<String, String> = coroutineScope {
        val files = smartReadAction(project) { Candidates.collect(project) }
        cache.keys.retainAll(files.mapTo(HashSet()) { it.path })
        val base = project.guessProjectDir() ?: return@coroutineScope emptyMap<String, String>()
        files.chunked(16).map { chunk ->
            async(Dispatchers.Default) {
                readAction {
                    chunk.mapNotNull { file ->
                        val path = VfsUtilCore.getRelativePath(file, base) ?: return@mapNotNull null
                        val document = FileDocumentManager.getInstance().getCachedDocument(file)
                        val stamp = document?.modificationStamp ?: file.modificationStamp
                        val text = cache[file.path]?.takeIf { it.stamp == stamp }?.doc?.text
                            ?: document?.text ?: loadSource(file) ?: return@mapNotNull null
                        path to text
                    }
                }
            }
        }.awaitAll().flatten().toMap()
    }

    private fun docFor(file: VirtualFile, base: VirtualFile?): FileDoc? {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file.path]?.takeIf { it.stamp == stamp }?.let { return it.doc }
        val text = document?.text ?: loadSource(file) ?: return null
        val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: return null
        val sketch = if (!psiSketches) RegexSketcher.sketch(path, text) else try {
            PsiManager.getInstance(project).findFile(file)?.let { Sketcher.sketch(it, path, text) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: RegexSketcher.sketch(path, text)
        return FileDoc(path, sketch, text).also { cache[file.path] = Cached(stamp, it) }
    }

    private fun loadSource(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

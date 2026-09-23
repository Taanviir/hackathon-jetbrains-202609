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
import java.time.Duration
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
    val settingsRevision: Long = 0,
    val profileDescription: String = "",
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
    private data class Cached(val stamp: Long, val doc: FileDoc, val layaSketch: Boolean)

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
    private var laya: LayaRelevance? = null
    private var layaConfiguration: ProviderConfiguration? = null
    @Volatile private var jevKey: String? = null
    @Volatile private var jevTimeoutSeconds = 30

    /** Reported Jev tokens in this session; checked between packs, not a strict in-flight spending cap. */
    val sessionTokens get() = retiredTokens + (jev?.calls?.sumOf { it.inputTokens.toLong() } ?: 0L)
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
            // A caller still receives its captured result, but a changed profile must not replay it into the UI.
            if (report.settingsRevision != settings.revision) return report
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
        val settingsRevision = settings.revision
        val selectedProvider = requestedProvider ?: provider
        val configuration = settings.configuration(selectedProvider)
        require(task.length <= configuration.taskLimit) {
            "${selectedProvider.label} supports task descriptions up to ${configuration.taskLimit} characters. The task was not truncated; shorten it or choose another provider."
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
            return@withLock publish(keywordReport(result, source, collectMs).copy(
                settingsRevision = settingsRevision, profileDescription = configuration.description,
            ))
        }
        val client = if (selectedProvider == DecisionProvider.JEV) jevClient(configuration) else null
        val local = if (selectedProvider == DecisionProvider.LAYA) layaClient(configuration) else null
        if (client != null && sessionTokens >= sessionBudget) throw BudgetExceededException(sessionTokens, sessionBudget)
        val ledger = client?.calls ?: requireNotNull(local).calls
        val before = ledger.size
        onProgress("Sketching project files")
        val started = System.nanoTime()
        val docs = collectDocs(selectedProvider)
        val sketchMs = (System.nanoTime() - started) / 1_000_000
        val prefilterStarted = System.nanoTime()
        // Laya is a small local encoder: bound CPU work and report this lexical prefilter openly.
        val candidateLimit = configuration.maxCandidates
        val scoringDocs = if (candidateLimit != null && docs.size > candidateLimit) {
            val paths = withContext(Dispatchers.Default) {
                Bm25.rank(task, docs.associate { it.path to it.text }, checkCancelled = { ensureActive() })
            }.take(candidateLimit)
            val byPath = docs.associateBy { it.path }
            paths.map(byPath::getValue)
        } else docs
        val prefilterMs = (System.nanoTime() - prefilterStarted) / 1_000_000
        val packer = if (client != null) {
            val jevScorer = JevRelevance(client)
            Packer(jevScorer, configuration.pack,
                chooser = jevScorer.takeIf { configuration.compareTop },
                roler = jevScorer.takeIf { configuration.assignRoles })
        } else Packer(requireNotNull(local), configuration.pack)
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
            jevModel = client?.let { "${it.model} via ${it.backend.name.lowercase()}" } ?: "Laya ${requireNotNull(local).model} (local, short excerpts)",
            provider = selectedProvider,
            scoredCandidates = scoringDocs.size,
            usageKnown = calls.all { it.usageKnown },
            cachedRequests = calls.count { it.cacheHit && it.error == null },
            cachedInputTokens = calls.filter { it.cacheHit && it.error == null }.sumOf { it.cachedInputTokens.toLong() },
            settingsRevision = settingsRevision,
            profileDescription = configuration.description,
        ).let(::publish)
    }

    /** Typing never invokes a model or changes the saved provider or last explicit pack. */
    suspend fun preview(task: String): PackReport = packLock.withLock {
        require(task.isNotBlank() && task.length <= 8_000) { "Describe a task under 8,000 characters." }
        val started = System.nanoTime()
        val texts = collectKeywordTexts()
        val collectMs = (System.nanoTime() - started) / 1_000_000
        val result = withContext(Dispatchers.Default) {
            KeywordPacker.pack(task, texts, checkCancelled = { ensureActive() }).copy(preview = true)
        }
        keywordReport(result, "preview", collectMs)
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
                if (sourceProblem(file) != null) return@mapNotNull null
                val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
                    ?: loadSource(file) ?: return@mapNotNull null
                path to text
            }.toMap()
        }
    }

    /** Manual pins follow the same readable-source policy as prompt construction. */
    fun fileFor(path: String): VirtualFile? = project.guessProjectDir()
        ?.let { safeFile(it, path) }
        ?.takeIf { sourceProblem(it) == null }

    fun sourceProblem(path: String): String? {
        val base = project.guessProjectDir() ?: return "This project has no source root to read."
        val file = safeFile(base, path) ?: return "This file is outside the project or unavailable."
        return sourceProblem(file)
    }

    private fun sourceProblem(file: VirtualFile): String? = when {
        file.isDirectory -> "Choose a file, not a folder."
        file.fileType.isBinary -> "Binary files cannot be added to a source prompt."
        !Candidates.fitsSizeLimit(file) ->
            "This file exceeds the ${Candidates.MAX_BYTES} source limit (bytes on disk, characters in the editor)."
        else -> null
    }

    private fun safeFile(base: VirtualFile, path: String): VirtualFile? {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || ':' in normalized || normalized.split('/').any { it == ".." }) return null
        val file = base.findFileByRelativePath(normalized) ?: return null
        return file.takeIf { Candidates.isInsideProjectWithoutLinks(base, it) }
    }

    /**
     * TypeSafe's own API by default: ~2.5 s a pack. Vercel AI Gateway only with `JEV_BACKEND=gateway`,
     * or when no TypeSafe key is set; it serves the same model but rate-limits hard (30-60 s a pack).
     */
    private suspend fun jevClient(configuration: ProviderConfiguration): JevClient = withContext(Dispatchers.IO) {
        val (key, backend) = when (configuration.jevBackend) {
            "typesafe" -> (Keys.TYPESAFE.get() ?: throw MissingKeyException(Keys.TYPESAFE)) to JevBackend.TYPESAFE
            "gateway" -> (Keys.GATEWAY.get() ?: throw MissingKeyException(Keys.GATEWAY)) to JevBackend.GATEWAY
            else -> {
                val typesafe = Keys.TYPESAFE.get()
                val gateway = if (typesafe == null) Keys.GATEWAY.get() else null
                when {
                    typesafe != null -> typesafe to JevBackend.TYPESAFE
                    gateway != null -> gateway to JevBackend.GATEWAY
                    else -> throw MissingKeyException(Keys.TYPESAFE, Keys.GATEWAY)
                }
            }
        }
        // Reuse the client (and its spend history) until the key changes, e.g. after "Set API Keys".
        jev?.takeIf { jevKey == key && it.backend == backend && jevTimeoutSeconds == configuration.timeoutSeconds }
            ?: JevClient(key, backend, concurrency = if (backend == JevBackend.GATEWAY) 2 else 48,
                timeout = Duration.ofSeconds(configuration.timeoutSeconds.toLong())).also {
                jev?.let { old -> retiredTokens += old.calls.sumOf { c -> c.inputTokens.toLong() } }
                jev = it
                jevKey = key
                jevTimeoutSeconds = configuration.timeoutSeconds
            }
    }

    /** Called under packLock; endpoint/model/timeouts are replaced between complete packs. */
    private fun layaClient(configuration: ProviderConfiguration): LayaRelevance {
        if (laya == null || layaConfiguration != configuration) {
            laya = LayaRelevance(configuration.layaEndpoint, configuration.layaModel,
                timeout = Duration.ofSeconds(configuration.timeoutSeconds.toLong()),
                maxExcerptChars = configuration.pack.fullChars)
            layaConfiguration = configuration
        }
        return requireNotNull(laya)
    }

    /** Preserve reported spend after a key change; packLock serializes client replacement and requests. */
    @Volatile private var retiredTokens = 0L

    /** Many short read actions in parallel, never one long one, so typing is never blocked. */
    private suspend fun collectDocs(selectedProvider: DecisionProvider = provider): List<FileDoc> = coroutineScope {
        val files = smartReadAction(project) { Candidates.collect(project) }
        val activePaths = files.mapTo(HashSet()) { it.path }
        cache.keys.retainAll(activePaths)
        val base = project.guessProjectDir()
        files.chunked(16).map { chunk ->
            async(Dispatchers.Default) {
                readAction { chunk.mapNotNull { docFor(it, base, selectedProvider == DecisionProvider.LAYA) } }
            }
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
                        if (!Candidates.fitsSizeLimit(file)) return@mapNotNull null
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

    private fun docFor(file: VirtualFile, base: VirtualFile?, layaSketch: Boolean): FileDoc? {
        if (!Candidates.fitsSizeLimit(file)) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file.path]?.takeIf { it.stamp == stamp && it.layaSketch == layaSketch }?.let { return it.doc }
        val text = document?.text ?: loadSource(file) ?: return null
        val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: return null
        val sketch = if (layaSketch) RegexSketcher.layaSketch(path, text)
        else if (!psiSketches) RegexSketcher.sketch(path, text) else try {
            PsiManager.getInstance(project).findFile(file)?.let { Sketcher.sketch(it, path, text) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: RegexSketcher.sketch(path, text)
        return FileDoc(path, sketch, text).also { cache[file.path] = Cached(stamp, it, layaSketch) }
    }

    private fun loadSource(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        null
    }
}

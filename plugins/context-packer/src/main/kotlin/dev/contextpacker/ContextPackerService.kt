package dev.contextpacker

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.contextpacker.jev.JevBackend
import dev.contextpacker.jev.JevClient
import dev.contextpacker.jev.JevRelevance
import dev.contextpacker.pack.Candidates
import dev.contextpacker.pack.FileDoc
import dev.contextpacker.pack.PackResult
import dev.contextpacker.pack.Packer
import dev.contextpacker.pack.RegexSketcher
import dev.contextpacker.pack.Sketcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

data class PackReport(
    val result: PackResult,
    /** Who asked: the tool window, or an agent over MCP. */
    val source: String,
    val sketchMs: Long,
    val jevCalls: Int,
    val inputTokens: Long,
    val failedCalls: Int,
    val jevModel: String,
) {
    /** $0.042 per million input tokens; output is free. */
    val costUsd get() = inputTokens * 0.042 / 1_000_000
    val totalMs get() = sketchMs + result.totalMs
}

@Service(Service.Level.PROJECT)
class ContextPackerService(private val project: Project, val scope: CoroutineScope) {
    private data class Cached(val stamp: Long, val doc: FileDoc)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * Pass 1 reads the spike's regex sketches by default: they're what every number in
     * spike/RESULTS.md was measured with, and they take under a second for 2,000 files. Structure
     * View sketches read better but cost ~19 ms a file cold (Kotlin analysis), 40 s+ on a fresh IDE,
     * and are unmeasured. CONTEXT_PACKER_PSI_SKETCH=1 turns them on.
     */
    private val psiSketches = System.getenv("CONTEXT_PACKER_PSI_SKETCH") == "1"
    @Volatile private var jev: JevClient? = null

    /** Jev input tokens spent in this IDE session, against a cap so a looping agent can't drain an account. */
    val sessionTokens get() = jev?.calls?.sumOf { it.inputTokens.toLong() } ?: 0L
    private val sessionBudget = System.getenv("CONTEXT_PACKER_TOKEN_BUDGET")?.toLongOrNull() ?: 20_000_000L

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PackReport) -> Unit>()

    /** Called after every pack, whoever asked, so the tool window can show what an agent was given. */
    fun onPack(listener: (PackReport) -> Unit) {
        listeners += listener
    }

    suspend fun pack(task: String, source: String = "tool window", onProgress: (String) -> Unit = {}): PackReport {
        val client = jevClient()
        if (sessionTokens >= sessionBudget) throw BudgetExceededException(sessionTokens, sessionBudget)
        val before = client.calls.size
        onProgress("Sketching project files")
        val started = System.nanoTime()
        val docs = collectDocs()
        val sketchMs = (System.nanoTime() - started) / 1_000_000
        val result = Packer(JevRelevance(client)).pack(task, docs, onProgress)
        val calls = client.calls.drop(before)
        val slowest = calls.maxOfOrNull { it.ms } ?: 0
        thisLogger().info(
            "pack: ${docs.size} files · sketch ${sketchMs} ms · pass1+bm25 ${result.pass1Ms} ms · pass2 ${result.pass2Ms} ms · " +
                "${calls.size} Jev calls, slowest ${slowest} ms, ${calls.count { it.error != null }} failed · " +
                "${calls.sumOf { it.inputTokens }} tokens, session ${sessionTokens} · cache ${cache.size}",
        )
        return PackReport(
            result = result,
            source = source,
            sketchMs = sketchMs,
            jevCalls = calls.size,
            inputTokens = calls.sumOf { it.inputTokens.toLong() },
            failedCalls = calls.count { it.error != null },
            jevModel = "${client.model} via ${client.backend.name.lowercase()}",
        ).also { report -> listeners.forEach { it(report) } }
    }

    /** Full text of each path, for building a prompt out of the picks. */
    suspend fun texts(paths: List<String>): Map<String, String> {
        val base = project.guessProjectDir() ?: return emptyMap()
        return readAction {
            paths.mapNotNull { path ->
                val file = base.findFileByRelativePath(path) ?: return@mapNotNull null
                val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
                    ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return@mapNotNull null
                path to text
            }.toMap()
        }
    }

    fun fileFor(path: String): VirtualFile? = project.guessProjectDir()?.findFileByRelativePath(path)

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
        val base = project.guessProjectDir()
        files.chunked(16).map { chunk ->
            async(Dispatchers.Default) { readAction { chunk.mapNotNull { docFor(it, base) } } }
        }.awaitAll().flatten()
    }

    private fun docFor(file: VirtualFile, base: VirtualFile?): FileDoc? {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        cache[file.path]?.takeIf { it.stamp == stamp }?.let { return it.doc }
        val text = document?.text ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.path
        val sketch = if (!psiSketches) RegexSketcher.sketch(path, text) else try {
            PsiManager.getInstance(project).findFile(file)?.let { Sketcher.sketch(it, path, text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: RegexSketcher.sketch(path, text)
        return FileDoc(path, sketch, text).also { cache[file.path] = Cached(stamp, it) }
    }
}

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
    @Volatile private var jev: JevClient? = null

    suspend fun pack(task: String, onProgress: (String) -> Unit = {}): PackReport {
        val client = jevClient()
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
                "${calls.size} Jev calls, slowest ${slowest} ms, ${calls.count { it.error != null }} failed · cache ${cache.size}",
        )
        return PackReport(
            result = result,
            sketchMs = sketchMs,
            jevCalls = calls.size,
            inputTokens = calls.sumOf { it.inputTokens.toLong() },
            failedCalls = calls.count { it.error != null },
            jevModel = "${client.model} via ${client.backend.name.lowercase()}",
        )
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

    /** Vercel AI Gateway if its key is set, TypeSafe's own API otherwise. `JEV_BACKEND` forces one. */
    private suspend fun jevClient(): JevClient {
        jev?.let { return it }
        val client = withContext(Dispatchers.IO) {
            val forced = System.getenv("JEV_BACKEND")?.uppercase()?.let { runCatching { JevBackend.valueOf(it) }.getOrNull() }
            val gateway = Keys.GATEWAY.get().takeIf { forced == null || forced == JevBackend.GATEWAY }
            val typesafe = Keys.TYPESAFE.get().takeIf { forced == null || forced == JevBackend.TYPESAFE }
            when {
                gateway != null -> JevClient(gateway, JevBackend.GATEWAY)
                typesafe != null -> JevClient(typesafe, JevBackend.TYPESAFE)
                else -> throw MissingKeyException(Keys.GATEWAY, Keys.TYPESAFE)
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
        val sketch = try {
            PsiManager.getInstance(project).findFile(file)?.let { Sketcher.sketch(it, path, text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: RegexSketcher.sketch(path, text)
        return FileDoc(path, sketch, text).also { cache[file.path] = Cached(stamp, it) }
    }
}

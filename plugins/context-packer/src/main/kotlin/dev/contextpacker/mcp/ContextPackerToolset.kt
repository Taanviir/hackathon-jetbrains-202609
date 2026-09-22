package dev.contextpacker.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.components.service
import dev.contextpacker.ContextPackerService
import dev.contextpacker.MissingKeyException
import dev.contextpacker.PackReport
import kotlinx.coroutines.currentCoroutineContext

/** Lets any MCP agent (Claude Code, Junie, Cursor…) get a packed context in one call instead of exploring. */
class ContextPackerToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        Find the files in the open project that a coding task needs, ranked by relevance. Call this FIRST,
        before searching or listing directories: it scores every file in the project in about three seconds,
        so you can go straight to reading the top results instead of exploring. Returns project-relative paths
        with a 0-1 relevance score; test files are marked.
        """,
    )
    suspend fun pack_context(
        @McpDescription("The change you are about to make, in a sentence or two, e.g. \"Add exponential backoff to HTTP retries\"")
        task: String,
        @McpDescription("How many files to return, 1 to 20")
        limit: Int = 10,
    ): String {
        val project = currentCoroutineContext().project
        val report = try {
            project.service<ContextPackerService>().pack(task)
        } catch (e: MissingKeyException) {
            throw McpExpectedError(e.message ?: "API key missing")
        }
        return render(report, limit.coerceIn(1, 20), project.basePath)
    }

    private fun render(report: PackReport, limit: Int, basePath: String?): String = buildString {
        val r = report.result
        append("Picked %d of %,d files in %.1f s. Paths are relative to %s.\n".format(
            minOf(limit, r.files.size), r.candidates, report.totalMs / 1000.0, basePath ?: "the project root",
        ))
        append("score  path\n")
        r.files.take(limit).forEach { f ->
            append("%.2f   %s%s\n".format(f.score, f.path, if (f.isTest) "  (test)" else ""))
        }
        append("Read the top few first. Scores come from Jev reading each file's full source against the task, fused with keyword match.")
    }
}

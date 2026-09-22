package dev.contextpacker.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.components.service
import dev.contextpacker.BudgetExceededException
import dev.contextpacker.ContextPackerService
import dev.contextpacker.DecisionProvider
import dev.contextpacker.MissingKeyException
import dev.contextpacker.PackReport
import dev.contextpacker.jev.JevException
import dev.contextpacker.laya.LayaException
import kotlinx.coroutines.currentCoroutineContext

/** Lets any MCP agent (Claude Code, Junie, Cursor…) get a packed context in one call instead of exploring. */
class ContextPackerToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        Locate relevant source files in the open project when a coding task's edit targets are unknown.
        Returns ranked project-relative paths with test files marked; it does not edit files.
        Use provider=laya for the local decision model, provider=jev for the configured API, or configured
        for the IDE preference. Laya scores short excerpts from a keyword shortlist of at most 60 files.
        Scores are ranking signals, not calibrated correctness confidence. Read the selected files before editing.
        """,
    )
    suspend fun pack_context(
        @McpDescription("The change you are about to make, in a sentence or two, e.g. \"Add exponential backoff to HTTP retries\"")
        task: String,
        @McpDescription("How many files to return, 1 to 20")
        limit: Int = 10,
        @McpDescription("Decision provider: configured, laya (local, no API key), or jev (API key required)")
        provider: String = "configured",
    ): String {
        val project = currentCoroutineContext().project
        val report = try {
            val selected = when (provider.lowercase()) {
                "configured" -> null
                "laya" -> DecisionProvider.LAYA
                "jev" -> DecisionProvider.JEV
                else -> throw IllegalArgumentException("provider must be configured, laya, or jev")
            }
            require(limit in 1..20) { "limit must be between 1 and 20" }
            project.service<ContextPackerService>().pack(task, selected)
        } catch (e: MissingKeyException) {
            throw McpExpectedError(e.message ?: "API key missing")
        } catch (e: BudgetExceededException) {
            throw McpExpectedError(e.message ?: "Jev budget used up")
        } catch (e: IllegalArgumentException) {
            throw McpExpectedError(e.message ?: "Invalid pack request")
        } catch (e: LayaException) {
            throw McpExpectedError(e.message ?: "Local Laya is unavailable")
        } catch (e: JevException) {
            throw McpExpectedError(e.message ?: "Jev is unavailable")
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
        append("Provider: ${report.jevModel}; scored ${report.scoredCandidates} candidates. ")
        append(if (report.provider == DecisionProvider.LAYA) "Laya reads short excerpts after a keyword prefilter. API fee is $0; local compute cost is excluded. "
            else "Jev reads bounded source excerpts; " + (report.costUsd?.let { "the API fee estimate is $%.4f. ".format(it) }
                ?: "token usage and API fee are unavailable. "))
        if (r.failedBatches > 0) append("WARNING: ${r.failedBatches} scoring batches failed; ranking is incomplete. ")
        append("Scores combine model relevance and keyword rank. Read the top files before changing code; request more context if needed.")
    }
}

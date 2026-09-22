package dev.intellijev.core

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class IntelliJevProjectService(private val project: Project) {
    private val events = mutableListOf<RunEvent>()
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(message: String) { events += RunEvent(LocalTime.now().format(clock), message) }
    fun events(): List<RunEvent> = events.toList()

    fun findContext(task: String): List<ContextCandidate> {
        val terms = task.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }.toSet()
        val files = mutableListOf<VirtualFile>()
        project.baseDir?.let { collectSourceFiles(it, files, 1_000) }
        log("Context scan inspected ${files.size} source files")
        val candidates = files.map { file ->
            val nameTokens = file.nameWithoutExtension.lowercase().split(Regex("[^a-z0-9]+"))
            val matched = terms.count { term -> nameTokens.any { it.contains(term) } }
            val role = when {
                matched > 0 -> ContextRole.EDIT_TARGET
                file.name.contains("test", true) || file.path.contains("test", true) -> ContextRole.EXAMPLE
                file.nameWithoutExtension.lowercase() in setOf("readme", "package", "pyproject", "cargo", "go", "pom", "build") -> ContextRole.CONSTRAINT
                else -> ContextRole.DEFINITION
            }
            ContextCandidate(file, role, (35 + matched * 25).coerceAtMost(99), if (matched > 0) "Matches task terms" else "Nearby project implementation")
        }.sortedByDescending { it.score }.take(24)
        return rankContextWithJev(task, candidates)
    }

    fun findBugTwins(): List<BugCandidate> {
        val result = mutableListOf<BugCandidate>()
        project.baseDir?.let { root ->
            val files = mutableListOf<VirtualFile>(); collectSourceFiles(root, files, 1_000)
            files.forEach { file ->
                val lines = runCatching { String(file.contentsToByteArray()).lines() }.getOrDefault(emptyList())
                lines.forEachIndexed { i, line ->
                    if (Regex("expiry|expire|validUntil|coupon|discount", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                        result += BugCandidate(file, i, file.nameWithoutExtension, "Expiry-related condition; compare boundary behavior with the selected fix.")
                    }
                }
            }
        }
        log("Bug Twins found ${result.size} unverified candidates")
        return result.take(20)
    }

    fun explainContext(task: String, candidates: List<ContextCandidate>): String {
        val evidence = candidates.take(10).joinToString("\n") { "- ${it.role.label}: ${it.file.path} (${it.reason}, score ${it.score})" }
        return askModel("You are an IDE assistant. Explain, concisely, why these files are useful for this coding task. Do not claim you inspected their bodies. Task: $task\nCandidates:\n$evidence")
    }

    fun explainBugs(candidates: List<BugCandidate>): String {
        val evidence = candidates.take(10).joinToString("\n") { "- ${it.file.path}:${it.line + 1}: ${it.evidence}" }
        return askModel("You are an IDE assistant. Give a concise review plan for these unverified possible related bugs. Clearly say they are candidates, not confirmed defects.\nEvidence:\n$evidence")
    }

    /** Creates reviewable replacements for existing project source files only. */
    fun proposeChanges(task: String, suppliedContext: List<ContextCandidate>): AgentProposal {
        if (task.isBlank()) return AgentProposal("Describe the change you want first.", emptyList())
        val candidates = suppliedContext.ifEmpty { findContext(task) }.filter { it.file.length <= 12_000 }.take(8)
        if (candidates.isEmpty()) return AgentProposal("No eligible source files under 12 KB were found. Narrow the task or choose a smaller file.", emptyList())
        val reviewNote = guardProposalWithJev(task, candidates)
        val context = candidates.joinToString("\n\n") { candidate ->
            val relative = relativePath(candidate.file)
            "FILE: $relative\nROLE: ${candidate.role.label}\n```\n${readText(candidate.file, Int.MAX_VALUE)}\n```"
        }
        val prompt = """You are IntelliJev, a careful coding agent. Make the smallest correct change for this task.
TASK: $task

You may change only files supplied below. Return ONLY valid JSON with this exact schema:
{"summary":"short summary","changes":[{"path":"relative/path","content":"complete replacement file content","summary":"why this file changes"}]}
Use an empty changes array if no safe change is possible. Never use Markdown fences. Preserve unrelated code and formatting.

$context"""
        val raw = askModel(prompt)
        val parsed = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            return AgentProposal("The model did not return a valid edit proposal: ${raw.take(500)}", emptyList())
        }
        val changes = parsed.getAsJsonArray("changes")?.mapNotNull { element ->
            val item = element.asJsonObject
            val path = item.get("path")?.asString ?: return@mapNotNull null
            val file = candidates.firstOrNull { relativePath(it.file) == path }?.file ?: return@mapNotNull null
            val content = item.get("content")?.asString ?: return@mapNotNull null
            ProposedChange(file, readText(file, Int.MAX_VALUE), content, item.get("summary")?.asString ?: "Proposed by model")
        } ?: emptyList()
        log("Agent proposed ${changes.size} reviewed file change(s)")
        return AgentProposal((reviewNote ?: "") + (parsed.get("summary")?.asString ?: "Model returned an edit proposal."), changes)
    }

    private fun askModel(prompt: String): String {
        val settings = IntelliJevSettings.instance()
        val apiKey = settings.generationKey() ?: return "Coding model is not configured. Save an OpenRouter (or OpenAI) key in Settings."
        if (settings.model().isBlank()) return "Coding model is not configured: enter a model ID in Settings."
        return runCatching {
            log("Sending curated context to ${settings.provider()} model ${settings.model()}")
            val body = JsonObject().apply {
                addProperty("model", settings.model())
                add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
                addProperty("temperature", 0.2)
            }
            val endpoint = if (settings.provider() == "OpenAI") "https://api.openai.com/v1/chat/completions" else "https://openrouter.ai/api/v1/chat/completions"
            val requestBuilder = HttpRequest.newBuilder(URI(endpoint))
                .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
            if (settings.provider() == "OpenRouter") requestBuilder.header("X-OpenRouter-Title", "IntelliJev")
            val request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) error("${settings.provider()} returned HTTP ${response.statusCode()}: ${response.body().take(400)}")
            JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message").get("content").asString
        }.onFailure { log("AI request failed: ${it.message}") }.getOrElse { "AI request failed: ${it.message}" }
    }

    /** TypeSafe Jev ranks task relevance; local ranking stays visible when no Jev key is configured. */
    private fun rankContextWithJev(task: String, candidates: List<ContextCandidate>): List<ContextCandidate> {
        val settings = IntelliJevSettings.instance()
        if (settings.jevKey().isNullOrBlank() || candidates.isEmpty()) {
            log("Jev is not configured; showing local candidates")
            return candidates
        }
        val state = JsonObject().apply {
            addProperty("task", task)
            add("candidates", JsonArray().apply { candidates.forEachIndexed { index, candidate ->
                add(JsonObject().apply {
                    addProperty("id", index)
                    addProperty("path", relativePath(candidate.file))
                    addProperty("preview", readText(candidate.file, 900))
                })
            } })
        }
        val questions = JsonObject().apply { candidates.forEachIndexed { index, candidate ->
            add("relevance_$index", JsonObject().apply {
                addProperty("type", "score")
                add("criteria", JsonArray().apply { add("irrelevant to the task"); add("helpful supporting context"); add("directly needed to implement the task") })
                addProperty("instructions", "How useful is candidate $index (${relativePath(candidate.file)}) to solve the task? Use its path and preview. Level 0 is irrelevant, 1 is useful context, 2 is likely needed for an edit.")
            })
        } }
        return runCatching {
            val answers = JevClient(settings.jevKey()!!).decide(state, questions)
            candidates.mapIndexed { index, candidate ->
                val score = JevClient.score(answers, "relevance_$index")
                candidate.copy(score = (score * 50).toInt().coerceIn(0, 100), reason = "Jev relevance ${"%.2f".format(score)}/2 · ${candidate.reason}")
            }.sortedByDescending { it.score }
        }.onFailure { log("Jev context ranking failed; used local ranking: ${it.message}") }.getOrDefault(candidates)
    }

    /** Jev supplies a risk signal shown to the developer; it cannot veto a proposed edit. */
    private fun guardProposalWithJev(task: String, candidates: List<ContextCandidate>): String? {
        val settings = IntelliJevSettings.instance()
        if (settings.jevKey().isNullOrBlank()) return null
        val state = JsonObject().apply { addProperty("task", task); add("files", JsonArray().apply { candidates.forEach { add(relativePath(it.file)) } }) }
        val questions = JsonObject().apply {
            add("risk", JsonObject().apply {
                addProperty("type", "score"); add("criteria", JsonArray().apply { add("low: isolated, reversible source edit"); add("medium: needs careful review"); add("high: security, destructive, credential, deployment, or broad-impact change") })
                addProperty("instructions", "Assess the risk of asking a coding model to propose a change for this task and file set. Do not authorize edits; only classify risk.")
            })
        }
        return runCatching {
            val risk = JevClient.score(JevClient(settings.jevKey()!!).decide(state, questions), "risk")
            if (risk >= 1.5) "Jev estimates elevated review risk (${"%.2f".format(risk)}/2). Inspect the proposed changes carefully. " else null
        }.onFailure { log("Jev safety gate unavailable; continued with manual-review workflow: ${it.message}") }.getOrNull()
    }

    private fun relativePath(file: VirtualFile): String = project.basePath?.let { base -> file.path.removePrefix(base).trimStart('/', '\\') } ?: file.name
    private fun readText(file: VirtualFile, maxChars: Int): String = runCatching { String(file.contentsToByteArray()).take(maxChars) }.getOrDefault("")

    private fun collectSourceFiles(root: VirtualFile, target: MutableList<VirtualFile>, limit: Int) {
        if (target.size >= limit || root.name in setOf(".git", "build", "out", ".idea", "node_modules", "vendor", "dist", "target", "venv", ".venv", ".gradle", ".next", "coverage")) return
        if (!root.isDirectory) {
            val name = root.name.lowercase()
            if (name.contains("secret") || name.contains("credential") || name.contains("private") || name.contains("key.") || name.startsWith(".env")) return
            val extensions = setOf("java", "kt", "kts", "js", "jsx", "ts", "tsx", "py", "go", "rs", "cs", "cpp", "c", "h", "hpp", "php", "rb", "swift", "scala", "sql", "html", "css", "scss", "vue", "svelte", "json", "yaml", "yml", "xml", "md")
            if ((root.extension?.lowercase() in extensions || name in setOf("dockerfile", "makefile", "readme", "gradlew", "pom.xml")) && root.length < 1_000_000) target += root
            return
        }
        root.children.forEach { collectSourceFiles(it, target, limit) }
    }
}

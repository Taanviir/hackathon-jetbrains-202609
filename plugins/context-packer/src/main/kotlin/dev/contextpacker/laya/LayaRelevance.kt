package dev.contextpacker.laya

import dev.contextpacker.jev.CallStat
import dev.contextpacker.pack.RelevanceScorer
import dev.contextpacker.pack.ScorerUnavailableException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException

/** Adapter for the Laya playground's local /api/predict contract. No cloud fallback or API key. */
class LayaRelevance(
    endpoint: String = System.getenv("CONTEXT_PACKER_LAYA_URL") ?: "http://127.0.0.1:8770/api/predict",
    val model: String = System.getenv("CONTEXT_PACKER_LAYA_MODEL") ?: "english",
    private val timeout: Duration = Duration.ofSeconds(90),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    private val maxExcerptChars: Int = MAX_EXCERPT_CHARS,
) : RelevanceScorer {
    init {
        require(maxExcerptChars in 1..MAX_EXCERPT_CHARS) { "Laya excerpt size must be between 1 and $MAX_EXCERPT_CHARS characters." }
    }
    private val uri = URI.create(endpoint).also {
        require(it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") && it.userInfo == null) {
            "Laya must use a local HTTP endpoint, e.g. http://127.0.0.1:8770/api/predict"
        }
    }
    private val permits = Semaphore(1)
    val calls = ConcurrentLinkedQueue<CallStat>()

    override suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double> {
        // Laya's English encoder has a 512-token window. Combining files in one state silently
        // drops later candidates. Each request therefore contains exactly one short file excerpt.
        val scores = LinkedHashMap<String, Double>()
        for ((path, text) in items) scores[path] = permits.withPermit { predict(task, path, text) }
        return scores
    }

    private suspend fun predict(task: String, path: String, text: String): Double {
        val body = buildJsonObject {
            put("model", model)
            put("state", "File: ${path.take(240)}\n${text.take(maxExcerptChars)}")
            put("questions", buildJsonObject {
                put("relevant", buildJsonObject {
                    put("type", "noul")
                    put("instructions", "This source file is relevant to implementing the following coding task: ${task.take(MAX_TASK_CHARS)}")
                })
            })
        }
        val request = HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val started = System.nanoTime()
        var tokens = 0
        var usageKnown = false
        var cacheHit = false
        var cachedTokens = 0
        try {
            val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() != 200) throw ScorerUnavailableException(
                "Local Laya returned HTTP ${response.statusCode()}. Check its server log and model readiness, then pack again.",
            )
            val root = Json.parseToJsonElement(response.body()).jsonObject
            val tokenValue = (root["usage"] as? JsonObject)?.get("input_tokens") as? JsonPrimitive
            val parsedTokens = tokenValue?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
            tokens = parsedTokens ?: 0
            usageKnown = parsedTokens != null
            cacheHit = (root["cache_hit"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
            val cachedValue = (root["usage"] as? JsonObject)?.get("cached_input_tokens") as? JsonPrimitive
            cachedTokens = if (cacheHit) cachedValue?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 } ?: 0 else 0
            val probability = root["answers"]?.jsonObject?.get("relevant")?.jsonObject
                ?.get("noul")?.jsonPrimitive?.takeUnless { it.isString }?.doubleOrNull
            if (probability == null || !probability.isFinite() || probability !in 0.0..1.0) {
                throw LayaException("Local Laya returned no valid relevance probability.")
            }
            calls += CallStat(elapsed(started), tokens, 1, null, usageKnown, cacheHit, cachedTokens)
            return probability
        } catch (e: CancellationException) {
            calls += CallStat(elapsed(started), 0, 1, "Local Laya request cancelled", usageKnown = false)
            throw e
        } catch (e: Exception) {
            val failure = when (e) {
                is ScorerUnavailableException, is LayaException -> e
                is IOException -> ScorerUnavailableException(
                    "Could not score with local Laya. Check the configured local server (${e::class.simpleName}), then pack again.", e,
                )
                else -> LayaException("Local Laya returned an invalid response (${e::class.simpleName}).", e)
            }
            calls += CallStat(elapsed(started), tokens, 1, failure.message, usageKnown, cacheHit, cachedTokens)
            throw failure
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val MAX_EXCERPT_CHARS = 1_000
        const val MAX_TASK_CHARS = 500
        /** CPU-local scoring is bounded before inference; the shortlist remains explicit in reports. */
        const val MAX_CANDIDATES = 60
    }
}

class LayaException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

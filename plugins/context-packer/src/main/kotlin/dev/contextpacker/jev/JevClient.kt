package dev.contextpacker.jev

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.random.Random

/**
 * Client for TypeSafe's System One endpoint, which serves Jev.
 *
 * There is no JVM SDK, so this mirrors typesafe-sdk 0.7.1: the same request body, and the same
 * retry policy (408, 429 and 5xx; exponential backoff with jitter; `retry-after` honoured).
 */
class JevClient(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    concurrency: Int = 16,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val maxRetries: Int = 4,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val permits = Semaphore(concurrency)

    /** Every call made, successful or not. Read it to report latency and token counts. */
    val calls = ConcurrentLinkedQueue<CallStat>()

    suspend fun systemOne(state: JsonObject, questions: Map<String, JsonObject>): JevResponse {
        val body = buildJsonObject {
            put("model", model)
            put("state", state)
            put("questions", JsonObject(questions))
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/v1/systemone"))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return permits.withPermit { send(request, questions.size) }
    }

    private suspend fun send(request: HttpRequest, questionCount: Int): JevResponse {
        val started = System.nanoTime()
        var wait = 0L
        var lastStatus: Int? = null
        var lastError = ""
        for (attempt in 0..maxRetries) {
            if (attempt > 0) delay(wait)
            val response = try {
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: java.io.IOException) {
                lastStatus = null
                lastError = "${e::class.simpleName}: ${e.message}"
                wait = backoffMs(attempt + 1)
                continue
            }
            if (response.statusCode() == 200) {
                val parsed = JevResponse.parse(response.body())
                calls += CallStat(elapsedMs(started), parsed.inputTokens, questionCount, null)
                return parsed
            }
            lastStatus = response.statusCode()
            lastError = "HTTP ${response.statusCode()}: ${response.body().take(MAX_ERROR_BODY)}"
            if (response.statusCode() !in RETRY_STATUSES) break
            wait = retryAfterMs(response) ?: backoffMs(attempt + 1)
        }
        calls += CallStat(elapsedMs(started), 0, questionCount, lastError)
        throw JevException(lastStatus, lastError)
    }

    private fun backoffMs(attempt: Int): Long {
        val exponential = min(BACKOFF_MAX_MS, BACKOFF_INITIAL_MS shl (attempt - 1).coerceAtMost(10))
        return (exponential * (1 - Random.nextDouble() * BACKOFF_JITTER)).toLong()
    }

    private fun retryAfterMs(response: HttpResponse<*>): Long? {
        response.headers().firstValue("retry-after-ms").orElse(null)?.toDoubleOrNull()?.let { return it.toLong() }
        return response.headers().firstValue("retry-after").orElse(null)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
        const val DEFAULT_MODEL = "jev-latest"
        private val RETRY_STATUSES = setOf(408, 429) + (500..599)
        private const val BACKOFF_INITIAL_MS = 500L
        private const val BACKOFF_MAX_MS = 5_000L
        private const val BACKOFF_JITTER = 0.25
        private const val MAX_ERROR_BODY = 200
    }
}

data class CallStat(val ms: Long, val inputTokens: Int, val questions: Int, val error: String?)

class JevException(val status: Int?, message: String) : RuntimeException(message)

data class JevResponse(val model: String, val answers: Map<String, JsonObject>, val inputTokens: Int) {
    /** P(statement is true) for a `noul` question, or null if Jev didn't answer it. */
    fun noul(key: String): Double? = answers[key]?.get("noul")?.jsonPrimitive?.doubleOrNull

    fun probabilities(key: String): Map<String, Double> =
        answers[key]?.get("probabilities")?.jsonObject?.mapValues { it.value.jsonPrimitive.double }.orEmpty()

    companion object {
        fun parse(body: String): JevResponse {
            val root = Json.parseToJsonElement(body).jsonObject
            return JevResponse(
                model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                answers = root["answers"]?.jsonObject?.mapValues { it.value.jsonObject }.orEmpty(),
                inputTokens = root["usage"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}

object Questions {
    fun noul(instructions: String): JsonObject = buildJsonObject {
        put("type", "noul")
        put("instructions", instructions)
    }
}

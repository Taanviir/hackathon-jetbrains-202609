package dev.contextpacker.jev

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Where Jev is served from. Both take the same state and questions; they differ in envelope. */
enum class JevBackend(val endpoint: String, val defaultModel: String) {
    /** TypeSafe's own API, as typesafe-sdk 0.7.1 calls it. */
    TYPESAFE("https://api.typesafe.ai/v1/systemone", "jev-latest"),

    /** Vercel AI Gateway's evaluation-model route, as @ai-sdk/gateway calls it. Yes/no questions are `boolean`. */
    GATEWAY("https://ai-gateway.vercel.sh/v4/ai/evaluation-model", "typesafe-ai/jev-latest"),
}

/**
 * Client for Jev. There is no JVM SDK, so this mirrors the official clients' wire format and
 * typesafe-sdk's retry policy (408, 429 and 5xx; exponential backoff with jitter; `retry-after`).
 */
class JevClient(
    private val apiKey: String,
    val backend: JevBackend = JevBackend.TYPESAFE,
    val model: String = backend.defaultModel,
    private val endpoint: String = backend.endpoint,
    concurrency: Int = 16,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val maxRetries: Int = 4,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val permits = Semaphore(concurrency)

    /** Every call made, successful or not. Read it to report latency and token counts. */
    val calls = ConcurrentLinkedQueue<CallStat>()

    /** Questions are written in TypeSafe's vocabulary (`noul`); the gateway's is translated here. */
    suspend fun systemOne(state: JsonObject, questions: Map<String, JsonObject>): JevResponse {
        val builder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
        val body = when (backend) {
            JevBackend.TYPESAFE -> buildJsonObject {
                put("model", model)
                put("state", state)
                put("questions", JsonObject(questions))
            }
            JevBackend.GATEWAY -> {
                builder.header("ai-gateway-protocol-version", "0.0.1")
                    .header("ai-gateway-auth-method", "api-key")
                    .header("ai-evaluation-model-specification-version", "4")
                    .header("ai-model-id", model)
                buildJsonObject {
                    put("state", state)
                    put("questions", JsonObject(questions.mapValues { (_, q) -> toGateway(q) }))
                }
            }
        }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        return permits.withPermit { send(request, questions.size) }
    }

    private fun toGateway(question: JsonObject): JsonObject =
        if (question["type"]?.jsonPrimitive?.contentOrNull == "noul") JsonObject(question + ("type" to JsonPrimitive("boolean")))
        else question

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
                val parsed = JevResponse.parse(response.body()).let { if (it.model.isEmpty()) it.copy(model = model) else it }
                calls += CallStat(elapsedMs(started), parsed.inputTokens, questionCount, null)
                return parsed
            }
            lastStatus = response.statusCode()
            lastError = describe(response.statusCode()) + " (HTTP ${response.statusCode()}): ${response.body().take(MAX_ERROR_BODY)}"
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

    private fun describe(status: Int) = when (status) {
        401, 403 -> "Jev rejected the API key"
        402 -> "No credits left on this Jev account"
        413 -> "Request too large for Jev"
        429 -> "Jev rate limit hit"
        in 500..599 -> "Jev is having trouble"
        else -> "Jev request failed"
    }

    companion object {
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
    /** P(statement is true) for a yes/no question (`noul` on TypeSafe, `boolean` on the gateway), or null. */
    fun noul(key: String): Double? =
        (answers[key]?.get("noul") ?: answers[key]?.get("probability"))?.jsonPrimitive?.doubleOrNull

    fun probabilities(key: String): Map<String, Double> =
        answers[key]?.get("probabilities")?.jsonObject?.mapValues { it.value.jsonPrimitive.double }.orEmpty()

    companion object {
        fun parse(body: String): JevResponse {
            val root = Json.parseToJsonElement(body).jsonObject
            return JevResponse(
                model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                answers = root["answers"]?.jsonObject?.mapValues { it.value.jsonObject }.orEmpty(),
                inputTokens = root["usage"]?.jsonObject?.let { it["input_tokens"] ?: it["inputTokens"] }
                    ?.jsonPrimitive?.intOrNull ?: 0,
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

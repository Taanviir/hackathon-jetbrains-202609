package dev.contextpacker.llm

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The text-writing half: an OpenAI-compatible chat endpoint, OpenRouter by default. */
class ChatClient(
    private val apiKey: String,
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    suspend fun complete(system: String, user: String): ChatReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Context Packer")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        check(response.statusCode() == 200) { "LLM call failed, HTTP ${response.statusCode()}: ${response.body().take(300)}" }
        val root = Json.parseToJsonElement(response.body()).jsonObject
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        check(!content.isNullOrBlank()) { "The coding model returned no text answer. Check the model configuration and try again." }
        val usage = root["usage"] as? JsonObject
        fun tokenCount(name: String): Int? = (usage?.get(name) as? JsonPrimitive)
            ?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }
        return ChatReply(
            content = content,
            promptTokens = tokenCount("prompt_tokens"),
            completionTokens = tokenCount("completion_tokens"),
            cost = (usage?.get("cost") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() && it >= 0 },
            truncated = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull == "length",
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        val DEFAULT_MODEL: String = System.getenv("CONTEXT_PACKER_LLM_MODEL") ?: "z-ai/glm-5.3-flash"
    }
}

data class ChatReply(
    val content: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val cost: Double?,
    val truncated: Boolean = false,
)

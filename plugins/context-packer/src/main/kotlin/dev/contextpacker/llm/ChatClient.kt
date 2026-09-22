package dev.contextpacker.llm

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val usage = root["usage"]?.jsonObject
        return ChatReply(
            content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            cost = usage?.get("cost")?.jsonPrimitive?.doubleOrNull,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        val DEFAULT_MODEL: String = System.getenv("CONTEXT_PACKER_LLM_MODEL") ?: "z-ai/glm-5.3-flash"
    }
}

data class ChatReply(val content: String, val promptTokens: Int, val completionTokens: Int, val cost: Double?)

package dev.intellijev.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Direct TypeSafe System One HTTP transport, matching the official SDK's system_one call. */
class JevClient(private val apiKey: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun decide(state: JsonObject, questions: JsonObject): JsonObject {
        require(questions.size() > 0) { "Jev needs at least one question" }
        val body = JsonObject().apply {
            addProperty("model", "jev-latest")
            add("state", state)
            add("questions", questions)
        }
        val request = HttpRequest.newBuilder(URI("https://api.typesafe.ai/v1/systemone"))
            .timeout(Duration.ofSeconds(35))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Jev request was cancelled")
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error("TypeSafe Jev HTTP ${response.statusCode()}: ${response.body().take(300)}")
        return JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("answers")
            ?: error("TypeSafe Jev response has no answers")
    }

    companion object {
        fun score(answers: JsonObject, id: String): Double {
            val answer = answers.getAsJsonObject(id) ?: error("Jev omitted answer $id")
            require(answer.get("type")?.asString == "score") { "Jev returned an unexpected answer type for $id" }
            val score = answer.get("score")?.asDouble ?: error("Jev omitted score for $id")
            require(score.isFinite() && score in 0.0..2.0) { "Jev score is outside the expected rubric" }
            return score
        }
    }
}

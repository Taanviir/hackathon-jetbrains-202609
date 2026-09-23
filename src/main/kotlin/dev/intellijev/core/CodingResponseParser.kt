package dev.intellijev.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Accept only a completed assistant text turn before parsing it as an edit proposal. */
internal object CodingResponseParser {
    fun content(body: String): String {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
        require(root?.isJsonObject == true) { "Coding model returned an invalid completion envelope." }
        val choices = root!!.asJsonObject.get("choices")
        require(choices?.isJsonArray == true && choices.asJsonArray.size() > 0) {
            "Coding model returned no choices."
        }
        val first = choices!!.asJsonArray[0]
        require(first.isJsonObject) { "Coding model returned an invalid choice." }
        val choice = first.asJsonObject
        val finish = stringValue(choice.get("finish_reason"))
        require(finish == "stop") { "Coding model response did not finish normally (finish reason: ${finish ?: "missing"})." }
        val message = choice.get("message")
        require(message?.isJsonObject == true) { "Coding model returned no assistant message." }
        val assistant = message!!.asJsonObject
        require(stringValue(assistant.get("role")) == "assistant") {
            "Coding model returned a non-assistant message."
        }
        val content = stringValue(assistant.get("content"))?.takeIf { it.isNotBlank() }
        require(content != null) { "Coding model returned no text content." }
        return content
    }

    private fun stringValue(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

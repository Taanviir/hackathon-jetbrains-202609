package dev.intellijev.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class ParsedReplacement(val path: String, val before: String, val after: String, val summary: String)
internal data class ParsedProposal(val summary: String, val changes: List<ParsedReplacement>)

/** Parse only complete replacements for the exact source snapshots supplied to the model. */
internal object EditProposalParser {
    fun parse(raw: String, originals: Map<String, String>): ParsedProposal {
        require(raw.length <= 1_000_000) { "The response exceeds the proposal size limit." }
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
        require(root?.isJsonObject == true) { "Expected a JSON object." }
        val body = root!!.asJsonObject
        val changes = body.get("changes")
        require(changes?.isJsonArray == true) { "Expected a changes array." }
        require(changes!!.asJsonArray.size() <= originals.size) { "Too many file replacements." }
        val seen = HashSet<String>()
        val replacements = changes.asJsonArray.map { element ->
            require(element.isJsonObject) { "Every replacement must be an object." }
            val item = element.asJsonObject
            val path = text(item, "path")
            val before = originals[path]
            require(before != null) { "A replacement refers to a file outside the supplied context." }
            require(seen.add(path)) { "A file appears more than once in the proposal." }
            val after = text(item, "content")
            require(after.length <= 100_000) { "A replacement exceeds the file size limit." }
            ParsedReplacement(path, before, after, text(item, "summary", "Proposed by model"))
        }
        return ParsedProposal(text(body, "summary", "Model returned an edit proposal."), replacements)
    }

    private fun text(objectValue: JsonObject, key: String, default: String? = null): String {
        val value = objectValue.get(key)
        if (value == null && default != null) return default
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Expected text in '$key'." }
        return value!!.asString
    }
}

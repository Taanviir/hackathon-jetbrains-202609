package dev.contextpacker.jev

import dev.contextpacker.pack.ChoiceScorer
import dev.contextpacker.pack.RelevanceScorer
import dev.contextpacker.pack.RoleScorer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Jev call for a batch of files. Each file gets its own key in the state, and its own
 * question naming that key, which is how a hundred files share one request.
 */
class JevRelevance(private val client: JevClient) : RelevanceScorer, ChoiceScorer, RoleScorer {

    override suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double> {
        val keys = items.mapIndexed { i, (path, _) -> key(i) to path }.toMap()
        val state = buildJsonObject {
            put("task", task)
            items.forEachIndexed { i, (_, text) -> put(key(i), text) }
        }
        val response = client.systemOne(state, keys.keys.associateWith { Questions.noul(question(it)) })
        return keys.entries.associate { (key, path) -> path to (response.noul(key) ?: 0.0) }
    }

    /** One `choice` across the files, so Jev compares them. Returns each file's probability of being the one to edit. */
    override suspend fun choose(task: String, items: List<Pair<String, String>>): Map<String, Double> {
        val keys = items.mapIndexed { i, (path, _) -> key(i) to path }.toMap()
        val state = buildJsonObject {
            put("task", task)
            items.forEachIndexed { i, (_, text) -> put(key(i), text) }
        }
        val criteria = keys.mapValues { (key, path) -> "the file in `$key` ($path)" }
        val response = client.systemOne(state, mapOf(PICK to Questions.choice(CHOICE_QUESTION, criteria)))
        val probs = response.probabilities(PICK)
        return keys.entries.associate { (key, path) -> path to (probs[key] ?: 0.0) }
    }

    /** One `choice` per file, all in one call: what part does each file play in the change? */
    override suspend fun roles(task: String, items: List<Pair<String, String>>): Map<String, Map<String, Double>> {
        val keys = items.mapIndexed { i, (path, _) -> key(i) to path }.toMap()
        val state = buildJsonObject {
            put("task", task)
            items.forEachIndexed { i, (_, text) -> put(key(i), text) }
        }
        val questions = keys.keys.associate { key ->
            "role_$key" to Questions.choice("What part does the file in `$key` play in the change described in `task`?", ROLES)
        }
        val response = client.systemOne(state, questions)
        return keys.entries.associate { (key, path) -> path to response.probabilities("role_$key") }
    }

    companion object {
        val ROLES = mapOf(
            "edit" to "Implementing the change requires editing this file.",
            "test" to "This file tests the code being changed and would need updating.",
            "example" to "This file shows an existing pattern the change should follow, but is not edited.",
            "dependency" to "The change uses an API declared in this file, but the file is not edited.",
            "unrelated" to "This file has nothing to do with the change.",
        )

        /** Stage 3's question. Chosen on dev (recall@5 0.536 to 0.583), measured once on test (0.539 to 0.572). */
        const val CHOICE_QUESTION = "Which file must be edited to implement the change described in `task`?"
        private const val PICK = "pick"

        /** The broader wording won in the spike: 0.66 vs 0.59 recall@10 for "requires editing". */
        fun question(key: String) =
            "Implementing the change described in `task` requires reading or editing the file in `$key`."

        private fun key(i: Int) = "f%03d".format(i)
    }
}

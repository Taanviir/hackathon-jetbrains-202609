package dev.contextpacker.jev

import dev.contextpacker.pack.RelevanceScorer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Jev call for a batch of files. Each file gets its own key in the state, and its own
 * question naming that key, which is how a hundred files share one request.
 */
class JevRelevance(private val client: JevClient) : RelevanceScorer {

    override suspend fun score(task: String, items: List<Pair<String, String>>): Map<String, Double> {
        val keys = items.mapIndexed { i, (path, _) -> key(i) to path }.toMap()
        val state = buildJsonObject {
            put("task", task)
            items.forEachIndexed { i, (_, text) -> put(key(i), text) }
        }
        val response = client.systemOne(state, keys.keys.associateWith { Questions.noul(question(it)) })
        return keys.entries.associate { (key, path) ->
            val probability = response.noul(key)
            require(probability != null && probability.isFinite() && probability in 0.0..1.0) {
                "Jev returned no valid relevance probability for $key"
            }
            path to probability
        }
    }

    companion object {
        /** The broader wording won in the spike: 0.66 vs 0.59 recall@10 for "requires editing". */
        fun question(key: String) =
            "Implementing the change described in `task` requires reading or editing the file in `$key`."

        private fun key(i: Int) = "f%03d".format(i)
    }
}

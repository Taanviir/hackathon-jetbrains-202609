package dev.intellijev.core

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JevClientTest {
    @Test
    fun preservesFractionalRelevanceScore() {
        val answers = JsonParser.parseString("""{"relevance_0":{"type":"score","score":1.7,"confidence":0.9}}""").asJsonObject
        assertEquals(1.7, JevClient.score(answers, "relevance_0"))
    }

    @Test
    fun rejectsWrongAnswerType() {
        val answers = JsonParser.parseString("""{"risk":{"type":"noul","noul":0.9}}""").asJsonObject
        assertFailsWith<IllegalArgumentException> { JevClient.score(answers, "risk") }
    }
}

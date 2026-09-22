package dev.intellijev.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodingResponseParserTest {
    @Test
    fun `completed assistant text is accepted`() {
        val body = """{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Complete proposal"}}]}"""
        assertEquals("Complete proposal", CodingResponseParser.content(body))
    }

    @Test
    fun `truncated and filtered turns cannot become reviewed edits`() {
        for (reason in listOf("length", "content_filter", "tool_calls")) {
            val body = """{"choices":[{"finish_reason":"$reason","message":{"role":"assistant","content":"{\"summary\":\"Ready\",\"changes\":[]}"}}]}"""
            assertFailsWith<IllegalArgumentException>(reason) { CodingResponseParser.content(body) }
        }
    }

    @Test
    fun `missing malformed or empty completion fields are rejected`() {
        listOf(
            "not JSON",
            "{}",
            """{"choices":[]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"   "}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":42}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"type":"text","text":"proposal"}]}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"user","content":"proposal"}}]}""",
            """{"choices":[{"message":{"role":"assistant","content":"proposal"}}]}""",
        ).forEach { body ->
            assertFailsWith<IllegalArgumentException>(body) { CodingResponseParser.content(body) }
        }
    }
}

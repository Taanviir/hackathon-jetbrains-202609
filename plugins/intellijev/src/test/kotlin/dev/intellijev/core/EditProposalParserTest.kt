package dev.intellijev.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EditProposalParserTest {
    private val originals = mapOf("src/Retry.kt" to "original retry source", "src/Test.kt" to "original test")

    @Test
    fun `replacement retains the exact original supplied to the model`() {
        val proposal = EditProposalParser.parse(
            """{"summary":"Fix retry","changes":[{"path":"src/Retry.kt","content":"new source","summary":"Bound retries"}]}""",
            originals,
        )
        assertEquals("Fix retry", proposal.summary)
        assertEquals("original retry source", proposal.changes.single().before)
        assertEquals("new source", proposal.changes.single().after)
        assertEquals("Bound retries", proposal.changes.single().summary)
    }

    @Test
    fun `no safe change can be represented explicitly`() {
        assertTrue(EditProposalParser.parse("""{"summary":"Need more evidence","changes":[]}""", originals).changes.isEmpty())
    }

    @Test
    fun `unknown and duplicate paths reject the entire proposal`() {
        listOf(
            """{"changes":[{"path":"../outside.kt","content":"bad"}]}""",
            """{"changes":[{"path":"src/Retry.kt","content":"one"},{"path":"src/Retry.kt","content":"two"}]}""",
        ).forEach { assertFailsWith<IllegalArgumentException> { EditProposalParser.parse(it, originals) } }
    }

    @Test
    fun `wrong JSON types and incomplete responses fail without partial changes`() {
        listOf(
            "not json", "[]", "null", "{}", """{"changes":{}}""",
            """{"changes":[null]}""", """{"changes":[{"path":7,"content":"bad"}]}""",
            """{"changes":[{"path":"src/Retry.kt","content":null}]}""",
            """{"changes":[{"path":"src/Retry.kt","content":42}]}""",
            """{"changes":[{"path":"src/Retry.kt","content":"ok"},{"path":"src/Test.kt"}]}""",
        ).forEach { raw -> assertFailsWith<IllegalArgumentException>(raw) { EditProposalParser.parse(raw, originals) } }
    }

    @Test
    fun `oversized responses are rejected`() {
        assertFailsWith<IllegalArgumentException> { EditProposalParser.parse(" ".repeat(1_000_001), originals) }
    }
}

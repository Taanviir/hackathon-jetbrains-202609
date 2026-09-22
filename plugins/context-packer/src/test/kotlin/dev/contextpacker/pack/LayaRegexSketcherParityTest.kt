package dev.contextpacker.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Laya path preserves the exact regex sketches used by the frozen evaluation. */
class LayaRegexSketcherParityTest {
    @Test
    fun `laya sketches match frozen evaluation fixture`() {
        val root = Json.parseToJsonElement(
            javaClass.getResource("/fixtures/laya_regex_parity.json")!!.readText(),
        ).jsonObject
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        for (case in cases) {
            val path = case.getValue("path").jsonPrimitive.content
            val text = case.getValue("text").jsonPrimitive.content
            val expected = case.getValue("sketch").jsonPrimitive.content
            assertEquals(path, expected, RegexSketcher.layaSketch(path, text))
        }
    }
}

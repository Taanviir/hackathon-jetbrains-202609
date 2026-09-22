package dev.contextpacker.pack

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The plugin must sketch exactly like the spike did, or it isn't running what was measured. */
class RegexSketcherParityTest {
    @Test
    fun `kotlin sketches match the python sketches the eval used`() {
        val root = Json.parseToJsonElement(javaClass.getResource("/fixtures/regex_parity.json")!!.readText()).jsonObject
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        for (c in cases) {
            val path = c.getValue("path").jsonPrimitive.content
            assertEquals(path, c.getValue("sketch").jsonPrimitive.content, RegexSketcher.sketch(path, c.getValue("text").jsonPrimitive.content))
        }
    }
}

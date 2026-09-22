package dev.contextpacker.pack

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SketcherTest : BasePlatformTestCase() {

    /** Like production: a background thread holding a read action. Kotlin analysis is banned on the EDT. */
    private fun sketchOffEdt(file: PsiFile, path: String, text: String): String =
        ApplicationManager.getApplication().executeOnPooledThread<String> {
            runReadAction { Sketcher.sketch(file, path, text) }
        }.get()

    fun `test kotlin sketch comes from the structure view with doc lines`() {
        val text = """
            package ai.koog.retry

            /** Decides how long to wait between attempts. */
            class RetryPolicy(val maxAttempts: Int) {
                /** Fixed delay between attempts. */
                fun nextDelay(attempt: Int): Long = 500

                private fun jitter(): Long = 0
            }

            fun defaultPolicy(): RetryPolicy = RetryPolicy(3)
        """.trimIndent()
        val file = myFixture.configureByText("RetryPolicy.kt", text)
        val sketch = sketchOffEdt(file, "src/RetryPolicy.kt", text)

        assertTrue(sketch, sketch.startsWith("path: src/RetryPolicy.kt\npackage ai.koog.retry"))
        assertTrue(sketch, "RetryPolicy" in sketch)
        assertTrue(sketch, "nextDelay" in sketch)
        assertTrue(sketch, "defaultPolicy" in sketch)
        assertTrue(sketch, "Decides how long to wait between attempts." in sketch)
        assertTrue(sketch, sketch.length <= Sketcher.MAX_CHARS)
    }

    fun `test files without a structure view fall back to the regex sketcher`() {
        val text = "fun hello() = 1\nclass Greeter"
        val file = myFixture.configureByText(PlainTextFileType.INSTANCE, text)
        val sketch = sketchOffEdt(file, "notes.txt", text)
        assertTrue(sketch, "- fun hello()" in sketch)
        assertTrue(sketch, "- class Greeter" in sketch)
    }
}

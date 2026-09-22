package dev.contextpacker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.contextpacker.pack.Candidates
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

class ContextPackerServiceIntegrationTest : BasePlatformTestCase() {
    private fun <T> offEdt(block: suspend () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread<T> {
            runBlocking { block() }
        }.get(30, TimeUnit.SECONDS)

    fun testKeywordPackReadsUnsavedEditorTextWithoutModelUsage(): Unit {
        val changed = myFixture.addFileToProject("src/ZFresh.java", "class ZFresh { int value = 1; }").virtualFile
        myFixture.addFileToProject("src/ABaseline.java", "class ABaseline { int value = 2; }")
        myFixture.configureFromExistingVirtualFile(changed)
        val marker = "quasarbudget"
        WriteCommandAction.runWriteCommandAction(project, Runnable {
            myFixture.editor.document.setText("class ZFresh { int $marker = 3; }")
        })
        assertFalse("the VFS copy should still be the original source", runReadAction { marker in VfsUtilCore.loadText(changed) })

        val service = project.getService(ContextPackerService::class.java)
        val report = offEdt { service.pack(marker, requestedProvider = DecisionProvider.KEYWORDS) }

        assertEquals(DecisionProvider.KEYWORDS, report.provider)
        assertEquals("src/ZFresh.java", report.result.files.first().path)
        assertEquals(0, report.jevCalls)
        assertEquals(0L, report.inputTokens)
        assertEquals(0, report.cachedRequests)
        assertEquals(0L, report.cachedInputTokens)
        assertEquals(0, report.failedCalls)
        assertEquals(0.0, report.costUsd!!, 0.0)
        assertTrue(offEdt { service.texts(listOf("src/ZFresh.java")) }.getValue("src/ZFresh.java").contains(marker))
    }

    fun testTextAndFileLookupRejectTraversalAndAbsolutePaths(): Unit {
        val source = "class SafeSource { int value = 7; }"
        val file = myFixture.addFileToProject("nested/SafeSource.java", source).virtualFile
        val service = project.getService(ContextPackerService::class.java)
        val valid = "nested/SafeSource.java"
        val rejected = listOf(
            "nested/../SafeSource.java",
            "nested\\..\\SafeSource.java",
            "../nested/SafeSource.java",
            file.path.replace('\\', '/'),
        )

        val resolved = runReadAction { service.fileFor(valid) }
        assertEquals(file.path, resolved?.path)
        assertEquals(source, runReadAction { VfsUtilCore.loadText(resolved!!) })
        rejected.forEach { path ->
            assertNull("fileFor accepted $path", runReadAction { service.fileFor(path) })
        }
        val texts = offEdt { service.texts(listOf(valid) + rejected) }
        assertEquals(mapOf(valid to source), texts)
    }

    fun testManualSourceLookupMatchesPromptReadabilityRules(): Unit {
        val note = "notes/Review.md"
        val noteText = "Context for the selected change."
        myFixture.addFileToProject(note, noteText)
        val binary = myFixture.addFileToProject("assets/image.png", "not actually an image").virtualFile
        val large = myFixture.addFileToProject("src/Huge.java", "class Huge { /* " + "x".repeat(100_001) + " */ }").virtualFile

        val service = project.getService(ContextPackerService::class.java)
        assertTrue(binary.fileType.isBinary)
        assertTrue(large.length > Candidates.MAX_BYTES)
        assertNull(service.sourceProblem(note))
        assertNotNull(service.fileFor(note))
        assertNull(service.fileFor("notes"))
        assertTrue(service.sourceProblem("notes")!!.contains("folder"))
        assertNull(service.fileFor("assets/image.png"))
        assertTrue(service.sourceProblem("assets/image.png")!!.contains("Binary"))
        assertNull(service.fileFor("src/Huge.java"))
        assertTrue(service.sourceProblem("src/Huge.java")!!.contains("source limit"))

        assertEquals(
            mapOf(note to noteText),
            offEdt { service.texts(listOf(note, "notes", "assets/image.png", "src/Huge.java")) },
        )
    }

    fun testTypingPreviewUsesNoModelAndPreservesExplicitPackForEveryProvider(): Unit {
        myFixture.addFileToProject("src/RetryPolicy.java", "class RetryPolicy { void retryBackoff() {} }")
        val service = project.getService(ContextPackerService::class.java)
        val previous = offEdt { service.pack("retry backoff", requestedProvider = DecisionProvider.KEYWORDS) }
        val saved = service.provider
        try {
            for (selected in DecisionProvider.entries) {
                service.provider = selected
                val report = offEdt { service.preview("update retry backoff") }
                assertEquals(DecisionProvider.KEYWORDS, report.provider)
                assertTrue(report.result.preview)
                assertEquals("src/RetryPolicy.java", report.result.files.first().path)
                assertEquals(0, report.jevCalls)
                assertEquals(0L, report.inputTokens)
                assertEquals(0L, service.sessionTokens)
                assertEquals(0.0, report.costUsd!!, 0.0)
                assertEquals(selected, service.provider)
                assertSame(previous, service.lastReport)
            }
        } finally {
            service.provider = saved
        }
    }
}

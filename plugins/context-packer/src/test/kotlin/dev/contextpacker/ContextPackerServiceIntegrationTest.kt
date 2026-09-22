package dev.contextpacker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
}

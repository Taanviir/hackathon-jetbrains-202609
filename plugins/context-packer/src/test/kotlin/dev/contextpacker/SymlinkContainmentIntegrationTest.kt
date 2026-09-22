package dev.contextpacker

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import dev.contextpacker.pack.Candidates
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class SymlinkContainmentIntegrationTest : BasePlatformTestCase() {
    protected override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private fun <T> offEdt(block: suspend () -> T): T =
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<T> {
            runBlocking { block() }
        }.get(30, TimeUnit.SECONDS)

    private fun withLink(toDirectory: Boolean, verify: (String, VirtualFile, VirtualFile) -> Unit) {
        val root = Path.of(project.basePath ?: error("Project has no base path"))
        assertTrue("The test fixture must have a real local project directory", Files.isDirectory(root))
        val controlName = "Control${UUID.randomUUID().toString().replace("-", "")}.java"
        val controlText = "class NormalControl { int value = 1; }"
        val control = myFixture.addFileToProject(controlName, controlText).virtualFile
        assertTrue("The control must be on the local filesystem", Files.isRegularFile(root.resolve(controlName)))
        val service = project.getService(ContextPackerService::class.java)
        assertTrue(runReadAction { Candidates.isInsideProjectWithoutLinks(project.guessProjectDir()!!, control) })
        assertEquals(control.path, runReadAction { service.fileFor(controlName) }?.path)
        assertEquals(controlText, offEdt { service.texts(listOf(controlName)) }[controlName])
        val outside = Files.createTempDirectory(root.parent, "context-packer-outside-")
        val source = outside.resolve("OutsideSource.java")
        val name = "linked-${UUID.randomUUID()}" + if (toDirectory) "" else ".java"
        val link = root.resolve(name)
        try {
            Files.writeString(source, "class OutsideSource { String confidentialMarker; }")
            try {
                Files.createSymbolicLink(link, if (toDirectory) outside else source)
            } catch (e: IOException) {
                Assume.assumeNoException(e)
            } catch (e: UnsupportedOperationException) {
                Assume.assumeNoException(e)
            } catch (e: SecurityException) {
                Assume.assumeNoException(e)
            }
            val virtualLink = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(link.toString().replace('\\', '/'))
            assertNotNull("The VFS must expose the local link for this test", virtualLink)
            assertTrue("The VFS must identify the link", virtualLink!!.`is`(VFileProperty.SYMLINK))
            val base = project.guessProjectDir() ?: error("Project has no VFS root")
            val relative = if (toDirectory) "$name/OutsideSource.java" else name
            verify(relative, base, virtualLink)
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(source)
            Files.deleteIfExists(outside)
        }
    }

    fun testLinkedSourceFileCannotBeScoredOrRead(): Unit = withLink(false) { relative, base, link ->
        val service = project.getService(ContextPackerService::class.java)
        assertFalse(runReadAction { Candidates.isInsideProjectWithoutLinks(base, link) })
        assertFalse(runReadAction { Candidates.collect(project).any { it.path == link.path } })
        assertNull(runReadAction { service.fileFor(relative) })
        assertTrue(offEdt { service.texts(listOf(relative)) }.isEmpty())
    }

    fun testLinkedDirectoryCannotExposeItsDescendants(): Unit = withLink(true) { relative, base, link ->
        val service = project.getService(ContextPackerService::class.java)
        assertFalse(runReadAction { Candidates.isInsideProjectWithoutLinks(base, link) })
        runReadAction { link.findChild("OutsideSource.java") }?.let { child ->
            assertFalse(runReadAction { Candidates.isInsideProjectWithoutLinks(base, child) })
        }
        assertFalse(runReadAction { Candidates.collect(project).any { it.path.endsWith(relative) } })
        assertNull(runReadAction { service.fileFor(relative) })
        assertTrue(offEdt { service.texts(listOf(relative)) }.isEmpty())
    }
}

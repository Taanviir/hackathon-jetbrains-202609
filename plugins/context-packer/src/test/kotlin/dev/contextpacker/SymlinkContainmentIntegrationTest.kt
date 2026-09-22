package dev.contextpacker

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.testFramework.ApplicationRule
import dev.contextpacker.pack.Candidates
import org.junit.Assume
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Exercise the shared containment helper with real local VFS paths, including physical links. */
class SymlinkContainmentIntegrationTest {
    @Rule @JvmField val application = ApplicationRule()

    private fun localFile(path: Path) = LocalFileSystem.getInstance()
        .refreshAndFindFileByPath(path.toString().replace('\\', '/'))

    private fun checkLink(toDirectory: Boolean) {
        val root = Files.createTempDirectory("context-packer-root-")
        val outside = Files.createTempDirectory("context-packer-outside-")
        val control = root.resolve("NormalControl.java")
        val source = outside.resolve("OutsideSource.java")
        val link = root.resolve(if (toDirectory) "linked" else "linked.java")
        try {
            Files.writeString(control, "class NormalControl { int value = 1; }")
            Files.writeString(source, "class OutsideSource { String confidentialMarker; }")
            val baseFile = localFile(root)
            val controlFile = localFile(control)
            assertNotNull("The VFS must expose the local project directory", baseFile)
            assertNotNull("The VFS must expose the normal source", controlFile)
            assertTrue(runReadAction {
                Candidates.isInsideProjectWithoutLinks(baseFile!!, controlFile!!)
            })

            try {
                Files.createSymbolicLink(link, if (toDirectory) outside else source)
            } catch (e: IOException) {
                Assume.assumeNoException(e)
            } catch (e: UnsupportedOperationException) {
                Assume.assumeNoException(e)
            } catch (e: SecurityException) {
                Assume.assumeNoException(e)
            }
            val linkFile = localFile(link)
            assertNotNull("The VFS must expose the local link", linkFile)
            assertTrue("The VFS must identify the link", linkFile!!.`is`(VFileProperty.SYMLINK))
            assertFalse(runReadAction { Candidates.isInsideProjectWithoutLinks(baseFile!!, linkFile) })

            if (toDirectory) {
                val child = localFile(link.resolve("OutsideSource.java"))
                assertNotNull("The VFS must expose the linked directory's source", child)
                assertFalse(runReadAction { Candidates.isInsideProjectWithoutLinks(baseFile!!, child!!) })
            }
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(source)
            Files.deleteIfExists(control)
            Files.deleteIfExists(outside)
            Files.deleteIfExists(root)
        }
    }

    @Test fun testLinkedSourceFileIsOutsideTheProject(): Unit = checkLink(false)

    @Test fun testLinkedDirectoryAndDescendantAreOutsideTheProject(): Unit = checkLink(true)
}

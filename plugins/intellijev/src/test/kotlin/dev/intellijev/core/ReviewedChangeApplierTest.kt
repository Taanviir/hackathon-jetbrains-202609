package dev.intellijev.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class ReviewedChangeApplierTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = Path.of("src", "test").toAbsolutePath().toString()

    fun testAppliesReviewedSnapshotAsAnUndoableCommand() {
        val file = myFixture.configureByText("Review.txt", "before").virtualFile
        val result = ReviewedChangeApplier.apply(project, ProposedChange(file, "before", "after", "summary"))

        assertTrue(result is ApplyChangeResult.Applied)
        val document = (result as ApplyChangeResult.Applied).document
        assertEquals("after", document.text)
        FileDocumentManager.getInstance().saveDocument(document)

        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue("The replacement should be available through standard Undo", undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals("before", document.text)
    }

    fun testRefusesChangedSourceWithoutOverwritingIt() {
        val file = myFixture.configureByText("Review.txt", "before").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project, Runnable { document.setText("newer user edit") })

        val result = ReviewedChangeApplier.apply(project, ProposedChange(file, "before", "after", "summary"))

        assertSame(ApplyChangeResult.Stale, result)
        assertEquals("newer user edit", document.text)
    }

    fun testReadOnlyDocumentIsUnavailable() {
        val file = myFixture.configureByText("Review.txt", "before").virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        document.setReadOnly(true)
        try {
            assertSame(
                ApplyChangeResult.Unavailable,
                ReviewedChangeApplier.apply(project, ProposedChange(file, "before", "after", "summary")),
            )
            assertEquals("before", document.text)
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testDeletedAndDirectoryFilesAreUnavailable() {
        val file = myFixture.addFileToProject("nested/Review.txt", "before").virtualFile
        val directory = file.parent
        assertSame(
            ApplyChangeResult.Unavailable,
            ReviewedChangeApplier.apply(project, ProposedChange(directory, "before", "after", "summary")),
        )

        WriteCommandAction.runWriteCommandAction(project, Runnable { file.delete(this) })
        assertFalse(file.isValid)
        assertSame(
            ApplyChangeResult.Unavailable,
            ReviewedChangeApplier.apply(project, ProposedChange(file, "before", "after", "summary")),
        )
    }
}

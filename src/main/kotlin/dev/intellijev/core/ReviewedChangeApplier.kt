package dev.intellijev.core

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

internal sealed interface ApplyChangeResult {
    data class Applied(val document: Document) : ApplyChangeResult
    data object Stale : ApplyChangeResult
    data object Unavailable : ApplyChangeResult
}

/** Applies only the reviewed source snapshot. The check and replacement share one undoable command. */
internal object ReviewedChangeApplier {
    fun apply(project: Project, change: ProposedChange): ApplyChangeResult {
        if (project.isDisposed) return ApplyChangeResult.Unavailable
        var result: ApplyChangeResult = ApplyChangeResult.Unavailable
        WriteCommandAction.runWriteCommandAction(project, "Apply IntelliJev proposal", null, Runnable {
            if (!change.file.isValid || change.file.isDirectory) return@Runnable
            val document = FileDocumentManager.getInstance().getDocument(change.file) ?: return@Runnable
            if (!change.file.isWritable || !document.isWritable) return@Runnable
            if (document.text != change.before) {
                result = ApplyChangeResult.Stale
            } else {
                document.setText(change.after)
                result = ApplyChangeResult.Applied(document)
            }
        })
        return result
    }
}

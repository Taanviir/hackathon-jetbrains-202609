package dev.intellijev.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager
import dev.intellijev.ui.IntelliJevPanel

open class OpenPanelAction : AnAction() {
    protected fun withPanel(e: AnActionEvent, action: (IntelliJevPanel) -> Unit) {
        val project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("IntelliJev") ?: return
        window.show(Runnable {
            window.contentManager.contents.asSequence().mapNotNull { it.component as? IntelliJevPanel }
                .firstOrNull()?.let(action)
        })
    }

    override fun actionPerformed(e: AnActionEvent) = withPanel(e) { }
}
class FindContextAction : OpenPanelAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val selection = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        withPanel(e) { it.findContextFromSelection(selection) }
    }
}
class FindBugTwinsAction : OpenPanelAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText.isNullOrBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: return
        val start = editor.selectionModel.selectionStart
        val end = (editor.selectionModel.selectionEnd - 1).coerceAtLeast(start)
        val lines = editor.document.getLineNumber(start)..editor.document.getLineNumber(end)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        withPanel(e) { it.findRelatedCodeFromSelection(selection, file, lines) }
    }
}

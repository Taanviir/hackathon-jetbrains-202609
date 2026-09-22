package dev.intellijev.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

open class OpenPanelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("IntelliJev")?.show() } }
}
class FindContextAction : OpenPanelAction() {
    override fun actionPerformed(e: AnActionEvent) { super.actionPerformed(e) }
}
class FindBugTwinsAction : OpenPanelAction() {
    override fun actionPerformed(e: AnActionEvent) { super.actionPerformed(e) }
}

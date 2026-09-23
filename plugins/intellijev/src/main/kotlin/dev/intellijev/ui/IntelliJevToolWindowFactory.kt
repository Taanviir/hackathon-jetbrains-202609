package dev.intellijev.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.contextpacker.ui.PackerPanel

class IntelliJevToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = IntelliJevPanel(project)
        val packer = PackerPanel(project) { task, files ->
            if (panel.acceptPackedContext(task, files)) {
                toolWindow.contentManager.setSelectedContent(
                    toolWindow.contentManager.contents.first { it.component === panel },
                )
            }
        }
        val context = ContentFactory.getInstance().createContent(packer, "Find context", false)
        context.setDisposer(packer)
        toolWindow.contentManager.addContent(context)

        val content = ContentFactory.getInstance().createContent(panel, "Review changes", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

package dev.contextpacker.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import dev.contextpacker.Keys

/** Stores keys in the IDE's password store, for IDEs launched without the env vars. Empty skips a key. */
class SetKeysAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        for (key in Keys.entries) {
            val value = Messages.showPasswordDialog(
                e.project, "${key.envVar} (leave empty to keep the current value)", "Context Packer", null,
            ) ?: return
            if (value.isNotBlank()) ApplicationManager.getApplication().executeOnPooledThread { key.store(value) }
        }
    }
}

package dev.contextpacker

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Sketches every file once the project has indexed, so the first pack is as quick as later ones. No Jev calls. */
class WarmUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ContextPackerService>().warm()
    }
}

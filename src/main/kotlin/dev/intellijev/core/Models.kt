package dev.intellijev.core

import com.intellij.openapi.vfs.VirtualFile

enum class ContextRole(val label: String) { EDIT_TARGET("Edit target"), EXAMPLE("Example"), CONSTRAINT("Constraint"), DEFINITION("Definition") }

data class ContextCandidate(val file: VirtualFile, val role: ContextRole, val score: Int, val reason: String)
data class BugCandidate(val file: VirtualFile, val line: Int, val symbol: String, val evidence: String)
data class RunEvent(val time: String, val message: String)
data class ProposedChange(val file: VirtualFile, val before: String, val after: String, val summary: String)
data class AgentProposal(val summary: String, val changes: List<ProposedChange>)

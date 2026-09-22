package dev.intellijev.core

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/** A project-local note kept in the IDE workspace file, outside source-controlled plugin settings. */
@Service(Service.Level.PROJECT)
@State(name = "IntelliJevSideQuestion", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SideQuestionState : PersistentStateComponent<SideQuestionState.Data> {
    data class Data(var note: String = "")

    private var data = Data()

    override fun getState(): Data = data
    override fun loadState(state: Data) { data = state }

    fun note(): String = data.note

    fun save(note: String) {
        require(note.length <= MAX_CHARS) { "Side question must be $MAX_CHARS characters or fewer." }
        data.note = note
    }

    companion object { const val MAX_CHARS = 20_000 }
}

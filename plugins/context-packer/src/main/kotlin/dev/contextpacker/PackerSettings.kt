package dev.contextpacker

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

enum class DecisionProvider(val label: String) {
    JEV("Jev (API)"), LAYA("Laya (local)"), KEYWORDS("Fast keywords (local)");
    override fun toString() = label
}

/** Only provider preferences go in project settings; credentials stay in PasswordSafe. */
@Service(Service.Level.PROJECT)
@State(name = "ContextPackerSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PackerSettings : PersistentStateComponent<PackerSettings.Preferences> {
    data class Preferences(var provider: String = "")
    private var preferences = Preferences()
    override fun getState() = preferences
    override fun loadState(state: Preferences) { preferences = state }

    var provider: DecisionProvider
        get() = when (preferences.provider.ifEmpty { System.getenv("CONTEXT_PACKER_PROVIDER").orEmpty() }.lowercase()) {
            "laya" -> DecisionProvider.LAYA
            "keywords", "bm25" -> DecisionProvider.KEYWORDS
            else -> DecisionProvider.JEV
        }
        set(value) { preferences.provider = value.name.lowercase() }
}

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

/** Independent project profiles; credentials stay in PasswordSafe, never in this state. */
@Service(Service.Level.PROJECT)
@State(name = "ContextPackerSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PackerSettings : PersistentStateComponent<PackerSettings.Preferences> {
    data class Preferences(
        var provider: String = "",
        var jev: JevProfile = JevProfile(),
        var laya: LayaProfile = LayaProfile(),
    )
    @Volatile private var preferences = Preferences()
    @Volatile var revision: Long = 0
        private set
    private fun copyOf(state: Preferences) = state.copy(jev = state.jev.copy(), laya = state.laya.copy())
    override fun getState() = copyOf(preferences)
    override fun loadState(state: Preferences) {
        // A damaged profile must not prevent opening settings or reset the other provider.
        val copy = copyOf(state)
        if (runCatching { ProviderConfiguration.resolve(DecisionProvider.JEV, copy.jev, copy.laya, emptyMap()) }.isFailure) copy.jev = JevProfile()
        if (runCatching { ProviderConfiguration.resolve(DecisionProvider.LAYA, copy.jev, copy.laya, emptyMap()) }.isFailure) copy.laya = LayaProfile()
        preferences = copy
        revision++
    }

    fun configuration(provider: DecisionProvider = this.provider): ProviderConfiguration {
        val state = preferences
        return ProviderConfiguration.resolve(provider, state.jev, state.laya)
    }

    fun editablePreferences(): Preferences {
        val copy = copyOf(preferences)
        copy.provider = provider.name.lowercase()
        // Blank connection fields intentionally inherit the launcher environment.
        // Opening or saving another profile must not persist their effective values.
        return copy
    }

    fun validatePreferences(state: Preferences) {
        require(state.provider.lowercase() in setOf("jev", "laya", "keywords", "bm25", "")) { "Choose a supported provider." }
        // Validate stored overrides independently of inherited launcher values. Runtime resolution
        // validates the effective connection, without blocking edits to the other provider.
        ProviderConfiguration.resolve(DecisionProvider.JEV, state.jev, state.laya, emptyMap())
        ProviderConfiguration.resolve(DecisionProvider.LAYA, state.jev, state.laya, emptyMap())
    }

    fun applyPreferences(state: Preferences) {
        val copy = copyOf(state)
        validatePreferences(copy)
        preferences = copy
        revision++
    }

    var provider: DecisionProvider
        get() = when (preferences.provider.ifEmpty { System.getenv("CONTEXT_PACKER_PROVIDER").orEmpty() }.lowercase()) {
            "laya" -> DecisionProvider.LAYA
            "keywords", "bm25" -> DecisionProvider.KEYWORDS
            else -> DecisionProvider.JEV
        }
        set(value) {
            if (provider == value && preferences.provider.isNotEmpty()) return
            preferences = copyOf(preferences).apply { provider = value.name.lowercase() }
            revision++
        }
}

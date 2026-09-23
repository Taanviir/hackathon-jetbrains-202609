package dev.intellijev.core

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.OneTimeString
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "IntelliJevSettings", storages = [Storage("intellijev.xml")])
class IntelliJevSettings : PersistentStateComponent<IntelliJevSettings.State> {
    data class State(var provider: String = "OpenRouter", var model: String = "openai/gpt-4.1-mini")
    private var state = State()
    private val openRouterKey = CredentialAttributes("IntelliJev.OpenRouter.ApiKey")
    private val openAiKey = CredentialAttributes("IntelliJev.OpenAI.ApiKey")
    private val typeSafeKey = CredentialAttributes("IntelliJev.TypeSafe.ApiKey")

    override fun getState() = state
    override fun loadState(state: State) { this.state = state }
    fun model() = state.model
    fun provider() = state.provider
    fun generationKey(): String? = PasswordSafe.instance.get(if (state.provider == "OpenAI") openAiKey else openRouterKey)?.getPasswordAsString()
    fun jevKey(): String? = PasswordSafe.instance.get(typeSafeKey)?.getPasswordAsString()
    fun save(provider: String, model: String, generationKey: String, jevKey: String) {
        state.provider = provider
        state.model = model.trim()
        if (generationKey.isNotBlank()) PasswordSafe.instance.set(if (provider == "OpenAI") openAiKey else openRouterKey, Credentials(provider, OneTimeString(generationKey.trim())))
        if (jevKey.isNotBlank()) PasswordSafe.instance.set(typeSafeKey, Credentials("typesafe", OneTimeString(jevKey.trim())))
    }

    companion object { fun instance(): IntelliJevSettings = ApplicationManager.getApplication().getService(IntelliJevSettings::class.java) }
}

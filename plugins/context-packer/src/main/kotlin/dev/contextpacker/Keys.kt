package dev.contextpacker

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API keys: an environment variable wins (runIde passes the repo's .env through), otherwise the
 * IDE's password store, which is where "Set API Keys" puts them.
 */
enum class Keys(val envVar: String) {
    /** Vercel AI Gateway, preferred for Jev when set. */
    GATEWAY("AI_GATEWAY_API_KEY"),
    TYPESAFE("TYPESAFE_API_KEY"),
    /** Only for the text-writing LLM. Jev never goes through OpenRouter. */
    OPENROUTER("OPENROUTER_API_KEY");

    private val attributes get() = CredentialAttributes(generateServiceName("Context Packer", envVar))

    /** Reads the password store, which can block. Call off the EDT. */
    fun get(): String? = System.getenv(envVar)?.trim()?.ifEmpty { null }
        ?: PasswordSafe.instance.getPassword(attributes)?.trim()?.ifEmpty { null }

    fun store(value: String?) {
        PasswordSafe.instance.set(attributes, value?.trim()?.ifEmpty { null }?.let { Credentials(envVar, it) })
    }
}

class MissingKeyException(vararg keys: Keys) : IllegalStateException(
    keys.joinToString(" or ") { it.envVar } + " is not set. Use Tools | Context Packer: Set API Keys, or export it.",
)

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
    TYPESAFE("TYPESAFE_API_KEY"),
    OPENROUTER("OPENROUTER_API_KEY");

    private val attributes get() = CredentialAttributes(generateServiceName("Context Packer", envVar))

    /** Reads the password store, which can block. Call off the EDT. */
    fun get(): String? = System.getenv(envVar)?.trim()?.ifEmpty { null }
        ?: PasswordSafe.instance.getPassword(attributes)?.trim()?.ifEmpty { null }

    fun store(value: String?) {
        PasswordSafe.instance.set(attributes, value?.trim()?.ifEmpty { null }?.let { Credentials(envVar, it) })
    }
}

class MissingKeyException(key: Keys) :
    IllegalStateException("${key.envVar} is not set. Use Tools | Context Packer: Set API Keys, or export it.")

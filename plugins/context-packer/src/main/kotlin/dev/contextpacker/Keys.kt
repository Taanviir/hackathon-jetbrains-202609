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
    /** Vercel AI Gateway: same Jev, but heavily rate-limited. Used when no TypeSafe key is set. */
    GATEWAY("AI_GATEWAY_API_KEY"),
    /** Only for the text-writing LLM. Jev never goes through OpenRouter. */
    OPENROUTER("OPENROUTER_API_KEY");

    private val attributes get() = CredentialAttributes(generateServiceName("Context Packer", envVar))
    private val unifiedAttributes get() = when (this) {
        TYPESAFE -> CredentialAttributes("IntelliJev.TypeSafe.ApiKey")
        OPENROUTER -> CredentialAttributes("IntelliJev.OpenRouter.ApiKey")
        GATEWAY -> null
    }

    /** Reads the password store, which can block. Call off the EDT. */
    fun get(): String? = System.getenv(envVar)?.trim()?.ifEmpty { null }
        ?: unifiedAttributes?.let { PasswordSafe.instance.getPassword(it)?.trim()?.ifEmpty { null } }
        ?: PasswordSafe.instance.getPassword(attributes)?.trim()?.ifEmpty { null }

    fun store(value: String?) {
        val credential = value?.trim()?.ifEmpty { null }?.let { Credentials(envVar, it) }
        PasswordSafe.instance.set(attributes, credential)
        unifiedAttributes?.let { PasswordSafe.instance.set(it, credential) }
    }
}

class MissingKeyException(vararg keys: Keys) : IllegalStateException(
    keys.joinToString(" or ") { it.envVar } + " is not set. Use Tools | IntelliJev: Set API Keys, or export it.",
)

class BudgetExceededException(spent: Long, budget: Long) : IllegalStateException(
    "Jev session threshold reached (%,d of %,d reported input tokens). Set CONTEXT_PACKER_TOKEN_BUDGET to raise it."
        .format(spent, budget),
)

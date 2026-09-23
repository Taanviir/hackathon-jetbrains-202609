package dev.contextpacker

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API keys: an environment variable wins (runIde passes the repo's .env through), otherwise the
 * IDE's password store, which is where "Set API Keys" puts them.
 */
enum class Keys(val envVar: String, slot: String) {
    TYPESAFE("TYPESAFE_API_KEY", "IntelliJev.TypeSafe.ApiKey"),
    /** Vercel AI Gateway: same Jev, but heavily rate-limited. Used when no TypeSafe key is set. */
    GATEWAY("AI_GATEWAY_API_KEY", "IntelliJev.Gateway.ApiKey"),
    /** Only for the text-writing LLM. Jev never goes through OpenRouter. */
    OPENROUTER("OPENROUTER_API_KEY", "IntelliJev.OpenRouter.ApiKey");

    /** Shared with IntelliJev's Settings tab, so a key entered in either place works in both. */
    private val attributes = CredentialAttributes(slot)

    /** Reads the password store, which can block. Call off the EDT. */
    fun get(): String? = System.getenv(envVar)?.trim()?.ifEmpty { null }
        ?: PasswordSafe.instance.getPassword(attributes)?.trim()?.ifEmpty { null }

    fun store(value: String?) {
        PasswordSafe.instance.set(attributes, value?.trim()?.ifEmpty { null }?.let { Credentials(envVar, it) })
    }
}

class MissingKeyException(vararg keys: Keys) : IllegalStateException(
    keys.joinToString(" or ") { it.envVar } + " is not set. Use Tools | IntelliJev: Set API Keys, or export it.",
)

class BudgetExceededException(spent: Long, budget: Long) : IllegalStateException(
    "Jev session threshold reached (%,d of %,d reported input tokens). Set CONTEXT_PACKER_TOKEN_BUDGET to raise it."
        .format(spent, budget),
)

package dev.contextpacker

import dev.contextpacker.laya.LayaRelevance
import dev.contextpacker.pack.PackConfig
import java.net.URI

/** Persisted controls are separate: changing one provider never rewrites the other. */
data class JevProfile(
    var backend: String = "",
    var batch: Int = 60,
    var pool: Int = 60,
    var perCall: Int = 6,
    var fullChars: Int = 6_000,
    var stage3K: Int = 10,
    var compareTop: Boolean = true,
    var assignRoles: Boolean = true,
    var timeoutSeconds: Int = 30,
)

data class LayaProfile(
    var endpoint: String = "",
    var model: String = "",
    var maxCandidates: Int = 60,
    var pool: Int = 20,
    var fullChars: Int = 1_000,
    var timeoutSeconds: Int = 90,
)

/** Immutable settings captured once per explicit pack, including MCP overrides. */
data class ProviderConfiguration(
    val provider: DecisionProvider,
    val taskLimit: Int,
    val pack: PackConfig,
    val maxCandidates: Int? = null,
    val jevBackend: String = "auto",
    val timeoutSeconds: Int = 30,
    val layaEndpoint: String = "",
    val layaModel: String = "",
    val compareTop: Boolean = false,
    val assignRoles: Boolean = false,
) {
    val description: String get() = when (provider) {
        DecisionProvider.JEV -> "Jev: task ≤ $taskLimit characters; ${pack.fullChars} source characters/file; " +
            "${pack.batch} sketches/call, ${pack.perCall} source files/call; all eligible candidates."
        DecisionProvider.LAYA -> "Laya $layaModel: task ≤ $taskLimit characters; ${pack.fullChars} excerpt characters/file (including path metadata); " +
            "up to $maxCandidates candidates; one file per sequential request."
        DecisionProvider.KEYWORDS -> "Fast keywords: task ≤ $taskLimit characters; full eligible source; no model or API key."
    }

    companion object {
        const val DEFAULT_LAYA_ENDPOINT = "http://127.0.0.1:8770/api/predict"

        fun resolve(
            provider: DecisionProvider,
            jev: JevProfile,
            laya: LayaProfile,
            environment: Map<String, String> = System.getenv(),
        ): ProviderConfiguration = when (provider) {
            DecisionProvider.KEYWORDS -> ProviderConfiguration(provider, 8_000, PackConfig())
            DecisionProvider.JEV -> {
                val backend = jev.backend.trim().lowercase().ifEmpty {
                    if (environment["JEV_BACKEND"]?.equals("gateway", true) == true) "gateway" else "auto"
                }
                require(backend in setOf("auto", "typesafe", "gateway")) { "Choose Auto, TypeSafe or Gateway for Jev." }
                require(jev.batch in 1..100) { "Jev sketches per call must be between 1 and 100." }
                require(jev.pool in 1..200) { "Jev pool size must be between 1 and 200." }
                require(jev.perCall in 1..20) { "Jev source files per call must be between 1 and 20." }
                require(jev.fullChars in 1..32_000) { "Jev source characters per file must be between 1 and 32,000." }
                require(jev.stage3K in 1..20) { "Jev top comparison size must be between 1 and 20." }
                require(jev.timeoutSeconds in 1..300) { "Jev request timeout must be between 1 and 300 seconds." }
                // A request-size guard, not a claim that characters equal the model's token window.
                val widestSourceCall = maxOf(jev.perCall, if (jev.compareTop || jev.assignRoles) jev.stage3K else 1)
                require(jev.fullChars.toLong() * widestSourceCall <= 96_000L) {
                    "Jev source text per request must stay within 96,000 characters. Reduce source length or files per call/top comparison."
                }
                ProviderConfiguration(
                    provider, 8_000,
                    PackConfig(batch = jev.batch, pool = jev.pool, perCall = jev.perCall,
                        fullChars = jev.fullChars, stage3K = jev.stage3K),
                    jevBackend = backend, timeoutSeconds = jev.timeoutSeconds,
                    compareTop = jev.compareTop, assignRoles = jev.assignRoles,
                )
            }
            DecisionProvider.LAYA -> {
                val endpoint = laya.endpoint.trim().ifEmpty {
                    environment["CONTEXT_PACKER_LAYA_URL"]?.trim().orEmpty().ifEmpty { DEFAULT_LAYA_ENDPOINT }
                }
                val model = laya.model.trim().ifEmpty {
                    environment["CONTEXT_PACKER_LAYA_MODEL"]?.trim().orEmpty().ifEmpty { "english" }
                }
                val uri = try { URI(endpoint) } catch (_: Exception) { null }
                require(uri != null && uri.scheme == "http" &&
                    uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1") &&
                    uri.userInfo == null && uri.fragment == null && uri.port != 0 && uri.port <= 65535) {
                    "Laya needs a local HTTP endpoint, e.g. $DEFAULT_LAYA_ENDPOINT (no credentials or fragment)."
                }
                require(model.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Laya model ID must contain 1–64 letters, digits, dots, underscores or hyphens." }
                require(laya.maxCandidates in 1..500) { "Laya candidate shortlist must be between 1 and 500." }
                require(laya.pool in 1..laya.maxCandidates) { "Laya pool size must be between 1 and its candidate shortlist size." }
                require(laya.fullChars in 1..LayaRelevance.MAX_EXCERPT_CHARS) {
                    "The Laya adapter supports 1–${LayaRelevance.MAX_EXCERPT_CHARS} excerpt characters per file."
                }
                require(laya.timeoutSeconds in 1..600) { "Laya request timeout must be between 1 and 600 seconds." }
                ProviderConfiguration(
                    provider, LayaRelevance.MAX_TASK_CHARS,
                    PackConfig(batch = 1, pool = laya.pool, perCall = 1, fullChars = laya.fullChars,
                        overlapPasses = false, requireFullSourceScores = true),
                    maxCandidates = laya.maxCandidates, timeoutSeconds = laya.timeoutSeconds,
                    layaEndpoint = endpoint, layaModel = model,
                )
            }
        }
    }
}

package dev.contextpacker

import dev.contextpacker.pack.PackConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigurationTest {
    @Test
    fun `default profiles keep the measured Jev and Laya configurations distinct`() {
        val jev = ProviderConfiguration.resolve(DecisionProvider.JEV, JevProfile(), LayaProfile(), emptyMap())
        assertEquals(8_000, jev.taskLimit)
        assertEquals(PackConfig(), jev.pack)
        assertEquals("auto", jev.jevBackend)
        assertEquals(30, jev.timeoutSeconds)
        assertTrue(jev.compareTop)
        assertTrue(jev.assignRoles)
        assertEquals(null, jev.maxCandidates)

        val laya = ProviderConfiguration.resolve(DecisionProvider.LAYA, JevProfile(), LayaProfile(), emptyMap())
        assertEquals(500, laya.taskLimit)
        assertEquals(60, laya.maxCandidates)
        assertEquals(1, laya.pack.batch)
        assertEquals(20, laya.pack.pool)
        assertEquals(1, laya.pack.perCall)
        assertEquals(1_000, laya.pack.fullChars)
        assertFalse(laya.pack.overlapPasses)
        assertTrue(laya.pack.requireFullSourceScores)
        assertFalse(laya.compareTop)
        assertFalse(laya.assignRoles)
        assertEquals(90, laya.timeoutSeconds)
        assertEquals(ProviderConfiguration.DEFAULT_LAYA_ENDPOINT, laya.layaEndpoint)
        assertEquals("english", laya.layaModel)
    }

    @Test
    fun `blank profile connection fields resolve environment without changing saved defaults`() {
        val environment = mapOf(
            "JEV_BACKEND" to "gateway",
            "CONTEXT_PACKER_LAYA_URL" to "http://127.0.0.1:9900/api/predict",
            "CONTEXT_PACKER_LAYA_MODEL" to "english-v2",
        )
        val jevProfile = JevProfile()
        val layaProfile = LayaProfile()
        val jev = ProviderConfiguration.resolve(DecisionProvider.JEV, jevProfile, layaProfile, environment)
        val laya = ProviderConfiguration.resolve(DecisionProvider.LAYA, jevProfile, layaProfile, environment)
        assertEquals("gateway", jev.jevBackend)
        assertEquals("http://127.0.0.1:9900/api/predict", laya.layaEndpoint)
        assertEquals("english-v2", laya.layaModel)
        assertEquals("", jevProfile.backend)
        assertEquals("", layaProfile.endpoint)
        assertEquals("", layaProfile.model)
    }

    @Test
    fun `editing Jev knobs does not freeze inherited provider connection values`() {
        val settings = PackerSettings()
        settings.loadState(PackerSettings.Preferences(provider = "jev"))
        val untouchedDraft = settings.editablePreferences()
        assertEquals("", untouchedDraft.jev.backend)
        assertEquals("", untouchedDraft.laya.endpoint)
        assertEquals("", untouchedDraft.laya.model)
        settings.applyPreferences(untouchedDraft)

        val changedDraft = settings.editablePreferences()
        changedDraft.jev.batch = 48
        settings.applyPreferences(changedDraft)
        val saved = settings.getState()
        assertEquals(48, saved.jev.batch)
        assertEquals("", saved.jev.backend)
        assertEquals("", saved.laya.endpoint)
        assertEquals("", saved.laya.model)

        val environment = mapOf(
            "JEV_BACKEND" to "gateway",
            "CONTEXT_PACKER_LAYA_URL" to "http://127.0.0.1:9900/api/predict",
            "CONTEXT_PACKER_LAYA_MODEL" to "english-v2",
        )
        assertEquals("gateway", ProviderConfiguration.resolve(
            DecisionProvider.JEV, saved.jev, saved.laya, environment).jevBackend)
        val laya = ProviderConfiguration.resolve(DecisionProvider.LAYA, saved.jev, saved.laya, environment)
        assertEquals("http://127.0.0.1:9900/api/predict", laya.layaEndpoint)
        assertEquals("english-v2", laya.layaModel)
    }

    @Test
    fun `independent profiles survive switching and persistence without aliasing`() {
        val settings = PackerSettings()
        val draft = PackerSettings.Preferences(
            provider = "jev",
            jev = JevProfile(backend = "typesafe", batch = 42, pool = 55, compareTop = false),
            laya = LayaProfile(endpoint = ProviderConfiguration.DEFAULT_LAYA_ENDPOINT,
                model = "english", maxCandidates = 34, pool = 12),
        )
        settings.applyPreferences(draft)
        val savedRevision = settings.revision
        draft.jev.batch = 1
        draft.laya.maxCandidates = 1
        assertEquals(42, settings.configuration(DecisionProvider.JEV).pack.batch)
        assertEquals(34, settings.configuration(DecisionProvider.LAYA).maxCandidates)

        settings.provider = DecisionProvider.LAYA
        assertEquals(12, settings.configuration().pack.pool)
        settings.provider = DecisionProvider.JEV
        assertEquals(42, settings.configuration().pack.batch)
        assertEquals(savedRevision + 2, settings.revision)

        val persisted = settings.getState()
        val restored = PackerSettings().apply { loadState(persisted) }
        persisted.jev.batch = 2
        persisted.laya.pool = 1
        assertEquals(42, restored.configuration(DecisionProvider.JEV).pack.batch)
        assertEquals(12, restored.configuration(DecisionProvider.LAYA).pack.pool)
        assertEquals(DecisionProvider.JEV, restored.provider)
    }

    @Test
    fun `configuration snapshots and editable drafts cannot change saved settings`() {
        val settings = PackerSettings()
        settings.applyPreferences(PackerSettings.Preferences(
            provider = "jev",
            jev = JevProfile(backend = "typesafe", batch = 40),
            laya = LayaProfile(endpoint = ProviderConfiguration.DEFAULT_LAYA_ENDPOINT, model = "english"),
        ))
        val before = settings.configuration(DecisionProvider.JEV)
        val draft = settings.editablePreferences()
        draft.jev.batch = 30
        draft.laya.pool = 10
        assertEquals(40, settings.configuration(DecisionProvider.JEV).pack.batch)
        assertEquals(20, settings.configuration(DecisionProvider.LAYA).pack.pool)

        settings.applyPreferences(draft)
        draft.jev.batch = 5
        assertEquals(40, before.pack.batch)
        assertEquals(30, settings.configuration(DecisionProvider.JEV).pack.batch)
        assertEquals(10, settings.configuration(DecisionProvider.LAYA).pack.pool)
        assertEquals("typesafe", settings.editablePreferences().jev.backend)
    }

    @Test
    fun `invalid local endpoint or profile bounds leave both saved profiles untouched`() {
        val settings = PackerSettings()
        settings.applyPreferences(PackerSettings.Preferences(
            provider = "laya",
            jev = JevProfile(backend = "typesafe", batch = 40),
            laya = LayaProfile(endpoint = ProviderConfiguration.DEFAULT_LAYA_ENDPOINT,
                model = "english", maxCandidates = 30, pool = 10),
        ))
        val before = settings.getState()
        val revision = settings.revision
        val invalid = listOf<(PackerSettings.Preferences) -> Unit>(
            { it.laya.endpoint = "https://remote.example/api/predict" },
            { it.laya.maxCandidates = 0 },
            { it.laya.pool = 31 },
            { it.jev.batch = 0 },
            { it.jev.fullChars = 32_000 }, // With comparison enabled, exceeds the source-character request guard.
        )
        for (change in invalid) {
            val attempt = settings.editablePreferences()
            change(attempt)
            assertThrows(IllegalArgumentException::class.java) { settings.applyPreferences(attempt) }
            assertEquals(before, settings.getState())
            assertEquals(revision, settings.revision)
        }
    }

    @Test
    fun `damaged persisted profile resets independently of the valid provider`() {
        val settings = PackerSettings()
        settings.loadState(PackerSettings.Preferences(
            provider = "laya",
            jev = JevProfile(batch = 0),
            laya = LayaProfile(endpoint = ProviderConfiguration.DEFAULT_LAYA_ENDPOINT,
                model = "english", maxCandidates = 25, pool = 9),
        ))
        assertEquals(DecisionProvider.LAYA, settings.provider)
        assertEquals(60, settings.configuration(DecisionProvider.JEV).pack.batch)
        assertEquals(25, settings.configuration(DecisionProvider.LAYA).maxCandidates)
        assertEquals(9, settings.configuration(DecisionProvider.LAYA).pack.pool)
    }
}

package dev.contextpacker

import org.junit.Assert.assertEquals
import org.junit.Test

class PackerSettingsTest {
    @Test
    fun `keyword mode survives project preference persistence`() {
        val settings = PackerSettings()
        settings.provider = DecisionProvider.KEYWORDS

        assertEquals("keywords", settings.getState().provider)
        assertEquals(DecisionProvider.KEYWORDS, PackerSettings().apply { loadState(settings.getState()) }.provider)
        assertEquals("Fast keywords (local)", DecisionProvider.KEYWORDS.toString())
    }
}

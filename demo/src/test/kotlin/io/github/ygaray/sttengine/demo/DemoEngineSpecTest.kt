package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.BackendPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-seam coverage for the corpus opt-in plumbing (Phase 25, D-17(e)): a changed
 * `retainForCorpus` must mint a new engine (spec inequality), and the spec must carry the flag into
 * the `SttConfig` the engine is built from.
 *
 * Plain JUnit, no Context: [DictationViewModel.EngineSpec] is a plain data class and
 * `toSttConfig()` touches nothing Android. Only `wss://host.example:8444/` appears as a host here.
 */
class DemoEngineSpecTest {

    private fun spec(retain: Boolean = false) = DictationViewModel.EngineSpec(
        language = "en",
        diagnosticLogging = false,
        serverUrl = "wss://host.example:8444/",
        prefer = BackendPreference.Server,
        retainForCorpus = retain,
    )

    @Test
    fun `specs differing only in the retain flag are not equal`() {
        assertNotEquals(spec(retain = false), spec(retain = true))
    }

    @Test
    fun `specs with the same retain flag are equal`() {
        assertEquals(spec(retain = true), spec(retain = true))
        assertEquals(spec(retain = false), spec(retain = false))
    }

    @Test
    fun `toSttConfig carries the retain flag`() {
        assertTrue(spec(retain = true).toSttConfig().retainForCorpus)
        assertFalse(spec(retain = false).toSttConfig().retainForCorpus)
    }

    @Test
    fun `toSttConfig carries language, prefer and server url`() {
        val config = spec(retain = true).toSttConfig()
        assertEquals("en", config.language)
        assertEquals(BackendPreference.Server, config.prefer)
        assertEquals("wss://host.example:8444/", config.serverUrl)
    }

    @Test
    fun `the retain flag defaults to false`() {
        val bare = DictationViewModel.EngineSpec(
            language = "en",
            diagnosticLogging = false,
            serverUrl = null,
            prefer = BackendPreference.Auto,
        )
        assertFalse(bare.retainForCorpus)
        assertFalse(bare.toSttConfig().retainForCorpus)
    }
}

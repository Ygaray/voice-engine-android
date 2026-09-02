package io.github.ygaray.sttengine

import android.speech.RecognizerIntent
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end regression guard for the SODA `ERROR_LANGUAGE_NOT_SUPPORTED` (code 12) fix: the engine
 * must hand the platform `RecognizerIntent` a REGION-QUALIFIED language, never the bare tag the
 * newer on-device recognizer rejects. Complements [io.github.ygaray.sttengine.internal.LanguageTagsTest]
 * (pure resolution logic) by proving the resolved tag actually reaches the recognition intent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeLanguageResolutionTest {

    private fun intentLanguageFor(configLanguage: String, deviceLocale: Locale): String? = run {
        var captured: String? = null
        runTest {
            val h = EngineHarness(
                testScheduler,
                config = SttConfig(language = configLanguage),
                deviceLocale = deviceLocale,
            )
            h.collectInto(backgroundScope)
            h.reachListening()
            captured = h.fake.lastIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        }
        captured
    }

    @Test
    fun `the default bare en is region-qualified to the device locale in the recognition intent`() {
        // The exact failure the device hit: SttConfig default "en" must not reach SODA as bare "en".
        assertEquals("en-US", intentLanguageFor("en", Locale.US))
        assertEquals("en-GB", intentLanguageFor("en", Locale.UK))
    }

    @Test
    fun `an already region-qualified language is passed through unchanged`() {
        assertEquals("en-GB", intentLanguageFor("en-GB", Locale.US))
        assertEquals("de-DE", intentLanguageFor("de-DE", Locale.US))
    }

    @Test
    fun `a bare non-device language borrows the device region`() {
        assertEquals("es-US", intentLanguageFor("es", Locale.US))
    }
}

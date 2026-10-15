package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.LanguageAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * The platform adapter's pack answers, on REAL platform objects.
 *
 * 1. `classify` on real `RecognitionSupport` objects — the builder is the real AOSP one under
 *    Robolectric sdk 35 (there is no shadow for it), so these rows exercise the exact lists a
 *    recognition service returns. The regional-variant row is the important one: an installed
 *    `es-ES` must never make `es-US` read INSTALLED.
 * 2. The synchronous-throw fold in `querySupport`: a support call that throws is a support call that
 *    could not answer, so it reads UNKNOWN — on the inline path (caller already on the main
 *    looper) AND on the posted path (caller off the main looper), where the throw happens inside the
 *    posted block and must neither escape onto the main looper nor leave the query waiting out its
 *    timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformRecognizerHandlePackTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        context = ApplicationProvider.getApplicationContext()
    }

    // --- classify on real RecognitionSupport ---------------------------------------------------

    @Test
    fun `an installed regional variant never makes es-US INSTALLED`() {
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(listOf("en-US", "es-ES"))
            .setSupportedOnDeviceLanguages(listOf("es-US", "de-DE"))
            .build()

        assertEquals(
            "es-ES installed + es-US supported → es-US is only downloadable",
            LanguageAvailability.DOWNLOADABLE,
            PlatformRecognizerHandle.classify("es-US", support),
        )
        assertEquals(LanguageAvailability.INSTALLED, PlatformRecognizerHandle.classify("en-US", support))
    }

    @Test
    fun `a pending download reads PENDING`() {
        val support = RecognitionSupport.Builder()
            .setPendingOnDeviceLanguages(listOf("es-US"))
            .setSupportedOnDeviceLanguages(listOf("es-US"))
            .build()

        assertEquals(LanguageAvailability.PENDING, PlatformRecognizerHandle.classify("es-US", support))
    }

    @Test
    fun `a service that lists nothing on-device reads UNKNOWN`() {
        val support = RecognitionSupport.Builder().build()

        assertEquals(LanguageAvailability.UNKNOWN, PlatformRecognizerHandle.classify("es-US", support))
    }

    @Test
    fun `a language in none of the on-device lists reads UNSUPPORTED`() {
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(listOf("en-US"))
            .setSupportedOnDeviceLanguages(listOf("en-US"))
            .build()

        assertEquals(LanguageAvailability.UNSUPPORTED, PlatformRecognizerHandle.classify("es-US", support))
    }

    // --- the querySupport synchronous-throw fold -----------------------------------------------

    @Test
    fun `a throwing support call on the main thread reads UNKNOWN on both overloads`() {
        val handle = PlatformRecognizerHandle(
            onDeviceRecognizer(),
            checkSupport = { _, _, _ -> throw IllegalStateException("binder died") },
        )

        val list = runBlocking { handle.languageAvailability(listOf("en-US", "es-US")) }
        val single = runBlocking { handle.languageAvailability("es-US") }

        assertEquals(
            "inline: List overload",
            mapOf("en-US" to LanguageAvailability.UNKNOWN, "es-US" to LanguageAvailability.UNKNOWN),
            list,
        )
        assertEquals("inline: single overload", LanguageAvailability.UNKNOWN, single)
    }

    @Test
    fun `a throwing support call posted from a background thread reads UNKNOWN promptly`() {
        val handle = PlatformRecognizerHandle(
            onDeviceRecognizer(),
            checkSupport = { _, _, _ -> throw IllegalStateException("binder died") },
        )
        var result: Map<String, LanguageAvailability>? = null
        var elapsedMs = -1L
        var thrown: Throwable? = null

        val thread = Thread {
            try {
                val startedAt = System.nanoTime()
                result = runBlocking { handle.languageAvailability(listOf("en-US", "es-US")) }
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            } catch (t: Throwable) {
                thrown = t
            }
        }
        thread.start()

        // If the catch sat outside the posted block, idle() itself would throw the platform's
        // IllegalStateException here on the test (main) thread.
        val deadline = System.currentTimeMillis() + 5_000L
        while (thread.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            thread.join(5L)
        }

        assertTrue("posted: the background call never returned", !thread.isAlive)
        assertEquals("posted: the background call threw", null, thrown)
        assertEquals(
            "posted: List overload",
            mapOf("en-US" to LanguageAvailability.UNKNOWN, "es-US" to LanguageAvailability.UNKNOWN),
            result,
        )
        assertTrue(
            "posted: must resume as soon as the posted call fails, not wait out the 2 s bound " +
                "(took $elapsedMs ms)",
            elapsedMs in 0 until 1_000L,
        )
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A REAL on-device recognizer, created on the test (main) thread. The fold rows wrap it with a
     * `checkSupport` that throws, as a dead or broken recognition service would.
     */
    private fun onDeviceRecognizer(): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
}

package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.checkLanguageAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * The PACK-01 tracer: ONE call to the public `checkLanguageAvailability(context)`, made from a
 * background thread exactly like a host coroutine off the main thread, travels through every real
 * layer — [LanguagePacks], the shared [autoPackAvailability] query, the real
 * [PlatformRecognizerHandle] and its `classify` — down to Robolectric's [ShadowSpeechRecognizer],
 * and back. Nothing in the path is faked except the recognition service's answer.
 *
 * The background thread matters: the function promises to enter the main thread itself (D-09). If
 * it did not, the shadowed on-device factory would throw its main-thread check and the call would
 * never reach the shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackPreflightTracerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        context = ApplicationProvider.getApplicationContext()
    }

    /** Wave-0 smoke: the real RecognitionSupport builder works under Robolectric sdk 35. */
    @Test
    fun `RecognitionSupport can be built under Robolectric sdk 35`() {
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(listOf("en-US"))
            .build()

        assertEquals(listOf("en-US"), support.installedOnDeviceLanguages)
    }

    @Test
    fun `a background-thread preflight reaches the platform and classifies its answer`() {
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(listOf("en-US", "es-ES"))
            .setSupportedOnDeviceLanguages(listOf("es-US", "es-ES", "de-DE"))
            .build()

        val call = PreflightCall { checkLanguageAvailability(context) }
        val recognizer = call.awaitRecognizer()
        shadowOf(recognizer).triggerSupportResult(support)
        val result = call.awaitResult()

        assertEquals(
            "en installed; es-US only supported (an installed es-ES is a different pack)",
            linkedMapOf("en" to LanguageAvailability.INSTALLED, "es" to LanguageAvailability.DOWNLOADABLE),
            result,
        )
        assertEquals("caller key order is echoed", listOf("en", "es"), result.keys.toList())
        assertTrue("the transient recognizer was not destroyed", shadowOf(recognizer).isDestroyed)
    }

    @Test
    fun `a support query that errors reads UNKNOWN and still destroys the recognizer`() {
        val call = PreflightCall { checkLanguageAvailability(context) }
        val recognizer = call.awaitRecognizer()
        shadowOf(recognizer).triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        val result = call.awaitResult()

        assertEquals(
            linkedMapOf("en" to LanguageAvailability.UNKNOWN, "es" to LanguageAvailability.UNKNOWN),
            result,
        )
        assertTrue("the transient recognizer was not destroyed", shadowOf(recognizer).isDestroyed)
    }

    @Test
    fun `unrecognized keys read UNSUPPORTED without constructing a recognizer`() {
        val call = PreflightCall { checkLanguageAvailability(context, setOf("fr", "en-US")) }
        val result = call.awaitResult()

        assertEquals(
            linkedMapOf("fr" to LanguageAvailability.UNSUPPORTED, "en-US" to LanguageAvailability.UNSUPPORTED),
            result,
        )
        assertNull(
            "a recognizer was constructed for keys the allowlist does not recognize",
            ShadowSpeechRecognizer.getLatestSpeechRecognizer(),
        )
    }

    @Test
    fun `the public enum keeps its frozen order`() {
        assertEquals(
            listOf("INSTALLED", "DOWNLOADABLE", "PENDING", "UNSUPPORTED", "UNKNOWN"),
            LanguageAvailability.entries.map { it.name },
        )
    }

    // --- the model download ------------------------------------------------------------------

    @Test
    fun `a background-thread download request puts the main-only seam on the main thread`() {
        // sdkInt 33 is injected because Robolectric shadows only the 1-arg triggerModelDownload.
        // If the orchestration ran the trigger off main, the seam's check would throw and the
        // result would read false.
        val packs = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                PlatformRecognizerHandle(SpeechRecognizer.createOnDeviceSpeechRecognizer(it), sdkInt = 33)
            },
            mainDispatcher = Dispatchers.Main.immediate,
        )
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(listOf("en-US"))
            .setSupportedOnDeviceLanguages(listOf("en-US", "es-US"))
            .build()

        val call = PreflightCall { packs.requestDownload(context, "es") }
        val recognizer = call.awaitRecognizer()
        shadowOf(recognizer).triggerSupportResult(support)
        val accepted = call.awaitResult()

        assertTrue("es-US supported but not installed → the trigger ran and was handed over", accepted)
        val intent = shadowOf(recognizer).latestModelDownloadIntent
        assertNotNull("the platform must have received a model-download intent", intent)
        assertEquals("es-US", intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("the transient recognizer was not destroyed", shadowOf(recognizer).isDestroyed)
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Runs [block] under `runBlocking` on a fresh background thread, while the test (main) thread
     * drives the paused main looper so the function's own main-thread hop can execute.
     */
    private class PreflightCall<T : Any>(block: suspend () -> T) {

        @Volatile private var result: T? = null
        @Volatile private var thrown: Throwable? = null

        private val thread = Thread {
            try {
                result = runBlocking { block() }
            } catch (t: Throwable) {
                thrown = t
            }
        }.also { it.start() }

        /** Idles the main looper until the call has constructed its recognizer (5 s deadline). */
        fun awaitRecognizer(): SpeechRecognizer {
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                ShadowSpeechRecognizer.getLatestSpeechRecognizer()?.let { return it }
                Thread.sleep(POLL_MS)
            }
            throw AssertionError("no recognizer was constructed within ${DEADLINE_MS} ms")
        }

        /** Idles the main looper until the background call finishes (5 s deadline). */
        fun awaitResult(): T {
            val deadline = System.currentTimeMillis() + DEADLINE_MS
            while (thread.isAlive && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                thread.join(POLL_MS)
            }
            if (thread.isAlive) throw AssertionError("the preflight did not return within ${DEADLINE_MS} ms")
            thrown?.let { throw AssertionError("the preflight threw", it) }
            return requireNotNull(result)
        }
    }

    private companion object {
        const val DEADLINE_MS = 5_000L
        const val POLL_MS = 5L
    }
}

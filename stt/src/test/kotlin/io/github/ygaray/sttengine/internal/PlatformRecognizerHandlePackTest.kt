package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.LanguageAvailability
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 3. The model download's API-33 branch through the shadow (the only overload Robolectric
 *    shadows), and the download seam's main-thread requirement: an off-main call throws before any
 *    intent is built, on both the API-33 and the API-34 branch.
 * 4. The listener-less download's ORDERING (CR-02): the platform only enqueues the request on the
 *    main looper, so the adapter must not return — and the caller must not destroy the handle —
 *    until that message has run; and on API 33 no trigger is sent without a support answer proving
 *    the recognizer connected.
 * 5. The API-34 listener branch with scripted listener callbacks (CR-01): `onError(15)` means the
 *    service has no listener-based download hook and started nothing, so the adapter must repeat
 *    the request WITHOUT a listener rather than report a download that never began.
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
        // A generous wall-clock ceiling: it only bounds a hung test on a loaded host. Whether the
        // query waited out its timeout is asserted separately, below.
        val deadline = System.currentTimeMillis() + 15_000L
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
            // Strictly under the 2 s timeout, with headroom for a loaded host: anything below it
            // cannot have waited the timeout out.
            elapsedMs in 0 until 1_800L,
        )
    }

    // --- the model download (API 33 branch) and its main-thread requirement ----------------------

    @Test
    fun `triggerModelDownload on API 33 hands the exact tag to the platform and reports true`() {
        val recognizer = onDeviceRecognizer()
        val handle = PlatformRecognizerHandle(recognizer, checkSupport = answering(), sdkInt = 33)

        val accepted = onMainLooper {
            handle.languageAvailability(listOf("en-US", "es-US"))
            handle.triggerModelDownload("es-US")
        }

        assertTrue("API 33: a request handed over without a throw reads accepted", accepted)
        val intent = shadowOf(recognizer).latestModelDownloadIntent
        assertNotNull("the platform must have received a model-download intent", intent)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent!!.action)
        assertEquals("es-US", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test
    fun `API 33 the queued platform request is dispatched before the caller can destroy the handle`() {
        // Stands in for AOSP's triggerModelDownload(Intent): it only POSTS a message to the main
        // looper, and the request reaches the service when that message runs. The shadow records the
        // intent synchronously, so it cannot show this. The caller then destroys the handle the
        // moment the call returns — synchronously, as AOSP 13's destroy() does (the shadow's destroy
        // is synchronous too). A request whose message runs after that destroy was dropped.
        val recognizer = onDeviceRecognizer()
        var dispatched = false
        var destroyedAtDispatch: Boolean? = null
        val handle = PlatformRecognizerHandle(
            recognizer,
            checkSupport = answering(),
            sdkInt = 33,
            handOver = {
                Handler(Looper.getMainLooper()).post {
                    destroyedAtDispatch = shadowOf(recognizer).isDestroyed
                    dispatched = true
                }
            },
        )

        val accepted = onMainLooper {
            handle.languageAvailability(listOf("en-US", "es-US"))
            val answer = handle.triggerModelDownload("es-US")
            handle.destroy()
            answer
        }

        assertTrue("the queued request message never ran", dispatched)
        assertEquals(
            "the handle was destroyed before the platform dispatched the queued request",
            false,
            destroyedAtDispatch,
        )
        assertTrue(accepted)
        assertTrue("the handle must still be destroyed afterwards", shadowOf(recognizer).isDestroyed)
    }

    @Test
    fun `API 33 without a support answer reads false and sends no trigger`() {
        for ((label, support) in listOf<Pair<String, (android.content.Intent, java.util.concurrent.Executor, android.speech.RecognitionSupportCallback) -> Unit>>(
            "no query at all" to answering(),
            "query errored" to { _, _, callback -> callback.onError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) },
            "query threw" to { _, _, _ -> throw IllegalStateException("binder died") },
        )) {
            var handedOver = 0
            val handle = PlatformRecognizerHandle(
                onDeviceRecognizer(),
                checkSupport = support,
                sdkInt = 33,
                handOver = { handedOver++ },
            )

            val accepted = onMainLooper {
                if (label != "no query at all") handle.languageAvailability(listOf("en-US", "es-US"))
                handle.triggerModelDownload("es-US")
            }

            assertFalse("$label: no proof of a connection must read 'not confirmed'", accepted)
            assertEquals("$label: no trigger may be sent", 0, handedOver)
        }
    }

    @Test
    fun `triggerModelDownload off main throws before any platform call`() {
        val recognizer = onDeviceRecognizer()

        for (sdkInt in listOf(33, 34)) {
            val handle = PlatformRecognizerHandle(recognizer, sdkInt = sdkInt)
            val thrown = AtomicReference<Throwable?>(null)
            val returned = AtomicReference<Boolean?>(null)

            val thread = Thread {
                try {
                    returned.set(runBlocking { handle.triggerModelDownload("es-US") })
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
            thread.start()
            thread.join(5_000L)

            assertTrue("sdk $sdkInt: the background call never finished", !thread.isAlive)
            assertNull("sdk $sdkInt: an off-main call must not return a result", returned.get())
            assertTrue(
                "sdk $sdkInt: an off-main call must throw IllegalStateException, got ${thrown.get()}",
                thrown.get() is IllegalStateException,
            )
            assertNull(
                "sdk $sdkInt: no platform download may be attempted off main",
                shadowOf(recognizer).latestModelDownloadIntent,
            )
        }
    }

    // --- the model download (API 34 listener branch, CR-01) -------------------------------------

    @Test
    fun `API 34 onError 15 repeats the request without a listener and reports it handed over`() {
        val handedOver = mutableListOf<android.content.Intent>()
        val handle = PlatformRecognizerHandle(
            onDeviceRecognizer(),
            sdkInt = 34,
            handOver = { handedOver += it },
            listenerTrigger = { _, sink -> sink.onError(15) },
        )

        val accepted = onMainLooper { handle.triggerModelDownload("es-US") }

        assertEquals(
            "error 15 = no listener hook: exactly one listener-less hand-over must follow",
            1,
            handedOver.size,
        )
        assertEquals("es-US", handedOver.single().getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue("a listener-less hand-over reads accepted, with the API-33 semantics", accepted)
    }

    @Test
    fun `API 34 onError 15 followed by a throwing hand-over reads false`() {
        val handle = PlatformRecognizerHandle(
            onDeviceRecognizer(),
            sdkInt = 34,
            handOver = { throw IllegalStateException("binder died") },
            listenerTrigger = { _, sink -> sink.onError(15) },
        )

        assertFalse(onMainLooper { handle.triggerModelDownload("es-US") })
    }

    @Test
    fun `API 34 listener acceptance and refusal never fall back to the listener-less call`() {
        for ((label, answer, expected) in listOf(
            Triple("onSuccess", { sink: DownloadOutcomeSink -> sink.onSuccess() }, true),
            Triple("onScheduled", { sink: DownloadOutcomeSink -> sink.onScheduled() }, true),
            Triple("onProgress", { sink: DownloadOutcomeSink -> sink.onProgress() }, true),
            Triple("onError(13)", { sink: DownloadOutcomeSink -> sink.onError(13) }, false),
        )) {
            val handedOver = mutableListOf<android.content.Intent>()
            val handle = PlatformRecognizerHandle(
                onDeviceRecognizer(),
                sdkInt = 34,
                handOver = { handedOver += it },
                listenerTrigger = { _, sink -> answer(sink) },
            )

            val accepted = onMainLooper { handle.triggerModelDownload("es-US") }

            assertEquals("$label: accepted", expected, accepted)
            assertTrue("$label: no listener-less fallback may run", handedOver.isEmpty())
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Runs [block] in a coroutine on the main looper — the test thread IS the main looper under
     * Robolectric — and idles the looper so anything [block] posted there runs. `runBlocking` would
     * block the very looper a posted step needs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> onMainLooper(block: suspend () -> T): T {
        val deferred = CoroutineScope(Dispatchers.Main.immediate).async { block() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the call did not complete once the main looper was idle", deferred.isCompleted)
        return deferred.getCompleted()
    }

    /** A support call that answers at once, as a connected recognition service would. */
    private fun answering(): (android.content.Intent, java.util.concurrent.Executor, android.speech.RecognitionSupportCallback) -> Unit =
        { _, _, callback ->
            callback.onSupportResult(
                RecognitionSupport.Builder()
                    .setInstalledOnDeviceLanguages(listOf("en-US"))
                    .setSupportedOnDeviceLanguages(listOf("en-US", "es-US"))
                    .build(),
            )
        }

    /**
     * A REAL on-device recognizer, created on the test (main) thread. The fold rows wrap it with a
     * `checkSupport` that throws, as a dead or broken recognition service would.
     */
    private fun onDeviceRecognizer(): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
}

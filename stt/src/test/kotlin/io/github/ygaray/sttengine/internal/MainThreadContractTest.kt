package io.github.ygaray.sttengine.internal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Proves the main-thread rule with a TEST rather than with a comment (threat T-05-13).
 *
 * `SpeechRecognizerImpl.checkIsCalledFromMainThread()` throws
 * `RuntimeException("SpeechRecognizer should be used only from the application's main thread")`
 * from `setRecognitionListener`, `startListening`, `stopListening` and `cancel`
 * [VERIFIED: `SpeechRecognizerImpl.java:155-217, 288-293`, read this session]. If
 * [PlatformRecognizerHandle]'s hop is ever removed, driving it from a background thread must fail
 * here.
 *
 * **This is not a vacuous test, and [platformItselfThrowsOffTheMainThread] is what makes that
 * claim checkable.** Robolectric shadows `startListening` but NOT `stopListening`, `cancel` or
 * `setRecognitionListener` — those three run the REAL AOSP implementation, thread check included
 * [VERIFIED by `javap` of `ShadowSpeechRecognizer` / `ShadowSpeechRecognizerImpl` 4.16.1]. The
 * negative control calls the platform object directly off the main thread and asserts it DOES
 * throw, so a future Robolectric upgrade that quietly starts shadowing those methods turns this
 * file red instead of turning it into a green test that proves nothing.
 *
 * ## Why `@Config(sdk = [35])` and not the plan's 36
 *
 * The plan pinned 36 for one reason — avoid a first-run network fetch, since 33 is not cached on
 * this host. **36 does not run here at all:** Robolectric refuses it with
 * `UnsupportedOperationException: Android SDK 36 requires Java 21 (have Java 17)`
 * (`DefaultSdkProvider.java:170`), and this project is Java 17 end to end (`compileOptions` and
 * `jvmTarget` are both `VERSION_17`, and `java -version` on this host is the only JDK installed).
 *
 * 35 satisfies the plan's actual requirement and improves on it:
 *  - **Cached.** `~/.m2/…/android-all-instrumented/15-robolectric-13954326-i7` is present, so there
 *    is still no network fetch. (33 remains uncached; that part of the plan's reasoning stands.)
 *  - **Runs on Java 17**, so no toolchain change is forced on a release path that other plans in
 *    this wave are building against.
 *  - **It is the tester device's API level.** `05-PROBE-REPORT.json` reports `SDK_INT: 35` on the
 *    Samsung SM-S908U. Pinning the host test to the level the measured behaviour came from is
 *    strictly better evidence than pinning it one level above.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainThreadContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        // The shadowed on-device factory refuses unless availability is switched on.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `all four main-thread-required methods are safe to call from a background thread`() {
        val handle = PlatformRecognizerHandleFactory.create(context)
        val intent = Intent("io.github.ygaray.sttengine.TEST_RECOGNIZE")
        val listener = NoOpRecognitionListener()

        assertNull("setListener threw off the main thread", onBackgroundThread { handle.setListener(listener) })
        assertNull("startListening threw off the main thread", onBackgroundThread { handle.startListening(intent) })
        assertNull("stopListening threw off the main thread", onBackgroundThread { handle.stopListening() })
        assertNull("cancel threw off the main thread", onBackgroundThread { handle.cancel() })

        // The work was QUEUED, not run: nothing has reached the platform object yet. This is the
        // positive evidence that a hop actually happened rather than the calls merely not throwing.
        val shadow = shadowOf(latestRecognizer())
        assertNull("the intent reached the platform without a main-looper hop", shadow.lastRecognizerIntent)

        // Draining the main looper is what executes the four posted calls — including the three
        // that run the real AOSP thread check. If any of them ran off the main thread, this line
        // is where the RuntimeException surfaces.
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("the intent the platform saw is not the one that was passed in", intent, shadow.lastRecognizerIntent)
    }

    @Test
    fun `destroy is routed through the same hop even though the platform exempts it`() {
        val handle = PlatformRecognizerHandleFactory.create(context)
        val recognizer = latestRecognizer()

        assertNull(onBackgroundThread { handle.destroy() })
        assertTrue("destroy ran inline instead of being posted", !shadowOf(recognizer).isDestroyed)

        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("destroy never reached the platform", shadowOf(recognizer).isDestroyed)
    }

    @Test
    fun `a call already on the main thread runs inline, with no extra frame of latency`() {
        val handle = PlatformRecognizerHandleFactory.create(context)
        val intent = Intent("io.github.ygaray.sttengine.TEST_RECOGNIZE_INLINE")

        // The Robolectric test thread IS the main thread. Dispatchers.Main.immediate semantics mean
        // this must take effect WITHOUT the looper being drained — the restart gap that AND-02
        // requires to be invisible cannot afford a spare frame.
        handle.setListener(NoOpRecognitionListener())
        handle.startListening(intent)

        assertSame(intent, shadowOf(latestRecognizer()).lastRecognizerIntent)
    }

    /**
     * NEGATIVE CONTROL. Asserts the platform's own thread check is live under this Robolectric
     * version, so the tests above cannot pass by shadowing rather than by hopping.
     */
    @Test
    fun platformItselfThrowsOffTheMainThread() {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

        val thrown = onBackgroundThread { recognizer.stopListening() }

        assertNotNull("the platform no longer enforces its main-thread contract under Robolectric", thrown)
        assertEquals(RuntimeException::class.java, thrown?.javaClass)
        assertTrue(
            "unexpected throwable: $thrown",
            thrown?.message?.contains("main thread") == true,
        )
    }

    // -------------------------------------------------------------------------------------------

    private fun latestRecognizer(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) {
            "no SpeechRecognizer was created"
        }

    /** Runs [block] on a fresh thread and returns whatever it threw, or null. */
    private fun onBackgroundThread(block: () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val thread = Thread {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        thread.start()
        thread.join()
        return thrown
    }

    private class NoOpRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.internal.PlatformRecognizerHandle.Companion.DOWNLOAD_ACCEPT_TIMEOUT_MS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The listener → [DownloadOutcome] bridge behind the API-34 download path, on virtual time.
 *
 * Plain JUnit, no Robolectric: the bridge is platform-free by design (no android.* import), which is
 * exactly what lets every outcome be scripted here — the platform's `ModelDownloadListener` itself is
 * an API-34 type that cannot be built on the plain-JVM `android.jar`.
 *
 * The first callback decides. A pack that is downloading, scheduled or done is ACCEPTED; error 15 is
 * LISTENER_UNSUPPORTED (the service has no listener-based download hook and started nothing — the
 * adapter must retry without a listener); any other error is REFUSED; silence past the bound is
 * NOT_CONFIRMED.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadListenerBridgeTest {

    /** Starts the bridge, lets `register` run, and hands back the sink it was given. */
    private class Started(val result: Deferred<DownloadOutcome>, val sink: DownloadOutcomeSink)

    private fun TestScope.start(): Started {
        var captured: DownloadOutcomeSink? = null
        val result = async { awaitDownloadOutcome(DOWNLOAD_ACCEPT_TIMEOUT_MS) { captured = it } }
        runCurrent()
        assertNotNull("awaitDownloadOutcome must hand register a sink", captured)
        return Started(result, captured!!)
    }

    // --- the first callback decides -----------------------------------------------------------

    @Test
    fun `onSuccess resolves ACCEPTED`() = runTest {
        val started = start()
        started.sink.onSuccess()
        assertEquals(DownloadOutcome.ACCEPTED, started.result.await())
    }

    @Test
    fun `onScheduled resolves ACCEPTED`() = runTest {
        val started = start()
        started.sink.onScheduled()
        assertEquals(DownloadOutcome.ACCEPTED, started.result.await())
    }

    @Test
    fun `the first onProgress resolves ACCEPTED`() = runTest {
        val started = start()
        started.sink.onProgress()
        assertEquals(DownloadOutcome.ACCEPTED, started.result.await())
    }

    @Test
    fun `onError 15 - no listener hook, nothing downloaded - resolves LISTENER_UNSUPPORTED`() = runTest {
        val started = start()
        started.sink.onError(15)
        assertEquals(
            "AOSP's default listener hook answers 15 without starting a download — never acceptance",
            DownloadOutcome.LISTENER_UNSUPPORTED,
            started.result.await(),
        )
    }

    @Test
    fun `any other onError resolves REFUSED`() = runTest {
        val started = start()
        started.sink.onError(13)
        runCurrent()
        assertTrue("onError(13) must settle immediately", started.result.isCompleted)
        assertEquals(DownloadOutcome.REFUSED, started.result.await())
    }

    // --- the bound ------------------------------------------------------------------------------

    @Test
    fun `no callback is still pending at 4999 ms and NOT_CONFIRMED at 5000 ms`() = runTest {
        val started = start()

        advanceTimeBy(DOWNLOAD_ACCEPT_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("still pending one millisecond before the bound", started.result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("settled at the bound", started.result.isCompleted)
        assertEquals("silence past the bound is not confirmed", DownloadOutcome.NOT_CONFIRMED, started.result.await())
    }

    // --- repeated and late callbacks -------------------------------------------------------------

    @Test
    fun `three onProgress then onSuccess resolve once to ACCEPTED without a double resume`() = runTest {
        val started = start()
        started.sink.onProgress()
        started.sink.onProgress()
        started.sink.onProgress()
        started.sink.onSuccess()
        assertEquals(DownloadOutcome.ACCEPTED, started.result.await())
    }

    @Test
    fun `a callback after the timeout already returned NOT_CONFIRMED is ignored`() = runTest {
        val started = start()
        advanceTimeBy(DOWNLOAD_ACCEPT_TIMEOUT_MS)
        runCurrent()
        assertEquals(DownloadOutcome.NOT_CONFIRMED, started.result.await())

        started.sink.onSuccess()
        started.sink.onProgress()
        started.sink.onError(15)
        runCurrent()
        assertEquals("a late callback cannot rewrite the answer", DownloadOutcome.NOT_CONFIRMED, started.result.await())
    }

    // --- the seam default -----------------------------------------------------------------------

    @Test
    fun `a handle that does not override triggerModelDownload reports false`() = runTest {
        val bare = object : RecognizerHandle {
            override fun setListener(listener: android.speech.RecognitionListener) = Unit
            override fun startListening(intent: android.content.Intent) = Unit
            override fun stopListening() = Unit
            override fun cancel() = Unit
            override fun destroy() = Unit
        }
        assertFalse("the seam default is 'not confirmed'", bare.triggerModelDownload("es-US"))
    }

    // --- registration that throws ----------------------------------------------------------------

    @Test
    fun `register throwing IllegalStateException synchronously resolves REFUSED`() = runTest {
        var registered = false
        val result = awaitDownloadOutcome(DOWNLOAD_ACCEPT_TIMEOUT_MS) {
            registered = true
            throw IllegalStateException("binder died")
        }
        assertTrue("register must be called", registered)
        assertEquals(DownloadOutcome.REFUSED, result)
        assertEquals(
            "a registration throw resolves at once, never waiting out the bound",
            0L,
            testScheduler.currentTime,
        )
    }

    @Test
    fun `register throwing CancellationException propagates`() = runTest {
        try {
            awaitDownloadOutcome(DOWNLOAD_ACCEPT_TIMEOUT_MS) { throw CancellationException("caller gone") }
            fail("a CancellationException from register must propagate, never fold to an outcome")
        } catch (cancellation: CancellationException) {
            assertEquals("caller gone", cancellation.message)
        }
    }
}

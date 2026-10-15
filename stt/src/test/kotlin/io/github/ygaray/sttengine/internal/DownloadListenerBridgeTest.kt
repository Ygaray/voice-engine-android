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
 * The listener → Boolean bridge behind the API-34 download path, on virtual time.
 *
 * Plain JUnit, no Robolectric: the bridge is platform-free by design (no android.* import), which is
 * exactly what lets every outcome be scripted here — the platform's `ModelDownloadListener` itself is
 * an API-34 type that cannot be built on the plain-JVM `android.jar`.
 *
 * The first callback decides. A pack that is downloading, scheduled, done, or whose progress feed
 * was lost (error 15) is ACCEPTED; any other error is refused; silence past the bound is "not
 * confirmed".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadListenerBridgeTest {

    /** Starts the bridge, lets `register` run, and hands back the sink it was given. */
    private class Started(val result: Deferred<Boolean>, val sink: DownloadOutcomeSink)

    private fun TestScope.start(): Started {
        var captured: DownloadOutcomeSink? = null
        val result = async { awaitDownloadAcceptance(DOWNLOAD_ACCEPT_TIMEOUT_MS) { captured = it } }
        runCurrent()
        assertNotNull("awaitDownloadAcceptance must hand register a sink", captured)
        return Started(result, captured!!)
    }

    // --- the first callback decides -----------------------------------------------------------

    @Test
    fun `onSuccess resolves true`() = runTest {
        val started = start()
        started.sink.onSuccess()
        assertTrue(started.result.await())
    }

    @Test
    fun `onScheduled resolves true`() = runTest {
        val started = start()
        started.sink.onScheduled()
        assertTrue(started.result.await())
    }

    @Test
    fun `the first onProgress resolves true`() = runTest {
        val started = start()
        started.sink.onProgress()
        assertTrue(started.result.await())
    }

    @Test
    fun `onError 15 - progress feed lost, not the download - resolves true`() = runTest {
        val started = start()
        started.sink.onError(15)
        assertTrue(started.result.await())
    }

    @Test
    fun `any other onError resolves false`() = runTest {
        val started = start()
        started.sink.onError(13)
        runCurrent()
        assertTrue("onError(13) must settle immediately", started.result.isCompleted)
        assertFalse(started.result.await())
    }

    // --- the bound ------------------------------------------------------------------------------

    @Test
    fun `no callback is still pending at 4999 ms and false at 5000 ms`() = runTest {
        val started = start()

        advanceTimeBy(DOWNLOAD_ACCEPT_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("still pending one millisecond before the bound", started.result.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("settled at the bound", started.result.isCompleted)
        assertFalse("silence past the bound is not confirmed", started.result.await())
    }

    // --- repeated and late callbacks -------------------------------------------------------------

    @Test
    fun `three onProgress then onSuccess resolve once to true without a double resume`() = runTest {
        val started = start()
        started.sink.onProgress()
        started.sink.onProgress()
        started.sink.onProgress()
        started.sink.onSuccess()
        assertTrue(started.result.await())
    }

    @Test
    fun `a callback after the timeout already returned false is ignored`() = runTest {
        val started = start()
        advanceTimeBy(DOWNLOAD_ACCEPT_TIMEOUT_MS)
        runCurrent()
        assertFalse(started.result.await())

        started.sink.onSuccess()
        started.sink.onProgress()
        started.sink.onError(13)
        runCurrent()
        assertFalse("a late callback cannot rewrite the answer", started.result.await())
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
    fun `register throwing IllegalStateException synchronously resolves false`() = runTest {
        var registered = false
        val result = awaitDownloadAcceptance(DOWNLOAD_ACCEPT_TIMEOUT_MS) {
            registered = true
            throw IllegalStateException("binder died")
        }
        assertTrue("register must be called", registered)
        assertFalse(result)
        assertEquals(
            "a registration throw resolves at once, never waiting out the bound",
            0L,
            testScheduler.currentTime,
        )
    }

    @Test
    fun `register throwing CancellationException propagates`() = runTest {
        try {
            awaitDownloadAcceptance(DOWNLOAD_ACCEPT_TIMEOUT_MS) { throw CancellationException("caller gone") }
            fail("a CancellationException from register must propagate, never fold to false")
        } catch (cancellation: CancellationException) {
            assertEquals("caller gone", cancellation.message)
        }
    }
}

package io.github.ygaray.sttengine.internal

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the fixture that plans 05, 06 and 08 are all going to be proved WITH.
 *
 * A fixture shipped untested is the same risk plan 02 caught in its own parity gate: the file looks
 * right in isolation and every downstream assertion built on it is quietly worth nothing. These
 * tests pin the three capabilities the downstream plans actually depend on —
 *
 *  1. text survives the round trip through a real `Bundle` (if it did not, "no text was committed"
 *     assertions would pass for the wrong reason),
 *  2. the fake can deliver a callback into a **stale** listener, which is the whole scenario plan
 *     05's epoch fence exists to survive and the one thing `ShadowSpeechRecognizer` cannot express,
 *  3. call order is recorded, because D-03's restart sequence is an ordering claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FakeRecognizerHandleTest {

    private val fake = FakeRecognizerHandle()

    /** Records everything, so an assertion can name the exact callback and payload. */
    private class Recording : RecognitionListener {
        val events: MutableList<String> = mutableListOf()
        override fun onReadyForSpeech(params: Bundle?) { events += "ready" }
        override fun onBeginningOfSpeech() { events += "beginning" }
        override fun onRmsChanged(rmsdB: Float) { events += "rms:$rmsdB" }
        override fun onBufferReceived(buffer: ByteArray?) { events += "buffer:${buffer?.size}" }
        override fun onEndOfSpeech() { events += "endOfSpeech" }
        override fun onError(error: Int) { events += "error:$error" }
        override fun onResults(results: Bundle?) {
            events += "results:${RecognizerBundles.firstNonBlankResult(results)}"
        }
        override fun onPartialResults(partialResults: Bundle?) {
            events += "partial:${RecognizerBundles.firstNonBlankResult(partialResults)}"
        }
        override fun onSegmentResults(segmentResults: Bundle) {
            events += "segment:${RecognizerBundles.firstNonBlankResult(segmentResults)}"
        }
        override fun onEndOfSegmentedSession() { events += "endOfSegmentedSession" }
        override fun onEvent(eventType: Int, params: Bundle?) { events += "event:$eventType" }
    }

    // --- recording ---------------------------------------------------------------------------

    @Test
    fun `it records the last intent by identity, per-method counts and call order`() {
        val listener = Recording()
        val first = Intent("first")
        val second = Intent("second")

        fake.setListener(listener)
        fake.startListening(first)
        fake.stopListening()
        fake.startListening(second)
        fake.cancel()
        fake.destroy()

        assertSame(second, fake.lastIntent)
        assertEquals(listOf(first, second), fake.startedIntents)
        assertEquals(1, fake.setListenerCount)
        assertEquals(2, fake.startCount)
        assertEquals(1, fake.stopCount)
        assertEquals(1, fake.cancelCount)
        assertEquals(1, fake.destroyCount)
        assertEquals(
            listOf("setListener", "startListening", "stopListening", "startListening", "cancel", "destroy"),
            fake.calls,
        )
    }

    @Test
    fun `firing before a listener is registered fails loudly instead of silently doing nothing`() {
        val thrown = runCatching { fake.fireBeginningOfSpeech() }.exceptionOrNull()
        assertTrue("expected an IllegalStateException, got $thrown", thrown is IllegalStateException)
        assertTrue(thrown?.message?.contains("no listener registered") == true)
    }

    // --- stimulus ----------------------------------------------------------------------------

    @Test
    fun `text survives the round trip through a real Bundle on every results channel`() {
        val listener = Recording()
        fake.setListener(listener)

        fake.firePartial("the")
        fake.fireResults("the morning")
        fake.fireSegmentResults("the morning was")

        assertEquals(
            listOf("partial:the", "results:the morning", "segment:the morning was"),
            listener.events,
        )
    }

    @Test
    fun `the full startup and segmented-session callback sequence is drivable`() {
        val listener = Recording()
        fake.setListener(listener)

        fake.reachListening()
        fake.fireRmsChanged(3.5f)
        fake.firePartial("the")
        fake.fireSegmentResults("the morning")
        fake.fireEndOfSegmentedSession()
        fake.fireEndOfSpeech()
        fake.fireError(7)
        fake.fireEvent(42)

        assertEquals(
            listOf(
                "ready",
                "beginning",
                "rms:3.5",
                "partial:the",
                "segment:the morning",
                "endOfSegmentedSession",
                "endOfSpeech",
                "error:7",
                "event:42",
            ),
            listener.events,
        )
    }

    @Test
    fun `reachListening can stop short of speech`() {
        val listener = Recording()
        fake.setListener(listener)
        fake.reachListening(withSpeech = false)
        assertEquals(listOf("ready"), listener.events)
    }

    // --- degenerate bundles ------------------------------------------------------------------

    @Test
    fun `every degenerate shape delivers a bundle that reads back as no text`() {
        val listener = Recording()
        fake.setListener(listener)

        FakeRecognizerHandle.DegenerateBundle.entries.forEach { shape ->
            fake.fireDegenerate(FakeRecognizerHandle.ResultChannel.RESULTS, shape)
        }
        // EMPTY_BUNDLE is the tester device's real onResults; the named shortcut fires the same.
        fake.fireEmptyResultsBundle()

        assertEquals(List(5) { "results:null" }, listener.events)
    }

    @Test
    fun `a null segment bundle is refused with an explanation rather than faked`() {
        fake.setListener(Recording())
        val thrown = runCatching {
            fake.fireDegenerate(
                FakeRecognizerHandle.ResultChannel.SEGMENT,
                FakeRecognizerHandle.DegenerateBundle.NULL,
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertTrue(thrown?.message?.contains("@NonNull") == true)
    }

    // --- the capability the Robolectric shadow cannot express --------------------------------

    @Test
    fun `a callback can be delivered into a listener a newer session has already replaced`() {
        val stale = Recording()
        val current = Recording()

        fake.setListener(stale)
        fake.setListener(current)

        // The late onResults from the dying session — the exact stimulus plan 05's epoch fence
        // has to drop. The fake must be able to produce it; deciding what to do with it is plan 05.
        fake.fireResults("text from the cancelled session", target = fake.registeredListeners[0])
        fake.fireResults("text from the live session")

        assertEquals(listOf("results:text from the cancelled session"), stale.events)
        assertEquals(listOf("results:text from the live session"), current.events)
        assertSame(current, fake.listener)
    }

    @Test
    fun `resetCounts clears the log without forgetting the registered listeners`() {
        val listener = Recording()
        fake.setListener(listener)
        fake.startListening(Intent("x"))

        fake.resetCounts()

        assertEquals(0, fake.startCount)
        assertEquals(0, fake.setListenerCount)
        assertTrue(fake.calls.isEmpty())
        assertSame(listener, fake.listener)
        // lastIntent is deliberately NOT cleared: it is evidence, not a counter.
        assertEquals("x", fake.lastIntent?.action)
    }

    @Test
    fun `a fresh fake has no listener and no intent`() {
        assertNull(fake.listener)
        assertNull(fake.lastIntent)
    }
}

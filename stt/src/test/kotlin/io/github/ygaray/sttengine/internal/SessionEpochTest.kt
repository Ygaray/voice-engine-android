package io.github.ygaray.sttengine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The epoch fence (D-03 mechanism 1, threat T-05-14).
 *
 * Pure JUnit — no device, no Robolectric, no coroutines. Every `RecognitionListener` callback is
 * delivered on the main looper (RESEARCH §Platform Ground Truth Finding A), so the fence is
 * single-threaded by construction and a plain-JVM test drives it exactly as the platform would.
 */
class SessionEpochTest {

    @Test
    fun `a fresh epoch starts at 0`() {
        val epoch = SessionEpoch()
        assertEquals(0L, epoch.current)
    }

    @Test
    fun `advance returns a strictly greater value each time`() {
        val epoch = SessionEpoch()
        var previous = epoch.current
        repeat(10) {
            val next = epoch.advance()
            assertTrue("advance() must be strictly monotonic: $next followed $previous", next > previous)
            assertEquals("advance() must leave current == the value it returned", next, epoch.current)
            previous = next
        }
    }

    @Test
    fun `accepts is true only for the current value and false for every prior one`() {
        val epoch = SessionEpoch()
        val seen = mutableListOf(epoch.current)
        repeat(5) { seen += epoch.advance() }

        assertTrue("the current epoch must be accepted", epoch.accepts(epoch.current))
        for (stale in seen.dropLast(1)) {
            assertFalse("epoch $stale is stale and must be rejected", epoch.accepts(stale))
        }
    }

    @Test
    fun `a future epoch is rejected too - the fence is equality, not a lower bound`() {
        val epoch = SessionEpoch()
        assertFalse(epoch.accepts(1L))
        assertFalse(epoch.accepts(Long.MAX_VALUE))
    }

    @Test
    fun `a listener bound to a stale epoch is rejected after an advance`() {
        val fence = SessionEpoch()
        val committed = mutableListOf<String>()

        // The listener is bound to the epoch that CREATED it and captures it by value, exactly as
        // the engine's `listenerFor(epoch)` will. It cannot mis-attribute itself by reading a
        // shared mutable field, which is the whole point of binding at construction.
        val listener = FakeBoundListener(boundEpoch = fence.current, fence = fence, sink = committed)

        listener.onPartialResults("live session speaks")
        assertEquals(listOf("live session speaks"), committed)

        // The session is torn down and the epoch advances. The listener object is still alive and
        // the dying recognizer session keeps firing at it.
        fence.advance()
        listener.onPartialResults("late callback from the dead session")
        listener.onResults("late final from the dead session")
        listener.onError(5)

        assertEquals(
            "a stale listener must not mutate live state after the epoch advanced",
            listOf("live session speaks"),
            committed,
        )
    }

    @Test
    fun `a listener bound to the NEW epoch is accepted while the stale one is not`() {
        val fence = SessionEpoch()
        val committed = mutableListOf<String>()
        val stale = FakeBoundListener(fence.current, fence, committed)

        val newEpoch = fence.advance()
        val fresh = FakeBoundListener(newEpoch, fence, committed)

        stale.onPartialResults("stale")
        fresh.onPartialResults("fresh")

        assertEquals(listOf("fresh"), committed)
    }
}

/**
 * A stand-in for the engine's fenced `RecognitionListener`. Deliberately checks [SessionEpoch.accepts]
 * as the FIRST statement of every callback — a missing fence in any one callback is the bug the
 * fence exists to prevent, so the test double reproduces the discipline rather than the platform type.
 */
private class FakeBoundListener(
    private val boundEpoch: Long,
    private val fence: SessionEpoch,
    private val sink: MutableList<String>,
) {
    private fun accept(): Boolean = fence.accepts(boundEpoch)

    fun onPartialResults(text: String) {
        if (!accept()) return
        sink += text
    }

    fun onResults(text: String) {
        if (!accept()) return
        sink += text
    }

    fun onError(code: Int) {
        if (!accept()) return
        sink += "error:$code"
    }
}

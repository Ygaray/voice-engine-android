package io.github.ygaray.sttengine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability ladder (D-01, D-04, D-05), as pure functions.
 *
 * The reference implementation deliberately extracted its candidate list and advance predicate out
 * of its manager class so they could be tested; this suite is why. Plain JUnit — no Android type is
 * reachable from [RungLadder], so plan 09 can flip a rung on or off from measured evidence and see
 * the consequence here in milliseconds rather than on a device.
 */
class RungLadderTest {

    // --- candidates(...) ------------------------------------------------------------------------

    @Test
    fun `no segmented support yields exactly the single terminal rung`() {
        assertEquals(
            listOf(Rung.RestartingSingleSession),
            RungLadder.candidates(supportsSegmented = false, supportsRawAudio = false),
        )
    }

    @Test
    fun `segmented but no raw audio yields silence-segmented then the terminal rung`() {
        assertEquals(
            listOf(Rung.SilenceSegmented, Rung.RestartingSingleSession),
            RungLadder.candidates(supportsSegmented = true, supportsRawAudio = false),
        )
    }

    @Test
    fun `segmented plus raw audio yields raw-audio, silence-segmented, terminal in that order`() {
        assertEquals(
            listOf(Rung.RawAudioSegmented, Rung.SilenceSegmented, Rung.RestartingSingleSession),
            RungLadder.candidates(supportsSegmented = true, supportsRawAudio = true),
        )
    }

    @Test
    fun `raw audio without segmented support is not offered - the raw-audio rung IS a segmented session`() {
        assertEquals(
            listOf(Rung.RestartingSingleSession),
            RungLadder.candidates(supportsSegmented = false, supportsRawAudio = true),
        )
    }

    @Test
    fun `every candidate list ends with the terminal rung`() {
        for (segmented in listOf(false, true)) {
            for (raw in listOf(false, true)) {
                val candidates = RungLadder.candidates(segmented, raw)
                assertEquals(
                    "the ladder's floor is mandatory and unconditional (segmented=$segmented raw=$raw)",
                    Rung.RestartingSingleSession,
                    candidates.last(),
                )
                assertTrue("a candidate list is never empty", candidates.isNotEmpty())
            }
        }
    }

    // --- next(...) ------------------------------------------------------------------------------

    @Test
    fun `next on the terminal rung returns the terminal rung`() {
        val full = RungLadder.candidates(supportsSegmented = true, supportsRawAudio = true)
        assertEquals(
            "the ladder never degrades below its floor",
            Rung.RestartingSingleSession,
            RungLadder.next(Rung.RestartingSingleSession, full),
        )
        assertEquals(
            Rung.RestartingSingleSession,
            RungLadder.next(Rung.RestartingSingleSession, listOf(Rung.RestartingSingleSession)),
        )
    }

    @Test
    fun `next from a higher rung returns the next rung down`() {
        val full = RungLadder.candidates(supportsSegmented = true, supportsRawAudio = true)
        assertEquals(Rung.SilenceSegmented, RungLadder.next(Rung.RawAudioSegmented, full))
        assertEquals(Rung.RestartingSingleSession, RungLadder.next(Rung.SilenceSegmented, full))
    }

    @Test
    fun `next skips a rung the device does not support rather than returning an unavailable one`() {
        val noRawAudio = RungLadder.candidates(supportsSegmented = true, supportsRawAudio = false)
        assertEquals(
            "RawAudioSegmented is not a candidate here, so the safe answer is the floor",
            Rung.RestartingSingleSession,
            RungLadder.next(Rung.RawAudioSegmented, noRawAudio),
        )
    }

    @Test
    fun `no call sequence ever returns a rung higher than the current one`() {
        val full = RungLadder.candidates(supportsSegmented = true, supportsRawAudio = true)
        // "Higher" == closer to the front of the ordered candidate list.
        for (start in full) {
            var current = start
            repeat(10) {
                val next = RungLadder.next(current, full)
                assertTrue(
                    "degradation is ONE WAY: $current must never lead back up to $next",
                    full.indexOf(next) >= full.indexOf(current),
                )
                current = next
            }
            assertEquals("every walk terminates on the floor", Rung.RestartingSingleSession, current)
        }
    }

    // --- shouldDegrade(...) ---------------------------------------------------------------------

    @Test
    fun `silence-segmented degrades when a session ended with a final and no end-of-segmented-session`() {
        val silentNoOp = SessionOutcome(
            cause = TerminatingCause.FINAL,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 0,
            appOwnedPipeOpen = false,
        )
        assertTrue(
            "a segmented session that ends via onResults without onEndOfSegmentedSession is a " +
                "PROVEN silent no-op — one session is enough evidence",
            RungLadder.shouldDegrade(Rung.SilenceSegmented, silentNoOp),
        )
    }

    @Test
    fun `silence-segmented does not degrade when the end-of-segmented-session callback was delivered`() {
        // The tester's measured shape: onEndOfSegmentedSession fired, onResults never did.
        val working = SessionOutcome(
            cause = TerminatingCause.SEGMENTED_SESSION_END,
            endOfSegmentedSessionDelivered = true,
            segmentResultCount = 13,
            appOwnedPipeOpen = false,
        )
        assertFalse(RungLadder.shouldDegrade(Rung.SilenceSegmented, working))

        // Even a session that DID end on a final is fine if the segmented callback also arrived.
        val bothDelivered = SessionOutcome(
            cause = TerminatingCause.FINAL,
            endOfSegmentedSessionDelivered = true,
            segmentResultCount = 4,
            appOwnedPipeOpen = false,
        )
        assertFalse(RungLadder.shouldDegrade(Rung.SilenceSegmented, bothDelivered))
    }

    @Test
    fun `silence-segmented does not degrade merely because an error occurred`() {
        val errored = SessionOutcome(
            cause = TerminatingCause.ERROR,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 3,
            appOwnedPipeOpen = false,
            errorCode = 7, // ERROR_NO_MATCH — routine, says nothing about the rung
        )
        assertFalse(
            "the trigger tests evidence the RUNG did not do its job, not that something went wrong",
            RungLadder.shouldDegrade(Rung.SilenceSegmented, errored),
        )
    }

    @Test
    fun `raw-audio degrades on error code 5 while our pipe is open - the upstream data-loss signature`() {
        val dataLoss = SessionOutcome(
            cause = TerminatingCause.ERROR,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 2,
            appOwnedPipeOpen = true,
            errorCode = 5, // ERROR_CLIENT
        )
        assertTrue(RungLadder.shouldDegrade(Rung.RawAudioSegmented, dataLoss))
    }

    @Test
    fun `raw-audio does not degrade on code 5 once our pipe is already closed`() {
        // The probe's own teardown shape: stopListening() on a raw-audio segmented session produced
        // onError(5) AFTER all six segment finals had been delivered. That is our teardown, not a
        // rung failure, and treating it as one would degrade a rung that measured precision 1.000.
        val ourTeardown = SessionOutcome(
            cause = TerminatingCause.ERROR,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 6,
            appOwnedPipeOpen = false,
            errorCode = 5,
        )
        assertFalse(RungLadder.shouldDegrade(Rung.RawAudioSegmented, ourTeardown))
    }

    @Test
    fun `raw-audio degrades when it produced zero segment results - a rung that never segments is a no-op`() {
        val neverSegmented = SessionOutcome(
            cause = TerminatingCause.FINAL,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 0,
            appOwnedPipeOpen = true,
        )
        assertTrue(RungLadder.shouldDegrade(Rung.RawAudioSegmented, neverSegmented))
    }

    @Test
    fun `a host-stopped session with zero segments is not evidence against the rung`() {
        val stoppedEarly = SessionOutcome(
            cause = TerminatingCause.STOPPED_BY_HOST,
            endOfSegmentedSessionDelivered = false,
            segmentResultCount = 0,
            appOwnedPipeOpen = true,
        )
        assertFalse(
            "the host stopped before the rung had a chance to segment — that indicts nothing",
            RungLadder.shouldDegrade(Rung.RawAudioSegmented, stoppedEarly),
        )
        assertFalse(RungLadder.shouldDegrade(Rung.SilenceSegmented, stoppedEarly))
    }

    @Test
    fun `the terminal rung never degrades, under every input`() {
        for (cause in TerminatingCause.entries) {
            for (endOfSegmented in listOf(false, true)) {
                for (segments in listOf(0, 1, 13)) {
                    for (pipeOpen in listOf(false, true)) {
                        for (errorCode in listOf(null, 5, 7, 8, 9999)) {
                            val outcome = SessionOutcome(
                                cause = cause,
                                endOfSegmentedSessionDelivered = endOfSegmented,
                                segmentResultCount = segments,
                                appOwnedPipeOpen = pipeOpen,
                                errorCode = errorCode,
                            )
                            assertFalse(
                                "the floor has nowhere to go: $outcome",
                                RungLadder.shouldDegrade(Rung.RestartingSingleSession, outcome),
                            )
                        }
                    }
                }
            }
        }
    }
}

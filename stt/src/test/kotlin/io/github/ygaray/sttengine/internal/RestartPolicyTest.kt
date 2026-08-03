package io.github.ygaray.sttengine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restart timing, the rebind budget and the runaway-loop guard — as arithmetic with tests, rather
 * than as behaviour discovered during a two-minute run.
 *
 * The policy owns no timers, so a fixed random source makes every jittered value exact. Same shape
 * as `web/src/reconnect.ts`, different constants: the engine asks for a delay and feeds it to its
 * own scheduler.
 */
class RestartPolicyTest {

    /** Jitter factor 1.0 — `1 + jitter * (0.5 * 2 - 1)` — so the exponential curve shows unmasked. */
    private fun noJitter() = RestartPolicy(random = { 0.5 })

    private fun minJitter() = RestartPolicy(random = { 0.0 })

    private fun maxJitter() = RestartPolicy(random = { 1.0 })

    // --- delayFor(reason) -----------------------------------------------------------------------

    @Test
    fun `the three base delays are 180, 200 and 600 milliseconds`() {
        val policy = noJitter()
        assertEquals(180L, policy.delayFor(RestartReason.SegmentedSessionEnded))
        assertEquals(200L, policy.delayFor(RestartReason.NormalFinal))
        assertEquals(600L, policy.delayFor(RestartReason.AfterError))
    }

    @Test
    fun `delayFor is pure - repeated calls do not escalate on their own`() {
        val policy = noJitter()
        repeat(5) { assertEquals(200L, policy.delayFor(RestartReason.NormalFinal)) }
        assertEquals("asking for a delay is not an attempt", 0, policy.attempt)
    }

    // --- nextRebindDelay() / the budget ---------------------------------------------------------

    @Test
    fun `a fresh policy reports attempt 0 and not exhausted`() {
        val policy = noJitter()
        assertEquals(0, policy.attempt)
        assertFalse(policy.exhausted)
        assertEquals(5, policy.maxAttempts)
    }

    @Test
    fun `the rebind delay doubles from 600ms and clamps at the 8000ms cap`() {
        val policy = noJitter()
        val sequence = List(5) { policy.nextRebindDelay() }

        assertEquals(
            "600 → 1200 → 2400 → 4800 → 9600-capped-to-8000",
            listOf(600L, 1_200L, 2_400L, 4_800L, 8_000L),
            sequence,
        )
        assertTrue("no delay may exceed the cap", sequence.all { it <= 8_000L })
    }

    @Test
    fun `jitter scatters the delay by the full plus or minus 20 percent and never past the cap`() {
        assertEquals(480L, minJitter().nextRebindDelay())   // 600 × 0.8
        assertEquals(720L, maxJitter().nextRebindDelay())   // 600 × 1.2

        // Even at maximum jitter the capped exponential must not exceed the cap.
        val policy = maxJitter()
        val sequence = List(8) { policy.nextRebindDelay() }
        assertTrue(
            "jitter must be applied INSIDE the cap, or a jittered value escapes it: $sequence",
            sequence.all { it <= 8_000L },
        )
        assertTrue("and never negative", sequence.all { it >= 0L })
    }

    @Test
    fun `exhausted becomes true after exactly 5 attempts and a sixth call does not increase the count`() {
        val policy = noJitter()
        repeat(4) { policy.nextRebindDelay() }
        assertEquals(4, policy.attempt)
        assertFalse("four attempts is still within budget", policy.exhausted)

        policy.nextRebindDelay()
        assertEquals(5, policy.attempt)
        assertTrue(policy.exhausted)

        policy.nextRebindDelay()
        policy.nextRebindDelay()
        assertEquals("an exhausted budget cannot be spent further", 5, policy.attempt)
        assertTrue(policy.exhausted)
    }

    @Test
    fun `reset returns the policy to attempt 0 and not exhausted`() {
        val policy = noJitter()
        repeat(5) { policy.nextRebindDelay() }
        assertTrue(policy.exhausted)

        policy.reset()

        assertEquals(0, policy.attempt)
        assertFalse(policy.exhausted)
        assertEquals("and the curve restarts from the base", 600L, policy.nextRebindDelay())
    }

    // --- the recognizer-busy exemption (phase 3 D-04) -------------------------------------------

    @Test
    fun `a recognizer-busy outcome leaves the attempt count unchanged`() {
        val policy = noJitter()
        policy.nextRebindDelay()
        val before = policy.attempt

        repeat(10) {
            policy.recordSessionOutcome(
                endedWithError = true,
                sawBeginningOfSpeech = false,
                sessionDurationMs = 40L,
                recognizerBusy = true,
            )
        }

        assertEquals(
            "recognizer-busy is TERMINAL and sits OFF the retry budget — settled for the web " +
                "backend in phase 3 D-04, and a host sharing failure-handling code across both " +
                "backends depends on it staying the same",
            before,
            policy.attempt,
        )
        assertFalse(policy.exhausted)
    }

    @Test
    fun `a recognizer-busy outcome does not feed the instant-error escalation either`() {
        val policy = noJitter()
        repeat(10) {
            policy.recordSessionOutcome(
                endedWithError = true,
                sawBeginningOfSpeech = false,
                sessionDurationMs = 40L,
                recognizerBusy = true,
            )
        }
        assertEquals(0, policy.instantErrorStreak)
        assertFalse(
            "retrying against a busy service is not a runaway loop — it terminates immediately",
            policy.shouldSurfaceInstantFailure,
        )
        assertEquals(600L, policy.delayFor(RestartReason.AfterError))
    }

    // --- the instant-error guard (threat T-05-15) ------------------------------------------------

    private fun RestartPolicy.recordInstantFailure() = recordSessionOutcome(
        endedWithError = true,
        sawBeginningOfSpeech = false,
        sessionDurationMs = 120L,
    )

    private fun RestartPolicy.recordHealthySession() = recordSessionOutcome(
        endedWithError = false,
        sawBeginningOfSpeech = true,
        sessionDurationMs = 9_000L,
    )

    @Test
    fun `the surface-failure signal appears only at the fifth consecutive instant failure`() {
        val policy = noJitter()
        repeat(4) {
            policy.recordInstantFailure()
            assertFalse(
                "four is not yet proof of a permanently broken recognizer (streak=${policy.instantErrorStreak})",
                policy.shouldSurfaceInstantFailure,
            )
        }

        policy.recordInstantFailure()

        assertEquals(5, policy.instantErrorStreak)
        assertTrue(policy.shouldSurfaceInstantFailure)
    }

    @Test
    fun `the instant-error counter resets after a session that produced speech`() {
        val policy = noJitter()
        repeat(4) { policy.recordInstantFailure() }
        assertEquals(4, policy.instantErrorStreak)

        policy.recordHealthySession()

        assertEquals("speech happened — the recognizer is not broken", 0, policy.instantErrorStreak)
        assertFalse(policy.shouldSurfaceInstantFailure)
        assertEquals("and the delay returns to its base", 600L, policy.delayFor(RestartReason.AfterError))
    }

    @Test
    fun `an error arriving after speech, or slowly, is not an instant failure`() {
        val policy = noJitter()

        // Errored, but the user had already begun speaking: a normal end-of-utterance error.
        policy.recordSessionOutcome(endedWithError = true, sawBeginningOfSpeech = true, sessionDurationMs = 80L)
        assertEquals(0, policy.instantErrorStreak)

        // Errored with no speech, but only after 4 s: the recognizer was listening, just to silence.
        policy.recordSessionOutcome(endedWithError = true, sawBeginningOfSpeech = false, sessionDurationMs = 4_000L)
        assertEquals(0, policy.instantErrorStreak)

        // Ended cleanly and instantly — no error at all.
        policy.recordSessionOutcome(endedWithError = false, sawBeginningOfSpeech = false, sessionDurationMs = 50L)
        assertEquals(0, policy.instantErrorStreak)
    }

    @Test
    fun `while the instant-error count is between 1 and 4 the delay escalates rather than staying at base`() {
        val policy = noJitter()
        val delays = mutableListOf(policy.delayFor(RestartReason.AfterError))
        repeat(4) {
            policy.recordInstantFailure()
            delays += policy.delayFor(RestartReason.AfterError)
        }

        assertEquals(
            "a broken device must not be able to spin the loop at the base delay",
            listOf(600L, 1_200L, 2_400L, 4_800L, 8_000L),
            delays,
        )
        assertTrue("strictly increasing until the cap", delays.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `the escalated delay is clamped at the same 8000ms cap`() {
        val policy = noJitter()
        repeat(12) { policy.recordInstantFailure() }
        for (reason in RestartReason.entries) {
            assertEquals(
                "escalation must saturate, not overflow into a delay measured in minutes",
                8_000L,
                policy.delayFor(reason),
            )
        }
    }

    @Test
    fun `escalation applies to every restart reason, not only the error path`() {
        val policy = noJitter()
        policy.recordInstantFailure()

        assertEquals(360L, policy.delayFor(RestartReason.SegmentedSessionEnded))
        assertEquals(400L, policy.delayFor(RestartReason.NormalFinal))
        assertEquals(1_200L, policy.delayFor(RestartReason.AfterError))
    }
}

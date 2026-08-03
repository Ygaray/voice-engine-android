package io.github.ygaray.sttengine.internal

import kotlin.math.min
import kotlin.math.roundToLong

// --- Tunable constants -------------------------------------------------------------------------
// TUNABLE, NOT CONTRACTUAL. Every value below is a timing knob, not a promise to a host. They start
// from the reference implementation's MEASURED values (openclaw TalkModeManager) and CONTEXT.md
// §Claude's Discretion explicitly marks them as starting points rather than locked. Declared here,
// in one block, so tuning is a one-file change with a test suite in front of it.

/**
 * Delay after `onEndOfSegmentedSession` before starting the next session.
 *
 * **Trade-off:** this is the shortest of the three because a segmented session ends *cleanly* — the
 * recognizer told us it was done rather than failing — so there is nothing to let settle. Shorter
 * still would shave the deaf window further but risks `ERROR_RECOGNIZER_BUSY` from restarting into a
 * service that has not finished releasing the microphone.
 */
internal const val DELAY_AFTER_SEGMENTED_SESSION_MS: Long = 180L

/**
 * Delay after an ordinary final before restarting.
 *
 * **Trade-off:** the whole of this delay lands inside the restart deaf window, on top of the
 * platform's own 164–259 ms. The probe measured this precisely: inserting exactly this 200 ms
 * shifted the measured deaf-window distribution from 164/198/259 ms to 375/415/446 ms. Lowering it
 * buys back real speech; the reason it is not lower is that a device restarting with *zero* delay is
 * the classic route to a busy recognizer.
 */
internal const val DELAY_AFTER_FINAL_MS: Long = 200L

/**
 * Delay after an error before restarting.
 *
 * **Trade-off:** three times the clean-final delay, deliberately. An error means something went
 * wrong that we did not diagnose, and hammering the service is how a transient fault becomes a
 * permanent one. This is the *base* — the instant-error guard escalates it from here.
 */
internal const val DELAY_AFTER_ERROR_MS: Long = 600L

/**
 * Base of the rebind backoff curve.
 *
 * **Trade-off:** matched to [DELAY_AFTER_ERROR_MS] on purpose — the first rebind should feel no
 * worse than an ordinary error restart, and only a *repeated* failure should start costing the user
 * perceptible time.
 */
internal const val REBIND_BASE_MS: Long = 600L

/**
 * Doubling factor for the rebind curve.
 *
 * **Trade-off:** 2 reaches the cap in five attempts, which matches [REBIND_MAX_ATTEMPTS] so the
 * budget expires at roughly the moment the delay stops growing. A gentler factor would spend the
 * whole budget at delays too short to let a recovering service actually recover.
 */
internal const val REBIND_FACTOR: Int = 2

/**
 * Ceiling on any computed delay, escalated or backed off.
 *
 * **Trade-off:** past ~8 s the user has concluded the app is broken, so a longer delay buys nothing
 * a typed error would not communicate better. Same value the web backend's reconnect policy uses,
 * which matters because a host may share failure-handling code across both backends.
 */
internal const val REBIND_CAP_MS: Long = 8_000L

/**
 * Fractional jitter, applied symmetrically (±20%).
 *
 * **Trade-off:** narrower than the web policy's ±50% because there is no thundering herd here — one
 * device talking to its own recognition service, not N clients hammering one socket. The jitter that
 * remains exists to break lockstep with any *other* app on the device competing for the microphone.
 */
internal const val REBIND_JITTER: Double = 0.20

/**
 * How many rebind attempts before the policy reports exhaustion.
 *
 * **Trade-off:** five attempts spans roughly 17 s of real time at the curve above — long enough to
 * ride out a service restart, short enough that a genuinely dead recognizer surfaces a typed error
 * while the user still associates it with what they were doing.
 */
internal const val REBIND_MAX_ATTEMPTS: Int = 5

/**
 * An error arriving within this window of `startListening`, with no beginning-of-speech, counts as
 * an *instant* failure.
 *
 * **Trade-off:** 1000 ms is comfortably longer than any healthy session's time-to-first-callback and
 * comfortably shorter than a session that genuinely listened to silence. Too high and ordinary
 * silence timeouts get miscounted as breakage; too low and a fast-failing device escapes the guard.
 */
internal const val INSTANT_ERROR_WINDOW_MS: Long = 1_000L

/**
 * Consecutive instant failures before the policy reports that a typed failure should be surfaced.
 *
 * **Trade-off:** below five, a transient fault (a passing phone call stealing the microphone) would
 * surface a scary error the user need never have seen. Above five, a permanently broken recognizer
 * spins for longer than anyone wants to watch. The escalating delay means five failures already take
 * several seconds, so this is not a tight loop even before the limit is reached.
 */
internal const val INSTANT_ERROR_STREAK_LIMIT: Int = 5

/** Why the engine is about to restart a session. Selects the base delay. */
internal enum class RestartReason {
    /** `onEndOfSegmentedSession` arrived — rungs (b)/(c). */
    SegmentedSessionEnded,

    /** An ordinary final ended the session — rung (a)'s normal cycle. */
    NormalFinal,

    /** The session ended via `onError`. */
    AfterError,
}

/**
 * Restart timing, the rebind budget and the runaway-loop guard — as arithmetic.
 *
 * **This class owns no timers, no coroutines, no handler and no clock.** The engine asks for a delay
 * and feeds it to its own scheduler; session durations are *passed in* rather than measured here.
 * That is the same split `web/src/reconnect.ts` uses on the other backend, and it is what makes both
 * of them trivially testable: a fixed random source turns every jittered value into an exact
 * expectation, with no fake clock and no virtual time.
 *
 * ## Two counters, deliberately separate
 *
 * [attempt] is the **rebind budget** — how many times the engine has torn down and rebuilt the
 * recognizer. [instantErrorStreak] is the **runaway-loop guard** — how many consecutive sessions
 * died before any speech. They answer different questions ("should I give up?" versus "is this
 * device broken?"), they are reset by different events, and conflating them would let a device that
 * fails fast exhaust the budget before the guard could ever describe *why*.
 *
 * ## What this class deliberately does not know
 *
 * Error taxonomy. On exhaustion the policy reports [exhausted] and the *engine* chooses the code —
 * `reconnect-exhausted` for the too-many-restarts path, `server-unreachable` for the disconnected
 * one. Keeping the mapping out of here is what lets plan 04's `ErrorMapper` own it alone.
 *
 * ## Threading
 *
 * Main-thread-confined like every other core in this package; plain `var` fields.
 *
 * @param random injectable source in `[0, 1)`. Defaults to [Math.random]; tests inject a constant.
 */
internal class RestartPolicy(
    private val random: () -> Double = { Math.random() },
) {

    /** Rebind attempts spent so far. Incremented only by [nextRebindDelay]. */
    var attempt: Int = 0
        private set

    /** Consecutive sessions that died instantly with no speech. Reset by any session that spoke. */
    var instantErrorStreak: Int = 0
        private set

    /** The configured ceiling, surfaced so a host-facing message can say "of N". */
    val maxAttempts: Int = REBIND_MAX_ATTEMPTS

    /**
     * True once the rebind budget is spent. The engine turns this into a terminal typed error —
     * which code is the engine's decision, not this class's.
     */
    val exhausted: Boolean get() = attempt >= maxAttempts

    /**
     * True once [INSTANT_ERROR_STREAK_LIMIT] consecutive sessions failed instantly.
     *
     * Mitigates threat **T-05-15**: a permanently failing recognizer would otherwise spin the
     * restart loop at the base delay indefinitely, draining the battery and flooding the log while
     * showing the user nothing that explains it.
     */
    val shouldSurfaceInstantFailure: Boolean get() = instantErrorStreak >= INSTANT_ERROR_STREAK_LIMIT

    /**
     * The delay, in ms, before restarting for [reason].
     *
     * Pure — asking does not spend anything. Returns the reason's base value while the device is
     * behaving, and escalates it along the same exponential curve as [nextRebindDelay] once the
     * instant-error streak is non-zero, so a broken device cannot spin the loop at 200 ms even
     * before the streak reaches its limit.
     */
    fun delayFor(reason: RestartReason): Long {
        val base = when (reason) {
            RestartReason.SegmentedSessionEnded -> DELAY_AFTER_SEGMENTED_SESSION_MS
            RestartReason.NormalFinal -> DELAY_AFTER_FINAL_MS
            RestartReason.AfterError -> DELAY_AFTER_ERROR_MS
        }
        if (instantErrorStreak == 0) return base
        return exponential(base, instantErrorStreak)
    }

    /**
     * The delay before the next rebind attempt, and spends one unit of budget.
     *
     * `min(cap, base × factor^attempt)` scattered by ±[REBIND_JITTER]. Jitter is applied **inside**
     * the clamp so a jittered value can never escape [REBIND_CAP_MS]. Once [exhausted], returns the
     * capped delay without incrementing — an exhausted budget cannot be spent further, and the
     * engine should be emitting a terminal error rather than asking again.
     */
    fun nextRebindDelay(): Long {
        if (exhausted) return jittered(REBIND_CAP_MS)
        val capped = exponential(REBIND_BASE_MS, attempt)
        attempt += 1
        return jittered(capped)
    }

    /**
     * Return the rebind budget to zero. Called once a session is healthy again.
     *
     * Deliberately does **not** touch [instantErrorStreak]: that counter has its own reset condition
     * — a session that actually produced speech, via [recordSessionOutcome] — because "we managed to
     * rebind" and "the recognizer works" are different claims, and only the second one should
     * silence the runaway-loop guard.
     */
    fun reset() {
        attempt = 0
    }

    /**
     * Feed the policy what a finished session did.
     *
     * Never touches [attempt]: the rebind budget is spent by [nextRebindDelay] alone, so recording
     * an outcome can never consume it.
     *
     * @param endedWithError whether the session ended via `onError`.
     * @param sawBeginningOfSpeech whether `onBeginningOfSpeech` was ever delivered. The single best
     *   signal that the recognizer is actually functioning.
     * @param sessionDurationMs how long the session lasted, measured by the *engine*. Passed in so
     *   this class stays clock-free.
     * @param recognizerBusy whether the session died on `ERROR_RECOGNIZER_BUSY`.
     *   **Terminal, and off the retry budget.** That meaning was settled for the web backend in
     *   phase 3 (D-04) and a host sharing failure-handling code across both backends depends on it
     *   staying identical. It is also excluded from the instant-error streak: a busy service is a
     *   diagnosable terminal condition that the engine surfaces immediately, not a runaway loop, and
     *   letting it inflate the streak would attribute someone else's microphone use to a broken
     *   device.
     */
    fun recordSessionOutcome(
        endedWithError: Boolean,
        sawBeginningOfSpeech: Boolean,
        sessionDurationMs: Long,
        recognizerBusy: Boolean = false,
    ) {
        if (recognizerBusy) return

        if (sawBeginningOfSpeech) {
            instantErrorStreak = 0
            return
        }

        val failedInstantly = endedWithError && sessionDurationMs < INSTANT_ERROR_WINDOW_MS
        if (failedInstantly) instantErrorStreak += 1 else instantErrorStreak = 0
    }

    /** `min(cap, base × factor^exponent)`, saturating rather than overflowing. */
    private fun exponential(base: Long, exponent: Int): Long {
        var value = base
        repeat(exponent) {
            if (value >= REBIND_CAP_MS) return REBIND_CAP_MS
            value *= REBIND_FACTOR
        }
        return min(REBIND_CAP_MS, value)
    }

    /** Symmetric ±[REBIND_JITTER]: `random()` in `[0, 1)` maps to a factor in `[0.8, 1.2]`. */
    private fun jittered(delayMs: Long): Long {
        val factor = 1.0 + REBIND_JITTER * (random() * 2.0 - 1.0)
        return (delayMs * factor).roundToLong().coerceIn(0L, REBIND_CAP_MS)
    }
}

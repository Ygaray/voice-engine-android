package io.github.ygaray.sttengine.internal

/**
 * Two-stage outbound backpressure gate — the Kotlin port of `web/src/backpressure.ts`'s
 * `createBackpressureGate` (Phase 9 Task 2, RESEARCH.md "Don't Hand-Roll": outbound WS
 * backpressure). Same thresholds, same state machine, fed [ServerBackend]'s real
 * `WebSocket.queueSize()` instead of the browser's `ws.bufferedAmount` — same units (bytes), same
 * meaning.
 *
 * This class owns NO timers: [evaluate] is fed `(bufferedAmount, now)` on every outbound frame tick
 * and only tracks the timestamps it is given, so the whole policy is deterministically testable with
 * a fake clock and has no side effects of its own.
 *
 * ## The policy
 *
 * - **At or below [ServerBackpressureGateOptions.soft]:** [BackpressureAction.Send] — the buffer is
 *   healthy, flush the outbound queue.
 * - **Above `soft`:** [BackpressureAction.DropOldest] — shed the oldest queued frame(s) to hold the
 *   memory bound, `warn = true` at most once per [ServerBackpressureGateOptions.warnThrottleMs].
 *   Never send while shedding — that is what lets the socket's own buffer actually drain.
 * - **Continuously above [ServerBackpressureGateOptions.hard] for
 *   [ServerBackpressureGateOptions.hardDebounceMs]:** [BackpressureAction.Fatal] — a genuine,
 *   sustained overrun, escalated to a terminal `send-backpressure` failure. A brief spike that
 *   recovers below `hard` resets the debounce clock rather than accumulating across separate spikes.
 * - **Dropping back below [ServerBackpressureGateOptions.low] while a warning is active:**
 *   [BackpressureAction.ClearWarning] — hysteresis so the warning does not flap between `soft` and
 *   `low`.
 */
internal class ServerBackpressureGateOptions(
    /** Soft ceiling in bytes: over this, shed oldest + warn. Default 65536 (~2 s @ 32 KB/s). */
    val soft: Long = 65_536L,
    /** Low-water mark in bytes: below this, clear the warning (hysteresis). Default 16384 (~0.5 s). */
    val low: Long = 16_384L,
    /** Hard ceiling in bytes: sustained breach → fatal. Default 163840 (~5 s). */
    val hard: Long = 163_840L,
    /** Continuous ms above [hard] before escalating to fatal. Default 3000. */
    val hardDebounceMs: Long = 3_000L,
    /** Minimum ms between warning signals while shedding. Default 1000 (~1/sec). */
    val warnThrottleMs: Long = 1_000L,
)

/** The action [ServerBackend] must take for the current outbound tick. */
internal sealed interface BackpressureAction {
    /** Under [ServerBackpressureGateOptions.soft] (and not clearing): flush the outbound queue. */
    data object Send : BackpressureAction

    /**
     * Over [ServerBackpressureGateOptions.soft]: shed the oldest queued frame(s). [warn] is true
     * when the throttle window has elapsed and a warning signal should be emitted this tick.
     */
    data class DropOldest(val warn: Boolean) : BackpressureAction

    /** Dropped below [ServerBackpressureGateOptions.low] while a warning was active: clear it. */
    data object ClearWarning : BackpressureAction

    /** Sustained above [ServerBackpressureGateOptions.hard] past the debounce: a fatal escalation. */
    data object Fatal : BackpressureAction
}

/**
 * A stateful two-stage backpressure gate. No timers of its own — pure threshold + timestamp
 * arithmetic over whatever `(bufferedAmount, now)` [evaluate] is called with.
 */
internal class ServerBackpressureGate(
    private val options: ServerBackpressureGateOptions = ServerBackpressureGateOptions(),
) {

    private var warningState = false

    /** `null` means "never warned yet" — the JS port's `-Infinity` sentinel, without the Long
     *  arithmetic overflow a literal `Long.MIN_VALUE` subtraction would risk. */
    private var lastWarnAt: Long? = null

    /** When the CURRENT continuous breach above [ServerBackpressureGateOptions.hard] began, or
     *  `null` if no breach is currently in flight. */
    private var hardBreachStart: Long? = null

    /** True while shedding is active (`soft` breached and not yet cleared below `low`). */
    val warning: Boolean get() = warningState

    /** Evaluate the gate for the current `bufferedAmount` (bytes) at time `now` (ms). Pure w.r.t.
     *  timers — the caller supplies both values every tick. */
    fun evaluate(bufferedAmount: Long, now: Long): BackpressureAction {
        if (bufferedAmount > options.soft) {
            warningState = true
            // Stage 2 debounce: only a CONTINUOUS breach above `hard` counts. Any tick at/below
            // `hard` breaks continuity and resets the clock, so a brief spike that recovers never
            // escalates.
            if (bufferedAmount > options.hard) {
                if (hardBreachStart == null) hardBreachStart = now
                if (now - hardBreachStart!! >= options.hardDebounceMs) return BackpressureAction.Fatal
            } else {
                hardBreachStart = null
            }
            val warn = lastWarnAt == null || now - lastWarnAt!! >= options.warnThrottleMs
            if (warn) lastWarnAt = now
            return BackpressureAction.DropOldest(warn)
        }

        // At or below `soft`: the send buffer is healthy again — no hard breach can be in flight.
        hardBreachStart = null
        if (warningState && bufferedAmount < options.low) {
            warningState = false
            lastWarnAt = null
            return BackpressureAction.ClearWarning
        }
        return BackpressureAction.Send
    }

    /** Reset all internal state. Call at the start of a fresh session. */
    fun reset() {
        warningState = false
        lastWarnAt = null
        hardBreachStart = null
    }
}

package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.SttEngineImpl

/**
 * What [DebugEngineProbe] could read from a live engine. Plain values only: an engine kind, a rung
 * name, an integer and a boolean. **No text of any kind**, so a snapshot is safe to print in a dump
 * taken during a real-microphone run.
 *
 * A `null` [rung] or [restartCount] means **unavailable**, never "healthy" and never zero. Render it
 * as unverified.
 */
public class EngineProbeSnapshot(
    /** `native` (a plain engine), `selector` (a server-first engine) or `unsupported`. */
    public val kind: String,
    /** The active rung's name, or `null` when it cannot be read as a fact (see [DebugEngineProbe]). */
    public val rung: String?,
    /** Sessions restarted since the last `start()`, or `null` when unavailable. */
    public val restartCount: Int?,
    /** True only when the engine is running and in [EngineState.Listening]. */
    public val listening: Boolean,
) {
    override fun toString(): String =
        "EngineProbeSnapshot(kind=$kind, rung=$rung, restartCount=$restartCount, listening=$listening)"
}

/**
 * Read the live engine's own rung and restart count, in memory, with no trace file.
 * **Debug builds only, and structurally so.**
 *
 * ## What this is for
 *
 * The real-microphone PAUSE-03 runs keep the engine's session trace OFF, because the trace records
 * what the user said. With the trace off, the demo's trace-derived rung and restart readouts are
 * null, which leaves two blind spots: which rung the microphone run actually used, and whether a
 * segmented session silently restarted (`onEndOfSegmentedSession` then `scheduleRestart`: no error,
 * no `Reconnecting`). This probe reads the two already-internal engine members that answer both
 * questions, straight from the object. Nothing is recorded, written or persisted.
 *
 * ## What it cannot see
 *
 * - **A file-fed run says nothing about the real microphone's rung.** The armed file replaces the
 *   audio source, so a real `AudioRecord` failure (which degrades rung (c) to rung (b) inside
 *   `start()`) never happens there. Only a probe on a real-microphone run can see it.
 * - **A [BackendSelector]'s private native engine** is not reached: a selector reports kind
 *   `selector`, and a foreign [SttEngine] reports `unsupported`, both with null readings. The PAUSE-03
 *   runs use no server URL, so their engine is a plain `SttEngineImpl`.
 * - **The rung is a fact of the CURRENT session.** Read it from a dump taken while listening; before
 *   the first session finishes starting, the field holds the floor rung, which is not a measurement,
 *   so both readings are null unless the engine is running and listening.
 *
 * A null field means UNAVAILABLE. It must never be rendered as healthy or as zero.
 *
 * ## Why it is safe for the shipped library
 *
 * This file lives in `stt/src/debug/`, which shares a Kotlin compilation with `main` for the debug
 * variant (that is how it can read internal members) and is absent from `compileReleaseKotlin`.
 * `api/stt.api` is a dump of the release compilation, so nothing here can reach the frozen surface.
 * `ReleaseEngineProbeSeamTest` asserts all of this.
 */
public object DebugEngineProbe {

    /** A snapshot of [engine]. Never throws; an unrecognized engine is kind `unsupported`. */
    public fun snapshot(engine: SttEngine): EngineProbeSnapshot = when (engine) {
        is SttEngineImpl -> {
            val listening = engine.isRunning && engine.state.value == EngineState.Listening
            EngineProbeSnapshot(
                kind = KIND_NATIVE,
                rung = if (listening) engine.activeRung.name else null,
                restartCount = if (listening) engine.restartCount else null,
                listening = listening,
            )
        }

        is BackendSelector ->
            EngineProbeSnapshot(KIND_SELECTOR, rung = null, restartCount = null, listening = false)

        else ->
            EngineProbeSnapshot(KIND_UNSUPPORTED, rung = null, restartCount = null, listening = false)
    }

    private const val KIND_NATIVE = "native"
    private const val KIND_SELECTOR = "selector"
    private const val KIND_UNSUPPORTED = "unsupported"
}

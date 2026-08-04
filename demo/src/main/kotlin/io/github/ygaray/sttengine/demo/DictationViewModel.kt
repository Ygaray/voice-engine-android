package io.github.ygaray.sttengine.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.createSttEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the engine and every piece of state the screen renders that the engine does not itself hold.
 *
 * The division of labour is deliberate and is the thing a consumer should copy:
 *
 * - **The engine owns live state.** `state` and `partial` are `StateFlow`s the screen reads
 *   directly with `collectAsStateWithLifecycle()`. Mirroring them into this class would add a frame
 *   of latency and a second source of truth for zero gain.
 * - **This class owns accumulated state** — the growing list of committed segments, the last error,
 *   the smoothed meter level, the diagnostics. None of that is the engine's business, and all of it
 *   has to survive a configuration change, which is what a `ViewModel` is for.
 *
 * The committed-segment stream is collected in **exactly one place** (the screen, once, keyed on the
 * live engine instance) and handed here through [onSegmentCommitted]. That is not style: the stream
 * is backed by an unlimited channel and is single-consumer by construction, so a second collector
 * would silently split the user's dictation between two lists.
 */
class DictationViewModel(app: Application) : AndroidViewModel(app) {

    // --- the engine ---------------------------------------------------------------------------------

    /**
     * The live engine.
     *
     * A `StateFlow` rather than a plain field because the debug control receiver can ask for a run
     * with a different language or with the session diagnostic enabled, and neither is reconfigurable
     * on a built engine — `createSttEngine` takes both at construction, on purpose, so that turning
     * on a diagnostic that records speech is a decision made in writing at one call site. A new
     * instance therefore has to replace the old one, and the screen has to notice and re-attach its
     * collectors. In the ordinary path — every release build, and any debug run that does not ask
     * for a different configuration — this emits once and never changes.
     */
    val engine: StateFlow<SttEngine>
        get() = _engine.asStateFlow()

    private val _engine: MutableStateFlow<SttEngine>
    private var builtWith: EngineSpec

    /** Whether the CURRENT engine instance is writing a session diagnostic. Never true by default. */
    private val _diagnosticLogging = MutableStateFlow(false)
    val diagnosticLogging: StateFlow<Boolean> = _diagnosticLogging.asStateFlow()

    // --- accumulated screen state -------------------------------------------------------------------

    private val _committed = MutableStateFlow<List<FinalSegment>>(emptyList())

    /** Every committed segment, oldest first. Immutable once appended — a final is never rewritten. */
    val committed: StateFlow<List<FinalSegment>> = _committed.asStateFlow()

    private val _error = MutableStateFlow<SttError?>(null)

    /** The session's terminal error, if one has been emitted. At most one per session. */
    val error: StateFlow<SttError?> = _error.asStateFlow()

    private val _level = MutableStateFlow(0f)

    /** The meter value the screen renders — always within `0.0..1.0`. See [onLevel]. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** The id of the most recently committed segment, or `null` before the first one. */
    val lastSegmentId: StateFlow<Int?>
        get() = _lastSegmentId.asStateFlow()

    private val _lastSegmentId = MutableStateFlow<Int?>(null)

    private var decayJob: Job? = null

    init {
        builtWith = EngineSpec(language = SttConfig().language, diagnosticLogging = false)
        _engine = MutableStateFlow(build(builtWith))
        startLevelDecay()
        live = this
    }

    // --- events in from the screen -------------------------------------------------------------------

    /**
     * Append a committed segment. Called from the screen's single collector.
     *
     * Appending — never mutating the tail — is the visual grammar the reference web demo established
     * and the reason finals read as settled: a block that can still change is a partial wearing a
     * final's clothes.
     */
    fun onSegmentCommitted(segment: FinalSegment) {
        _committed.value = _committed.value + segment
        _lastSegmentId.value = segment.segmentId
    }

    /** Record the session's terminal error so the loud banner can render it. */
    fun onEngineError(error: SttError) {
        _error.value = error
    }

    /**
     * Feed a level frame from the engine.
     *
     * Clamped here rather than trusted: the contract says `0.0..1.0` and the engine honours it, but
     * a meter is the one widget where an out-of-range value renders as a visibly broken app rather
     * than as a wrong number, so the render path clamps regardless.
     */
    fun onLevel(value: Float) {
        if (!value.isFinite()) return
        val clamped = value.coerceIn(0f, 1f)
        // Rising edges are instant; falling is left to the decay ticker. A meter that tracks every
        // downward sample is a meter that flickers.
        if (clamped >= _level.value) _level.value = clamped
    }

    // --- controls -------------------------------------------------------------------------------------

    /**
     * Begin a session.
     *
     * @param language the recognition language, or `null` to keep the current one.
     * @param diagnosticLogging when `true`, rebuild the engine with the JSONL session diagnostic on.
     *   **This records the user's dictated speech to a file**, which is why it is off by default,
     *   why nothing in the release build can reach this argument, and why asking for it mints a new
     *   engine rather than flipping a flag on the live one.
     */
    fun start(language: String? = null, diagnosticLogging: Boolean = false) {
        viewModelScope.launch {
            ensureEngine(language ?: builtWith.language, diagnosticLogging)
            // The banner persists until the next start, exactly as the web demo does it — a stale
            // error next to a live session would be worse than no error at all.
            _error.value = null
            _engine.value.start()
        }
    }

    /** End the session. Safe in any state, including from a terminal error. */
    fun stop() {
        viewModelScope.launch { _engine.value.stop() }
    }

    /** True while a session is up — the enabled/disabled rule for the two controls. */
    fun sessionLive(state: EngineState): Boolean =
        state == EngineState.Connecting ||
            state == EngineState.Listening ||
            state == EngineState.Reconnecting

    // --- internals -------------------------------------------------------------------------------------

    private suspend fun ensureEngine(language: String, diagnosticLogging: Boolean) {
        val wanted = EngineSpec(language, diagnosticLogging)
        if (wanted == builtWith) return
        _engine.value.stop()
        builtWith = wanted
        _engine.value = build(wanted)
    }

    private fun build(spec: EngineSpec): SttEngine {
        _diagnosticLogging.value = spec.diagnosticLogging
        return createSttEngine(
            context = getApplication(),
            config = SttConfig(language = spec.language),
            diagnosticLogging = spec.diagnosticLogging,
        )
    }

    /**
     * Decay the meter toward zero instead of snapping it there.
     *
     * This exists because of a measured property of this device: an ordinary restart leaves the
     * engine deaf for 164–259 ms (median 198 ms) while the recognizer is torn down and started
     * again. The contract deliberately does NOT change state across that gap — a routine restart is
     * not a reconnection and must not be rendered as one — so the state-driven meter reset the web
     * demo uses never fires, and the level stream simply goes quiet for those milliseconds.
     *
     * Both reference implementations zero the level on end-of-speech. Doing that here would put a
     * hard visible flicker at the exact boundary the whole restart design exists to hide, which is
     * the one place the seam must not show. A short decay reads as "you stopped talking" instead of
     * "something happened", which is the truth.
     *
     * The meter still snaps flat when the session genuinely is not listening — that is not a seam,
     * it is the honest answer.
     */
    private fun startLevelDecay() {
        decayJob?.cancel()
        decayJob = viewModelScope.launch {
            while (isActive) {
                delay(LEVEL_DECAY_TICK_MS)
                if (_engine.value.state.value != EngineState.Listening) {
                    _level.value = 0f
                    continue
                }
                val current = _level.value
                if (current <= 0f) continue
                val decayed = current * LEVEL_DECAY_FACTOR
                _level.value = if (decayed < LEVEL_DECAY_FLOOR) 0f else decayed
            }
        }
    }

    override fun onCleared() {
        decayJob?.cancel()
        // stop() releases the recognizer, and it must still run: viewModelScope is already cancelled
        // by the time this is called, so a short-lived scope is used and shut down immediately after.
        // Leaking a recognizer past the screen that owned it holds the microphone open.
        val dying = _engine.value
        val teardown = MainScope()
        teardown.launch {
            try {
                dying.stop()
            } finally {
                teardown.cancel()
            }
        }
        if (live === this) live = null
        super.onCleared()
    }

    private data class EngineSpec(val language: String, val diagnosticLogging: Boolean)

    internal companion object {

        /**
         * The currently attached view model, or `null` when no dictation screen is up.
         *
         * This exists for ONE consumer: the debug-source-set control receiver, which is how an agent
         * drives the demo over adb without touching screen coordinates. A broadcast receiver has no
         * route to a `ViewModel` otherwise, and the alternative — routing commands through an
         * Activity intent — would put the debug action strings into the main source set, which is
         * exactly what the debug-only structure is meant to prevent.
         *
         * Cleared in [onCleared], so it holds nothing after the screen goes away. The reference held
         * is an `AndroidViewModel` over the application context, so even a missed clear would leak
         * nothing that outlives the process.
         */
        @Volatile
        var live: DictationViewModel? = null

        /** ~20 Hz. Fast enough to look continuous, slow enough to cost nothing. */
        private const val LEVEL_DECAY_TICK_MS = 50L

        /**
         * Per-tick multiplier. At 20 Hz this takes a full-scale meter to ~30% over the 200 ms median
         * deaf window — clearly falling, never gone, and back up the instant audio resumes.
         */
        private const val LEVEL_DECAY_FACTOR = 0.75f

        /** Below this the bar is a hairline; snap it flat rather than render a sliver forever. */
        private const val LEVEL_DECAY_FLOOR = 0.01f
    }
}

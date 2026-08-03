package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.ReconnectInfo
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The dB value mapped to a level of `0.0`.
 *
 * `RecognitionListener.onRmsChanged`'s javadoc gives no absolute scale and only says the value is in
 * dB; the tester's own trace tops out at `maxRmsDb: 10` (`05-PROBE-REPORT.json`). −2 dB is the floor
 * the reference implementation uses and it matches what silence reads as here. TUNABLE, NOT
 * CONTRACTUAL — the *contract* is only that [SttEngine.level] is within `0.0..1.0`.
 */
internal const val LEVEL_FLOOR_DB: Float = -2f

/** The dB value mapped to a level of `1.0`. See [LEVEL_FLOOR_DB]. */
internal const val LEVEL_CEILING_DB: Float = 10f

/**
 * How many [SttEvent]s the merged [SttEngine.events] stream buffers for a slow collector.
 *
 * Generous, because a level meter emits far faster than anything else on the stream and a small
 * buffer would let level frames push a `Final` out. It is still bounded: [SttEngine.finals] is the
 * lossless path and `events` is the convenience union — a host that must not lose committed text
 * collects `finals`.
 */
internal const val EVENTS_BUFFER_CAPACITY: Int = 512

/**
 * The engine: preflight, one hot session-scoped Flow surface, the classic restart rung, and one
 * terminal-failure path.
 *
 * This is the Kotlin counterpart of `web/src/engine.ts`, and the structural correspondence is
 * deliberate down to the names — [fail] is the same single-terminal-failure funnel, [setState] the
 * same one state mutation point, [pendingStart] the same start-settle pair. A host sharing
 * failure-handling code across both backends is relying on those semantics being identical.
 *
 * ## Everything is injected, so the whole class runs on the host
 *
 * The recognizer factory, the permission checker, the dispatcher and the clock are all constructor
 * parameters. That is what lets `StateMachineTest`, `RestartBoundaryTest`, `FinalsLosslessTest`,
 * `StartCompletionTest` and `PreflightTest` drive the *real* engine through
 * [FakeRecognizerHandle] with no device attached. `SttEngineFactory` wires the platform ones.
 *
 * ## Threading (D-09)
 *
 * The library owns its dispatcher. Every call into [RecognizerHandle] goes through
 * `withContext(mainDispatcher)`, whose production value is `Dispatchers.Main.immediate`; no
 * `Looper`, `Handler` or `Executor` appears in any public signature and the caller is never required
 * to be on the main thread.
 *
 * Listener bodies only mutate main-thread-confined fields and `trySend`/`tryEmit` into the backing
 * primitives — **they never block and never suspend**. They run on the main looper, so blocking one
 * blocks the UI and delays the next callback. Because every recognizer callback lands on that looper
 * in FIFO order, [epoch], [accumulator], the rung state and [restartJob] are plain fields with no
 * locks, exactly as [SessionEpoch]'s KDoc explains.
 *
 * Note that the per-handler `try`/`catch` hazard `web/src/engine.ts` carries does not exist here.
 * That engine drives its state machine *through* `emit()`, so a throwing host handler could corrupt
 * it. Kotlin Flow decouples collectors from the state machine entirely; the trySend-only rule above
 * is what replaces the guard.
 */
internal class SttEngineImpl(
    private val appContext: Context,
    private val config: SttConfig,
    private val handleFactory: RecognizerHandleFactory,
    private val permissions: PermissionChecker,
    private val log: SessionLog = SessionLog(),
    private val supportsSegmented: Boolean = false,
    private val supportsRawAudio: Boolean = false,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val restartPolicy: RestartPolicy = RestartPolicy(),
) : SttEngine {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    // --- the public surface (D-07/D-08) -----------------------------------------------------------

    private val _state = MutableStateFlow(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    /**
     * **UNLIMITED, and that is the load-bearing choice on this whole surface.**
     *
     * A replay-free `SharedFlow` here would silently discard a committed final every time a
     * lifecycle-aware host detached on backgrounding — which for a dictation app whose user switches
     * away mid-thought is normal, expected behaviour. Those are words the user actually spoke. In a
     * note-taking product that is DATA LOSS, not a UI glitch, and it is invisible until someone
     * reads their notes back. An unlimited channel buffers across the detached window instead.
     *
     * The price is that this flow is **single-consumer by construction**: a second collector would
     * steal segments from the first. `FinalsLosslessTest` is the proof, and it is written so that a
     * replay-free shared flow could not make it pass.
     */
    private val finalsChannel = Channel<FinalSegment>(Channel.UNLIMITED)
    override val finals: Flow<FinalSegment> = finalsChannel.receiveAsFlow()

    /** Also channel-backed: at most one error is ever emitted per session, and losing it is losing
     *  the only explanation the host will ever get. Single-consumer for the same reason as [finals]. */
    private val errorsChannel = Channel<SttError>(Channel.UNLIMITED)
    override val errors: Flow<SttError> = errorsChannel.receiveAsFlow()

    /** Conflated, drop-oldest: a level frame is meaningful only at the instant it is produced, so
     *  back-pressuring a main-looper callback to deliver a stale one would be strictly worse. */
    private val _level = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val level: Flow<Float> = _level.asSharedFlow()

    /**
     * The merged union.
     *
     * Built as an ordered **mirror** rather than a `merge()` of the five properties, and the
     * difference matters: [finals] and [errors] are channel-backed and single-consumer, so merging
     * them would mean a host collecting `events` *steals* committed finals from a host collecting
     * `finals`. Mirroring costs one `tryEmit` per emission and additionally gives `events` a real
     * ordering guarantee, which `merge` does not.
     *
     * [SttEvent.Warning] is never emitted in v1c (D-17): the recognizer owns the microphone, so
     * there is no outbound send buffer to apply backpressure to. The type stays in the union so a
     * host's shared `when` compiles against both backends.
     */
    private val _events = MutableSharedFlow<SttEvent>(
        replay = 0,
        extraBufferCapacity = EVENTS_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SttEvent> = _events.asSharedFlow()

    // --- session state — main-thread confined, plain fields, no locks (D-09) ----------------------

    private val epoch = SessionEpoch()
    private val accumulator = SegmentAccumulator()

    private var handle: RecognizerHandle? = null
    private var restartJob: Job? = null
    private var candidates: List<Rung> = listOf(Rung.RestartingSingleSession)
    private var currentRung: Rung = Rung.RestartingSingleSession

    private var running = false
    private var terminal = false
    private var selfInflictedTeardown = false
    private var audioErrorAlreadyOccurred = false
    private var pendingStart: CompletableDeferred<Unit>? = null

    private var sessionStartedAtMs = 0L
    private var restartTriggeredAtMs = 0L
    private var sawBeginningOfSpeech = false
    private var segmentResultCount = 0
    private var endOfSegmentedSessionDelivered = false

    /** How many sessions this engine has restarted. Read by plan 07's diagnostic row and plan 08's log. */
    var restartCount: Int = 0
        private set

    /** The rung the current session is running on. Read by the same two consumers. */
    val activeRung: Rung get() = currentRung

    /** The live session epoch, so a diagnostic can tag a line with the session it belongs to. */
    val currentEpoch: Long get() = epoch.current

    /** Where the last committed final's text came from — `PLATFORM_FINAL` or the last partial. */
    val lastCommitSource: FinalTextSource get() = accumulator.lastCommitSource

    // --- start / stop -----------------------------------------------------------------------------

    /**
     * Preflight, then the first session.
     *
     * Completes when the engine reaches [EngineState.Listening] **or** when it fails terminally
     * (D-10), and on the failure path it RETURNS rather than throwing — a mic-denied or
     * recognizer-less device must not hang the caller forever on a state it will never reach. The
     * settle is a [CompletableDeferred] that [fail] `complete`s (never
     * `completeExceptionally`s), because permission denial is specified as a typed EVENT and not an
     * exception (D-10/D-11).
     */
    override suspend fun start() {
        if (running) return
        running = true
        terminal = false
        audioErrorAlreadyOccurred = false
        restartCount = 0
        restartPolicy.reset()
        setState(EngineState.Connecting)

        val pending = CompletableDeferred<Unit>()
        pendingStart = pending

        // --- preflight, before a single frame of audio ---
        // 1. RECORD_AUDIO. The HOST holds it — a library has no Activity to request from, which is
        //    the one documented asymmetry against the web engine (D-11).
        if (!permissions.hasRecordAudio()) {
            fail(ErrorMapper.mapPreflight(PreflightFailure.RecordAudioDenied))
            return
        }

        // 2. Construct the recognizer, and CATCH rather than check-then-create: the availability
        //    query and the throw are literally the same signal inside the platform factory, so a
        //    pre-check buys nothing but a time-of-check-to-time-of-use gap.
        val created = try {
            withContext(mainDispatcher) { handleFactory.create(appContext) }
        } catch (unsupported: UnsupportedOperationException) {
            fail(ErrorMapper.mapPreflight(PreflightFailure.RecognizerUnavailable))
            return
        }
        handle = created

        // 3. The language. Every outcome routes through the same D-18 preflight table.
        when (created.languageAvailability(config.language)) {
            LanguageAvailability.SUPPORTED_NOT_INSTALLED -> {
                fail(ErrorMapper.mapPreflight(PreflightFailure.LanguageNotInstalled(config.language)))
                return
            }

            LanguageAvailability.DOWNLOAD_PENDING -> {
                fail(ErrorMapper.mapPreflight(PreflightFailure.LanguageDownloadPending(config.language)))
                return
            }

            LanguageAvailability.NOT_SUPPORTED -> {
                fail(ErrorMapper.mapPreflight(PreflightFailure.LanguageNotSupported(config.language)))
                return
            }

            // A query that could not answer must never stop a session that would otherwise work.
            LanguageAvailability.INSTALLED, LanguageAvailability.UNDETERMINED -> Unit
        }

        // Both capabilities false: this plan ships the ladder's terminal rung only. Plan 09 turns the
        // other two on if and only if the probe verdict says so, and it does that by changing these
        // two parameters — nothing in the session loop below has to know.
        candidates = RungLadder.candidates(supportsSegmented, supportsRawAudio)
        currentRung = candidates.first()

        startSession()
        pending.await()
    }

    /**
     * Flush, then release.
     *
     * Suspending because the contract says the open segment is committed as a final BEFORE teardown
     * — text the user already spoke is committed rather than dropped — and that has to happen while
     * the engine is still listening, not after it has gone idle.
     */
    override suspend fun stop() {
        running = false
        restartJob?.cancel()
        restartJob = null

        commitLivePartial()
        teardownSession(destroyHandle = true)
        epoch.advance()
        accumulator.onSegmentBoundary()

        if (!terminal) setState(EngineState.Idle)
        pendingStart?.complete(Unit)
        pendingStart = null
    }

    // --- the session loop ---------------------------------------------------------------------------

    private suspend fun startSession() {
        val active = handle ?: return
        val boundEpoch = epoch.current
        sessionStartedAtMs = monotonicMs()
        sawBeginningOfSpeech = false
        segmentResultCount = 0
        endOfSegmentedSessionDelivered = false

        log.rung(currentRung)
        val intent = RecognizerIntents.forRung(currentRung, config, appContext)
        withContext(mainDispatcher) {
            active.setListener(listenerFor(boundEpoch))
            active.startListening(intent)
        }
        selfInflictedTeardown = false
    }

    /**
     * The restart sequence. **The order is the whole point.**
     *
     * ```
     * 1. commit the live partial      ← BEFORE teardown: teardown is WHAT CAUSES the missing final
     * 2. tear the session down
     * 3. advance the epoch            ← AFTER teardown: fences the dying session's late callbacks
     * 4. open the next segment
     * 5. wait out the policy's delay, re-check we are still meant to be running, start the next session
     * ```
     *
     * Reversing 1 and 2 reintroduces the dropped-final bug; dropping 3 reintroduces the double-emit
     * bug. `RestartBoundaryTest` has one test per direction so a reversal cannot pass silently.
     *
     * Steps 1–4 complete synchronously inside the callback that triggered the restart, because
     * `Dispatchers.Main.immediate` runs a `launch` from the main looper inline up to the first
     * suspension. No other callback can interleave with them.
     *
     * An ordinary restart is INVISIBLE (D-17/AND-02): the state stays [EngineState.Listening] across
     * the gap. Only the two paths that genuinely are reconnections — the service-disconnected rebind
     * and the too-many-requests backoff — call [enterReconnecting] first.
     */
    private fun scheduleRestart(
        reason: RestartReason,
        cause: TerminatingCause,
        errorCode: Int? = null,
        delayOverride: Long? = null,
        rebind: Boolean = false,
    ) {
        if (terminal || !running) return
        restartCount += 1
        restartTriggeredAtMs = monotonicMs()

        val rungBefore = currentRung
        recordOutcome(cause, errorCode)

        // 1. commit BEFORE teardown
        commitLivePartial()

        val delayMs = delayOverride ?: restartPolicy.delayFor(reason)
        log.restart(
            epoch = epoch.current,
            reason = reason.name,
            delayMs = delayMs,
            advanceRung = currentRung != rungBefore,
        )
        restartJob?.cancel()
        restartJob = scope.launch {
            // 2. teardown
            teardownSession(destroyHandle = rebind)
            // 3. fence AFTER teardown
            epoch.advance()
            // 4. open the next segment before the next session's first result can arrive
            accumulator.onSegmentBoundary()

            delay(delayMs)
            if (terminal || !running) return@launch

            if (rebind) {
                val rebuilt = try {
                    withContext(mainDispatcher) { handleFactory.create(appContext) }
                } catch (unsupported: UnsupportedOperationException) {
                    fail(ErrorMapper.mapPreflight(PreflightFailure.RecognizerUnavailable))
                    return@launch
                }
                handle = rebuilt
            }
            startSession()
        }
    }

    /**
     * Feed the finished session to the two things that judge it: the restart policy's runaway-loop
     * guard, and the rung ladder's degradation trigger.
     *
     * The ladder is wired even though this plan ships the terminal rung only, where
     * `shouldDegrade` is unconditionally false. It costs one call and it means plan 09 enables the
     * upper rungs by flipping two constructor flags rather than by adding a code path.
     */
    private fun recordOutcome(cause: TerminatingCause, errorCode: Int?) {
        restartPolicy.recordSessionOutcome(
            endedWithError = cause == TerminatingCause.ERROR,
            sawBeginningOfSpeech = sawBeginningOfSpeech,
            sessionDurationMs = monotonicMs() - sessionStartedAtMs,
            recognizerBusy = errorCode == RAW_ERROR_RECOGNIZER_BUSY,
        )
        val outcome = SessionOutcome(
            cause = cause,
            endOfSegmentedSessionDelivered = endOfSegmentedSessionDelivered,
            segmentResultCount = segmentResultCount,
            // Rung (c) is not enabled in this plan, so this engine owns no pipe to still be open.
            appOwnedPipeOpen = false,
            errorCode = errorCode,
        )
        if (RungLadder.shouldDegrade(currentRung, outcome)) {
            currentRung = RungLadder.next(currentRung, candidates)
        }
    }

    private suspend fun teardownSession(destroyHandle: Boolean) {
        val active = handle ?: return
        // Our own teardown provokes ERROR_CLIENT(5). The epoch fence already rejects it (it reaches
        // the retired listener), and this flag is the second line of defence for the same-epoch case.
        selfInflictedTeardown = true
        withContext(mainDispatcher) {
            active.cancel()
            if (destroyHandle) active.destroy()
        }
        if (destroyHandle) handle = null
    }

    // --- the one terminal-failure path ---------------------------------------------------------------

    /**
     * EVERY typed error routes through here, and it is idempotent: the FIRST error wins and
     * `state(error)` is emitted EXACTLY ONCE. A second condition — a paired error and teardown, a
     * late callback, a second preflight failure — is a no-op.
     *
     * It cancels the pending restart, tears the recognizer down, retires the epoch so nothing from
     * the dying session can speak again, emits the error once, sets the state once, and completes a
     * pending [start] so the caller is released rather than left waiting for a state that will never
     * arrive.
     */
    private fun fail(error: SttError) {
        if (terminal) return
        terminal = true
        running = false
        restartJob?.cancel()
        restartJob = null

        val dying = handle
        handle = null
        epoch.advance()
        if (dying != null) {
            selfInflictedTeardown = true
            scope.launch {
                withContext(mainDispatcher) {
                    dying.cancel()
                    dying.destroy()
                }
            }
        }

        errorsChannel.trySend(error)
        _events.tryEmit(SttEvent.Error(error))
        log.error(error)
        setState(EngineState.Error)

        pendingStart?.complete(Unit)
        pendingStart = null
    }

    // --- emission helpers ------------------------------------------------------------------------------

    /** The ONE place [state] is mutated, so every transition is mirrored onto [events] and the
     *  session log exactly once. */
    private fun setState(next: EngineState) {
        if (_state.value == next) return
        _state.value = next
        _events.tryEmit(SttEvent.State(next))
        log.state(next)
    }

    private fun commitLivePartial() {
        emitFinal(accumulator.commitLivePartialAsFinal() ?: return)
    }

    private fun emitFinal(segment: FinalSegment) {
        _partial.value = ""
        finalsChannel.trySend(segment)
        _events.tryEmit(SttEvent.Final(segment))
        log.final(segment)
    }

    private fun enterReconnecting() {
        val info = ReconnectInfo(restartPolicy.attempt + 1, restartPolicy.maxAttempts)
        setState(EngineState.Reconnecting)
        _events.tryEmit(SttEvent.Reconnecting(info))
    }

    // --- the listener ------------------------------------------------------------------------------------

    /**
     * A listener BOUND to [boundEpoch] at construction, checking the fence as the **first statement
     * of every single callback**.
     *
     * Binding at construction rather than reading a shared "current session" field is what makes
     * this safe: a bound listener physically cannot mis-attribute a dying session's callback to the
     * live one. A missing fence in any one callback is the bug, so every override below opens with
     * `if (!accept()) return` — including the ones whose bodies do nothing.
     */
    private fun listenerFor(boundEpoch: Long): RecognitionListener = object : RecognitionListener {

        private fun accept(): Boolean = epoch.accepts(boundEpoch)

        override fun onReadyForSpeech(params: Bundle?) {
            if (!accept()) return
            // The deaf window is the audio this engine could not hear: the gap between the callback
            // that triggered the restart and the microphone being live again. The probe measured
            // 164/198/259 ms (min/median/max) across 13 restarts, which is why plan 08 wants it per
            // restart rather than as one number. A ready that no restart preceded reports the
            // sentinel rather than a fabricated zero that would drag the distribution down.
            val deafWindowMs =
                if (restartTriggeredAtMs == 0L) SessionLog.NO_DEAF_WINDOW
                else monotonicMs() - restartTriggeredAtMs
            log.ready(epoch = boundEpoch, deafWindowMs = deafWindowMs)
            setState(EngineState.Listening)
            pendingStart?.complete(Unit)
            pendingStart = null
        }

        override fun onBeginningOfSpeech() {
            if (!accept()) return
            sawBeginningOfSpeech = true
            // A session that actually heard speech is the single best evidence the recognizer works,
            // so this — and not merely reaching `ready` — is what returns the rebind budget.
            restartPolicy.reset()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!accept()) return
            // Non-finite input is DISCARDED rather than clamped: a NaN reaching a host's meter would
            // render as an undefined width, and the value carries no information anyway.
            if (!rmsdB.isFinite()) return
            val normalized =
                ((rmsdB - LEVEL_FLOOR_DB) / (LEVEL_CEILING_DB - LEVEL_FLOOR_DB)).coerceIn(0f, 1f)
            _level.tryEmit(normalized)
            _events.tryEmit(SttEvent.Level(normalized))
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (!accept()) return
            // Raw audio is rung (c)'s concern and this plan ships rung (a). Fenced anyway: an
            // unfenced callback is the bug even when its body is empty.
        }

        override fun onEndOfSpeech() {
            if (!accept()) return
            // The final follows; nothing to do here. Deliberately NOT a state change — the session
            // is still live and the host must keep seeing `listening`.
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!accept()) return
            // Untrusted cross-process input. A null bundle, a missing key, an empty list and a list
            // of blank strings all read back as null here rather than throwing (T-05-12).
            val text = RecognizerBundles.firstNonBlankResult(partialResults) ?: return
            accumulator.onPartial(text)
            _partial.value = text
            _events.tryEmit(SttEvent.Partial(text))
            log.partial(segmentId = accumulator.segmentId, text = text)
        }

        override fun onResults(results: Bundle?) {
            if (!accept()) return
            // Pass "" when the bundle carried nothing — do NOT substitute the live partial here.
            // FinalTextPolicy is the one tested place that decision lives, and on this phase's tester
            // the empty bundle is the ORDINARY case, not the exceptional one.
            val text = RecognizerBundles.firstNonBlankResult(results) ?: NO_PLATFORM_FINAL
            accumulator.onFinal(text)?.let(::emitFinal)
            scheduleRestart(RestartReason.NormalFinal, TerminatingCause.FINAL)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!accept()) return
            segmentResultCount += 1
            val text = RecognizerBundles.firstNonBlankResult(segmentResults) ?: NO_PLATFORM_FINAL
            accumulator.onFinal(text)?.let(::emitFinal)
            // A segmented session continues after a segment: advance the counter, do NOT restart.
            accumulator.onSegmentBoundary()
        }

        override fun onEndOfSegmentedSession() {
            if (!accept()) return
            endOfSegmentedSessionDelivered = true
            scheduleRestart(RestartReason.SegmentedSessionEnded, TerminatingCause.SEGMENTED_SESSION_END)
        }

        override fun onError(error: Int) {
            if (!accept()) return
            val context = ErrorContext(
                selfInflictedTeardown = selfInflictedTeardown,
                audioErrorAlreadyOccurred = audioErrorAlreadyOccurred,
                restartBudgetRemaining = !restartPolicy.exhausted,
            )
            when (val disposition = ErrorMapper.map(error, context)) {
                Disposition.Swallow -> Unit

                Disposition.SwallowAndRestart ->
                    scheduleRestart(RestartReason.AfterError, TerminatingCause.ERROR, error)

                Disposition.RestartOnce -> {
                    audioErrorAlreadyOccurred = true
                    scheduleRestart(RestartReason.AfterError, TerminatingCause.ERROR, error)
                }

                Disposition.BackoffAndRestart -> {
                    enterReconnecting()
                    scheduleRestart(
                        RestartReason.AfterError,
                        TerminatingCause.ERROR,
                        error,
                        delayOverride = restartPolicy.nextRebindDelay(),
                    )
                }

                Disposition.RebindAndRestart -> {
                    enterReconnecting()
                    scheduleRestart(
                        RestartReason.AfterError,
                        TerminatingCause.ERROR,
                        error,
                        delayOverride = restartPolicy.nextRebindDelay(),
                        rebind = true,
                    )
                }

                is Disposition.Fatal -> fail(disposition.error)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            if (!accept()) return
            // Reserved by the platform for service-specific events. Nothing to do — fenced anyway.
        }

        override fun onLanguageDetection(results: Bundle) {
            if (!accept()) return
            // v1c is single-language (D-13); the detection surface is v2's. Fenced anyway.
        }
    }

    private companion object {
        /** Sentinel handed to the accumulator when the platform's bundle carried no text. */
        const val NO_PLATFORM_FINAL = ""

        /** `ERROR_RECOGNIZER_BUSY`, as a raw Binder-delivered integer. */
        const val RAW_ERROR_RECOGNIZER_BUSY = 8
    }
}

package io.github.ygaray.sttengine.internal

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.ReconnectInfo
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttEvent
import io.github.ygaray.sttengine.corrections.Corrections
import java.util.Locale
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
    private val audioPipeFactory: () -> AudioSourcePipe = { AudioSourcePipe() },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val restartPolicy: RestartPolicy = RestartPolicy(),
    private val deviceLocale: Locale = Locale.getDefault(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SttEngine {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** Whether [config] requested `"auto"` bilingual capture, regardless of platform support. */
    private val isAutoSession: Boolean = config.language == AUTO_LANGUAGE

    /**
     * Whether this session actually runs native on-device EN/ES detection — `"auto"` AND
     * `sdkInt >= 34` (D-03). On a sub-34 runtime an `"auto"` session still starts, but it falls back
     * to [AUTO_SUB34_FALLBACK] with no switch extras and no detection tracking (NBIL-04/NBIL-07).
     */
    private val autoDetectionActive: Boolean = isAutoSession && sdkInt >= 34

    /**
     * [config] with its [SttConfig.language] resolved for the platform recognizer (see
     * [LanguageTags]). This is the ONLY language the native preflight and the recognition intent
     * ever see — a bare `en` reaches SODA as `en-US` instead of being rejected with code 12, and
     * `"auto"` is resolved to a concrete starting language BEFORE region-qualification ever sees the
     * literal `"auto"` (D-03 — `LanguageTags.regionQualified("auto", deviceLocale)` would otherwise
     * return `"auto-US"`, which fails preflight). The public [config] is left untouched so the host
     * still observes exactly what it passed.
     */
    private val resolvedConfig: SttConfig =
        SttConfig(
            language = when {
                autoDetectionActive -> AUTO_STARTING_LANGUAGE
                isAutoSession -> LanguageTags.regionQualified(AUTO_SUB34_FALLBACK, deviceLocale)
                else -> LanguageTags.regionQualified(config.language, deviceLocale)
            },
            prefer = config.prefer,
            serverUrl = config.serverUrl,
            // IN-02 (13-REVIEW.md): forwarded for consistency with `config` — a future reader who
            // reasonably assumes `resolvedConfig` is simply "`config` with a region-qualified
            // language" would otherwise silently read back `null` here regardless of what the host
            // configured. Harmless today (only `.language` is read off `resolvedConfig`; `emitFinal`
            // already reads `config.corrections` directly, never `resolvedConfig.corrections`), but
            // forwarding it removes the trap.
            corrections = config.corrections,
        )

    // --- the public surface (D-07/D-08) -----------------------------------------------------------

    private val _state = MutableStateFlow(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Fixed at [Backend.Native] for this engine's entire life (Phase 9, SEL-03). `SttEngineImpl` is
     * the on-device-only engine — it never runs against the server — so this never mutates, unlike
     * `internal.BackendSelector`'s own `backend`, which does.
     */
    override val backend: StateFlow<Backend> = MutableStateFlow(Backend.Native).asStateFlow()

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

    /**
     * The app-owned microphone, live only while the session is running on rung (c). `null` on every
     * other rung, because on those the recognition service owns the microphone and this library must
     * not also hold it.
     */
    private var audioPipe: AudioSourcePipe? = null
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

    /**
     * The last `confidenceLevel == HIGHLY_CONFIDENT` language seen since the current segment
     * boundary (bare `en`/`es` only, normalized — D-02). `null` means "unknown for this segment", not
     * "English" — distinguishable from a real detection so a host is never told the system is
     * confident when it is not (NBIL-02). Only ever mutated by [onLanguageDetection] when
     * [autoDetectionActive] is true; stamped at commit by [languageForFinal] and cleared on every
     * segment boundary by [advanceSegmentBoundary] — main-thread-confined, no lock (D-09).
     */
    private var detectedLanguage: String? = null

    /**
     * Runtime evidence that a rung does not work on this device, accumulated across sessions.
     *
     * Set when — and only when — that rung's own degradation trigger fires, which by
     * [RungLadder.shouldDegrade]'s design means the rung was *proven* not to do its job rather than
     * merely having seen an error. Read once per [start] and never mid-session.
     *
     * One way, deliberately: nothing ever clears these within an engine instance. A device that
     * silently ignored the segmented extra will ignore it again, and re-probing costs a deaf window
     * every time. Fresh evidence needs a fresh engine.
     */
    private var segmentedRungWithdrawn = false
    private var rawAudioRungWithdrawn = false

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

        // 3. The language. Every outcome routes through the same D-18 preflight table, extended
        //    under "auto" (NBIL-10) to check BOTH en-US and es-US before any session/listener is
        //    attached — en-US checked first, per AUTO_SWITCH_ALLOWED_LANGUAGES' literal order, so
        //    which language's error surfaces when both are missing is deterministic. Non-"auto" and
        //    sub-34-"auto" sessions still issue exactly one languageAvailability call, byte-identical
        //    to before this plan (NBIL-05/NBIL-07).
        val preflightError = if (autoDetectionActive) {
            val availabilities = created.languageAvailability(RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES)
            RecognizerIntents.AUTO_SWITCH_ALLOWED_LANGUAGES.firstNotNullOfOrNull { lang ->
                preflightErrorFor(lang, availabilities[lang] ?: LanguageAvailability.UNDETERMINED)
            }
        } else {
            preflightErrorFor(resolvedConfig.language, created.languageAvailability(resolvedConfig.language))
        }
        if (preflightError != null) {
            fail(preflightError)
            return
        }

        // The capability is a PARAMETER resolved here, once per start(), and never a compile-time
        // constant — see `05-RUNG-DECISION.md`. It is the constructor's seed (the probe's answer)
        // MINUS anything this engine has since watched fail on this device: a rung whose degradation
        // trigger fired proved at runtime that it does not do its job here, and re-offering it at the
        // next start() would buy another silent no-op and another deaf window for the same answer.
        //
        // Resolved ONLY here. Mid-session the ladder walks DOWN through `candidates` and never
        // re-reads these flags, because a session is not the place to change what the device can do.
        //
        // The third term is the debug rung ceiling — `null` in every shipped build and unassignable
        // there, so this reads exactly as it did before the seam existed. It exists because rung (c)
        // is ABSORBING on a device that supports it (its session ends only when the audio source
        // ends, and an app-owned source never does), which makes AND-02's forced-restart evidence
        // unreachable without a way to ask for a lower rung. See `DebugRungCeilingHolder`.
        val ceiling = DebugRungCeilingHolder.ceiling
        candidates = RungLadder.candidates(
            supportsSegmented = supportsSegmented && !segmentedRungWithdrawn &&
                ceiling != Rung.RestartingSingleSession,
            supportsRawAudio = supportsRawAudio && !rawAudioRungWithdrawn &&
                (ceiling == null || ceiling == Rung.RawAudioSegmented),
        )
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
        advanceSegmentBoundary()

        if (!terminal) setState(EngineState.Idle)
        pendingStart?.complete(Unit)
        pendingStart = null
    }

    /**
     * D-18's four-way language-preflight mapping, as a pure function (NBIL-10 extension).
     *
     * The exact same mapping the single-language `when` block used before this plan, now shared by
     * both the non-`"auto"`/sub-34 single-language preflight and the `"auto"` dual-language preflight
     * so there is exactly one place this table lives.
     */
    private fun preflightErrorFor(language: String, availability: LanguageAvailability): SttError? =
        when (availability) {
            LanguageAvailability.SUPPORTED_NOT_INSTALLED ->
                ErrorMapper.mapPreflight(PreflightFailure.LanguageNotInstalled(language))

            LanguageAvailability.DOWNLOAD_PENDING ->
                ErrorMapper.mapPreflight(PreflightFailure.LanguageDownloadPending(language))

            LanguageAvailability.NOT_SUPPORTED ->
                ErrorMapper.mapPreflight(PreflightFailure.LanguageNotSupported(language))

            // A query that could not answer must never stop a session that would otherwise work.
            LanguageAvailability.INSTALLED, LanguageAvailability.UNDETERMINED -> null
        }

    // --- the session loop ---------------------------------------------------------------------------

    private suspend fun startSession() {
        val active = handle ?: return
        val boundEpoch = epoch.current
        sessionStartedAtMs = monotonicMs()
        sawBeginningOfSpeech = false
        segmentResultCount = 0
        endOfSegmentedSessionDelivered = false

        // Rung (c) owns the microphone itself, so the pipe has to exist before the intent does —
        // a rung-(c) intent with no descriptor is a hard failure by construction. This can degrade
        // the rung, so it must run BEFORE the rung is logged.
        val pipe = openAudioPipeForRung()

        log.rung(currentRung)
        val intent =
            RecognizerIntents.forRung(
                currentRung,
                resolvedConfig,
                appContext,
                audioSource = pipe?.readDescriptor,
                enableLanguageSwitch = autoDetectionActive,
            )
        withContext(mainDispatcher) {
            active.setListener(listenerFor(boundEpoch))
            active.startListening(intent)
        }
        selfInflictedTeardown = false
    }

    /**
     * Open the app-owned pipe when the current rung needs one.
     *
     * A microphone we cannot open is **evidence the rung does not work here**, not a reason to fail
     * the session: the whole point of the ladder is that there is always something below. So this
     * degrades and returns `null`, and the caller builds the lower rung's intent instead — within
     * the same `startSession`, with no extra deaf window and nothing for the host to see.
     */
    private fun openAudioPipeForRung(): AudioSourcePipe? {
        if (currentRung != Rung.RawAudioSegmented) return null
        val pipe = audioPipeFactory()
        return try {
            pipe.open()
            audioPipe = pipe
            pipe
        } catch (unopenable: Exception) {
            pipe.close()
            audioPipe = null
            withdrawRung(Rung.RawAudioSegmented)
            currentRung = RungLadder.next(Rung.RawAudioSegmented, candidates)
            null
        }
    }

    /**
     * The restart sequence. **The order is the whole point.**
     *
     * ```
     * 1. commit the live partial      ← BEFORE teardown: teardown is WHAT CAUSES the missing final
     * 2. tear the session down        ← closes the app-owned pipe FIRST, then the recognizer
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
            advanceSegmentBoundary()

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
     * Both segmented rungs are live (`05-RUNG-DECISION.md`), so the trigger has real work to do. It
     * degrades **within one session** — the next session after the evidence runs a rung lower — and
     * it additionally *withdraws* the rung so the next [start] does not offer it again. Degradation
     * is one way: nothing here can return a higher rung than the one it was handed.
     */
    private fun recordOutcome(cause: TerminatingCause, errorCode: Int?) {
        restartPolicy.recordSessionOutcome(
            endedWithError = cause == TerminatingCause.ERROR,
            sawBeginningOfSpeech = sawBeginningOfSpeech,
            sessionDurationMs = monotonicMs() - sessionStartedAtMs,
            recognizerBusy = errorCode == RAW_ERROR_RECOGNIZER_BUSY,
        )
        if (RungLadder.shouldDegrade(currentRung, outcomeSoFar(cause, errorCode))) {
            withdrawRung(currentRung)
            currentRung = RungLadder.next(currentRung, candidates)
        }
    }

    /**
     * What the live session has done so far, as the pure holder the ladder judges.
     *
     * One builder, two callers — [recordOutcome] after the session is over and [rungAbsorbs] while
     * the terminating callback is still on the stack — so the two can never disagree about what the
     * evidence was. `appOwnedPipeOpen` is read here rather than stored, and both callers run BEFORE
     * teardown closes the pipe: that timing is what separates the upstream data-loss signature (a
     * code 5 while our write end is still open) from a code 5 provoked by our own teardown, which
     * the probe measured arriving after six successful segment finals on the rung scoring precision
     * 1.000.
     */
    private fun outcomeSoFar(cause: TerminatingCause, errorCode: Int?): SessionOutcome =
        SessionOutcome(
            cause = cause,
            endOfSegmentedSessionDelivered = endOfSegmentedSessionDelivered,
            segmentResultCount = segmentResultCount,
            appOwnedPipeOpen = audioPipe?.isOpen == true,
            errorCode = errorCode,
        )

    /**
     * Whether an otherwise-fatal [error] indicts the RUNG rather than the engine.
     *
     * Rung (c)'s headline hazard — `ERROR_CLIENT(5)` arriving while our own pipe is still open — maps
     * to a fatal disposition in the error table, and correctly so: on any other rung an unprovoked
     * client error is not something the engine caused or can fix. But on a rung that has somewhere
     * to fall, that same code is the *documented* signature of the app-owned audio source
     * misbehaving, and killing the whole engine over it would throw away a working dictation session
     * to punish an optional rung. Without this the ladder's code-5 trigger could never fire at all,
     * because `fail()` does not run the restart path that judges the session.
     *
     * Narrow by construction, and the narrowness is load bearing. The ladder's **other** rung-(c)
     * trigger — a session that produced no segment results — deliberately does NOT get to swallow a
     * fatal error, because a genuinely fatal condition (a revoked permission, a recognizer that
     * vanished) also produces a session with no segments. Judging on that would walk the ladder
     * silently downwards while never telling the host why dictation stopped working, which is
     * strictly worse than the error it was trying to avoid. That condition keeps its proper job on
     * the ordinary restart path, in [recordOutcome], where the session really did just end quietly.
     *
     * So: only rung (c), only its one documented hazard, only while our own write end is still open,
     * and only when there is genuinely a lower rung to fall to. The floor can never reach it.
     */
    private fun rungAbsorbs(error: Int): Boolean {
        if (currentRung != Rung.RawAudioSegmented) return false
        if (error != RAW_ERROR_CLIENT || audioPipe?.isOpen != true) return false
        // Confirmed against the ladder rather than decided here, so the signature has exactly one
        // definition in the codebase and this cannot drift away from it.
        return RungLadder.shouldDegrade(currentRung, outcomeSoFar(TerminatingCause.ERROR, error)) &&
            RungLadder.next(currentRung, candidates) != currentRung
    }

    /** Records that [rung] proved itself a no-op here, so the next [start] stops offering it. */
    private fun withdrawRung(rung: Rung) {
        when (rung) {
            Rung.SilenceSegmented -> segmentedRungWithdrawn = true
            Rung.RawAudioSegmented -> rawAudioRungWithdrawn = true
            // The floor is never withdrawn — shouldDegrade is unconditionally false for it and
            // there is nothing below it to fall to.
            Rung.RestartingSingleSession -> Unit
        }
    }

    private suspend fun teardownSession(destroyHandle: Boolean) {
        // The pipe closes FIRST — before the return below, so a null handle cannot leak it, and
        // before anything is asked of the recognizer.
        //
        // Both callers commit the live partial before they get here, which makes the full order
        // COMMIT -> CLOSE THE PIPE -> ADVANCE THE EPOCH. That order is the mitigation for the
        // upstream data-loss signature: the words already spoken are committed before the write end
        // closes, so even on a device where a close mid-read kills the open segment, nothing the
        // user said is lost. Closing the write end is also the ENDING THIS RUNG PREFERS — a clean
        // end of stream, which the probe measured this device finalising gracefully on, rather than
        // asking the recognizer to stop while it is still reading.
        audioPipe?.close()
        audioPipe = null

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

        // Before the epoch advances, and synchronously rather than inside the launch below: an
        // engine that has failed terminally must not still be holding the microphone, and a
        // terminal failure is exactly the path a leak would survive unnoticed on.
        audioPipe?.close()
        audioPipe = null

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

    /**
     * Advance to the next segment: close this one in [accumulator], then clear [detectedLanguage]
     * so the next segment starts with no label carried over (D-02's per-segment reset — NBIL-09's
     * "never carry a stale label across a boundary"). Called at every one of the three points the
     * session closes a segment; the commit that consumes [detectedLanguage] (via [emitFinal]) always
     * runs BEFORE its call site calls this, preserving the stamp-then-clear ordering.
     */
    private fun advanceSegmentBoundary() {
        accumulator.onSegmentBoundary()
        detectedLanguage = null
    }

    /**
     * The label to stamp on the NEXT committed [FinalSegment] (D-01/D-02). `null` for any non-
     * `"auto"` session (byte-identical to today, NBIL-07); [AUTO_SUB34_FALLBACK] (bare `"en"`) for a
     * sub-34 `"auto"` session, which never runs detection (NBIL-04); the tracked [detectedLanguage]
     * — possibly still `null` if no confidence-3 event has been seen this segment — for an
     * API-34+ `"auto"` session.
     */
    private fun languageForFinal(): String? = when {
        autoDetectionActive -> detectedLanguage
        isAutoSession -> AUTO_SUB34_FALLBACK
        else -> null
    }

    private fun emitFinal(rawSegment: FinalSegment) {
        _partial.value = ""
        // Android origin point 1 of 3 (RESEARCH.md Pitfall 1) — the on-device-only path. Correction
        // is applied here BEFORE any emission, constructing a new FinalSegment with the SAME
        // segmentId (mirrors web's D-01 exactly). ServerBackend's final branch and
        // BackendSelector's handoff-commit are the other two real origin points (13-02) — do NOT
        // re-apply at BackendSelector's forwarding sites, which would double-apply already-corrected
        // segments. Both branches construct a FRESH FinalSegment so the language [label] (D-01) is
        // carried regardless of whether a correction ruleset is configured — rawSegment itself is
        // always language-free (SegmentAccumulator stays language-free per D-01).
        val label = languageForFinal()
        val segment =
            config.corrections?.takeIf { it.isNotEmpty() }
                ?.let { FinalSegment(Corrections.apply(rawSegment.text, it), rawSegment.segmentId, label) }
                ?: FinalSegment(rawSegment.text, rawSegment.segmentId, label)
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
            // A segmented session continues after a segment: close the segment, do NOT restart.
            //
            // This is where the AND-02 id gap came from, and it is worth naming. When the session
            // then ends, `scheduleRestart` announces a SECOND boundary with an empty buffer — so a
            // counter that advanced on every boundary spent an id no final ever carried. The
            // accumulator now spends an id only on a boundary that closes a committed segment, so
            // announcing one here AND at the restart is safe rather than merely conventional.
            advanceSegmentBoundary()
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

                // A fatal error on a rung that has somewhere to fall is a RUNG failure before it is
                // an ENGINE failure — but only when the ladder itself says this evidence indicts
                // this rung. See rungAbsorbs.
                is Disposition.Fatal ->
                    if (rungAbsorbs(error)) {
                        scheduleRestart(RestartReason.AfterError, TerminatingCause.ERROR, error)
                    } else {
                        fail(disposition.error)
                    }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            if (!accept()) return
            // Reserved by the platform for service-specific events. Nothing to do — fenced anyway.
        }

        override fun onLanguageDetection(results: Bundle) {
            if (!accept()) return
            // Non-"auto" and sub-34-"auto" sessions never track a detected label — NBIL-07: the
            // "auto" path's extra tracking must not leak onto any other session.
            if (!autoDetectionActive) return
            // Untrusted cross-process input (T-18-01) — defensive `getInt` with the documented `-1`
            // sentinel default, matching RecognizerBundles' own no-`!!` discipline. Only a
            // HIGHLY_CONFIDENT event may mutate the tracked label; anything lower is ignored.
            val confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, -1)
            if (confidence == HIGHLY_CONFIDENT_LEVEL) {
                val raw = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                detectedLanguage = LanguageTags.normalizeDetected(raw)
            }

            // NBIL-10 runtime signal: the platform reports it SKIPPED a language switch because the
            // target language's on-device pack is missing — the same "loud, never silent" typed
            // error the preflight uses, routed through the SAME idempotent fail() funnel. Untrusted
            // cross-process input (T-18-05): defensive `getInt` with a `-1` sentinel; only the exact
            // documented SKIPPED_NO_MODEL value (3) triggers a failure, any other value (including a
            // hostile/out-of-range Int) is ignored.
            val switchResult = results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, -1)
            if (switchResult == SWITCH_RESULT_SKIPPED_NO_MODEL) {
                // Defensive, no `!!` — the platform does not guarantee DETECTED_LANGUAGE is present
                // on a skipped-switch event (T-18-05, specless edge: empty). Fall back to a sentinel
                // that cannot be confused with a real BCP-47 locale tag — AUTO_LANGUAGE ("auto") is
                // SttConfig.language's own request value, not a locale, and surfacing it in
                // SttError.message would read as "download a model literally named auto".
                val missingLanguage =
                    results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: UNKNOWN_LANGUAGE_SENTINEL
                fail(ErrorMapper.mapPreflight(PreflightFailure.LanguageNotInstalled(missingLanguage)))
            }
        }
    }

    private companion object {
        /** Sentinel handed to the accumulator when the platform's bundle carried no text. */
        const val NO_PLATFORM_FINAL = ""

        /** `ERROR_RECOGNIZER_BUSY`, as a raw Binder-delivered integer. */
        const val RAW_ERROR_RECOGNIZER_BUSY = 8

        /** `ERROR_CLIENT`. Rung (c)'s documented hazard when it arrives while our pipe is open. */
        const val RAW_ERROR_CLIENT = 5

        /** The value [SttConfig.language] carries to request native bilingual EN/ES detection. */
        const val AUTO_LANGUAGE = "auto"

        /** Sentinel used when a `SKIPPED_NO_MODEL` [onLanguageDetection] event carries no
         *  `DETECTED_LANGUAGE` key (the specless edge T-18-05 defends against). Deliberately NOT
         *  [AUTO_LANGUAGE] — that string is [SttConfig.language]'s own request value, not a real
         *  BCP-47 locale tag, and surfacing it via [PreflightFailure.LanguageNotInstalled] would
         *  read as "the on-device model for \"auto\" is supported but not installed," which no host
         *  can act on since no platform model is ever named `"auto"`. */
        const val UNKNOWN_LANGUAGE_SENTINEL = "unknown"

        /** Sub-34 fallback for an `"auto"` session — the library's existing bare default. */
        const val AUTO_SUB34_FALLBACK = "en"

        /** The concrete API-34+ starting language for an `"auto"` session's recognition intent. */
        const val AUTO_STARTING_LANGUAGE = "en-US"

        /** `SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT` (== 3). Only a
         *  detection at this confidence level is trusted enough to stamp a label (D-02/NBIL-02). */
        val HIGHLY_CONFIDENT_LEVEL = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT

        /** `SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL` (== 3) — the platform skipped
         *  a language switch mid-session because the target language's on-device pack is missing
         *  (NBIL-10). Routes through the same [fail]/`NO_OFFLINE_MODEL` funnel as the preflight. */
        val SWITCH_RESULT_SKIPPED_NO_MODEL = SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL
    }
}

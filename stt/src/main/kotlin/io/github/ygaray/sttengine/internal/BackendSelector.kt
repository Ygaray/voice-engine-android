package io.github.ygaray.sttengine.internal

import android.content.Context
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.SttEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The try-server-then-fallback policy, implementing the public [SttEngine] contract (Phase 9,
 * SEL-01/SEL-02/SEL-03/D-04).
 *
 * This sits ONE LEVEL ABOVE the on-device rung ladder — a peer to [SttEngineImpl], never a `Rung`
 * (D-03: "The selector sits one level above the rung ladder... a peer, never a `Rung`."). Wired by
 * [io.github.ygaray.sttengine.createSttEngine] instead of a bare [SttEngineImpl] exactly when
 * `config.serverUrl != null && config.prefer != BackendPreference.OnDevice`; every other
 * `createSttEngine(context)` call stays byte-identical to v1.0.
 *
 * ## The policy (`start()`)
 *
 * 1. `prefer == OnDevice`, or no `serverUrl` configured: skip the server phase ENTIRELY — no
 *    [ServerBackend]/[WebSocketFactory] construction happens at all on this path (SEL-02).
 * 2. Otherwise, bound [ServerBackend.connect] with `withTimeoutOrNull(tryServerTimeoutMs)` at the
 *    COROUTINE level (RESEARCH.md Pattern 2 / Pitfall 1 — never `OkHttpClient.connectTimeout`
 *    alone, which bounds only the raw TCP+TLS handshake, not the WS-upgrade-then-`ready` round
 *    trip this window actually needs).
 * 3. On success: [backend] becomes [Backend.Server] and every [ServerBackend] flow is forwarded
 *    into this selector's own equivalent flow via one launched collector per property (mirrors
 *    [SttEngineImpl]'s single-state-mutation-point discipline, applied to a whole upstream engine).
 * 4. On timeout/failure: [ServerBackend.abandon] closes the half-open socket without emitting any
 *    host-visible state (D-04's swallow mechanism — the failed instance's flows are simply never
 *    forwarded, so nothing it already produced internally is ever observed). Then:
 *    - `prefer == Server`: [fail] with the typed error [ServerBackend] produced (or
 *      [SttErrorCode.SERVER_UNREACHABLE] if the failure was a bare coroutine timeout that never
 *      reached an OkHttp callback) — no native fallback in this mode (SEL-02's force-server
 *      semantics).
 *    - Otherwise (`prefer == Auto`): fall through to native, exactly like the v1.0 default path
 *      (SEL-01's silent swallow of the connect timeout / unreachable / `busy` close-1013
 *      conditions — no typed error reaches the host on this branch).
 *
 * ## Everything is injected (mirrors [SttEngineImpl])
 *
 * [webSocketFactory] is accepted here rather than a raw `OkHttpClient` — `OkHttpClient`/`WebSocket`
 * are concrete platform classes with no fake-friendly seam of their own, so the factory entry point
 * wraps a shared `OkHttpClient` in [OkHttpWebSocketFactory] before it reaches this constructor,
 * while `BackendSelectorTest` injects a [FakeWebSocketFactory] directly — the same DI discipline
 * [SttEngineImpl] uses for [RecognizerHandleFactory].
 */
internal class BackendSelector(
    private val appContext: Context,
    private val config: SttConfig,
    private val handleFactory: RecognizerHandleFactory,
    private val permissions: PermissionChecker,
    private val log: SessionLog = SessionLog(),
    private val webSocketFactory: WebSocketFactory,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** **DEFERRED, not tuned** (D-05). Plan 09-03 built `ServerHandoffProbeTest` (the on-device
     *  probe meant to measure this) but could NOT run it: at execution time the only adb-visible
     *  device did not match the tester's documented tailnet IP, so device identity could not be
     *  confirmed — see
     *  `.planning/phases/09-android-server-backed-live-backend-backend-selection/09-HANDOFF-EVIDENCE.md`
     *  for the full record and the exact reproduction procedure a later Gate-1 run should follow.
     *  This value therefore remains the SAME unmeasured placeholder Plan 09-01 introduced — it is
     *  explicitly NOT "measured, then happened to keep the same number." Do not read the absence of
     *  the word PLACEHOLDER-in-caps as evidence this was tuned; re-run the deferred probe and update
     *  this KDoc (and the value, per D-05: 2x the measured median RTT, never less than the measured
     *  worst-case trial) once real numbers exist. It bounds the FULL connect→config→`ready`
     *  sequence, not just the TCP+TLS handshake. */
    private val tryServerTimeoutMs: Long = DEFAULT_TRY_SERVER_TIMEOUT_MS,
    private val serverBackendFactory: (String) -> ServerBackend = { url ->
        ServerBackend(
            webSocketFactory = webSocketFactory,
            serverUrl = url,
            language = config.language,
            mainDispatcher = mainDispatcher,
        )
    },
) : SttEngine {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _state = MutableStateFlow(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _backend = MutableStateFlow(Backend.Native)
    override val backend: StateFlow<Backend> = _backend.asStateFlow()

    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial.asStateFlow()

    private val finalsChannel = Channel<FinalSegment>(Channel.UNLIMITED)
    override val finals: Flow<FinalSegment> = finalsChannel.receiveAsFlow()

    private val errorsChannel = Channel<SttError>(Channel.UNLIMITED)
    override val errors: Flow<SttError> = errorsChannel.receiveAsFlow()

    private val _level = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val level: Flow<Float> = _level.asSharedFlow()

    /** The merged union — built as an ordered mirror, same reasoning as [SttEngineImpl.events]:
     *  [finals]/[errors] are channel-backed and single-consumer, so merging them here would let an
     *  `events` collector steal from a dedicated `finals`/`errors` collector. */
    private val _events = MutableSharedFlow<SttEvent>(
        replay = 0,
        extraBufferCapacity = EVENTS_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<SttEvent> = _events.asSharedFlow()

    private var terminal = false
    private var activeServer: ServerBackend? = null
    private var activeNative: SttEngineImpl? = null

    /** One-shot guard for [handleServerDrop] — SEL-04's handoff is one-way per session. */
    private var handoffStarted = false

    /**
     * The in-flight [ServerBackend] under a try-server connect attempt — assigned the moment it is
     * constructed, BEFORE `connect()` is awaited, and cleared once the try-server phase resolves
     * either way. This is what lets [stop] abandon it even while [start] is still suspended inside
     * the try-server window (CR-03: closes the "`stop()` during try-server does nothing" race —
     * without this field, `stop()` finds [activeServer] `null` for the entire window, because that
     * field is only assigned on a successful connect).
     */
    private var pendingServer: ServerBackend? = null

    /**
     * The job actually running [runStart] — launched on [scope] rather than executed inline in the
     * caller's own coroutine, specifically so [stop] has something it can cancel regardless of which
     * phase `start()` is currently in (CR-03's other half: [pendingServer] stops a leaked mic/socket,
     * this stops the REST of `start()`'s sequence — e.g. a still-pending `startNative()` — from
     * running to completion and mutating [state]/[backend] after `stop()` already returned).
     *
     * [start] itself only launches this job and joins it — [Job.join] never throws on cancellation,
     * so a `stop()`-triggered cancellation here never propagates out of `start()` to the host; it
     * simply makes `start()` return once the cancellation settles, exactly like an ordinary
     * completion would.
     */
    private var startJob: Job? = null

    override suspend fun start() {
        val job = scope.launch { runStart() }
        startJob = job
        job.join()
    }

    private suspend fun runStart() {
        val serverUrl = config.serverUrl
        if (serverUrl != null && config.prefer != BackendPreference.OnDevice) {
            // CR-04: mirrors SttEngineImpl.start()'s own first line — a host rendering a
            // "connecting…" affordance off `state == Connecting` must see it during the try-server
            // window too, not just on the native-only path.
            setState(EngineState.Connecting)
            val serverBackend = serverBackendFactory(serverUrl)
            pendingServer = serverBackend
            val connected = try {
                withTimeoutOrNull(tryServerTimeoutMs) { serverBackend.connect() }
            } finally {
                // The try-server phase has resolved one way or another (success, timeout, failure,
                // or a stop()-triggered cancellation) — nothing further should treat this instance
                // as "pending" once we leave this block.
                pendingServer = null
            }
            if (connected == true) {
                activeServer = serverBackend
                setBackend(Backend.Server)
                forwardServer(serverBackend)
                return
            }

            // Never leak the half-open socket — abandon it BEFORE deciding what happens next.
            // Emits nothing on its own errors/state (D-04): this IS the swallow mechanism, not a
            // special case bolted on top of it. Idempotent — a no-op if stop() already abandoned
            // this instance via pendingServer above.
            serverBackend.abandon()

            if (config.prefer == BackendPreference.Server) {
                // prefer:server means server-only — no native fallback (SEL-02).
                fail(
                    serverBackend.lastError
                        ?: SttError(SttErrorCode.SERVER_UNREACHABLE, "server unreachable"),
                )
                return
            }
            // Otherwise (Auto): fall through to native below — SEL-01's silent swallow of the
            // connect timeout / unreachable / busy (close 1013) conditions.
        }

        setBackend(Backend.Native)
        startNative()
    }

    override suspend fun stop() {
        // CR-03: cancel whatever `start()` sequence is still in flight FIRST — before touching
        // activeServer/activeNative — so nothing further in that sequence (a still-pending
        // startNative() call, most notably) can mutate state/backend after this stop() returns.
        startJob?.cancel()
        // Belt-and-suspenders alongside the cancellation above: abandon a try-server attempt that
        // is still pending RIGHT NOW, in case stop() is racing runStart() between two suspension
        // points where cancellation has not yet been delivered. abandon() is idempotent.
        pendingServer?.abandon()
        pendingServer = null
        activeServer?.stop()
        activeNative?.stop()
    }

    private fun setBackend(next: Backend) {
        if (_backend.value == next) return
        _backend.value = next
    }

    private fun setState(next: EngineState) {
        if (_state.value == next) return
        _state.value = next
        _events.tryEmit(SttEvent.State(next))
    }

    /**
     * One launched collector per property — mirrors [SttEngineImpl]'s single-state-mutation-point
     * discipline, applied here to a whole upstream engine rather than one field at a time.
     *
     * The `state` collector deliberately DROPS [ServerBackend]'s own [EngineState.Error]: once this
     * selector has committed to the server path, ANY [ServerBackend] failure is handled by
     * [handleServerDrop]'s one-way handoff (SEL-04, D-03), never mirrored onto this selector's own
     * `state` — the whole transition stays invisible on the shared contract (`state` never even
     * touches `Error`, let alone `Reconnecting`), exactly like an ordinary native restart already is.
     */
    private fun forwardServer(serverBackend: ServerBackend) {
        scope.launch {
            serverBackend.state.collect { next -> if (next != EngineState.Error) setState(next) }
        }
        scope.launch {
            serverBackend.partial.collect { text ->
                _partial.value = text
                _events.tryEmit(SttEvent.Partial(text))
            }
        }
        scope.launch {
            serverBackend.finals.collect { segment ->
                finalsChannel.trySend(segment)
                _events.tryEmit(SttEvent.Final(segment))
            }
        }
        scope.launch {
            serverBackend.errors.collect { handleServerDrop(serverBackend) }
        }
        scope.launch {
            serverBackend.level.collect { level ->
                _level.tryEmit(level)
                _events.tryEmit(SttEvent.Level(level))
            }
        }
        // CR-02: ServerBackend.warnings' own KDoc documents this selector as the ONLY place
        // SttWarning surfaces on the frozen contract — this collector is that wire. Note that in the
        // currently shipped code this is still unreachable in practice: sendPcmFrame (the only
        // producer that feeds ServerBackpressureGate) has no production caller yet — the continuous
        // audio-pump loop is deferred to Plan 09-03 (see ServerBackend's own KDoc). This collector
        // makes the forwarding correct so that work lands as a pure wiring change, not a second
        // missing wire, once the pump exists.
        scope.launch {
            serverBackend.warnings.collect { warning -> _events.tryEmit(SttEvent.Warning(warning)) }
        }
    }

    /**
     * The one-way server→native handoff (Phase 9 Task 1, SEL-04, D-03). **The order is the whole
     * point** — mirrors [SttEngineImpl.scheduleRestart]'s own restart-sequence discipline:
     *
     * ```
     * 1. commit the live partial          (words already spoken are never dropped)
     * 2. release the server-path mic      (release BEFORE any native acquire — two mic clients
     *                                       cannot coexist, T-09-06; the WebSocket is already closed
     *                                       by ServerBackend's OWN fail() funnel by the time an
     *                                       error reaches this collector at all)
     * 3. construct native and start it    (native acquisition happens only AFTER release)
     * ```
     *
     * `state` never transitions to [EngineState.Reconnecting] anywhere in this sequence, and
     * [ServerBackend]'s own terminal [EngineState.Error] is never mirrored onto this selector's
     * `state` in the first place ([forwardServer] filters it) — the whole transition is invisible on
     * the shared contract, exactly like an ordinary native restart already is (D-17). [backend] is
     * flipped to [Backend.Native] only once the native session actually reaches [EngineState.Listening]
     * (see [startNative]'s own state collector), never before — a host reading [backend] must never
     * observe `Native` ahead of native audio actually flowing.
     *
     * ONE-WAY: guarded by identity against [activeServer] plus a one-shot flag, so a second failure
     * — from an already-abandoned instance, or any other stray emission — is a no-op. This selector
     * never attempts the server again for the rest of this session's lifetime.
     */
    private suspend fun handleServerDrop(serverBackend: ServerBackend) {
        if (serverBackend !== activeServer || handoffStarted) return
        handoffStarted = true
        activeServer = null

        // 1. commit the live partial BEFORE any teardown.
        val partialText = serverBackend.partial.value
        if (partialText.isNotBlank()) {
            val segment = FinalSegment(partialText, HANDOFF_SEGMENT_ID)
            finalsChannel.trySend(segment)
            _events.tryEmit(SttEvent.Final(segment))
        }

        // 2. release the server-path mic — explicit here for the handoff's own documentation/
        //    testability even though ServerBackend.fail() already released it as part of its own
        //    teardown; idempotent, so this call is a safe no-op either way.
        serverBackend.releaseAudioSource()

        // 3. construct native and start it — native acquisition happens only AFTER release (above).
        startNative()
    }

    /**
     * Constructs the native fallback with IDENTICAL wiring to
     * [io.github.ygaray.sttengine.createSttEngine] — both segmented rungs on, the platform
     * recognizer factory, the context permission checker — so a session that falls back here
     * behaves byte-identically to a plain v1.0 engine for the rest of its life.
     */
    private suspend fun startNative() {
        val native = SttEngineImpl(
            appContext = appContext,
            config = config,
            handleFactory = handleFactory,
            permissions = permissions,
            log = log,
            supportsSegmented = true,
            supportsRawAudio = true,
            mainDispatcher = mainDispatcher,
        )
        activeNative = native
        scope.launch {
            native.state.collect { next ->
                // Only flip `backend` once native audio is ACTUALLY flowing (SEL-04) — never
                // before, so a host reading `backend` never observes Native ahead of native audio.
                // A no-op on the plain v1.0/native-only path, where `backend` is already Native
                // from construction.
                if (next == EngineState.Listening) setBackend(Backend.Native)
                setState(next)
            }
        }
        scope.launch {
            native.partial.collect { text ->
                _partial.value = text
                _events.tryEmit(SttEvent.Partial(text))
            }
        }
        scope.launch {
            native.finals.collect { segment ->
                finalsChannel.trySend(segment)
                _events.tryEmit(SttEvent.Final(segment))
            }
        }
        scope.launch {
            native.errors.collect { error ->
                errorsChannel.trySend(error)
                _events.tryEmit(SttEvent.Error(error))
            }
        }
        scope.launch {
            native.level.collect { level ->
                _level.tryEmit(level)
                _events.tryEmit(SttEvent.Level(level))
            }
        }
        native.start()
    }

    /**
     * `prefer: server`'s force-surface path (D-04) — the ONLY way a try-server failure reaches the
     * host directly from this selector without a native attempt in between.
     */
    private fun fail(error: SttError) {
        if (terminal) return
        terminal = true
        errorsChannel.trySend(error)
        _events.tryEmit(SttEvent.Error(error))
        setState(EngineState.Error)
    }

    private companion object {
        /** DEFERRED, not tuned — see the constructor parameter's KDoc and 09-HANDOFF-EVIDENCE.md.
         *  Plan 09-03's on-device measurement could not be taken (device identity unconfirmed); this
         *  is still the same unmeasured value Plan 09-01 introduced. */
        const val DEFAULT_TRY_SERVER_TIMEOUT_MS: Long = 2_500L

        /** The synthetic [FinalSegment.segmentId] the SEL-04 handoff's own commit-on-drop uses.
         *  `segmentId` is opaque and per-session (API.md) — this is not part of [ServerBackend]'s own
         *  server-numbered sequence, just a safe fixed value for the one-off commit this path makes. */
        const val HANDOFF_SEGMENT_ID: Int = 0
    }
}

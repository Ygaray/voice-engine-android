package io.github.ygaray.sttengine.internal

import android.content.Context
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 *    host-visible state, then falls through to native — exactly like the v1.0 default path. **This
 *    plan (09-01) proves the mechanical fallback exists and reaches `Listening`; the swallow-vs-
 *    surface distinction for SEL-01's three specific failure conditions (timeout/unreachable/busy)
 *    and SEL-02's `prefer == Server` force-surface semantics (D-04) are Task 2's addition.**
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
    /** PLACEHOLDER pending the Plan 09-03 on-device measurement (D-05) — do NOT treat this value as
     *  tuned. It bounds the FULL connect→config→`ready` sequence, not just the TCP+TLS handshake. */
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

    override suspend fun start() {
        val serverUrl = config.serverUrl
        if (serverUrl != null && config.prefer != BackendPreference.OnDevice) {
            val serverBackend = serverBackendFactory(serverUrl)
            val connected = withTimeoutOrNull(tryServerTimeoutMs) { serverBackend.connect() }
            if (connected == true) {
                activeServer = serverBackend
                setBackend(Backend.Server)
                forwardServer(serverBackend)
                return
            }

            // Never leak the half-open socket — abandon it BEFORE falling through to native.
            // TASK 1: proves the mechanical fallback path exists and reaches Listening. The
            // swallow-vs-surface distinction for SEL-01's three failure conditions and SEL-02's
            // prefer=Server force-surface semantics (D-04) land in Task 2 (09-01-TASK-2).
            serverBackend.abandon()
        }

        setBackend(Backend.Native)
        startNative()
    }

    override suspend fun stop() {
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

    /** One launched collector per property — mirrors [SttEngineImpl]'s single-state-mutation-point
     *  discipline, applied here to a whole upstream engine rather than one field at a time. */
    private fun forwardServer(serverBackend: ServerBackend) {
        scope.launch { serverBackend.state.collect { setState(it) } }
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
            serverBackend.errors.collect { error ->
                errorsChannel.trySend(error)
                _events.tryEmit(SttEvent.Error(error))
            }
        }
        scope.launch {
            serverBackend.level.collect { level ->
                _level.tryEmit(level)
                _events.tryEmit(SttEvent.Level(level))
            }
        }
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
        scope.launch { native.state.collect { setState(it) } }
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
        /** PLACEHOLDER — see the constructor parameter's KDoc. Pending Plan 09-03 measurement. */
        const val DEFAULT_TRY_SERVER_TIMEOUT_MS: Long = 2_500L
    }
}

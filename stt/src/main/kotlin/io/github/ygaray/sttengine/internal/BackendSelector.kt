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
import io.github.ygaray.sttengine.corrections.Corrections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 2. Otherwise, run the try-server attempt loop ([tryServer], Phase 21, TRY-02) under ONE
 *    `withTimeoutOrNull(tryServerTimeoutMs)` at the COROUTINE level (RESEARCH.md Pattern 2 /
 *    Pitfall 1 — never `OkHttpClient.connectTimeout` alone, which bounds only the raw TCP+TLS
 *    handshake, not the WS-upgrade-then-`ready` round trip this window actually needs). Each attempt
 *    is a fresh single-attempt [ServerBackend]; a failed attempt is classified by connection phase
 *    with [classifyConnectFailure] (D-06):
 *    - **FastFail** — the host never answered (DNS, refused, unreachable, cleartext refused) or
 *      rejected the session deterministically (busy close 1013, an `error` frame, a non-5xx upgrade
 *      answer, a TLS certificate/hostname failure): the loop ends at once.
 *    - **Retryable** — the host answered but was not ready yet (an EOF before the response
 *      headers, a handshake the peer terminated, a reset, a 5xx upgrade, a close before `ready`,
 *      a socket timeout): retried after [RETRY_BACKOFF_MS] (250 → 500 → 1 000 ms, then 1 000 ms).
 *      This is the tailscale-serve cold start, which fails FAST, so a bigger window alone would not
 *      help.
 *    - **A black-holed host** (no callback at all) waits the full window (D-14): the window, not a
 *      short `connectTimeout`, is the only bound, and a socket timeout is retried rather than
 *      treated as a reason to fall back early.
 * 3. On success: [backend] becomes [Backend.Server] and every [ServerBackend] flow is forwarded
 *    into this selector's own equivalent flow via one launched collector per property (mirrors
 *    [SttEngineImpl]'s single-state-mutation-point discipline, applied to a whole upstream engine).
 * 4. On fast-fail or window expiry: every failed attempt was already abandoned by the loop
 *    ([ServerBackend.abandon] closes the half-open socket without emitting any host-visible state —
 *    D-04's swallow mechanism: the failed instances' flows are simply never forwarded). Then:
 *    - `prefer == Server`: [fail] with the latest typed error an attempt produced (or
 *      [SttErrorCode.SERVER_UNREACHABLE] if no attempt ever reached an OkHttp callback) — exactly
 *      one error, no native fallback in this mode (SEL-02's force-server semantics).
 *    - Otherwise (`prefer == Auto`): fall through to native, exactly like the v1.0 default path
 *      (SEL-01's silent swallow — no typed error reaches the host on this branch).
 *
 * Documented edge: a `start()` right after a previous session's flushing `stop()` can meet `busy`
 * (close 1013) while the server's single session slot is still being released; busy fast-fails, so
 * that session falls back to native instead of retrying.
 *
 * Each attempt writes one opt-in `server-attempt` trace line through [log] (class simple names,
 * counts, codes and booleans only — never a message, the URL or text).
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
    /** The try-server window: the host's [SttConfig.serverTryTimeoutMs] (TRY-01; default 10 000 ms,
     *  clamped to 1 000..10 000 ms by the config itself per D-07). It bounds the WHOLE attempt loop —
     *  connect → config → `ready`, retries and their backoffs included — as ONE deadline, not just
     *  the TCP+TLS handshake. The Phase-9 on-device probe that was meant to measure a fixed value
     *  (`ServerHandoffProbeTest`, never run) is superseded by connection-phase classification
     *  (D-06): a refused/DNS-class failure fails fast regardless of the window, so the window only
     *  has to cover a cold-starting host; the live cold-start capture is plan 21-08. `internal` (not
     *  private) so the factory-level test can prove the host's value arrives here; tests that pass
     *  an explicit value keep doing so. */
    internal val tryServerTimeoutMs: Long = config.serverTryTimeoutMs,
    private val serverBackendFactory: (String) -> ServerBackend = { url ->
        ServerBackend(
            webSocketFactory = webSocketFactory,
            serverUrl = url,
            language = config.language,
            mainDispatcher = mainDispatcher,
            corrections = config.corrections,
            vocabulary = config.vocabulary,
            retainForCorpus = config.retainForCorpus,
        )
    },
) : SttEngine {

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + mainDispatcher)

    /**
     * How many [stop] calls are still running — main-confined (every [stop] runs on
     * [mainDispatcher]). While it is above zero the session is still tearing down (the server flush
     * can take ~2.5 s), so [start]'s guard treats the session as active. See [start].
     */
    private var stopsInFlight = 0

    /**
     * Parent of the CURRENT session's forwarding collectors ([forwardServer], [startNative]) — every
     * one except the `finals` collectors. Cancelled and replaced at the top of [runStart], so a
     * previous session's state/partial/level/warning/error collectors can never write into the next
     * session's flows (and no longer pile up on a reused engine). The `finals` collectors are not
     * under it: [finals] is LOSSLESS, so they are never cancelled. They are drained and ended
     * instead — see [finalsForwarders]. `internal` only so a test can observe the cancellation.
     */
    internal var sessionForwarders: Job = SupervisorJob(scopeJob)
        private set

    /** One `finals` collector and the call that ends its source. See [finalsForwarders]. */
    private class FinalsForwarder(val job: Job, val closeSource: () -> Unit)

    /**
     * The current session's `finals` collectors, one per backend it forwarded (a server, a native
     * engine, or both after a handoff). Main-confined.
     *
     * [finals] is lossless, so these are never cancelled. They are retired by
     * [retireFinalsForwarders]: each backend's own `finals` is closed, and each collector is joined
     * once it has handed everything still buffered to [finals] and completed. That happens when
     * [stop] has settled the session, and again at the top of [runStart] for a session that ended
     * without a `stop()` (a terminal error). So a previous session's final is always delivered
     * BEFORE the next session resets anything or emits a single event, and no collector outlives its
     * session.
     */
    private val finalsForwarders = mutableListOf<FinalsForwarder>()

    /** The current session's live `finals` collectors. `internal` only so a test can prove they
     *  complete. Main-confined. */
    internal val finalsForwarderJobs: List<Job>
        get() = finalsForwarders.map { it.job }

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

    // `@Volatile` (LAST-01): [lastFinal]'s getter reads these, and a host may call it off the main
    // thread. Writes are main-confined: [start]'s guard and [stop] hop onto [mainDispatcher].
    @Volatile
    private var activeServer: ServerBackend? = null

    @Volatile
    private var activeNative: SttEngineImpl? = null

    /** One-shot guard for [handleServerDrop] — SEL-04's handoff is one-way per session. */
    private var handoffStarted = false

    /**
     * The segment [handleServerDrop]'s handoff commit emitted, if any (LAST-01, D-05) — written
     * synchronously at that emit-origin point as the SAME instance sent on [finals]. Reported by
     * [lastFinal] after a server→native handoff until native commits its own first final.
     */
    @Volatile
    private var handoffFinal: FinalSegment? = null

    /**
     * The committed [ServerBackend] after it stopped being [activeServer] — detached by [stop] (21-03)
     * or dropped by [handleServerDrop] — kept ONLY so [lastFinal] can still read the tail it flushed.
     * Never driven again; reset per session at the top of [runStart].
     */
    @Volatile
    private var lastServer: ServerBackend? = null

    /**
     * LAST-01 (D-05): read straight from the emit-origin points' own fields — native's
     * [SttEngineImpl.lastFinal], the handoff commit, or the server's [ServerBackend.lastFinal] —
     * NEVER from this selector's launched forwarding collectors, which run asynchronously and would
     * report the previous segment if read the instant [stop] returns.
     */
    override val lastFinal: FinalSegment?
        get() = activeNative?.lastFinal ?: handoffFinal ?: (activeServer ?: lastServer)?.lastFinal

    /**
     * CORR-07 (D-09, D-04 refined by D-17(c)): read synchronously from the detached or active
     * server backend, the same way [lastFinal] is. [ServerBackend.endedSessionId] is non-null only
     * after that backend finished a flushed `stop()`, so a live session, a failed or handed-off one
     * (whose backend never ran a stop) and a native session all read null. [runStart] clearing
     * [lastServer] at the top of a new session is the reset.
     */
    override val lastSessionId: String?
        get() = (activeServer ?: lastServer)?.endedSessionId

    /**
     * The in-flight [ServerBackend] under a try-server connect attempt — assigned the moment it is
     * constructed, BEFORE `connect()` is awaited, and cleared once the try-server phase resolves
     * either way. This is what lets [stop] abandon it even while [start] is still suspended inside
     * the try-server window (CR-03: closes the "`stop()` during try-server does nothing" race —
     * without this field, `stop()` finds [activeServer] `null` for the entire window, because that
     * field is only assigned on a successful connect).
     */
    private var pendingServer: ServerBackend? = null

    /** The latest typed error any attempt of the current try-server loop produced — what
     *  `prefer = Server` surfaces when the loop gives up (TRY-02). Reset per [tryServer]. */
    private var lastTryError: SttError? = null

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
     *
     * `internal` (read-only) only so a test can prove [stop] cancelled it.
     */
    internal var startJob: Job? = null
        private set

    /**
     * Re-entry mirrors [SttEngineImpl.start]'s own `if (running) return`: a `start()` while this
     * selector's session is still starting (the try-server loop, its backoffs, the native start) or
     * live (a committed server, or a native session that has not stopped or failed) is a no-op that
     * returns at once. Without it a second call ran a second [runStart] beside the first — two
     * attempt loops, two sockets, an older job [stop] could no longer cancel, and two native
     * recognizers. After [stop] or a terminal failure, `start()` begins a fresh session as before.
     *
     * **Threading.** The public contract does not confine callers to the main thread (D-09: "the
     * caller is never required to be on the main thread"), so two hosts' coroutines may call
     * `start()` at the same moment. The guard is therefore a check-and-install executed ON
     * [mainDispatcher] — the dispatcher every other piece of this selector's session bookkeeping
     * ([runStart], the forwarding collectors, [handleServerDrop], [stop]) already runs on. That
     * dispatcher is single-threaded (production: `Dispatchers.Main.immediate`), and the block has no
     * suspension point, so no other check, install, stop or session transition can interleave with
     * it: of two concurrent calls exactly one installs a job and the other sees it. On a caller that
     * is already on the main thread the hop is free — the immediate dispatcher runs it in place.
     *
     * **A `start()` during a `stop()` is ignored too.** A server-path [stop] suspends for the flush
     * (up to ~2.5 s) after detaching [activeServer] (D-04), and the session is not over until it
     * returns. Admitting a `start()` in that window would open a second socket mid-flush, and its
     * [runStart] would clear [lastServer] before the original `stop()` returned — breaking LAST-01's
     * promise that [lastFinal] holds the flushed tail as soon as `stop()` returns. So [stopsInFlight]
     * counts as an active session, the same "ignore while active" rule as above (rather than
     * queueing the start behind the teardown, which would race the stop caller's [lastFinal] read).
     * A host that awaits `stop()` before calling `start()` is unaffected.
     *
     * The job is launched LAZY and recorded in [startJob] BEFORE it runs, so a nested `start()`
     * issued while [runStart] is executing eagerly (an immediate main dispatcher) still sees it.
     */
    override suspend fun start() {
        val job = withContext(mainDispatcher) {
            if (sessionActive()) return@withContext null
            scope.launch(start = CoroutineStart.LAZY) { runStart() }.also {
                startJob = it
                it.start()
            }
        } ?: return
        job.join()
    }

    /** See [start]. A cancelled [startJob] (one [stop] ended) no longer counts as in flight.
     *  Main-confined: called only from [start]'s block on [mainDispatcher]. */
    private fun sessionActive(): Boolean {
        val starting = startJob?.let { !it.isCompleted && !it.isCancelled } == true
        return stopsInFlight > 0 || starting || activeServer != null || activeNative?.isRunning == true
    }

    private suspend fun runStart() {
        // Per-session reset (D-05, LAST-01). `:demo` reuses ONE engine across runs, so everything a
        // previous session left behind — its backends, its handoff commit, its terminal flag — must
        // be cleared here, or [lastFinal] would hand a host a previous session's text and the
        // one-shot handoff guard would stay spent. [start] runs this only when no session is
        // starting, live or stopping (see its re-entry guard), so clearing the active references is
        // safe. The previous session's forwarding collectors are retired first, so none of them can
        // write into this session: its `finals` collectors drain and end (a session that ended
        // without a stop() still has them — see [finalsForwarders]), and the rest are cancelled.
        // The drain cannot be interrupted half-way; a stop() that cancelled this job in the meantime
        // then ends it before any reset.
        withContext(NonCancellable) { retireFinalsForwarders() }
        currentCoroutineContext().ensureActive()
        sessionForwarders.cancel()
        sessionForwarders = SupervisorJob(scopeJob)
        terminal = false
        handoffStarted = false
        handoffFinal = null
        lastServer = null
        activeServer = null
        activeNative = null

        val serverUrl = config.serverUrl
        if (serverUrl != null && config.prefer != BackendPreference.OnDevice) {
            // CR-04: mirrors SttEngineImpl.start()'s own first line — a host rendering a
            // "connecting…" affordance off `state == Connecting` must see it during the try-server
            // window too, not just on the native-only path.
            setState(EngineState.Connecting)
            val serverBackend = tryServer(serverUrl)
            if (serverBackend != null) {
                activeServer = serverBackend
                setBackend(Backend.Server)
                forwardServer(serverBackend)
                return
            }

            if (config.prefer == BackendPreference.Server) {
                // prefer:server means server-only — no native fallback (SEL-02, TRY-02's
                // transparency prohibition): exactly one typed error, the latest one an attempt
                // produced, or SERVER_UNREACHABLE when no attempt ever reached an OkHttp callback.
                fail(lastTryError ?: SttError(SttErrorCode.SERVER_UNREACHABLE, "server unreachable"))
                return
            }
            // Otherwise (Auto): fall through to native below — SEL-01's silent swallow of the
            // fast-fail / window-expired conditions.
        }

        setBackend(Backend.Native)
        startNative()
    }

    /**
     * The try-server attempt loop (Phase 21, TRY-02, D-06/D-14), all under ONE deadline equal to
     * [tryServerTimeoutMs]. Returns the [ServerBackend] that reached `ready`, or `null` once a
     * failure fast-failed or the window expired. Every failed attempt is abandoned before anything
     * else happens (D-04's swallow mechanism: its errors/state are never forwarded).
     *
     * Each attempt is a fresh single-attempt [ServerBackend] assigned to [pendingServer] BEFORE
     * `connect()` (CR-03), so [stop] always reaches the live one. A coroutine cancellation (`stop()`
     * cancelling [startJob], during an attempt or a backoff) propagates untouched; the `finally`
     * abandons whatever attempt is still in flight, and no further attempt is created.
     *
     * Each outcome writes one opt-in `server-attempt` trace line ([SessionLog.serverAttempt]):
     * `Connected`, `FastFail`, `Retryable`, or — once, when the deadline ends the loop — `WindowExpired`
     * (numbered as the in-flight attempt, or as the last attempt started if the window ran out
     * during a backoff).
     */
    private suspend fun tryServer(url: String): ServerBackend? {
        lastTryError = null
        var attempt = 0
        var gaveUp = false
        // This loop's OWN in-flight attempt. [pendingServer] is the shared handle [stop] reads; every
        // clear below goes through [releasePending], which only clears it while it still names this
        // loop's attempt, and the `finally` abandons only this local — so a loop that is still
        // settling its cancellation can never clear or abandon a later session's attempt.
        var inFlight: ServerBackend? = null
        try {
            val connected = withTimeoutOrNull(tryServerTimeoutMs) {
                var retryIndex = 0
                while (true) {
                    attempt += 1
                    val backend = serverBackendFactory(url)
                    inFlight = backend
                    pendingServer = backend
                    if (backend.connect()) {
                        inFlight = null
                        releasePending(backend)
                        logAttempt(attempt, "Connected", backend.connectObservation)
                        return@withTimeoutOrNull backend
                    }
                    val observation = backend.connectObservation
                    val cls = classifyConnectFailure(observation)
                    backend.lastError?.let { lastTryError = it }
                    // Never leak the half-open socket — abandon BEFORE deciding what happens next.
                    backend.abandon()
                    inFlight = null
                    releasePending(backend)
                    logAttempt(attempt, cls.name, observation)
                    if (cls == ConnectFailureClass.FastFail) {
                        gaveUp = true
                        return@withTimeoutOrNull null
                    }
                    delay(RETRY_BACKOFF_MS[min(retryIndex, RETRY_BACKOFF_MS.lastIndex)])
                    retryIndex += 1
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            if (connected == null && !gaveUp) {
                // D-14: the window — not a socket timeout, not a backoff — ran out.
                val expired = inFlight
                inFlight = null
                if (expired != null) releasePending(expired)
                val observation = expired?.connectObservation ?: ConnectObservation()
                expired?.lastError?.let { lastTryError = it }
                expired?.abandon()
                logAttempt(attempt, WINDOW_EXPIRED, observation)
            }
            return connected
        } finally {
            // A stop()-triggered cancellation: abandon THIS loop's in-flight attempt (cancels its
            // socket, releases any mic it opened). Idempotent; a no-op on every other path.
            inFlight?.let {
                it.abandon()
                releasePending(it)
            }
        }
    }

    /** Clears [pendingServer] only while it still names [backend] — see [tryServer]'s ownership note. */
    private fun releasePending(backend: ServerBackend) {
        if (pendingServer === backend) pendingServer = null
    }

    /** One `server-attempt` trace line — the throwable's simple class name only, never its
     *  message (it embeds the server URL). */
    private fun logAttempt(attempt: Int, outcome: String, observation: ConnectObservation) {
        log.serverAttempt(
            attempt = attempt,
            outcome = outcome,
            exception = observation.throwable?.javaClass?.simpleName,
            sawOpen = observation.sawOpen,
            closeCode = observation.closeCode,
            responseCode = observation.responseCode,
        )
    }

    /**
     * Ends the session at the host's request.
     *
     * On the server path this now waits for the server's flush (Phase 21, SRV-05, D-04):
     * [ServerBackend.stop] sends the protocol `stop` token and suspends until the server's close
     * 1000, bounded by [ServerBackend.STOP_FLUSH_TIMEOUT_MS] (~2.5 s) — on timeout it commits the
     * open partial locally. So a server-path `stop()` can take up to ~2.5 s; the native path's
     * `stop()` is unchanged.
     *
     * **Detach before await.** The committed server is read into a local and [activeServer] is
     * cleared BEFORE that suspending flush starts. Any socket failure, abnormal close or error frame
     * during the wait then reaches [handleServerDrop] with an instance that is no longer
     * [activeServer], so its identity guard makes it a no-op: a stop can never start the native
     * recognizer. The forwarding collectors from [forwardServer] keep running after the detach, so
     * the server's flushed tail final still reaches [finals] and [state] still follows the backend
     * to [EngineState.Idle]. Once the flush has settled, the `finals` collectors are drained and
     * ended ([settleStoppedSession]), so the tail is on [finals] before `stop()` returns.
     *
     * **Threading.** Runs on [mainDispatcher], like [start]'s guard (see its KDoc): the cancel, the
     * abandon and the detach below are one uninterrupted main-thread step, so a [runStart] that is
     * committing a server or a native session cannot interleave with them from another thread.
     *
     * **The detach does not open the start guard.** [stopsInFlight] stays above zero until this
     * returns, so a concurrent [start] during the flush is ignored (see [start]); [lastServer] — and
     * so [lastFinal] — is still this session's when `stop()` returns.
     *
     * **Cancellation cannot skip the teardown.** The hop onto [mainDispatcher] is entered under
     * [NonCancellable]. A plain `withContext(mainDispatcher)` is cancellable on entry: a caller that is
     * already cancelled, or is cancelled while the hop is still queued on main, would never run the
     * block — leaving [startJob] running, the socket or native recognizer live, and possibly the
     * microphone held. Under [NonCancellable] the whole teardown always runs to the end, flush
     * included. That stays bounded: [ServerBackend.stop]'s own deadline
     * ([ServerBackend.STOP_FLUSH_TIMEOUT_MS], D-04) still applies, so a cancelled caller waits at most
     * the same ~2.5 s as any other. A cancelled caller then sees its own cancellation once `stop()`
     * returns, as with any suspend call.
     */
    override suspend fun stop() {
        withContext(NonCancellable + mainDispatcher) { stopOnMain() }
    }

    private suspend fun stopOnMain() {
        stopsInFlight += 1
        try {
            detachAndFlush()
            // Only the last stop still in flight settles the session. A concurrent stop that
            // returns early (it found nothing left to detach) must not settle a session another
            // stop is still flushing.
            if (stopsInFlight == 1) settleStoppedSession()
        } finally {
            stopsInFlight -= 1
        }
    }

    /**
     * The session is over. Every backend has stopped (the flush has settled), so its `finals`
     * collectors are drained and ended ([retireFinalsForwarders]): the flushed tail is on [finals]
     * before `stop()` returns, and nothing of this session can reach the next one.
     *
     * Then [state] ends at [EngineState.Idle], the same as [SttEngineImpl.stop]. A committed
     * backend's own forwarded state already took it there. This covers a stop during the try-server
     * window, where [runStart] is cancelled before any backend owns [state] and it would otherwise
     * stay at [EngineState.Connecting]. An [EngineState.Error] is left alone, as [SttEngineImpl.stop]
     * leaves its own.
     */
    private suspend fun settleStoppedSession() {
        retireFinalsForwarders()
        if (_state.value != EngineState.Error) setState(EngineState.Idle)
    }

    /**
     * Ends the current session's `finals` collectors without losing a final (see
     * [finalsForwarders]): closes every source, then joins every collector. Each collector body
     * only does non-suspending sends, so a join returns as soon as main has run the buffered sends.
     * Main-confined; called only once every backend in the list is finished.
     *
     * The collectors leave the list only AFTER they are joined, so a second retire that overlaps
     * this one (a [stop] that cancelled a [runStart] mid-drain, or the next [runStart] it then
     * admits) joins the same collectors instead of finding an empty list and resetting the session
     * while they are still draining. Closing a source twice is a no-op.
     */
    private suspend fun retireFinalsForwarders() {
        if (finalsForwarders.isEmpty()) return
        val retiring = finalsForwarders.toList()
        retiring.forEach { it.closeSource() }
        retiring.forEach { it.job.join() }
        finalsForwarders.removeAll(retiring)
    }

    private suspend fun detachAndFlush() {
        // CR-03: cancel whatever `start()` sequence is still in flight FIRST — before touching
        // activeServer/activeNative — so nothing further in that sequence (a still-pending
        // startNative() call, most notably) can mutate state/backend after this stop() returns.
        startJob?.cancel()
        // Belt-and-suspenders alongside the cancellation above: abandon a try-server attempt that
        // is still pending RIGHT NOW, in case stop() is racing runStart() between two suspension
        // points where cancellation has not yet been delivered. abandon() is idempotent.
        pendingServer?.abandon()
        pendingServer = null
        // D-04: detach first, then await the flush (see the KDoc above).
        val srv = activeServer
        activeServer = null
        // LAST-01: keep the detached instance readable so lastFinal reports the tail it flushes.
        if (srv != null) lastServer = srv
        srv?.stop()
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
        val forwarders = sessionForwarders
        scope.launch(forwarders) {
            serverBackend.state.collect { next -> if (next != EngineState.Error) setState(next) }
        }
        scope.launch(forwarders) {
            serverBackend.partial.collect { text ->
                _partial.value = text
                _events.tryEmit(SttEvent.Partial(text))
            }
        }
        // Not under [forwarders]: [finals] is lossless, so this one is drained, never cancelled
        // (see [finalsForwarders]).
        val finalsJob = scope.launch {
            serverBackend.finals.collect { segment ->
                finalsChannel.trySend(segment)
                _events.tryEmit(SttEvent.Final(segment))
            }
        }
        finalsForwarders += FinalsForwarder(finalsJob, serverBackend::closeFinals)
        scope.launch(forwarders) {
            serverBackend.errors.collect { handleServerDrop(serverBackend) }
        }
        scope.launch(forwarders) {
            serverBackend.level.collect { level ->
                _level.tryEmit(level)
                _events.tryEmit(SttEvent.Level(level))
            }
        }
        // CR-02: ServerBackend.warnings' own KDoc documents this selector as the ONLY place
        // SttWarning surfaces on the frozen contract — this collector is that wire. It is live on
        // the server path: ServerBackend's PCM pump (Phase 21, SRV-05) feeds every captured frame
        // through sendPcmFrame, so ServerBackpressureGate's shed/clear notices reach the host here
        // as SttEvent.Warning whenever the socket falls behind.
        scope.launch(forwarders) {
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
        lastServer = serverBackend

        // 1. commit the live partial BEFORE any teardown.
        val partialText = serverBackend.partial.value
        if (partialText.isNotBlank()) {
            val text = config.corrections?.takeIf { it.isNotEmpty() }
                ?.let { Corrections.apply(partialText, it) } ?: partialText
            // The handoff commit carries no detection evidence, so it is language-free and source-free (null/null).
            val segment = FinalSegment(text, HANDOFF_SEGMENT_ID)
            handoffFinal = segment // LAST-01 (D-05): before the send, same instance.
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
        val forwarders = sessionForwarders
        scope.launch(forwarders) {
            native.state.collect { next ->
                // Only flip `backend` once native audio is ACTUALLY flowing (SEL-04) — never
                // before, so a host reading `backend` never observes Native ahead of native audio.
                // A no-op on the plain v1.0/native-only path, where `backend` is already Native
                // from construction.
                if (next == EngineState.Listening) setBackend(Backend.Native)
                setState(next)
            }
        }
        scope.launch(forwarders) {
            native.partial.collect { text ->
                _partial.value = text
                _events.tryEmit(SttEvent.Partial(text))
            }
        }
        // Not under [forwarders]: [finals] is lossless, so this one is drained, never cancelled
        // (see [finalsForwarders]).
        val finalsJob = scope.launch {
            native.finals.collect { segment ->
                finalsChannel.trySend(segment)
                _events.tryEmit(SttEvent.Final(segment))
            }
        }
        finalsForwarders += FinalsForwarder(finalsJob, native::closeFinals)
        scope.launch(forwarders) {
            native.errors.collect { error ->
                errorsChannel.trySend(error)
                _events.tryEmit(SttEvent.Error(error))
            }
        }
        scope.launch(forwarders) {
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
        /** The synthetic [FinalSegment.segmentId] the SEL-04 handoff's own commit-on-drop uses.
         *  `segmentId` is opaque and per-session (API.md) — this is not part of [ServerBackend]'s own
         *  server-numbered sequence, just a safe fixed value for the one-off commit this path makes. */
        const val HANDOFF_SEGMENT_ID: Int = 0

        /** The backoff before each retry of a Retryable failure (TRY-02, D-06; values are Claude's
         *  discretion): 250 → 500 → 1 000 ms, then 1 000 ms for every later retry. All of them sit
         *  inside the ONE window [tryServerTimeoutMs], which cancels a backoff that outlives it. */
        val RETRY_BACKOFF_MS: LongArray = longArrayOf(250L, 500L, 1_000L)

        /** The `server-attempt` trace class written once when the window ends the loop (D-14). */
        const val WINDOW_EXPIRED = "WindowExpired"
    }
}

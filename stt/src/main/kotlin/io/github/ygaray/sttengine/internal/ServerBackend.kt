package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.SttWarning
import io.github.ygaray.sttengine.corrections.Corrections
import io.github.ygaray.sttengine.corrections.CorrectionRuleset
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellableContinuation
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
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * The narrow seam between [ServerBackend] and OkHttp's `WebSocket` construction (Phase 9, D-02).
 *
 * `OkHttpClient`/`WebSocket` are concrete platform classes with no fake-friendly seam of their own
 * (unlike [RecognizerHandle], the platform gives no interface to stand behind). This interface is
 * that seam: [ServerBackendTest]/[BackendSelectorTest] drive a [FakeWebSocketFactory] instead,
 * exactly the way [FakeRecognizerHandle] stands in for a real `SpeechRecognizer`.
 */
internal fun interface WebSocketFactory {
    fun newWebSocket(url: String, listener: WebSocketListener): WebSocket
}

/** The production [WebSocketFactory]: a thin wrapper over a real [OkHttpClient]. */
internal class OkHttpWebSocketFactory(
    private val client: OkHttpClient,
) : WebSocketFactory {
    override fun newWebSocket(url: String, listener: WebSocketListener): WebSocket =
        client.newWebSocket(Request.Builder().url(url).build(), listener)
}

/**
 * The OkHttp-backed WS state machine for the server-backed live path (Phase 9, SEL-01).
 *
 * This is the Kotlin counterpart of `web/src/engine.ts`'s WS state machine — same `fail()` funnel,
 * same `setState` single-mutation-point discipline (RESEARCH.md Pattern 4) — but it does NOT port
 * `web/src/reconnect.ts`'s exponential-backoff keep-capture-warm reconnect loop: D-03 already
 * resolves post-commit drops differently (a one-way handoff to native, owned by
 * [BackendSelector]), and the mid-session handoff itself is Plan 09-02/09-03 scope, not this plan.
 *
 * ## One connection attempt per instance
 *
 * Unlike [SttEngineImpl], which restarts many recognizer sessions across one engine lifetime, a
 * `ServerBackend` models exactly ONE WebSocket connection attempt. [BackendSelector] constructs a
 * fresh instance per `start()`. [SessionEpoch] is still reused here — not as a restart ladder, but
 * as the mitigation for a late OkHttp callback arriving after [fail]/[abandon] has already retired
 * this instance (the same T-05-14 hazard `SessionEpoch`'s own KDoc documents).
 *
 * ## Threading (Common Pitfall 3)
 *
 * OkHttp's `WebSocketListener` callbacks run on OkHttp's own dispatcher thread — NOT the main looper,
 * unlike `RecognitionListener`. Every callback therefore hops onto [mainDispatcher] via [onFenced]
 * BEFORE it reads the epoch fence or touches any shared field, mirroring
 * `PlatformRecognizerHandle.onMain`'s hop for exactly the same reason.
 *
 * ## The server-path microphone (Phase 9 Task 1, SEL-04)
 *
 * [pcmSourceFactory] opens [audioSource] once the session reaches [ServerFrame.Ready] — mirroring
 * Pattern 3's "`ServerBackend` calls `AudioRecordPcmSource.open(16_000)` directly rather than
 * hand-rolling a second `AudioRecord` wrapper." [releaseAudioSource] is the release half
 * [BackendSelector]'s mid-session handoff calls BEFORE any native `SpeechRecognizer` acquire — two
 * mic clients cannot coexist (T-09-06). Continuously PUMPING captured frames through a live capture
 * loop is deferred to the on-device measurement work (Plan 09-03); [sendPcmFrame] is the gated send
 * path that future caller will drive, one frame at a time.
 *
 * ## Outbound backpressure (Phase 9 Task 2, T-09-05)
 *
 * [sendPcmFrame] ports `web/src/engine.ts`'s `sendFrame`/`flushQueue` — a small outbound FIFO
 * ([outboundQueue], mirrors `engine.ts`'s `MAX_QUEUE_FRAMES`), gated by [backpressureGate] fed
 * OkHttp's real `WebSocket.queueSize()` (the `ws.bufferedAmount` analog) rather than a
 * hand-maintained counter. Every `WebSocket.send(...)` call is Boolean-checked (Common Pitfall 2):
 * OkHttp's `send()` returns `false` — never throws — on an already closing/closed/canceled socket or
 * past its own internal queue ceiling, and a `false` return routes through the SAME [fail] funnel as
 * any other terminal failure, with [SttErrorCode.SEND_BACKPRESSURE], never a silently dropped frame.
 */
internal class ServerBackend(
    private val webSocketFactory: WebSocketFactory,
    private val serverUrl: String,
    private val language: String,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val pcmSourceFactory: PcmSourceFactory = AudioRecordPcmSource,
    private val backpressureGate: ServerBackpressureGate = ServerBackpressureGate(),
    corrections: CorrectionRuleset? = null,
    private val monotonicMs: () -> Long = { System.currentTimeMillis() },
    vocabulary: List<String>? = null,
) {
    // WR-03 (13-REVIEW.md): immutable snapshot of the host-supplied ruleset — see SttConfig's
    // matching KDoc note; `CorrectionRuleset` carries no runtime immutability guarantee.
    private val corrections: CorrectionRuleset? = corrections?.toList()

    // Same immutable-snapshot treatment as `corrections` above, for the same reason (BIAS-01).
    private val vocabulary: List<String>? = vocabulary?.toList()


    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val epoch = SessionEpoch()

    private val _state = MutableStateFlow(EngineState.Connecting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** UNLIMITED — see [SttEngineImpl.finalsChannel]'s KDoc for why: a committed final must survive
     *  a detached collector, exactly the same load-bearing choice for this backend. */
    private val finalsChannel = Channel<FinalSegment>(Channel.UNLIMITED)
    val finals: Flow<FinalSegment> = finalsChannel.receiveAsFlow()

    /** Also unlimited, and effectively single-shot: at most one error is ever sent per instance
     *  (first-error-wins via [fail]). Never observed at all when [BackendSelector] abandons this
     *  instance during the try-server window (D-04's swallow semantics: this IS the mechanism). */
    private val errorsChannel = Channel<SttError>(Channel.UNLIMITED)
    val errors: Flow<SttError> = errorsChannel.receiveAsFlow()

    private val _level = MutableSharedFlow<Float>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val level: Flow<Float> = _level.asSharedFlow()

    /** Backpressure shed/clear notices (Task 2). [BackendSelector] forwards these onto
     *  `SttEvent.Warning` — the only place [SttWarning] surfaces on the frozen contract. */
    private val _warnings = MutableSharedFlow<SttWarning>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val warnings: Flow<SttWarning> = _warnings.asSharedFlow()

    /** The outbound PCM FIFO [sendPcmFrame] sheds from under sustained backpressure. Mirrors
     *  `engine.ts`'s `queue` + `MAX_QUEUE_FRAMES`. */
    private val outboundQueue: ArrayDeque<ByteArray> = ArrayDeque()

    private var webSocket: WebSocket? = null
    private var connectContinuation: CancellableContinuation<Boolean>? = null
    private var terminal = false

    /** The server-path microphone, opened in [doOnMessage] once [ServerFrame.Ready] arrives. `null`
     *  until then, and after [releaseAudioSource] has run. */
    private var audioSource: PcmSource? = null

    /**
     * The typed error this instance last produced, or `null` if [connect] never failed through
     * [fail] at all (e.g. a bare coroutine cancellation via `withTimeoutOrNull`, where no OkHttp
     * callback ever fired). [BackendSelector] reads this ONLY for `prefer: server`'s force-surface
     * path (D-04) — the swallow path never reads it at all.
     */
    var lastError: SttError? = null
        private set

    /**
     * Opens the WebSocket, sends the config frame on `onOpen`, and resumes `true` only once a
     * [ServerFrame.Ready] frame is parsed. Resumes `false` on any terminal failure reached before
     * `Ready` — including a coroutine-level cancellation (e.g. the caller's own
     * `withTimeoutOrNull`), via [CancellableContinuation.invokeOnCancellation] cancelling the
     * half-open socket so it is never leaked (RESEARCH.md Pattern 2).
     */
    suspend fun connect(): Boolean {
        if (terminal) return false
        val boundEpoch = epoch.current
        return suspendCancellableCoroutine { cont ->
            connectContinuation = cont
            val socket = webSocketFactory.newWebSocket(serverUrl, listenerFor(boundEpoch))
            webSocket = socket
            cont.invokeOnCancellation { socket.cancel() }
        }
    }

    /**
     * Closes a half-open socket after [BackendSelector] abandons the try-server window — e.g. after
     * a `withTimeoutOrNull` expiry, or any other reason it chooses not to commit to this instance.
     * Idempotent. Deliberately emits NOTHING on [errors]/[state]: those are simply never observed
     * once the selector has moved on to native, which is the swallow mechanism itself (D-04), not a
     * special case bolted on top of it.
     */
    fun abandon() {
        if (terminal) return
        terminal = true
        epoch.advance()
        // CR-01: a late Ready that races the coroutine-level timeout can open the server-path mic
        // between the timeout firing and this call — release it here too, mirroring fail()/stop(),
        // so abandon() never leaves this instance still holding the microphone.
        releaseAudioSource()
        outboundQueue.clear()
        webSocket?.cancel()
        webSocket = null
    }

    /**
     * Ends a committed session (post-`Ready`) at the host's request. Never emits on [errors] — a
     * requested stop is not a failure. Idempotent.
     */
    fun stop() {
        if (terminal) return
        terminal = true
        epoch.advance()
        releaseAudioSource()
        outboundQueue.clear()
        webSocket?.close(NORMAL_CLOSE_CODE, "normal")
        webSocket = null
        setState(EngineState.Idle)
    }

    /**
     * Releases the server-path microphone (Phase 9 Task 1, SEL-04). Idempotent — tolerates being
     * called when nothing was ever opened (a `null` [audioSource], e.g. this instance never reached
     * [ServerFrame.Ready] at all) or when already released.
     *
     * [BackendSelector]'s mid-session handoff calls this EXPLICITLY, BEFORE constructing the native
     * fallback (release-then-acquire, D-03/T-09-06) — two mic clients cannot coexist. [fail] and
     * [stop] also call it as part of their own teardown, so a terminal failure or a requested stop
     * never leaves this instance still holding the microphone either.
     */
    fun releaseAudioSource() {
        val source = audioSource ?: return
        audioSource = null
        source.stop()
        source.release()
    }

    // --- the backpressure-aware outbound PCM send path (Phase 9 Task 2, T-09-05) --------------------

    /**
     * Queue one PCM frame for the socket, then evaluate [backpressureGate] against OkHttp's real
     * `WebSocket.queueSize()` (mirrors `engine.ts`'s `sendFrame`, fed on every outbound frame tick):
     *
     * - [BackpressureAction.Send] / [BackpressureAction.ClearWarning]: flush [outboundQueue] via
     *   [flushQueue].
     * - [BackpressureAction.DropOldest]: shed the oldest queued frame(s) to hold the bound — never
     *   send while shedding, so the socket's own buffer can actually drain — and emit a throttled
     *   [SttWarning] when [BackpressureAction.DropOldest.warn] is true.
     * - [BackpressureAction.Fatal]: a sustained overrun — [fail] with
     *   [SttErrorCode.SEND_BACKPRESSURE] (D-08 stage 2).
     *
     * A no-op once [terminal] or before [webSocket] exists (gap audio — mirrors `engine.ts`'s
     * `readyState !== WS_OPEN` gate).
     */
    fun sendPcmFrame(pcm: ByteArray) {
        if (terminal) return
        val socket = webSocket ?: return
        outboundQueue.addLast(pcm)
        when (val action = backpressureGate.evaluate(socket.queueSize(), monotonicMs())) {
            is BackpressureAction.Send -> flushQueue(socket)

            is BackpressureAction.DropOldest -> {
                // Hold the bound: drop from the FRONT (oldest) so only the newest frames survive.
                while (outboundQueue.size > MAX_QUEUE_FRAMES) outboundQueue.removeFirst()
                if (action.warn) {
                    _warnings.tryEmit(
                        SttWarning(
                            code = "backpressure",
                            active = true,
                            message = "outbound buffer over soft ceiling — shedding oldest audio to keep up",
                        ),
                    )
                }
            }

            is BackpressureAction.ClearWarning -> {
                _warnings.tryEmit(
                    SttWarning(
                        code = "backpressure",
                        active = false,
                        message = "outbound buffer drained — backpressure cleared",
                    ),
                )
                flushQueue(socket)
            }

            is BackpressureAction.Fatal ->
                fail(
                    SttError(
                        SttErrorCode.SEND_BACKPRESSURE,
                        "send buffer overrun — audio produced faster than the socket can drain",
                    ),
                )
        }
    }

    /**
     * Flush the whole outbound FIFO to the socket in order. Every [WebSocket.send] call is
     * Boolean-checked (Common Pitfall 2, T-09-05) — unlike the web port's un-checked
     * `socket.send(...)` call sites, which cannot fail synchronously there. A `false` return here —
     * OkHttp's internal queue ceiling, or the socket already closing/closed/canceled — is ALSO a
     * terminal send-backpressure failure, never a silently dropped frame.
     */
    private fun flushQueue(socket: WebSocket) {
        while (outboundQueue.isNotEmpty()) {
            val frame = outboundQueue.first()
            val sent = socket.send(frame.toByteString())
            if (!sent) {
                fail(
                    SttError(
                        SttErrorCode.SEND_BACKPRESSURE,
                        "outbound send failed — the socket queue is full or already closing",
                    ),
                )
                return
            }
            outboundQueue.removeFirst()
        }
    }

    // --- the one terminal-failure funnel (mirrors SttEngineImpl.fail / engine.ts's fail) -----------

    /**
     * EVERY typed failure routes through here — first-error-wins, idempotent, mirrors
     * [SttEngineImpl]'s own funnel. Resolves a still-pending [connect] with `false`; a failure that
     * arrives after `Ready` has no pending continuation to resolve (already `null`), and instead
     * reaches the host through [errors]/[state] once [BackendSelector] has forwarded them (i.e. only
     * once this session has committed to the server path, D-04).
     */
    private fun fail(error: SttError) {
        if (terminal) return
        terminal = true
        lastError = error
        epoch.advance()
        // Before the error is emitted: an instance that has failed terminally must not still be
        // holding the microphone (mirrors SttEngineImpl.fail's own ordering discipline).
        releaseAudioSource()
        outboundQueue.clear()
        webSocket?.cancel()
        webSocket = null

        errorsChannel.trySend(error)
        setState(EngineState.Error)

        val cont = connectContinuation
        connectContinuation = null
        if (cont != null && cont.isActive) cont.resume(false)
    }

    private fun succeed() {
        setState(EngineState.Listening)
        val cont = connectContinuation
        connectContinuation = null
        if (cont != null && cont.isActive) cont.resume(true)
    }

    private fun setState(next: EngineState) {
        if (_state.value == next) return
        _state.value = next
    }

    // --- the listener, epoch-fenced and hopped onto mainDispatcher (Common Pitfall 3) ---------------

    private fun listenerFor(boundEpoch: Long): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onFenced(boundEpoch) { doOnOpen(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onFenced(boundEpoch) { doOnMessage(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onFenced(boundEpoch) { doOnClosing(code, reason) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onFenced(boundEpoch) { doOnClosed(code) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onFenced(boundEpoch) { doOnFailure(t) }
        }
    }

    /**
     * Hops onto [mainDispatcher], THEN checks the epoch fence as the first statement once there.
     * OkHttp delivers every [WebSocketListener] callback on its own dispatcher thread, so the fence
     * itself — a plain field, main-thread-confined by [SessionEpoch]'s own discipline — cannot be
     * read before the hop completes (Common Pitfall 3).
     */
    private fun onFenced(boundEpoch: Long, block: () -> Unit) {
        scope.launch {
            if (!epoch.accepts(boundEpoch)) return@launch
            block()
        }
    }

    private fun doOnOpen(socket: WebSocket) {
        if (terminal) return
        // Pitfall 2: OkHttp's send() returns false rather than throwing on failure (an already
        // closing/closed/canceled socket, or the internal queue ceiling) — checked, unlike the web
        // port's un-checked `socket.send(...)` call sites (which cannot fail synchronously there).
        val sent = socket.send(buildConfigFrame(language, vocabulary))
        if (!sent) {
            fail(SttError(SttErrorCode.INTERNAL_ERROR, "failed to send the config frame"))
        }
    }

    private fun doOnMessage(text: String) {
        if (terminal) return
        when (val result = parseServerFrame(text)) {
            is ParseResult.Ok -> when (val frame = result.frame) {
                ServerFrame.Ready -> {
                    // Open the server-path mic BEFORE declaring the session Listening (Task 1,
                    // SEL-04) — a session is not really live if it cannot capture audio to send.
                    // A capture failure here is routed through the SAME fail() funnel as any other
                    // terminal failure, never a bare uncaught exception (CLAUDE.md's loud-typed-
                    // failure discipline).
                    // WR-02: narrowed to Exception — AudioRecordPcmSource.open() only ever throws
                    // IllegalStateException/IllegalArgumentException (both Exception subtypes); a
                    // bare Throwable catch would also swallow OutOfMemoryError/StackOverflowError and
                    // silently downgrade a serious VM-level condition to an ordinary mic-denied
                    // failure.
                    val opened = try {
                        pcmSourceFactory.open(SAMPLE_RATE)
                    } catch (unavailable: Exception) {
                        null
                    }
                    if (opened == null) {
                        fail(SttError(SttErrorCode.MIC_DENIED, "microphone unavailable for the server-backed session"))
                    } else {
                        audioSource = opened
                        succeed()
                    }
                }

                is ServerFrame.Partial -> _partial.value = frame.text

                is ServerFrame.Final -> {
                    _partial.value = ""
                    val text = corrections?.takeIf { it.isNotEmpty() }
                        ?.let { Corrections.apply(frame.text, it) } ?: frame.text
                    finalsChannel.trySend(FinalSegment(text, frame.segmentId, frame.language))
                }

                // Carries the server's taxonomy code verbatim (mirrors protocol.ts) — this client
                // does not reject an unknown code, it just wraps it.
                is ServerFrame.Error -> fail(SttError(SttErrorCode(frame.code), frame.message))
            }

            is ParseResult.ProtocolViolation ->
                fail(SttError(SttErrorCode.PROTOCOL_VIOLATION, result.message))
        }
    }

    private fun doOnClosing(code: Int, reason: String) {
        if (terminal) return
        when (code) {
            // A normal closure with no prior frame is an ordinary teardown, not a failure.
            NORMAL_CLOSE_CODE -> Unit
            // Mirrors protocol.ts's closeCodeFor/engine.ts's failBusy(): the server closes 1013 when
            // it is already serving its single permitted session.
            BUSY_CLOSE_CODE ->
                fail(SttError(SttErrorCode.BUSY, "server busy — another dictation session is active"))

            else -> fail(SttError(SttErrorCode.INTERNAL_ERROR, "server closed the connection: $code $reason"))
        }
    }

    private fun doOnClosed(code: Int) {
        if (terminal) return
        if (code != NORMAL_CLOSE_CODE) {
            fail(SttError(SttErrorCode.SERVER_UNREACHABLE, "connection closed unexpectedly: $code"))
        }
    }

    private fun doOnFailure(t: Throwable) {
        if (terminal) return
        fail(SttError(SttErrorCode.SERVER_UNREACHABLE, t.message ?: "server unreachable"))
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
        const val BUSY_CLOSE_CODE = 1013

        /** Mirrors `engine.ts:79`'s `MAX_QUEUE_FRAMES` — each frame ~1 KB, so ~64 KB, matched to the
         *  ported gate's own soft ceiling. Bounds [outboundQueue] so it cannot grow unbounded while
         *  shedding. */
        const val MAX_QUEUE_FRAMES = 64
    }
}

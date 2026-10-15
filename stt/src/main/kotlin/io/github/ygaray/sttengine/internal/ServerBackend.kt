package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.SttWarning
import io.github.ygaray.sttengine.corrections.Corrections
import io.github.ygaray.sttengine.corrections.CorrectionRuleset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * The server path's pure frame-accumulation loop (Phase 21, SRV-05, D-01) — the body of
 * [ServerBackend]'s pump thread, kept top-level so a JVM test can drive it with a finite fake
 * [PcmSource] and no thread, socket or dispatcher at all.
 *
 * Reads [source] while [running] holds, accumulating exactly [frameBytes] bytes per frame (a read
 * may be short — a paced debug feed returns whatever its clock released) and hands each whole frame
 * to [onFrame] as a fresh copy. A `read()` result `<= 0` is end of stream and ends the loop quietly,
 * exactly as `AudioSourcePipe.pumpInto` treats it; a partial frame left over at that point is
 * dropped (less than 32 ms of audio, and the server's frame contract is whole 512-sample frames).
 *
 * `catch (Exception)` only, never `Throwable` (mirrors `AudioSourcePipe` CR-03): a read failure —
 * typically an `IllegalStateException` from a recorder released under a wedged read — is the ordinary
 * end of capture, but an `Error` such as `OutOfMemoryError` must not be swallowed. [running] is
 * cleared on every exit path.
 */
internal fun pumpFrames(
    source: PcmSource,
    running: AtomicBoolean,
    frameBytes: Int,
    onFrame: (ByteArray) -> Unit,
) {
    val frame = ByteArray(frameBytes)
    var filled = 0
    try {
        while (running.get()) {
            val chunk = ByteArray(frameBytes - filled)
            val read = source.read(chunk)
            if (read <= 0) break
            val count = minOf(read, chunk.size)
            System.arraycopy(chunk, 0, frame, filled, count)
            filled += count
            if (filled == frameBytes) {
                onFrame(frame.copyOf())
                filled = 0
            }
        }
    } catch (ended: Exception) {
        // The ordinary end of a capture session, never a fault — see the KDoc above.
    } finally {
        running.set(false)
    }
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
 * [BackendSelector]'s `handleServerDrop`).
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
 * [pcmSourceFactory] (the microphone, or in a debug build an armed [DebugAudioSourceHolder] feed)
 * opens [audioSource] once the session reaches [ServerFrame.Ready] — mirroring
 * Pattern 3's "`ServerBackend` calls `AudioRecordPcmSource.open(16_000)` directly rather than
 * hand-rolling a second `AudioRecord` wrapper." [releaseAudioSource] is the release half
 * [BackendSelector]'s mid-session handoff calls BEFORE any native `SpeechRecognizer` acquire — two
 * mic clients cannot coexist (T-09-06).
 *
 * ## The PCM pump (Phase 21, SRV-05, D-01)
 *
 * Right after the mic opens on `Ready`, one dedicated daemon thread named [PUMP_THREAD_NAME] runs
 * [pumpFrames] over the opened source, accumulating whole [FRAME_BYTES]-byte frames (512 samples of
 * 16 kHz PCM16). Each frame crosses to [mainDispatcher] through one unlimited [Channel], and a
 * single consumer job there calls [sendPcmFrame] in order — so [outboundQueue] and
 * [backpressureGate] stay main-confined, and network backpressure stays the gate's job (D-02): the
 * pump itself never blocks on main. The pump thread also computes the input level with a
 * [LevelMeter] (D-03, parity with the web worklet) and emits it on [level]. Teardown copies `AudioSourcePipe`: clear the run flag, `source.stop()`, a bounded
 * `join(PUMP_JOIN_MS)`, and only then `source.release()` — never release while a read may still be
 * in flight (CR-02). Every teardown path ([stop], [abandon], [fail], [releaseAudioSource]) runs it.
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
    /** The server-path capture source (Phase 21, D-08). The default consults
     *  [DebugAudioSourceHolder] before falling back to the microphone — the same idiom as
     *  [AudioSourcePipe]'s `sourceFactory` — so a debug build can feed this path a fixed WAV for a
     *  reproducible Gate-1 run. Release builds never write that holder, so there this always
     *  resolves to [AudioRecordPcmSource]. It is read once per construction, and the selector builds
     *  a fresh instance per `start()`, so arming before `start()` covers that session. An explicit
     *  argument always wins over the holder. */
    private val pcmSourceFactory: PcmSourceFactory =
        DebugAudioSourceHolder.factory ?: AudioRecordPcmSource,
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

    // --- the PCM pump (D-01) -------------------------------------------------------------------

    /** Cleared to ask the pump thread to stop. Atomic because the pump thread reads it. */
    private val pumping = AtomicBoolean(false)

    /** The daemon pump thread, or `null` before `Ready` and after [releaseAudioSource]. */
    private var pumpThread: Thread? = null

    /** Pump thread → main hand-off. UNLIMITED: the pump must never block on main (D-02 keeps
     *  network backpressure in [backpressureGate], not here). */
    private var frames: Channel<ByteArray>? = null

    /** The single main-dispatcher consumer that drains [frames] through [sendPcmFrame] in order. */
    private var consumerJob: Job? = null

    /** D-03's level aggregator — used by the pump thread only. */
    private val levelMeter = LevelMeter()

    // --- the flushing stop (D-04) --------------------------------------------------------------

    /** Set at the start of [stop]; the listener stays live (the epoch is not advanced yet) so the
     *  server's flushed tail final can still arrive. */
    private var stopping = false

    /** Completed by the listener while [stopping]: `true` = the server's clean close 1000, `false`
     *  = a failure, an abnormal close, or [fail] during the flush. First completion wins. */
    private val flushDone = CompletableDeferred<Boolean>()

    /** The server `segmentId` of the live partial, so a stop that cannot flush commits the partial
     *  under its own id rather than a synthetic one. Cleared by every server final. */
    private var livePartialSegmentId: Int? = null

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
        // An abandoned instance drops its unsent audio.
        dropPendingFrames()
        outboundQueue.clear()
        webSocket?.cancel()
        webSocket = null
        // A stop() racing this abandon must not wait out its whole bound for a callback the epoch
        // fence above will now swallow.
        flushDone.complete(false)
    }

    /**
     * Ends a committed session (post-`Ready`) at the host's request, flushing the tail first
     * (Phase 21, SRV-05, D-04). Never emits on [errors] and never sets [state] to
     * [EngineState.Error] — a requested stop is not a failure. Idempotent: a call while already
     * stopping, or after any terminal transition, is a no-op.
     *
     * The order is load-bearing:
     *
     * ```
     * 1. stop the pump, bounded-join it, release the mic   (releaseAudioSource)
     * 2. close the frame channel and join the consumer     (every captured frame went through
     *                                                        sendPcmFrame — "stop" never overtakes
     *                                                        the last audio, Pitfall 3)
     * 3. flush the outbound FIFO
     * 4. send the text token "stop"                        (Boolean-checked; false → step 6)
     * 5. await the server's close 1000                     (doOnClosing replies close(1000) —
     *                                                        OkHttp does not auto-reply — and any
     *                                                        final the server flushes first is
     *                                                        emitted on [finals] before this returns)
     * 6. otherwise: commit the live partial locally under its own server segmentId, cancel the socket
     * 7. terminal, epoch advanced, mic released again (idempotent), state Idle
     * ```
     *
     * One deadline bounds the whole call: [STOP_FLUSH_TIMEOUT_MS] is measured from entry and covers
     * every stage 1-5 — the pump's ≤ [PUMP_JOIN_MS] join, the channel drain, the FIFO flush, the
     * token and the flush await all sit inside the same `withTimeoutOrNull`. Exhausting it at any
     * stage lands on step 6, so a starved drain cannot push `stop()` past the bound and sends no
     * frame after it. It never calls `close` before the server's own close (the web client's order
     * loses the flushed final), and the epoch is advanced only after the flush settles (advancing it
     * first would fence the flushed final away).
     */
    suspend fun stop() {
        if (terminal || stopping) return
        stopping = true
        var flushed = false
        try {
            flushed = withTimeoutOrNull(STOP_FLUSH_TIMEOUT_MS) {
                releaseAudioSource()
                frames?.close()
                consumerJob?.join()
                webSocket?.let { flushQueue(it) }
                if (webSocket?.send(STOP_TOKEN) != true) return@withTimeoutOrNull false
                flushDone.await()
            } == true
        } finally {
            if (!flushed) {
                // No frame may be sent after the bound, and the words already spoken must survive.
                dropPendingFrames()
                commitLivePartialLocally()
                webSocket?.cancel()
            }
            terminal = true
            epoch.advance()
            // Idempotent — keeps the mic-released guarantee independent of which stage the deadline
            // (or a caller cancellation) interrupted.
            releaseAudioSource()
            frames = null
            consumerJob = null
            outboundQueue.clear()
            webSocket = null
            setState(EngineState.Idle)
            val cont = connectContinuation
            connectContinuation = null
            if (cont != null && cont.isActive) cont.resume(false)
        }
    }

    /**
     * Releases the server-path microphone (Phase 9 Task 1, SEL-04), stopping the PCM pump first
     * (Phase 21, D-01). Idempotent — tolerates being called when nothing was ever opened (a `null`
     * [audioSource], e.g. this instance never reached [ServerFrame.Ready] at all) or when already
     * released.
     *
     * The order copies `AudioSourcePipe.close` (CR-02): clear the pump's run flag, `source.stop()`
     * (best effort at unblocking a read in flight), a bounded `join(PUMP_JOIN_MS)` on the pump
     * thread, and only then `source.release()` — releasing while another thread may still be inside
     * a blocking read on the same recorder is unsafe. Each step is nested in `finally` so a throwing
     * step cannot skip the next.
     *
     * [BackendSelector]'s mid-session handoff calls this EXPLICITLY, BEFORE constructing the native
     * fallback (release-then-acquire, D-03/T-09-06) — two mic clients cannot coexist. [fail],
     * [abandon] and [stop] also call it as part of their own teardown, so a terminal failure or a
     * requested stop never leaves this instance still holding the microphone or reading from it.
     */
    fun releaseAudioSource() {
        pumping.set(false)
        val source = audioSource ?: return
        val pump = pumpThread
        audioSource = null
        pumpThread = null
        try {
            runCatching { source.stop() }
        } finally {
            try {
                // Bounded — never unbounded: this can run on the main looper.
                runCatching { pump?.join(PUMP_JOIN_MS) }
            } finally {
                runCatching { source.release() }
            }
        }
    }

    /** Starts the pump thread and its main-dispatcher consumer over the just-opened [source]. */
    private fun startPump(source: PcmSource) {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        frames = channel
        consumerJob = scope.launch {
            for (frame in channel) sendPcmFrame(frame)
        }
        pumping.set(true)
        pumpThread = Thread(
            {
                pumpFrames(source, pumping, FRAME_BYTES) { frame ->
                    // D-03: level from the pump thread (tryEmit is thread-safe; the meter is
                    // touched by this thread only), then hand the frame to main.
                    levelMeter.accept(frame)?.let { _level.tryEmit(it) }
                    channel.trySend(frame)
                }
            },
            PUMP_THREAD_NAME,
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Drops every captured-but-unsent frame: cancels the frame channel and its consumer. */
    private fun dropPendingFrames() {
        frames?.cancel()
        consumerJob?.cancel()
    }

    /**
     * The stop path's fallback when the server's flush did not settle cleanly (D-04): emit the live
     * partial as one [FinalSegment] under the partial's own server `segmentId` (corrections
     * applied, exactly like a server final), then clear it. A blank partial commits nothing.
     */
    private fun commitLivePartialLocally() {
        val partialText = _partial.value
        if (partialText.isBlank()) return
        finalsChannel.trySend(FinalSegment(corrected(partialText), livePartialSegmentId ?: 0))
        _partial.value = ""
        livePartialSegmentId = null
    }

    private fun corrected(text: String): String =
        corrections?.takeIf { it.isNotEmpty() }?.let { Corrections.apply(text, it) } ?: text

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
        if (stopping) {
            // D-04: a failure DURING a requested stop (a false send while flushing, a server error
            // frame, a malformed frame) ends the flush, never the session's contract: no error on
            // [errors], no [EngineState.Error]. stop() sees `false` and commits the live partial
            // locally, so an open partial survives a mid-flush failure.
            webSocket?.cancel()
            webSocket = null
            flushDone.complete(false)
            return
        }
        terminal = true
        lastError = error
        epoch.advance()
        // Before the error is emitted: an instance that has failed terminally must not still be
        // holding the microphone (mirrors SttEngineImpl.fail's own ordering discipline).
        releaseAudioSource()
        // A failed instance drops its unsent audio.
        dropPendingFrames()
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
                    // A Ready that arrives after stop() began must never open the mic.
                    if (stopping) return
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
                        // SRV-05 (D-01): the mic is open and the session is live — start pumping.
                        startPump(opened)
                    }
                }

                is ServerFrame.Partial -> {
                    _partial.value = frame.text
                    livePartialSegmentId = frame.segmentId
                }

                // Keeps emitting while stopping: that is the server's flushed tail (D-04).
                is ServerFrame.Final -> {
                    _partial.value = ""
                    livePartialSegmentId = null
                    finalsChannel.trySend(
                        FinalSegment(corrected(frame.text), frame.segmentId, frame.language),
                    )
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
        if (stopping) {
            if (code == NORMAL_CLOSE_CODE) {
                // RESEARCH Pattern 2 / Pitfall 2: OkHttp 5.2.1 does NOT auto-reply to a received
                // close — without this the handshake never completes and the server waits out its
                // own close timeout. Then the flush is done.
                webSocket?.close(NORMAL_CLOSE_CODE, "normal")
                flushDone.complete(true)
            } else {
                // An abnormal close during a requested stop ends the flush, never with an error.
                flushDone.complete(false)
            }
            return
        }
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
        if (stopping) {
            // First completion wins: after a clean onClosing(1000) this cannot flip it to false.
            flushDone.complete(false)
            return
        }
        if (code != NORMAL_CLOSE_CODE) {
            fail(SttError(SttErrorCode.SERVER_UNREACHABLE, "connection closed unexpectedly: $code"))
        }
    }

    private fun doOnFailure(t: Throwable) {
        if (terminal) return
        if (stopping) {
            // A socket failure mid-flush: stop() commits locally, and a requested stop never errors.
            flushDone.complete(false)
            return
        }
        fail(SttError(SttErrorCode.SERVER_UNREACHABLE, t.message ?: "server unreachable"))
    }

    internal companion object {
        private const val NORMAL_CLOSE_CODE = 1000
        private const val BUSY_CLOSE_CODE = 1013

        /** Mirrors `engine.ts:79`'s `MAX_QUEUE_FRAMES` — each frame ~1 KB, so ~64 KB, matched to the
         *  ported gate's own soft ceiling. Bounds [outboundQueue] so it cannot grow unbounded while
         *  shedding. */
        private const val MAX_QUEUE_FRAMES = 64

        /** The protocol's end-of-audio text token (mirrors `server/` and `web/src/engine.ts`). */
        const val STOP_TOKEN = "stop"

        /** The ONE end-to-end bound on [stop] (D-04), measured from entry: pump teardown, frame
         *  drain, FIFO flush, token send and flush await all share it. */
        const val STOP_FLUSH_TIMEOUT_MS: Long = 2_500L

        /** How long a teardown waits for the pump thread to notice and exit (copies
         *  `AudioSourcePipe`'s bound): never unbounded, because teardown can run on the main looper
         *  and a wedged driver read must not hang it. */
        const val PUMP_JOIN_MS: Long = 250L

        /** The pump thread's name — findable in a thread dump. */
        const val PUMP_THREAD_NAME = "stt-server-pcm-pump"

        /** One wire frame: [FRAME_SAMPLES] samples of 16-bit PCM = 1 024 bytes. */
        const val FRAME_BYTES = FRAME_SAMPLES * 2
    }
}

package io.github.ygaray.sttengine.internal

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import okio.ByteString

/**
 * The test double for [WebSocketFactory] — the translation of [FakeRecognizerHandle]'s
 * record-then-fire shape onto the WS transport seam (Phase 9). `WsProtocolTest`/
 * `BackendSelectorTest` drive `connect → ready → partial/final/error` sequences with no real socket
 * and no Robolectric — `OkHttpClient`/`WebSocket` are plain-JVM-constructible platform classes with
 * no shadow needed.
 *
 * Mirrors [FakeRecognizerHandle]'s "record what the subject did, then fire callbacks back into it"
 * shape: [newWebSocket] records every URL a [ServerBackend] asked to connect to and hands back a
 * [FakeWebSocket]; the `fire*` methods below deliver [WebSocketListener] callbacks into whichever
 * socket's listener the caller targets (defaulting to the most recently created one, exactly like
 * [FakeRecognizerHandle.listener]).
 */
internal class FakeWebSocketFactory : WebSocketFactory {

    /** Every URL a `ServerBackend` asked to connect to, oldest first. */
    val requestedUrls: MutableList<String> = mutableListOf()

    /** Every socket this factory has ever created, oldest first. */
    val sockets: MutableList<FakeWebSocket> = mutableListOf()

    /** The most recently created [FakeWebSocket] — the one `fire*` targets by default. */
    val lastSocket: FakeWebSocket?
        get() = sockets.lastOrNull()

    /** How many times [newWebSocket] was called. Proves a test asserting "never touches the WS
     *  factory at all" (SEL-02's OnDevice-skip semantics) is asserting on real state, not a vacuous
     *  absence of an assertion. */
    val createCount: Int
        get() = sockets.size

    override fun newWebSocket(url: String, listener: WebSocketListener): WebSocket {
        requestedUrls += url
        val socket = FakeWebSocket(url, listener)
        sockets += socket
        return socket
    }

    fun fireOpen(socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onOpen(target, fakeResponse(target.url))
    }

    fun fireMessage(text: String, socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onMessage(target, text)
    }

    fun fireClosing(code: Int, reason: String = "", socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onClosing(target, code, reason)
    }

    fun fireClosed(code: Int, reason: String = "", socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onClosed(target, code, reason)
    }

    fun fireFailure(t: Throwable, response: Response? = null, socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onFailure(target, t, response)
    }

    private fun target(socket: FakeWebSocket?): FakeWebSocket =
        socket ?: lastSocket
            ?: error("FakeWebSocketFactory: no socket created yet — call connect() first.")

    private fun fakeResponse(url: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()
}

/**
 * The fake [WebSocket] a [FakeWebSocketFactory] hands to a `ServerBackend`. Records every [send]
 * call rather than opening a real socket — both the text overload (the wire protocol's JSON control
 * frames) and the binary overload ([sentBinary], the outbound PCM path Phase 9 Task 2 wires).
 */
internal class FakeWebSocket(
    val url: String,
    val listener: WebSocketListener,
) : WebSocket {

    /** Every text frame sent on this socket, oldest first. */
    val sent: MutableList<String> = mutableListOf()

    /** Every binary frame sent on this socket, oldest first (Task 2's outbound PCM send path). */
    val sentBinary: MutableList<ByteString> = mutableListOf()

    /**
     * Phase 21 (D-04): ONE interleaved, ordered log of everything the subject did to this socket —
     * `text:<payload>` / `binary:<size>` for each successful send, `close:<code>` and `cancel` — so a
     * test can assert the stop-flush ORDER (frames, then the `stop` token, then the close reply)
     * rather than per-kind counts. Synchronized: a starved-drain test touches the socket from the
     * test thread while the backend's executor is parked.
     */
    val sendLog: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Invoked after every successful text send — lets a test script a server's reaction (e.g. to
     *  the `stop` token). */
    var onTextSent: ((String) -> Unit)? = null

    var cancelCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    var lastCloseCode: Int? = null
        private set

    /** When `false`, [send] returns `false` without recording — simulates OkHttp's own
     *  already-closing/closed/canceled or over-the-ceiling send failure (Common Pitfall 2). */
    var sendSucceeds: Boolean = true

    /** Reported by [queueSize] — the `ws.bufferedAmount` analog [ServerBackpressureGate] is fed.
     *  `0L` by default (a healthy, draining socket); a test overrides this to drive the gate's
     *  soft/hard thresholds against a real [ServerBackend] rather than the gate in isolation. */
    var fakeQueueSize: Long = 0L

    override fun request(): Request = Request.Builder().url(url).build()

    override fun queueSize(): Long = fakeQueueSize

    override fun send(text: String): Boolean {
        if (!sendSucceeds) return false
        sent += text
        sendLog += "text:$text"
        onTextSent?.invoke(text)
        return true
    }

    override fun send(bytes: ByteString): Boolean {
        if (!sendSucceeds) return false
        sentBinary += bytes
        sendLog += "binary:${bytes.size}"
        return true
    }

    override fun close(code: Int, reason: String?): Boolean {
        closeCount++
        lastCloseCode = code
        sendLog += "close:$code"
        return true
    }

    override fun cancel() {
        cancelCount++
        sendLog += "cancel"
    }
}

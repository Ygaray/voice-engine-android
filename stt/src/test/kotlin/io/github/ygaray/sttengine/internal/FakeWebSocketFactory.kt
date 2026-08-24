package io.github.ygaray.sttengine.internal

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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

    fun fireFailure(t: Throwable, socket: FakeWebSocket? = null) {
        val target = target(socket)
        target.listener.onFailure(target, t, null)
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
 * call rather than opening a real socket — this fixture only exercises text frames (the wire
 * protocol's JSON control frames), matching what `ServerBackend`/`BackendSelector` actually send in
 * this plan (the outbound PCM audio pump is Plan 09-02/09-03 scope).
 */
internal class FakeWebSocket(
    val url: String,
    val listener: WebSocketListener,
) : WebSocket {

    /** Every text frame sent on this socket, oldest first. */
    val sent: MutableList<String> = mutableListOf()

    var cancelCount: Int = 0
        private set
    var closeCount: Int = 0
        private set
    var lastCloseCode: Int? = null
        private set

    /** When `false`, [send] returns `false` without recording — simulates OkHttp's own
     *  already-closing/closed/canceled or over-the-ceiling send failure (Common Pitfall 2). */
    var sendSucceeds: Boolean = true

    override fun request(): Request = Request.Builder().url(url).build()

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        if (!sendSucceeds) return false
        sent += text
        return true
    }

    override fun send(bytes: ByteString): Boolean = sendSucceeds

    override fun close(code: Int, reason: String?): Boolean {
        closeCount++
        lastCloseCode = code
        return true
    }

    override fun cancel() {
        cancelCount++
    }
}

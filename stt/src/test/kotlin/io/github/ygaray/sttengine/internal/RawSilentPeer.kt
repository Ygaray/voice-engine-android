package io.github.ygaray.sttengine.internal

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * A WebSocket peer that completes the upgrade, optionally sends `ready`, and then answers NOTHING —
 * no pong, no close reply, no frame of any kind — while it keeps draining inbound bytes so TCP never
 * stalls (Phase 28, LIVE-03). It models a hung server or a half-open path: the socket is up, the
 * peer is gone.
 *
 * MockWebServer cannot play this part: its WebSocket peer answers every ping automatically.
 *
 * Loopback only, ephemeral port only (threat T-28-24): it never contacts anything, it only accepts
 * one connection. Instants are `System.nanoTime()` values, comparable with the test's own.
 */
internal class RawSilentPeer(private val sendReady: Boolean = true) : AutoCloseable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @Volatile private var accepted: Socket? = null

    /** When the `101 Switching Protocols` response had been written. */
    @Volatile var upgradeAt: Long? = null
        private set

    /** When the `{"type":"ready"}` frame had been written (null if [sendReady] is false). */
    @Volatile var readyAt: Long? = null
        private set

    /** When the first inbound PING frame (opcode 0x9) was read; null if none was seen. */
    @Volatile var firstPingAt: Long? = null
        private set

    /** How many inbound frames were read, any opcode (diagnostic only). */
    @Volatile var inboundFrames: Int = 0
        private set

    val url: String = checkNotNull(server.inetAddress.hostAddress).let { host ->
        val literal = if (':' in host) "[$host]" else host // an IPv6 loopback needs brackets
        "ws://$literal:${server.localPort}/"
    }

    private val thread = Thread({ serve() }, "raw-silent-peer").apply {
        isDaemon = true
        start()
    }

    private fun serve() {
        try {
            val socket = server.accept()
            accepted = socket
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = socket.getOutputStream()

            val key = readUpgradeKey(input)
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
            )
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            upgradeAt = System.nanoTime()

            if (sendReady) {
                val payload = """{"type":"ready"}""".toByteArray(Charsets.UTF_8)
                // One unmasked server text frame: FIN + opcode 0x1, then a 7-bit length.
                output.write(byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload)
                output.flush()
                readyAt = System.nanoTime()
            }

            // From here on: read and discard every frame, answer none.
            while (true) drainFrame(input)
        } catch (_: IOException) {
            // The client dropped the socket (the expected end) or close() tore it down.
        }
    }

    /** Reads the HTTP upgrade request up to its blank line and returns `Sec-WebSocket-Key`. */
    private fun readUpgradeKey(input: DataInputStream): String {
        var key: String? = null
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Sec-WebSocket-Key", ignoreCase = true)) {
                key = line.substring(colon + 1).trim()
            }
        }
        return checkNotNull(key) { "upgrade request carried no Sec-WebSocket-Key" }
    }

    /** One CRLF-terminated line, byte by byte, so nothing past the header block is consumed. */
    private fun readLine(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("upgrade request ended early")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    /** Reads (and discards) one client frame; records the first ping. Client frames are masked. */
    private fun drainFrame(input: DataInputStream) {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val opcode = b0 and 0x0F
        if (opcode == OPCODE_PING && firstPingAt == null) firstPingAt = System.nanoTime()
        inboundFrames++
        val length = when (val len7 = b1 and 0x7F) {
            126 -> input.readUnsignedShort().toLong()
            127 -> input.readLong()
            else -> len7.toLong()
        }
        val masked = (b1 and 0x80) != 0
        if (masked) input.readFully(ByteArray(4))
        var remaining = length
        val sink = ByteArray(1024)
        while (remaining > 0) {
            val n = input.read(sink, 0, minOf(sink.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("frame payload ended early")
            remaining -= n
        }
    }

    override fun close() {
        runCatching { accepted?.close() }
        runCatching { server.close() }
        thread.join(1_000)
    }

    private companion object {
        /** RFC 6455 section 1.3. */
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_PING = 0x9
    }
}

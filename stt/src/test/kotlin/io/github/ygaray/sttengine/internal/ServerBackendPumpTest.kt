package io.github.ygaray.sttengine.internal

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The server-path PCM pump (Phase 21, SRV-05, D-01/D-03) — including the test D-13 says was
 * missing all along: audio read from the session's [PcmSource] reaches the socket after `ready`
 * with nobody handing the backend a frame by hand.
 *
 * Two layers: the pure [pumpFrames] loop (no thread, no dispatcher), and the real backend with a
 * real single-thread executor as its `mainDispatcher` — never an unconfined test dispatcher, which
 * would run the consumer on the pump thread and pass for the wrong reason (Pitfall 8). Robolectric
 * (sdk 35) only because the config frame is built with `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerBackendPumpTest {

    // --- the pure frame loop -------------------------------------------------------------------

    @Test
    fun `short reads are accumulated into whole 1024-byte frames`() {
        val source = FinitePcmSource(frames = 4, chunkBytes = 300, sample = 0x1234)
        val running = AtomicBoolean(true)
        val frames = mutableListOf<ByteArray>()

        pumpFrames(source, running, ServerBackend.FRAME_BYTES) { frames += it }

        assertEquals(4, frames.size)
        assertTrue(frames.all { it.size == 1024 })
        assertTrue("reads must have been short: ${source.readCount}", source.readCount > 4)
        // Little-endian 0x1234 throughout — accumulation preserved byte order across short reads.
        assertTrue(
            frames.all { f -> f.indices.all { i -> f[i] == (if (i % 2 == 0) 0x34 else 0x12).toByte() } },
        )
        assertFalse("running must be cleared when the stream ends", running.get())
    }

    @Test
    fun `a source that returns 0 on its first read yields no frames and ends quietly`() {
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int = 0
            override fun stop() = Unit
            override fun release() = Unit
        }
        val running = AtomicBoolean(true)
        val frames = mutableListOf<ByteArray>()

        pumpFrames(source, running, ServerBackend.FRAME_BYTES) { frames += it }

        assertTrue(frames.isEmpty())
        assertFalse(running.get())
    }

    @Test
    fun `a read that throws ends the loop without propagating`() {
        var reads = 0
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int {
                reads++
                if (reads > 2) throw IllegalStateException("recorder released under the read")
                buffer.fill(1)
                return buffer.size
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val running = AtomicBoolean(true)
        val frames = mutableListOf<ByteArray>()

        pumpFrames(source, running, ServerBackend.FRAME_BYTES) { frames += it } // must not throw

        assertEquals("the frames read before the failure are still delivered", 2, frames.size)
        assertFalse(running.get())
    }

    @Test
    fun `clearing running stops the loop before the next read`() {
        val running = AtomicBoolean(true)
        val source = FinitePcmSource(frames = 1_000)
        var frames = 0

        pumpFrames(source, running, ServerBackend.FRAME_BYTES) {
            frames++
            if (frames == 3) running.set(false)
        }

        assertEquals(3, frames)
    }

    // --- the real backend (the D-13 missing test) ----------------------------------------------

    private suspend fun awaitOn(
        dispatcher: CoroutineDispatcher,
        what: String,
        timeoutMs: Long = 2_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!withContext(dispatcher) { condition() }) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            delay(20)
        }
    }

    /** Connects [backend] through [fakeWs] on [mainExec] and fires open + ready. */
    private suspend fun CoroutineScope.connectToReady(
        backend: ServerBackend,
        fakeWs: FakeWebSocketFactory,
        mainExec: CoroutineDispatcher,
    ) {
        val connected = async(mainExec) { backend.connect() }
        awaitOn(mainExec, "the socket to be created") { fakeWs.lastSocket != null }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        assertTrue("connect() must resolve true on ready", connected.await())
    }

    @Test
    fun `after ready the pump delivers every captured frame to the socket with no manual send`() {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    // 8192 = quarter scale → level 0.25 / √½ ≈ 0.3536 on every window.
                    val sources = finitePcmSourceFactory(frames = 6, sample = 8192)
                    val backend = ServerBackend(
                        webSocketFactory = fakeWs,
                        serverUrl = "wss://example.ts.net:8444/",
                        language = "en",
                        mainDispatcher = mainExec,
                        pcmSourceFactory = sources,
                    )
                    val levels: MutableList<Float> = Collections.synchronizedList(mutableListOf())
                    // Unconfined collector: consumes each level synchronously on the emitting pump
                    // thread, so the flow's one-slot DROP_OLDEST buffer cannot drop a value here.
                    val levelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    levelScope.launch { backend.level.collect { levels += it } }
                    try {
                        connectToReady(backend, fakeWs, mainExec)
                        val socket = checkNotNull(fakeWs.lastSocket)

                        awaitOn(mainExec, "6 binary frames on the socket") { socket.sentBinary.size >= 6 }
                        delay(100) // a stray 7th frame would show up here
                        val sent = withContext(mainExec) { socket.sentBinary.toList() }

                        assertEquals(6, sent.size)
                        assertTrue(sent.all { it.size == 1024 })
                        assertTrue("level must emit at least twice: $levels", levels.size >= 2)
                        val expected = (0.25 / Math.sqrt(0.5)).toFloat()
                        levels.toList().forEach { assertEquals(expected, it, 1e-3f) }
                    } finally {
                        levelScope.cancel()
                        withContext(mainExec) { backend.abandon() }
                    }
                }
            }
        } finally {
            mainExec.close()
        }
    }

    @Test
    fun `abandon stops the pump, joins it, and only then releases the mic`() {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    // Effectively endless, but paced, so abandon() catches the pump mid-stream.
                    val sources = finitePcmSourceFactory(frames = 100_000, readDelayMs = 5)
                    val backend = ServerBackend(
                        webSocketFactory = fakeWs,
                        serverUrl = "wss://example.ts.net:8444/",
                        language = "en",
                        mainDispatcher = mainExec,
                        pcmSourceFactory = sources,
                    )
                    connectToReady(backend, fakeWs, mainExec)
                    val socket = checkNotNull(fakeWs.lastSocket)
                    awaitOn(mainExec, "the pump to be streaming") { socket.sentBinary.size >= 2 }

                    val source = sources.last
                    val pump = assertNotNullAndGet(source.readerThread)
                    assertEquals(ServerBackend.PUMP_THREAD_NAME, pump.name)
                    assertTrue("the pump must be a daemon thread", pump.isDaemon)
                    assertTrue(pump.isAlive)

                    withContext(mainExec) { backend.abandon() }

                    assertTrue("abandon() must stop the source", source.stopped)
                    assertTrue("abandon() must release the source", source.released)
                    assertEquals(
                        "release() must run only after the pump thread was joined",
                        false,
                        source.readerAliveAtRelease,
                    )
                    assertFalse("no pump thread may outlive teardown", pump.isAlive)

                    // Nothing more reaches the socket once the instance is torn down.
                    val after = withContext(mainExec) { socket.sentBinary.size }
                    delay(100)
                    assertEquals(after, withContext(mainExec) { socket.sentBinary.size })
                }
            }
        } finally {
            mainExec.close()
        }
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

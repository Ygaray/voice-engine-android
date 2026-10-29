package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
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

    // --- the end reason (Phase 28, LIVE-01, D-13): true = the pump ended UNASKED ----------------

    @Test
    fun `pumpFrames returns true when a read returns end of stream while running is still set`() {
        val source = FinitePcmSource(frames = 2) // returns -1 forever once drained
        val running = AtomicBoolean(true)

        val endedUnasked = pumpFrames(source, running, ServerBackend.FRAME_BYTES) { }

        assertTrue("a -1 read with running still set is an unasked end", endedUnasked)
        assertFalse(running.get())
    }

    @Test
    fun `pumpFrames returns true when a read returns 0 while running is still set`() {
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int = 0
            override fun stop() = Unit
            override fun release() = Unit
        }

        assertTrue(pumpFrames(source, AtomicBoolean(true), ServerBackend.FRAME_BYTES) { })
    }

    @Test
    fun `pumpFrames returns true when a read throws while running is still set`() {
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int = throw IllegalStateException("recorder died")
            override fun stop() = Unit
            override fun release() = Unit
        }

        assertTrue(pumpFrames(source, AtomicBoolean(true), ServerBackend.FRAME_BYTES) { })
    }

    @Test
    fun `pumpFrames returns false when running was cleared - before the next read, after a late read, or before a throw`() {
        // Cleared between frames: the loop check ends it.
        val running1 = AtomicBoolean(true)
        var frames = 0
        val cleared = pumpFrames(FinitePcmSource(frames = 1_000), running1, ServerBackend.FRAME_BYTES) {
            frames++
            if (frames == 2) running1.set(false)
        }
        assertFalse("a loop that saw running cleared ended because it was asked to", cleared)

        // Cleared while a read was in flight, which then returned bytes.
        val running2 = AtomicBoolean(true)
        val late = object : PcmSource {
            override fun read(buffer: ByteArray): Int {
                running2.set(false)
                return buffer.size
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        assertFalse(pumpFrames(late, running2, ServerBackend.FRAME_BYTES) { })

        // Cleared, then the read returned end of stream (teardown's source.stop() unblocking it).
        val running3 = AtomicBoolean(true)
        val stoppedRead = object : PcmSource {
            override fun read(buffer: ByteArray): Int {
                running3.set(false)
                return -1
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        assertFalse(pumpFrames(stoppedRead, running3, ServerBackend.FRAME_BYTES) { })

        // Cleared, then the read threw (a recorder released under a wedged read after teardown).
        val running4 = AtomicBoolean(true)
        val throwsAfterClear = object : PcmSource {
            override fun read(buffer: ByteArray): Int {
                running4.set(false)
                throw IllegalStateException("released under the read")
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        assertFalse(pumpFrames(throwsAfterClear, running4, ServerBackend.FRAME_BYTES) { })

        // Never running at all.
        assertFalse(pumpFrames(FinitePcmSource(frames = 1), AtomicBoolean(false), ServerBackend.FRAME_BYTES) { })
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

    @Test
    fun `a read that returns after running was cleared is dropped, never emitted`() {
        // 21 W12 review, finding 1(a): teardown clears the flag while a read is in flight; the bytes
        // that read returns are post-stop capture and must not become a frame (or a level).
        val running = AtomicBoolean(true)
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int {
                running.set(false) // teardown lands while this read is blocked
                buffer.fill(7)
                return buffer.size // ...and the read still returns a whole frame
            }
            override fun stop() = Unit
            override fun release() = Unit
        }
        val frames = mutableListOf<ByteArray>()

        pumpFrames(source, running, ServerBackend.FRAME_BYTES) { frames += it }

        assertTrue("a late read must be dropped: ${frames.size} frame(s) emitted", frames.isEmpty())
        assertFalse(running.get())
    }

    // --- release ownership (21 W12 review, finding 1(b)) ----------------------------------------

    @Test
    fun `release handoff - a pump that exits first leaves the release to teardown`() {
        val handoff = PumpReleaseHandoff()
        assertFalse("the pump exited before any hand-off: it must not release", handoff.pumpExited())
        assertFalse("teardown after the pump exited must release itself", handoff.handOffToPump())
    }

    @Test
    fun `release handoff - teardown that gives up first hands the release to the pump`() {
        val handoff = PumpReleaseHandoff()
        assertTrue("teardown with the pump still reading hands the release over", handoff.handOffToPump())
        assertTrue("the pump must then release on its way out", handoff.pumpExited())
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
                    // A repeat teardown is a no-op: release runs exactly once.
                    withContext(mainExec) { backend.releaseAudioSource() }
                    assertEquals("release() must run exactly once", 1, source.releaseCount)
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

    @Test
    fun `a pump wedged in read past the join bound releases the source itself, once, and emits nothing late`() {
        // 21 W12 review, finding 1: a vendor read that ignores stop(). Teardown must stay bounded,
        // must NOT release the recorder under the live reader, and the late read's audio must
        // reach neither the level flow nor the socket. Once the read returns, the pump thread
        // releases the source — exactly once.
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    // Two free frames prime the 3-frame level window; the wedged read is the 3rd,
                    // so without the post-read recheck it would complete a window and emit a level.
                    val source = WedgedPcmSource(freeReads = 2, sample = 0x2000)
                    val backend = ServerBackend(
                        webSocketFactory = fakeWs,
                        serverUrl = "wss://example.ts.net:8444/",
                        language = "en",
                        mainDispatcher = mainExec,
                        pcmSourceFactory = SingleSourceFactory(source),
                    )
                    val levels: MutableList<Float> = Collections.synchronizedList(mutableListOf())
                    val levelScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    levelScope.launch { backend.level.collect { levels += it } }
                    try {
                        connectToReady(backend, fakeWs, mainExec)
                        val socket = checkNotNull(fakeWs.lastSocket)
                        awaitOn(mainExec, "the two free frames on the socket") { socket.sentBinary.size >= 2 }
                        assertTrue("the pump never reached the wedged read", source.awaitWedged(2_000))
                        val pump = assertNotNullAndGet(source.readerThread)

                        val startedNs = System.nanoTime()
                        withContext(mainExec) { backend.abandon() }
                        val tookMs = (System.nanoTime() - startedNs) / 1_000_000

                        assertTrue("teardown must stay bounded (took $tookMs ms)", tookMs < 2_000)
                        assertTrue("abandon() must still stop the source", source.stopped)
                        assertTrue("the wedged pump is still alive", pump.isAlive)
                        assertEquals(
                            "the source must not be released under a reader still inside read()",
                            0,
                            source.releaseCount,
                        )
                        // A repeat teardown must not release it either: ownership was handed over.
                        withContext(mainExec) { backend.releaseAudioSource() }
                        assertEquals(0, source.releaseCount)

                        source.unblock()
                        pump.join(2_000)
                        assertFalse("the pump must exit once its read returns", pump.isAlive)

                        assertEquals("release() must run exactly once", 1, source.releaseCount)
                        assertEquals("the pump thread owns the release now", pump, source.releaseThread)
                        assertEquals("no read may be in flight at release", false, source.inReadAtRelease)

                        delay(100)
                        assertTrue("the late read must not emit a level: $levels", levels.isEmpty())
                        assertEquals(
                            "the late read must not reach the socket",
                            2,
                            withContext(mainExec) { socket.sentBinary.size },
                        )
                    } finally {
                        source.unblock()
                        levelScope.cancel()
                    }
                }
            }
        } finally {
            mainExec.close()
        }
    }

    /** Collects [ServerBackend.errors] synchronously (Unconfined) into a thread-safe list. */
    private class ErrorRecorder(backend: ServerBackend) {
        val errors: MutableList<SttError> = Collections.synchronizedList(mutableListOf())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        init {
            scope.launch { backend.errors.collect { errors += it } }
        }

        val codes: List<SttErrorCode> get() = synchronized(errors) { errors.map { it.code } }

        fun close() = scope.cancel()
    }

    private fun newBackend(fakeWs: FakeWebSocketFactory, mainExec: CoroutineDispatcher, pcm: PcmSourceFactory) =
        ServerBackend(
            webSocketFactory = fakeWs,
            serverUrl = "wss://example.ts.net:8444/",
            language = "en",
            mainDispatcher = mainExec,
            pcmSourceFactory = pcm,
        )

    @Test
    fun `a pump that ended on its own fails the session and releases the mic exactly once, after the join`() {
        // Rewritten for Phase 28 (D-13). The old premise — an ended pump sits there until a teardown
        // releases it — is exactly the LIVE-01 hole: nobody observed the end. Now the end itself
        // fails the session, which releases the source once, only after the pump thread is gone,
        // and a later teardown changes nothing.
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    val sources = finitePcmSourceFactory(frames = 2, endsStream = true)
                    val backend = newBackend(fakeWs, mainExec, sources)
                    connectToReady(backend, fakeWs, mainExec)
                    awaitOn(mainExec, "the dead pump to fail the session") {
                        backend.state.value == EngineState.Error
                    }
                    val source = sources.last
                    val pump = assertNotNullAndGet(source.readerThread)
                    pump.join(2_000)
                    assertFalse(pump.isAlive)
                    assertEquals("the failed session releases the source once", 1, source.releaseCount)
                    assertEquals(
                        "release() must run only after the pump thread was gone",
                        false,
                        source.readerAliveAtRelease,
                    )

                    withContext(mainExec) {
                        backend.abandon()
                        backend.releaseAudioSource()
                    }

                    assertEquals("a later teardown must not release again", 1, source.releaseCount)
                }
            }
        } finally {
            mainExec.close()
        }
    }

    // --- LIVE-01 (D-13): an unasked pump end fails the session; a requested teardown never does -----

    @Test
    fun `a pump that ends unasked after ready emits one AUDIO_ERROR, goes Error, cancels the socket once and releases once`() {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    // Three frames of audio, then the mic dies (end of stream with the run flag set).
                    val sources = finitePcmSourceFactory(frames = 3, endsStream = true)
                    val backend = newBackend(fakeWs, mainExec, sources)
                    val recorder = ErrorRecorder(backend)
                    try {
                        connectToReady(backend, fakeWs, mainExec)
                        val socket = checkNotNull(fakeWs.lastSocket)

                        awaitOn(mainExec, "the session to leave Listening on the dead pump") {
                            backend.state.value == EngineState.Error
                        }
                        delay(300) // a second error or cancel would show up here

                        assertEquals(listOf(SttErrorCode.AUDIO_ERROR), recorder.codes)
                        assertEquals(EngineState.Error, withContext(mainExec) { backend.state.value })
                        assertEquals("the socket is cancelled exactly once", 1, socket.cancelCount)
                        assertTrue(
                            "only the captured frames were sent",
                            withContext(mainExec) { socket.sentBinary.size } <= 3,
                        )
                        val source = sources.last
                        assertEquals("the mic is released exactly once", 1, source.releaseCount)
                        assertEquals(false, source.readerAliveAtRelease)

                        // A stop() after the death is a no-op: no throw, no emission, no state change.
                        withContext(mainExec) { backend.stop() }
                        delay(100)
                        assertEquals(listOf(SttErrorCode.AUDIO_ERROR), recorder.codes)
                        assertEquals(EngineState.Error, withContext(mainExec) { backend.state.value })
                        assertEquals(1, socket.cancelCount)
                        assertEquals(1, source.releaseCount)
                    } finally {
                        recorder.close()
                        withContext(mainExec) { backend.abandon() }
                    }
                }
            }
        } finally {
            mainExec.close()
        }
    }

    /** Drives a parked (live, silent) mic to `ready`, runs [teardown] on main, and proves the pump's
     *  end it caused never reads as a death: no error within 300 ms. Returns the final state. */
    private fun requestedTeardown(teardown: suspend (ServerBackend) -> Unit): EngineState {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    val sources = finitePcmSourceFactory(frames = 2) // parks: a live mic
                    val backend = newBackend(fakeWs, mainExec, sources)
                    val recorder = ErrorRecorder(backend)
                    try {
                        connectToReady(backend, fakeWs, mainExec)
                        val socket = checkNotNull(fakeWs.lastSocket)
                        // The server answers the stop token with its clean close, so stop() flushes.
                        socket.onTextSent = { text ->
                            if (text == ServerBackend.STOP_TOKEN) fakeWs.fireClosing(1000, "", socket)
                        }
                        awaitOn(mainExec, "the pump to be parked on the live mic") {
                            sources.opened.isNotEmpty() && sources.last.exhausted
                        }
                        val pump = assertNotNullAndGet(sources.last.readerThread)

                        withContext(mainExec) { teardown(backend) }
                        pump.join(2_000)
                        assertFalse("the asked-for pump end must have happened", pump.isAlive)
                        delay(300)

                        assertTrue("a requested teardown must not emit an error: ${recorder.codes}", recorder.codes.isEmpty())
                        withContext(mainExec) { backend.state.value }
                    } finally {
                        recorder.close()
                        withContext(mainExec) { backend.abandon() }
                    }
                }
            }
        } finally {
            mainExec.close()
        }
    }

    @Test
    fun `a requested stop never reads as a dead pump and ends Idle`() {
        assertEquals(EngineState.Idle, requestedTeardown { it.stop() })
    }

    @Test
    fun `a requested abandon never reads as a dead pump`() {
        assertTrue(requestedTeardown { it.abandon() } != EngineState.Error)
    }

    @Test
    fun `the selector's releaseAudioSource never reads as a dead pump`() {
        assertEquals(EngineState.Listening, requestedTeardown { it.releaseAudioSource() })
    }

    /**
     * The fail-versus-pump-end race (review 28-05 round 2), on the real single-thread executor. The
     * executor is held by a latch-guarded blocker task, so the order in which the socket failure and
     * the pump-end callback are QUEUED behind it is exactly the order they run in. Whichever runs
     * first wins; the other is a no-op. Either way: one error, one cancel, one release.
     */
    private fun failVersusPumpEnd(pumpEndFirst: Boolean): Triple<List<SttErrorCode>, Int, Int> {
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    val gate = CountDownLatch(1)
                    val (source, factory) = gatedEndPcmSourceFactory(gate)
                    val backend = newBackend(fakeWs, mainExec, factory)
                    val recorder = ErrorRecorder(backend)
                    try {
                        connectToReady(backend, fakeWs, mainExec)
                        val socket = checkNotNull(fakeWs.lastSocket)
                        val deadline = System.currentTimeMillis() + 5_000
                        while (source.readerThread == null) {
                            check(System.currentTimeMillis() < deadline) { "the pump never started reading" }
                            delay(10)
                        }
                        val pump = assertNotNullAndGet(source.readerThread)
                        assertEquals(ServerBackend.PUMP_THREAD_NAME, pump.name)

                        val blocker = CountDownLatch(1)
                        executor.execute { blocker.await() }
                        try {
                            if (pumpEndFirst) {
                                gate.countDown()
                                pump.join(5_000) // its end callback is now queued FIRST
                                assertFalse(pump.isAlive)
                                fakeWs.fireFailure(IOException("connection reset"))
                            } else {
                                fakeWs.fireFailure(IOException("connection reset")) // queued FIRST
                                gate.countDown()
                                pump.join(5_000) // its end callback is now queued SECOND
                                assertFalse(pump.isAlive)
                            }
                        } finally {
                            blocker.countDown()
                        }

                        awaitOn(mainExec, "the session to fail") { backend.state.value == EngineState.Error }
                        delay(300) // a second error would show up here
                        Triple(recorder.codes, socket.cancelCount, source.releaseCount)
                    } finally {
                        recorder.close()
                        gate.countDown()
                        withContext(mainExec) { backend.abandon() }
                    }
                }
            }
        } finally {
            mainExec.close()
        }
    }

    @Test
    fun `fail first, then the pump end - exactly one SERVER_UNREACHABLE, one cancel, one release`() {
        val (codes, cancels, releases) = failVersusPumpEnd(pumpEndFirst = false)
        assertEquals(listOf(SttErrorCode.SERVER_UNREACHABLE), codes)
        assertEquals(1, cancels)
        assertEquals(1, releases)
    }

    @Test
    fun `pump end first, then fail - exactly one AUDIO_ERROR, one cancel, one release`() {
        val (codes, cancels, releases) = failVersusPumpEnd(pumpEndFirst = true)
        assertEquals(listOf(SttErrorCode.AUDIO_ERROR), codes)
        assertEquals(1, cancels)
        assertEquals(1, releases)
    }

    @Test
    fun `a duplicate ready never opens a second source or pump`() {
        // 21 W12 review, finding 2: a repeat `ready` while Listening used to open another source and
        // start another pump, orphaning the first (never stopped, never released). It is ignored:
        // one source, one pump, the session stays Listening with no error.
        val executor = Executors.newSingleThreadExecutor()
        val mainExec = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val fakeWs = FakeWebSocketFactory()
                    // A factory that would happily open a second source — so only the guard stops it.
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

                    fakeWs.fireMessage("""{"type":"ready"}""")
                    fakeWs.fireMessage("""{"type":"ready"}""")
                    delay(100)

                    assertEquals("a repeat ready must not open another source", 1, sources.opened.size)
                    assertEquals(EngineState.Listening, withContext(mainExec) { backend.state.value })
                    assertEquals(null, withContext(mainExec) { backend.lastError })
                    // The original pump keeps streaming.
                    val before = withContext(mainExec) { socket.sentBinary.size }
                    awaitOn(mainExec, "the original pump to keep streaming") { socket.sentBinary.size > before }

                    withContext(mainExec) { backend.abandon() }
                    val source = sources.last
                    assertTrue(source.stopped)
                    assertEquals("the one source is released exactly once", 1, source.releaseCount)
                    assertEquals(false, source.readerAliveAtRelease)
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

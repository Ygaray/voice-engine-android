package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.corrections.CorrectionPair
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString.Companion.toByteString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ServerBackend`'s own WS state machine (Phase 9 Task 2) — this drives the WS state machine only,
 * through [FakeWebSocketFactory], never `SpeechRecognizer` or a `Bundle` (mirrors `StateMachineTest`'s
 * harness-free style). Covers the outbound backpressure gate directly, the gated send path's
 * Boolean-checked `WebSocket.send()` discipline (Common Pitfall 2), a Final frame's exactly-once
 * routing, and a malformed frame's typed (never thrown) failure.
 *
 * **Robolectric, not plain JUnit** (a deviation from this plan's own action text — see
 * `09-02-SUMMARY.md`): [ServerBackend.doOnOpen] builds the config frame via `org.json.JSONObject`,
 * which is a platform stub on the plain-JVM mockable `android.jar` (`testOptions.unitTests.
 * isReturnDefaultValues = true`) — every method returns a default value there, so `JSONObject.put()`
 * returns `null` and the very next chained `.put()` call NPEs. `WsProtocolTest` hit this exact issue
 * in Plan 09-01 (its own deviation #2) for the identical reason; any test that fires `onOpen` on a
 * REAL `ServerBackend` (not a hand-rolled fake) needs the same fix.
 *
 * Every `ServerBackend` under test shares its `mainDispatcher` with the OUTER `launch(dispatcher) {}`
 * calls that drive `connect()` — both bound to the SAME `UnconfinedTestDispatcher(testScheduler)` —
 * so `connect()` starts executing (and creates its socket) eagerly, before the very next line of test
 * code fires a WS callback on it. Mirrors `BackendSelectorTest`'s established pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class ServerBackendTest {

    private fun newBackend(
        fakeWs: FakeWebSocketFactory,
        dispatcher: CoroutineDispatcher,
        gate: ServerBackpressureGate = ServerBackpressureGate(),
    ): ServerBackend = ServerBackend(
        webSocketFactory = fakeWs,
        serverUrl = "wss://example.ts.net:8444/",
        language = "en",
        mainDispatcher = dispatcher,
        backpressureGate = gate,
    )

    // --- direct ServerBackpressureGate coverage --------------------------------------------------

    @Test
    fun `ServerBackpressureGate evaluate() at or below the soft threshold returns Send`() {
        val gate = ServerBackpressureGate(ServerBackpressureGateOptions(soft = 1_000L, low = 0L))

        assertEquals(BackpressureAction.Send, gate.evaluate(bufferedAmount = 0L, now = 0L))
        assertEquals(BackpressureAction.Send, gate.evaluate(bufferedAmount = 1_000L, now = 1L))
    }

    @Test
    fun `ServerBackpressureGate sustained above the hard threshold past hardDebounceMs returns Fatal`() {
        val gate = ServerBackpressureGate(
            ServerBackpressureGateOptions(soft = 100L, low = 0L, hard = 200L, hardDebounceMs = 1_000L),
        )

        // First breach: starts the debounce clock, does not yet escalate.
        assertEquals(
            BackpressureAction.DropOldest(warn = true),
            gate.evaluate(bufferedAmount = 300L, now = 0L),
        )
        // Still within the debounce window.
        assertEquals(
            BackpressureAction.DropOldest(warn = false),
            gate.evaluate(bufferedAmount = 300L, now = 500L),
        )
        // Sustained continuously above `hard` for >= hardDebounceMs: escalates.
        assertEquals(BackpressureAction.Fatal, gate.evaluate(bufferedAmount = 300L, now = 1_000L))
    }

    @Test
    fun `a brief spike above hard that recovers below it never escalates`() {
        val gate = ServerBackpressureGate(
            ServerBackpressureGateOptions(soft = 100L, low = 0L, hard = 200L, hardDebounceMs = 1_000L),
        )

        gate.evaluate(bufferedAmount = 300L, now = 0L) // breach starts
        // Recovers to at/below hard (but still above soft) — resets the debounce clock.
        gate.evaluate(bufferedAmount = 150L, now = 100L)
        // Breaches again, but the clock restarted at 100L, so 1_000L later has not yet elapsed.
        val action = gate.evaluate(bufferedAmount = 300L, now = 1_050L)
        assertTrue("a recovered-then-rebreached spike must not carry over the old clock", action !is BackpressureAction.Fatal)
    }

    @Test
    fun `ServerBackpressureGate clears the warning with hysteresis below the low mark`() {
        val gate = ServerBackpressureGateOptions(soft = 100L, low = 50L).let { ServerBackpressureGate(it) }

        gate.evaluate(bufferedAmount = 200L, now = 0L)
        assertTrue(gate.warning)
        // Between low and soft: warning stays active (hysteresis).
        gate.evaluate(bufferedAmount = 75L, now = 1L)
        assertTrue(gate.warning)
        assertEquals(BackpressureAction.ClearWarning, gate.evaluate(bufferedAmount = 10L, now = 2L))
        assertFalse(gate.warning)
    }

    // --- WR-03: ServerBackpressureGateOptions must validate low < soft < hard -----------------------

    @Test
    fun `ServerBackpressureGateOptions rejects hard less than or equal to soft`() {
        try {
            ServerBackpressureGateOptions(soft = 100L, low = 0L, hard = 100L)
            org.junit.Assert.fail("expected an IllegalArgumentException for hard <= soft")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `ServerBackpressureGateOptions rejects low greater than or equal to soft`() {
        try {
            ServerBackpressureGateOptions(soft = 100L, low = 100L)
            org.junit.Assert.fail("expected an IllegalArgumentException for low >= soft")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // --- ServerBackend-level coverage ---------------------------------------------------------------
    //
    // Every `connect()`/collector coroutine below is launched on `backgroundScope` — `runTest`'s
    // sanctioned home for coroutines that outlive the test body's own assertions (a raw `launch{}`
    // that never completes, e.g. `connect()` before `Ready`, would otherwise fail the test with an
    // UncompletedCoroutinesError once the test body returns).

    @Test
    fun `a parsed Final ServerFrame is routed to ServerBackend finals exactly once`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val backend = newBackend(fakeWs, dispatcher)
        val finals = mutableListOf<FinalSegment>()
        backgroundScope.launch(dispatcher) { backend.finals.collect { finals += it } }

        backgroundScope.launch(dispatcher) { backend.connect() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"final","text":"hello world","segmentId":3}""")

        assertEquals(listOf(FinalSegment("hello world", 3)), finals)
    }

    @Test
    fun `a Final frame's language reaches the emitted FinalSegment`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val backend = newBackend(fakeWs, dispatcher)
        val finals = mutableListOf<FinalSegment>()
        backgroundScope.launch(dispatcher) { backend.finals.collect { finals += it } }

        backgroundScope.launch(dispatcher) { backend.connect() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"final","text":"hola","segmentId":0,"language":"es"}""")

        assertEquals(listOf(FinalSegment("hola", 0, "es")), finals)
    }

    @Test
    fun `webSocket send() returning false routes to fail() with SEND_BACKPRESSURE`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val backend = newBackend(fakeWs, dispatcher)
        val errors = mutableListOf<SttError>()
        backgroundScope.launch(dispatcher) { backend.errors.collect { errors += it } }

        backgroundScope.launch(dispatcher) { backend.connect() }
        fakeWs.fireOpen()
        checkNotNull(fakeWs.lastSocket).sendSucceeds = false

        backend.sendPcmFrame(ByteArray(4))

        assertEquals(1, errors.size)
        assertEquals(SttErrorCode.SEND_BACKPRESSURE, errors.single().code)
        assertEquals(EngineState.Error, backend.state.value)
    }

    @Test
    fun `sendPcmFrame flushes over the real socket when the queue is healthy`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val backend = newBackend(fakeWs, dispatcher)

        backgroundScope.launch(dispatcher) { backend.connect() }
        fakeWs.fireOpen()
        val socket = checkNotNull(fakeWs.lastSocket)
        assertTrue("expected the config frame already sent as text", socket.sent.isNotEmpty())
        assertTrue("no binary frame should exist yet", socket.sentBinary.isEmpty())

        backend.sendPcmFrame(byteArrayOf(1, 2, 3, 4))

        assertEquals("exactly one flushed PCM frame", 1, socket.sentBinary.size)
        assertEquals(byteArrayOf(1, 2, 3, 4).toByteString(), socket.sentBinary.single())
    }

    @Test
    fun `malformed server frame produces a typed internal failure via fail(), never a thrown exception`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val fakeWs = FakeWebSocketFactory()
            val backend = newBackend(fakeWs, dispatcher)
            val errors = mutableListOf<SttError>()
            backgroundScope.launch(dispatcher) { backend.errors.collect { errors += it } }

            backgroundScope.launch(dispatcher) { backend.connect() }
            fakeWs.fireOpen()
            fakeWs.fireMessage("not valid json at all")

            assertEquals(1, errors.size)
            assertEquals(SttErrorCode.PROTOCOL_VIOLATION, errors.single().code)
            assertEquals(EngineState.Error, backend.state.value)
        }

    // --- Phase 13-02: ServerFrame.Final applies the configured correction ruleset ------------------

    @Test
    fun `a Final frame containing the ruleset's wrong text is corrected before reaching finals`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val fakeWs = FakeWebSocketFactory()
            val backend = ServerBackend(
                webSocketFactory = fakeWs,
                serverUrl = "wss://example.ts.net:8444/",
                language = "en",
                mainDispatcher = dispatcher,
                corrections = listOf(CorrectionPair(wrong = "kubernetes", right = "Kubernetes")),
            )
            val finals = mutableListOf<FinalSegment>()
            backgroundScope.launch(dispatcher) { backend.finals.collect { finals += it } }

            backgroundScope.launch(dispatcher) { backend.connect() }
            fakeWs.fireOpen()
            fakeWs.fireMessage("""{"type":"final","text":"deploy kubernetes now","segmentId":1}""")

            assertEquals(listOf(FinalSegment("deploy Kubernetes now", 1)), finals)
        }

    @Test
    fun `a Final frame with no configured corrections passes frame text through unchanged`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val fakeWs = FakeWebSocketFactory()
            val backend = ServerBackend(
                webSocketFactory = fakeWs,
                serverUrl = "wss://example.ts.net:8444/",
                language = "en",
                mainDispatcher = dispatcher,
                corrections = null,
            )
            val finals = mutableListOf<FinalSegment>()
            backgroundScope.launch(dispatcher) { backend.finals.collect { finals += it } }

            backgroundScope.launch(dispatcher) { backend.connect() }
            fakeWs.fireOpen()
            fakeWs.fireMessage("""{"type":"final","text":"deploy kubernetes now","segmentId":1}""")

            assertEquals(listOf(FinalSegment("deploy kubernetes now", 1)), finals)
        }

    // --- CR-01: abandon() must release an already-opened server-path mic ---------------------------

    private class RecordingPcmSource : PcmSource {
        var released = false
            private set

        override fun read(buffer: ByteArray): Int = 0
        override fun stop() = Unit
        override fun release() {
            released = true
        }
    }

    @Test
    fun `abandon() after Ready already opened the mic still releases it`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val recordingSource = RecordingPcmSource()
        val backend = ServerBackend(
            webSocketFactory = fakeWs,
            serverUrl = "wss://example.ts.net:8444/",
            language = "en",
            mainDispatcher = dispatcher,
            pcmSourceFactory = PcmSourceFactory { recordingSource },
        )

        backgroundScope.launch(dispatcher) { backend.connect() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        assertEquals(EngineState.Listening, backend.state.value)
        assertFalse("mic must not be released yet", recordingSource.released)

        // Simulates the timeout/late-Ready race (CR-01): the caller decided this instance lost the
        // try-server window and abandons it AFTER Ready already opened the mic.
        backend.abandon()

        assertTrue("abandon() must release an already-opened mic", recordingSource.released)
    }
}

package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LIVE-02 (Phase 28, D-14) at the selector: a post-ready server close 1000 ends the session in
 * Idle with no handoff, no error and no fallback reason; every final the server sent before the
 * close reaches [BackendSelector.finals] BEFORE `state` reads Idle; and the selector frees its
 * session, so the next `start()` opens a fresh socket.
 *
 * Main is a [StandardTestDispatcher], so the test decides when queued main-thread work runs: the two
 * finals and the close are fired "in the same turn" and only then is the queue drained. The `state`
 * recorder is Unconfined, so it logs Idle the instant the selector publishes it, while the host's
 * `finals` collector runs on main like a real host's. The ordering assertion therefore FAILS on an
 * implementation that forwards the server's Idle first and retires the finals forwarders afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class BackendSelectorServerCloseTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class Harness(
        val main: CoroutineDispatcher,
        val selector: BackendSelector,
        val fakeWs: FakeWebSocketFactory,
        val fakeHandle: FakeRecognizerHandle,
        val servers: List<ServerBackend>,
        /** ONE ordered log both the finals and the state recorders append to. */
        val log: MutableList<String>,
        val errors: MutableList<SttError>,
        val finals: MutableList<FinalSegment>,
        /** For each Idle the state recorder saw: were all of [watchedFinalsJobs] complete then? */
        val finalsDoneAtIdle: MutableList<Boolean>,
    ) {
        var watchedFinalsJobs: List<Job> = emptyList()
    }

    private fun TestScope.harness(sessionLog: SessionLog = SessionLog()): Harness {
        val main = StandardTestDispatcher(testScheduler)
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val servers = mutableListOf<ServerBackend>()
        val selector = BackendSelector(
            appContext = context(),
            config = SttConfig(serverUrl = SERVER_URL),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            log = sessionLog,
            webSocketFactory = fakeWs,
            mainDispatcher = main,
            tryServerTimeoutMs = WINDOW_MS,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = fakeWs,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = main,
                    // Parks: a live, silent mic — the pump never ends unasked.
                    pcmSourceFactory = finitePcmSourceFactory(frames = 0),
                ).also { servers += it }
            },
        )
        val log = mutableListOf<String>()
        val errors = mutableListOf<SttError>()
        val finals = mutableListOf<FinalSegment>()
        val finalsDoneAtIdle = mutableListOf<Boolean>()
        val h = Harness(main, selector, fakeWs, fakeHandle, servers, log, errors, finals, finalsDoneAtIdle)
        backgroundScope.launch(main) {
            selector.finals.collect {
                finals += it
                log += "final:${it.text}"
            }
        }
        backgroundScope.launch(main) { selector.errors.collect { errors += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            selector.state.collect { next ->
                if (next == EngineState.Idle) finalsDoneAtIdle += h.watchedFinalsJobs.all { it.isCompleted }
                log += "state:$next"
            }
        }
        return h
    }

    /** Starts the selector and commits it to the server on socket [index]. */
    private fun TestScope.commitServer(h: Harness, index: Int) {
        launch(h.main) { h.selector.start() }
        runCurrent()
        assertEquals(index + 1, h.fakeWs.createCount)
        h.fakeWs.fireOpen()
        h.fakeWs.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertEquals(Backend.Server, h.selector.backend.value)
        assertEquals(EngineState.Listening, h.selector.state.value)
        // The recorder also saw the selector's INITIAL Idle (a StateFlow replays its value); only
        // what happens after this session went live is under test.
        h.log.clear()
        h.finalsDoneAtIdle.clear()
    }

    @Test
    fun `an idle close after two finals delivers both before Idle, hands off nothing, and the next start opens a new socket`() =
        runTest {
            val h = harness()
            commitServer(h, index = 0)
            h.watchedFinalsJobs = h.selector.finalsForwarderJobs
            assertEquals(1, h.watchedFinalsJobs.size)

            // Same turn: two finals, then the server's idle close. Nothing on main has run yet.
            h.fakeWs.fireMessage("""{"type":"final","text":"one","segmentId":1}""")
            h.fakeWs.fireMessage("""{"type":"final","text":"two","segmentId":2}""")
            h.fakeWs.fireClosing(1000, ServerBackend.IDLE_TIMEOUT_CLOSE_REASON)
            runCurrent()

            val idleAt = h.log.indexOf("state:${EngineState.Idle}")
            assertTrue("state must reach Idle: ${h.log}", idleAt >= 0)
            assertTrue(
                "both finals must reach finals BEFORE state reads Idle: ${h.log}",
                h.log.indexOf("final:one") in 0 until idleAt && h.log.indexOf("final:two") in 0 until idleAt,
            )
            assertEquals(
                "every finals forwarder must have completed when Idle was published",
                listOf(true),
                h.finalsDoneAtIdle,
            )
            assertEquals(listOf("one", "two"), h.finals.map { it.text })
            assertEquals("a server close is not a drop: no native start", 0, h.fakeHandle.startCount)
            assertTrue("a graceful close is not an error: ${h.errors}", h.errors.isEmpty())
            assertEquals(Backend.Server, h.selector.backend.value)
            assertNull(h.selector.fallbackReason.value)
            assertTrue("the idle close is recorded", h.servers[0].closedByIdleTimeout)
            assertEquals(1, h.fakeWs.sockets[0].closeCount)

            // The selector freed its session: a second start() opens a NEW socket and listens.
            commitServer(h, index = 1)
            assertEquals(2, h.fakeWs.createCount)
        }

    @Test
    fun `a start issued while the drain is pending is ignored, and a start after Idle works`() = runTest {
        val h = harness()
        commitServer(h, index = 0)

        h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":1}""")
        h.fakeWs.fireClosing(1000, "")
        // Issued before main has run the close or the drain: the session still owns activeServer.
        launch(h.main) { h.selector.start() }
        runCurrent()

        assertEquals("a start during the drain must be ignored", 1, h.fakeWs.createCount)
        assertEquals(EngineState.Idle, h.selector.state.value)
        assertEquals(listOf("tail"), h.finals.map { it.text })

        commitServer(h, index = 1)
        assertEquals(2, h.fakeWs.createCount)
        assertEquals(0, h.fakeHandle.startCount)
    }

    @Test
    fun `a host stop issued while the drain is pending ends Idle once, duplicates no final, and never writes over a later session`() =
        runTest {
            val h = harness()
            commitServer(h, index = 0)

            h.fakeWs.fireMessage("""{"type":"final","text":"tail","segmentId":1}""")
            h.fakeWs.fireClosing(1000, "")
            launch(h.main) { h.selector.stop() }
            runCurrent()

            assertEquals(EngineState.Idle, h.selector.state.value)
            assertEquals("the final is delivered exactly once", listOf("tail"), h.finals.map { it.text })
            assertEquals(
                "Idle is published once: ${h.log}",
                1,
                h.log.count { it == "state:${EngineState.Idle}" },
            )
            assertTrue(h.errors.isEmpty())
            assertEquals(0, h.fakeHandle.startCount)

            // A later session is never overwritten by a late Idle from the ended one.
            commitServer(h, index = 1)
            testScheduler.advanceUntilIdle()
            assertEquals(EngineState.Listening, h.selector.state.value)
            assertEquals(Backend.Server, h.selector.backend.value)
        }

    // --- IN-01 (Phase 28 review): the opt-in trace tells the idle close apart ----------------------

    private fun serverCloseLines(log: SessionLog): List<JSONObject> =
        (log.file?.readLines() ?: emptyList())
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }
            .filter { it.getString("kind") == SessionLog.KIND_SERVER_CLOSE }

    @Test
    fun `an idle close writes one server-close trace line with reason idle-timeout`() = runTest {
        val log = SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = 1L)
        val h = harness(log)
        commitServer(h, index = 0)

        h.fakeWs.fireClosing(1000, ServerBackend.IDLE_TIMEOUT_CLOSE_REASON)
        runCurrent()

        val lines = serverCloseLines(log)
        assertEquals(1, lines.size)
        assertEquals("idle-timeout", lines.single().getString("reason"))
    }

    @Test
    fun `any other graceful server close writes reason normal and never the server's text`() = runTest {
        val log = SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = 1L)
        val h = harness(log)
        commitServer(h, index = 0)

        h.fakeWs.fireClosing(1000, "some free-form server text")
        runCurrent()

        val lines = serverCloseLines(log)
        assertEquals(1, lines.size)
        assertEquals("normal", lines.single().getString("reason"))
        assertTrue(
            "the server's own reason text never reaches the trace",
            !(log.file?.readText() ?: "").contains("free-form"),
        )
    }

    @Test
    fun `a requested stop writes no server-close line`() = runTest {
        val log = SessionLog(enabled = true, directory = folder.root, sessionStartEpochMs = 1L)
        val h = harness(log)
        commitServer(h, index = 0)

        launch(h.main) { h.selector.stop() }
        runCurrent()
        h.fakeWs.fireClosing(1000, "")
        runCurrent()

        assertEquals(EngineState.Idle, h.selector.state.value)
        assertTrue(serverCloseLines(log).isEmpty())
    }

    private companion object {
        const val SERVER_URL = "wss://example.ts.net:8444/"
        const val WINDOW_MS = 5_000L
    }
}

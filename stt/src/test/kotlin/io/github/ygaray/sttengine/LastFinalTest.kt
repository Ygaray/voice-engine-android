package io.github.ygaray.sttengine

import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.FakeWebSocketFactory
import io.github.ygaray.sttengine.internal.PermissionChecker
import io.github.ygaray.sttengine.internal.RecognizerHandleFactory
import io.github.ygaray.sttengine.internal.ServerBackend
import io.github.ygaray.sttengine.internal.finitePcmSourceFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LAST-01 (D-05, D-15): [SttEngine.lastFinal] — the final the most recent `stop()` flushed, readable
 * synchronously the moment `stop()` returns, with no `finals` collector kept alive across the stop.
 *
 * The native half drives a real `SttEngineImpl` through [EngineHarness]; the server half drives a
 * real `BackendSelector` + `ServerBackend` over [FakeWebSocketFactory] (the 21-03 stop-test harness:
 * `StandardTestDispatcher` + `runCurrent()` between fires). Every "same final" assertion
 * is `assertSame`, not `assertEquals`: D-05 requires `lastFinal` to be the SAME instance `finals`
 * delivers, written at the emit-origin point, never a reconstruction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class LastFinalTest {

    // --- native (SttEngineImpl) -------------------------------------------------------------------

    @Test
    fun `native - lastFinal is null after reaching listening, before any final`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        assertNull(h.engine.lastFinal)
    }

    @Test
    fun `native - a mid-session commit is the same instance finals delivered`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("first thought")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertSame(h.finals.last(), h.engine.lastFinal)
    }

    @Test
    fun `native - immediately after stop returns, lastFinal is the tail stop flushed, with its id and language`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            // One committed segment first, so a stale "previous segment" answer would be wrong.
            h.fake.fireSegmentResults("primera parte")
            advanceUntilIdle()
            val earlier = h.engine.lastFinal

            h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
            h.fake.firePartial("y la cola abierta")
            runCurrent()

            h.engine.stop()
            // Read BEFORE any scheduler advance — this is the host's "read right after stop" race.
            val tail = h.engine.lastFinal

            assertNotNull(tail)
            assertEquals("y la cola abierta", tail!!.text)
            assertEquals("es", tail.language)
            assertEquals(earlier!!.segmentId + 1, tail.segmentId)

            advanceUntilIdle()
            assertSame("lastFinal is the very instance finals delivered", h.finals.last(), tail)
        }

    @Test
    fun `native - a new start resets lastFinal to null until that session's first final`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("previous session words")
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()
        assertNotNull(h.engine.lastFinal)

        h.reachListening()
        assertNull("a reused engine must never report a previous session's final", h.engine.lastFinal)

        h.fake.firePartial("new session words")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()

        assertEquals("new session words", h.engine.lastFinal?.text)
        assertSame(h.finals.last(), h.engine.lastFinal)
    }

    @Test
    fun `native - a terminal error does not clear lastFinal`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("kept across the failure")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()
        val committed = h.engine.lastFinal
        assertNotNull(committed)

        h.fake.fireError(ERROR_LANGUAGE_NOT_SUPPORTED)
        advanceUntilIdle()

        assertEquals(EngineState.Error, h.engine.state.value)
        assertSame(committed, h.engine.lastFinal)
    }

    // --- server / selector (BackendSelector + ServerBackend) ----------------------------------------

    @Test
    fun `server - lastFinal is null on a committed server session before any final`() = runTest {
        val s = committedServerSession(StandardTestDispatcher(testScheduler))

        assertNull(s.selector.lastFinal)
    }

    @Test
    fun `server - read right after stop returns, lastFinal is the tail the server flushed, not the previous segment`() =
        runTest {
            val s = committedServerSession(StandardTestDispatcher(testScheduler))
            s.ws.fireMessage("""{"type":"final","text":"one","segmentId":3,"language":"en"}""")
            runCurrent()
            s.ws.fireMessage("""{"type":"partial","text":"ta","segmentId":4}""")
            runCurrent()

            // The host's exact shape: call stop(), and read lastFinal the instant it returns — inside
            // the same coroutine, before the test advances the scheduler at all (Pitfall 4).
            var readAtReturn: FinalSegment? = null
            val stopJob = launch(s.main) {
                s.selector.stop()
                readAtReturn = s.selector.lastFinal
            }
            runCurrent()
            assertTrue("the stop token must be on the wire", "text:stop" in s.sendLog())
            assertFalse("stop() must still be awaiting the flush", stopJob.isCompleted)

            s.ws.fireMessage("""{"type":"final","text":"tail","segmentId":4,"language":"es"}""")
            s.ws.fireClosing(1000)
            runCurrent()

            assertTrue("stop() must have returned", stopJob.isCompleted)
            val tail = readAtReturn
            assertNotNull("lastFinal must already hold the flushed tail when stop() returns", tail)
            assertEquals("tail", tail!!.text)
            assertEquals(4, tail.segmentId)
            assertEquals("es", tail.language)

            advanceUntilIdle()
            assertEquals(FinalSegment("tail", 4, "es"), s.finals.last())
            assertSame("the same instance finals delivered", s.finals.last(), tail)
            assertSame(tail, s.selector.lastFinal)
        }

    /**
     * The D-05 discriminator. Under one FIFO dispatcher the selector's launched forwarding hop for a
     * final happens to be queued before `stop()`'s own resumption, so the test above would also pass
     * for a `lastFinal` derived from that forwarding collector. Here [ServerBackend] runs eagerly
     * while the selector's scope is queued: the flushed tail is emitted, and `lastFinal` is read
     * while the forwarding hop is provably still PENDING (the host's `finals` has not seen it). Only
     * an emit-origin write can answer "tail" at that instant.
     */
    @Test
    fun `server - lastFinal never waits on the selector's async forwarding (D-05)`() = runTest {
        val s = committedServerSession(
            main = StandardTestDispatcher(testScheduler),
            backendMain = UnconfinedTestDispatcher(testScheduler),
        )
        s.ws.fireMessage("""{"type":"final","text":"one","segmentId":3,"language":"en"}""")
        runCurrent()
        assertEquals("one", s.selector.lastFinal?.text)
        s.ws.fireMessage("""{"type":"partial","text":"ta","segmentId":4}""")
        runCurrent()

        val stopJob = launch(s.main) { s.selector.stop() }
        runCurrent()
        assertTrue("the stop token must be on the wire", "text:stop" in s.sendLog())

        s.ws.fireMessage("""{"type":"final","text":"tail","segmentId":4,"language":"es"}""")
        // NO runCurrent(): the selector's forwarding hop has not run yet.
        assertFalse("precondition: forwarding still pending", s.finals.any { it.text == "tail" })
        assertEquals("tail", s.selector.lastFinal?.text)
        assertEquals(4, s.selector.lastFinal?.segmentId)

        s.ws.fireClosing(1000)
        advanceUntilIdle()
        assertTrue(stopJob.isCompleted)
        assertSame(s.finals.last(), s.selector.lastFinal)
    }

    @Test
    fun `server - on a flush timeout lastFinal is the locally committed partial with its own segmentId`() =
        runTest {
            val s = committedServerSession(StandardTestDispatcher(testScheduler))
            s.ws.fireMessage("""{"type":"partial","text":"open","segmentId":9}""")
            runCurrent()

            var readAtReturn: FinalSegment? = null
            val stopJob = launch(s.main) {
                s.selector.stop()
                readAtReturn = s.selector.lastFinal
            }
            runCurrent()
            assertFalse(stopJob.isCompleted)

            // No reply at all: the ~2.5 s bound commits the open partial locally.
            advanceTimeBy(ServerBackend.STOP_FLUSH_TIMEOUT_MS)
            runCurrent()

            assertTrue("stop() must have returned at the bound", stopJob.isCompleted)
            assertEquals(FinalSegment("open", 9), readAtReturn)
            advanceUntilIdle()
            assertSame(s.finals.last(), readAtReturn)
        }

    @Test
    fun `handoff - lastFinal is the handoff commit until native commits, then native's own segment`() = runTest {
        val s = committedServerSession(UnconfinedTestDispatcher(testScheduler))
        s.ws.fireMessage("""{"type":"partial","text":"spoken before the drop","segmentId":2}""")
        advanceUntilIdle()

        s.ws.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        s.handle.reachListening()
        advanceUntilIdle()
        assertEquals(Backend.Native, s.selector.backend.value)

        val handoff = s.selector.lastFinal
        assertNotNull(handoff)
        assertEquals("spoken before the drop", handoff!!.text)
        assertEquals(0, handoff.segmentId)
        assertSame(s.finals.last(), handoff)

        s.handle.fireSegmentResults("native carries on")
        advanceUntilIdle()

        assertEquals("native carries on", s.selector.lastFinal?.text)
        assertSame(s.finals.last(), s.selector.lastFinal)
    }

    @Test
    fun `reuse - a second start on the same selector resets lastFinal to null until that session's first final`() =
        runTest {
            val s = committedServerSession(StandardTestDispatcher(testScheduler))
            s.ws.fireMessage("""{"type":"final","text":"previous session","segmentId":1}""")
            runCurrent()
            val stopJob = launch(s.main) { s.selector.stop() }
            runCurrent()
            s.ws.fireClosing(1000)
            runCurrent()
            assertTrue(stopJob.isCompleted)
            assertEquals("previous session", s.selector.lastFinal?.text)

            reachServerReady(s)
            assertNull("a reused engine must never report a previous session's final", s.selector.lastFinal)

            s.ws.fireMessage("""{"type":"final","text":"second session","segmentId":1}""")
            runCurrent()
            assertEquals("second session", s.selector.lastFinal?.text)
        }

    @Test
    fun `reuse - after a handoff session, a new start clears the handoff commit and the old native`() = runTest {
        val s = committedServerSession(UnconfinedTestDispatcher(testScheduler))
        s.ws.fireMessage("""{"type":"partial","text":"handoff words","segmentId":2}""")
        advanceUntilIdle()
        s.ws.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        s.handle.reachListening()
        advanceUntilIdle()
        s.handle.fireSegmentResults("old native words")
        advanceUntilIdle()
        s.selector.stop()
        advanceUntilIdle()
        assertNotNull(s.selector.lastFinal)

        reachServerReady(s)
        assertEquals(Backend.Server, s.selector.backend.value)
        assertNull("neither the old native's nor the handoff's final may survive start()", s.selector.lastFinal)
    }

    // --- helpers ------------------------------------------------------------------------------------

    private class SelectorSession(
        val main: CoroutineDispatcher,
        val ws: FakeWebSocketFactory,
        val handle: FakeRecognizerHandle,
        val selector: BackendSelector,
        val finals: MutableList<FinalSegment>,
    ) {
        fun sendLog(): List<String> {
            val socket = checkNotNull(ws.lastSocket)
            return synchronized(socket.sendLog) { socket.sendLog.toList() }
        }
    }

    /**
     * A [BackendSelector] committed to the server path (`ready` reached), with ONE `finals` collector
     * (single-consumer). The committed session's pump ends at once (`finitePcmSourceFactory(0)`) so
     * nothing spins on the Robolectric-shadowed `AudioRecord` — the 21-03 stop-test harness.
     */
    private fun TestScope.committedServerSession(
        main: CoroutineDispatcher,
        backendMain: CoroutineDispatcher = main,
    ): SelectorSession {
        val ws = FakeWebSocketFactory()
        val handle = FakeRecognizerHandle()
        val selector = BackendSelector(
            appContext = ApplicationProvider.getApplicationContext(),
            config = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
            handleFactory = RecognizerHandleFactory { PreflightHandle(handle, LanguageAvailability.INSTALLED) },
            permissions = PermissionChecker { true },
            webSocketFactory = ws,
            mainDispatcher = main,
            serverBackendFactory = { url ->
                ServerBackend(
                    webSocketFactory = ws,
                    serverUrl = url,
                    language = "en",
                    mainDispatcher = backendMain,
                    pcmSourceFactory = finitePcmSourceFactory(frames = 0),
                )
            },
        )
        val finals = mutableListOf<FinalSegment>()
        backgroundScope.launch(main) { selector.finals.collect { finals += it } }
        val s = SelectorSession(main, ws, handle, selector, finals)
        reachServerReady(s)
        return s
    }

    /** `selector.start()` → open → ready on the newest socket; asserts start() has returned. */
    private fun TestScope.reachServerReady(s: SelectorSession) {
        val started: Job = launch(s.main) { s.selector.start() }
        runCurrent()
        s.ws.fireOpen()
        runCurrent()
        s.ws.fireMessage("""{"type":"ready"}""")
        runCurrent()
        assertTrue("start() must have returned once ready committed the server", started.isCompleted)
        assertEquals(Backend.Server, s.selector.backend.value)
    }

    private fun highlyConfidentDetection(detectedLanguage: String): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT,
        )
    }

    private companion object {
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    }
}

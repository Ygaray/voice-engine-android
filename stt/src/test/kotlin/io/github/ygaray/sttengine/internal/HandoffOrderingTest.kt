package io.github.ygaray.sttengine.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.corrections.CorrectionPair
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SEL-04's structural proof: the mid-session server→native handoff's release-then-acquire mic
 * ordering, one-way-to-native semantics, exactly-once partial commit, and the invisible-on-the-
 * shared-contract state transition (D-03) — proven by a real ordered call log and real state/final
 * collection, not by code inspection alone.
 *
 * Mirrors [RestartBoundaryTest]'s one-test-per-direction discipline: a reversed ordering fails
 * exactly one of these tests, never silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class HandoffOrderingTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /**
     * A [PcmSource] that records into a log SHARED with [FakeRecognizerHandle.calls] — the
     * translation of "one ordered call log" onto two otherwise-unrelated fakes, so the handoff's
     * release-then-acquire ordering is provable as a single timeline rather than two separate ones.
     */
    private class RecordingPcmSource(private val log: MutableList<String>) : PcmSource {
        private var released = false

        override fun read(buffer: ByteArray): Int = 0

        override fun stop() {
            log += "server.audioSource.stop"
        }

        override fun release() {
            if (released) return
            released = true
            log += "server.audioSource.release"
        }
    }

    private fun buildSelector(
        fakeWs: FakeWebSocketFactory,
        fakeHandle: FakeRecognizerHandle,
        dispatcher: CoroutineDispatcher,
        sharedLog: MutableList<String>,
        config: SttConfig = SttConfig(serverUrl = "wss://example.ts.net:8444/"),
    ): BackendSelector = BackendSelector(
        appContext = context(),
        config = config,
        handleFactory = RecognizerHandleFactory {
            PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
        },
        permissions = PermissionChecker { true },
        webSocketFactory = fakeWs,
        mainDispatcher = dispatcher,
        serverBackendFactory = { url ->
            ServerBackend(
                webSocketFactory = fakeWs,
                serverUrl = url,
                language = "en",
                mainDispatcher = dispatcher,
                pcmSourceFactory = PcmSourceFactory { RecordingPcmSource(sharedLog) },
            )
        },
    )

    @Test
    fun `the native startListening call is recorded strictly after the server path's mic-release call`() =
        runTest {
            val fakeWs = FakeWebSocketFactory()
            val fakeHandle = FakeRecognizerHandle()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            // fakeHandle.calls IS the shared ordered log: both the recording PCM source and the
            // native fake append into the SAME list object.
            val log = fakeHandle.calls
            val selector = buildSelector(fakeWs, fakeHandle, dispatcher, log)

            val started = launch(dispatcher) { selector.start() }
            fakeWs.fireOpen()
            fakeWs.fireMessage("""{"type":"ready"}""")
            started.join()
            assertEquals(Backend.Server, selector.backend.value)

            // Mid-session server drop.
            fakeWs.fireFailure(IOException("connection reset"))
            advanceUntilIdle()
            fakeHandle.reachListening()
            advanceUntilIdle()

            val releaseIndex = log.indexOf("server.audioSource.release")
            val startListeningIndex = log.indexOf("startListening")
            assertTrue("expected a recorded mic-release call in $log", releaseIndex >= 0)
            assertTrue("expected a recorded native startListening call in $log", startListeningIndex >= 0)
            assertTrue(
                "release-then-acquire violated: startListening ($startListeningIndex) must come " +
                    "strictly after the mic release ($releaseIndex) — full log: $log",
                startListeningIndex > releaseIndex,
            )
            assertEquals(Backend.Native, selector.backend.value)
            assertEquals(EngineState.Listening, selector.state.value)
        }

    @Test
    fun `a partial spoken immediately before the drop is committed as exactly one final`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val selector = buildSelector(fakeWs, fakeHandle, dispatcher, fakeHandle.calls)
        val finals = mutableListOf<FinalSegment>()
        backgroundScope.launch(dispatcher) { selector.finals.collect { finals += it } }

        val started = launch(dispatcher) { selector.start() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        fakeWs.fireMessage("""{"type":"partial","text":"the morning light","segmentId":0}""")
        advanceUntilIdle()
        assertTrue("nothing is committed until the drop", finals.isEmpty())

        fakeWs.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        fakeHandle.reachListening()
        advanceUntilIdle()

        assertEquals(listOf(FinalSegment("the morning light", 0)), finals.toList())
    }

    @Test
    fun `state never transitions to Reconnecting anywhere during the handoff sequence`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val selector = buildSelector(fakeWs, fakeHandle, dispatcher, fakeHandle.calls)
        val states = mutableListOf<EngineState>()
        backgroundScope.launch(dispatcher) { selector.state.collect { states += it } }

        val started = launch(dispatcher) { selector.start() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        fakeWs.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        fakeHandle.reachListening()
        advanceUntilIdle()

        assertFalse(
            "state must never observe Reconnecting during the handoff: $states",
            states.contains(EngineState.Reconnecting),
        )
        assertFalse(
            "state must never observe Error during the handoff either — the drop stays invisible: $states",
            states.contains(EngineState.Error),
        )
        assertEquals(EngineState.Listening, selector.state.value)
    }

    @Test
    fun `a second server failure arriving after the handoff already completed is a no-op`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val selector = buildSelector(fakeWs, fakeHandle, dispatcher, fakeHandle.calls)

        val started = launch(dispatcher) { selector.start() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        fakeWs.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        fakeHandle.reachListening()
        advanceUntilIdle()
        assertEquals(1, fakeHandle.startCount)

        // A stray second failure on the same (already-abandoned) socket — the epoch fence and
        // ServerBackend's own terminal guard reject it before it can reach BackendSelector at all;
        // BackendSelector's identity + one-shot guard is the second, independent line of defence.
        fakeWs.fireFailure(IOException("stray late failure"))
        advanceUntilIdle()

        assertEquals(
            "a second server drop must never trigger a second native start — one-way, D-03",
            1,
            fakeHandle.startCount,
        )
        assertEquals(Backend.Native, selector.backend.value)
        assertEquals(EngineState.Listening, selector.state.value)
    }

    // --- Phase 13-02: the handoff-commit applies the configured correction ruleset -----------------

    @Test
    fun `the synthetic handoff final is corrected when a ruleset is configured`() = runTest {
        val fakeWs = FakeWebSocketFactory()
        val fakeHandle = FakeRecognizerHandle()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = SttConfig(
            serverUrl = "wss://example.ts.net:8444/",
            corrections = listOf(CorrectionPair(wrong = "kubernetes", right = "Kubernetes")),
        )
        val selector = buildSelector(fakeWs, fakeHandle, dispatcher, fakeHandle.calls, config)
        val finals = mutableListOf<FinalSegment>()
        backgroundScope.launch(dispatcher) { selector.finals.collect { finals += it } }

        val started = launch(dispatcher) { selector.start() }
        fakeWs.fireOpen()
        fakeWs.fireMessage("""{"type":"ready"}""")
        started.join()

        fakeWs.fireMessage("""{"type":"partial","text":"deploy kubernetes now","segmentId":0}""")
        advanceUntilIdle()

        fakeWs.fireFailure(IOException("connection reset"))
        advanceUntilIdle()
        fakeHandle.reachListening()
        advanceUntilIdle()

        assertEquals(listOf(FinalSegment("deploy Kubernetes now", 0)), finals.toList())
    }
}

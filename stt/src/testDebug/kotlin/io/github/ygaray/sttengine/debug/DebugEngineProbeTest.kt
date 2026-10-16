package io.github.ygaray.sttengine.debug

import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.EngineHarness
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.PreflightHandle
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.SttEngine
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.DebugRungCeilingHolder
import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.FakeWebSocketFactory
import io.github.ygaray.sttengine.internal.PermissionChecker
import io.github.ygaray.sttengine.internal.RecognizerHandleFactory
import io.github.ygaray.sttengine.internal.Rung
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DebugEngineProbe] reads the live engine's rung and restart count, with no trace file and no text.
 *
 * ## Why this exists, and why a file-fed run cannot stand in for it
 *
 * The PAUSE-03 real-microphone runs keep the session trace OFF (it records the user's dictated
 * speech), so the demo's rung and restart readouts, which are derived from the trace file, are null.
 * A file-fed confirmation cannot fill that gap: `AudioSourcePipe` swaps its source for the armed
 * file, so a file-fed run says nothing about whether a real `AudioRecord` opened. The test
 * `a pipe that cannot be opened degrades from rung c to rung b` is exactly that blind spot: the same
 * engine, with a real microphone failure, ends on rung (b) inside the same `start()`, with no error
 * and no state change. Only a reader of the live engine sees it.
 *
 * Every driven scenario uses a real [io.github.ygaray.sttengine.internal.SttEngineImpl] through
 * [EngineHarness], so the probe is proven against the engine's real field semantics, not a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class DebugEngineProbeTest {

    @After
    fun resetCeiling() {
        DebugRungCeilingHolder.ceiling = null
    }

    @Test
    fun `before start the rung and restart count are unavailable, not the field default`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)

        val snap = DebugEngineProbe.snapshot(h.engine)

        assertEquals("native", snap.kind)
        assertNull("the pre-start field holds the floor rung, which is not a measurement", snap.rung)
        assertNull(snap.restartCount)
        assertFalse(snap.listening)
    }

    @Test
    fun `a listening engine with a working pipe reports rung c and zero restarts`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        val snap = DebugEngineProbe.snapshot(h.engine)

        assertEquals("native", snap.kind)
        assertEquals(Rung.RawAudioSegmented.name, snap.rung)
        assertEquals(0, snap.restartCount)
        assertTrue(snap.listening)
    }

    @Test
    fun `a pipe that cannot be opened degrades from rung c to rung b and the probe sees it`() =
        runTest {
            // The live-path failure a file-fed run cannot reveal: a real AudioRecord that cannot open
            // is a degrade inside the same start(), with no error and no extra session. The file feed
            // replaces the microphone source, so it would have "opened" fine.
            val h = EngineHarness(
                testScheduler,
                supportsSegmented = true,
                supportsRawAudio = true,
                audioPipeOpenFails = true,
            )
            h.collectInto(backgroundScope)
            h.reachListening()

            val snap = DebugEngineProbe.snapshot(h.engine)

            assertEquals(Rung.SilenceSegmented.name, snap.rung)
            assertEquals(0, snap.restartCount)
            assertTrue(snap.listening)
            assertTrue("the degrade must be silent to the host", h.errors.isEmpty())
            assertEquals(1, h.fake.startCount)
        }

    @Test
    fun `a forced ceiling of rung b is reported as rung b`() = runTest {
        DebugRungCeilingHolder.ceiling = Rung.SilenceSegmented
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        assertEquals(Rung.SilenceSegmented.name, DebugEngineProbe.snapshot(h.engine).rung)
    }

    @Test
    fun `a forced ceiling of the floor is reported as the floor`() = runTest {
        DebugRungCeilingHolder.ceiling = Rung.RestartingSingleSession
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        assertEquals(Rung.RestartingSingleSession.name, DebugEngineProbe.snapshot(h.engine).rung)
    }

    @Test
    fun `a silent segmented session restart is counted with no error and no state change`() =
        runTest {
            val h = EngineHarness(testScheduler, supportsSegmented = true)
            h.collectInto(backgroundScope)
            h.reachListening()
            assertEquals(0, DebugEngineProbe.snapshot(h.engine).restartCount)

            // A working segmented session: results arrive, then the platform ends the session.
            h.fake.fireSegmentResults("the morning light")
            h.fake.fireEndOfSegmentedSession()
            advanceUntilIdle()
            h.fake.reachListening()
            advanceUntilIdle()

            val afterOne = DebugEngineProbe.snapshot(h.engine)
            assertEquals(1, afterOne.restartCount)
            assertEquals(Rung.SilenceSegmented.name, afterOne.rung)
            assertTrue("the silent restart must not surface as an error", h.errors.isEmpty())
            assertTrue(
                "the host never saw a reconnect or error state: that is what makes it silent",
                h.states.none { it == EngineState.Reconnecting || it == EngineState.Error },
            )

            h.fake.fireSegmentResults("came through the kitchen window")
            h.fake.fireEndOfSegmentedSession()
            advanceUntilIdle()
            h.fake.reachListening()
            advanceUntilIdle()

            assertEquals(2, DebugEngineProbe.snapshot(h.engine).restartCount)
        }

    @Test
    fun `a fresh start after stop resets the restart count to zero`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()
        h.fake.fireSegmentResults("the morning light")
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()
        h.fake.reachListening()
        advanceUntilIdle()
        assertEquals(1, DebugEngineProbe.snapshot(h.engine).restartCount)

        h.engine.stop()
        advanceUntilIdle()
        h.reachListening()

        assertEquals(0, DebugEngineProbe.snapshot(h.engine).restartCount)
    }

    @Test
    fun `after stop the rung and restart count are unavailable`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.engine.stop()
        advanceUntilIdle()

        val snap = DebugEngineProbe.snapshot(h.engine)
        assertEquals("native", snap.kind)
        assertNull(snap.rung)
        assertNull(snap.restartCount)
        assertFalse(snap.listening)
    }

    @Test
    fun `a backend selector reports kind selector with nothing else available`() = runTest {
        val selector = selector(this)

        val snap = DebugEngineProbe.snapshot(selector)

        assertEquals("selector", snap.kind)
        assertNull(snap.rung)
        assertNull(snap.restartCount)
        assertFalse(snap.listening)
    }

    @Test
    fun `a foreign engine reports kind unsupported without throwing`() {
        val foreign = Proxy.newProxyInstance(
            SttEngine::class.java.classLoader,
            arrayOf(SttEngine::class.java),
        ) { _, _, _ -> null } as SttEngine

        val snap = DebugEngineProbe.snapshot(foreign)

        assertEquals("unsupported", snap.kind)
        assertNull(snap.rung)
        assertNull(snap.restartCount)
        assertFalse(snap.listening)
    }

    @Test
    fun `the snapshot carries no transcript text`() = runTest {
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()
        h.fake.firePartial("secret words")
        runCurrent()

        val snap = DebugEngineProbe.snapshot(h.engine)

        val everything = listOf(snap.kind, snap.rung, snap.restartCount, snap.listening, snap.toString())
            .joinToString(" ")
        assertFalse("secret" in everything || "words" in everything)
        val stringFields = EngineProbeSnapshot::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
            .toSet()
        assertEquals(
            "the only String fields a snapshot may carry are the engine kind and the rung name",
            setOf("kind", "rung"),
            stringFields,
        )
    }

    private fun selector(scope: TestScope): BackendSelector {
        val fakeHandle = FakeRecognizerHandle()
        return BackendSelector(
            appContext = ApplicationProvider.getApplicationContext(),
            config = SttConfig(),
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(fakeHandle, LanguageAvailability.INSTALLED)
            },
            permissions = PermissionChecker { true },
            webSocketFactory = FakeWebSocketFactory(),
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }
}

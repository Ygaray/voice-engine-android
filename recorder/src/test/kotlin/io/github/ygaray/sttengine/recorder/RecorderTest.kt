package io.github.ygaray.sttengine.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.recorder.internal.RecorderImpl
import io.github.ygaray.sttengine.recorder.internal.RecorderSession
import io.github.ygaray.sttengine.recorder.internal.RecorderSessionFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The standalone Android Recorder primitive (REC-01/02/03/04/06/07), constructed DIRECTLY with an
 * injected [FakeRecorderSessionFactory] — mirroring `AudioSourcePipeTest`'s convention of testing
 * the internal class rather than through the public [createRecorder] factory.
 *
 * ## What each test rules out (grows across Tasks 1-3)
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | createRecorder constructs without throwing | The public factory wiring is broken |
 * | start then stop returns a fully populated RecordedFile | `stop()` returns a partial/empty result |
 * | delete removes the file at the returned uri, idempotent on repeat | `delete()` misses the exact path, or throws on a repeat/nonexistent target (REC-03) |
 * | a second start while Recording pushes ALREADY_RECORDING | A concurrent `start()` silently opens two sessions (edge-probe REC-01 concurrency) |
 * | pause then resume calls the same session's pause/resume exactly once each | Pause/resume opens a second session instead of suspending the one it has (REC-07) |
 * | durationMs excludes the wall-clock time spent paused | Paused wall-clock time is charged to the recording (REC-07) |
 * | resume while idle / pause while already paused both push INVALID_STATE | An out-of-order call corrupts session/timing state instead of erroring (edge-probe REC-07 concurrency) |
 * | delete on the live recording's own uri is rejected | `delete()` races a still-open session's file (edge-probe REC-03 concurrency) |
 * | the level flow ticks only while Recording, normalized from maxAmplitude | The meter free-runs before `start()`/while `Idle`, or leaks ticks while `Paused` (REC-06) |
 * | a RuntimeException from open maps to MIC_DENIED | A mic-denial crashes instead of surfacing a typed error (REC-02) |
 * | an IOException from open maps to NO_STORAGE | A disk-full/permission failure crashes instead of surfacing a typed error (REC-02) |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- Task 1: end-to-end round trip + full public API ----------------------------------------

    @Test
    fun `createRecorder constructs a real recorder without throwing`() {
        assertNotNull(createRecorder(context))
    }

    @Test
    fun `start then stop returns a fully populated RecordedFile`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()
        val recorded = recorder.stop()

        assertTrue("uri must be populated", recorded.uri.isNotBlank())
        assertTrue("durationMs must be non-negative", recorded.durationMs >= 0)
        assertEquals("audio/mp4", recorded.format)
        assertTrue("createdAt must be populated", recorded.createdAt.isNotBlank())
        assertEquals("exactly one session must have been opened", 1, factory.openCallCount)
    }

    @Test
    fun `delete removes the file at the returned uri and is idempotent on a second call`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()
        val recorded = recorder.stop()

        // The fake session never writes real bytes, so simulate what a real MediaRecorder would
        // have produced at the exact internally-generated path before exercising delete().
        val file = File(recorded.uri)
        file.parentFile?.mkdirs()
        file.writeText("fake m4a bytes")
        assertTrue("expected the fixture file to exist before delete()", file.exists())

        recorder.delete(recorded.uri)
        assertFalse("delete() must remove the file at the exact recorded uri", file.exists())

        // Idempotent: a second delete() on an already-deleted uri must not throw.
        recorder.delete(recorded.uri)
    }

    @Test
    fun `a second start while already recording pushes ALREADY_RECORDING and does not open a second session`() =
        runTest {
            val factory = FakeRecorderSessionFactory()
            val recorder = RecorderImpl(context, factory)

            recorder.start()
            assertEquals(1, factory.openCallCount)

            recorder.start()

            val error = recorder.errors.first()
            assertEquals(RecorderErrorCode.ALREADY_RECORDING, error.code)
            assertEquals(
                "a second start() while Recording must not open a second session",
                1,
                factory.openCallCount,
            )
        }

    // --- Task 2: pause/resume + concurrency/idempotency guards (REC-07) -------------------------

    @Test
    fun `pause then resume calls the same session's pause and resume exactly once each`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()
        recorder.pause()
        recorder.resume()
        recorder.stop()

        val session = factory.openedSessions.single()
        assertEquals(1, session.pauseCalls)
        assertEquals(1, session.resumeCalls)
        assertEquals("no additional session may be opened by pause/resume", 1, factory.openCallCount)
    }

    @Test
    fun `durationMs excludes the wall-clock time spent paused`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()
        Thread.sleep(20)
        recorder.pause()
        Thread.sleep(200)
        recorder.resume()
        Thread.sleep(20)
        val recorded = recorder.stop()

        assertTrue(
            "durationMs (${recorded.durationMs}) must be measurably less than the ~240ms raw " +
                "wall-clock elapsed, since ~200ms of that was spent Paused",
            recorded.durationMs < 150,
        )
    }

    @Test
    fun `resume while idle and pause while already paused both push INVALID_STATE`() = runTest {
        val idleFactory = FakeRecorderSessionFactory()
        val idleRecorder = RecorderImpl(context, idleFactory)

        idleRecorder.resume()
        val idleError = idleRecorder.errors.first()
        assertEquals(RecorderErrorCode.INVALID_STATE, idleError.code)

        val recordingFactory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, recordingFactory)
        recorder.start()
        recorder.pause()

        recorder.pause() // already Paused — out of order

        val pausedError = recorder.errors.first()
        assertEquals(RecorderErrorCode.INVALID_STATE, pausedError.code)

        val session = recordingFactory.openedSessions.single()
        assertEquals("the out-of-order pause() must not touch the session again", 1, session.pauseCalls)
    }

    @Test
    fun `delete on the live recording's own uri is rejected and does not delete the file`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()
        val liveUri = requireNotNull(recorder.currentUriForTest())
        val file = File(liveUri)
        file.parentFile?.mkdirs()
        file.writeText("still being written")

        recorder.delete(liveUri)

        val error = recorder.errors.first()
        assertEquals(RecorderErrorCode.INVALID_STATE, error.code)
        assertTrue("delete() must not remove a file a session is still writing to", file.exists())

        recorder.stop()
    }

    // --- Task 3: amplitude polling + typed error mapping (REC-02, REC-06) -----------------------

    @Test
    fun `the level flow ticks only while recording, normalized from maxAmplitude`() = runBlocking {
        val session = FakeRecorderSession()
        session.queueAmplitudes(16383, 32767, 3276, 32767, 16383, 3276, 32767, 16383)
        val factory = FakeRecorderSessionFactory { session }
        val recorder = RecorderImpl(context, factory)

        val ticks = mutableListOf<Float>()
        val collectJob = launch { recorder.level.collect { ticks.add(it) } }

        assertTrue("no ticks before start()", ticks.isEmpty())

        recorder.start()
        delay(400) // real time — several ~150ms poll cycles

        recorder.pause()
        val countWhilePaused = ticks.size
        delay(400)
        assertEquals("no new ticks while Paused", countWhilePaused, ticks.size)

        recorder.stop()
        collectJob.cancel()

        assertTrue("expected at least one normalized tick while Recording", ticks.isNotEmpty())
        for (tick in ticks) {
            assertTrue("tick $tick must be in [0,1]", tick in 0f..1f)
        }
    }

    @Test
    fun `a RuntimeException from open maps to MIC_DENIED and pushes Error state`() = runTest {
        val factory = FakeRecorderSessionFactory { throw RuntimeException("mic busy") }
        val recorder = RecorderImpl(context, factory)

        recorder.start()

        val error = recorder.errors.first()
        assertEquals(RecorderErrorCode.MIC_DENIED, error.code)
        assertEquals(RecorderState.Error, recorder.state.value)
    }

    @Test
    fun `an IOException from open maps to NO_STORAGE and pushes Error state`() = runTest {
        val factory = FakeRecorderSessionFactory { throw IOException("disk full") }
        val recorder = RecorderImpl(context, factory)

        recorder.start()

        val error = recorder.errors.first()
        assertEquals(RecorderErrorCode.NO_STORAGE, error.code)
        assertEquals(RecorderState.Error, recorder.state.value)
    }

    // --- fakes ------------------------------------------------------------------------------------

    /**
     * A counting [RecorderSession] fake. Never touches real audio hardware or disk — resource
     * discipline and behavior are asserted via the counters/queue below, mirroring
     * `AudioSourcePipeTest`'s `CountingPcmSource` convention.
     */
    private open class FakeRecorderSession : RecorderSession {
        var pauseCalls = 0
            private set
        var resumeCalls = 0
            private set
        var stopCalls = 0
            private set
        var releaseCalls = 0
            private set
        private val amplitudeQueue: ArrayDeque<Int> = ArrayDeque()

        fun queueAmplitudes(vararg values: Int) {
            amplitudeQueue.addAll(values.toList())
        }

        override fun pause() {
            pauseCalls++
        }

        override fun resume() {
            resumeCalls++
        }

        override fun stop() {
            stopCalls++
        }

        override fun release() {
            releaseCalls++
        }

        override fun maxAmplitude(): Int = amplitudeQueue.removeFirstOrNull() ?: 0
    }

    /** Opens a fresh [FakeRecorderSession] per call by default; [opener] can throw to simulate REC-02. */
    private class FakeRecorderSessionFactory(
        private val opener: () -> RecorderSession = { FakeRecorderSession() },
    ) : RecorderSessionFactory {
        var openCallCount = 0
            private set
        val openedSessions = mutableListOf<FakeRecorderSession>()

        override fun open(context: Context, outputFile: File): RecorderSession {
            openCallCount++
            val session = opener()
            if (session is FakeRecorderSession) openedSessions.add(session)
            return session
        }
    }
}

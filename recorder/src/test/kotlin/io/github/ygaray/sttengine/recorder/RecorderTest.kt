package io.github.ygaray.sttengine.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.recorder.internal.AndroidMediaRecorderSession
import io.github.ygaray.sttengine.recorder.internal.RecorderImpl
import io.github.ygaray.sttengine.recorder.internal.RecorderSession
import io.github.ygaray.sttengine.recorder.internal.RecorderSessionFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
 * | level poll cadence is driven by RecorderConfig, exact counts on virtual time (Test A) | The cadence silently stays fixed at 150ms once host-configurable, or the proof itself is a flaky wall-clock race (AUDIO-02/D-03, REVIEW-OC-04) |
 * | RecorderConfig defaults reproduce tag v0.3.1 behavior (Test B) | A default RecorderConfig silently changes cadence or encoding behavior for an existing consumer (D-01) |
 * | RecorderConfig rejects non-positive/below-floor levelIntervalMs and non-positive encoding fields (Test C) | A host can configure a busy-spin cadence or a nonsensical encoding parameter (T-142-07) |
 * | stop cancels and joins the poll loop so no read follows release, even at the floor cadence (Test F) | A poll iteration reads from an already-released session (REVIEW-OLLAMA-01, T-142-13) |
 * | a failing amplitude read ends the poll loop without escaping the coroutine, and emits METERING_FAILED (Test G) | An uncaught read failure reaches the thread's default handler and crashes the host process (REVIEW-OLLAMA-01, T-142-13), or the host is left with no signal that metering silently died (WR-02) |
 * | RecorderConfig encoding fields reach the session-open call unchanged (Test D) | A host-supplied encoding parameter is lost or mutated before it reaches the session seam (AUDIO-02) |
 * | default RecorderConfig reaches the session-open call carrying three nulls (Test E) | A default config silently starts calling an encoding setter it never called before (D-01) |
 * | encoding parameters reach the real MediaRecorder, proven via ShadowMediaRecorder (Test S) | The seam hands the config to `RecorderImpl`/the factory but the platform adapter never actually calls the MediaRecorder setters (REVIEW-OC-06) |
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    // --- Task 1: RecorderConfig — level cadence, defaults, validation, poll-teardown ordering -----

    @Test
    fun `level poll cadence is driven by RecorderConfig, exact counts on virtual time`() = runTest {
        // A StandardTestDispatcher sharing runTest's own testScheduler: both RecorderImpls' poll
        // scopes advance on the SAME virtual clock this test drives with advanceTimeBy below, so
        // the assertion is deterministic rather than a wall-clock race (REVIEW-OC-04).
        val dispatcher = StandardTestDispatcher(testScheduler)

        val fastSession = FakeRecorderSession()
        val fastRecorder = RecorderImpl(
            context = context,
            sessionFactory = FakeRecorderSessionFactory { fastSession },
            config = RecorderConfig(levelIntervalMs = 50L),
            pollDispatcher = dispatcher,
        )

        val defaultSession = FakeRecorderSession()
        val defaultRecorder = RecorderImpl(
            context = context,
            sessionFactory = FakeRecorderSessionFactory { defaultSession },
            pollDispatcher = dispatcher,
        )

        fastRecorder.start()
        defaultRecorder.start()

        // 610ms is deliberately NOT a multiple of 50 or 150, so the scheduler's boundary-inclusion
        // rule (tasks scheduled exactly at the new time are not run by advanceTimeBy alone) cannot
        // shift either count — the last poll for each cadence lands comfortably before 610.
        advanceTimeBy(610)
        runCurrent()

        // Asserted on the fake's maxAmplitudeCalls counter, NOT on collected level ticks:
        // mutableLevel is a DROP_OLDEST conflated MutableSharedFlow and can legitimately drop an
        // emission, which would corrupt a tick-count assertion for a reason unrelated to cadence.
        assertEquals(
            "50ms cadence over a 610ms window must poll at exactly t=0,50,...,600",
            13,
            fastSession.maxAmplitudeCalls,
        )
        assertEquals(
            "150ms (default) cadence over a 610ms window must poll at exactly t=0,150,...,600",
            5,
            defaultSession.maxAmplitudeCalls,
        )

        // Exercises the joined teardown (Test F covers the ordering guarantee in depth).
        fastRecorder.stop()
        defaultRecorder.stop()
    }

    @Test
    fun `RecorderConfig defaults reproduce tag v0-3-1 behavior`() {
        val config = RecorderConfig()

        assertEquals(150L, config.levelIntervalMs)
        assertNull("a default config must call no sampleRate setter at all", config.sampleRateHz)
        assertNull("a default config must call no encodingBitRate setter at all", config.encodingBitRate)
        assertNull("a default config must call no channelCount setter at all", config.channelCount)
    }

    @Test
    fun `RecorderConfig rejects non-positive or below-floor levelIntervalMs and non-positive encoding fields`() {
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(levelIntervalMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(levelIntervalMs = -1L) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(levelIntervalMs = 1L) }

        // Exactly the floor constructs successfully.
        RecorderConfig(levelIntervalMs = RecorderConfig.MIN_LEVEL_INTERVAL_MS)

        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(sampleRateHz = 0) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(sampleRateHz = -1) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(encodingBitRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(encodingBitRate = -128000) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(channelCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { RecorderConfig(channelCount = -1) }
    }

    @Test
    fun `stop cancels and joins the poll loop so no read follows release, even at the floor cadence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = FakeRecorderSession()
        val recorder = RecorderImpl(
            context = context,
            sessionFactory = FakeRecorderSessionFactory { session },
            config = RecorderConfig(levelIntervalMs = RecorderConfig.MIN_LEVEL_INTERVAL_MS),
            pollDispatcher = dispatcher,
        )

        recorder.start()
        advanceTimeBy(105) // several 10ms poll cycles at the floor cadence
        runCurrent()

        recorder.stop()

        val callsAtStop = session.maxAmplitudeCalls

        // If stopPolling were fire-and-forget, a poll iteration mid-flight at stop() time could
        // still land a read here. cancelAndJoin() must have already ended it.
        advanceTimeBy(1000)
        runCurrent()

        assertEquals(
            "no amplitude read may occur after stop() has returned, at any cadence including the floor",
            callsAtStop,
            session.maxAmplitudeCalls,
        )

        val releaseIndex = session.callLog.indexOf("release")
        val lastAmplitudeIndex = session.callLog.lastIndexOf("maxAmplitude")
        assertTrue("release() must have been called by stop()", releaseIndex >= 0)
        assertTrue(
            "no maxAmplitude entry may appear at or after the release entry in the ordered call log",
            lastAmplitudeIndex < releaseIndex,
        )
    }

    @Test
    fun `a failing amplitude read ends the poll loop without escaping the coroutine`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = ThrowingAmplitudeSession()
        val recorder = RecorderImpl(
            context = context,
            sessionFactory = FakeRecorderSessionFactory { session },
            pollDispatcher = dispatcher,
        )

        recorder.start()

        // The poll loop's first iteration throws immediately. A SupervisorJob alone does not catch
        // this — reaching the assertions below without the test aborting IS the proof the guard
        // contained it rather than letting it escape to the thread's default handler.
        advanceTimeBy(200)
        runCurrent()

        // WR-02: the host must learn metering died, not just observe level going silent — a typed
        // METERING_FAILED error must land on `errors` before the loop returns.
        val error = recorder.errors.first()
        assertEquals(RecorderErrorCode.METERING_FAILED, error.code)

        // stop() must still succeed normally afterward — the failed poll loop must not have left
        // the Recorder in a broken state.
        recorder.stop()
    }

    // --- Task 2: encoding parameters reach the session-open call (AUDIO-02) -----------------------

    @Test
    fun `RecorderConfig encoding fields reach the session-open call unchanged`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val config = RecorderConfig(sampleRateHz = 44_100, encodingBitRate = 128_000, channelCount = 1)
        val recorder = RecorderImpl(context, factory, config)

        recorder.start()

        val openedConfig = requireNotNull(factory.lastOpenedConfig)
        assertEquals(44_100, openedConfig.sampleRateHz)
        assertEquals(128_000, openedConfig.encodingBitRate)
        assertEquals(1, openedConfig.channelCount)

        recorder.stop()
    }

    @Test
    fun `default RecorderConfig reaches the session-open call carrying three nulls`() = runTest {
        val factory = FakeRecorderSessionFactory()
        val recorder = RecorderImpl(context, factory)

        recorder.start()

        val openedConfig = requireNotNull(factory.lastOpenedConfig)
        assertNull("a default config must reach the session seam calling no sampleRate setter", openedConfig.sampleRateHz)
        assertNull(
            "a default config must reach the session seam calling no encodingBitRate setter",
            openedConfig.encodingBitRate,
        )
        assertNull("a default config must reach the session seam calling no channelCount setter", openedConfig.channelCount)

        recorder.stop()
    }

    @Test
    fun `encoding parameters reach the real MediaRecorder, proven via ShadowMediaRecorder (Test S)`() {
        // Robolectric 4.16.1's ShadowMediaRecorder exposes getAudioSamplingRate/
        // getAudioEncodingBitRate/getAudioChannels accessors (probed directly against the jar in
        // ~/.gradle/caches before writing this test) — a positive probe, so this proves the
        // PLATFORM adapter itself, not merely the session-open seam Tests D/E already cover
        // (REVIEW-OC-06).
        val outputFile = File(context.filesDir, "shadow-probe.m4a")
        val config = RecorderConfig(sampleRateHz = 44_100, encodingBitRate = 128_000, channelCount = 1)

        val session = AndroidMediaRecorderSession.open(context, outputFile, config) as AndroidMediaRecorderSession
        val shadowRecorder = shadowOf(session.mediaRecorderForTest())

        assertEquals(44_100, shadowRecorder.audioSamplingRate)
        assertEquals(128_000, shadowRecorder.audioEncodingBitRate)
        assertEquals(1, shadowRecorder.audioChannels)

        session.release()
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

        /**
         * Total number of times [maxAmplitude] has been invoked — a conflation-proof tick count,
         * unlike collected [io.github.ygaray.sttengine.recorder.Recorder.level] ticks (`mutableLevel`
         * is a `DROP_OLDEST` conflated `MutableSharedFlow` and can legitimately drop an emission,
         * corrupting a count for a reason unrelated to cadence). Asserted exactly by Test A.
         */
        var maxAmplitudeCalls = 0
            private set

        /**
         * Ordered log of `maxAmplitude`/`stop`/`release` invocations, in invocation order — what
         * Test F asserts the release-versus-read ordering on.
         */
        val callLog: List<String> get() = mutableCallLog
        private val mutableCallLog = mutableListOf<String>()

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
            mutableCallLog.add("stop")
        }

        override fun release() {
            releaseCalls++
            mutableCallLog.add("release")
        }

        override fun maxAmplitude(): Int {
            maxAmplitudeCalls++
            mutableCallLog.add("maxAmplitude")
            return amplitudeQueue.removeFirstOrNull() ?: 0
        }
    }

    /**
     * A [FakeRecorderSession] whose [maxAmplitude] always throws — the shape a released
     * `MediaRecorder` throws. Deliberately does NOT call `super.maxAmplitude()`, since Test G does
     * not need the call counted; it only needs the throw to be contained by [RecorderImpl]'s guard.
     */
    private class ThrowingAmplitudeSession : FakeRecorderSession() {
        override fun maxAmplitude(): Int = throw IllegalStateException("released")
    }

    /** Opens a fresh [FakeRecorderSession] per call by default; [opener] can throw to simulate REC-02. */
    private class FakeRecorderSessionFactory(
        private val opener: () -> RecorderSession = { FakeRecorderSession() },
    ) : RecorderSessionFactory {
        var openCallCount = 0
            private set
        val openedSessions = mutableListOf<FakeRecorderSession>()

        /** The [RecorderConfig] passed to the most recent [open] call — what Tests D and E assert on. */
        var lastOpenedConfig: RecorderConfig? = null
            private set

        override fun open(context: Context, outputFile: File, config: RecorderConfig): RecorderSession {
            openCallCount++
            lastOpenedConfig = config
            val session = opener()
            if (session is FakeRecorderSession) openedSessions.add(session)
            return session
        }
    }
}

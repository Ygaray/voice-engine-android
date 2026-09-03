package io.github.ygaray.sttengine.recorder.internal

import android.content.Context
import io.github.ygaray.sttengine.recorder.RecordedFile
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.recorder.RecorderConfig
import io.github.ygaray.sttengine.recorder.RecorderError
import io.github.ygaray.sttengine.recorder.RecorderErrorCode
import io.github.ygaray.sttengine.recorder.RecorderState
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The [Recorder] implementation. Owns exactly one [RecorderSession] at a time and self-generates
 * every output filename via [UUID] into [context]'s private `filesDir` — never from caller input
 * (T-08-04: a consumer never supplies a filename, closing off path-traversal-via-filename).
 *
 * Constructed directly with an injected [sessionFactory] by `RecorderTest.kt`, mirroring
 * `AudioSourcePipeTest`'s convention of testing the internal class directly rather than through the
 * public [io.github.ygaray.sttengine.recorder.createRecorder] factory.
 *
 * @param config host-configurable poll cadence (AUDIO-02/D-03). Encoding parameters are threaded
 *   through [sessionFactory] separately, not read directly here.
 * @param pollDispatcher backs the amplitude poll [scope]. `internal` and defaulted so it never
 *   surfaces on the public ABI or in [io.github.ygaray.sttengine.recorder.createRecorder]'s
 *   signature — its sole purpose is the determinism seam that lets `RecorderTest.kt` drive the poll
 *   loop from a `TestScheduler`-backed dispatcher instead of racing a wall clock.
 */
internal class RecorderImpl(
    private val context: Context,
    private val sessionFactory: RecorderSessionFactory = AndroidMediaRecorderSession,
    private val config: RecorderConfig = RecorderConfig(),
    private val pollDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Recorder {

    private val mutableState = MutableStateFlow(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    /**
     * `DROP_OLDEST` is what makes this genuinely conflated — a stale meter frame is worthless
     * (REC-06), and without an explicit overflow policy `MutableSharedFlow` defaults to `SUSPEND`,
     * which would stall the poll loop against a slow/absent collector instead of just dropping.
     */
    private val mutableLevel = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val level: Flow<Float> = mutableLevel.asSharedFlow()

    /**
     * `replay = 1` so a collector that attaches AFTER the push (e.g. `errors.first()` racing a
     * synchronous emit) still observes the most recent typed failure, rather than hanging forever
     * waiting for a next emission that never comes.
     */
    private val mutableErrors = MutableSharedFlow<RecorderError>(replay = 1, extraBufferCapacity = 8)
    override val errors: Flow<RecorderError> = mutableErrors.asSharedFlow()

    private var session: RecorderSession? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private var pausedAccumMs: Long = 0L
    private var pausedAtMs: Long = 0L

    /**
     * Backs the amplitude poll loop (REC-06). `SupervisorJob` so one failed tick cannot cancel the
     * rest — but note a `SupervisorJob` does NOT catch a `launch` child's exception, it only stops
     * that exception from cancelling siblings; the amplitude read inside [startPolling] is guarded
     * separately for that reason (REVIEW-OLLAMA-01). [pollDispatcher] defaults to
     * [Dispatchers.Default] but is overridable for deterministic virtual-time testing.
     */
    private val scope = CoroutineScope(SupervisorJob() + pollDispatcher)
    private var pollJob: Job? = null

    override suspend fun start() {
        if (mutableState.value == RecorderState.Recording || mutableState.value == RecorderState.Paused) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.ALREADY_RECORDING, "a recording is already in progress"),
            )
            return
        }

        val file = File(context.filesDir, "${UUID.randomUUID()}.m4a")
        pausedAccumMs = 0L

        val opened = try {
            sessionFactory.open(context, file)
        } catch (diskFailure: IOException) {
            // prepare()'s failure mode: disk-full or a permission error (REC-02).
            mutableState.value = RecorderState.Error
            mutableErrors.emit(
                RecorderError(
                    RecorderErrorCode.NO_STORAGE,
                    diskFailure.message ?: "no storage available for recording",
                ),
            )
            return
        } catch (micFailure: RuntimeException) {
            // start()'s failure mode: the mic is unavailable or busy (REC-02).
            mutableState.value = RecorderState.Error
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.MIC_DENIED, micFailure.message ?: "microphone unavailable"),
            )
            return
        }

        outputFile = file
        session = opened
        startedAtMs = System.currentTimeMillis()
        mutableState.value = RecorderState.Recording
        startPolling(opened)
    }

    override suspend fun pause() {
        if (mutableState.value != RecorderState.Recording) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.INVALID_STATE, "pause() requires an active Recording session"),
            )
            return
        }

        pausedAtMs = System.currentTimeMillis()
        stopPolling()
        session?.pause()
        mutableState.value = RecorderState.Paused
    }

    override suspend fun resume() {
        if (mutableState.value != RecorderState.Paused) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.INVALID_STATE, "resume() requires a Paused session"),
            )
            return
        }

        pausedAccumMs += (System.currentTimeMillis() - pausedAtMs)
        session?.resume()
        mutableState.value = RecorderState.Recording
        session?.let { startPolling(it) }
    }

    override suspend fun stop(): RecordedFile {
        // Guard on state the same way pause()/resume()/start() already do (CR-04): stop() while
        // Idle/Error — either never started, or a previous stop() already completed — must not
        // silently return stale/fabricated data. `outputFile` is reset to null on a successful
        // stop() below specifically so this guard also catches a SECOND stop() call, not just the
        // very-first-call case `checkNotNull` used to guard alone.
        if (mutableState.value != RecorderState.Recording && mutableState.value != RecorderState.Paused) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.INVALID_STATE, "stop() requires an active session"),
            )
            throw IllegalStateException("stop() called with no active recording session")
        }

        val file = checkNotNull(outputFile) { "stop() called before start()" }

        stopPolling()

        // Idempotent teardown — each step best-effort/tolerant of already-torn-down state,
        // mirroring AudioSourcePipe.close's discipline. `activeSession?.stop()`'s failure is
        // captured (not swallowed, CR-05): `android.media.MediaRecorder.stop()` documents throwing
        // a RuntimeException when "no valid audio/video data has been received" (the well-known
        // very-short-recording pitfall) — that must surface as a typed error, since the caller
        // otherwise gets a normal-looking RecordedFile pointing at a possibly empty/corrupt file.
        val activeSession = session
        val stopFailure = runCatching { activeSession?.stop() }.exceptionOrNull()
        runCatching { activeSession?.release() }
        session = null

        if (stopFailure != null) {
            mutableErrors.emit(
                RecorderError(
                    RecorderErrorCode.NO_STORAGE,
                    stopFailure.message ?: "recording finalize failed",
                ),
            )
        }

        val durationMs = ((System.currentTimeMillis() - startedAtMs) - pausedAccumMs).coerceAtLeast(0L)
        outputFile = null
        mutableState.value = RecorderState.Idle

        return RecordedFile(
            uri = file.absolutePath,
            durationMs = durationMs,
            format = "audio/mp4",
            createdAt = Instant.ofEpochMilli(startedAtMs).toString(),
        )
    }

    override suspend fun delete(uri: String) {
        val liveFile = outputFile
        if (session != null && liveFile != null && liveFile.absolutePath == uri) {
            mutableErrors.emit(
                RecorderError(
                    RecorderErrorCode.INVALID_STATE,
                    "cannot delete a file that is still being recorded",
                ),
            )
            return
        }

        val file = File(uri)
        if (file.exists() && !file.delete()) {
            // File.delete() returns false (never throws) on ordinary filesystem failures
            // (permission denied, locked by another process) — surface it rather than silently
            // reporting success for a delete that did not actually happen (WR-03).
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.NO_STORAGE, "failed to delete $uri"),
            )
        }
    }

    /** Test-only accessor for the currently-open recording's uri, before [stop] finalizes it. */
    internal fun currentUriForTest(): String? = outputFile?.absolutePath

    /**
     * Poll [RecorderSession.maxAmplitude] every [config]'s [RecorderConfig.levelIntervalMs] and emit
     * a normalized `[0,1]` tick on [level] — PEAK amplitude, not RMS; there is no RMS variant exposed
     * by the platform API (REC-06's documented, intentional cross-platform asymmetry against the web
     * Recorder).
     *
     * The pre-emptive `pollJob?.cancel()` below is fire-and-forget deliberately, unlike
     * [stopPolling]: it only ever replaces a job polling the SAME session (a defensive guard against
     * [startPolling] somehow being invoked twice for one session) and is never followed by a
     * [RecorderSession.release] call, so there is no read-after-release race to close here.
     */
    private fun startPolling(activeSession: RecorderSession) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // A failed amplitude read means the session is no longer readable — most plausibly
                // an already-released `MediaRecorder` throwing `IllegalStateException` from native
                // code. This scope installs no `CoroutineExceptionHandler`, and a `SupervisorJob`
                // does NOT catch a `launch` child's exception, it only stops that exception from
                // cancelling siblings — so letting this escape would reach the thread's default
                // uncaught-exception handler and, on Android, crash the host process over a single
                // meter frame. Stop iterating instead of retrying: once a read has failed, the
                // session is not expected to become readable again (REVIEW-OLLAMA-01, T-142-13).
                val amplitude = try {
                    activeSession.maxAmplitude()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (readFailure: Throwable) {
                    return@launch
                }
                val normalized = (amplitude.toFloat() / MAX_AMPLITUDE_FULLSCALE).coerceIn(0f, 1f)
                mutableLevel.emit(normalized)
                delay(config.levelIntervalMs)
            }
        }
    }

    /**
     * Stops emitting ticks — called on [pause] and [stop] so the amplitude channel goes quiet.
     *
     * Cancels AND JOINS the poll job. A bare `cancel()` is cooperative and asynchronous — it returns
     * immediately, while the coroutine may still be mid-[RecorderSession.maxAmplitude]. [stop] calls
     * [RecorderSession.release] a few lines after this returns, so without the join a poll iteration
     * could read from an already-released session. Joining makes "the poll loop has finished" a fact
     * the caller can rely on rather than a likelihood — and a host-lowered
     * [RecorderConfig.levelIntervalMs] makes that race MORE probable, not less, since it raises the
     * fraction of wall-clock time the loop spends inside the read (REVIEW-OLLAMA-01, T-142-13).
     */
    private suspend fun stopPolling() {
        pollJob?.cancelAndJoin()
        pollJob = null
    }

    private companion object {
        const val MAX_AMPLITUDE_FULLSCALE = 32767f
    }
}

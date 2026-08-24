package io.github.ygaray.sttengine.recorder.internal

import android.content.Context
import io.github.ygaray.sttengine.recorder.RecordedFile
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.recorder.RecorderError
import io.github.ygaray.sttengine.recorder.RecorderErrorCode
import io.github.ygaray.sttengine.recorder.RecorderState
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 */
internal class RecorderImpl(
    private val context: Context,
    private val sessionFactory: RecorderSessionFactory = AndroidMediaRecorderSession,
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

    /** Backs the amplitude poll loop (REC-06). `SupervisorJob` so one failed tick cannot cancel the rest. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        val file = checkNotNull(outputFile) { "stop() called before start()" }

        stopPolling()

        // Idempotent teardown — each step best-effort/tolerant of already-torn-down state,
        // mirroring AudioSourcePipe.close's discipline.
        val activeSession = session
        runCatching { activeSession?.stop() }
        runCatching { activeSession?.release() }
        session = null

        val durationMs = ((System.currentTimeMillis() - startedAtMs) - pausedAccumMs).coerceAtLeast(0L)
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
        if (file.exists()) file.delete()
    }

    /** Test-only accessor for the currently-open recording's uri, before [stop] finalizes it. */
    internal fun currentUriForTest(): String? = outputFile?.absolutePath

    /**
     * Poll [RecorderSession.maxAmplitude] every [POLL_INTERVAL_MS] and emit a normalized `[0,1]`
     * tick on [level] — PEAK amplitude, not RMS; there is no RMS variant exposed by the platform
     * API (REC-06's documented, intentional cross-platform asymmetry against the web Recorder).
     */
    private fun startPolling(activeSession: RecorderSession) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val normalized = (activeSession.maxAmplitude().toFloat() / MAX_AMPLITUDE_FULLSCALE)
                    .coerceIn(0f, 1f)
                mutableLevel.emit(normalized)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops emitting ticks — called on [pause] and [stop] so the amplitude channel goes quiet. */
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private companion object {
        const val POLL_INTERVAL_MS = 150L
        const val MAX_AMPLITUDE_FULLSCALE = 32767f
    }
}

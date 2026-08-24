package io.github.ygaray.sttengine.recorder.internal

import android.content.Context
import io.github.ygaray.sttengine.recorder.RecordedFile
import io.github.ygaray.sttengine.recorder.Recorder
import io.github.ygaray.sttengine.recorder.RecorderError
import io.github.ygaray.sttengine.recorder.RecorderErrorCode
import io.github.ygaray.sttengine.recorder.RecorderState
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** Conflated by capacity, not by replay — a stale meter frame is worthless (REC-06). */
    private val mutableLevel = MutableSharedFlow<Float>(extraBufferCapacity = 1)
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

    override suspend fun start() {
        if (mutableState.value == RecorderState.Recording || mutableState.value == RecorderState.Paused) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.ALREADY_RECORDING, "a recording is already in progress"),
            )
            return
        }

        val file = File(context.filesDir, "${UUID.randomUUID()}.m4a")
        outputFile = file
        pausedAccumMs = 0L
        session = sessionFactory.open(context, file)
        startedAtMs = System.currentTimeMillis()
        mutableState.value = RecorderState.Recording
    }

    override suspend fun pause() {
        if (mutableState.value != RecorderState.Recording) {
            mutableErrors.emit(
                RecorderError(RecorderErrorCode.INVALID_STATE, "pause() requires an active Recording session"),
            )
            return
        }

        pausedAtMs = System.currentTimeMillis()
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
    }

    override suspend fun stop(): RecordedFile {
        val file = checkNotNull(outputFile) { "stop() called before start()" }

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
}

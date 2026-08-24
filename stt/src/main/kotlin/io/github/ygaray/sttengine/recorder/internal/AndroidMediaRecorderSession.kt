package io.github.ygaray.sttengine.recorder.internal

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * A live recording session, behind a seam — mirroring [io.github.ygaray.sttengine.internal.PcmSource]'s
 * exact three-piece structure (interface + fun-interface factory + impl-with-companion, all one
 * file), so [io.github.ygaray.sttengine.recorder.internal.RecorderImpl]'s resource discipline is
 * provable on the host with no microphone and no Robolectric shadow for `MediaRecorder`.
 */
internal interface RecorderSession {

    /** Begin capturing. May throw — see [AndroidMediaRecorderSession]'s companion factory. */
    fun start()

    /** Suspend capture, writing to the same underlying file. Not idempotence-guarded here — the
     *  caller ([RecorderImpl]) owns the out-of-order guard against the current [io.github.ygaray.sttengine.recorder.RecorderState]. */
    fun pause()

    /** Resume a suspended session. */
    fun resume()

    /** Stop capturing and finalize the output file. Must tolerate being called once, cleanly. */
    fun stop()

    /** Release the underlying platform resource. Must tolerate being called when already released. */
    fun release()

    /** The max absolute amplitude sampled since the last call to this method — PEAK, not RMS. */
    fun maxAmplitude(): Int
}

/** Opens a [RecorderSession] against [outputFile]. Throws when the platform will not give us one. */
internal fun interface RecorderSessionFactory {
    fun open(context: Context, outputFile: File): RecorderSession
}

/**
 * The platform [RecorderSession]: a real `android.media.MediaRecorder` configured for
 * `MediaRecorder.OutputFormat.MPEG_4` + `MediaRecorder.AudioEncoder.AAC` (REC-04) — MPEG_4, NOT
 * `AAC_ADTS`, so the output is a real `.m4a` container (MP4 box structure) rather than a bare ADTS
 * stream.
 */
internal class AndroidMediaRecorderSession private constructor(
    private val recorder: MediaRecorder,
) : RecorderSession {

    /** The idempotence guard [RecorderSession.release] requires, mirroring `AudioRecordPcmSource`. */
    private var released = false

    override fun start() = recorder.start()

    override fun pause() = recorder.pause()

    override fun resume() = recorder.resume()

    override fun stop() = recorder.stop()

    override fun release() {
        if (released) return
        released = true
        recorder.release()
    }

    override fun maxAmplitude(): Int = recorder.maxAmplitude

    companion object : RecorderSessionFactory {

        override fun open(context: Context, outputFile: File): RecorderSession {
            @Suppress("DEPRECATION") // API 31+ constructor form; :stt minSdk 33 clears this.
            val recorder = MediaRecorder(context)
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setOutputFile(outputFile.absolutePath)
                // Can throw IOException — disk-full/permission (REC-02's NO_STORAGE mapping).
                recorder.prepare()
                // Can throw RuntimeException — mic-denied/busy (REC-02's MIC_DENIED mapping).
                recorder.start()
            } catch (failure: Throwable) {
                // Release before rethrowing, mirroring AudioRecordPcmSource.open's catch-release-
                // rethrow discipline: a half-constructed recorder still holds the input.
                runCatching { recorder.release() }
                throw failure
            }
            return AndroidMediaRecorderSession(recorder)
        }
    }
}

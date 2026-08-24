package io.github.ygaray.sttengine.internal

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * A source of 16-bit PCM frames, behind a seam.
 *
 * The seam exists so the *resource discipline* below — which is the entire risk of this rung — is
 * provable on the host with no microphone, no device and no Robolectric shadow for the recorder.
 * `AudioSourcePipeTest` drives a counting fake through every path including the failure ones.
 */
internal interface PcmSource {

    /**
     * Fill [buffer] with frames, blocking until some are available.
     *
     * @return how many bytes were written, or a value `<= 0` when the source has ended or errored.
     *   The pump treats any non-positive value as end of stream and stops.
     */
    fun read(buffer: ByteArray): Int

    /** Stop capturing. Must tolerate being called when already stopped. */
    fun stop()

    /** Release the underlying resource. Must tolerate being called when already released. */
    fun release()
}

/** Opens a [PcmSource]. Throws when the device will not give us a working one. */
internal fun interface PcmSourceFactory {
    fun open(sampleRateHz: Int): PcmSource
}

/**
 * The platform [PcmSource]: an `AudioRecord` on the voice-recognition input.
 *
 * The voice-recognition source specifically, not the default one — it is the input the platform
 * documents as tuned for recognition, and the probe measured that its echo cancellation does not
 * prevent recognition on this device.
 */
internal class AudioRecordPcmSource private constructor(
    private val record: AudioRecord,
) : PcmSource {

    override fun read(buffer: ByteArray): Int = record.read(buffer, 0, buffer.size)

    override fun stop() {
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
    }

    override fun release() {
        record.release()
    }

    companion object : PcmSourceFactory {

        /**
         * The host holds `RECORD_AUDIO` and the engine's preflight has already checked it before any
         * session can reach this code (D-11), so the lint warning here is about a check that has
         * demonstrably already happened one call stack up.
         */
        @SuppressLint("MissingPermission")
        override fun open(sampleRateHz: Int): PcmSource {
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "no usable recorder buffer size for ${sampleRateHz}Hz mono PCM16" }
            // At least one second of audio, so a busy main looper cannot overrun the driver's ring
            // buffer and drop frames the user actually spoke. The probe measured 32000 bytes here.
            val bufferBytes = maxOf(minBuffer, sampleRateHz * 2)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) {
                    "recorder did not initialise"
                }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "recorder did not start"
                }
            } catch (failure: Throwable) {
                // Release before rethrowing. A half-constructed recorder still holds the input and
                // the next attempt on the rung below would find the microphone taken by us.
                runCatching { record.release() }
                throw failure
            }
            return AudioRecordPcmSource(record)
        }
    }
}

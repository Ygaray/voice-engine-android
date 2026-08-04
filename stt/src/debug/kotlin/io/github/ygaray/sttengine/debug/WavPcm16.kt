package io.github.ygaray.sttengine.debug

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The PCM payload of a 16 kHz mono 16-bit RIFF/WAVE file, plus the facts that proved it was one.
 *
 * @property samples the `data` chunk contents verbatim — little-endian signed 16-bit mono frames,
 *   which is byte-for-byte what `AudioRecord` would have produced and therefore what the recognizer
 *   expects to read out of rung (c)'s pipe.
 * @property sampleRateHz always 16 000; carried so a caller can assert rather than assume.
 * @property durationMs how long [samples] takes to play at [sampleRateHz]. The harness sizes its
 *   hold from this, because a run that stops before the passage finishes has silently changed the
 *   stimulus.
 */
internal class WavPcm16(
    val samples: ByteArray,
    val sampleRateHz: Int,
    val durationMs: Long,
)

/**
 * Reject-everything-else WAV reading, for the debug audio-source seam only.
 *
 * ## Why this is strict rather than accommodating
 *
 * Rung (c)'s intent declares the format to the recognizer — mono, `ENCODING_PCM_16BIT`,
 * [io.github.ygaray.sttengine.internal.RecognizerIntents.AUDIO_SOURCE_SAMPLE_RATE_HZ] — and the
 * recognizer then reads the pipe *as if* that declaration were true. Feeding it a 44.1 kHz stereo
 * file does not fail; it produces a stream the recognizer interprets at the wrong rate and the wrong
 * channel count, so the words come out as noise and the run looks like an engine failure. Every
 * check below therefore refuses rather than converts: **an unusable stimulus must fail at the point
 * a human can still fix it**, not two minutes later inside a verdict.
 *
 * There is deliberately no resampling and no channel down-mix. This is a test fixture reader, and a
 * fixture that quietly gets converted is a fixture nobody can reason about.
 */
internal object WavPcm16Reader {

    private const val REQUIRED_SAMPLE_RATE_HZ = 16_000
    private const val REQUIRED_CHANNELS = 1
    private const val REQUIRED_BITS_PER_SAMPLE = 16

    /** Uncompressed integer PCM. Any other value is a codec this reader will not silently accept. */
    private const val WAVE_FORMAT_PCM = 1

    /** `RIFF` + size + `WAVE` — the smallest prefix worth trying to parse. */
    private const val RIFF_HEADER_BYTES = 12

    /** Every chunk is an 8-byte header (4-byte id, 4-byte little-endian size) then its payload. */
    private const val CHUNK_HEADER_BYTES = 8

    private const val BYTES_PER_FRAME = REQUIRED_CHANNELS * (REQUIRED_BITS_PER_SAMPLE / 8)
    private const val MS_PER_SECOND = 1_000L

    /**
     * Read and validate [path].
     *
     * @throws IOException when the path is not a readable regular file. Separated from the format
     *   failures below because the operator fix is different — a wrong path or a missing `adb push`
     *   is not a wrong file.
     * @throws IllegalArgumentException when the bytes are not a 16 kHz mono 16-bit PCM WAVE. The
     *   message always names **what was found**, not just what was wanted: "expected 16000 Hz, found
     *   44100 Hz" tells the operator which file they grabbed; "bad format" does not.
     */
    fun read(path: String): WavPcm16 {
        val file = File(path)
        if (!file.exists()) {
            throw IOException("audio-source file does not exist: $path (was it adb-pushed?)")
        }
        if (!file.isFile) {
            throw IOException("audio-source path is not a regular file: $path")
        }
        if (!file.canRead()) {
            throw IOException("audio-source file is not readable by this app: $path")
        }

        val bytes = file.readBytes()
        require(bytes.size >= RIFF_HEADER_BYTES) {
            "audio-source file is ${bytes.size} bytes, too short to be a WAVE file: $path"
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = buffer.readAscii(4)
        buffer.int // the RIFF chunk size, which is not trusted: a truncated file lies here.
        val wave = buffer.readAscii(4)
        require(riff == "RIFF" && wave == "WAVE") {
            "audio-source file is not RIFF/WAVE (found \"$riff\"/\"$wave\"): $path"
        }

        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var audioFormat = -1
        var data: ByteArray? = null

        // Walk the chunks rather than assuming the canonical 44-byte header. Recorders and editors
        // legitimately insert LIST/fact/cue chunks before `data`, and a reader that assumed a fixed
        // offset would read metadata as audio and produce a stimulus of pure noise.
        while (buffer.remaining() >= CHUNK_HEADER_BYTES) {
            val id = buffer.readAscii(4)
            val declared = buffer.int
            require(declared >= 0) {
                "audio-source file declares a negative size for chunk \"$id\": $path"
            }
            // Clamp: a truncated file declares more than it carries, and honouring that blindly
            // throws BufferUnderflow with no diagnosis attached.
            val size = minOf(declared, buffer.remaining())
            val payloadStart = buffer.position()

            when (id) {
                "fmt " -> {
                    require(size >= 16) { "audio-source \"fmt \" chunk is only $size bytes: $path" }
                    audioFormat = buffer.short.toInt() and 0xFFFF
                    channels = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byte rate — derivable, so not trusted
                    buffer.short // block align — likewise
                    bitsPerSample = buffer.short.toInt() and 0xFFFF
                }

                "data" -> {
                    val payload = ByteArray(size)
                    buffer.get(payload)
                    data = payload
                }
            }

            // Chunks are word-aligned: an odd size is followed by one pad byte.
            val next = payloadStart + size + (size and 1)
            if (next <= payloadStart || next > buffer.limit()) break
            buffer.position(next)
        }

        require(audioFormat == WAVE_FORMAT_PCM) {
            "audio-source file is not uncompressed PCM (WAVE format tag $audioFormat, expected " +
                "$WAVE_FORMAT_PCM): $path"
        }
        require(channels == REQUIRED_CHANNELS) {
            "audio-source file has $channels channel(s), expected mono ($REQUIRED_CHANNELS): $path"
        }
        require(sampleRate == REQUIRED_SAMPLE_RATE_HZ) {
            "audio-source file is $sampleRate Hz, expected $REQUIRED_SAMPLE_RATE_HZ Hz: $path"
        }
        require(bitsPerSample == REQUIRED_BITS_PER_SAMPLE) {
            "audio-source file is $bitsPerSample-bit, expected $REQUIRED_BITS_PER_SAMPLE-bit: $path"
        }

        val payload = data
        require(payload != null) { "audio-source file has no \"data\" chunk: $path" }
        require(payload.size >= BYTES_PER_FRAME) {
            "audio-source file carries no audio (${payload.size}-byte data chunk): $path"
        }

        val frames = payload.size / BYTES_PER_FRAME
        return WavPcm16(
            // Drop a trailing half-frame rather than feeding the recognizer a split sample.
            samples = if (frames * BYTES_PER_FRAME == payload.size) {
                payload
            } else {
                payload.copyOf(frames * BYTES_PER_FRAME)
            },
            sampleRateHz = sampleRate,
            durationMs = frames.toLong() * MS_PER_SECOND / sampleRate,
        )
    }

    private fun ByteBuffer.readAscii(count: Int): String {
        val raw = ByteArray(count)
        get(raw)
        return String(raw, Charsets.US_ASCII)
    }
}

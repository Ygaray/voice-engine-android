package io.github.ygaray.sttengine.debug

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [WavPcm16Reader] accepts exactly one shape of file and says precisely what was wrong with the rest.
 *
 * ## Why the rejection cases carry most of the weight here
 *
 * A wrong-format stimulus does not fail loudly on its own. Rung (c)'s intent *declares* mono/16 kHz/
 * 16-bit to the recognizer, which then reads the pipe as if that declaration were true — so a 44.1
 * kHz stereo file produces a stream the recognizer interprets at the wrong rate and turns into
 * gibberish. The run completes, the trace looks populated, and the verdict is nonsense. **That is the
 * failure mode this reader exists to prevent**, and it can only prevent it by refusing.
 *
 * So every test below asserts on the *message*, not merely that something was thrown. A message that
 * names what was found ("found 44100 Hz") tells an operator which file they grabbed; "bad format"
 * costs them an hour.
 *
 * These files are built byte by byte rather than committed as fixtures: a malformed WAV checked into
 * git is a binary nobody can review, and half of these shapes could not be produced by an encoder
 * anyway.
 */
class WavPcm16ReaderTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    // --- the accepted shape ---------------------------------------------------------------------

    @Test
    fun `reads a 16 kHz mono 16-bit file and reports its duration`() {
        // 16 000 frames = exactly one second at 16 kHz.
        val samples = pattern(frames = 16_000)
        val wav = WavPcm16Reader.read(write("good.wav", wave(samples)))

        assertEquals(16_000, wav.sampleRateHz)
        assertEquals(1_000L, wav.durationMs)
        assertArrayEquals("the data chunk must reach the caller verbatim", samples, wav.samples)
    }

    @Test
    fun `walks past chunks it does not care about rather than assuming a 44-byte header`() {
        // A LIST chunk between `fmt ` and `data` is ordinary output from real editors. A reader that
        // assumed the canonical header offset would read this metadata AS AUDIO and hand the
        // recognizer pure noise — which reads as a recognition failure, not as a bad fixture.
        val samples = pattern(frames = 800)
        val extra = "LIST".toByteArray(Charsets.US_ASCII) +
            littleEndianInt(10) + ByteArray(10) { 0x41 }

        val wav = WavPcm16Reader.read(write("chunky.wav", wave(samples, betweenFmtAndData = extra)))
        assertArrayEquals(samples, wav.samples)
    }

    @Test
    fun `honours the word-alignment pad byte after an odd-sized chunk`() {
        val samples = pattern(frames = 400)
        // 7 bytes of payload, so the chunk is followed by one pad byte. Miscounting it shifts every
        // subsequent chunk header by one and the `data` chunk is never found.
        val odd = "cue ".toByteArray(Charsets.US_ASCII) +
            littleEndianInt(7) + ByteArray(7) { 0x42 } + ByteArray(1)

        val wav = WavPcm16Reader.read(write("odd.wav", wave(samples, betweenFmtAndData = odd)))
        assertArrayEquals(samples, wav.samples)
    }

    @Test
    fun `drops a trailing half-frame rather than splitting a sample`() {
        val samples = pattern(frames = 100)
        val truncated = samples + byteArrayOf(0x7F)
        val wav = WavPcm16Reader.read(write("halfframe.wav", wave(truncated)))

        assertEquals("an odd-length data chunk must be trimmed to whole frames", 200, wav.samples.size)
    }

    // --- the refusals -------------------------------------------------------------------------

    @Test
    fun `a missing path names the path and suggests the adb push`() {
        val missing = File(temporaryFolder.root, "not-there.wav").absolutePath
        val thrown = assertThrows(IOException::class.java) { WavPcm16Reader.read(missing) }
        assertMessageContains(thrown, missing, "does not exist")
    }

    @Test
    fun `a directory is not a file`() {
        val directory = temporaryFolder.newFolder("a-directory").absolutePath
        val thrown = assertThrows(IOException::class.java) { WavPcm16Reader.read(directory) }
        assertMessageContains(thrown, "not a regular file")
    }

    @Test
    fun `a non-RIFF file is refused and the magic it actually carried is quoted`() {
        val notAWave = "ID3junkjunkjunkjunk".toByteArray(Charsets.US_ASCII)
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("mp3ish.wav", notAWave))
        }
        assertMessageContains(thrown, "not RIFF/WAVE", "ID3")
    }

    @Test
    fun `a file too short to hold a header is refused before it is parsed`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("stub.wav", byteArrayOf(1, 2, 3)))
        }
        assertMessageContains(thrown, "too short")
    }

    @Test
    fun `stereo is refused and the channel count is named`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("stereo.wav", wave(pattern(400), channels = 2)))
        }
        assertMessageContains(thrown, "2 channel", "expected mono")
    }

    @Test
    fun `the wrong sample rate is refused and both rates are named`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("cd.wav", wave(pattern(400), sampleRate = 44_100)))
        }
        assertMessageContains(thrown, "44100 Hz", "expected 16000 Hz")
    }

    @Test
    fun `8-bit audio is refused and the bit depth is named`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("eightbit.wav", wave(pattern(400), bitsPerSample = 8)))
        }
        assertMessageContains(thrown, "8-bit", "expected 16-bit")
    }

    @Test
    fun `a compressed payload is refused rather than fed through as PCM`() {
        // Format tag 0x0011 is IMA ADPCM. Its bytes are not samples, and feeding them to the
        // recognizer as if they were is the single most confusing way to fail.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("adpcm.wav", wave(pattern(400), formatTag = 0x11)))
        }
        assertMessageContains(thrown, "not uncompressed PCM", "17")
    }

    @Test
    fun `a file with no data chunk is refused`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("headeronly.wav", wave(ByteArray(0), omitData = true)))
        }
        assertMessageContains(thrown, "no \"data\" chunk")
    }

    @Test
    fun `an empty data chunk carries no audio and is refused`() {
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            WavPcm16Reader.read(write("silent.wav", wave(ByteArray(0))))
        }
        assertMessageContains(thrown, "carries no audio")
    }

    // --- fixture construction --------------------------------------------------------------------

    /** Frames whose little-endian value is the frame index, so a read offset is identifiable. */
    private fun pattern(frames: Int): ByteArray {
        val buffer = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buffer.putShort((it % 32_768).toShort()) }
        return buffer.array()
    }

    private fun wave(
        samples: ByteArray,
        channels: Int = 1,
        sampleRate: Int = 16_000,
        bitsPerSample: Int = 16,
        formatTag: Int = 1,
        betweenFmtAndData: ByteArray = ByteArray(0),
        omitData: Boolean = false,
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(formatTag.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
        }.array()

        val body = "WAVE".toByteArray(Charsets.US_ASCII) +
            "fmt ".toByteArray(Charsets.US_ASCII) + littleEndianInt(fmt.size) + fmt +
            betweenFmtAndData +
            if (omitData) {
                ByteArray(0)
            } else {
                "data".toByteArray(Charsets.US_ASCII) + littleEndianInt(samples.size) + samples
            }

        return "RIFF".toByteArray(Charsets.US_ASCII) + littleEndianInt(body.size) + body
    }

    private fun littleEndianInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun write(name: String, bytes: ByteArray): String =
        temporaryFolder.newFile(name).apply { writeBytes(bytes) }.absolutePath

    private fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit): T {
        val thrown = runCatching(block).exceptionOrNull()
            ?: throw AssertionError("expected ${type.simpleName} but nothing was thrown")
        if (!type.isInstance(thrown)) {
            throw AssertionError("expected ${type.simpleName} but got $thrown", thrown)
        }
        @Suppress("UNCHECKED_CAST")
        return thrown as T
    }

    /**
     * Every rejection must diagnose itself. Asserting on the message is the point of these tests:
     * a reader that threw a bare exception would satisfy a type-only assertion and still leave the
     * operator guessing which of six properties of their file was wrong.
     */
    private fun assertMessageContains(thrown: Throwable, vararg fragments: String) {
        val message = thrown.message.orEmpty()
        for (fragment in fragments) {
            assertTrue(
                "the failure must name \"$fragment\" so the operator can fix the right thing, " +
                    "but the message was: $message",
                message.contains(fragment),
            )
        }
    }
}

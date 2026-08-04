package io.github.ygaray.sttengine.internal

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Rung (c)'s resource discipline — the entire risk of the only rung where this library owns the
 * microphone.
 *
 * Two descriptors and one recorder are acquired per session, on a rung that is torn down and rebuilt
 * on every restart of a dictation session that may run for an hour. A descriptor leaked per session
 * is file-descriptor exhaustion; an un-released recorder holds the input against every later
 * attempt *including the attempt on the rung below*, so the symptom would be "this phone cannot do
 * speech recognition at all" rather than anything pointing back here. None of that is visible in a
 * transcript, which is exactly why it is asserted rather than reviewed.
 *
 * Robolectric is the runner because `ParcelFileDescriptor.createPipe()` needs a platform; the PCM
 * source is a counting fake because a unit test must never open the host's microphone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioSourcePipeTest {

    // --- the happy path -----------------------------------------------------------------------

    @Test
    fun `open exposes a live read descriptor and pumps until the source ends`() {
        val source = CountingPcmSource(framesBeforeEnd = 3)
        val ends = RecordingPipeFactory()
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = ends)

        pipe.open()
        assertTrue(pipe.isOpen)
        assertNotNull(pipe.readDescriptor)

        // Wait for the SOURCE to report that it was drained, rather than closing immediately and
        // asserting a count. Closing first would be asserting a race: `close()` stops the pump
        // before its next read on purpose, so an immediate close legitimately yields one read.
        assertTrue(
            "the pump must reach the source's end-of-stream signal",
            source.awaitEnd(),
        )
        pipe.close()

        assertEquals(
            "the pump must stop reading once the source reports end of stream",
            4,
            source.readCount,
        )
    }

    // --- the idempotence guard (T-05-09) ---------------------------------------------------------

    @Test
    fun `close releases the recorder and closes both descriptors exactly once`() {
        val source = CountingPcmSource()
        val ends = RecordingPipeFactory()
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = ends)

        pipe.open()
        pipe.close()

        assertEquals(1, source.stopCount)
        assertEquals(1, source.releaseCount)
        assertFalse("the write end must be closed", ends.writeEnd.fileDescriptor.valid())
        assertFalse("the read end must be closed", ends.readEnd.fileDescriptor.valid())
        assertFalse(pipe.isOpen)
    }

    @Test
    fun `a second close is a no-op, not a second release`() {
        val source = CountingPcmSource()
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = RecordingPipeFactory())

        pipe.open()
        pipe.close()
        pipe.close()
        pipe.close()

        // The engine closes the pipe on the restart path, the stop path AND the terminal-failure
        // path, and those overlap. Without the guard a double close would release a recorder that
        // is already gone.
        assertEquals(1, source.stopCount)
        assertEquals(1, source.releaseCount)
    }

    @Test
    fun `closing without ever opening is a no-op`() {
        val source = CountingPcmSource()
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = RecordingPipeFactory())

        pipe.close()

        assertEquals(0, source.releaseCount)
        assertFalse(pipe.isOpen)
    }

    // --- the failure paths, which are where a leak would actually happen ---------------------------

    @Test
    fun `a source that will not open leaves no descriptor behind and rethrows`() {
        val ends = RecordingPipeFactory()
        val pipe = AudioSourcePipe(
            sourceFactory = { error("the microphone is held by another app") },
            pipeFactory = ends,
        )

        assertThrows(IllegalStateException::class.java) { pipe.open() }

        // The pipe was created before the recorder was asked for, so this is the path where two
        // descriptors would leak per attempt — and the ladder retries on the rung below immediately
        // after, so it would be two per session rather than two per install.
        assertFalse(ends.writeEnd.fileDescriptor.valid())
        assertFalse(ends.readEnd.fileDescriptor.valid())
        assertFalse(pipe.isOpen)
    }

    @Test
    fun `a recorder that throws on release still leaves both descriptors closed`() {
        val source = object : PcmSource {
            override fun read(buffer: ByteArray): Int = -1
            override fun stop() = Unit
            override fun release(): Unit = throw IllegalStateException("driver went away")
        }
        val ends = RecordingPipeFactory()
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = ends)

        pipe.open()
        pipe.close()

        // The descriptor closes live in a finally precisely so a recorder failing on the way out
        // cannot take them with it.
        assertFalse(ends.writeEnd.fileDescriptor.valid())
        assertFalse(ends.readEnd.fileDescriptor.valid())
    }

    @Test
    fun `close stops a source that would otherwise never end`() {
        val source = CountingPcmSource(framesBeforeEnd = Int.MAX_VALUE, delayPerReadMs = 1)
        val pipe = AudioSourcePipe(sourceFactory = { source }, pipeFactory = RecordingPipeFactory())

        pipe.open()
        pipe.close()

        assertFalse(pipe.isOpen)
        assertEquals(1, source.releaseCount)
    }

    // --- the descriptor is not addressable outside the open window ---------------------------------

    @Test
    fun `readDescriptor throws before open and after close`() {
        val pipe = AudioSourcePipe(
            sourceFactory = { CountingPcmSource() },
            pipeFactory = RecordingPipeFactory(),
        )

        // A rung-(c) intent built around a descriptor that is not live would produce a session
        // behaving like rung (b), which would then degrade for a reason having nothing to do with
        // raw audio. Fail where the mistake is.
        assertThrows(IllegalStateException::class.java) { pipe.readDescriptor }
        pipe.open()
        pipe.close()
        assertThrows(IllegalStateException::class.java) { pipe.readDescriptor }
    }

    @Test
    fun `an opened pipe cannot be reopened`() {
        val pipe = AudioSourcePipe(
            sourceFactory = { CountingPcmSource() },
            pipeFactory = RecordingPipeFactory(),
        )

        pipe.open()
        try {
            assertThrows(IllegalStateException::class.java) { pipe.open() }
        } finally {
            pipe.close()
        }
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** Counts what the pipe did to it, and can either end at once or never. */
    private class CountingPcmSource(
        private val framesBeforeEnd: Int = 0,
        private val delayPerReadMs: Long = 0,
    ) : PcmSource {
        var readCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var releaseCount: Int = 0
            private set

        private val ended = CountDownLatch(1)

        /** Blocks until [read] has signalled end of stream. Bounded so a hang fails the test. */
        fun awaitEnd(): Boolean = ended.await(END_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        override fun read(buffer: ByteArray): Int {
            readCount += 1
            if (delayPerReadMs > 0) Thread.sleep(delayPerReadMs)
            if (readCount > framesBeforeEnd) {
                ended.countDown()
                return -1
            }
            // A small frame on purpose: this writes into a real pipe for the length of the test.
            buffer.fill(FRAME_BYTE, 0, FRAME_BYTES)
            return FRAME_BYTES
        }

        override fun stop() {
            stopCount += 1
        }

        override fun release() {
            releaseCount += 1
        }

        private companion object {
            const val FRAME_BYTES = 64
            const val FRAME_BYTE: Byte = 7
            const val END_TIMEOUT_SECONDS = 5L
        }
    }

    /** Hands the pipe a real pair and keeps references, so closure is assertable from outside. */
    private class RecordingPipeFactory : () -> Array<ParcelFileDescriptor> {
        private val ends: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
        val readEnd: ParcelFileDescriptor get() = ends[0]
        val writeEnd: ParcelFileDescriptor get() = ends[1]
        override fun invoke(): Array<ParcelFileDescriptor> = ends
    }
}

package io.github.ygaray.sttengine.debug

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PacedWavFeed] plays at wall-clock speed, resumes across restarts, and never ends the stream early.
 *
 * ## The three properties, and what each one prevents
 *
 * **Pacing.** An unthrottled source hands the whole 4 MB passage over in well under a second, and
 * every time-based fact about the run becomes fiction: AND-02's first assertion needs 120 000 ms of
 * session measured from the first `listening` state and would fail instantly, and the restart cadence
 * the requirement is actually about would never occur. [neverHandsOutMoreThanTheClockHasReleased] is
 * the guard, asserted as an invariant on **every** read rather than once at the end — a source that
 * dumped the file and then idled would satisfy an end-state check.
 *
 * **Clock-derived position.** Each recognition session builds a *new* source from the same factory.
 * If position were per-source, every restart would replay the passage from byte zero and the
 * analyzer would see duplicated text — failing the exact assertion the run exists to test, for a
 * reason having nothing to do with the engine. [aRestartResumesWhereTheClockGotTo] pins that down,
 * and pins the honest consequence with it: the audio that played during the restart's deaf window is
 * *gone*, exactly as it is with a microphone.
 *
 * **Silence, not end-of-stream.** A non-positive read ends the recognition session, which the engine
 * answers with a restart, whose fresh source is also immediately exhausted — a restart storm in the
 * final seconds of every run that trips the runaway guard and writes an error into the trace.
 *
 * The clock is injected and the sleeper advances it, so all of this runs on the host in
 * milliseconds. A test that actually waited 130 seconds would not be run.
 */
class PacedWavFeedTest {

    /** 16 000 Hz × 2 bytes = 32 bytes per millisecond. The constant the whole design turns on. */
    private val bytesPerMs = 32L

    /** Two seconds of identifiable audio. */
    private val frames = 32_000
    private val samples = pattern(frames)
    private val wav = WavPcm16(samples = samples, sampleRateHz = 16_000, durationMs = 2_000)

    private var clock = 1_000L

    /** Advancing the clock from inside the sleeper is what lets a paced source be tested instantly. */
    private fun feed(): PacedWavFeed = PacedWavFeed(
        wav = wav,
        path = "/fixture/passage.wav",
        nowMs = { clock },
        sleepMs = { clock += it },
    )

    // --- pacing -----------------------------------------------------------------------------------

    @Test
    fun `never hands out more than the clock has released`() {
        val paced = feed()
        val source = paced.open(16_000)
        val startedAt = clock

        val buffer = ByteArray(3_200)
        var delivered = 0L
        while (clock - startedAt < 1_000) {
            val read = source.read(buffer)
            assertTrue("a paced source must not report end-of-stream mid-passage", read > 0)
            delivered += read
            assertTrue(
                "handed out $delivered bytes after ${clock - startedAt} ms, which is faster than " +
                    "real time — AND-02 assertion 1 needs 120 000 ms of wall clock and an " +
                    "unthrottled source makes that unreachable",
                delivered <= (clock - startedAt) * bytesPerMs,
            )
        }

        // One second of audio is 32 000 bytes. Anything near the 64 000-byte file size would mean
        // the passage had already been dumped whole.
        assertTrue(
            "expected roughly one second of audio (32 000 bytes) after 1 s, got $delivered",
            delivered in 30_000..32_000,
        )
    }

    @Test
    fun `a read returns the passage bytes in order from the beginning`() {
        val paced = feed()
        val source = paced.open(16_000)

        val buffer = ByteArray(3_200)
        val read = source.read(buffer)
        assertTrue(read > 0)
        assertArrayEquals(
            "the first read must deliver the start of the passage",
            samples.copyOfRange(0, read),
            buffer.copyOfRange(0, read),
        )
    }

    @Test
    fun `an odd sized buffer never leaves the cursor mid sample`() {
        val paced = feed()
        val source = paced.open(16_000)
        val startedAt = clock
        // Let the clock release far more than one buffer (16 000 bytes), so each read is bounded by the
        // BUFFER size, not by the clock: that is the case an odd buffer used to corrupt.
        clock = startedAt + 500

        val buffer = ByteArray(3_201) // odd on purpose: a 16-bit sample is 2 bytes
        val collected = java.io.ByteArrayOutputStream()
        repeat(4) {
            val read = source.read(buffer)
            assertTrue("a paced source must not report end-of-stream mid-passage", read > 0)
            assertEquals("every read is a whole number of samples", 0, read % 2)
            collected.write(buffer, 0, read)
        }

        val got = collected.toByteArray()
        assertArrayEquals(
            "the bytes delivered through an odd buffer are exactly the passage prefix, sample aligned",
            samples.copyOfRange(0, got.size),
            got,
        )
    }

    // --- restart continuity ------------------------------------------------------------------------

    @Test
    fun aRestartResumesWhereTheClockGotTo() {
        val paced = feed()
        val first = paced.open(16_000)
        val startedAt = clock

        val buffer = ByteArray(3_200)
        first.read(buffer)
        first.stop()

        // Half a second into the passage, a restart builds a fresh source.
        clock = startedAt + 500
        val second = paced.open(16_000)
        val read = second.read(buffer)
        assertTrue(read > 0)

        val expectedOffset = 500 * bytesPerMs
        assertArrayEquals(
            "a restarted source must resume at the wall-clock offset. Replaying from byte zero " +
                "would feed the recognizer the same words twice and fail the duplication " +
                "assertion for a reason that has nothing to do with the engine.",
            samples.copyOfRange(expectedOffset.toInt(), expectedOffset.toInt() + read),
            buffer.copyOfRange(0, read),
        )
        assertNotEquals(
            "the resumed bytes must not be the start of the passage",
            samples.copyOfRange(0, read).toList(),
            buffer.copyOfRange(0, read).toList(),
        )
        assertEquals("two sessions means two opens", 2, paced.openCount)
    }

    @Test
    fun `the audio that played during a restart gap is lost, exactly as it is with a microphone`() {
        val paced = feed()
        val first = paced.open(16_000)
        val startedAt = clock
        val buffer = ByteArray(3_200)
        val firstRead = first.read(buffer)
        first.stop()

        clock = startedAt + 200
        val second = paced.open(16_000)
        val secondRead = second.read(buffer)

        val servedFromPcm = paced.pcmBytesFed
        val elapsed = clock - startedAt
        assertTrue(
            "the feed served $servedFromPcm bytes but $elapsed ms of passage went past; the gap " +
                "must be skipped rather than queued, or the restart would be inaudible in the " +
                "trace and the deaf window it exists to measure would vanish",
            servedFromPcm < elapsed * bytesPerMs,
        )
        assertEquals(firstRead + secondRead, servedFromPcm.toInt())
    }

    // --- the tail ---------------------------------------------------------------------------------

    @Test
    fun `past the end of the passage it feeds paced silence rather than ending the stream`() {
        val paced = feed()
        paced.open(16_000)
        val startedAt = clock

        // Well past the two-second passage, a restart builds a fresh source — which is exactly how
        // a run reaches the tail, since the hold outlasts the stimulus by design.
        clock = startedAt + 5_000
        val source = paced.open(16_000)
        val buffer = ByteArray(3_200) { 0x7F }
        val read = source.read(buffer)

        assertTrue(
            "an exhausted passage must not report end-of-stream: that ends the recognition " +
                "session, the engine restarts, the next source is exhausted too, and the run " +
                "finishes in a restart storm that trips the runaway guard",
            read > 0,
        )
        assertArrayEquals(
            "the tail must be silence",
            ByteArray(read),
            buffer.copyOfRange(0, read),
        )
        assertTrue("tail silence must be accounted for", paced.silenceBytesFed > 0)
        assertTrue(paced.exhausted)
    }

    @Test
    fun `a read that straddles the end of the passage delivers audio then silence`() {
        val paced = feed()
        val source = paced.open(16_000)
        val startedAt = clock

        // 1 ms past the end: 32 bytes of the read fall beyond the passage. The buffer is larger than
        // the whole passage so the boundary lands inside ONE read rather than between two.
        clock = startedAt + wav.durationMs + 1
        val buffer = ByteArray(65_536)
        val read = source.read(buffer)

        assertEquals("everything the clock released must be delivered", samples.size + 32, read)
        assertArrayEquals(samples, buffer.copyOfRange(0, samples.size))
        assertArrayEquals(ByteArray(32), buffer.copyOfRange(samples.size, read))
        assertEquals(samples.size.toLong(), paced.pcmBytesFed)
        assertEquals(32L, paced.silenceBytesFed)
    }

    // --- lifecycle and refusals ---------------------------------------------------------------------

    @Test
    fun `stop ends the stream so the pump can be joined within its bound`() {
        val paced = feed()
        val source = paced.open(16_000)
        source.stop()

        assertEquals(
            "a stopped source must report end-of-stream; AudioSourcePipe joins the pump with a " +
                "250 ms bound and a source that ignored stop would be past it",
            -1,
            source.read(ByteArray(3_200)),
        )
    }

    @Test
    fun `release also ends the stream`() {
        val paced = feed()
        val source = paced.open(16_000)
        source.release()
        assertEquals(-1, source.read(ByteArray(3_200)))
    }

    @Test
    fun `a sample-rate mismatch is refused rather than fed through`() {
        val thrown = runCatching { feed().open(44_100) }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $thrown", thrown is IllegalArgumentException)
        assertTrue(
            "the refusal must name both rates: feeding 16 kHz audio to a session that declared " +
                "another rate produces garbled text that reads exactly like a recognition failure",
            thrown!!.message.orEmpty().contains("44100") &&
                thrown.message.orEmpty().contains("16000"),
        )
    }

    @Test
    fun `nothing is consumed before the first open`() {
        val paced = feed()
        assertEquals(0, paced.openCount)
        assertEquals(0L, paced.pcmBytesFed)
        assertTrue("the passage cannot be exhausted before it has started", !paced.exhausted)
    }

    // --- the default clock is monotonic (the stimulus and the session trace share a clock family) ---

    @Test
    fun `a feed built without an injected clock paces on the monotonic default`() {
        val paced = PacedWavFeed(wav = wav, path = "/fixture/passage.wav")
        val before = PacedWavFeed.monotonicNowMs()
        val source = paced.open(16_000)
        var delivered = 0L
        val buffer = ByteArray(3_200)
        // Real waiting, but short: three reads of at most 100 ms of audio each.
        repeat(3) { delivered += source.read(buffer) }
        val elapsed = PacedWavFeed.monotonicNowMs() - before
        source.release()

        assertTrue("the default-clock feed delivered nothing", delivered > 0)
        // Scheduler jitter is tolerated: the invariant is only that the feed never runs ahead of the
        // monotonic clock, plus one chunk of slack for the frame alignment and the final read.
        assertTrue(
            "delivered $delivered bytes in $elapsed ms; the feed ran ahead of the monotonic clock",
            delivered <= (elapsed + 1) * bytesPerMs + 3_200,
        )
    }

    @Test
    fun `the monotonic default never goes backwards and is not the epoch`() {
        var last = PacedWavFeed.monotonicNowMs()
        repeat(10_000) {
            val now = PacedWavFeed.monotonicNowMs()
            assertTrue("monotonic clock went backwards: $last then $now", now >= last)
            last = now
        }
        // This documents a clock DOMAIN, not an exact value: an uptime-style reading is nowhere near
        // the epoch, which is how a wall-clock source would be told apart from this one.
        val oneYearMs = 365L * 24 * 60 * 60 * 1_000
        assertTrue(
            "$last looks like epoch milliseconds, not a monotonic reading",
            last < System.currentTimeMillis() - oneYearMs,
        )
    }

    private fun pattern(frames: Int): ByteArray {
        val buffer = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buffer.putShort(((it % 30_000) + 1).toShort()) }
        return buffer.array()
    }
}

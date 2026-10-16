package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.internal.PcmSource
import io.github.ygaray.sttengine.internal.PcmSourceFactory
import io.github.ygaray.sttengine.internal.RecognizerIntents
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [PcmSourceFactory] that plays a fixed WAV into rung (c)'s pipe **at wall-clock speed**, and
 * keeps playing (as silence) once the passage is over.
 *
 * ## Pacing is not a nicety — it is the difference between evidence and nonsense
 *
 * The obvious implementation reads the array as fast as the pipe accepts it. A 4.1 MB stimulus then
 * arrives in well under a second, and everything downstream that is measured in *time* becomes
 * meaningless: AND-02's first assertion requires 120 000 ms of session measured from the first
 * `listening` state and would fail instantly; the restart cadence the requirement is actually about
 * would never occur; the deaf windows would not exist to be measured. So the source hands out
 * exactly [BYTES_PER_MS] × elapsed-milliseconds and no more — 3 200 bytes per 100 ms, which is the
 * same rate a real `AudioRecord` at 16 kHz mono PCM16 produces and the same size
 * `AudioSourcePipe`'s pump asks for.
 *
 * ## The cursor is derived from the clock, which is what makes restarts honest
 *
 * Each recognition session builds a **new** [PcmSource] from this same factory, so a naive
 * implementation would restart the passage from byte zero on every restart and hand the analyzer a
 * transcript full of duplicated text — failing the very assertion the run exists to test, for a
 * reason that has nothing to do with the engine.
 *
 * Instead the read position is a pure function of elapsed time since the **first** open. A new
 * source therefore resumes wherever the clock has got to, which means the audio that played during
 * the restart's deaf window is *genuinely lost* — exactly as it is with a microphone, where the
 * speaker keeps talking into a recognizer that is not listening. The file-fed run is deterministic
 * without being dishonest: it reproduces the same loss, in the same place, every time.
 *
 * ## Why silence rather than end-of-stream at the end of the passage
 *
 * A non-positive read is end-of-stream to the pump, which ends the recognition session, which the
 * engine answers with a restart — whose fresh source is also immediately exhausted. That is a
 * restart storm at the tail of every run: it trips the restart policy's runaway guard, writes an
 * error into the trace, and turns a passing run into a failing one in its last five seconds.
 *
 * Paced silence instead. It costs nothing, it is what a microphone in a quiet room produces, and it
 * keeps the tail of the trace as boring as it should be. [silenceBytesFed] records how much, so a
 * run whose hold badly overshot the passage is visible after the fact rather than mysterious.
 *
 * ## The default clock is monotonic
 *
 * The pacing clock defaults to the monotonic millisecond source the session trace stamps its `t`
 * with, so the manifest-relative stimulus timeline and the trace share a clock family and a
 * wall-clock adjustment mid-run cannot shift one against the other. Tests inject their own clock.
 */
internal class PacedWavFeed(
    private val wav: WavPcm16,
    /** The armed path, carried only so diagnostics can name the file rather than describe it. */
    val path: String,
    private val nowMs: () -> Long = { monotonicNowMs() },
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) : PcmSourceFactory {

    /** When the first source opened, i.e. when this passage started playing. `-1` until then. */
    @Volatile
    private var startedAtMs: Long = NOT_STARTED

    /** How many [PcmSource]s have been handed out — one per recognition session on rung (c). */
    @Volatile
    var openCount: Int = 0
        private set

    /** Bytes served from [WavPcm16.samples]. The number that says the stimulus actually played. */
    @Volatile
    var pcmBytesFed: Long = 0
        private set

    /** Bytes of tail silence served after the passage ran out. */
    @Volatile
    var silenceBytesFed: Long = 0
        private set

    /** The passage's length, so a caller can size a hold that does not cut it short. */
    val durationMs: Long get() = wav.durationMs

    /** True once the clock has run past the end of the passage. */
    val exhausted: Boolean
        get() {
            val started = startedAtMs
            return started != NOT_STARTED && offsetAt(nowMs()) >= wav.samples.size
        }

    /**
     * @throws IllegalArgumentException when the engine asks for a rate this passage is not in.
     *   Silently feeding 16 kHz audio to a session that told the recognizer some other rate would
     *   produce garbled text that reads exactly like a recognition failure.
     */
    override fun open(sampleRateHz: Int): PcmSource {
        require(sampleRateHz == wav.sampleRateHz) {
            "rung (c) asked for ${sampleRateHz}Hz but the armed audio source is " +
                "${wav.sampleRateHz}Hz: $path"
        }
        synchronized(this) {
            if (startedAtMs == NOT_STARTED) startedAtMs = nowMs()
            openCount += 1
        }
        return Source(cursor = offsetAt(nowMs()))
    }

    /** Bytes of the virtual stream the wall clock has released by [atMs], aligned to a whole frame. */
    private fun offsetAt(atMs: Long): Long {
        val started = startedAtMs
        if (started == NOT_STARTED) return 0
        val elapsed = (atMs - started).coerceAtLeast(0)
        val raw = elapsed * BYTES_PER_MS
        return raw - (raw % BYTES_PER_FRAME)
    }

    private fun recordFed(fromPcm: Int, fromSilence: Int) = synchronized(this) {
        pcmBytesFed += fromPcm
        silenceBytesFed += fromSilence
    }

    private inner class Source(private var cursor: Long) : PcmSource {

        private val stopped = AtomicBoolean(false)

        override fun read(buffer: ByteArray): Int {
            // Wait for the clock to release at least one frame. Polled rather than computed as a
            // single sleep because `stop()` must be noticed promptly: the pump thread is joined with
            // a 250 ms bound during teardown, and a source parked in one long sleep would be past it.
            var released = offsetAt(nowMs())
            while (released <= cursor) {
                if (stopped.get()) return END_OF_STREAM
                sleepMs(PACE_POLL_MS)
                released = offsetAt(nowMs())
            }
            if (stopped.get()) return END_OF_STREAM

            // Whole samples only: `released` and `cursor` are 2-byte aligned, but an odd-sized buffer would
            // make `want` odd and leave `cursor` mid-sample, corrupting every later read. A buffer too small
            // to hold one 16-bit sample (< 2 bytes) can never be served, so it ends the stream (the pump
            // asks for 3 200 bytes; this is a latent guard, not a reachable path).
            val want = (minOf(buffer.size.toLong(), released - cursor).toInt()) and 1.inv()
            if (want <= 0) return END_OF_STREAM

            var written = 0
            var fromPcm = 0
            while (written < want) {
                val at = cursor + written
                if (at < wav.samples.size) {
                    val available = (wav.samples.size - at).toInt()
                    val take = minOf(want - written, available)
                    System.arraycopy(wav.samples, at.toInt(), buffer, written, take)
                    written += take
                    fromPcm += take
                } else {
                    buffer.fill(0, written, want)
                    written = want
                }
            }

            cursor += want
            recordFed(fromPcm, want - fromPcm)
            return want
        }

        override fun stop() {
            stopped.set(true)
        }

        override fun release() {
            stopped.set(true)
        }
    }

    internal companion object {

        private const val NOT_STARTED = -1L

        /**
         * The default pacing clock: the same monotonic millisecond source `SessionLog` stamps its
         * `t` with, so a wall-clock adjustment cannot move the stimulus against the trace.
         */
        fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

        /** 16 000 Hz × 2 bytes = 32 000 bytes per second = 32 per millisecond. */
        const val BYTES_PER_MS: Long = (RecognizerIntents.AUDIO_SOURCE_SAMPLE_RATE_HZ * 2L) / 1_000L

        /** Mono 16-bit: never hand out a partial sample. */
        private const val BYTES_PER_FRAME = 2L

        /**
         * How long a source waits before re-asking the clock.
         *
         * An order of magnitude below the pump's 100 ms chunk, so the pump is never made late by the
         * poll granularity, and short enough that a `stop()` during teardown is seen well inside the
         * 250 ms pump join.
         */
        private const val PACE_POLL_MS = 5L

        /** `AudioSourcePipe`'s pump treats any non-positive read as the end of the stream. */
        private const val END_OF_STREAM = -1
    }
}

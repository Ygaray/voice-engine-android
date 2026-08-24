package io.github.ygaray.sttengine.internal

import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How many bytes the pump moves per read. One tenth of a second of 16 kHz mono 16-bit PCM.
 *
 * TUNABLE, NOT CONTRACTUAL. Larger means fewer syscalls and coarser cancellation; smaller means the
 * pump notices [AudioSourcePipe.close] sooner. Nothing in the contract depends on it.
 */
private const val PUMP_CHUNK_BYTES: Int = 3_200

/**
 * How long [AudioSourcePipe.close] waits for the pump thread to notice and finish.
 *
 * Bounded on purpose: the pump can be parked inside a recorder read, and a teardown that waited
 * forever for a wedged driver would hang the restart — which is on the main looper.
 */
private const val PUMP_JOIN_MS: Long = 250

/**
 * Rung (c)'s app-owned microphone: a [PcmSource] pumped into a `ParcelFileDescriptor` pipe whose
 * read end the recognizer consumes as its audio input.
 *
 * This is the **only** place in the library that owns the microphone; on every other rung the
 * recognition service owns it. Measured SHIP on the tester — 6 segment finals at precision 1.000
 * from a pipe-fed session (`05-RUNG-DECISION.md`).
 *
 * ## Resource discipline is the entire risk
 *
 * Two descriptors and one recorder are acquired here, on a rung that runs for as long as the user
 * keeps dictating and is torn down and rebuilt on every restart. A descriptor leaked per session is
 * a process-wide file-descriptor exhaustion on a long dictation session, and an un-released recorder
 * holds the input against every later attempt — including the attempt on the rung *below*, so the
 * failure would look like "this phone cannot do speech recognition at all".
 *
 * Therefore: [close] releases everything on every path including exceptions, each step guarded so
 * one failure cannot skip the next, and it is **idempotent** — a second call is a no-op. [open]
 * closes everything it acquired before rethrowing.
 *
 * ## Why the write end closes first, and why the engine commits before it does
 *
 * Closing the write end is a clean end-of-stream to the recognizer, and the probe measured this
 * device finalising gracefully on it — the upstream data-loss signature (a mid-read close killing
 * the open segment with `ERROR_CLIENT(5)` and losing the whole utterance) did **not** reproduce
 * here. That is a device-specific negative, so the engine still does the safe thing: it commits the
 * live partial **before** this closes, so the words already spoken are committed whichever way the
 * recognizer reacts, and the ladder still carries the code-5 trigger for devices this measurement
 * does not cover.
 *
 * Single ownership, deliberately: the pump *borrows* an output stream over the write descriptor and
 * never closes it. [close] owns every descriptor, so there is exactly one place to audit for leaks.
 *
 * ## What this class must never do
 *
 * Nothing here may touch device-wide sound state to suppress the recognizer's start beep — no
 * stream muting, no volume adjustment, no ringer-mode change, no interruption-filter change. Those
 * controls belong to apps that replace the platform's own sound management, not to a dictation
 * library; they are a no-op under a fixed-volume policy; and a ringer-mode change has needed
 * do-not-disturb access since Android N. Silencing the user's phone to hide an implementation
 * artifact trades a small annoyance for a missed call or a missed alarm. An acceptance gate greps
 * the library source for exactly those calls, so do not name them here either.
 *
 * ## This class is A consumer of [PcmSourceFactory], not its sole owner (D-03)
 *
 * [sourceFactory] is an injected [PcmSourceFactory] living in its own file (`PcmSource.kt`); this
 * class merely holds a reference to one and calls [PcmSourceFactory.open] once per [open]. Nothing
 * here prevents a second, independent [AudioSourcePipe] from being constructed against the SAME
 * factory instance and opening its own, unrelated [PcmSource] — the constructor-injection shape
 * already models "named consumer," not "implicit sole owner."
 *
 * @param sampleRateHz must match what the intent declares — [RecognizerIntents] owns that constant
 *   so the recorder and the intent cannot drift apart.
 * @param sourceFactory injected so the discipline above is testable on the host. Its **default**
 *   consults [DebugAudioSourceHolder] before falling back to the microphone, which is what lets a
 *   debug build feed rung (c) a fixed WAV for a deterministic AND-02 evidence run. That cell is
 *   `null` in every release compilation and nothing there can assign it, so the default is
 *   [AudioRecordPcmSource] in a shipped build exactly as it was before the seam existed. Reading it
 *   in the default argument — rather than inside [open] — is deliberate: every existing caller that
 *   passes a factory explicitly (which is all of `AudioSourcePipeTest`) never touches the cell at
 *   all, so the seam cannot perturb a single existing test.
 * @param pipeFactory injected for the same reason.
 */
internal class AudioSourcePipe(
    private val sampleRateHz: Int = RecognizerIntents.AUDIO_SOURCE_SAMPLE_RATE_HZ,
    private val sourceFactory: PcmSourceFactory =
        DebugAudioSourceHolder.factory ?: AudioRecordPcmSource,
    private val pipeFactory: () -> Array<ParcelFileDescriptor> = { ParcelFileDescriptor.createPipe() },
) {

    /** The idempotence guard. Atomic because [close] can race the pump thread's own failure path. */
    private val closed = AtomicBoolean(false)

    /** Cleared to ask the pump to stop. Atomic because the pump thread reads it. */
    private val pumping = AtomicBoolean(false)

    private var opened = false
    private var readEnd: ParcelFileDescriptor? = null
    private var writeEnd: ParcelFileDescriptor? = null
    private var source: PcmSource? = null
    private var pump: Thread? = null

    /**
     * The read end, to hand to the recognizer through the intent.
     *
     * @throws IllegalStateException before [open] or after [close] — a rung-(c) intent built around
     *   a descriptor that is not live would produce a session that silently behaves like rung (b),
     *   which would then degrade for a reason having nothing to do with raw audio.
     */
    val readDescriptor: ParcelFileDescriptor
        get() = checkNotNull(readEnd) { "AudioSourcePipe is not open" }

    /**
     * Whether **our** write end is still open.
     *
     * This is the field the ladder's code-5 degradation trigger reads: an `ERROR_CLIENT(5)` while
     * this is `true` matches the upstream data-loss signature, whereas the same code arriving after
     * we closed the pipe ourselves is our own teardown and is not evidence against the rung.
     */
    val isOpen: Boolean get() = opened && !closed.get()

    /**
     * Acquire the pipe and the recorder and start pumping.
     *
     * Single use: an instance that has been opened cannot be reopened, because a reused pipe would
     * hand the recognizer a descriptor a previous session may still be draining.
     *
     * @throws Exception whatever the platform raised. Everything acquired is released first, so a
     *   caller is free to degrade to the next rung immediately.
     */
    fun open() {
        check(!opened) { "AudioSourcePipe is single use: open() once, close() once" }
        opened = true
        try {
            val ends = pipeFactory()
            readEnd = ends[0]
            writeEnd = ends[1]

            val opening = sourceFactory.open(sampleRateHz)
            source = opening

            // Borrowed, never closed by the pump — see the class KDoc on single ownership.
            val sink = FileOutputStream(ends[1].fileDescriptor)
            pumping.set(true)
            pump = Thread({ pumpInto(opening, sink) }, PUMP_THREAD_NAME).apply {
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /**
     * Release everything, exactly once.
     *
     * Idempotent by the [closed] compare-and-set: the engine closes the pipe on the restart path,
     * on the stop path and on the terminal-failure path, and those can overlap. A double close must
     * be a no-op rather than a second `release()` on a recorder that is already gone.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        pumping.set(false)

        val dyingSource = source
        val dyingWrite = writeEnd
        val dyingRead = readEnd
        val dyingPump = pump
        source = null
        writeEnd = null
        readEnd = null
        pump = null

        try {
            runCatching { dyingSource?.stop() }
            runCatching { dyingSource?.release() }
        } finally {
            // In a finally so a recorder that throws on release cannot leak a descriptor, and
            // nested so neither descriptor can be skipped by the other failing.
            try {
                // The WRITE end first: to the recognizer that is a clean end of stream.
                runCatching { dyingWrite?.close() }
            } finally {
                try {
                    runCatching { dyingRead?.close() }
                } finally {
                    // Bounded — see PUMP_JOIN_MS. Never unbounded: this runs on the main looper.
                    runCatching { dyingPump?.join(PUMP_JOIN_MS) }
                }
            }
        }
    }

    private fun pumpInto(from: PcmSource, into: OutputStream) {
        val buffer = ByteArray(PUMP_CHUNK_BYTES)
        try {
            while (pumping.get()) {
                val read = from.read(buffer)
                if (read <= 0) break
                into.write(buffer, 0, read)
                into.flush()
            }
        } catch (ended: IOException) {
            // Either the recognizer let go of its end or ours was closed under us. Both are the
            // ordinary end of a session, not a fault: the thread's only job is to stop pumping.
        } finally {
            pumping.set(false)
        }
    }

    private companion object {
        const val PUMP_THREAD_NAME = "stt-audio-source-pump"
    }
}

package io.github.ygaray.sttengine.internal

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A finite [PcmSource] for the server-path pump tests (Phase 21, SRV-05, Pitfall 1).
 *
 * Hands out exactly `frames * frameBytes` bytes of little-endian [sample], at most [chunkBytes] per
 * `read()` (so a test can force short reads), then returns `-1` forever — end of stream, which ends
 * [pumpFrames] quietly. Also returns `-1` once [stop] has been called. Unlike the Robolectric shadow
 * `AudioRecord` (whose `read()` returns a full buffer forever), a pump over this source always ends.
 *
 * Every observable flag is `@Volatile`: the pump thread writes them, the test thread reads them.
 *
 * @param readDelayMs an optional sleep per successful read, so a teardown test can catch the pump
 *   mid-stream without flooding memory.
 */
internal class FinitePcmSource(
    frames: Int,
    frameBytes: Int = 1024,
    private val chunkBytes: Int = frameBytes,
    sample: Short = 0,
    private val readDelayMs: Long = 0L,
) : PcmSource {

    private val lo: Byte = (sample.toInt() and 0xFF).toByte()
    private val hi: Byte = ((sample.toInt() shr 8) and 0xFF).toByte()
    private var remaining: Long = frames.toLong() * frameBytes
    private var position: Long = 0L

    /** How many times [read] was called (any result). */
    @Volatile var readCount: Int = 0
        private set

    /** `true` once every byte has been handed out AND the reader came back for more (got `-1`) —
     *  i.e. the pump has finished with the last frame it read. */
    @Volatile var exhausted: Boolean = false
        private set

    @Volatile var stopped: Boolean = false
        private set

    @Volatile var released: Boolean = false
        private set

    /** The thread that last called [read] — the pump thread, in a backend test. */
    @Volatile var readerThread: Thread? = null
        private set

    /** Whether [readerThread] was still alive when [release] ran (`null` = never released, or
     *  never read). The teardown discipline requires `false`: release only after the join. */
    @Volatile var readerAliveAtRelease: Boolean? = null
        private set

    override fun read(buffer: ByteArray): Int {
        readerThread = Thread.currentThread()
        readCount++
        if (stopped) return -1
        if (remaining <= 0L) {
            exhausted = true
            return -1
        }
        if (readDelayMs > 0L) Thread.sleep(readDelayMs)
        val count = minOf(chunkBytes.toLong(), buffer.size.toLong(), remaining).toInt()
        for (i in 0 until count) {
            buffer[i] = if ((position + i) % 2L == 0L) lo else hi
        }
        position += count
        remaining -= count
        return count
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        readerAliveAtRelease = readerThread?.isAlive
        released = true
    }
}

/** A [PcmSourceFactory] of [FinitePcmSource]s that remembers every source it opened. */
internal class FinitePcmSourceFactory(
    private val frames: Int,
    private val chunkBytes: Int = 1024,
    private val sample: Short = 0,
    private val readDelayMs: Long = 0L,
) : PcmSourceFactory {

    /** Every source this factory opened, oldest first. */
    val opened: MutableList<FinitePcmSource> = CopyOnWriteArrayList()

    /** The most recently opened source. */
    val last: FinitePcmSource
        get() = opened.lastOrNull() ?: error("FinitePcmSourceFactory: nothing opened yet")

    override fun open(sampleRateHz: Int): PcmSource =
        FinitePcmSource(
            frames = frames,
            chunkBytes = chunkBytes,
            sample = sample,
            readDelayMs = readDelayMs,
        ).also { opened += it }
}

/** `frames` whole 1 024-byte frames of [sample], then end of stream (see [FinitePcmSource]). */
internal fun finitePcmSourceFactory(
    frames: Int,
    chunkBytes: Int = 1024,
    sample: Short = 0,
    readDelayMs: Long = 0L,
): FinitePcmSourceFactory = FinitePcmSourceFactory(frames, chunkBytes, sample, readDelayMs)

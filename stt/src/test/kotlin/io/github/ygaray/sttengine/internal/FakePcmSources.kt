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

    /** How many times [release] ran. The teardown discipline requires exactly one. */
    @Volatile var releaseCount: Int = 0
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
        releaseCount++
    }
}

/**
 * A [PcmSource] that models a vendor `AudioRecord.read()` which does NOT unblock on `stop()`
 * (21 W12 review, finding 1).
 *
 * The first [freeReads] reads each return one full frame of [sample] immediately. The next read
 * blocks — ignoring [stop] — until the test calls [unblock], then returns one more full frame of
 * [sample]: the "late" capture that must never be emitted. Every read after that returns `-1`.
 *
 * Records what the teardown discipline is judged by: how many times [release] ran, on which thread,
 * and whether a read was in flight at that moment.
 */
internal class WedgedPcmSource(
    private val freeReads: Int,
    private val sample: Short = 0x2000,
) : PcmSource {

    private val wedged = java.util.concurrent.CountDownLatch(1)
    private val gate = java.util.concurrent.CountDownLatch(1)
    private val reads = java.util.concurrent.atomic.AtomicInteger(0)
    private val releases = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile var inRead: Boolean = false
        private set

    @Volatile var stopped: Boolean = false
        private set

    /** The thread that called [read] — the pump thread. */
    @Volatile var readerThread: Thread? = null
        private set

    /** The thread [release] last ran on. */
    @Volatile var releaseThread: Thread? = null
        private set

    /** Whether a read was in flight when [release] last ran. Must be `false`. */
    @Volatile var inReadAtRelease: Boolean? = null
        private set

    val releaseCount: Int get() = releases.get()

    /** Blocks until the pump is parked in the wedged read. */
    fun awaitWedged(timeoutMs: Long): Boolean =
        wedged.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    /** Lets the wedged read return (with a full frame of audio). */
    fun unblock() = gate.countDown()

    override fun read(buffer: ByteArray): Int {
        readerThread = Thread.currentThread()
        val n = reads.incrementAndGet()
        if (n > freeReads + 1) return -1
        inRead = true
        try {
            if (n == freeReads + 1) {
                wedged.countDown()
                gate.await() // deliberately deaf to stop(): the vendor wedge
            }
            fill(buffer)
            return buffer.size
        } finally {
            inRead = false
        }
    }

    private fun fill(buffer: ByteArray) {
        val lo = (sample.toInt() and 0xFF).toByte()
        val hi = ((sample.toInt() shr 8) and 0xFF).toByte()
        for (i in buffer.indices) buffer[i] = if (i % 2 == 0) lo else hi
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        inReadAtRelease = inRead
        releaseThread = Thread.currentThread()
        releases.incrementAndGet()
    }
}

/** A [PcmSourceFactory] that always hands out [source] and counts how often it was asked. */
internal class SingleSourceFactory(private val source: PcmSource) : PcmSourceFactory {
    @Volatile var openCount: Int = 0
        private set

    override fun open(sampleRateHz: Int): PcmSource {
        openCount++
        return source
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

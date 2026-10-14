package io.github.ygaray.sttengine.internal

import kotlin.math.sqrt

/**
 * The server path's input-level aggregator (Phase 21, SRV-05, D-03) — a direct port of
 * `web/src/worklet/downsample.worklet.ts`'s `createLevelAggregator`, so a host renders the same
 * meter whichever backend is live.
 *
 * Each [accept] takes one wire frame of little-endian PCM16 and adds `(sample / 32768)²` per sample
 * to a running sum. Every [framesPerLevel]-th frame it returns `min(1, rms / √½)` and resets; every
 * other frame it returns `null`. With 512-sample frames at 16 kHz and the default of 3 frames per
 * level that is one value per ~96 ms (≈ 10.4 Hz), the same cadence as the web client. Dividing by
 * √½ (the RMS of a full-scale sine) makes `1.0` mean clipping-loud and keeps quieter input in
 * `[0, 1)`.
 *
 * Not thread-safe, and does not need to be: [ServerBackend] uses one instance from its single pump
 * thread only.
 */
internal class LevelMeter(private val framesPerLevel: Int = 3) {

    private var sumSquares = 0.0
    private var sampleCount = 0
    private var frames = 0

    fun accept(frame: ByteArray): Float? {
        var i = 0
        while (i + 1 < frame.size) {
            val sample = (frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)
            val v = sample.toShort() / 32768.0
            sumSquares += v * v
            sampleCount++
            i += 2
        }
        frames++
        if (frames < framesPerLevel) return null

        val rms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
        sumSquares = 0.0
        sampleCount = 0
        frames = 0
        return minOf(1.0, rms / FULL_SCALE_RMS).toFloat()
    }

    private companion object {
        /** RMS of a full-scale sine — `Math.SQRT1_2` in the web aggregator. */
        val FULL_SCALE_RMS: Double = sqrt(0.5)
    }
}

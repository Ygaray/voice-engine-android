package io.github.ygaray.sttengine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D-03 level parity: [LevelMeter] must compute exactly what `web/src/worklet/downsample.worklet.ts`'s
 * `createLevelAggregator` computes — RMS of int16/32768 samples over 3 consecutive 512-sample
 * frames, divided by √½, clamped to 1 — so a host renders the same meter on either backend.
 * Plain JUnit: the meter is pure arithmetic over little-endian PCM16 bytes.
 */
class LevelMeterTest {

    /** One 1 024-byte frame (512 samples) whose sample `i` is `sampleAt(i)`, little-endian. */
    private fun frame(sampleAt: (Int) -> Int): ByteArray {
        val bytes = ByteArray(FRAME_SAMPLES * 2)
        for (i in 0 until FRAME_SAMPLES) {
            val s = sampleAt(i)
            bytes[2 * i] = (s and 0xFF).toByte()
            bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Every [LevelMeter.accept] on a window's last frame must emit a level. */
    private fun emitted(level: Float?): Float {
        assertNotNull("expected a level on the window's last frame", level)
        return level!!
    }

    @Test
    fun `three silent frames emit null, null, then 0`() {
        val meter = LevelMeter()
        val silent = frame { 0 }
        assertNull(meter.accept(silent))
        assertNull(meter.accept(silent))
        assertEquals(0.0f, emitted(meter.accept(silent)), 0.0f)
    }

    @Test
    fun `a full-scale square wave clamps to exactly 1`() {
        val meter = LevelMeter()
        val square = frame { i -> if (i % 2 == 0) 32767 else -32768 }
        assertNull(meter.accept(square))
        assertNull(meter.accept(square))
        assertEquals(1.0f, emitted(meter.accept(square)), 0.0f)
    }

    @Test
    fun `a constant half-scale signal reads 0_5 over root-half`() {
        val meter = LevelMeter()
        val half = frame { 16384 }
        meter.accept(half)
        meter.accept(half)
        val expected = minOf(1.0, 0.5 / Math.sqrt(0.5)).toFloat() // ≈ 0.7071
        assertEquals(expected, emitted(meter.accept(half)), 1e-3f)
    }

    @Test
    fun `frames four to six start a fresh window`() {
        val meter = LevelMeter()
        val loud = frame { i -> if (i % 2 == 0) 32767 else -32768 }
        val silent = frame { 0 }
        meter.accept(loud)
        meter.accept(loud)
        assertEquals(1.0f, emitted(meter.accept(loud)), 0.0f)
        // The previous window's loud energy must not leak into the next one.
        assertNull(meter.accept(silent))
        assertNull(meter.accept(silent))
        assertEquals(0.0f, emitted(meter.accept(silent)), 0.0f)
    }
}

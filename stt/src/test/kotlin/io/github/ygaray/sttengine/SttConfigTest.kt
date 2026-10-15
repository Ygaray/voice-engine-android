package io.github.ygaray.sttengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRY-01 (D-07, D-14): `SttConfig.serverTryTimeoutMs` — the host's try-server window.
 *
 * Pins the default (5 000 ms), the clamp into [1 000, 10 000] ms (clamped, never rejected), the
 * adjacency edges on both bounds, the degenerate inputs (zero, negative, Long extremes), and that the
 * clamped value is what `equals` / `hashCode` / `toString` see. Also pins that every pre-existing
 * call shape still compiles and behaves as before — the knob is the trailing, defaulted 6th
 * parameter, so nothing that compiled before this field existed can stop compiling.
 */
class SttConfigTest {

    @Test
    fun `omitted window defaults to 5000 ms`() {
        assertEquals(5_000L, SttConfig().serverTryTimeoutMs)
        assertEquals(5_000L, SttConfig(serverUrl = "wss://host.example:8444/").serverTryTimeoutMs)
    }

    @Test
    fun `bounds are kept exactly`() {
        assertEquals(1_000L, SttConfig(serverTryTimeoutMs = 1_000L).serverTryTimeoutMs)
        assertEquals(10_000L, SttConfig(serverTryTimeoutMs = 10_000L).serverTryTimeoutMs)
        assertEquals(7_000L, SttConfig(serverTryTimeoutMs = 7_000L).serverTryTimeoutMs)
    }

    @Test
    fun `values just outside the bounds clamp to the adjacent bound`() {
        assertEquals(1_000L, SttConfig(serverTryTimeoutMs = 999L).serverTryTimeoutMs)
        assertEquals(10_000L, SttConfig(serverTryTimeoutMs = 10_001L).serverTryTimeoutMs)
    }

    @Test
    fun `zero, negative and Long extremes clamp rather than throw`() {
        assertEquals(1_000L, SttConfig(serverTryTimeoutMs = 0L).serverTryTimeoutMs)
        assertEquals(1_000L, SttConfig(serverTryTimeoutMs = -1L).serverTryTimeoutMs)
        assertEquals(1_000L, SttConfig(serverTryTimeoutMs = Long.MIN_VALUE).serverTryTimeoutMs)
        assertEquals(10_000L, SttConfig(serverTryTimeoutMs = Long.MAX_VALUE).serverTryTimeoutMs)
    }

    @Test
    fun `configs whose windows clamp to the same value are equal with equal hashCodes`() {
        val a = SttConfig(serverTryTimeoutMs = 0L)
        val b = SttConfig(serverTryTimeoutMs = 1_000L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val c = SttConfig(serverTryTimeoutMs = 50_000L)
        val d = SttConfig(serverTryTimeoutMs = 10_000L)
        assertEquals(c, d)
        assertEquals(c.hashCode(), d.hashCode())
    }

    @Test
    fun `different windows are not equal`() {
        assertNotEquals(
            SttConfig(serverTryTimeoutMs = 2_000L),
            SttConfig(serverTryTimeoutMs = 3_000L),
        )
        assertNotEquals(SttConfig(), SttConfig(serverTryTimeoutMs = 6_000L))
    }

    @Test
    fun `toString includes the clamped window last`() {
        val s = SttConfig(serverTryTimeoutMs = 7_000L).toString()
        assertTrue(s, s.contains("serverTryTimeoutMs=7000"))
        assertTrue(s, s.endsWith("vocabulary=null, serverTryTimeoutMs=7000)"))

        val clamped = SttConfig(serverTryTimeoutMs = 99_999L).toString()
        assertTrue(clamped, clamped.contains("serverTryTimeoutMs=10000"))
    }

    @Test
    fun `pre-existing call shapes compile and behave as before`() {
        val zero = SttConfig()
        assertEquals("en", zero.language)
        assertEquals(BackendPreference.Auto, zero.prefer)
        assertNull(zero.serverUrl)
        assertNull(zero.corrections)
        assertNull(zero.vocabulary)

        assertEquals("en", SttConfig("en").language)

        val positional = SttConfig("en", BackendPreference.Server, "wss://host.example:8444/", null, null)
        assertEquals(BackendPreference.Server, positional.prefer)
        assertEquals("wss://host.example:8444/", positional.serverUrl)
        assertEquals(5_000L, positional.serverTryTimeoutMs)

        val named = SttConfig(language = "auto", serverUrl = "wss://host.example:8444/")
        assertEquals("auto", named.language)
        assertEquals(BackendPreference.Auto, named.prefer)
        assertEquals(5_000L, named.serverTryTimeoutMs)

        // Positional 6-argument form (the new @JvmOverloads arity Java callers also get).
        val six = SttConfig("en", BackendPreference.Auto, null, null, null, 4_000L)
        assertEquals(4_000L, six.serverTryTimeoutMs)
    }
}

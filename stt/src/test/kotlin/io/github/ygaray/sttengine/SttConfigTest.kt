package io.github.ygaray.sttengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(s, s.endsWith("serverTryTimeoutMs=7000, retainForCorpus=false)"))

        val clamped = SttConfig(serverTryTimeoutMs = 99_999L).toString()
        assertTrue(clamped, clamped.contains("serverTryTimeoutMs=10000"))
    }

    // --- Phase 25 (CORR-06, D-08): the trailing defaulted retainForCorpus ----------------------------

    @Test
    fun `retainForCorpus defaults to false`() {
        assertFalse(SttConfig().retainForCorpus)
        assertFalse(SttConfig(serverUrl = "wss://host.example:8444/").retainForCorpus)
    }

    @Test
    fun `the 7th positional argument sets retainForCorpus`() {
        val seven = SttConfig("en", BackendPreference.Auto, null, null, null, 4_000L, true)
        assertTrue(seven.retainForCorpus)
        assertEquals(4_000L, seven.serverTryTimeoutMs)
        assertTrue(SttConfig(retainForCorpus = true).retainForCorpus)
    }

    @Test
    fun `configs differing only in retainForCorpus are not equal and toString shows the flag last`() {
        val off = SttConfig()
        val on = SttConfig(retainForCorpus = true)
        assertNotEquals(off, on)
        assertNotEquals(off.hashCode(), on.hashCode())
        assertEquals(on, SttConfig(retainForCorpus = true))
        assertEquals(on.hashCode(), SttConfig(retainForCorpus = true).hashCode())

        assertTrue(off.toString(), off.toString().endsWith("retainForCorpus=false)"))
        assertTrue(on.toString(), on.toString().endsWith("retainForCorpus=true)"))
    }

    @Test
    fun `SttConfig keeps a public constructor of every arity 0 through 7`() {
        // Guards the @JvmOverloads binary shape: dropping the annotation, or adding the 7th parameter
        // without it, would silently remove the in-between arities Java and old callers link against.
        val arities = SttConfig::class.java.constructors
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.parameterCount }
            .toSet()
        for (n in 0..7) {
            assertTrue("missing a public constructor of arity $n; have $arities", n in arities)
        }
    }

    @Test
    fun `SttConfig is still a plain class, never a data class`() {
        // A data class generates copy() and componentN(); a plain class has neither.
        val names = SttConfig::class.java.methods.map { it.name }
        assertFalse("copy() would make SttConfig a data class", "copy" in names)
        assertFalse("component1() would make SttConfig a data class", "component1" in names)
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

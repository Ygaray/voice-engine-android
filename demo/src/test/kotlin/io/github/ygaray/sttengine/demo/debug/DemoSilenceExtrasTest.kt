package io.github.ygaray.sttengine.demo.debug

import io.github.ygaray.sttengine.debug.DebugSilenceExtras
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral coverage for the Phase 26 (D-01) START extra `SILENCE_EXTRAS`: the pure bounded parser
 * [parseSilenceExtras] and the dump helper [silenceExtrasDiagnostics].
 *
 * Plain JUnit, exactly like [DemoServerPathTest]: every function under test is a pure top-level
 * helper, so no Context, no Robolectric and no live view model are needed. The Context-bound wiring
 * (clear on every START without the extra, clear on STOP) is asserted by a source-level check in the
 * plan's acceptance criteria and exercised on the device by the spike driver's `finish`.
 */
class DemoSilenceExtrasTest {

    private val remove = DebugSilenceExtras.REMOVE

    @After
    fun clearTheProcessGlobalCell() {
        DebugSilenceExtras.clear()
    }

    private fun assertSet(raw: String, possibly: Int?, complete: Int?, minimum: Int?) {
        val request = parseSilenceExtras(raw)
        assertTrue("expected Set for \"$raw\" but got $request", request is SilenceExtrasRequest.Set)
        request as SilenceExtrasRequest.Set
        assertEquals("possibly of \"$raw\"", possibly, request.possibly)
        assertEquals("complete of \"$raw\"", complete, request.complete)
        assertEquals("minimum of \"$raw\"", minimum, request.minimum)
    }

    private fun assertRefused(raw: String): String {
        val request = parseSilenceExtras(raw)
        assertTrue("expected Refused for \"$raw\" but got $request", request is SilenceExtrasRequest.Refused)
        return (request as SilenceExtrasRequest.Refused).reason
    }

    // --- accepted forms -----------------------------------------------------------------------------

    @Test
    fun `null means no silence extras`() {
        assertEquals(SilenceExtrasRequest.None, parseSilenceExtras(null))
    }

    @Test
    fun `a blank string means no silence extras`() {
        assertEquals(SilenceExtrasRequest.None, parseSilenceExtras("  "))
        assertEquals(SilenceExtrasRequest.None, parseSilenceExtras(""))
    }

    @Test
    fun `a single pair sets that extra and leaves the others alone`() {
        assertSet("possibly=1000", possibly = 1000, complete = null, minimum = null)
        assertSet("complete=2500", possibly = null, complete = 2500, minimum = null)
        assertSet("minimum=300", possibly = null, complete = null, minimum = 300)
    }

    @Test
    fun `all three pairs set all three extras in any order`() {
        assertSet("possibly=800,complete=1500,minimum=500", possibly = 800, complete = 1500, minimum = 500)
        assertSet("minimum=500,possibly=800,complete=1500", possibly = 800, complete = 1500, minimum = 500)
    }

    @Test
    fun `none maps to the REMOVE sentinel`() {
        assertSet("possibly=none", possibly = remove, complete = null, minimum = null)
        assertSet("possibly=none,complete=none,minimum=none", possibly = remove, complete = remove, minimum = remove)
    }

    @Test
    fun `whitespace around pairs is tolerated`() {
        assertSet("  possibly = 800 ,  complete=1500  ", possibly = 800, complete = 1500, minimum = null)
    }

    @Test
    fun `the inclusive bounds 1 and 60000 are accepted`() {
        assertSet("possibly=1,complete=60000", possibly = 1, complete = 60_000, minimum = null)
    }

    // --- refusals -----------------------------------------------------------------------------------

    @Test
    fun `an unknown key is refused without echoing it`() {
        val reason = assertRefused("sneaky=100")
        assertFalse("the reason must not echo the raw key", reason.contains("sneaky"))
        assertTrue(reason.contains("possibly"))
    }

    @Test
    fun `a duplicate key is refused`() {
        val reason = assertRefused("possibly=100,possibly=200")
        assertTrue(reason.contains("possibly"))
    }

    @Test
    fun `a pair with no equals sign is refused`() {
        assertRefused("possibly")
        assertRefused("possibly=100,complete")
    }

    @Test
    fun `an empty value is refused`() {
        val reason = assertRefused("possibly=")
        assertTrue(reason.contains("possibly"))
    }

    @Test
    fun `a non-numeric value is refused without echoing it`() {
        val reason = assertRefused("possibly=abc123xyz")
        assertFalse("the reason must not echo the raw value", reason.contains("abc123xyz"))
        assertTrue(reason.contains("possibly"))
    }

    @Test
    fun `a none-prefixed value like none5 is refused`() {
        assertRefused("possibly=none5")
    }

    @Test
    fun `a signed or decimal value is refused`() {
        assertRefused("possibly=-5")
        assertRefused("possibly=+5")
        assertRefused("possibly=1.5")
    }

    @Test
    fun `an integer below 1 or above 60000 is refused`() {
        assertRefused("possibly=0")
        assertRefused("complete=60001")
        assertRefused("minimum=99999999999999999999")
    }

    @Test
    fun `more than three pairs is refused`() {
        assertRefused("possibly=1,complete=2,minimum=3,possibly=4")
    }

    @Test
    fun `an empty pair from a stray comma is refused`() {
        assertRefused("possibly=100,")
        assertRefused(",possibly=100")
    }

    @Test
    fun `a raw string longer than 64 characters is refused without echoing it`() {
        val longValue = "possibly=" + "9".repeat(60) // 69 characters
        assertTrue(longValue.length > 64)
        val reason = assertRefused(longValue)
        assertFalse(reason.contains("9999"))
    }

    @Test
    fun `a 64 character raw string is not refused for length alone`() {
        // "possibly=" (9) + 55 spaces of padding = 64 characters, then a valid value squeezed in.
        val raw = "possibly=1" + " ".repeat(54)
        assertEquals(64, raw.length)
        assertSet(raw, possibly = 1, complete = null, minimum = null)
    }

    // --- the dump echo ------------------------------------------------------------------------------

    @Test
    fun `the dump helper writes exactly one key equal to describe verbatim`() {
        DebugSilenceExtras.configure(possiblyMs = 800, completeMs = remove)
        val described = DebugSilenceExtras.describe()

        val into = JSONObject()
        silenceExtrasDiagnostics(into, described)

        assertEquals(1, into.length())
        assertEquals(described, into.getString("silenceExtras"))
        assertEquals("silence-extras=possibly:800,complete:remove,minimum:unchanged", into.getString("silenceExtras"))
    }

    @Test
    fun `the dump helper echoes none when nothing is set`() {
        DebugSilenceExtras.clear()
        val into = JSONObject()
        silenceExtrasDiagnostics(into, DebugSilenceExtras.describe())
        assertEquals("silence-extras=none", into.getString("silenceExtras"))
    }
}

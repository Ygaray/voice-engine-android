package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.internal.DebugSilenceExtrasHolder
import io.github.ygaray.sttengine.internal.SilenceExtrasOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The debug-only writer [DebugSilenceExtras], tested where it can be referenced: this is the
 * debug-variant unit-test source set (`src/testDebug`), the only place a test can see a class that
 * `compileReleaseKotlin` never compiles. The engine-level behaviour of the cell it writes is in
 * `SilenceExtrasSeamEngineTest`; the proof that this class is absent from release is
 * `ReleaseSilenceExtrasSeamTest`.
 *
 * `describe()` is a fixed grammar because the PAUSE-03 scorer (plan 26-08) and the spike driver parse
 * the dump's `silenceExtras` echo, and a drifting format would turn every provenance check into
 * UNVERIFIED. These tests therefore assert the literal strings, not substrings.
 */
class DebugSilenceExtrasTest {

    private var saved: SilenceExtrasOverride? = null

    @Before
    fun saveHolder() {
        saved = DebugSilenceExtrasHolder.overrides
        DebugSilenceExtrasHolder.overrides = null
    }

    @After
    fun restoreHolder() {
        DebugSilenceExtrasHolder.overrides = saved
    }

    @Test
    fun describeIsExactlyNoneWhenNothingIsSet() {
        assertEquals("silence-extras=none", DebugSilenceExtras.describe())
    }

    @Test
    fun configureWritesTheHolderAndReturnsTheDescription() {
        val described = DebugSilenceExtras.configure(possiblyMs = 800, completeMs = 1500, minimumMs = 500)

        val written = DebugSilenceExtrasHolder.overrides
        assertNotNull("configure must write the holder", written)
        assertEquals(800, written!!.possibly)
        assertEquals(1500, written.complete)
        assertEquals(500, written.minimum)
        assertEquals("silence-extras=possibly:800,complete:1500,minimum:500", described)
        assertEquals(described, DebugSilenceExtras.describe())
    }

    @Test
    fun describeNamesUnchangedForNullFieldsAndRemoveForTheSentinel() {
        DebugSilenceExtras.configure(possiblyMs = DebugSilenceExtras.REMOVE)
        assertEquals("silence-extras=possibly:remove,complete:unchanged,minimum:unchanged", DebugSilenceExtras.describe())

        DebugSilenceExtras.configure(completeMs = 1500)
        assertEquals("silence-extras=possibly:unchanged,complete:1500,minimum:unchanged", DebugSilenceExtras.describe())

        DebugSilenceExtras.configure(minimumMs = 700)
        assertEquals("silence-extras=possibly:unchanged,complete:unchanged,minimum:700", DebugSilenceExtras.describe())
    }

    @Test
    fun theReExportedSentinelIsTheHoldersSentinel() {
        assertEquals(DebugSilenceExtrasHolder.REMOVE, DebugSilenceExtras.REMOVE)
    }

    @Test
    fun clearResetsTheHolderToNullAndIsIdempotent() {
        DebugSilenceExtras.configure(possiblyMs = 900)
        assertNotNull(DebugSilenceExtrasHolder.overrides)

        DebugSilenceExtras.clear()
        assertNull(DebugSilenceExtrasHolder.overrides)
        assertEquals("silence-extras=none", DebugSilenceExtras.describe())

        DebugSilenceExtras.clear() // safe when nothing is set
        assertNull(DebugSilenceExtrasHolder.overrides)
    }

    @Test
    fun configureAcceptsTheInclusiveBoundaries() {
        DebugSilenceExtras.configure(possiblyMs = 1, completeMs = 60_000)
        assertEquals("silence-extras=possibly:1,complete:60000,minimum:unchanged", DebugSilenceExtras.describe())
    }

    @Test
    fun configureIsLoudOnAValueOutsideTheAllowedRange() {
        for (bad in listOf(0, -2, 60_001, Int.MAX_VALUE)) {
            try {
                DebugSilenceExtras.configure(possiblyMs = bad)
                fail("a value of $bad must be refused loudly")
            } catch (e: IllegalArgumentException) {
                assertTrue("the message must state the allowed range: ${e.message}", e.message!!.contains("1..60000"))
            }
            assertNull("a refused call must not write the holder", DebugSilenceExtrasHolder.overrides)
        }
    }

    @Test
    fun configureIsLoudOnEachFieldNotJustTheFirst() {
        for (call in listOf<() -> Unit>(
            { DebugSilenceExtras.configure(completeMs = 60_001) },
            { DebugSilenceExtras.configure(minimumMs = 0) },
            { DebugSilenceExtras.configure(possiblyMs = 800, completeMs = 1500, minimumMs = 70_000) },
        )) {
            try {
                call()
                fail("an out-of-range field must be refused")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            assertNull(DebugSilenceExtrasHolder.overrides)
        }
    }

    @Test
    fun configureIsLoudWhenItConfiguresNothing() {
        try {
            DebugSilenceExtras.configure()
            fail("a call that configures nothing must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
        assertNull(DebugSilenceExtrasHolder.overrides)
    }
}

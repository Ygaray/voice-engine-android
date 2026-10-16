package io.github.ygaray.sttengine.internal

import android.content.Intent
import android.speech.RecognizerIntent
import io.github.ygaray.sttengine.EngineHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-01: the debug silence-extras override reaches the intent the REAL engine hands to the
 * recognizer, exactly as requested and only for the requested extras.
 *
 * ## Why these set the holder directly
 *
 * `src/test` compiles against both the debug and the release variant, and the debug writer
 * (`DebugSilenceExtras`) exists only in the debug one. The writer's own behaviour is therefore in
 * `DebugSilenceExtrasTest` (the `testDebug` source set); here the internal cell is assigned directly,
 * which is the same cell the writer assigns.
 *
 * ## Tune-dependent vs tune-independent
 *
 * Exactly two tests in this file are PRE-TUNE facts about today's engine (their names end in
 * `PreTune`): they are true only while the engine passes no commit-silence value to
 * `RecognizerIntents.forRung`. Plan 26-05 makes the engine pass one and edits exactly those two
 * deliberately. Every other test compares relatively or asserts only what an override REPLACES, so it
 * passes both before and after the tune.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SilenceExtrasSeamEngineTest {

    private val complete = RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
    private val possibly = RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS
    private val minimum = RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS

    @Before
    fun clearHolders() {
        DebugSilenceExtrasHolder.overrides = null
        DebugRungCeilingHolder.ceiling = null
    }

    /** Resets BOTH cells, including after a failed or aborted test body. */
    @After
    fun resetHolders() {
        DebugSilenceExtrasHolder.overrides = null
        DebugRungCeilingHolder.ceiling = null
    }

    /** One engine, one session: the first intent handed to the recognizer under [override]/[ceiling]. */
    private suspend fun TestScope.firstIntent(
        override: SilenceExtrasOverride?,
        ceiling: Rung? = null,
    ): Intent {
        DebugSilenceExtrasHolder.overrides = override
        DebugRungCeilingHolder.ceiling = ceiling
        val h = EngineHarness(testScheduler, supportsSegmented = true, supportsRawAudio = true)
        h.collectInto(backgroundScope)
        h.reachListening()
        return h.fake.startedIntents[0]
    }

    /**
     * Every extra of [intent] as key to value, with the pipe descriptor (a distinct live object per
     * session) reduced to a marker so two sessions through the same harness shape compare equal.
     */
    private fun snapshot(intent: Intent): Map<String, Any?> {
        val extras = intent.extras
        assertNotNull("the intent must carry extras", extras)
        return extras!!.keySet().sorted().associateWith { key ->
            if (key == RecognizerIntent.EXTRA_AUDIO_SOURCE) "<descriptor>" else extras.get(key)
        }
    }

    // --- PRE-TUNE facts about today's engine (plan 26-05 edits exactly these two) ------------------

    /** Pre-tune fact about today's engine; plan 26-05 updates it deliberately once the tune ships. */
    @Test
    fun noOverrideRungCIntentCarriesNoSilenceExtrasPreTune() = runTest {
        val intent = firstIntent(override = null)

        assertTrue("it really is rung (c)", intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertFalse(intent.hasExtra(complete))
        assertFalse(intent.hasExtra(possibly))
        assertFalse(intent.hasExtra(minimum))
    }

    /** Pre-tune fact about today's engine; plan 26-05 updates it deliberately once the tune ships. */
    @Test
    fun completeAndMinimumOverrideOnRungCLeavesPossiblyAbsentPreTune() = runTest {
        val intent = firstIntent(SilenceExtrasOverride(possibly = null, complete = 1500, minimum = 700))

        assertEquals(1500, intent.getIntExtra(complete, -1))
        assertEquals(700, intent.getIntExtra(minimum, -1))
        assertFalse("a null field leaves the builder's value alone, and today the builder sets none", intent.hasExtra(possibly))
    }

    // --- tune-independent: an explicit override replaces whatever the builder set -------------------

    @Test
    fun possiblyOverrideOnRungCReadsTheRequestedValueAndKeepsTheAudioSourceExtras() = runTest {
        val intent = firstIntent(SilenceExtrasOverride(possibly = 900, complete = null, minimum = null))

        assertEquals(900, intent.getIntExtra(possibly, -1))
        assertTrue("it really is rung (c)", intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertEquals(
            RecognizerIntent.EXTRA_AUDIO_SOURCE,
            intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
        )
    }

    @Test
    fun allThreeOverridesReachTheRungCIntentExactly() = runTest {
        val intent = firstIntent(SilenceExtrasOverride(possibly = 800, complete = 1500, minimum = 500))

        assertEquals(800, intent.getIntExtra(possibly, -1))
        assertEquals(1500, intent.getIntExtra(complete, -1))
        assertEquals(500, intent.getIntExtra(minimum, -1))
    }

    @Test
    fun removePossiblyOnForcedRungALeavesCompleteAlone() = runTest {
        val intent = firstIntent(
            SilenceExtrasOverride(possibly = DebugSilenceExtrasHolder.REMOVE, complete = null, minimum = null),
            ceiling = Rung.RestartingSingleSession,
        )

        assertFalse("REMOVE deletes exactly the requested extra", intent.hasExtra(possibly))
        assertEquals("the remove request touches one extra only", 2_500, intent.getIntExtra(complete, -1))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
    }

    @Test
    fun possiblyOverrideOnForcedRungBLeavesTheSessionBoundAlone() = runTest {
        val intent = firstIntent(
            SilenceExtrasOverride(possibly = 1_000, complete = null, minimum = null),
            ceiling = Rung.SilenceSegmented,
        )

        assertEquals(1_000, intent.getIntExtra(possibly, -1))
        assertEquals("the session bound is not touched by a POSSIBLY override", 8_000, intent.getIntExtra(complete, -1))
        assertTrue(intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
    }

    @Test
    fun forcedRungsAAndBKeepTheirUnrelatedSilenceExtrasUnderAMinimumOnlyOverride() = runTest {
        val a = firstIntent(
            SilenceExtrasOverride(possibly = null, complete = null, minimum = 600),
            ceiling = Rung.RestartingSingleSession,
        )
        assertEquals(600, a.getIntExtra(minimum, -1))
        assertEquals(2_500, a.getIntExtra(complete, -1))
        assertEquals(1_800, a.getIntExtra(possibly, -1))

        val b = firstIntent(
            SilenceExtrasOverride(possibly = null, complete = null, minimum = 600),
            ceiling = Rung.SilenceSegmented,
        )
        assertEquals(600, b.getIntExtra(minimum, -1))
        assertEquals(8_000, b.getIntExtra(complete, -1))
        assertEquals(1_800, b.getIntExtra(possibly, -1))
    }

    // --- tune-independent invariants (relative comparisons, never a literal absence) ----------------

    @Test
    fun nullFieldOverrideEqualsNoOverrideOnEveryRung() = runTest {
        for (ceiling in listOf<Rung?>(null, Rung.SilenceSegmented, Rung.RestartingSingleSession)) {
            val without = snapshot(firstIntent(override = null, ceiling = ceiling))
            val withNulls = snapshot(
                firstIntent(SilenceExtrasOverride(possibly = null, complete = null, minimum = null), ceiling = ceiling),
            )
            assertEquals(
                "an all-null override must preserve whatever the builder produced (ceiling=$ceiling)",
                without,
                withNulls,
            )
        }
    }

    @Test
    fun clearedOverrideRestoresTheNoOverrideIntent() = runTest {
        val never = snapshot(firstIntent(override = null))

        val overridden = snapshot(firstIntent(SilenceExtrasOverride(possibly = 900, complete = 1500, minimum = 700)))
        assertFalse("the override must actually have changed the intent", never == overridden)

        DebugSilenceExtrasHolder.overrides = null
        val cleared = snapshot(firstIntent(override = null))
        assertEquals("clearing the cell restores a never-overridden session's intent", never, cleared)
    }

    @Test
    fun aRestartReappliesTheOverrideBecauseItIsAppliedInsideStartSession() = runTest {
        DebugSilenceExtrasHolder.overrides = SilenceExtrasOverride(possibly = 900, complete = null, minimum = null)
        // Rung (b): a segmented session ends on the segmented-end callback, which restarts the session.
        val h = EngineHarness(testScheduler, supportsSegmented = true)
        h.collectInto(backgroundScope)
        h.reachListening()
        h.fake.fireSegmentResults("the morning light")
        h.fake.fireEndOfSegmentedSession()
        advanceUntilIdle()

        assertTrue("the run must have restarted", h.fake.startedIntents.size >= 2)
        for ((index, intent) in h.fake.startedIntents.withIndex()) {
            assertEquals("session $index carries the override", 900, intent.getIntExtra(possibly, -1))
        }
    }
}

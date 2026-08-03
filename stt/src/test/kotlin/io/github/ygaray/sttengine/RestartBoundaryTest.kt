package io.github.ygaray.sttengine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The restart boundary — the one place AND-02 can be lost, and it can be lost in **two opposite
 * directions at once**.
 *
 * Plan 05 proved the two mechanisms in isolation (`SegmentAccumulatorTest`, `SessionEpochTest`).
 * This suite proves the *engine* wires them in the order that makes both hold simultaneously:
 *
 * - commit the live partial **before** teardown, or the words spoken since the last final vanish,
 *   because teardown is precisely what causes the final that never arrives;
 * - advance the epoch **after** teardown, or the dying session's late callback re-emits the same
 *   words under a new segment id.
 *
 * Get the order wrong and exactly one of these two tests fails — which is the point of having both.
 *
 * On the phase's tester the loss direction is not hypothetical: `onResults` delivers a Bundle with
 * **zero keys** there (`05-RUNG-VERDICT.md`), so the last non-blank partial is the *only* source of
 * committed text on rung (a), the ladder's terminal floor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class RestartBoundaryTest {

    @Test
    fun `a forced teardown with no final delivered still commits the last partial exactly once`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.firePartial("the morning")
            h.fake.firePartial("the morning light")
            runCurrent()
            assertTrue("nothing is committed until a boundary", h.finals.isEmpty())

            // ERROR_NO_MATCH ends the session without any final — the ordinary end of an utterance,
            // and the exact shape of "the final that never arrives".
            h.fake.fireError(ERROR_NO_MATCH)
            advanceUntilIdle()

            assertEquals(listOf(FinalSegment("the morning light", 0)), h.finals.toList())
            assertEquals("", h.engine.partial.value)
        }

    @Test
    fun `the engine's real onResults path commits a blank platform final as the last partial`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.firePartial("the morning light")
            runCurrent()
            // The MEASURED shape of onResults on the tester device: a Bundle carrying zero keys.
            h.fake.fireEmptyResultsBundle()
            advanceUntilIdle()

            assertEquals(listOf(FinalSegment("the morning light", 0)), h.finals.toList())
        }

    @Test
    fun `a late final from the retired epoch after a restart yields no second final`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()
        val dyingListener = h.listenerFor(0)

        h.fake.firePartial("the morning light")
        runCurrent()
        h.fake.fireError(ERROR_NO_MATCH)
        advanceUntilIdle()
        assertEquals(1, h.finals.size)

        // The dying session keeps firing at the listener it was given. Its epoch is retired, so the
        // fence must reject every one of these before they touch a single field.
        h.fake.fireResults("the morning light", target = dyingListener)
        h.fake.firePartial("the morning light again", target = dyingListener)
        h.fake.fireError(ERROR_NO_MATCH, target = dyingListener)
        advanceUntilIdle()

        assertEquals("a retired session may not re-emit its words", 1, h.finals.size)
        assertEquals("", h.engine.partial.value)
    }

    @Test
    fun `a restart re-registers a listener bound to the new epoch and starts a second session`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireError(ERROR_NO_MATCH)
            advanceUntilIdle()

            assertEquals(2, h.fake.startCount)
            assertEquals(2, h.fake.setListenerCount)
            assertTrue(
                "the new session's listener must be a different object, bound to the new epoch",
                h.listenerFor(0) !== h.listenerFor(1),
            )
            assertEquals(
                "the ordinary restart reuses the recognizer — only a rebind rebuilds it",
                1,
                h.handleCreateCount,
            )
        }

    @Test
    fun `a full restart cycle produces neither duplicated nor dropped text`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("first utterance")
        runCurrent()
        h.fake.fireError(ERROR_NO_MATCH)
        advanceUntilIdle()
        h.fake.reachListening()

        h.fake.firePartial("second utterance")
        runCurrent()
        h.fake.fireError(ERROR_NO_MATCH)
        advanceUntilIdle()

        assertEquals(
            listOf(
                FinalSegment("first utterance", 0),
                FinalSegment("second utterance", 1),
            ),
            h.finals.toList(),
        )
    }

    private companion object {
        /** `ERROR_NO_MATCH`, as a raw Binder-delivered integer. */
        const val ERROR_NO_MATCH = 7
    }
}

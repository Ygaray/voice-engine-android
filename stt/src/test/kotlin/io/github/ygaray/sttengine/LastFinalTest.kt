package io.github.ygaray.sttengine

import android.os.Bundle
import android.speech.SpeechRecognizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LAST-01 (D-05, D-15): [SttEngine.lastFinal] — the final the most recent `stop()` flushed, readable
 * synchronously the moment `stop()` returns, with no `finals` collector kept alive across the stop.
 *
 * The native half drives a real `SttEngineImpl` through [EngineHarness]. Every "same final" assertion
 * is `assertSame`, not `assertEquals`: D-05 requires `lastFinal` to be the SAME instance `finals`
 * delivers, written at the emit-origin point, never a reconstruction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class LastFinalTest {

    // --- native (SttEngineImpl) -------------------------------------------------------------------

    @Test
    fun `native - lastFinal is null after reaching listening, before any final`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        assertNull(h.engine.lastFinal)
    }

    @Test
    fun `native - a mid-session commit is the same instance finals delivered`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("first thought")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()

        assertEquals(1, h.finals.size)
        assertSame(h.finals.last(), h.engine.lastFinal)
    }

    @Test
    fun `native - immediately after stop returns, lastFinal is the tail stop flushed, with its id and language`() =
        runTest {
            val h = EngineHarness(testScheduler, config = SttConfig(language = "auto"))
            h.collectInto(backgroundScope)
            h.reachListening()

            // One committed segment first, so a stale "previous segment" answer would be wrong.
            h.fake.fireSegmentResults("primera parte")
            advanceUntilIdle()
            val earlier = h.engine.lastFinal

            h.fake.fireLanguageDetection(highlyConfidentDetection("es-US"))
            h.fake.firePartial("y la cola abierta")
            runCurrent()

            h.engine.stop()
            // Read BEFORE any scheduler advance — this is the host's "read right after stop" race.
            val tail = h.engine.lastFinal

            assertNotNull(tail)
            assertEquals("y la cola abierta", tail!!.text)
            assertEquals("es", tail.language)
            assertEquals(earlier!!.segmentId + 1, tail.segmentId)

            advanceUntilIdle()
            assertSame("lastFinal is the very instance finals delivered", h.finals.last(), tail)
        }

    @Test
    fun `native - a new start resets lastFinal to null until that session's first final`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("previous session words")
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()
        assertNotNull(h.engine.lastFinal)

        h.reachListening()
        assertNull("a reused engine must never report a previous session's final", h.engine.lastFinal)

        h.fake.firePartial("new session words")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()

        assertEquals("new session words", h.engine.lastFinal?.text)
        assertSame(h.finals.last(), h.engine.lastFinal)
    }

    @Test
    fun `native - a terminal error does not clear lastFinal`() = runTest {
        val h = EngineHarness(testScheduler)
        h.collectInto(backgroundScope)
        h.reachListening()

        h.fake.firePartial("kept across the failure")
        runCurrent()
        h.fake.fireEmptyResultsBundle()
        advanceUntilIdle()
        val committed = h.engine.lastFinal
        assertNotNull(committed)

        h.fake.fireError(ERROR_LANGUAGE_NOT_SUPPORTED)
        advanceUntilIdle()

        assertEquals(EngineState.Error, h.engine.state.value)
        assertSame(committed, h.engine.lastFinal)
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun highlyConfidentDetection(detectedLanguage: String): Bundle = Bundle().apply {
        putString(SpeechRecognizer.DETECTED_LANGUAGE, detectedLanguage)
        putInt(
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
            SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT,
        )
    }

    private companion object {
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    }
}

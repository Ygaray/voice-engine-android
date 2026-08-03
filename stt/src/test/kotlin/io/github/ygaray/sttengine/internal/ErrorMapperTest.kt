package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.SttErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every failure an OEM recognizer can produce, enumerated.
 *
 * Translated from `web/tests/errors.test.ts`, which enumerates its taxonomy case by case rather
 * than sampling it. The table under test is D-18 (`05-CONTEXT.md:186-211`), whose API levels were
 * independently re-verified against the local SDK's `api-versions.xml` (05-RESEARCH §Finding D).
 *
 * Pure JVM — no Robolectric, no `Bundle`, no device. That is the point of `ErrorMapper` being a
 * pure function of an `Int` and a small context object: the most safety-critical table in the
 * library is also the cheapest thing in it to prove.
 */
class ErrorMapperTest {

    private fun ctx(
        selfInflictedTeardown: Boolean = false,
        audioErrorAlreadyOccurred: Boolean = false,
        restartBudgetRemaining: Boolean = true,
    ) = ErrorContext(selfInflictedTeardown, audioErrorAlreadyOccurred, restartBudgetRemaining)

    private fun fatal(raw: Int, context: ErrorContext = ctx()): Disposition.Fatal {
        val result = ErrorMapper.map(raw, context)
        assertTrue("expected Fatal for $raw, got $result", result is Disposition.Fatal)
        return result as Disposition.Fatal
    }

    // -------------------------------------------------------------------------------------------
    // The 15 documented integers, one test each
    // -------------------------------------------------------------------------------------------

    @Test
    fun `1 ERROR_NETWORK_TIMEOUT is fatal server-unreachable -- loud, because it proves we are not on-device`() {
        assertEquals(SttErrorCode.SERVER_UNREACHABLE, fatal(1).error.code)
    }

    @Test
    fun `2 ERROR_NETWORK is fatal server-unreachable -- the same signal`() {
        assertEquals(SttErrorCode.SERVER_UNREACHABLE, fatal(2).error.code)
    }

    @Test
    fun `3 ERROR_AUDIO restarts once, then is fatal audio-error`() {
        assertEquals(Disposition.RestartOnce, ErrorMapper.map(3, ctx(audioErrorAlreadyOccurred = false)))
        assertEquals(SttErrorCode.AUDIO_ERROR, fatal(3, ctx(audioErrorAlreadyOccurred = true)).error.code)
    }

    @Test
    fun `4 ERROR_SERVER is fatal internal-error`() {
        assertEquals(SttErrorCode.INTERNAL_ERROR, fatal(4).error.code)
    }

    @Test
    fun `5 ERROR_CLIENT is swallowed when self-inflicted and fatal internal-error otherwise`() {
        // Our own cancel()/destroy() provokes this. Treating it as an error would turn every normal
        // restart into a user-visible failure.
        assertEquals(Disposition.Swallow, ErrorMapper.map(5, ctx(selfInflictedTeardown = true)))
        assertEquals(SttErrorCode.INTERNAL_ERROR, fatal(5, ctx(selfInflictedTeardown = false)).error.code)
    }

    @Test
    fun `6 ERROR_SPEECH_TIMEOUT is swallowed and restarted`() {
        assertEquals(Disposition.SwallowAndRestart, ErrorMapper.map(6, ctx()))
    }

    @Test
    fun `7 ERROR_NO_MATCH is swallowed and restarted`() {
        // The single most common terminal in the device probe's long-silence run. If this were
        // fatal, ordinary dictation would end itself on the first unrecognised pause.
        assertEquals(Disposition.SwallowAndRestart, ErrorMapper.map(7, ctx()))
    }

    @Test
    fun `8 ERROR_RECOGNIZER_BUSY is fatal busy`() {
        assertEquals(SttErrorCode.BUSY, fatal(8).error.code)
    }

    @Test
    fun `8 ERROR_RECOGNIZER_BUSY sits OFF the retry budget`() {
        // Phase 3 D-04: busy is terminal and does not consume the reconnect budget. In a PURE
        // mapper the way to assert "the budget was not decremented" is to prove the mapper never
        // CONSULTS it: identical output for both budget states means no caller can be told to
        // spend anything, and it can never degrade into reconnect-exhausted.
        val withBudget = ErrorMapper.map(8, ctx(restartBudgetRemaining = true))
        val withoutBudget = ErrorMapper.map(8, ctx(restartBudgetRemaining = false))

        assertEquals(withBudget, withoutBudget)
        assertEquals(SttErrorCode.BUSY, (withoutBudget as Disposition.Fatal).error.code)
        assertNotEquals(SttErrorCode.RECONNECT_EXHAUSTED, withoutBudget.error.code)
    }

    @Test
    fun `9 ERROR_INSUFFICIENT_PERMISSIONS is fatal mic-denied`() {
        assertEquals(SttErrorCode.MIC_DENIED, fatal(9).error.code)
    }

    @Test
    fun `10 ERROR_TOO_MANY_REQUESTS backs off on budget and is fatal reconnect-exhausted when spent`() {
        assertEquals(Disposition.BackoffAndRestart, ErrorMapper.map(10, ctx(restartBudgetRemaining = true)))
        assertEquals(
            SttErrorCode.RECONNECT_EXHAUSTED,
            fatal(10, ctx(restartBudgetRemaining = false)).error.code,
        )
    }

    @Test
    fun `11 ERROR_SERVER_DISCONNECTED rebinds on budget and is fatal server-unreachable when spent`() {
        assertEquals(Disposition.RebindAndRestart, ErrorMapper.map(11, ctx(restartBudgetRemaining = true)))
        assertEquals(
            SttErrorCode.SERVER_UNREACHABLE,
            fatal(11, ctx(restartBudgetRemaining = false)).error.code,
        )
    }

    @Test
    fun `12 ERROR_LANGUAGE_NOT_SUPPORTED is fatal unsupported-language`() {
        assertEquals(SttErrorCode.UNSUPPORTED_LANGUAGE, fatal(12).error.code)
    }

    @Test
    fun `13 ERROR_LANGUAGE_UNAVAILABLE is fatal no-offline-model, NOT unsupported-language`() {
        // The distinction is the whole reason D-14 added a code. unsupported-language tells a host
        // to give up; no-offline-model tells it to trigger a download. Coercing 13 into 12 would be
        // the silent-wrong-code failure the project's loud-failures rule exists to forbid.
        val error = fatal(13).error
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, error.code)
        assertNotEquals(SttErrorCode.UNSUPPORTED_LANGUAGE, error.code)
    }

    @Test
    fun `14 ERROR_CANNOT_CHECK_SUPPORT is swallowed -- never block dictation`() {
        assertEquals(Disposition.Swallow, ErrorMapper.map(14, ctx()))
    }

    @Test
    fun `15 ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS is swallowed -- the download may still succeed`() {
        assertEquals(Disposition.Swallow, ErrorMapper.map(15, ctx()))
    }

    // -------------------------------------------------------------------------------------------
    // Outside the documented set — threat T-05-02
    // -------------------------------------------------------------------------------------------

    @Test
    fun `an integer outside the documented set is fatal internal-error and keeps the raw value`() {
        // RecognitionListener.onError's own javadoc requires handling values outside its documented
        // set. 0 is included deliberately: the ERROR_* constants start at 1, so 0 is undocumented
        // however the range is phrased.
        listOf(0, -1, 16, 99, Int.MAX_VALUE, Int.MIN_VALUE).forEach { raw ->
            val error = fatal(raw).error
            assertEquals("raw $raw", SttErrorCode.INTERNAL_ERROR, error.code)
            assertEquals("raw $raw", raw, error.platformDetail)
        }
    }

    @Test
    fun `an undocumented integer cannot crash the mapper under any context`() {
        val contexts = listOf(
            ctx(),
            ctx(selfInflictedTeardown = true),
            ctx(audioErrorAlreadyOccurred = true),
            ctx(restartBudgetRemaining = false),
        )
        (-5..25).forEach { raw ->
            contexts.forEach { context ->
                // The assertion is that this line returns at all for every combination.
                ErrorMapper.map(raw, context)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Invariants that must hold for EVERY fatal result
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every fatal result carries a non-blank message and the raw integer in platformDetail`() {
        val contexts = listOf(
            ctx(),
            ctx(selfInflictedTeardown = true),
            ctx(audioErrorAlreadyOccurred = true),
            ctx(restartBudgetRemaining = false),
            ctx(selfInflictedTeardown = true, audioErrorAlreadyOccurred = true, restartBudgetRemaining = false),
        )
        (-2..17).forEach { raw ->
            contexts.forEach { context ->
                val result = ErrorMapper.map(raw, context)
                if (result is Disposition.Fatal) {
                    assertFalse("blank message for raw $raw", result.error.message.isBlank())
                    assertEquals("platformDetail for raw $raw", raw, result.error.platformDetail)
                }
            }
        }
    }

    @Test
    fun `a fatal message names the raw integer, so a bug report keeps full fidelity`() {
        assertTrue(fatal(4).error.message.contains("4"))
        assertTrue(fatal(99).error.message.contains("99"))
    }

    // -------------------------------------------------------------------------------------------
    // The non-onError failure conditions (D-18's second table) — plan 06's preflight
    // -------------------------------------------------------------------------------------------

    @Test
    fun `an unavailable on-device recognizer maps to recognizer-unavailable`() {
        val error = ErrorMapper.mapPreflight(PreflightFailure.RecognizerUnavailable)
        assertEquals(SttErrorCode.RECOGNIZER_UNAVAILABLE, error.code)
        assertFalse(error.message.isBlank())
        // No platform integer caused this; inventing one would be a lie in every bug report.
        assertNull(error.platformDetail)
    }

    @Test
    fun `a pre-flight RECORD_AUDIO denial maps to mic-denied`() {
        val error = ErrorMapper.mapPreflight(PreflightFailure.RecordAudioDenied)
        assertEquals(SttErrorCode.MIC_DENIED, error.code)
        assertFalse(error.message.isBlank())
        assertNull(error.platformDetail)
    }

    @Test
    fun `a supported but not installed language maps to no-offline-model`() {
        val error = ErrorMapper.mapPreflight(PreflightFailure.LanguageNotInstalled("fr-FR"))
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, error.code)
        assertTrue("the message must name the language: ${error.message}", error.message.contains("fr-FR"))
    }

    @Test
    fun `a pending language download maps to no-offline-model with a DISTINCT message`() {
        val notInstalled = ErrorMapper.mapPreflight(PreflightFailure.LanguageNotInstalled("fr-FR"))
        val pending = ErrorMapper.mapPreflight(PreflightFailure.LanguageDownloadPending("fr-FR"))

        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, pending.code)
        assertNotEquals(
            "the pending case must be distinguishable from the not-installed case by message alone",
            notInstalled.message,
            pending.message,
        )
        assertTrue(
            "the pending message must say a download is already scheduled: ${pending.message}",
            pending.message.contains("scheduled") || pending.message.contains("already"),
        )
        assertTrue(pending.message.contains("fr-FR"))
    }

    @Test
    fun `every preflight code is one this contract actually declares`() {
        val declared = SttErrorCode.SHARED + SttErrorCode.ANDROID_ONLY
        listOf(
            PreflightFailure.RecognizerUnavailable,
            PreflightFailure.RecordAudioDenied,
            PreflightFailure.LanguageNotInstalled("en-US"),
            PreflightFailure.LanguageDownloadPending("en-US"),
        ).forEach { failure ->
            val error = ErrorMapper.mapPreflight(failure)
            assertTrue("${error.code.value} is not in the declared taxonomy", error.code in declared)
        }
    }

    @Test
    fun `every code the onError table can emit is one this contract actually declares`() {
        val declared = SttErrorCode.SHARED + SttErrorCode.ANDROID_ONLY
        (-2..17).forEach { raw ->
            listOf(ctx(), ctx(restartBudgetRemaining = false), ctx(audioErrorAlreadyOccurred = true)).forEach { c ->
                val result = ErrorMapper.map(raw, c)
                if (result is Disposition.Fatal) {
                    assertTrue(
                        "raw $raw produced ${result.error.code.value}, which is not in the taxonomy",
                        result.error.code in declared,
                    )
                }
            }
        }
    }
}

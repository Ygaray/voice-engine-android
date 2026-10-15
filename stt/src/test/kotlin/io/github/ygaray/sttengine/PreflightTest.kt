package io.github.ygaray.sttengine

import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.PlatformRecognizerHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-18's **non-`onError` table**: the failures diagnosed before or instead of a session.
 *
 * ## Why every one of these is forced rather than observed
 *
 * `05-PROBE-REPORT.json` measured the tester's language inventory: `en-US` **installed**, 30 more
 * supported but not installed (`de-DE` among them), and **nothing pending**. So on the only device
 * this phase runs on, `no-offline-model` can never fire naturally — a preflight written and then
 * "verified" against that device would ship an unexercised D-18 row that nobody would discover until
 * a user with a fresh phone hit it, which is the *dominant* first-run failure mode.
 *
 * Hence the availability answer is injected (`PreflightHandle`), and the not-installed case is
 * written with `de-DE` specifically: a locale the device really does support and really has not
 * installed, so the test describes a situation that genuinely exists rather than an invented one.
 *
 * The two `no-offline-model` cases must carry **distinct messages**, because plan 07's error banner
 * has to tell "download it" apart from "a download is already running" — the same code with
 * different advice.
 *
 * ## And the bundles
 *
 * The four degenerate bundle shapes are cross-process input from an OEM-controlled process, so
 * tolerating them is part of the contract (T-05-12), not an edge case. `EMPTY_BUNDLE` in particular
 * is not hypothetical: it is the MEASURED shape of `onResults` on this tester.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PreflightTest {

    // --- the two exception paths -------------------------------------------------------------------

    @Test
    fun `a denied RECORD_AUDIO yields exactly one mic-denied error and no exception`() = runTest {
        val h = EngineHarness(testScheduler, permissionGranted = false)
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.MIC_DENIED, h.errors.single().code)
        assertEquals(
            "no platform integer caused this, so inventing one would put a fiction in a bug report",
            null,
            h.errors.single().platformDetail,
        )
        assertEquals("nothing may be constructed after a denial", 0, h.handleCreateCount)
    }

    @Test
    fun `a factory throwing UnsupportedOperationException yields exactly one recognizer-unavailable`() =
        runTest {
            val h = EngineHarness(testScheduler, factoryThrows = true)
            h.collectInto(backgroundScope)

            h.engine.start()
            advanceUntilIdle()

            assertEquals(1, h.errors.size)
            assertEquals(SttErrorCode.RECOGNIZER_UNAVAILABLE, h.errors.single().code)
            assertTrue(
                "the message must say the engine does not fall back to the network recognizer",
                h.errors.single().message.contains("network-backed"),
            )
        }

    // --- the three language conditions --------------------------------------------------------------

    @Test
    fun `a supported but not installed language yields no-offline-model`() = runTest {
        // de-DE is one of the 30 locales the tester reports as supported-but-not-installed.
        val h = EngineHarness(
            testScheduler,
            languageAvailability = LanguageAvailability.DOWNLOADABLE,
            config = SttConfig(language = "de-DE"),
        )
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
        assertTrue(
            "the message must name the language the host would have to download",
            h.errors.single().message.contains("de-DE"),
        )
        assertTrue(
            "and must point at a download as the recovery",
            h.errors.single().message.contains("download"),
        )
    }

    @Test
    fun `a language already downloading yields no-offline-model with a DISTINCT message`() = runTest {
        val pending = EngineHarness(
            testScheduler,
            languageAvailability = LanguageAvailability.PENDING,
            config = SttConfig(language = "de-DE"),
        )
        pending.collectInto(backgroundScope)
        pending.engine.start()
        advanceUntilIdle()

        val notInstalled = EngineHarness(
            testScheduler,
            languageAvailability = LanguageAvailability.DOWNLOADABLE,
            config = SttConfig(language = "de-DE"),
        )
        notInstalled.collectInto(backgroundScope)
        notInstalled.engine.start()
        advanceUntilIdle()

        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, pending.errors.single().code)
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, notInstalled.errors.single().code)
        assertNotEquals(
            "the banner must be able to tell 'download it' from 'a download is already running'",
            notInstalled.errors.single().message,
            pending.errors.single().message,
        )
        assertTrue(
            pending.errors.single().message.contains("already scheduled"),
        )
    }

    @Test
    fun `an unsupported language yields unsupported-language`() = runTest {
        val h = EngineHarness(
            testScheduler,
            languageAvailability = LanguageAvailability.UNSUPPORTED,
            config = SttConfig(language = "xx-XX"),
        )
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.UNSUPPORTED_LANGUAGE, h.errors.single().code)
        assertTrue(
            "unlike no-offline-model, no download would help — say so",
            h.errors.single().message.contains("no model download"),
        )
    }

    @Test
    fun `an installed language and an undetermined answer both proceed to a session`() = runTest {
        listOf(LanguageAvailability.INSTALLED, LanguageAvailability.UNKNOWN).forEach { answer ->
            val h = EngineHarness(testScheduler, languageAvailability = answer)
            h.collectInto(backgroundScope)
            h.reachListening()

            assertTrue("$answer must not block dictation", h.errors.isEmpty())
            assertEquals(EngineState.Listening, h.engine.state.value)
        }
    }

    // --- NBIL-10: dual-language preflight under "auto" -----------------------------------------------

    @Test
    fun `an auto session with es-US missing fails with no-offline-model naming es-US`() = runTest {
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto"),
            languageAvailabilityOverrides = mapOf(
                "en-US" to LanguageAvailability.INSTALLED,
                "es-US" to LanguageAvailability.DOWNLOADABLE,
            ),
        )
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
        assertTrue(h.errors.single().message.contains("es-US"))
        assertEquals("preflight failed before any session started", 0, h.fake.startCount)
    }

    @Test
    fun `an auto session with en-US missing fails with no-offline-model naming en-US`() = runTest {
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto"),
            languageAvailabilityOverrides = mapOf(
                "en-US" to LanguageAvailability.DOWNLOADABLE,
                "es-US" to LanguageAvailability.INSTALLED,
            ),
        )
        h.collectInto(backgroundScope)

        h.engine.start()
        advanceUntilIdle()

        assertEquals(1, h.errors.size)
        assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
        assertTrue(h.errors.single().message.contains("en-US"))
        assertEquals(0, h.fake.startCount)
    }

    @Test
    fun `an auto session with BOTH languages missing fails with exactly one error naming en-US first`() =
        runTest {
            val h = EngineHarness(
                testScheduler,
                config = SttConfig(language = "auto"),
                languageAvailabilityOverrides = mapOf(
                    "en-US" to LanguageAvailability.DOWNLOADABLE,
                    "es-US" to LanguageAvailability.DOWNLOADABLE,
                ),
            )
            h.collectInto(backgroundScope)

            h.engine.start()
            advanceUntilIdle()

            assertEquals(
                "exactly one error, not a merged/duplicate message for both languages",
                1,
                h.errors.size,
            )
            assertEquals(SttErrorCode.NO_OFFLINE_MODEL, h.errors.single().code)
            assertTrue(
                "en-US is checked first (AUTO_SWITCH_ALLOWED_LANGUAGES' literal order)",
                h.errors.single().message.contains("en-US"),
            )
            assertEquals(0, h.fake.startCount)
        }

    @Test
    fun `a non-auto session and a sub-34 auto session still preflight with exactly one languageAvailability call`() =
        runTest {
            // Non-"auto": regression-proven by the pre-existing de-DE case above still passing
            // unmodified. Sub-34 "auto" additionally proves the dual-language path is gated on
            // autoDetectionActive, not merely on the "auto" string.
            val h = EngineHarness(
                testScheduler,
                config = SttConfig(language = "auto"),
                sdkInt = 33,
                languageAvailabilityOverrides = mapOf(
                    // If the dual-language path fired despite sdkInt=33, this would incorrectly fail
                    // the session naming es-US even though the sub-34 fallback never requests it.
                    "es-US" to LanguageAvailability.DOWNLOADABLE,
                ),
            )
            h.collectInto(backgroundScope)
            h.reachListening()

            assertTrue("sub-34 auto must not be blocked by an es-US-only override", h.errors.isEmpty())
            assertEquals(EngineState.Listening, h.engine.state.value)
        }

    // --- language-tag matching, which decides the tester's own happy path ---------------------------

    @Test
    fun `the language matcher treats en as installed when the device lists en-US`() {
        // The library's default config asks for "en"; the tester has exactly "en-US" installed. A
        // strict equality match here would report no-offline-model for the default configuration on
        // the one device this phase runs on.
        assertTrue(PlatformRecognizerHandle.matches("en", listOf("en-US")))
        assertTrue(PlatformRecognizerHandle.matches("en-US", listOf("en")))
        assertTrue(PlatformRecognizerHandle.matches("EN_us", listOf("en-US")))
        assertTrue(PlatformRecognizerHandle.matches("de-DE", THIRTY_SUPPORTED))

        assertFalse(PlatformRecognizerHandle.matches("de", listOf("en-US")))
        assertFalse("a shared prefix is not a subtag match", PlatformRecognizerHandle.matches("en", listOf("eng-GB")))
        assertFalse(PlatformRecognizerHandle.matches("en", emptyList()))
        assertFalse(PlatformRecognizerHandle.matches("", listOf("en-US")))
    }

    // --- degenerate bundles are contract, not edge cases (T-05-12) ----------------------------------

    @Test
    fun `every degenerate partial bundle produces no partial and no exception`() = runTest {
        FakeRecognizerHandle.DegenerateBundle.entries.forEach { shape ->
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireDegenerate(FakeRecognizerHandle.ResultChannel.PARTIAL, shape)
            advanceUntilIdle()

            assertEquals("$shape must produce no partial", "", h.engine.partial.value)
            assertEquals("$shape must produce no final", emptyList<FinalSegment>(), h.finals.toList())
            assertEquals("$shape must produce no error", emptyList<SttError>(), h.errors.toList())
        }
    }

    @Test
    fun `every degenerate results bundle produces no final and never an empty-text final`() = runTest {
        FakeRecognizerHandle.DegenerateBundle.entries.forEach { shape ->
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.fireDegenerate(FakeRecognizerHandle.ResultChannel.RESULTS, shape)
            advanceUntilIdle()

            assertEquals(
                "$shape must never commit an empty-text final",
                emptyList<FinalSegment>(),
                h.finals.toList(),
            )
            assertEquals("$shape must produce no error", emptyList<SttError>(), h.errors.toList())
            // The session still restarts — a bundle carrying nothing is still the end of a session.
            assertEquals(EngineState.Listening, h.engine.state.value)
        }
    }

    @Test
    fun `a degenerate results bundle after a real partial still commits that partial, once`() =
        runTest {
            val h = EngineHarness(testScheduler)
            h.collectInto(backgroundScope)
            h.reachListening()

            h.fake.firePartial("the morning light")
            runCurrent()
            h.fake.fireDegenerate(
                FakeRecognizerHandle.ResultChannel.RESULTS,
                FakeRecognizerHandle.DegenerateBundle.EMPTY_BUNDLE,
            )
            advanceUntilIdle()

            assertEquals(listOf(FinalSegment("the morning light", 0)), h.finals.toList())
        }

    private companion object {
        /** The first few of the 30 supported-but-not-installed locales the probe recorded. */
        val THIRTY_SUPPORTED = listOf("de-DE", "es-ES", "fr-FR", "it-IT", "en-AU")
    }
}

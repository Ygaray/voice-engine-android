package io.github.ygaray.sttengine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.LanguagePacks
import io.github.ygaray.sttengine.internal.RecognizerHandleFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SC2, as corrected by D-10: for every pack state, the public preflight's value and the outcome of a
 * native `"auto"` session start (API 34+) agree. The SAME override map feeds both paths, and both go
 * through the one shared `autoPackAvailability()` query, so a disagreement here means the two paths
 * stopped sharing it.
 *
 * The mapping asserted is D-10's — NOT ROADMAP SC2's original "every unavailable language fails
 * no-offline-model" wording:
 *
 * | pack state     | preflight      | native "auto" start()          |
 * |----------------|----------------|--------------------------------|
 * | INSTALLED      | INSTALLED      | Listening                      |
 * | DOWNLOADABLE   | DOWNLOADABLE   | no-offline-model (names tag)   |
 * | PENDING        | PENDING        | no-offline-model (names tag)   |
 * | UNSUPPORTED    | UNSUPPORTED    | unsupported-language (names)   |
 * | UNKNOWN        | UNKNOWN        | Listening                      |
 * | query throws   | UNKNOWN        | Listening                      |
 * | factory throws | UNSUPPORTED    | recognizer-unavailable         |
 *
 * The agreement is scoped to native `"auto"` on API 34+ (D-02); the sdkInt 33 row documents why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PackAgreementTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** One pack state, what the preflight must report for it, and what start() must do with it. */
    private class Row(
        val state: LanguageAvailability,
        val expectPreflight: LanguageAvailability,
        /** null = start() reaches Listening with no error. */
        val expectStartError: SttErrorCode?,
    )

    private val rows = listOf(
        Row(LanguageAvailability.INSTALLED, LanguageAvailability.INSTALLED, null),
        Row(LanguageAvailability.DOWNLOADABLE, LanguageAvailability.DOWNLOADABLE, SttErrorCode.NO_OFFLINE_MODEL),
        Row(LanguageAvailability.PENDING, LanguageAvailability.PENDING, SttErrorCode.NO_OFFLINE_MODEL),
        Row(LanguageAvailability.UNSUPPORTED, LanguageAvailability.UNSUPPORTED, SttErrorCode.UNSUPPORTED_LANGUAGE),
        Row(LanguageAvailability.UNKNOWN, LanguageAvailability.UNKNOWN, null),
    )

    /** (target tag, its host key, the other key) — the other language stays INSTALLED. */
    private val targets = listOf(
        Triple("es-US", "es", "en"),
        Triple("en-US", "en", "es"),
    )

    private val fullAllowlistQuery = listOf(listOf("en-US", "es-US"))

    @Test
    fun `every pack state reads the same through the preflight and a native auto start`() = runTest {
        rows.forEach { row ->
            targets.forEach { (tag, key, otherKey) ->
                val label = "state=${row.state} tag=$tag"
                val overrides = mapOf(tag to row.state)

                val h = EngineHarness(
                    testScheduler,
                    config = SttConfig(language = "auto"),
                    sdkInt = 35,
                    languageAvailabilityOverrides = overrides,
                )
                h.collectInto(backgroundScope)

                // --- path 1: the public preflight ---
                val preflightFake = FakeRecognizerHandle()
                val preflight = LanguagePacks(
                    handleFactory = RecognizerHandleFactory {
                        PreflightHandle(preflightFake, LanguageAvailability.INSTALLED, overrides)
                    },
                    mainDispatcher = h.dispatcher,
                ).check(context, setOf("en", "es"))

                assertEquals("$label: preflight value", row.expectPreflight, preflight[key])
                assertEquals("$label: the other key", LanguageAvailability.INSTALLED, preflight[otherKey])

                // --- path 2: a native "auto" start on API 35 ---
                startAndAssert(h, label, row.expectStartError, tag)

                // --- the two paths asked the platform the identical question ---
                assertEquals("$label: preflight query", fullAllowlistQuery, preflightFake.languageQueries)
                assertEquals("$label: start() query", fullAllowlistQuery, h.fake.languageQueries)
            }
        }
    }

    @Test
    fun `a recognizer that cannot be constructed reads UNSUPPORTED and fails start RECOGNIZER_UNAVAILABLE`() =
        runTest {
            val h = EngineHarness(
                testScheduler,
                config = SttConfig(language = "auto"),
                sdkInt = 35,
                factoryThrows = true,
            )
            h.collectInto(backgroundScope)

            val preflight = LanguagePacks(
                handleFactory = RecognizerHandleFactory {
                    throw UnsupportedOperationException("On-device recognition is not available")
                },
                mainDispatcher = h.dispatcher,
            ).check(context, setOf("en", "es"))

            assertEquals(
                "factory throws: preflight",
                linkedMapOf("en" to LanguageAvailability.UNSUPPORTED, "es" to LanguageAvailability.UNSUPPORTED),
                preflight,
            )
            startAndAssert(h, "factory throws", SttErrorCode.RECOGNIZER_UNAVAILABLE, tag = null)
        }

    @Test
    fun `a support query that throws reads UNKNOWN and lets a native auto start reach Listening`() = runTest {
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto"),
            sdkInt = 35,
            languageQueryThrows = IllegalStateException("query failed"),
        )
        h.collectInto(backgroundScope)

        val preflightFake = FakeRecognizerHandle()
        val preflight = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(
                    preflightFake,
                    LanguageAvailability.INSTALLED,
                    emptyMap(),
                    listQueryThrows = IllegalStateException("query failed"),
                )
            },
            mainDispatcher = h.dispatcher,
        ).check(context, setOf("en", "es"))

        assertEquals(
            "query throws: preflight",
            linkedMapOf("en" to LanguageAvailability.UNKNOWN, "es" to LanguageAvailability.UNKNOWN),
            preflight,
        )
        startAndAssert(h, "query throws", expectError = null, tag = null)
        assertEquals("query throws: preflight query", fullAllowlistQuery, preflightFake.languageQueries)
        assertEquals("query throws: start() query", fullAllowlistQuery, h.fake.languageQueries)
    }

    @Test
    fun `both packs missing - the preflight reports both while start names en-US first`() = runTest {
        val overrides = mapOf(
            "en-US" to LanguageAvailability.DOWNLOADABLE,
            "es-US" to LanguageAvailability.UNSUPPORTED,
        )
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto"),
            sdkInt = 35,
            languageAvailabilityOverrides = overrides,
        )
        h.collectInto(backgroundScope)

        val preflightFake = FakeRecognizerHandle()
        val preflight = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(preflightFake, LanguageAvailability.INSTALLED, overrides)
            },
            mainDispatcher = h.dispatcher,
        ).check(context, setOf("en", "es"))

        assertEquals(
            "both missing: preflight",
            linkedMapOf("en" to LanguageAvailability.DOWNLOADABLE, "es" to LanguageAvailability.UNSUPPORTED),
            preflight,
        )
        startAndAssert(h, "both missing", SttErrorCode.NO_OFFLINE_MODEL, tag = "en-US")
        assertEquals("both missing: preflight query", fullAllowlistQuery, preflightFake.languageQueries)
        assertEquals("both missing: start() query", fullAllowlistQuery, h.fake.languageQueries)
    }

    /**
     * D-02 scope. Below API 34 an `"auto"` session does not detect languages: it starts in one
     * resolved language and never consults es-US. The preflight still reports es-US's real state, so
     * here the two legitimately differ — which is exactly why the public KDoc scopes the agreement
     * guarantee to native `"auto"` on API 34+.
     */
    @Test
    fun `below API 34 an auto start does not consult es-US, so agreement is scoped to 34+`() = runTest {
        val overrides = mapOf(
            "en-US" to LanguageAvailability.INSTALLED,
            "es-US" to LanguageAvailability.DOWNLOADABLE,
        )
        val h = EngineHarness(
            testScheduler,
            config = SttConfig(language = "auto"),
            sdkInt = 33,
            languageAvailabilityOverrides = overrides,
        )
        h.collectInto(backgroundScope)

        val preflight = LanguagePacks(
            handleFactory = RecognizerHandleFactory {
                PreflightHandle(FakeRecognizerHandle(), LanguageAvailability.INSTALLED, overrides)
            },
            mainDispatcher = h.dispatcher,
        ).check(context, setOf("en", "es"))

        assertEquals("sdk 33: preflight es", LanguageAvailability.DOWNLOADABLE, preflight["es"])
        startAndAssert(h, "sdk 33", expectError = null, tag = null)
        assertTrue("sdk 33: start() made no list query", h.fake.languageQueries.isEmpty())
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Drive [h]'s start() and assert its D-10 outcome: [expectError] null → Listening with no error;
     * otherwise exactly one error with that code whose message names [tag] (when given).
     */
    private suspend fun TestScope.startAndAssert(
        h: EngineHarness,
        label: String,
        expectError: SttErrorCode?,
        tag: String?,
    ) {
        if (expectError == null) {
            h.reachListening()
            assertTrue("$label: start() must not fail, got ${h.errors}", h.errors.isEmpty())
            assertEquals("$label: start() state", EngineState.Listening, h.engine.state.value)
        } else {
            h.engine.start()
            advanceUntilIdle()
            assertEquals("$label: start() errors", 1, h.errors.size)
            assertEquals("$label: start() error code", expectError, h.errors.single().code)
            if (tag != null) {
                assertTrue(
                    "$label: start() error must name $tag, was '${h.errors.single().message}'",
                    h.errors.single().message.contains(tag),
                )
            }
        }
    }
}

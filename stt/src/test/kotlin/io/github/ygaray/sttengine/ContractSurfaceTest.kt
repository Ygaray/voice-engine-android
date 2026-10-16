package io.github.ygaray.sttengine

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin contract surface is a TRANSLATION of the shipped TypeScript contract
 * (`web/src/types.ts`, `web/src/errors.ts`), not an independent design. This test asserts that
 * shape mechanically so the parity claim survives review, refactors and v2 (AND-01, D-12).
 *
 * The sibling assertions live in `web/tests/errors.test.ts:127` (TypeScript) and
 * `server/tests/test_protocol.py:146` (Python). `ErrorCodeParityTest` is the cross-file half;
 * this file is the in-language half plus the structural surface.
 */
public class ContractSurfaceTest {

    /** The frozen 11, sorted — byte-identical with `web/tests/errors.test.ts:130-142`. */
    private val frozenEleven: List<String> = listOf(
        "busy",
        "internal-error",
        "invalid-config",
        "mic-denied",
        "protocol-violation",
        "reconnect-exhausted",
        "send-backpressure",
        "server-unreachable",
        "unsupported-language",
        "unsupported-sample-rate",
        "version-mismatch",
    ).sorted()

    // --- SttErrorCode (D-14/D-19) ---------------------------------------------------------------

    @Test
    public fun `SHARED holds exactly the frozen eleven`() {
        assertEquals(11, SttErrorCode.SHARED.size)
        assertEquals(frozenEleven, SttErrorCode.SHARED.map { it.value }.sorted())
    }

    @Test
    public fun `ANDROID_ONLY holds exactly the three platform additions`() {
        assertEquals(3, SttErrorCode.ANDROID_ONLY.size)
        assertEquals(
            listOf("audio-error", "no-offline-model", "recognizer-unavailable"),
            SttErrorCode.ANDROID_ONLY.map { it.value }.sorted(),
        )
    }

    @Test
    public fun `SHARED and ANDROID_ONLY are disjoint and together number fourteen`() {
        assertTrue(
            "an Android-only code leaked into the shared set: " +
                SttErrorCode.SHARED.intersect(SttErrorCode.ANDROID_ONLY),
            SttErrorCode.SHARED.intersect(SttErrorCode.ANDROID_ONLY).isEmpty(),
        )
        assertEquals(14, (SttErrorCode.SHARED + SttErrorCode.ANDROID_ONLY).size)
    }

    @Test
    public fun `every companion constant is reachable and carries its wire string`() {
        assertEquals("version-mismatch", SttErrorCode.VERSION_MISMATCH.value)
        assertEquals("busy", SttErrorCode.BUSY.value)
        assertEquals("invalid-config", SttErrorCode.INVALID_CONFIG.value)
        assertEquals("unsupported-language", SttErrorCode.UNSUPPORTED_LANGUAGE.value)
        assertEquals("unsupported-sample-rate", SttErrorCode.UNSUPPORTED_SAMPLE_RATE.value)
        assertEquals("protocol-violation", SttErrorCode.PROTOCOL_VIOLATION.value)
        assertEquals("internal-error", SttErrorCode.INTERNAL_ERROR.value)
        assertEquals("mic-denied", SttErrorCode.MIC_DENIED.value)
        assertEquals("server-unreachable", SttErrorCode.SERVER_UNREACHABLE.value)
        assertEquals("reconnect-exhausted", SttErrorCode.RECONNECT_EXHAUSTED.value)
        assertEquals("send-backpressure", SttErrorCode.SEND_BACKPRESSURE.value)
        assertEquals("recognizer-unavailable", SttErrorCode.RECOGNIZER_UNAVAILABLE.value)
        assertEquals("no-offline-model", SttErrorCode.NO_OFFLINE_MODEL.value)
        assertEquals("audio-error", SttErrorCode.AUDIO_ERROR.value)
    }

    // --- EngineState (web/src/types.ts:11) ------------------------------------------------------

    @Test
    public fun `EngineState mirrors the five TypeScript union members in order`() {
        assertEquals(5, EngineState.entries.size)
        assertEquals(
            listOf("idle", "connecting", "listening", "reconnecting", "error"),
            EngineState.entries.map { it.wireName },
        )
    }

    // --- LanguageAvailability (host-confirmed pack-preflight shape) ------------------------------

    @Test
    public fun `LanguageAvailability mirrors the five frozen values in order`() {
        assertEquals(
            listOf("INSTALLED", "DOWNLOADABLE", "PENDING", "UNSUPPORTED", "UNKNOWN"),
            LanguageAvailability.entries.map { it.name },
        )
    }

    @Test
    public fun `pack preflight keeps its frozen shape`() {
        // A shape change (parameter types, order, return type, suspend-ness) stops this compiling.
        // The function is deliberately not called: this pins the signature, not the behavior.
        val preflight: suspend (Context, Set<String>) -> Map<String, LanguageAvailability> =
            ::checkLanguageAvailability
        assertNotNull(preflight)
    }

    @Test
    public fun `pack download keeps its frozen shape`() {
        // A shape change (parameter types, order, return type, suspend-ness) stops this compiling.
        // The function is deliberately not called: this pins the signature, not the behavior.
        val download: suspend (Context, String) -> Boolean = ::requestLanguagePackDownload
        assertNotNull(download)
    }

    // --- LanguageSource (closed provenance set for FinalSegment.language) ------------------------

    @Test
    public fun `LanguageSource mirrors the two frozen values in order`() {
        assertEquals(
            listOf("DETECTED", "FALLBACK"),
            LanguageSource.entries.map { it.name },
        )
    }

    @Test
    public fun `FinalSegment keeps all three constructor shapes and defaults the source to null`() {
        val twoArg = FinalSegment("x", 0)
        val threeArg = FinalSegment("x", 0, "es")
        val fourArg = FinalSegment("x", 0, "es", LanguageSource.DETECTED)
        assertNull(twoArg.languageSource)
        assertNull(threeArg.languageSource)
        assertEquals(LanguageSource.DETECTED, fourArg.languageSource)
    }

    // --- SttConfig (web/src/types.ts:32-39, D-13) -----------------------------------------------

    @Test
    public fun `SttConfig defaults to the only v1 language`() {
        assertEquals("en", SttConfig().language)
        assertEquals("de", SttConfig(language = "de").language)
    }

    @Test
    public fun `SttConfig keeps the 7-argument constructor with retainForCorpus last (D-08)`() {
        val seven = SttConfig("en", BackendPreference.Auto, null, null, null, 4_000L, true)
        assertTrue(seven.retainForCorpus)
        assertEquals(4_000L, seven.serverTryTimeoutMs)
        // The pre-existing 6-argument shape still resolves and leaves the flag off.
        val six = SttConfig("en", BackendPreference.Auto, null, null, null, 4_000L)
        assertEquals(false, six.retainForCorpus)
    }

    // --- FinalSegment (web/src/types.ts:17-20) --------------------------------------------------

    @Test
    public fun `FinalSegment numbering starts at zero`() {
        val first = FinalSegment(text = "hello world", segmentId = 0)
        assertEquals(0, first.segmentId)
        assertEquals("hello world", first.text)
        assertEquals(first, FinalSegment("hello world", 0))
    }

    // --- ReconnectInfo (web/src/types.ts:27-30) -------------------------------------------------

    @Test
    public fun `ReconnectInfo attempt is one-based`() {
        val info = ReconnectInfo(attempt = 1, max = 7)
        assertEquals(1, info.attempt)
        assertEquals(7, info.max)
    }

    // --- SttError (D-16) ------------------------------------------------------------------------

    @Test
    public fun `SttError platformDetail is absent by default and present when supplied`() {
        val plain = SttError(SttErrorCode.MIC_DENIED, "host app has not been granted RECORD_AUDIO")
        assertNull(plain.platformDetail)

        val fromRecognizer = SttError(SttErrorCode.AUDIO_ERROR, "recognizer audio error", 3)
        assertEquals(3, fromRecognizer.platformDetail)
    }

    // --- SttWarning (D-17) ----------------------------------------------------------------------

    @Test
    public fun `SttWarning keeps the web triple for structural parity`() {
        val warning = SttWarning(code = "backpressure", active = true, message = "shedding")
        assertEquals("backpressure", warning.code)
        assertTrue(warning.active)
        assertEquals("shedding", warning.message)
    }

    // --- SttEvent (web/src/types.ts:48-58) ------------------------------------------------------

    @Test
    public fun `SttEvent mirrors the seven TypeScript events`() {
        val events: List<SttEvent> = listOf(
            SttEvent.Partial("live text"),
            SttEvent.Final(FinalSegment("committed", 0)),
            SttEvent.State(EngineState.Listening),
            SttEvent.Error(SttError(SttErrorCode.INTERNAL_ERROR, "boom")),
            SttEvent.Level(0.5f),
            SttEvent.Reconnecting(ReconnectInfo(1, 7)),
            SttEvent.Warning(SttWarning("backpressure", false, "cleared")),
        )
        assertEquals(7, events.size)
        assertEquals(7, events.map { it::class }.toSet().size)
    }

    // --- SttEngine (D-07/D-09/D-10) -------------------------------------------------------------

    /**
     * Implementing the interface anonymously is the assertion: it will not compile unless
     * `SttEngine` declares exactly these seven Flow properties and both suspend functions. That is
     * a stronger, earlier check than reflecting over the members at runtime.
     *
     * `backend` (Phase 9, SEL-03) is the one addition since v1.0 — Android-only additive metadata
     * that never widened `state`/`SttEvent` (D-01/D-03); this test asserting a seventh property
     * compiled unmodified except for this one new override is itself evidence the addition stayed
     * additive.
     */
    @Test
    public fun `SttEngine declares the seven flows and both suspend functions`() {
        val engine: SttEngine = object : SttEngine {
            override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Idle)
            override val backend: StateFlow<Backend> = MutableStateFlow(Backend.Native)
            override val partial: StateFlow<String> = MutableStateFlow("")
            override val finals: Flow<FinalSegment> = emptyFlow()
            override val errors: Flow<SttError> = emptyFlow()
            override val level: Flow<Float> = emptyFlow()
            override val events: Flow<SttEvent> = emptyFlow()
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }

        assertEquals(EngineState.Idle, engine.state.value)
        assertEquals(Backend.Native, engine.backend.value)
        assertEquals("", engine.partial.value)
        assertNotNull(engine.finals)
        assertNotNull(engine.errors)
        assertNotNull(engine.level)
        assertNotNull(engine.events)
    }

    // --- SttEngine.lastSessionId (Phase 25, D-01 / D-09): a defaulted member -----------------------

    @Test
    public fun `an implementer that does not override lastSessionId reads null (D-09)`() {
        // Compiling this anonymous object with NO lastSessionId override is the source-compat proof:
        // a host fake written against v0.7.0 must keep compiling and read null.
        val engine: SttEngine = object : SttEngine {
            override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Idle)
            override val backend: StateFlow<Backend> = MutableStateFlow(Backend.Native)
            override val partial: StateFlow<String> = MutableStateFlow("")
            override val finals: Flow<FinalSegment> = emptyFlow()
            override val errors: Flow<SttError> = emptyFlow()
            override val level: Flow<Float> = emptyFlow()
            override val events: Flow<SttEvent> = emptyFlow()
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }

        assertNull(engine.lastSessionId)
        assertNull(engine.lastFinal)
    }

    @Test
    public fun `an implementer that overrides lastSessionId returns the override`() {
        val engine: SttEngine = object : SttEngine {
            override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Idle)
            override val backend: StateFlow<Backend> = MutableStateFlow(Backend.Server)
            override val partial: StateFlow<String> = MutableStateFlow("")
            override val finals: Flow<FinalSegment> = emptyFlow()
            override val errors: Flow<SttError> = emptyFlow()
            override val level: Flow<Float> = emptyFlow()
            override val events: Flow<SttEvent> = emptyFlow()
            override val lastSessionId: String? get() = "0123456789abcdef0123456789abcdef"
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
        }

        assertEquals("0123456789abcdef0123456789abcdef", engine.lastSessionId)
    }

    // --- submitCorrection / CorrectionSubmitResult (Phase 25, D-10 / D-01) -------------------------

    @Test
    public fun `CorrectionSubmitResult mirrors the four frozen values in order (D-10)`() {
        assertEquals(
            listOf("ACCEPTED", "UNKNOWN_SESSION", "UNREACHABLE", "REJECTED"),
            CorrectionSubmitResult.entries.map { it.name },
        )
    }

    @Test
    public fun `submitCorrection keeps its frozen shape (D-10)`() {
        // A shape change (parameter types, order, return type, suspend-ness) stops this compiling.
        // The function is deliberately not called: this pins the signature, not the behavior.
        val submit: suspend (Context, String, String, String, String?) -> CorrectionSubmitResult =
            ::submitCorrection
        assertNotNull(submit)
    }

    @Test
    public fun `submitCorrection can be called without language (D-10 default)`() {
        // Compiles only while `language` keeps its null default. Never invoked.
        val omitted: suspend (Context) -> CorrectionSubmitResult = { context ->
            submitCorrection(context, "https://example.ts.net/SttBatch", "0123456789abcdef0123456789abcdef", "fixed")
        }
        assertNotNull(omitted)
    }

    @Test
    public fun `submitCorrection lives in the SubmitCorrectionKt facade and the enum is not a data type (D-01)`() {
        // Loading the class is the check: it fails if the facade is renamed or moved.
        Class.forName("io.github.ygaray.sttengine.SubmitCorrectionKt")
        assertTrue(CorrectionSubmitResult::class.java.isEnum)
    }
}

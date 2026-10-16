package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.legacy.ParseResult as LegacyParseResult
import io.github.ygaray.sttengine.legacy.ServerFrame as LegacyServerFrame
import io.github.ygaray.sttengine.legacy.buildConfigFrame as legacyBuildConfigFrame
import io.github.ygaray.sttengine.legacy.parseServerFrame as legacyParseServerFrame
import io.github.ygaray.sttengine.CorrectionSubmitResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kotlin leg of the three-language wire-frame drift gate (Phase 23, LSRC-02, D-07).
 *
 * `wire-final-frames.json` is committed as three byte-identical copies (server/tests/fixtures,
 * web/tests/fixtures, and this module's `src/test/resources`); `scripts/check-wire-frame-parity.sh`
 * proves them identical and runs the Python, TS and Kotlin legs. This leg proves that
 * [parseServerFrame] reproduces every `client` expectation, and that the frozen pre-phase parser
 * ([io.github.ygaray.sttengine.legacy.parseServerFrame], a verbatim snapshot of `WsProtocol.kt` at
 * b9dfc68) still reads text / segmentId / language of every golden frame unchanged (SC3: a client
 * that predates the field works unchanged).
 *
 * The fixture is the classpath copy unless the system property `stt.wire.goldenFixture` (set from
 * `-Pstt.wire.goldenFixture` in `android/stt/build.gradle.kts`; the parity script passes it on every
 * run) names an absolute path. Robolectric-backed for the same reason as [WsProtocolTest]:
 * `org.json` is a platform class that is only a stub on the plain-JVM `android.jar`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WireFrameGoldenTest {

    private fun loadFixture(): JSONObject {
        val override = System.getProperty("stt.wire.goldenFixture")
        val text = if (!override.isNullOrBlank()) {
            File(override).readText(Charsets.UTF_8)
        } else {
            val stream = WireFrameGoldenTest::class.java.getResourceAsStream("/wire-final-frames.json")
            assertNotNull("classpath fixture /wire-final-frames.json is missing", stream)
            stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return JSONObject(text)
    }

    private fun loadCases(): JSONArray = loadFixture().getJSONArray("cases")

    /** An array the fixture must carry: a missing key fails loudly instead of scoring nothing. */
    private fun loadArray(key: String): JSONArray {
        val fixture = loadFixture()
        assertTrue("wire-final-frames.json has no $key array", fixture.has(key))
        return fixture.getJSONArray(key)
    }

    private fun namesOf(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getJSONObject(it).getString("name") }

    /** True when two JSON objects hold the same keys and values (key order is not part of the contract). */
    private fun sameObject(expected: JSONObject, actual: JSONObject): Boolean {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys().asSequence().toSet()
        if (expectedKeys != actualKeys) return false
        return expectedKeys.all { key ->
            val e = expected.get(key)
            val a = actual.get(key)
            if (e is JSONArray || e is JSONObject) e.toString() == a.toString() else e == a
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    @Test
    fun `fixture contains every required case and is not empty`() {
        val cases = loadCases()
        assertTrue("wire-final-frames.json has no cases", cases.length() > 0)
        val names = (0 until cases.length()).map { cases.getJSONObject(it).getString("name") }
        for (required in REQUIRED_CASES) {
            assertTrue("fixture is missing required case $required (has $names)", required in names)
        }
    }

    @Test
    fun `PROTOCOL_VERSION stays 1`() {
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test
    fun `parseServerFrame reproduces every client expectation`() {
        val cases = loadCases()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val client = case.getJSONObject("client")
            val result = parseServerFrame(case.getString("frame"))

            if (client.getString("outcome") == "protocol-violation") {
                assertTrue("$name: expected a protocol violation, got $result", result is ParseResult.ProtocolViolation)
                continue
            }
            assertTrue("$name: expected Ok, got $result", result is ParseResult.Ok)
            val frame = (result as ParseResult.Ok).frame
            assertTrue("$name: expected a final frame, got $frame", frame is ServerFrame.Final)
            val final = frame as ServerFrame.Final
            assertEquals("$name text", client.getString("text"), final.text)
            assertEquals("$name segmentId", client.getInt("segmentId"), final.segmentId)
            assertEquals("$name language", client.stringOrNull("language"), final.language)
            assertEquals("$name languageSource", client.stringOrNull("languageSource"), final.languageSource)
        }
    }

    @Test
    fun `the frozen pre-phase parser reads every frame unchanged`() {
        val cases = loadCases()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val legacy = case.getJSONObject("legacy")
            val result = legacyParseServerFrame(case.getString("frame"))

            assertTrue("$name: legacy parser should accept, got $result", result is LegacyParseResult.Ok)
            val frame = (result as LegacyParseResult.Ok).frame
            assertTrue("$name: legacy parser should yield a final, got $frame", frame is LegacyServerFrame.Final)
            val final = frame as LegacyServerFrame.Final
            assertEquals("$name legacy text", legacy.getString("text"), final.text)
            assertEquals("$name legacy segmentId", legacy.getInt("segmentId"), final.segmentId)
            assertEquals("$name legacy language", legacy.stringOrNull("language"), final.language)
        }
    }

    @Test
    fun `ready fixture contains every required case and is not empty`() {
        val names = namesOf(loadArray("readyCases"))
        assertTrue("readyCases is empty", names.isNotEmpty())
        for (required in REQUIRED_READY) {
            assertTrue("fixture is missing required readyCase $required (has $names)", required in names)
        }
    }

    @Test
    fun `parseServerFrame reproduces every ready expectation`() {
        val cases = loadArray("readyCases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val client = case.getJSONObject("client")
            val result = parseServerFrame(case.getString("frame"))

            if (client.getString("outcome") == "protocol-violation") {
                assertTrue("$name: expected a protocol violation, got $result", result is ParseResult.ProtocolViolation)
                continue
            }
            assertTrue("$name: expected Ok, got $result", result is ParseResult.Ok)
            val frame = (result as ParseResult.Ok).frame
            assertTrue("$name: expected a ready frame, got $frame", frame is ServerFrame.Ready)
            assertEquals("$name sessionId", client.stringOrNull("sessionId"), (frame as ServerFrame.Ready).sessionId)
        }
    }

    @Test
    fun `the frozen pre-phase parser still reads every ready frame as a ready`() {
        val cases = loadArray("readyCases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            assertEquals("$name legacy outcome", "ready", case.getJSONObject("legacy").getString("outcome"))
            val result = legacyParseServerFrame(case.getString("frame"))
            assertTrue("$name: legacy parser should accept, got $result", result is LegacyParseResult.Ok)
            assertEquals(
                "$name: legacy parser should yield a ready",
                LegacyServerFrame.Ready,
                (result as LegacyParseResult.Ok).frame,
            )
        }
    }

    @Test
    fun `config fixture contains every required case and is not empty`() {
        val names = namesOf(loadArray("configCases"))
        assertTrue("configCases is empty", names.isNotEmpty())
        for (required in REQUIRED_CONFIG) {
            assertTrue("fixture is missing required configCase $required (has $names)", required in names)
        }
    }

    @Test
    fun `revision fixture contains every required case and is not empty`() {
        // The execution of revisionCases is the next test; this one proves the array cannot be
        // emptied or truncated without failing the gate.
        val names = namesOf(loadArray("revisionCases"))
        assertTrue("revisionCases is empty", names.isNotEmpty())
        for (required in REQUIRED_REVISION) {
            assertTrue("fixture is missing required revisionCase $required (has $names)", required in names)
        }
    }

    @Test
    fun `CorpusCorrections maps every revision case status to its result`() {
        // Phase 25 (plan 25-05, D-15, D-16): the Kotlin leg of the correction status table. Each case
        // is replayed through a local server that answers exactly the case's status (with the
        // server's generic error body when an error name is set), and the enum NAME the transport
        // returns must equal the case's `result`. Python scores the status and error against the
        // real route; this leg scores the status against the client's mapping, so a drift in
        // either one fails the gate.
        val cases = loadArray("revisionCases")
        assertTrue("revisionCases is empty", cases.length() > 0)
        val names = namesOf(cases)
        for (required in REQUIRED_REVISION) {
            assertTrue("fixture is missing required revisionCase $required (has $names)", required in names)
        }
        val validResults = CorrectionSubmitResult.entries.map { it.name }
        var scored = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val status = case.getInt("status")
            val expected = case.getString("result")
            assertTrue("$name: result $expected is not a CorrectionSubmitResult name ($validResults)", expected in validResults)

            val server = MockWebServer()
            server.start()
            try {
                val response = MockResponse.Builder().code(status)
                when {
                    status == 302 -> response.addHeader("Location", server.url("/elsewhere").toString())
                    !case.isNull("error") ->
                        response.body("""{"error": "${case.getString("error")}", "message": "x"}""")
                    else -> response.body("""{"ok":true,"revision":1}""")
                }
                server.enqueue(response.build())

                val base = "http://${server.hostName}:${server.port}/SttBatch"
                val result = runBlocking {
                    withTimeout(20_000) {
                        CorpusCorrections().submit(base, "0123456789abcdef0123456789abcdef", "fixed", "en")
                    }
                }

                assertEquals("$name: status $status", expected, result.name)
                assertEquals("$name: one request per call", 1, server.requestCount)
                scored++
            } finally {
                server.close()
            }
        }
        assertEquals("every revision case must be scored", cases.length(), scored)
    }

    @Test
    fun `buildConfigFrame reproduces every config frame a client builds`() {
        val cases = loadArray("configCases")
        var scored = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (!case.has("build")) continue
            val name = case.getString("name")
            val build = case.getJSONObject("build")
            val vocabulary = if (build.has("vocabulary")) {
                val array = build.getJSONArray("vocabulary")
                (0 until array.length()).map { array.getString(it) }
            } else {
                null
            }
            val built = buildConfigFrame(
                build.getString("language"),
                vocabulary,
                build.optBoolean("retainForCorpus", false),
            )
            assertTrue(
                "$name: built $built != frame ${case.getString("frame")}",
                sameObject(JSONObject(case.getString("frame")), JSONObject(built)),
            )
            scored++
        }
        assertTrue("no configCase carried a build", scored > 0)
    }

    @Test
    fun `the default config frame equals the frozen pre-phase builder output`() {
        assertEquals(legacyBuildConfigFrame("en"), buildConfigFrame("en"))
    }

    private companion object {
        /** An emptied or truncated fixture must fail, never pass vacuously. */
        val REQUIRED_CASES = listOf(
            "auto-detected",
            "non-auto-no-language",
            "auto-fallback",
            "language-without-source",
            "unknown-source-string",
            "wrong-type-number",
            "wrong-type-boolean",
            "wrong-type-null",
            "source-without-language",
            "unknown-extra-key",
            "unsupported-language-with-source",
            "empty-language-with-source",
        )

        /** Phase 25 (plan 25-02): the new arrays must be present and carry these names. */
        val REQUIRED_READY = listOf(
            "ready-plain",
            "ready-with-session-id",
            "ready-session-id-number",
            "ready-session-id-null",
            "ready-session-id-object",
            "ready-session-id-empty-string",
            "ready-unknown-extra-key",
        )
        val REQUIRED_CONFIG = listOf(
            "config-default",
            "config-retain-true",
            "config-retain-true-with-vocabulary",
            "config-retain-false-omitted",
            "config-retain-null",
            "config-retain-int-1",
            "config-retain-string",
        )
        val REQUIRED_REVISION = listOf(
            "revision-created",
            "revision-unknown-id",
            "revision-invalid-body",
            "revision-payload-too-large",
            "revision-limit",
            "revision-busy",
            "revision-corpus-unavailable",
            "revision-internal-error",
            "revision-request-timeout",
            "revision-other-4xx",
            "revision-unexpected-redirect",
        )
    }
}

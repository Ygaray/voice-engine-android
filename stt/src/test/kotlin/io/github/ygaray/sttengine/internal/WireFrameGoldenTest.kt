package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.legacy.ParseResult as LegacyParseResult
import io.github.ygaray.sttengine.legacy.ServerFrame as LegacyServerFrame
import io.github.ygaray.sttengine.legacy.parseServerFrame as legacyParseServerFrame
import java.io.File
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

    private fun loadCases(): JSONArray {
        val override = System.getProperty("stt.wire.goldenFixture")
        val text = if (!override.isNullOrBlank()) {
            File(override).readText(Charsets.UTF_8)
        } else {
            val stream = WireFrameGoldenTest::class.java.getResourceAsStream("/wire-final-frames.json")
            assertNotNull("classpath fixture /wire-final-frames.json is missing", stream)
            stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return JSONObject(text).getJSONArray("cases")
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
    }
}

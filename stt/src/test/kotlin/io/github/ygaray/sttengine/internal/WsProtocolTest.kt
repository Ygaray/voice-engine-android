package io.github.ygaray.sttengine.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed, like [SessionLogTest] — NOT because anything here is Android-platform logic,
 * but because `org.json.JSONObject`/`JSONTokener` are themselves platform classes: on the plain-JVM
 * mockable `android.jar` (`testOptions.unitTests.isReturnDefaultValues = true`) every method is a
 * stub that returns a default value rather than doing real work, which turns a legitimate JSON parse
 * into a silent `NullPointerException` rather than a correctness signal. Robolectric supplies the
 * real implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WsProtocolTest {

    // --- buildConfigFrame ----------------------------------------------------------------------

    @Test
    fun `buildConfigFrame produces version, language and sampleRate`() {
        val json = JSONObject(buildConfigFrame("en"))

        assertEquals(1, json.getInt("version"))
        assertEquals("en", json.getString("language"))
        assertEquals(16_000, json.getInt("sampleRate"))
    }

    // --- parseServerFrame: the happy paths ------------------------------------------------------

    @Test
    fun `parseServerFrame accepts a ready frame`() {
        val result = parseServerFrame("""{"type":"ready"}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Ready, (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame accepts a partial frame`() {
        val result = parseServerFrame("""{"type":"partial","text":"hello","segmentId":0}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Partial("hello", 0), (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame accepts a final frame`() {
        val result = parseServerFrame("""{"type":"final","text":"hello world","segmentId":2}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Final("hello world", 2), (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame accepts a final frame with a language key and carries it through`() {
        val result = parseServerFrame("""{"type":"final","text":"hola","segmentId":0,"language":"es"}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Final("hola", 0, "es"), (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame accepts a final frame with no language key — language is null`() {
        val result = parseServerFrame("""{"type":"final","text":"hi","segmentId":0}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Final("hi", 0, null), (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame rejects a final frame with a non-string language, never coerces`() {
        val result = parseServerFrame("""{"type":"final","text":"hi","segmentId":0,"language":123}""")

        assertTrue(result is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame accepts an error frame and carries the code verbatim`() {
        val result = parseServerFrame("""{"type":"error","code":"busy","message":"server busy"}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Error("busy", "server busy"), (result as ParseResult.Ok).frame)
    }

    @Test
    fun `parseServerFrame carries an unrecognized error code verbatim rather than rejecting it`() {
        val result = parseServerFrame(
            """{"type":"error","code":"some-future-server-code","message":"m"}""",
        )

        assertTrue(result is ParseResult.Ok)
        assertEquals(
            ServerFrame.Error("some-future-server-code", "m"),
            (result as ParseResult.Ok).frame,
        )
    }

    // --- parseServerFrame: malformed / non-JSON never throws, always a typed violation ----------

    @Test
    fun `parseServerFrame on a non-JSON string returns a protocol violation, never throws`() {
        val result = parseServerFrame("not json at all")

        assertTrue(result is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame on an empty string returns a protocol violation`() {
        assertTrue(parseServerFrame("") is ParseResult.ProtocolViolation)
        assertTrue(parseServerFrame("   ") is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame on a JSON array returns a protocol violation`() {
        assertTrue(parseServerFrame("[1,2,3]") is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame on a JSON primitive returns a protocol violation`() {
        assertTrue(parseServerFrame("42") is ParseResult.ProtocolViolation)
        assertTrue(parseServerFrame(""""just a string"""") is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame on an unknown type returns a protocol violation`() {
        assertTrue(parseServerFrame("""{"type":"bogus"}""") is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame on a missing type returns a protocol violation`() {
        assertTrue(parseServerFrame("""{"text":"hello"}""") is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame never coerces a malformed partial frame`() {
        // segmentId is a string, not an int — must be rejected, not coerced.
        val result = parseServerFrame("""{"type":"partial","text":"hi","segmentId":"0"}""")
        assertTrue(result is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame rejects a partial frame with a non-string text field`() {
        val result = parseServerFrame("""{"type":"partial","text":42,"segmentId":0}""")
        assertTrue(result is ParseResult.ProtocolViolation)
    }

    @Test
    fun `parseServerFrame rejects an error frame with an empty code`() {
        val result = parseServerFrame("""{"type":"error","code":"","message":"m"}""")
        assertTrue(result is ParseResult.ProtocolViolation)
    }

    // --- closeCodeFor ----------------------------------------------------------------------------

    @Test
    fun `closeCodeFor maps the known taxonomy codes and defaults everything else to 1008`() {
        assertEquals(1013, closeCodeFor("busy"))
        assertEquals(1011, closeCodeFor("internal-error"))
        assertEquals(1000, closeCodeFor("normal"))
        assertEquals(1008, closeCodeFor("invalid-config"))
        assertEquals(1008, closeCodeFor("some-unrecognized-code"))
    }
}

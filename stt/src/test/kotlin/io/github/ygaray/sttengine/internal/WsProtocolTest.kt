package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.LanguageSource
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

    @Test
    fun `buildConfigFrame includes a vocabulary array when a non-empty list is supplied`() {
        val json = JSONObject(buildConfigFrame("en", listOf("Yahir", "Tailscale")))

        val vocabulary = json.getJSONArray("vocabulary")
        assertEquals(2, vocabulary.length())
        assertEquals("Yahir", vocabulary.getString(0))
        assertEquals("Tailscale", vocabulary.getString(1))
    }

    @Test
    fun `buildConfigFrame omits vocabulary entirely for a null or empty list`() {
        val nullJson = JSONObject(buildConfigFrame("en", null))
        val emptyJson = JSONObject(buildConfigFrame("en", emptyList()))

        assertTrue(!nullJson.has("vocabulary"))
        assertTrue(!emptyJson.has("vocabulary"))
    }

    // --- parseServerFrame: the happy paths ------------------------------------------------------

    @Test
    fun `parseServerFrame accepts a ready frame`() {
        val result = parseServerFrame("""{"type":"ready"}""")

        assertTrue(result is ParseResult.Ok)
        assertEquals(ServerFrame.Ready(), (result as ParseResult.Ok).frame)
        assertEquals(null, ((result as ParseResult.Ok).frame as ServerFrame.Ready).sessionId)
    }

    // --- retainForCorpus + ready.sessionId (Phase 25, CORR-06 / CORR-07, D-03) -------------------

    @Test
    fun `buildConfigFrame default frames are string-equal to the frozen legacy builder`() {
        assertEquals(io.github.ygaray.sttengine.legacy.buildConfigFrame("en"), buildConfigFrame("en"))
        assertEquals(
            io.github.ygaray.sttengine.legacy.buildConfigFrame("en", listOf("x")),
            buildConfigFrame("en", listOf("x")),
        )
        assertEquals(
            io.github.ygaray.sttengine.legacy.buildConfigFrame("en"),
            buildConfigFrame("en", null, false),
        )
    }

    @Test
    fun `buildConfigFrame emits retainForCorpus true only when requested`() {
        val on = JSONObject(buildConfigFrame("en", null, true))
        assertTrue(on.has("retainForCorpus"))
        assertEquals(true, on.getBoolean("retainForCorpus"))
        assertEquals("en", on.getString("language"))

        assertTrue(!JSONObject(buildConfigFrame("en", null, false)).has("retainForCorpus"))
        assertTrue(!JSONObject(buildConfigFrame("en", listOf("a"))).has("retainForCorpus"))
    }

    private fun readyOf(json: String): ServerFrame.Ready {
        val result = parseServerFrame(json)
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        return (result as ParseResult.Ok).frame as ServerFrame.Ready
    }

    @Test
    fun `parseServerFrame carries a ready sessionId`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(ServerFrame.Ready(id), readyOf("""{"type":"ready","sessionId":"$id"}"""))
    }

    @Test
    fun `parseServerFrame treats an empty ready sessionId as no claim`() {
        assertEquals(ServerFrame.Ready(), readyOf("""{"type":"ready","sessionId":""}"""))
    }

    @Test
    fun `parseServerFrame ignores unknown extra keys on a ready frame`() {
        assertEquals(ServerFrame.Ready(), readyOf("""{"type":"ready","somethingNew":1}"""))
    }

    @Test
    fun `parseServerFrame rejects a non-string ready sessionId as a violation naming sessionId`() {
        for (bad in listOf("12", "null", "{}", "[]", "true")) {
            val result = parseServerFrame("""{"type":"ready","sessionId":$bad}""")

            assertTrue("sessionId=$bad should be a violation, got $result", result is ParseResult.ProtocolViolation)
            assertTrue((result as ParseResult.ProtocolViolation).message.contains("sessionId"))
        }
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

    // --- languageSource (Phase 23, LSRC-02, D-03 / D-06) -----------------------------------------
    //
    // The contract is ONE-WAY: a parsed languageSource implies a parsed language. The converse is
    // not a contract — a server that predates the field sends a language with no source.

    private fun finalOf(json: String): ServerFrame.Final {
        val result = parseServerFrame(json)
        assertTrue("expected Ok, got $result", result is ParseResult.Ok)
        val frame = (result as ParseResult.Ok).frame
        assertTrue("expected a final, got $frame", frame is ServerFrame.Final)
        return frame as ServerFrame.Final
    }

    @Test
    fun `parseServerFrame carries a detected languageSource alongside the language`() {
        val final = finalOf("""{"type":"final","text":"hola","segmentId":0,"language":"es","languageSource":"detected"}""")

        assertEquals(ServerFrame.Final("hola", 0, "es", "detected"), final)
    }

    @Test
    fun `parseServerFrame carries a fallback languageSource alongside the language`() {
        val final = finalOf("""{"type":"final","text":"hi","segmentId":0,"language":"en","languageSource":"fallback"}""")

        assertEquals("fallback", final.languageSource)
    }

    @Test
    fun `parseServerFrame older-server frame is a language with no source and never reads as fallback`() {
        // A server that predates the field: legal wire state, parses to (language, no source) = no claim.
        val final = finalOf("""{"type":"final","text":"hola","segmentId":0,"language":"es"}""")

        assertEquals("es", final.language)
        assertEquals(null, final.languageSource)
    }

    @Test
    fun `parseServerFrame unknown languageSource string is no source, not a violation`() {
        val final = finalOf("""{"type":"final","text":"hola","segmentId":0,"language":"es","languageSource":"held"}""")

        assertEquals("es", final.language)
        assertEquals(null, final.languageSource)
    }

    @Test
    fun `parseServerFrame languageSource without a language is dropped so a source implies a language`() {
        val final = finalOf("""{"type":"final","text":"hi","segmentId":0,"languageSource":"detected"}""")

        assertEquals(null, final.language)
        assertEquals(null, final.languageSource)
    }

    @Test
    fun `parseServerFrame passes an unsupported or empty language through with its source`() {
        // The EN/ES restriction is a server emission rule, not client validation: a later
        // multilingual server must stay additive.
        assertEquals("detected", finalOf("""{"type":"final","text":"a","segmentId":0,"language":"fr","languageSource":"detected"}""").languageSource)
        assertEquals("", finalOf("""{"type":"final","text":"a","segmentId":0,"language":"","languageSource":"detected"}""").language)
    }

    @Test
    fun `languageSourceFromWire maps the two exact lowercase strings to the enum`() {
        assertEquals(LanguageSource.DETECTED, languageSourceFromWire("detected"))
        assertEquals(LanguageSource.FALLBACK, languageSourceFromWire("fallback"))
    }

    @Test
    fun `languageSourceFromWire maps absent, unknown and wrong-case values to null, never FALLBACK`() {
        assertEquals(null, languageSourceFromWire(null))
        assertEquals(null, languageSourceFromWire("held"))
        assertEquals(null, languageSourceFromWire(""))
        assertEquals(null, languageSourceFromWire("DETECTED"))
        assertEquals(null, languageSourceFromWire("Fallback"))
    }

    @Test
    fun `parseServerFrame rejects a non-string languageSource as a violation naming languageSource`() {
        for (bad in listOf("1", "true", "null", "[]", "{}")) {
            val result = parseServerFrame("""{"type":"final","text":"hi","segmentId":0,"language":"es","languageSource":$bad}""")

            assertTrue("languageSource=$bad should be a violation, got $result", result is ParseResult.ProtocolViolation)
            assertTrue(
                (result as ParseResult.ProtocolViolation).message.contains("languageSource"),
            )
        }
    }

    @Test
    fun `parseServerFrame a malformed language still reports its own message before languageSource`() {
        val result = parseServerFrame("""{"type":"final","text":"hi","segmentId":0,"language":123,"languageSource":1}""")

        assertTrue(result is ParseResult.ProtocolViolation)
        assertTrue((result as ParseResult.ProtocolViolation).message.contains("language must be a string"))
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

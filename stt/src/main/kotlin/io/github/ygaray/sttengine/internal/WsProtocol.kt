package io.github.ygaray.sttengine.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Wire constants + config-frame builder + strict server-frame parser — the byte-for-byte Kotlin
 * port of `web/src/protocol.ts` (Phase 9, D-02).
 *
 * CONTRACT-TO-MIRROR (verbatim): every value here matches the server byte-for-byte —
 *  - [PROTOCOL_VERSION], [closeCodeFor]'s map <- `server/src/stt_server/protocol.py`
 *  - [SAMPLE_RATE], [FRAME_SAMPLES]           <- `server/src/stt_server/config.py`
 * The client cannot negotiate: the config frame is validated against these server-side.
 *
 * [parseServerFrame] mirrors the server's `validate_config` discipline (`protocol.ts:64-140`,
 * T-09-01): server frames are UNTRUSTED input — strict JSON parse, validate `type`, one typed
 * [ParseResult.ProtocolViolation] per failure, and NEVER throw to the caller, NEVER coerce a
 * malformed frame into a best-effort guess (ASVS V5 / CLAUDE.md "loud typed failures").
 */

/** The one accepted wire protocol version (mirrors `protocol.ts:18`). */
internal const val PROTOCOL_VERSION: Int = 1

/** 16 kHz mono is the fixed pipeline rate (mirrors `protocol.ts:21`). */
internal const val SAMPLE_RATE: Int = 16_000

/**
 * One 16 kHz output frame == one Silero VAD chunk (mirrors `protocol.ts:24`).
 *
 * Used by [ServerBackend]'s PCM pump (Phase 21, SRV-05): `FRAME_BYTES = FRAME_SAMPLES * 2` is the
 * whole-frame size the pump accumulates captured PCM into before each frame goes through
 * [ServerBackend.sendPcmFrame] — every binary frame on the wire is exactly this many samples.
 */
internal const val FRAME_SAMPLES: Int = 512

/**
 * The tailnet wss port — the `tailscale serve --tls-terminated-tcp=8444` raw-TCP mount that forwards
 * to the loopback server. This is the ONLY WS-safe path (mirrors `protocol.ts:26-32`): it is NEVER
 * the pretty `:443/SttEngine` static mount, which terminates as HTTP/2 and silently fails the WS
 * upgrade ("missing Connection header" — the `tailscale-serve-websocket-h2` gotcha).
 *
 * IN-01: intentionally reserved, not yet referenced by any production call site — today the server
 * URL is supplied whole via `SttConfig.serverUrl`/`BackendSelector`'s constructor rather than
 * assembled from this port. Kept here as the documented, correct default port for whichever call
 * site (a future `SttConfig` convenience default, most likely) ends up assembling one.
 */
internal const val WSS_PORT: Int = 8444

/**
 * Build the first-frame session config. Carries `version`/`language`/`sampleRate` always — config
 * never travels in the WS URL query string (mirrors `protocol.ts:41-48`); the server rejects any
 * non-empty query as `protocol-violation`. `vocabulary` (BIAS-01) is the one additive field:
 * omitted entirely for a `null`/empty list (byte-identical to the pre-existing three-key shape),
 * present only when the caller supplies a non-empty list — mirrors `protocol.ts`'s
 * `buildConfigFrame` conditional-spread idiom exactly.
 */
internal fun buildConfigFrame(language: String, vocabulary: List<String>? = null): String {
    val json = JSONObject()
        .put("version", PROTOCOL_VERSION)
        .put("language", language)
        .put("sampleRate", SAMPLE_RATE)
    if (!vocabulary.isNullOrEmpty()) {
        json.put("vocabulary", JSONArray(vocabulary))
    }
    return json.toString()
}

/** A validated, well-typed server frame — the accept path of [parseServerFrame]. */
internal sealed interface ServerFrame {
    /** The server is ready; the session may begin streaming audio. */
    object Ready : ServerFrame

    data class Partial(val text: String, val segmentId: Int) : ServerFrame

    data class Final(val text: String, val segmentId: Int, val language: String? = null) : ServerFrame

    /** Carries the server's taxonomy code verbatim — this client does not reject an unknown code,
     *  mirroring `protocol.ts:132`'s "the server owns its taxonomy" comment. */
    data class Error(val code: String, val message: String) : ServerFrame
}

/** The parse result: either a validated frame, or a loud typed protocol violation. Never throws. */
internal sealed interface ParseResult {
    data class Ok(val frame: ServerFrame) : ParseResult
    data class ProtocolViolation(val message: String) : ParseResult
}

private val KNOWN_TYPES = setOf("ready", "partial", "final", "error")

private fun violation(message: String): ParseResult = ParseResult.ProtocolViolation(message)

/**
 * Parse + validate an untrusted server frame (T-09-01). Returns [ParseResult.ProtocolViolation] for
 * a non-JSON / empty / non-object / unknown-type / malformed-payload frame — it NEVER throws and
 * NEVER coerces. Mirrors `protocol.ts:81-140`'s strict "reject, do not crash, do not coerce"
 * discipline.
 */
internal fun parseServerFrame(raw: String): ParseResult {
    if (raw.trim().isEmpty()) {
        return violation("empty or non-string server frame")
    }

    val parsed: Any = try {
        JSONTokener(raw).nextValue()
    } catch (malformed: JSONException) {
        return violation("server frame is not valid JSON")
    }

    if (parsed !is JSONObject) {
        val kind = if (parsed is JSONArray) "array" else parsed.javaClass.simpleName
        return violation("server frame must be a JSON object, got $kind")
    }

    val type = parsed.opt("type")
    if (type !is String || type !in KNOWN_TYPES) {
        return violation("unknown or missing server frame type: $type")
    }

    return when (type) {
        "ready" -> ParseResult.Ok(ServerFrame.Ready)

        "partial" -> {
            val text = parsed.opt("text")
            val segmentId = parsed.opt("segmentId")
            if (text !is String || !isInt(segmentId)) {
                violation("malformed partial frame (need string text + int segmentId)")
            } else {
                ParseResult.Ok(ServerFrame.Partial(text, (segmentId as Number).toInt()))
            }
        }

        "final" -> {
            val text = parsed.opt("text")
            val segmentId = parsed.opt("segmentId")
            val language = parsed.opt("language")
            if (text !is String || !isInt(segmentId)) {
                violation("malformed final frame (need string text + int segmentId)")
            } else if (language != null && language !is String) {
                violation("malformed final frame (language must be a string when present)")
            } else {
                ParseResult.Ok(ServerFrame.Final(text, (segmentId as Number).toInt(), language))
            }
        }

        "error" -> {
            val code = parsed.opt("code")
            val message = parsed.opt("message")
            if (code !is String || code.isEmpty() || message !is String) {
                violation("malformed error frame (need non-empty string code + string message)")
            } else {
                ParseResult.Ok(ServerFrame.Error(code, message))
            }
        }

        // Unreachable given the KNOWN_TYPES guard above, but never coerce.
        else -> violation("unhandled server frame type: $type")
    }
}

/**
 * True when [value] is an integral JSON number. `org.json` may hand back an `Int`, `Long`, or a
 * `Double` — only an integral value satisfies the wire contract's `segmentId`, mirroring
 * `protocol.ts`'s `isInt` (`Number.isInteger`).
 */
private fun isInt(value: Any?): Boolean = when (value) {
    is Int, is Long -> true
    is Double -> !value.isInfinite() && !value.isNaN() && value == Math.floor(value)
    else -> false
}

/**
 * Map a taxonomy code (or the `'normal'` sentinel) to a coarse standard WS close code. Mirrors
 * `protocol.ts:148-153` exactly: busy->1013, internal-error->1011, normal->1000, every config/
 * protocol rejection (default) -> 1008. Coarse hint only; the JSON `error.code` remains the single
 * source of truth.
 */
internal fun closeCodeFor(code: String): Int = when (code) {
    "busy" -> 1013 // TRY_AGAIN_LATER
    "internal-error" -> 1011 // INTERNAL_ERROR
    "normal" -> 1000 // NORMAL_CLOSURE
    else -> 1008 // POLICY_VIOLATION — all config/protocol rejections
}

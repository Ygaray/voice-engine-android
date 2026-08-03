package io.github.ygaray.sttengine

/**
 * A typed, host-visible error: the machine-readable [code] drives logic, [message] diagnoses.
 * Translated from `web/src/errors.ts:45-49`.
 *
 * Not a `data class`, per D-12 — see [FinalSegment] for the reasoning.
 *
 * **[platformDetail] is the one field the web shape does not have (D-16).** It carries the raw
 * `RecognitionListener.onError` integer, unmapped, and is deliberately platform-local: the web
 * `SttError` shape is UNTOUCHED by this addition, and a shared host `when (error.code)` keeps
 * working against both backends because the extra field is optional and additive. It exists so a
 * bug report can name the exact platform condition behind a generic [SttErrorCode.INTERNAL_ERROR]
 * — without it, the recognizer's own diagnosis is discarded at the contract boundary.
 *
 * Friendly hint text belongs to the host or the demo, NEVER to the contract
 * (`web/src/errors.ts:12-14`). A host renders `ERROR [code] message` and adds its own copy.
 *
 * @property code The frozen, machine-readable code. See [SttErrorCode].
 * @property message A terse machine-facing description.
 * @property platformDetail The raw platform error integer, when one caused this error; null
 *   otherwise (and always null on the web backend's equivalent).
 */
public class SttError(
    public val code: SttErrorCode,
    public val message: String,
    public val platformDetail: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttError) return false
        return code == other.code &&
            message == other.message &&
            platformDetail == other.platformDetail
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (platformDetail ?: 0)
        return result
    }

    override fun toString(): String =
        "SttError(code=${code.value}, message=$message, platformDetail=$platformDetail)"
}

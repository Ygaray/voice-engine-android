package io.github.ygaray.sttengine

/**
 * A NON-fatal, transient warning. Translated from `web/src/errors.ts:51-65`, where it carries
 * outbound-backpressure shedding notices. A warning never changes state and is rendered separately
 * from the error banner.
 *
 * **This type is NEVER EMITTED in v1c (D-17).** It exists for structural parity with the web
 * surface so a host can write ONE `when` over engine events and share it across both backends
 * without a backend-specific branch. The reason it cannot fire on Android is structural, not a
 * to-do: the recognizer *service* owns the microphone, so this library never buffers outbound
 * audio and there is no backpressure analog to warn about. If a future server-backed Android
 * backend is added (D-13), it will have a send buffer and this type will start carrying traffic —
 * which is exactly why it is defined now rather than added later as a breaking change.
 *
 * [code] is a `String` rather than the web's `'backpressure'` literal union, because Kotlin has no
 * string-literal types and an `enum` here would make a future warning kind source-breaking for
 * consumers — the same reasoning [SttErrorCode] documents at length.
 *
 * @property code The warning kind. Only `backpressure` is defined by the contract.
 * @property active True while the condition holds; false once it clears.
 * @property message A terse machine-facing description; friendly copy belongs to the host UI.
 */
public class SttWarning(
    public val code: String,
    public val active: Boolean,
    public val message: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttWarning) return false
        return code == other.code && active == other.active && message == other.message
    }

    override fun hashCode(): Int {
        var result = code.hashCode()
        result = 31 * result + active.hashCode()
        result = 31 * result + message.hashCode()
        return result
    }

    override fun toString(): String = "SttWarning(code=$code, active=$active, message=$message)"
}

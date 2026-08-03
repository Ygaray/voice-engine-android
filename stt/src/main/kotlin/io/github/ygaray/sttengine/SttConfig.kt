package io.github.ygaray.sttengine

/**
 * Options for creating an engine. Translated from `web/src/types.ts:32-39`
 * (`SttEngineOptions`), with one field kept and two dropped.
 *
 * A plain class with a defaulted parameter, NOT a `data class` (D-12) — see [FinalSegment].
 *
 * **`serverUrl` and `sampleRate` are DROPPED, not stubbed (D-13).** There is no server and no wire
 * format on this backend: the recognizer service owns the microphone end to end, so both fields
 * would be meaningless knobs a host could set and watch do nothing. Stubbing them would be worse
 * than omitting them — it would advertise a capability that does not exist. If a server-backed
 * Android backend is added in future it gets its OWN factory and its OWN config type carrying
 * those fields, which is precisely how the backend seam gets proven rather than assumed.
 *
 * @property language Passed straight through to `RecognizerIntent.EXTRA_LANGUAGE`. v1 accepts
 *   `"en"`; the field exists from day one so v2 multilingual is ADDITIVE rather than a breaking
 *   contract change — the same day-one rationale `web/src/types.ts:37` records.
 */
public class SttConfig(
    public val language: String = "en",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SttConfig) return false
        return language == other.language
    }

    override fun hashCode(): Int = language.hashCode()

    override fun toString(): String = "SttConfig(language=$language)"
}

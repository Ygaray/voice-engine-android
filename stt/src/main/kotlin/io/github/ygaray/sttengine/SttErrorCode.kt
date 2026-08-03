package io.github.ygaray.sttengine

/**
 * Frozen error taxonomy — the durable cross-language contract (D-08/D-14).
 *
 * [SHARED] mirrors `web/src/errors.ts` (`ERROR_CODES` + `CLIENT_ERROR_CODES`) VERBATIM, which in
 * turn mirrors `server/src/stt_server/protocol.py` `ERROR_CODES`. These eleven kebab-case strings
 * exist in three languages; **a rename is a breaking cross-language event** in all three at once.
 * Define them once, freeze the strings.
 *
 * The mirror is not maintained by convention. It is enforced by two gates:
 *  - `ErrorCodeParityTest` parses `web/src/errors.ts` and asserts set-equality with [SHARED]
 *    (D-19; skipped in the public split mirror where `web/` does not exist, hard-failed when
 *    `-Pstt.requireWebParity=true`, which the release script always passes).
 *  - `scripts/check-error-code-parity.sh` at the repo root compares all THREE sources, because the
 *    repo root is the only place Python, TypeScript and Kotlin coexist.
 *
 * Friendly hint text is intentionally NOT baked in here — hints belong to the demo/host, not the
 * frozen contract (`web/src/errors.ts:12-14`). [SttError] is just `{ code, message }` plus the
 * platform-local `platformDetail`.
 *
 * **Why a `value class` over `String` and NOT an `enum` (D-12).** `RecognitionListener.onError`'s
 * own javadoc requires callers to handle integer error values outside its documented set, so the
 * Android backend must be able to produce a code it did not anticipate. With an `enum`, every code
 * added in a future version would break any consumer's exhaustive `when` at compile time — a
 * source-breaking change forced on hosts by a platform detail they never asked about. With a value
 * class the set stays open, an unknown recognizer integer maps to [INTERNAL_ERROR] carrying the
 * raw value in [SttError.platformDetail], and no consumer's error handling can be broken by it
 * (threat T-05-02). The `@JvmInline` erasure means a code costs no allocation on the hot path.
 */
@JvmInline
public value class SttErrorCode(public val value: String) {

    public companion object {

        // --- BEGIN SHARED — the frozen 11, byte-identical with web/src/errors.ts -----------------
        // Machine-readable markers: scripts/check-error-code-parity.sh extracts the string literals
        // between BEGIN SHARED and END SHARED. Do not put an Android-only code inside this block.

        // The seven server-originated codes (protocol.py:39-49 → errors.ts ERROR_CODES).

        /** The client's protocol version is not the one the server speaks. */
        public val VERSION_MISMATCH: SttErrorCode = SttErrorCode("version-mismatch")

        /** The server is already serving its single permitted session. */
        public val BUSY: SttErrorCode = SttErrorCode("busy")

        /** The config frame was malformed or carried an out-of-contract value. */
        public val INVALID_CONFIG: SttErrorCode = SttErrorCode("invalid-config")

        /** The requested language is outside the accepted set. */
        public val UNSUPPORTED_LANGUAGE: SttErrorCode = SttErrorCode("unsupported-language")

        /** The requested sample rate is outside the accepted set. */
        public val UNSUPPORTED_SAMPLE_RATE: SttErrorCode = SttErrorCode("unsupported-sample-rate")

        /** A frame arrived that the protocol does not permit in the current state. */
        public val PROTOCOL_VIOLATION: SttErrorCode = SttErrorCode("protocol-violation")

        /**
         * An unexpected internal failure. Originated by EITHER side — the server, or a client
         * library. On Android it is the `else` branch of the recognizer error table: an integer
         * outside the documented set becomes `internal-error` plus the raw value in
         * [SttError.platformDetail].
         */
        public val INTERNAL_ERROR: SttErrorCode = SttErrorCode("internal-error")

        // The four codes a client originates locally (errors.ts CLIENT_ERROR_CODES).

        /** The microphone is unavailable to the engine — on Android, the HOST holds the permission (D-11). */
        public val MIC_DENIED: SttErrorCode = SttErrorCode("mic-denied")

        /** The server could not be reached. Structurally unreachable on the Android backend (no server). */
        public val SERVER_UNREACHABLE: SttErrorCode = SttErrorCode("server-unreachable")

        /** The bounded recovery budget ran out; the session is terminal. */
        public val RECONNECT_EXHAUSTED: SttErrorCode = SttErrorCode("reconnect-exhausted")

        /** The outbound audio buffer overflowed. Structurally unreachable on Android (D-17, see [SttWarning]). */
        public val SEND_BACKPRESSURE: SttErrorCode = SttErrorCode("send-backpressure")

        // --- END SHARED -------------------------------------------------------------------------

        // --- BEGIN ANDROID_ONLY — additive, and NEVER added to the TypeScript union (D-14/D-15) --
        //
        // These three describe conditions that only exist when recognition runs on-device against
        // the platform SpeechRecognizer. They are deliberately kept OUT of the shared set and OUT
        // of `web/src/errors.ts`: widening a closed union silently breaks exhaustive handling in
        // every already-shipped web host, and nothing in this Kotlin build would catch it.

        /** No on-device recognition service is available on this device at all. */
        public val RECOGNIZER_UNAVAILABLE: SttErrorCode = SttErrorCode("recognizer-unavailable")

        /**
         * The language is supported on-device but its model is not installed (or its download is
         * still pending). Recoverable — the host can trigger a model download.
         */
        public val NO_OFFLINE_MODEL: SttErrorCode = SttErrorCode("no-offline-model")

        /** The recognizer reported an audio-capture failure. */
        public val AUDIO_ERROR: SttErrorCode = SttErrorCode("audio-error")

        // --- END ANDROID_ONLY -------------------------------------------------------------------

        /**
         * The eleven codes shared with the TypeScript and Python mirrors. **This is the set the
         * parity gates assert on** — it must equal `ERROR_CODES + CLIENT_ERROR_CODES` in
         * `web/src/errors.ts`, exactly, forever.
         */
        public val SHARED: Set<SttErrorCode> = setOf(
            VERSION_MISMATCH,
            BUSY,
            INVALID_CONFIG,
            UNSUPPORTED_LANGUAGE,
            UNSUPPORTED_SAMPLE_RATE,
            PROTOCOL_VIOLATION,
            INTERNAL_ERROR,
            MIC_DENIED,
            SERVER_UNREACHABLE,
            RECONNECT_EXHAUSTED,
            SEND_BACKPRESSURE,
        )

        /**
         * The three codes this platform adds (D-14). Kept in a separately named set so they can
         * never be confused for shared contract, and so the parity gates can exclude them
         * mechanically rather than by a hand-maintained ignore list.
         */
        public val ANDROID_ONLY: Set<SttErrorCode> = setOf(
            RECOGNIZER_UNAVAILABLE,
            NO_OFFLINE_MODEL,
            AUDIO_ERROR,
        )
    }
}

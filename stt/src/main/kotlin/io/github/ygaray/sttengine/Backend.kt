package io.github.ygaray.sttengine

/**
 * Which recognition backend is actually driving the current session — Android-only, additive
 * observable metadata (SEL-03, D-01).
 *
 * This never widens the shared [EngineState]/[SttEvent] unions: it rides alongside [SttEngine.state]
 * as its own [kotlinx.coroutines.flow.StateFlow], exactly the way `SttError.platformDetail` was
 * added as an additive optional field rather than widening `SttErrorCode` (D-16). The web backend has
 * no equivalent — there is only ever one backend there.
 *
 * **v2 caveat, mirroring [EngineState]'s own documented tradeoff.** Adding a third value is
 * SOURCE-BREAKING for any consumer that writes an exhaustive `when (backend)` without an `else`
 * branch. An `enum` is nonetheless the right shape: this set is closed by design (there are exactly
 * two backends this library can drive a session on) and a host genuinely wants the compiler to know
 * about a new one, the same reasoning [EngineState]'s KDoc gives.
 */
public enum class Backend {
    /** The session is running against the self-hosted STT server over a WebSocket (SEL-01). */
    Server,

    /** The session is running against this device's on-device `SpeechRecognizer` (v1.0, unchanged). */
    Native,
}

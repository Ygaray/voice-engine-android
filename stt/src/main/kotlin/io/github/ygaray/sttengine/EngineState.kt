package io.github.ygaray.sttengine

/**
 * The engine's observable state machine — a translation of `web/src/types.ts:11`:
 *
 * ```typescript
 * export type EngineState = 'idle' | 'connecting' | 'listening' | 'reconnecting' | 'error';
 * ```
 *
 * The five names are frozen and shared with the web engine so a host can render one state banner
 * against either backend (AND-01).
 *
 * [wireName] carries the exact lowercase string the TypeScript union uses. It exists so logs,
 * banners and the demo's diagnostic surface render the *same* strings the web demo shows — a
 * `toString()` of the enum entry would render `Idle`, not `idle`, and the cross-language parity a
 * host sees would silently be cosmetic-only.
 *
 * **v2 caveat (D-12).** Adding a sixth value is SOURCE-BREAKING for any consumer that writes an
 * exhaustive `when (state)` without an `else` branch: their code stops compiling against the new
 * version. It is binary-compatible but not source-compatible. An `enum` is nonetheless the right
 * shape here — unlike [SttErrorCode], this set is closed by design and a host genuinely wants the
 * compiler to tell it about a new state.
 */
public enum class EngineState(public val wireName: String) {
    /** No session. The initial state and the state `stop()` returns to. */
    Idle("idle"),

    /**
     * A session is being brought up: permission and capability preflight, then recognizer
     * construction and the first `startListening`. On the web backend this is the WebSocket
     * handshake; on Android there is no socket, but the state exists so the host's rendering is
     * backend-independent.
     */
    Connecting("connecting"),

    /** The steady state — audio is being recognized and partials are flowing. */
    Listening("listening"),

    /**
     * A bounded recovery attempt is in flight. **Not** emitted for an ordinary segment restart
     * (D-17): the restart-on-result loop that makes continuous dictation possible would otherwise
     * flash this state after every pause. Only genuine recovery reaches it.
     */
    Reconnecting("reconnecting"),

    /** Terminal. A typed [SttError] was emitted exactly once and the session is over. */
    Error("error"),
}

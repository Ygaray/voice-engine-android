package io.github.ygaray.sttengine

/**
 * How strongly a host wants a particular [Backend] for this session (SEL-02, D-01).
 *
 * Passed via [SttConfig.prefer]. [Auto] is the default: try the server first, bounded by a short
 * timeout, and fall back to the on-device recognizer silently on timeout/unreachable/busy (SEL-01,
 * D-04) — a typed [SttError] only reaches the host when native is also unavailable.
 *
 * A closed Kotlin `enum`, not an open string-based type like [SttErrorCode] — this is a small,
 * host-facing, non-extensible-by-design configuration knob (unlike `SttErrorCode`, which must absorb
 * arbitrary platform integers it cannot anticipate), so the usual enum-widening-is-source-breaking
 * caveat applies but is acceptable here (mirrors [EngineState]'s own tradeoff).
 */
public enum class BackendPreference {
    /** Force the server backend. A try-server failure surfaces a typed error rather than falling
     *  back to native (SEL-02) — no fallback in this mode. */
    Server,

    /** Force the on-device backend. The server is never attempted, not even opportunistically —
     *  no [ServerBackend][io.github.ygaray.sttengine.internal.ServerBackend]/OkHttp construction
     *  happens at all on this path (SEL-02). */
    OnDevice,

    /** Try the server first, bounded by a short timeout; fall back to native silently on
     *  timeout/unreachable/busy (SEL-01, D-04). The default. */
    Auto,
}

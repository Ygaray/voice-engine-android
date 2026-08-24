package io.github.ygaray.sttengine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The public engine contract a host consumes — the Kotlin translation of `web/src/types.ts:60-70`
 * (AND-01, D-07/D-09/D-10).
 *
 * The web contract is `on(event, cb)` returning an unsubscribe handle. Kotlin's idiomatic
 * equivalent is a set of cold/hot Flows, so the translation swaps the *mechanism* while keeping
 * every event name and payload identical. A host that renders state and errors for the web engine
 * can share that code here, because [EngineState], [SttError] and [SttErrorCode] are the same
 * names carrying the same strings.
 *
 * **Delivery semantics differ per property, deliberately** — they are part of the contract, not an
 * implementation detail, because choosing the wrong one silently loses dictated text:
 *
 * | Property | Shape | Why |
 * |---|---|---|
 * | [state] | `StateFlow` | current-value replay; a recomposing Compose host reads it immediately |
 * | [partial] | `StateFlow` | "replaces previous" IS StateFlow conflation |
 * | [finals] | `Flow`, LOSSLESS | committed text may never be dropped |
 * | [errors] | `Flow` | at most one per session (first-error-wins) |
 * | [level] | `Flow`, conflated | a stale meter frame is worthless; drop it |
 * | [events] | `Flow` | the merged union, for one shared `when` |
 *
 * **The one contract asymmetry versus the web engine (D-11):** the HOST app must hold the
 * `RECORD_AUDIO` runtime permission. This library has no Activity, so it cannot request one — it
 * only CHECKS, and reports a missing grant as a typed [SttErrorCode.MIC_DENIED] error. On the web
 * backend the engine itself triggers the browser's permission prompt. A host integrating this
 * library must request the permission before calling [start].
 */
public interface SttEngine {

    /**
     * The state machine's current value, replayed to every new collector.
     *
     * Intended for `collectAsStateWithLifecycle()` in a Compose host: a collector that attaches
     * after a transition still sees the current state rather than waiting for the next one.
     */
    public val state: StateFlow<EngineState>

    /**
     * Which [Backend] is actually driving the current session — Android-only additive observable
     * metadata (SEL-03, D-01, D-03). Never widens [state]/[SttEvent]: a host that does not care never
     * has to touch it, unlike a `when` over [SttEvent], which every existing host already writes.
     *
     * [Backend.Native] on an engine constructed with no `serverUrl` (or
     * [BackendPreference.OnDevice]) for the entirety of its life — the v1.0 default path never
     * changes value here. On a session that tried the server first (SEL-01) this flips to
     * [Backend.Server] once the try-server window succeeds, and back to [Backend.Native] one-way on
     * a mid-session drop (SEL-04, D-03) — it never flips back to [Backend.Server] within the same
     * session.
     */
    public val backend: StateFlow<Backend>

    /**
     * The live, in-progress transcript text. Latest-wins.
     *
     * A `StateFlow` because conflation IS the desired semantic here: a partial that has been
     * superseded is worthless, and dropping it is correct rather than lossy. Empty string when
     * there is no partial in flight.
     */
    public val partial: StateFlow<String>

    /**
     * Committed, immutable transcript segments. **LOSSLESS and single-consumer by construction.**
     *
     * This is backed by an unlimited channel, not a replay-0 shared flow, and the difference is
     * load-bearing: with a shared flow, every time a lifecycle-aware host detached on backgrounding
     * — which is normal, expected behaviour for a dictation app whose user switches away mid-thought
     * — any final emitted in that window would be silently discarded. That is DATA LOSS of text the
     * user actually spoke, not a UI glitch. An unlimited channel buffers instead, so the host
     * receives everything on reattach.
     *
     * The cost of that guarantee is that this flow has exactly ONE consumer. A second collector
     * would steal segments from the first. Collect it once, at the highest level that outlives
     * configuration changes.
     */
    public val finals: Flow<FinalSegment>

    /**
     * Typed, terminal failures. At most one per session: the FIRST error wins, and every typed
     * failure routes through a single path so `state == Error` is reached exactly once.
     *
     * Permission denial arrives here as [SttErrorCode.MIC_DENIED] — a typed EVENT, never a thrown
     * exception. A host handles every failure mode through one `when (error.code)`.
     */
    public val errors: Flow<SttError>

    /**
     * Input level, normalized to `0.0..1.0`, for a live meter.
     *
     * Conflated, dropping oldest: a level frame is only meaningful at the instant it is produced,
     * so back-pressuring the recognizer callback to deliver a stale one would be strictly worse
     * than dropping it. Only meaningful while [state] is [EngineState.Listening].
     */
    public val level: Flow<Float>

    /**
     * All seven events as one ordered stream — the cross-language parity surface (D-07).
     *
     * A host that wants a single `when (event)` collects this; a host that wants Compose state
     * collects [state]/[partial] directly. Both are supported on purpose.
     *
     * Listener bodies must only forward — never block. Kotlin Flow decouples collectors from the
     * engine's state machine (unlike the web emitter, which drives its state machine THROUGH
     * `emit()` and therefore has to try/catch each handler), so a slow collector cannot corrupt
     * engine state here. It can still stall delivery of subsequent events to itself.
     */
    public val events: Flow<SttEvent>

    /**
     * Begin a session: preflight, construct the recognizer, and start recognizing.
     *
     * **Completes when the engine reaches [EngineState.Listening] OR on a terminal error (D-10)** —
     * and on the error path it RETURNS rather than throwing, having already emitted the typed error
     * on [errors]. That is what stops a mic-denied or recognizer-unavailable device from hanging
     * the caller forever on a state it will never reach. Inspect [state] after it returns, or
     * collect [errors]; do not rely on an exception.
     *
     * Requires the host to already hold `RECORD_AUDIO` (D-11); a missing grant is reported as
     * [SttErrorCode.MIC_DENIED].
     */
    public suspend fun start()

    /**
     * End the session and release the recognizer.
     *
     * The open partial is flushed as a final before teardown, so text the user had already spoken
     * is committed rather than dropped. Returns the engine to [EngineState.Idle]. Safe to call in
     * any state, including from a terminal error.
     */
    public suspend fun stop()
}

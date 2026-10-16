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
     * The final the most recent [stop] flushed — or, if nothing was open at stop, the session's
     * latest final (LAST-01, D-05, D-15). `null` before the first final of the current session: it
     * is reset by [start], so a reused engine never reports a previous session's text.
     *
     * Readable **synchronously as soon as [stop] returns**, with no [finals] collector kept alive
     * across the stop — a host that wants the tail of a dictation (to insert into a note, say) reads
     * it here instead of racing the lossless flow. It is the SAME [FinalSegment] instance that
     * [finals] delivers (same text, `segmentId` and `language`); [finals] is unchanged and still
     * delivers it.
     *
     * `language` can be `null` for a stop-flushed native tail under `"auto"` on API 34+ when the
     * session closed before a confident detection (NBIL-02) — "unknown", not "English".
     *
     * A terminal error does not clear it; only [start] does. [stop] still returns `Unit`.
     *
     * Defaulted to `null` so existing implementers (including host test fakes) stay source- and
     * binary-compatible; a fake may override it to drive host logic.
     */
    public val lastFinal: FinalSegment? get() = null

    /**
     * The corpus session id of the session that just ended (CORR-07), or `null`.
     *
     * Non-null only after [stop] on a session that opted in with [SttConfig.retainForCorpus], ran on
     * the server backend, never failed and was never handed off to the on-device recognizer, and whose
     * server finished its stop path by closing cleanly. The server attempts the corpus commit before
     * that close, so the observed clean close is the earliest moment the record can exist. This does
     * NOT guarantee the record was stored: a server-side retention failure is not signalled to the
     * client, and a later `submitCorrection` for such an id returns `UNKNOWN_SESSION`.
     *
     * Readable after [stop] returns, and also once the engine has reported [EngineState.Idle] after
     * it (the id is published before Idle). Reset by [start]. `null` on every other path: while a
     * session is live, after a flush that timed out, an error, an abnormal close, a mid-session
     * fallback to the on-device recognizer, a stop during the try-server window, any native session,
     * a server that sent no id, and a session that did not request retention.
     *
     * The inverse also holds: a `null` after a flush that timed out does not prove nothing was
     * stored. [stop] bounds the wait for the server's tail flush at about 2.5 s, and on a retained
     * session the server commits the record inside that same window. If a long recording or a slow
     * corpus disk pushes the commit past the bound, the server may still finish it, leaving a record
     * that no client holds a handle for, and that record cannot be corrected.
     *
     * The id is the handle for `submitCorrection` and is a capability to append corrections to that
     * record: treat it like any identifier you would not publish.
     *
     * Defaulted to `null` so existing implementers (including host test fakes) stay source- and
     * binary-compatible; a fake may override it to drive host logic.
     */
    public val lastSessionId: String? get() = null

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
     *
     * **Latency on the server backend:** `stop()` sends the protocol `stop` token and waits up to
     * about 2.5 s for the server to flush the tail (committing the open partial locally if the
     * flush does not finish in time), so the flushed tail is on [finals] and in [lastFinal] when it
     * returns. It cannot be cut short by cancelling the caller. On the native backend it returns
     * promptly. Call it from a coroutine rather than blocking a UI thread on it.
     */
    public suspend fun stop()
}

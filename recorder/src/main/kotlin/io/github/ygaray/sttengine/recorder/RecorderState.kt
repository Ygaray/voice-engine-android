package io.github.ygaray.sttengine.recorder

/**
 * The Recorder's observable state machine — a DISTINCT enum from [io.github.ygaray.sttengine.EngineState]
 * (D-04). The Recorder and the recognition engine are separate capabilities: a record-only consumer
 * never touches [io.github.ygaray.sttengine.SttEngine] and must never be forced to reason about
 * recognition's five-state machine to render a four-state recording UI.
 *
 * Exactly four values, by locked design (UI-SPEC Contract Surface Sec.1): there is no fifth
 * transient/starting state. `state` stays [Idle] until [Recorder.start] either reaches [Recording]
 * or surfaces a typed error via [Recorder.errors] (pushing [Error]) — a host never needs to render an
 * in-between "starting" banner.
 *
 * [wireName] mirrors [io.github.ygaray.sttengine.EngineState.wireName]'s rationale: the lowercase
 * string is what a log line or diagnostic surface should render, not `toString()`'s `Idle`/`Recording`.
 */
public enum class RecorderState(public val wireName: String) {
    /** No active recording session. The initial state and the state [Recorder.stop] returns to. */
    Idle("idle"),

    /** A session is actively capturing audio to the output file. */
    Recording("recording"),

    /** The session is suspended: the underlying file is untouched until [Recorder.resume]. */
    Paused("paused"),

    /** Terminal for the current session. A typed [RecorderError] was pushed on [Recorder.errors]. */
    Error("error"),
}

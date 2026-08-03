package io.github.ygaray.sttengine

/**
 * One engine event, as a closed union. Translated from `web/src/types.ts:48-58` (`SttEventMap`) —
 * the same seven events, in the same order, carrying the same payloads.
 *
 * The typed per-event flows on [SttEngine] are what a Compose host normally collects. This union
 * exists in addition to them (D-07) so a host can consume [SttEngine.events] as ONE ordered stream
 * and write ONE `when` — which is the shape a shared cross-backend handler takes, and the reason
 * the event names had to survive translation unchanged.
 *
 * Members are plain classes, not `data class`es (D-12) — see [FinalSegment].
 *
 * `sealed` deliberately makes an eighth event source-breaking for an exhaustive consumer `when`,
 * the same trade-off [EngineState] documents. This set is closed by design; [SttErrorCode], which
 * is not, is a value class for exactly that reason.
 */
public sealed interface SttEvent {

    /** Live, in-progress text. Latest-wins: each partial REPLACES the previous one. */
    public class Partial(public val text: String) : SttEvent {
        override fun toString(): String = "Partial(text=$text)"
    }

    /** A committed, immutable segment. Lossless — see [SttEngine.finals]. */
    public class Final(public val segment: FinalSegment) : SttEvent {
        override fun toString(): String = "Final(segment=$segment)"
    }

    /** A state-machine transition. */
    public class State(public val state: EngineState) : SttEvent {
        override fun toString(): String = "State(state=${state.wireName})"
    }

    /** A typed, terminal failure. Emitted exactly once per session (first-error-wins). */
    public class Error(public val error: SttError) : SttEvent {
        override fun toString(): String = "Error(error=$error)"
    }

    /** Input level, normalized to `0.0..1.0`, for a live meter. Conflated — drops are expected. */
    public class Level(public val value: Float) : SttEvent {
        override fun toString(): String = "Level(value=$value)"
    }

    /** A bounded recovery attempt is now in flight. Not emitted for ordinary restarts (D-17). */
    public class Reconnecting(public val info: ReconnectInfo) : SttEvent {
        override fun toString(): String = "Reconnecting(info=$info)"
    }

    /** A non-fatal transient warning. Never emitted on this backend (D-17) — see [SttWarning]. */
    public class Warning(public val warning: SttWarning) : SttEvent {
        override fun toString(): String = "Warning(warning=$warning)"
    }
}

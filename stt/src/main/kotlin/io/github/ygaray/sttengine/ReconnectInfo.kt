package io.github.ygaray.sttengine

/**
 * The payload of a reconnect event: which bounded attempt is now in flight and the ceiling.
 *
 * Translated from `web/src/types.ts:22-30`. [attempt] is **1-based** (the first reconnect is
 * `attempt = 1`) so a host can render "attempt {n} of {max}" directly.
 *
 * Not a `data class`, per D-12 — see [FinalSegment] for the full reasoning.
 *
 * @property attempt The 1-based index of the attempt now in flight.
 * @property max The ceiling; once [attempt] reaches it the engine gives up with
 *   `reconnect-exhausted` and goes terminal.
 */
public class ReconnectInfo(
    public val attempt: Int,
    public val max: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReconnectInfo) return false
        return attempt == other.attempt && max == other.max
    }

    override fun hashCode(): Int = 31 * attempt + max

    override fun toString(): String = "ReconnectInfo(attempt=$attempt, max=$max)"
}

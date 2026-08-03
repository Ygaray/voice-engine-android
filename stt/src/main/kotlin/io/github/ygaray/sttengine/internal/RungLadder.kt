package io.github.ygaray.sttengine.internal

/** How a recognition session came to an end. Pure data — no platform types. */
internal enum class TerminatingCause {
    /** The session ended by delivering a final (`onResults`), blank or otherwise. */
    FINAL,

    /** The session ended by delivering `onEndOfSegmentedSession` — only possible in segmented mode. */
    SEGMENTED_SESSION_END,

    /** The session ended via `onError`. See [SessionOutcome.errorCode]. */
    ERROR,

    /** The host called `stop()`, or the engine tore the session down for its own reasons. */
    STOPPED_BY_HOST,
}

/**
 * What one recognition session actually did, reduced to the four facts the degradation trigger needs.
 *
 * Deliberately a small pure holder rather than a live reference to session state: the trigger runs
 * *after* the session is over, and a snapshot cannot be corrupted by a late callback from the very
 * session it is describing.
 *
 * @property cause how the session ended.
 * @property endOfSegmentedSessionDelivered whether `onEndOfSegmentedSession` was ever delivered.
 *   This is the crisp binary discriminator for rung (b): the callback is delivered *only* in
 *   segmented mode, so its absence across a whole session is proof the extra was ignored.
 * @property segmentResultCount how many `onSegmentResults` callbacks arrived.
 * @property appOwnedPipeOpen whether **our** `ParcelFileDescriptor` write end was still open when
 *   the session ended. Distinguishes the upstream data-loss signature from our own teardown.
 * @property errorCode the raw `onError` integer when [cause] is [TerminatingCause.ERROR].
 */
internal class SessionOutcome(
    val cause: TerminatingCause,
    val endOfSegmentedSessionDelivered: Boolean,
    val segmentResultCount: Int,
    val appOwnedPipeOpen: Boolean,
    val errorCode: Int? = null,
) {
    override fun toString(): String =
        "SessionOutcome(cause=$cause, endOfSegmentedSession=$endOfSegmentedSessionDelivered, " +
            "segments=$segmentResultCount, pipeOpen=$appOwnedPipeOpen, errorCode=$errorCode)"
}

/**
 * The capability ladder (D-01), as **pure functions only**.
 *
 * No session state, no Android types, no timers. The reference implementation deliberately pulled
 * its candidate list and advance predicate out of its manager class so they could be tested; this
 * goes further and keeps the whole ladder free of anything a device could influence.
 *
 * That is what makes [candidates] take the device's capabilities as *parameters*: plan 09 can flip a
 * rung on or off straight from the probe verdict without touching a line of session logic, and the
 * consequence is visible in a JVM test in milliseconds rather than on a device.
 *
 * ## Degradation is one way, within a session
 *
 * Never climb back up mid-session. A device that ignores an extra will ignore it again, and
 * re-probing costs a deaf window every single time. Capabilities are re-evaluated at the next
 * `start()`, which is the only place new evidence could have appeared.
 */
internal object RungLadder {

    /**
     * `ERROR_CLIENT`. Matched as a **raw integer literal** with the symbolic name here in the
     * comment, deliberately: the taxonomy is about *values received* from an OEM service, not about
     * APIs this library calls, and reading `SpeechRecognizer.ERROR_*` symbols would drag a platform
     * import into an otherwise pure file for no gain.
     */
    private const val ERROR_CLIENT = 5

    /**
     * The rungs to try on this device, ordered most capable → terminal.
     *
     * Always ends with [Rung.RestartingSingleSession]: it is mandatory, unconditional, and the floor
     * the ladder degrades onto.
     *
     * @param supportsSegmented whether this device honours `EXTRA_SEGMENTED_SESSION` (the D-04 probe
     *   answers this; the discriminator is whether `onEndOfSegmentedSession` is ever delivered).
     * @param supportsRawAudio whether an app-owned `AudioRecord` → pipe audio source is viable here.
     *   Ignored unless [supportsSegmented] is also true — rung (c) **is** a segmented session
     *   (`EXTRA_SEGMENTED_SESSION` names `EXTRA_AUDIO_SOURCE`), so offering it to a device that
     *   ignores segmentation would produce a session that behaves like rung (b) while wearing rung
     *   (c)'s costs, and would then degrade for a reason that has nothing to do with raw audio.
     */
    fun candidates(supportsSegmented: Boolean, supportsRawAudio: Boolean): List<Rung> = buildList {
        if (supportsSegmented) {
            if (supportsRawAudio) add(Rung.RawAudioSegmented)
            add(Rung.SilenceSegmented)
        }
        add(Rung.RestartingSingleSession)
    }

    /**
     * The next rung **down** from [current], or [current] itself when it is already the floor.
     *
     * Because [candidates] is ordered most capable first, "down" is simply the next index — so this
     * function structurally cannot return a higher rung, whatever it is fed. A [current] that is not
     * in [candidates] at all (a stale rung after the device's capabilities were re-evaluated) yields
     * the floor, which is always safe and always available.
     */
    fun next(current: Rung, candidates: List<Rung>): Rung {
        val index = candidates.indexOf(current)
        if (index < 0 || index >= candidates.lastIndex) return Rung.RestartingSingleSession
        return candidates[index + 1]
    }

    /**
     * Whether [current] proved itself a no-op (or a hazard) during the session [outcome] describes.
     *
     * The trigger tests **evidence that the rung did not do its job**, never merely that an error
     * occurred. Errors are routine — `ERROR_NO_MATCH` fires on any silence — and degrading on one
     * would walk a perfectly good device down to the floor within seconds, discarding rung (b)'s
     * measured 57-second no-restart session for nothing.
     *
     * | Rung | Degrades when | Why that is proof |
     * |---|---|---|
     * | (c) [Rung.RawAudioSegmented] | `ERROR_CLIENT(5)` while **our** pipe is open, or zero segment results | The upstream data-loss signature exactly; and a rung that never segments is a no-op wearing a costume |
     * | (b) [Rung.SilenceSegmented] | ended on a final with `onEndOfSegmentedSession` never delivered | That callback exists only in segmented mode, so its absence is a *proven* silent no-op. One session is enough evidence |
     * | (a) [Rung.RestartingSingleSession] | never | Terminal. There is nothing below it |
     *
     * A session the host stopped is never evidence against a rung: the rung was not given the chance
     * to do its job, and code 5 arriving at our own teardown is *our* teardown, not a failure. The
     * probe measured exactly that shape — `stopListening()` on a raw-audio segmented session
     * produced `onError(5)` **after** all six segment finals had already been delivered. Degrading
     * on it would abandon a rung that scored precision 1.000.
     */
    fun shouldDegrade(current: Rung, outcome: SessionOutcome): Boolean {
        if (current == Rung.RestartingSingleSession) return false
        if (outcome.cause == TerminatingCause.STOPPED_BY_HOST) return false

        return when (current) {
            Rung.RawAudioSegmented -> {
                val dataLossSignature = outcome.cause == TerminatingCause.ERROR &&
                    outcome.errorCode == ERROR_CLIENT &&
                    outcome.appOwnedPipeOpen
                val neverSegmented = outcome.segmentResultCount == 0
                dataLossSignature || neverSegmented
            }

            Rung.SilenceSegmented ->
                outcome.cause == TerminatingCause.FINAL && !outcome.endOfSegmentedSessionDelivered

            Rung.RestartingSingleSession -> false
        }
    }
}

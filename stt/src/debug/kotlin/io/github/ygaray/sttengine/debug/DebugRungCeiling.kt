package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.internal.DebugRungCeilingHolder
import io.github.ygaray.sttengine.internal.Rung

/**
 * Cap how high the capability ladder may climb. **Debug builds only, and structurally so.**
 *
 * ## What this is for
 *
 * AND-02 is about dictation surviving **forced restarts**, and on this device the shipped ladder
 * never produces one. Rung (c) binds the end of a recognition session to the exhaustion of the
 * app-owned audio source, and an app-owned source — a microphone, or the paced feed
 * [DebugAudioSource] arms — never exhausts. Measured on the tester: a 140 s file-fed run and a 75 s
 * quiet-microphone run each recorded **zero** restart events and zero errors, and neither degraded,
 * because a session the host stopped is never evidence against a rung.
 *
 * Rung (a) `RestartingSingleSession` is where the restart loop lives: it restarts on every final, and
 * a two-minute passage crosses a dozen of them. Asking for it is the only way to put a real,
 * restart-crossing session in front of the analyzer.
 *
 * ## The trade this cap makes, stated plainly
 *
 * Rung (a) does **not** accept an app-owned audio source — the recognition service owns the
 * microphone there — so a capped run cannot also be file-fed. The two seams are mutually exclusive by
 * construction, and that is not a limitation to work around: it is why the AND-02 evidence is two
 * runs rather than one. The file-fed run on rung (c) carries the text-integrity claim with perfect
 * recall and no restarts; the capped run on rung (a) carries the restart-boundary claim over an
 * acoustic stimulus. Neither run can make the other's claim, and reporting them as one number would
 * hide exactly that.
 *
 * ## Why it is safe for the shipped library
 *
 * This whole file lives in `stt/src/debug/`. The debug source set shares a Kotlin compilation with
 * `main` for the debug variant — which is how it can see the `internal` [DebugRungCeilingHolder] and
 * [Rung] — and is absent from `compileReleaseKotlin` entirely. `api/stt.api` is a dump of the
 * **release** compilation, so nothing here can reach the frozen public surface.
 *
 * The property that actually matters is the one `ReleaseRungCeilingSeamTest` asserts: the holder is
 * `null` in a release compilation **and nothing there can assign it**, because every assignment site
 * in the module is in this directory. A shipped build resolves the ladder exactly as it did before
 * the seam existed.
 *
 * ## Usage
 *
 * ```kotlin
 * DebugRungCeiling.restrictTo("RestartingSingleSession")
 * engine.start()          // the ladder is now [RestartingSingleSession]
 * // …
 * DebugRungCeiling.clear()
 * ```
 *
 * [restrictTo] **throws** on a name that is not a rung. Callers must let that failure be loud: a run
 * that believes it was capped but silently was not produces a plausible-looking trace and a
 * meaningless verdict, which is strictly worse than no run because it is evidence-shaped. The cap is
 * a *request* — the `rung` lines in the session trace are the fact, and the AND-02 harness checks
 * them rather than trusting this call.
 */
public object DebugRungCeiling {

    /** The requested ceiling's name, or `null` when the ladder is unrestricted. */
    public val ceiling: String? get() = DebugRungCeilingHolder.ceiling?.name

    /** Every legal argument to [restrictTo], highest rung first. */
    public val rungNames: List<String> get() = Rung.entries.map { it.name }

    /**
     * Cap the ladder at [rungName] for every session until [clear].
     *
     * Resolved and validated **now**, on the calling thread, so a typo fails at the moment a human
     * can still fix it rather than as a silently unrestricted two-minute run.
     *
     * @param rungName one of [rungNames], matched case-insensitively.
     * @return the canonical rung name that was set.
     * @throws IllegalArgumentException when [rungName] names no rung. The message always lists what
     *   was actually available.
     */
    public fun restrictTo(rungName: String): String {
        val rung = Rung.entries.firstOrNull { it.name.equals(rungName, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "\"$rungName\" is not a rung; expected one of ${Rung.entries.joinToString { it.name }}",
            )
        DebugRungCeilingHolder.ceiling = rung
        return rung.name
    }

    /** Return the ladder to the device's own answer. Idempotent, and safe when nothing was capped. */
    public fun clear() {
        DebugRungCeilingHolder.ceiling = null
    }

    /** One line naming the cap, for a log or a diagnostics sidecar. */
    public fun describe(): String =
        DebugRungCeilingHolder.ceiling?.let { "rung-ceiling=${it.name}" } ?: "rung-ceiling=none"
}

package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.internal.DebugSilenceExtrasHolder
import io.github.ygaray.sttengine.internal.SilenceExtrasOverride

/**
 * Change the silence extras of the recognition intent. **Debug builds only, and structurally so.**
 *
 * ## What this is for
 *
 * The Phase 26 endpointing spike varies `POSSIBLY_COMPLETE`, `COMPLETE` and `MINIMUM_LENGTH` through
 * the real engine on the tester (D-01). Rung (c) sets none of them today, so without this writer the
 * spike's arms (and, after the tune ships, the legacy-baseline arm and the three-way comparison)
 * cannot be produced. The override is applied after the pure intent builder, once per session
 * including every restart.
 *
 * ## Why it is safe for the shipped library
 *
 * This whole file lives in `stt/src/debug/`. The debug source set shares a Kotlin compilation with
 * `main` for the debug variant (which is how it can see the `internal` [DebugSilenceExtrasHolder]) and
 * is absent from `compileReleaseKotlin` entirely, so this **writer** cannot exist in a shipped build
 * and cannot reach the frozen `stt.api`. The null-initialized holder and the one read hook in the
 * engine do remain in the release compilation; nothing there can assign the cell.
 * `ReleaseSilenceExtrasSeamTest` asserts all of this.
 *
 * ## Usage
 *
 * ```kotlin
 * DebugSilenceExtras.configure(possiblyMs = 1000)               // set one
 * DebugSilenceExtras.configure(possiblyMs = DebugSilenceExtras.REMOVE) // delete one
 * engine.start()
 * DebugSilenceExtras.clear()
 * ```
 *
 * [configure] **throws** on a bad value. Callers must let that be loud: a run that believes it was
 * overridden but was not produces a plausible-looking trace and a meaningless verdict, which is
 * evidence-shaped noise. The override is a *request*; the demo's diagnostics dump echoes it
 * (`silenceExtras`) and the session trace's `rung` lines are the fact.
 */
public object DebugSilenceExtras {

    /** Sentinel for "delete this extra from the intent". Re-exported from the internal holder. */
    public const val REMOVE: Int = DebugSilenceExtrasHolder.REMOVE

    private const val MIN_MS = 1
    private const val MAX_MS = 60_000

    /**
     * Override the given extras for every session until [clear]. A `null` argument leaves that extra
     * exactly as the engine's builder produced it.
     *
     * Validated **now**, on the calling thread, before anything is written, so a typo fails at the
     * moment a human can still fix it and a refused call leaves the previous override untouched.
     *
     * @return [describe] after the write.
     * @throws IllegalArgumentException when no argument is given, or when a given value is neither
     *   [REMOVE] nor within 1..60000.
     */
    public fun configure(
        possiblyMs: Int? = null,
        completeMs: Int? = null,
        minimumMs: Int? = null,
    ): String {
        require(possiblyMs != null || completeMs != null || minimumMs != null) {
            "configure() was given nothing to change; pass at least one of possiblyMs, completeMs, " +
                "minimumMs (use clear() to drop an override)"
        }
        validate("possiblyMs", possiblyMs)
        validate("completeMs", completeMs)
        validate("minimumMs", minimumMs)
        DebugSilenceExtrasHolder.overrides = SilenceExtrasOverride(possiblyMs, completeMs, minimumMs)
        return describe()
    }

    /** Drop any override. Idempotent, and safe when nothing was set. */
    public fun clear() {
        DebugSilenceExtrasHolder.overrides = null
    }

    /**
     * One line naming the request, for a log or a diagnostics sidecar. A fixed grammar, because the
     * PAUSE-03 scorer and the spike driver parse it: exactly `silence-extras=none`, or exactly
     * `silence-extras=possibly:<v>,complete:<v>,minimum:<v>` where each `<v>` is the integer,
     * `remove`, or `unchanged`.
     */
    public fun describe(): String {
        val o = DebugSilenceExtrasHolder.overrides ?: return "silence-extras=none"
        return "silence-extras=possibly:${render(o.possibly)},complete:${render(o.complete)},minimum:${render(o.minimum)}"
    }

    private fun render(value: Int?): String = when (value) {
        null -> "unchanged"
        REMOVE -> "remove"
        else -> value.toString()
    }

    private fun validate(name: String, value: Int?) {
        if (value == null || value == REMOVE) return
        require(value in MIN_MS..MAX_MS) {
            "$name=$value is outside the allowed range $MIN_MS..$MAX_MS (or REMOVE=$REMOVE)"
        }
    }
}

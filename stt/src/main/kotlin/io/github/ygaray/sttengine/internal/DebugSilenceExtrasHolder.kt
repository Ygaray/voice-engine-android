package io.github.ygaray.sttengine.internal

import android.content.Intent
import android.speech.RecognizerIntent

/**
 * The one mutable cell that lets a **debug** build change the three silence-related extras of the
 * recognition intent at run time. `null` in every shipped build, and nothing in a shipping source set
 * can write it.
 *
 * ## What this is for
 *
 * The Phase 26 endpointing spike has to vary `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`,
 * `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` and `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`
 * through the **real engine** on the tester (D-01), and [RecognizerIntents.forRung] sets none of them
 * on rung (c) today. After the tune ships the same lever produces the legacy-baseline arm (REMOVE)
 * and the three-way comparison. The override is applied by [SttEngineImpl.startSession] AFTER
 * [RecognizerIntents.forRung] returns, so `forRung` stays a pure function and every existing caller
 * is byte-identical.
 *
 * ## Why the cell is in `main` and the writer is not
 *
 * [SttEngineImpl] builds the intent in `main`, so the cell it reads has to live here. Nothing in
 * `main` ever *writes* it: the only assignment site in the whole module is `stt/src/debug/`
 * (`DebugSilenceExtras`), which shares a Kotlin compilation with `main` for the debug variant (hence
 * it can see this `internal` cell) and is absent from `compileReleaseKotlin` entirely. This is the
 * exact shape of [DebugRungCeilingHolder], for the same reason.
 *
 * Stated plainly, so the property is not overstated: the **writer** is structurally absent from the
 * release build. This null-initialized holder and the one read hook in `SttEngineImpl.startSession`
 * DO remain in the release compilation, as a mutable cell in the release read path that nothing in a
 * shipping source set can assign. `ReleaseSilenceExtrasSeamTest` asserts all of that rather than
 * trusting it, and `api/stt.api` is unchanged because every symbol here is `internal`.
 *
 * ## The single-session assumption
 *
 * The cell is **process-global**. The demo hosts one engine session at a time, so a global is enough;
 * two concurrent engines in one process would share one override. The demo's debug receiver therefore
 * clears it on every START that carries no override and on STOP, and the spike driver ends every run
 * by force-stopping the demo process, which drops the cell.
 *
 * ## The mislabelling hazard
 *
 * An override is a **request**; the session trace's `rung` lines are the fact. A run that believes it
 * was overridden but was not is evidence-shaped noise, so the demo's diagnostics dump echoes the
 * request (`silenceExtras`) and the trace names the rung that actually ran.
 */
internal object DebugSilenceExtrasHolder {

    /** Sentinel meaning "delete this extra from the intent" (the future legacy-baseline arm). */
    const val REMOVE: Int = -1

    /**
     * The requested override, or `null` for the engine's own intent.
     *
     * `@Volatile` because it is written from whichever thread set it (a broadcast receiver on the
     * main looper, in practice) and read inside [SttEngineImpl.startSession], once per session
     * including every restart.
     */
    @Volatile
    internal var overrides: SilenceExtrasOverride? = null
}

/**
 * A request to change up to three silence extras of an already-built recognition intent.
 *
 * Each field is: `null` = leave the extra exactly as the builder produced it (whatever that is, tuned
 * or not), [DebugSilenceExtrasHolder.REMOVE] = delete the extra, any other value = put that many
 * milliseconds. A plain class, not a data class, to match the module's convention for anything that
 * could drift toward the public surface; it is `internal` and never does.
 */
internal class SilenceExtrasOverride(
    val possibly: Int?,
    val complete: Int?,
    val minimum: Int?,
) {

    /** Apply this request to [intent] in the order possibly, complete, minimum. */
    fun applyTo(intent: Intent) {
        apply(intent, RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, possibly)
        apply(intent, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, complete)
        apply(intent, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, minimum)
    }

    private fun apply(intent: Intent, key: String, value: Int?) {
        when (value) {
            null -> Unit
            DebugSilenceExtrasHolder.REMOVE -> intent.removeExtra(key)
            else -> intent.putExtra(key, value)
        }
    }
}

package io.github.ygaray.sttengine.demo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.ygaray.sttengine.demo.DictationViewModel
import java.io.File
import org.json.JSONObject

/**
 * The adb control surface: a deterministic, layout-independent handle on the running demo.
 *
 * ## Why this lives in `src/debug/` and nowhere else
 *
 * A manifest-declared `android:exported="true"` receiver is reachable by **any other application on
 * the device**. In a release build that is a remote-control surface for a dictation app — anything
 * installed could start a microphone session. Keeping the receiver in the debug source set makes its
 * absence from a release build **structural**: it is not compiled in, so there is no runtime flag to
 * get wrong, no `BuildConfig.DEBUG` check to forget, and no future refactor that can accidentally
 * ship it. A `if (BuildConfig.DEBUG)` guard would be one deleted line away from a security bug; this
 * is zero lines away from nothing.
 *
 * The same reasoning covers the session diagnostic. That trace records the user's dictated speech,
 * and the only thing in this app that can turn it on is [EXTRA_SESSION_LOG] on [ACTION_START] — an
 * extra on a receiver that cannot exist in a release build.
 *
 * ## Why broadcasts rather than taps
 *
 * `adb shell input tap` plus a uiautomator dump is a coordinate-dependent script that breaks on a
 * layout change, a font-size setting, or a different screen. An explicit broadcast addressed with
 * `-n` names a component and bypasses the implicit-broadcast restrictions entirely; the Android 14
 * exported-receiver requirement applies to runtime-registered receivers, not manifest-declared ones.
 *
 * ## Usage
 *
 * The demo screen must be up — the receiver drives the live view model and cannot conjure one:
 *
 * ```bash
 * S=100.113.42.22:1496
 * adb -s $S shell am start -n io.github.ygaray.sttengine.demo/.MainActivity
 * adb -s $S shell am broadcast -n io.github.ygaray.sttengine.demo/io.github.ygaray.sttengine.demo.debug.DemoControlReceiver \
 *   -a io.github.ygaray.sttengine.demo.debug.START \
 *   --ez io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG true \
 *   --es io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE en-US
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.DUMP_DIAGNOSTICS
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.STOP
 * ```
 *
 * **The five strings below are a cross-plan interface.** Plan 08's harness and plan 10's device run
 * are authored against them verbatim and plan 08's automated verify cross-greps its own strings
 * against this file. Renaming one here silently breaks both. `src/debug/AndroidManifest.xml` repeats
 * the three action names as literals because a manifest `<action android:name>` cannot reference a
 * Kotlin constant — that duplication is the one place drift can start, and both files say so.
 */
class DemoControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val viewModel = DictationViewModel.live
        if (viewModel == null) {
            // Not an error worth crashing over, but it IS the single most likely reason a harness
            // sees nothing happen — so say the fix, not just the symptom.
            Log.d(
                TAG,
                "no live dictation screen; run: am start -n " +
                    "io.github.ygaray.sttengine.demo/.MainActivity  (then re-send this broadcast)",
            )
            return
        }

        when (intent.action) {
            ACTION_START -> {
                val sessionLog = intent.getBooleanExtra(EXTRA_SESSION_LOG, false)
                val language = intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
                Log.d(TAG, "START language=${language ?: "(default)"} sessionLog=$sessionLog")
                viewModel.start(language = language, diagnosticLogging = sessionLog)
            }

            ACTION_STOP -> {
                Log.d(TAG, "STOP")
                viewModel.stop()
            }

            ACTION_DUMP_DIAGNOSTICS -> dumpDiagnostics(context, viewModel)

            else -> Log.d(TAG, "ignored action=${intent.action}")
        }
    }

    /**
     * Snapshot every diagnostic value to the system log at DEBUG level and to a demo-owned sidecar
     * trace next to the engine's own.
     *
     * **No transcript text is written by either path.** Only counters, identifiers and the engine
     * state go out. `Log.d` is below informational on purpose: a bug-report capture picks up info
     * and above, and dictated speech must never be in one.
     *
     * The sidecar is a separate file rather than a line appended to the engine's session trace. That
     * trace's schema is frozen and asserted key-by-key by the library's own tests, and plan 08's
     * analyzer reads it by line kind — injecting a demo-shaped line into it would put this app's
     * convenience inside somebody else's contract.
     */
    private fun dumpDiagnostics(context: Context, viewModel: DictationViewModel) {
        val snapshot = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("kind", "demo-diagnostics")
            put("state", viewModel.engine.value.state.value.wireName)
            put("services", viewModel.recognizerServices.value.joinToString(separator = " "))
            put("selected", viewModel.selectedRecognizer.value ?: JSONObject.NULL)
            put("segmentId", viewModel.lastSegmentId.value ?: JSONObject.NULL)
            put("committedCount", viewModel.committed.value.size)
            put("restartCount", viewModel.restartCount.value ?: JSONObject.NULL)
            put("activeRung", viewModel.activeRung.value ?: JSONObject.NULL)
            put("lastDeafWindowMs", viewModel.lastDeafWindowMs.value ?: JSONObject.NULL)
            put("diagnosticLogging", viewModel.diagnosticLogging.value)
            put("errorCode", viewModel.error.value?.code?.value ?: JSONObject.NULL)
            put("errorPlatformDetail", viewModel.error.value?.platformDetail ?: JSONObject.NULL)
        }

        Log.d(TAG, "DUMP_DIAGNOSTICS $snapshot")

        val directory = context.getExternalFilesDir(null) ?: return
        // Best-effort, exactly like the library's own trace: a diagnostic that can break the app it
        // is diagnosing is worse than no diagnostic.
        runCatching {
            File(directory, DIAGNOSTICS_FILE).appendText(snapshot.toString() + "\n")
        }
    }

    companion object {

        private const val TAG = "SttDemoControl"

        // ── THE CROSS-PLAN INTERFACE — verbatim, do not rename ───────────────────────────────────

        /** Begin a session. Honours [EXTRA_LANGUAGE] and [EXTRA_SESSION_LOG]. */
        const val ACTION_START = "io.github.ygaray.sttengine.demo.debug.START"

        /** End the session. */
        const val ACTION_STOP = "io.github.ygaray.sttengine.demo.debug.STOP"

        /** Write the current diagnostic values to logcat and to the sidecar trace. */
        const val ACTION_DUMP_DIAGNOSTICS =
            "io.github.ygaray.sttengine.demo.debug.DUMP_DIAGNOSTICS"

        /**
         * Boolean extra on [ACTION_START]. Enables the engine's JSONL session trace for that run.
         *
         * The trace is off by default because it records dictated speech; this is the only switch
         * in the app that turns it on, and it cannot exist in a release build.
         */
        const val EXTRA_SESSION_LOG = "io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG"

        /** String extra on [ACTION_START]. A BCP-47 tag, e.g. `en-US`. Absent keeps the current one. */
        const val EXTRA_LANGUAGE = "io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE"

        // ────────────────────────────────────────────────────────────────────────────────────────

        /** The demo's own diagnostic sidecar, beside the engine's trace and pullable the same way. */
        const val DIAGNOSTICS_FILE = "demo-diagnostics.jsonl"
    }
}

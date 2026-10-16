package io.github.ygaray.sttengine.demo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.LanguageAvailability
import io.github.ygaray.sttengine.LanguageSource
import io.github.ygaray.sttengine.checkLanguageAvailability
import io.github.ygaray.sttengine.debug.DebugRungCeiling
import io.github.ygaray.sttengine.demo.DictationViewModel
import io.github.ygaray.sttengine.requestLanguagePackDownload
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
 * `<serial>` below is a placeholder: take the adb serial from your own device config at run time.
 * A real serial or address is never committed to this file.
 *
 * START, STOP and DUMP_DIAGNOSTICS need the demo screen up — they drive the live view model and
 * cannot conjure one:
 *
 * ```bash
 * S=<serial>
 * adb -s $S shell am start -n io.github.ygaray.sttengine.demo/.MainActivity
 * adb -s $S shell am broadcast -n io.github.ygaray.sttengine.demo/io.github.ygaray.sttengine.demo.debug.DemoControlReceiver \
 *   -a io.github.ygaray.sttengine.demo.debug.START \
 *   --ez io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG true \
 *   --es io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE en-US
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.DUMP_DIAGNOSTICS
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.STOP
 * ```
 *
 * For a deterministic, file-fed AND-02 run, push a 16 kHz mono 16-bit WAV into the app's own
 * external files directory (no storage permission needed, unlike shared storage) and name it on
 * START. `SESSION_LOG true` is mandatory with it — see [EXTRA_AUDIO_SOURCE]:
 *
 * ```bash
 * adb -s $S push stimulus.wav /sdcard/Android/data/io.github.ygaray.sttengine.demo/files/and02-stimulus.wav
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.START \
 *   --ez io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG true \
 *   --es io.github.ygaray.sttengine.demo.debug.extra.AUDIO_SOURCE /sdcard/Android/data/io.github.ygaray.sttengine.demo/files/and02-stimulus.wav
 * ```
 *
 * The language-pack actions (Phase 22, D-06) need only a Context, so they work with or without the
 * demo screen — and while a session is listening, which is the point of the contention check. Each
 * writes one logcat line and one sidecar line, and nothing else:
 *
 * ```bash
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.CHECK_PACKS
 * adb -s $S shell am broadcast -n … -a io.github.ygaray.sttengine.demo.debug.DOWNLOAD_PACK \
 *   --es io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE es
 * ```
 *
 * **The eleven strings below (five actions, six extras) are a cross-plan interface.** Plan 08's
 * harness, plan 10's device run and the Phase 21/22 Gate-1 scripts are authored against them
 * verbatim, and plan 08's automated verify cross-greps its own strings against this file. Renaming
 * one here silently breaks them. `src/debug/AndroidManifest.xml` repeats the five action names as
 * literals because a manifest `<action android:name>` cannot reference a Kotlin constant — that
 * duplication is the one place drift can start, both files say so, and `DemoPackActionsTest` fails
 * if a constant has no matching manifest action.
 */
class DemoControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The pack actions need only a Context, so they are answered BEFORE the live-screen check:
        // they must work with no demo screen, and the D-06 contention leg sends CHECK_PACKS while a
        // session is listening in this same process.
        when (intent.action) {
            ACTION_CHECK_PACKS -> {
                checkPacks(context)
                return
            }
            ACTION_DOWNLOAD_PACK -> {
                downloadPack(context, intent)
                return
            }
        }

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
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)?.takeIf { it.isNotBlank() }
                val rungCeiling = intent.getStringExtra(EXTRA_RUNG_CEILING)?.takeIf { it.isNotBlank() }
                val serverPath = parseServerPathExtras(
                    serverUrl = intent.getStringExtra(EXTRA_SERVER_URL),
                    prefer = intent.getStringExtra(EXTRA_PREFER),
                    retainForCorpus = intent.getBooleanExtra(EXTRA_RETAIN_FOR_CORPUS, false),
                )
                if (serverPath is ServerPathRequest.Refused) {
                    Log.d(TAG, "START refused: ${serverPath.reason}")
                    return
                }
                val serverUrl = (serverPath as? ServerPathRequest.Server)?.url
                val prefer = (serverPath as? ServerPathRequest.Server)?.prefer ?: BackendPreference.Auto
                // Only WHETHER a URL was set is logged — never the URL, which names a private host.
                Log.d(
                    TAG,
                    "START language=${language ?: "(default)"} sessionLog=$sessionLog " +
                        "audioSource=${audioSource ?: "(microphone)"} " +
                        "rungCeiling=${rungCeiling ?: "(none)"} " +
                        "serverUrl=${if (serverUrl != null) "set" else "(none)"} prefer=${prefer.name} " +
                        "retainForCorpus=${retainOf(serverPath)}",
                )

                // Applied BEFORE the session starts, because the ladder is resolved once per start()
                // and never re-read mid-session. A bad name throws here rather than producing a
                // silently unrestricted two-minute run reported as a capped one.
                if (rungCeiling == null) {
                    DebugRungCeiling.clear()
                } else {
                    val applied = runCatching { DebugRungCeiling.restrictTo(rungCeiling) }
                    if (applied.isFailure) {
                        Log.d(TAG, "START refused: ${applied.exceptionOrNull()?.message}")
                        return
                    }
                    Log.d(TAG, "rung ceiling applied: ${applied.getOrNull()}")
                }

                // Every refusal above has returned, so this START is accepted: mint the run id that
                // every dump of this session carries (see [RunIdTracker]). A file-fed refusal below
                // still belongs to THIS start, so its dumps carry this id along with the abort reason.
                val runId = runIds.onStartAccepted()
                Log.d(TAG, "START accepted runId=$runId")

                if (audioSource == null) {
                    // The ordinary path, and the only one a release build could ever have had.
                    FileAudioSourceControl.stopFileFed()
                    viewModel.start(
                        language = language,
                        diagnosticLogging = sessionLog,
                        serverUrl = serverUrl,
                        prefer = prefer,
                        retainForCorpus = retainOf(serverPath),
                    )
                } else {
                    // Deliberately NOT falling through to a microphone session when this refuses.
                    // An acoustic run labelled file-fed is worse than no run: it is evidence-shaped.
                    FileAudioSourceControl.startFileFed(
                        viewModel = viewModel,
                        path = audioSource,
                        language = language,
                        sessionLog = sessionLog,
                        serverUrl = serverUrl,
                        prefer = prefer,
                        retainForCorpus = retainOf(serverPath),
                    )
                }
            }

            ACTION_STOP -> {
                Log.d(TAG, "STOP")
                viewModel.stop()
                FileAudioSourceControl.stopFileFed()
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
            // Which START this dump belongs to — what lets a Gate-1 checker pair a pre-STOP and a
            // post-STOP dump by identity instead of by time window.
            runIdDiagnostics(this, runIds.current)
            put("state", viewModel.engine.value.state.value.wireName)
            put("services", viewModel.recognizerServices.value.joinToString(separator = " "))
            put("selected", viewModel.selectedRecognizer.value ?: JSONObject.NULL)
            put("segmentId", viewModel.lastSegmentId.value ?: JSONObject.NULL)
            languageDiagnostics(this, viewModel.committed.value)
            put("committedCount", viewModel.committed.value.size)
            put("restartCount", viewModel.restartCount.value ?: JSONObject.NULL)
            put("activeRung", viewModel.activeRung.value ?: JSONObject.NULL)
            put("lastDeafWindowMs", viewModel.lastDeafWindowMs.value ?: JSONObject.NULL)
            put("diagnosticLogging", viewModel.diagnosticLogging.value)
            put("errorCode", viewModel.error.value?.code?.value ?: JSONObject.NULL)
            put("errorPlatformDetail", viewModel.error.value?.platformDetail ?: JSONObject.NULL)
            // What was ASKED for. `activeRung` above is what actually happened; a report that
            // quotes one without the other cannot tell a working cap from an ignored one.
            put("rungCeiling", DebugRungCeiling.ceiling ?: JSONObject.NULL)
            // The server-path facts (Phase 21, D-08). Names, a boolean and counts — the partial's
            // text is only tested for blankness, never written.
            val engine = viewModel.engine.value
            serverPathDiagnostics(
                into = this,
                backend = engine.backend.value,
                partialLive = engine.partial.value.isNotBlank(),
                partialUpdates = viewModel.partialUpdates.value,
                warningCount = viewModel.warningCount.value,
            )
            // LAST-01: the final the last stop() flushed, by id + language only — never its text.
            lastFinalDiagnostics(this, engine.lastFinal)
            // The file-feed facts. `fileFedConfirmed` and `fileFedOpens` are what turn "this run was
            // file-fed" from a claim into a measurement, and the harness refuses to score a
            // supposedly file-fed run without them. Counters only — still no transcript text.
            FileAudioSourceControl.snapshot(this)
        }

        Log.d(TAG, "DUMP_DIAGNOSTICS $snapshot")

        val directory = context.getExternalFilesDir(null) ?: return
        // Best-effort, exactly like the library's own trace: a diagnostic that can break the app it
        // is diagnosing is worse than no diagnostic.
        runCatching {
            File(directory, DIAGNOSTICS_FILE).appendText(snapshot.toString() + "\n")
        }
    }

    /**
     * [ACTION_CHECK_PACKS]: ask the library for the default `{en, es}` pack state, off the main
     * thread under `goAsync()`, bounded by [PACK_ACTION_BUDGET_MS], always finishing the broadcast.
     */
    private fun checkPacks(context: Context) {
        val appContext = context.applicationContext
        val pending = goAsync()
        packScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val result = withTimeoutOrNull(PACK_ACTION_BUDGET_MS) {
                    checkLanguageAvailability(appContext)
                }
                val elapsedMs = SystemClock.elapsedRealtime() - started
                if (result == null) {
                    Log.d(TAG, "PACKS timeout elapsedMs=$elapsedMs")
                } else {
                    Log.d(TAG, packsLogLine(result))
                    appendPackLine(appContext) { packsDiagnostics(it, result, elapsedMs) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The library promises not to throw; if it ever does, say so instead of crashing the
                // demo. The class name only — a message could carry anything.
                Log.d(TAG, "PACKS failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * [ACTION_DOWNLOAD_PACK]: ask the library to download the pack named by [EXTRA_LANGUAGE], off the
     * main thread under `goAsync()`, bounded by [PACK_ACTION_BUDGET_MS], always finishing the
     * broadcast. The raw extra goes to the library only; logcat and the sidecar get [packLangLabel].
     */
    private fun downloadPack(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_LANGUAGE)
        if (raw.isNullOrBlank()) {
            // Refused before goAsync(), so there is nothing to finish and nothing else happens.
            Log.d(TAG, "DOWNLOAD_PACK refused: LANGUAGE extra required")
            return
        }
        // Sanitized ONCE, before the call (review round 1 LOW): this is the only form of the value
        // that reaches logcat or the sidecar.
        val label = packLangLabel(raw)
        val appContext = context.applicationContext
        val pending = goAsync()
        packScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val accepted = withTimeoutOrNull(PACK_ACTION_BUDGET_MS) {
                    requestLanguagePackDownload(appContext, raw)
                }
                val elapsedMs = SystemClock.elapsedRealtime() - started
                if (accepted == null) {
                    Log.d(TAG, "DOWNLOAD $label timeout elapsedMs=$elapsedMs")
                } else {
                    Log.d(TAG, downloadLogLine(label, accepted, elapsedMs))
                    appendPackLine(appContext) { downloadDiagnostics(it, label, accepted, elapsedMs) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.d(TAG, "DOWNLOAD $label failed: ${failure.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Append one pack-action line (with `t`) to the demo sidecar. Best-effort, exactly like
     * [dumpDiagnostics]: a diagnostic that can break the app it is diagnosing is worse than none.
     */
    private fun appendPackLine(context: Context, fill: (JSONObject) -> Unit) {
        val directory = context.getExternalFilesDir(null) ?: return
        runCatching {
            val line = JSONObject().apply {
                put("t", System.currentTimeMillis())
                fill(this)
            }
            File(directory, DIAGNOSTICS_FILE).appendText(line.toString() + "\n")
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
         * Ask the library which on-device language packs this device has (Phase 22, D-06): runs
         * `checkLanguageAvailability(context)` for the default `{en, es}` and writes a
         * `PACKS {"en":"…","es":"…"}` logcat line plus one `demo-packs` sidecar line.
         *
         * Needs only a Context, so it works with or without the demo screen — including WHILE a
         * session is listening, which is the D-06 contention check. Debug-only, like every action
         * here: it never reaches a release build.
         */
        const val ACTION_CHECK_PACKS = "io.github.ygaray.sttengine.demo.debug.CHECK_PACKS"

        /**
         * Ask the library to download one on-device language pack (Phase 22, D-06): runs
         * `requestLanguagePackDownload(context, lang)` with [EXTRA_LANGUAGE] — a bare `en` or `es` —
         * and writes a `DOWNLOAD <lang> accepted=<bool> elapsedMs=<n>` logcat line plus one
         * `demo-pack-download` sidecar line.
         *
         * Anything other than a bare key is passed through and the library itself refuses it without
         * touching the platform; a missing or blank LANGUAGE is refused here and does nothing. The
         * logged language is always [packLangLabel]'s bounded form, never the raw extra. Works without
         * the demo screen. Debug-only: it never reaches a release build.
         */
        const val ACTION_DOWNLOAD_PACK = "io.github.ygaray.sttengine.demo.debug.DOWNLOAD_PACK"

        /**
         * Boolean extra on [ACTION_START]. Enables the engine's JSONL session trace for that run.
         *
         * The trace is off by default because it records dictated speech; this is the only switch
         * in the app that turns it on, and it cannot exist in a release build.
         */
        const val EXTRA_SESSION_LOG = "io.github.ygaray.sttengine.demo.debug.extra.SESSION_LOG"

        /** String extra on [ACTION_START]. A BCP-47 tag, e.g. `en-US`. Absent keeps the current one. */
        const val EXTRA_LANGUAGE = "io.github.ygaray.sttengine.demo.debug.extra.LANGUAGE"

        /**
         * String extra on [ACTION_START]. An **on-device path** to a 16 kHz mono 16-bit PCM WAVE
         * file to feed rung (c) instead of the microphone. Absent means the microphone.
         *
         * A device path rather than a bundled asset on purpose: the harness `adb push`es whichever
         * stimulus it wants, so changing the passage never means rebuilding the app, and no WAV is
         * baked into the library or the demo.
         *
         * Two things this extra REQUIRES, both enforced by [FileAudioSourceControl] and neither
         * bypassable: the file must validate, and [EXTRA_SESSION_LOG] must be true. A file-fed run
         * whose active rung cannot be read back from the trace is unverifiable, and an unverifiable
         * run that gets scored is worse than no run.
         */
        const val EXTRA_AUDIO_SOURCE = "io.github.ygaray.sttengine.demo.debug.extra.AUDIO_SOURCE"

        /**
         * String extra on [ACTION_START]. Caps how high the library's capability ladder may climb —
         * one of `RawAudioSegmented`, `SilenceSegmented`, `RestartingSingleSession`. Absent means the
         * device's own answer, which is what every release build always does.
         *
         * **Why AND-02 needs this.** Rung (c) ends a recognition session when the app-owned audio
         * source is exhausted, and an app-owned source — a microphone, or the paced file feed — never
         * exhausts. Measured on the tester: a 140 s file-fed run and a 75 s quiet-microphone run each
         * recorded **zero** restart events, and neither degraded, because a session the host stopped
         * is never evidence against a rung. So the forced-restart behaviour the requirement is about
         * is unreachable from a normal run on a device where rung (c) works. `RestartingSingleSession`
         * is where the restart loop lives.
         *
         * **Mutually exclusive with [EXTRA_AUDIO_SOURCE] in practice**, and deliberately not enforced
         * here: rung (a) does not accept an app-owned audio source, so a capped-to-floor run cannot
         * be file-fed. [FileAudioSourceControl]'s rung gate catches the combination and aborts loudly
         * rather than scoring a run whose stimulus never reached the recognizer.
         *
         * A cap is a **request**. The `rung` lines the engine writes into its own trace are the fact,
         * and the AND-02 harness scores a capped run only when every one of them names the rung that
         * was asked for.
         */
        const val EXTRA_RUNG_CEILING = "io.github.ygaray.sttengine.demo.debug.extra.RUNG_CEILING"

        /**
         * String extra on [ACTION_START] (Phase 21, D-08). A `wss://` server URL, supplied **at run
         * time over adb** — e.g. `wss://host.example:8444/` — so no server address ever enters this
         * source tree. Absent means no server, which is what every ordinary run does.
         *
         * Only `wss://` is accepted; anything else refuses the START loudly and starts nothing.
         * The START log line records only `serverUrl=set` / `serverUrl=(none)`, never the URL.
         *
         * **Language on the server path:** the server rejects region-tagged languages such as
         * `en-US` (`unsupported-language`), which would fast-fail the run to the on-device backend
         * and make it look like a passing native run. Pair this extra with [EXTRA_LANGUAGE] `en` or
         * `auto` (Pitfall 6).
         */
        const val EXTRA_SERVER_URL = "io.github.ygaray.sttengine.demo.debug.extra.SERVER_URL"

        /**
         * String extra on [ACTION_START] (Phase 21, D-08). The backend preference — exactly one of
         * `Auto`, `Server`, `OnDevice` (the `BackendPreference` names, case-sensitive). Absent means
         * `Auto`. Requires [EXTRA_SERVER_URL]; on its own it refuses the START.
         *
         * A Gate-1 run uses `Server`, so a failed connect is a loud error instead of a silent native
         * run labelled as a server run.
         */
        const val EXTRA_PREFER = "io.github.ygaray.sttengine.demo.debug.extra.PREFER"

        /**
         * Boolean extra on [ACTION_START] (Phase 25, D-17(e)). Opts the **server** session into the
         * host's voice corpus (`SttConfig.retainForCorpus`). Absent means false, which is what every
         * ordinary run does.
         *
         * **Requires [EXTRA_SERVER_URL].** Without a server there is nothing to retain, and a run
         * that quietly stayed on-device would look like a passing one, so the START is refused.
         *
         * **Use it only with synthetic or public-corpus fixture audio** fed through
         * [EXTRA_AUDIO_SOURCE] — never a person's real voice. The START log line records only
         * `retainForCorpus=true|false`, never a URL.
         *
         * **Reading the result:** `STOP` is asynchronous. After it, poll [ACTION_DUMP_DIAGNOSTICS]
         * until the dump's `state` is `idle`, and only then trust that dump's `lastSessionId` (the
         * library publishes the id before the engine reports Idle). The key is a string after a
         * clean retained server stop and JSON null otherwise.
         */
        const val EXTRA_RETAIN_FOR_CORPUS = "io.github.ygaray.sttengine.demo.debug.extra.RETAIN_FOR_CORPUS"

        // ────────────────────────────────────────────────────────────────────────────────────────

        /** The demo's own diagnostic sidecar, beside the engine's trace and pullable the same way. */
        const val DIAGNOSTICS_FILE = "demo-diagnostics.jsonl"

        /**
         * The overall budget for one pack action under `goAsync()`. Below the ~10 s a broadcast
         * receiver may hold before the system treats it as unresponsive; the library's own worst case
         * is about 2 s for the query plus 5 s for download acceptance.
         */
        const val PACK_ACTION_BUDGET_MS = 9_000L

        /**
         * Where the pack actions run. A fresh receiver instance handles every broadcast and is gone
         * once `goAsync()` is finished, so the work needs a scope that outlives it; the supervisor
         * keeps one failed action from cancelling the next.
         */
        private val packScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * The run id of the most recently accepted START. Process-wide on purpose: Android makes a
         * fresh receiver instance for every broadcast, so START and the later dumps never share one.
         */
        internal val runIds = RunIdTracker()
    }
}

/**
 * Mints and holds the per-START run id written into every diagnostics dump as `runId`
 * (Phase 21, W12 review cycle 2, finding 3).
 *
 * Every accepted [DemoControlReceiver.ACTION_START] mints a fresh id and every dump after it carries
 * that id until the next accepted START, so STOP does not clear it: the post-STOP dump must carry
 * the same id as the pre-STOP one. A refused START mints nothing, because it leaves the previous
 * session running and its dumps still belong to that session. Before any START the id is `null`.
 *
 * The id is a random UUID: opaque, with no device, host, serial or time information in it, so it
 * is safe in logcat and in the sidecar (D-08). [mint] is injectable only so a test can pin it.
 *
 * Scope: the id lives in this demo's dump only. The engine's session trace (`:stt`) has a frozen
 * schema and does not carry it, so pairing a trace with a run is still done by time window.
 */
internal class RunIdTracker(private val mint: () -> String = ::newRunId) {

    /** The current run's id, or `null` before the first accepted START. */
    @Volatile
    var current: String? = null
        private set

    /** Called once per accepted START. Returns the new id, which is now [current]. */
    fun onStartAccepted(): String = mint().also { current = it }
}

/** A fresh opaque run id: a random (version 4) UUID string. */
internal fun newRunId(): String = UUID.randomUUID().toString()

/**
 * Writes the `runId` key onto [into]: the id of the START this dump belongs to, or JSON `null`
 * before any START. Always written, so a checker can tell "no id" from "an old APK without the
 * field". Plain and dependency-free so it is unit-testable in plain JUnit.
 */
internal fun runIdDiagnostics(into: JSONObject, runId: String?) {
    into.put("runId", runId ?: JSONObject.NULL)
}

/**
 * The most recently committed segment's detected language, or `null` before any segment has
 * committed or when the latest segment carries no confident label.
 *
 * Reads [FinalSegment.language] ONLY — an IMPLEMENTATION INVARIANT of this function's body, not a
 * type-level guarantee: the `List<FinalSegment>` parameter still carries `.text`, and nothing in
 * the type system stops a future edit from reading it (19-REVIEWS.md cycle 2, Codex LOW).
 *
 * Plain, dependency-free (no Context, no ViewModel) so it is unit-testable in plain JUnit.
 */
internal fun lastSegmentLanguageOf(committed: List<FinalSegment>): String? =
    committed.lastOrNull()?.language

/**
 * The most recently committed segment's language provenance, or `null` before any segment has
 * committed or when the latest segment carries no source (no label, or a label from a server that
 * predates the field — `null` is "no claim", never a fallback).
 *
 * Reads [FinalSegment.languageSource] ONLY (a label, never transcript content) — the same
 * implementation-invariant-not-type-level-guarantee caveat as [lastSegmentLanguageOf].
 */
internal fun lastSegmentLanguageSourceOf(committed: List<FinalSegment>): LanguageSource? =
    committed.lastOrNull()?.languageSource

/**
 * Every committed segment's detected language and its provenance, oldest first — mirroring
 * [DictationViewModel]'s own documented `committed` ordering — as one `{"segmentId": <Int>,
 * "language": <bare en/es> or JSONObject.NULL, "languageSource": <"DETECTED"|"FALLBACK"> or
 * JSONObject.NULL}` entry per segment.
 *
 * Reads [FinalSegment.segmentId], [FinalSegment.language] and [FinalSegment.languageSource] ONLY —
 * labels, never transcript content; see [lastSegmentLanguageOf]'s KDoc for the same
 * implementation-invariant-not-type-level-guarantee caveat. Plain, dependency-free, unit-testable.
 */
internal fun segmentLanguagesOf(committed: List<FinalSegment>): JSONArray {
    val array = JSONArray()
    committed.forEach { segment ->
        array.put(
            JSONObject().apply {
                put("segmentId", segment.segmentId)
                put("language", segment.language ?: JSONObject.NULL)
                put("languageSource", segment.languageSource?.name ?: JSONObject.NULL)
            },
        )
    }
    return array
}

/**
 * Writes both the [lastSegmentLanguageOf] scalar and the [segmentLanguagesOf] ordered array onto
 * [into] — mirroring the existing [FileAudioSourceControl.snapshot] pattern called one line below
 * this in [DemoControlReceiver.dumpDiagnostics].
 *
 * Three things a driver reading this diagnostics dump needs to know:
 *
 * 1. This function reads ONLY `.language` and `.languageSource` (labels) on every committed entry,
 *    never the transcript content — an IMPLEMENTATION INVARIANT of this function's body, not a
 *    type-level guarantee (the `List<FinalSegment>` parameter still carries the text;
 *    19-REVIEWS.md cycle 2, Codex LOW).
 * 2. `segmentLanguages` carries the COMPLETE, ordered per-utterance sequence in a SINGLE
 *    end-of-run dump — a driver scoring a multi-utterance run (EN-only/ES-only/alternating/
 *    long-form) should read this array rather than polling `committedCount` and dumping once per
 *    new segment. This SUPERSEDES the earlier poll-per-segment protocol, which was itself still
 *    lossy if two segments committed between polls (19-REVIEWS.md cycle 2, Codex MEDIUM #1).
 * 3. `lastSegmentLanguage` is kept as a redundant convenience scalar — always equal to the array's
 *    last entry — for any driver that only wants the latest label. `lastSegmentLanguageSource` is the
 *    same convenience for the provenance (the enum name, or JSON `null`).
 */
internal fun languageDiagnostics(into: JSONObject, committed: List<FinalSegment>) {
    into.put("lastSegmentLanguage", lastSegmentLanguageOf(committed) ?: JSONObject.NULL)
    into.put("lastSegmentLanguageSource", lastSegmentLanguageSourceOf(committed)?.name ?: JSONObject.NULL)
    into.put("segmentLanguages", segmentLanguagesOf(committed))
}

/**
 * Writes the engine's [io.github.ygaray.sttengine.SttEngine.lastFinal] identity onto [into]
 * (LAST-01, Phase 21): `lastFinalSegmentId` (Int, or JSON `null` before the session's first final)
 * and `lastFinalLanguage` (bare `en`/`es`, or JSON `null` when there is no final or no confident
 * label), and `lastFinalLanguageSource` (the [LanguageSource] enum name, or JSON `null` when there
 * is no final or no source). This is what lets a device check confirm, right after STOP, that
 * `lastFinal` is the tail the stop flushed — by `segmentId`, against `segmentLanguages`' last entry.
 *
 * Reads [FinalSegment.segmentId], [FinalSegment.language] and [FinalSegment.languageSource] ONLY
 * (labels, never transcript content) — an IMPLEMENTATION INVARIANT of this function's body, not a
 * type-level guarantee (same caveat as [lastSegmentLanguageOf]; D-08/SRV-05: the dump never carries
 * transcript text). Plain and dependency-free so it is unit-testable in plain JUnit.
 */
internal fun lastFinalDiagnostics(into: JSONObject, lastFinal: FinalSegment?) {
    into.put("lastFinalSegmentId", lastFinal?.segmentId ?: JSONObject.NULL)
    into.put("lastFinalLanguage", lastFinal?.language ?: JSONObject.NULL)
    into.put("lastFinalLanguageSource", lastFinal?.languageSource?.name ?: JSONObject.NULL)
}

/**
 * What an [DemoControlReceiver.ACTION_START] asked for on the server path (Phase 21, D-08).
 *
 * - [None]: no server URL and no preference — the ordinary on-device run, exactly as before.
 * - [Server]: a validated `wss://` URL plus the [BackendPreference] to start under.
 * - [Refused]: the extras were malformed; the receiver logs [Refused.reason] and starts nothing.
 *
 * A demo-only debug type; the URL lives here only in memory and is never logged or dumped.
 */
internal sealed interface ServerPathRequest {

    data object None : ServerPathRequest

    class Server(
        val url: String,
        val prefer: BackendPreference,
        val retainForCorpus: Boolean = false,
    ) : ServerPathRequest {
        // The URL is deliberately left out: a stray `Log.d("$request")` must not print it.
        override fun toString(): String =
            "Server(url=set, prefer=${prefer.name}, retain=$retainForCorpus)"
    }

    class Refused(val reason: String) : ServerPathRequest {
        override fun toString(): String = "Refused($reason)"
    }
}

/**
 * Whether [request] opts the session into the corpus: true only for a [ServerPathRequest.Server]
 * with the flag set. The ONE helper both the ordinary and the file-fed START paths forward, so a
 * mapping slip cannot drop the flag on one path only (T-25-63).
 */
internal fun retainOf(request: ServerPathRequest): Boolean =
    (request as? ServerPathRequest.Server)?.retainForCorpus == true

/**
 * Validate the server-path extras on START.
 *
 * Rules, each a refusal rather than a silent downgrade, because a Gate-1 run that quietly ends up
 * on-device looks exactly like a passing one:
 *
 * - no URL (absent or blank) and no preference → [ServerPathRequest.None];
 * - a preference with no URL → refused (there is nothing to prefer);
 * - the corpus opt-in ([retainForCorpus]) with no URL → refused (nothing to retain, and an on-device
 *   run that looked retained would be a false pass);
 * - a URL that does not start with `wss://` → refused (the server is only reachable over TLS, and a
 *   plain `ws://` or an `https://` URL can only fail later, less legibly);
 * - a preference that is not exactly one of the [BackendPreference] names (`Auto`, `Server`,
 *   `OnDevice` — case-sensitive) → refused;
 * - otherwise [ServerPathRequest.Server], preference defaulting to [BackendPreference.Auto].
 *
 * Refusal reasons never quote the URL itself: logcat leaves the app, and the URL names a private
 * host. Plain and dependency-free so it is unit-testable in plain JUnit.
 */
internal fun parseServerPathExtras(
    serverUrl: String?,
    prefer: String?,
    retainForCorpus: Boolean = false,
): ServerPathRequest {
    val url = serverUrl?.trim()?.takeIf { it.isNotEmpty() }
    val preferName = prefer?.trim()?.takeIf { it.isNotEmpty() }

    if (url == null) {
        return if (retainForCorpus) {
            ServerPathRequest.Refused(
                "RETAIN_FOR_CORPUS was given without SERVER_URL. Only a server session can be " +
                    "retained; re-send START with a wss:// SERVER_URL or drop RETAIN_FOR_CORPUS.",
            )
        } else if (preferName == null) {
            ServerPathRequest.None
        } else {
            ServerPathRequest.Refused(
                "PREFER was given without SERVER_URL. A backend preference needs a server to " +
                    "prefer; re-send START with a wss:// SERVER_URL or drop PREFER.",
            )
        }
    }

    if (!url.startsWith(WSS_SCHEME)) {
        return ServerPathRequest.Refused(
            "SERVER_URL must start with $WSS_SCHEME. The server is reachable only over TLS, so any " +
                "other scheme can only fail later and less legibly.",
        )
    }

    val preference = if (preferName == null) {
        BackendPreference.Auto
    } else {
        BackendPreference.entries.firstOrNull { it.name == preferName }
            ?: return ServerPathRequest.Refused(
                "PREFER must be exactly one of " +
                    BackendPreference.entries.joinToString { it.name } +
                    " (case-sensitive); got \"$preferName\".",
            )
    }

    return ServerPathRequest.Server(url, preference, retainForCorpus)
}

private const val WSS_SCHEME = "wss://"

/**
 * Writes the four server-path facts Gate-1 scores (Phase 21, D-08) onto [into]:
 *
 * - `backend` — which backend actually ran (`Server` / `Native`), the fact a server-path run is
 *   judged by (a `PREFER Auto` run that silently fell back reads `Native` here);
 * - `partialLive` — whether a non-blank partial is on screen at this instant, which is how a driver
 *   finds the mid-utterance moment to send STOP;
 * - `partialUpdates` — non-blank partial emissions since the last start;
 * - `warningCount` — `SttEvent.Warning`s since the last start. D-02 ships the backpressure gate
 *   as-is and retunes it only on evidence: this count IS that Gate-1 observation.
 *
 * Counts, names and booleans only — never transcript text and never the server URL. Plain and
 * dependency-free so it is unit-testable in plain JUnit.
 */
internal fun serverPathDiagnostics(
    into: JSONObject,
    backend: Backend,
    partialLive: Boolean,
    partialUpdates: Int,
    warningCount: Int,
) {
    into.put("backend", backend.name)
    into.put("partialLive", partialLive)
    into.put("partialUpdates", partialUpdates)
    into.put("warningCount", warningCount)
}

/** The longest LANGUAGE value [packLangLabel] passes through unchanged. */
private const val PACK_LANG_LABEL_MAX = 16

/** What [packLangLabel] writes in place of a value it will not repeat. */
private const val PACK_LANG_UNRECOGNIZED = "(unrecognized)"

/** Letters, optionally followed by hyphen-separated alphanumeric subtags: `es`, `en-US`, `EN`. */
private val PACK_LANG_SHAPE = Regex("[A-Za-z]+(-[A-Za-z0-9]+)*")

/**
 * The bounded form of a [DemoControlReceiver.ACTION_DOWNLOAD_PACK] LANGUAGE value (Phase 22, D-06;
 * review round 1 LOW).
 *
 * Returns [raw] unchanged when it is language-tag shaped and at most 16 characters long, and
 * `(unrecognized)` otherwise. The receiver computes this ONCE per broadcast, and the result is the
 * only form of the value that reaches logcat or the sidecar. The receiver is exported, so anything
 * with adb access can send it any string; bounding the value here keeps such a caller from inflating
 * the sidecar or the log.
 *
 * Shape only: `EN` and `en-US` pass through even though the library refuses both. That refusal
 * belongs to [io.github.ygaray.sttengine.requestLanguagePackDownload], which is handed the raw value.
 * The length is checked before the pattern, so an oversized value is never scanned. Plain and
 * dependency-free so it is unit-testable in plain JUnit.
 */
internal fun packLangLabel(raw: String): String =
    if (raw.length <= PACK_LANG_LABEL_MAX && PACK_LANG_SHAPE.matches(raw)) raw else PACK_LANG_UNRECOGNIZED

/**
 * The logcat line for a [DemoControlReceiver.ACTION_CHECK_PACKS] result, keys in [result]'s own
 * order: `PACKS {"en":"INSTALLED","es":"DOWNLOADABLE"}`.
 *
 * Built by hand rather than through [JSONObject], whose key order is not guaranteed on every
 * implementation; the line is read by people and by grep, so a stable order matters. Language keys
 * and enum names only. Plain and dependency-free so it is unit-testable in plain JUnit.
 */
internal fun packsLogLine(result: Map<String, LanguageAvailability>): String =
    result.entries.joinToString(separator = ",", prefix = "PACKS {", postfix = "}") { (lang, availability) ->
        "${JSONObject.quote(lang)}:${JSONObject.quote(availability.name)}"
    }

/**
 * Writes a [DemoControlReceiver.ACTION_CHECK_PACKS] result onto [into] as exactly three keys:
 * `kind` = `demo-packs`, `packs` (an object of language key to [LanguageAvailability] name) and
 * `elapsedMs`. The receiver adds `t` when it appends the line to the sidecar.
 *
 * Language keys, enum names and a duration only — no transcript text exists on this path. Plain and
 * dependency-free so it is unit-testable in plain JUnit.
 */
internal fun packsDiagnostics(into: JSONObject, result: Map<String, LanguageAvailability>, elapsedMs: Long) {
    into.put("kind", "demo-packs")
    into.put(
        "packs",
        JSONObject().apply { result.forEach { (lang, availability) -> put(lang, availability.name) } },
    )
    into.put("elapsedMs", elapsedMs)
}

/**
 * The logcat line for a [DemoControlReceiver.ACTION_DOWNLOAD_PACK] result:
 * `DOWNLOAD es accepted=true elapsedMs=412`.
 *
 * [label] must be the [packLangLabel] of the LANGUAGE extra, never the raw value. Plain and
 * dependency-free so it is unit-testable in plain JUnit.
 */
internal fun downloadLogLine(label: String, accepted: Boolean, elapsedMs: Long): String =
    "DOWNLOAD $label accepted=$accepted elapsedMs=$elapsedMs"

/**
 * Writes a [DemoControlReceiver.ACTION_DOWNLOAD_PACK] result onto [into] as exactly four keys:
 * `kind` = `demo-pack-download`, `lang`, `accepted` and `elapsedMs`. The receiver adds `t` when it
 * appends the line to the sidecar.
 *
 * [label] must be the [packLangLabel] of the LANGUAGE extra, never the raw value, so the sidecar line
 * stays bounded whatever an exported-receiver caller sends. Plain and dependency-free so it is
 * unit-testable in plain JUnit.
 */
internal fun downloadDiagnostics(into: JSONObject, label: String, accepted: Boolean, elapsedMs: Long) {
    into.put("kind", "demo-pack-download")
    into.put("lang", label)
    into.put("accepted", accepted)
    into.put("elapsedMs", elapsedMs)
}

package io.github.ygaray.sttengine.demo.debug

import android.util.Log
import io.github.ygaray.sttengine.SttError
import io.github.ygaray.sttengine.SttErrorCode
import io.github.ygaray.sttengine.debug.DebugAudioSource
import io.github.ygaray.sttengine.demo.DictationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Drives a **file-fed** dictation session, and refuses — loudly — to let one be mislabelled.
 *
 * ## Why a file at all
 *
 * AND-02 is scored by an analyzer over a real session trace, so the evidence run needs a stimulus.
 * The acoustic speaker-to-microphone loopback measured **recall 0.559** — the restart deaf window
 * clips the onset of every utterance, so ~44% of the words never reached the recognizer. An
 * instrument that loses 44% of its input cannot separate "the engine dropped text" from "the room
 * dropped text", which is precisely the question AND-02 asks. A human speaking is not reproducible,
 * so a red verdict cannot be re-run against the same input. Feeding a fixed WAV through rung (c)'s
 * app-owned pipe makes the run deterministic without changing anything the engine does.
 *
 * ## The failure this class exists to prevent
 *
 * **The hazard is not the microphone; it is rung (c) never being selected.** The library's ladder
 * picks a rung per session from the device's measured capabilities minus whatever this engine has
 * since watched fail. If it lands on (b) or (a), the app-owned pipe is *never opened*, the armed file
 * is never read, and the session quietly records a quiet room — while the operator, the log filename
 * and the report all say "file-fed". That trace is evidence-shaped and worthless, which makes it
 * strictly worse than no run at all: a wrong answer that looks like a measurement gets believed.
 *
 * Four gates, none optional and none a warning:
 *
 * 1. **The file is validated before the session starts.** A missing, unreadable or wrong-format path
 *    aborts at the point a human can still fix it — never falls back to the microphone.
 * 2. **A file-fed run REQUIRES the session diagnostic.** The active rung is only knowable from the
 *    engine's own trace, so a run without it cannot be checked and is refused rather than guessed at.
 * 3. **The tailed rung must be `RawAudioSegmented`.** Anything else, or nothing at all, stops the
 *    session and paints the loud banner.
 * 4. **The armed feed must actually have been opened.** [DebugAudioSource.openCount] is a count of
 *    real opens, so "was this file-fed?" is answered by a number rather than by an assumption.
 *
 * Every abort also lands in [snapshot], which the diagnostics dump writes to the demo's sidecar —
 * so the harness on the other end of adb can see the reason without reading logcat.
 */
internal object FileAudioSourceControl {

    private const val TAG = "SttDemoFileFeed"

    /** The rung name the engine writes into its trace for the app-owned-pipe rung. */
    private const val RUNG_RAW_AUDIO = "RawAudioSegmented"

    /**
     * How long to wait for the trace to name a rung.
     *
     * Generous on purpose: the engine must clear preflight, reach a session, and flush a line the
     * view model only polls for twice a second. Still far inside any run's hold, so an abort here is
     * visible long before the harness stops.
     */
    private const val RUNG_WAIT_MS = 25_000L

    /**
     * Grace for the open count after the rung is confirmed.
     *
     * The engine opens the pipe *before* it logs the rung, so a confirmed `RawAudioSegmented` line
     * already implies an open. This is belt and braces, not a race window.
     */
    private const val OPEN_WAIT_MS = 3_000L

    /**
     * The code the loud banner shows when a file-fed run is refused.
     *
     * Minted here as a plain string rather than added to [SttErrorCode]'s frozen sets: that taxonomy
     * is shared byte-for-byte with the TypeScript client and the Python server, and a debug harness
     * has no business widening it. [SttErrorCode] is a value class precisely so an unanticipated code
     * renders instead of crashing an exhaustive `when`.
     */
    private const val CODE_ABORTED = "debug-file-feed-aborted"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var guard: Job? = null

    @Volatile
    private var abortReason: String? = null

    @Volatile
    private var confirmed: Boolean = false

    /**
     * Arm [path] and start a file-fed session, or abort loudly without starting one.
     *
     * @param sessionLog whether the caller asked for the engine's JSONL trace. **Must be true** — see
     *   gate 2 in the class documentation.
     * @return `true` when a session was started, `false` when the request was refused. A caller that
     *   ignores this and starts a session anyway reintroduces the exact mislabelling this prevents.
     */
    fun startFileFed(
        viewModel: DictationViewModel,
        path: String,
        language: String?,
        sessionLog: Boolean,
    ): Boolean {
        guard?.cancel()
        guard = null
        abortReason = null
        confirmed = false
        DebugAudioSource.disarm()

        if (!sessionLog) {
            return refuse(
                viewModel,
                "a file-fed run requires the session diagnostic: the active rung is only knowable " +
                    "from the engine's own trace, and a run that cannot be checked must not be " +
                    "scored. Re-send START with --ez ${DemoControlReceiver.EXTRA_SESSION_LOG} true",
            )
        }

        val durationMs = try {
            DebugAudioSource.armWavFile(path)
        } catch (rejected: Exception) {
            return refuse(
                viewModel,
                "could not arm the audio source, so NO session was started (falling back to the " +
                    "microphone would have produced an acoustic run labelled file-fed): " +
                    "${rejected.message}",
            )
        }

        Log.i(TAG, "armed $path (${durationMs} ms); starting a file-fed session")
        viewModel.start(language = language, diagnosticLogging = true)
        guard = scope.launch { watchRung(viewModel) }
        return true
    }

    /** Return rung (c) to the microphone and stop watching. Safe when nothing was armed. */
    fun stopFileFed() {
        guard?.cancel()
        guard = null
        if (DebugAudioSource.armedPath != null) {
            Log.i(TAG, "disarming: ${DebugAudioSource.describe()}")
        }
        DebugAudioSource.disarm()
    }

    /**
     * Gates 3 and 4, once the session is under way.
     *
     * Reads the rung the engine actually chose rather than the one the operator hoped for. The
     * difference between those two is the entire failure mode.
     */
    private suspend fun watchRung(viewModel: DictationViewModel) {
        val rung = withTimeoutOrNull(RUNG_WAIT_MS) { viewModel.activeRung.filterNotNull().first() }

        if (rung == null) {
            refuse(
                viewModel,
                "no rung appeared in the session trace within ${RUNG_WAIT_MS / 1_000}s, so there " +
                    "is no evidence this run was file-fed. Anything scored from it would be a guess",
            )
            return
        }

        if (rung != RUNG_RAW_AUDIO) {
            refuse(
                viewModel,
                "the ladder selected \"$rung\", not $RUNG_RAW_AUDIO. On that rung the recognition " +
                    "service owns the microphone and the armed file is never read, so this session " +
                    "was recording the ROOM while being labelled file-fed",
            )
            return
        }

        val opened = withTimeoutOrNull(OPEN_WAIT_MS) {
            while (DebugAudioSource.openCount == 0) delay(250)
            true
        }
        if (opened == null) {
            refuse(
                viewModel,
                "the trace names $RUNG_RAW_AUDIO but the armed file was never opened, so no audio " +
                    "from it reached the recognizer",
            )
            return
        }

        confirmed = true
        Log.i(TAG, "file-fed run CONFIRMED on $RUNG_RAW_AUDIO — ${DebugAudioSource.describe()}")
    }

    /**
     * Stop, disarm, shout.
     *
     * The banner, the error log and the sidecar all carry the same sentence. A refusal that only
     * reached logcat would be invisible to the operator watching the screen and to the harness
     * reading files, which are the two places anybody actually looks.
     */
    private fun refuse(viewModel: DictationViewModel, reason: String): Boolean {
        abortReason = reason
        confirmed = false
        Log.e(TAG, "REFUSED: $reason")
        DebugAudioSource.disarm()
        viewModel.stop()
        viewModel.onEngineError(SttError(SttErrorCode(CODE_ABORTED), reason))
        return false
    }

    /**
     * Everything a verdict depends on, for the demo's diagnostic sidecar.
     *
     * `fileFedConfirmed` and `fileFedOpens` are the two the harness gates on: together they are the
     * difference between "this run was file-fed" as a claim and as a measurement.
     */
    fun snapshot(into: JSONObject) {
        into.put("fileFedPath", DebugAudioSource.armedPath ?: JSONObject.NULL)
        into.put("fileFedDurationMs", DebugAudioSource.armedDurationMs)
        into.put("fileFedConfirmed", confirmed)
        into.put("fileFedOpens", DebugAudioSource.openCount)
        into.put("fileFedPcmBytes", DebugAudioSource.pcmBytesFed)
        into.put("fileFedSilenceBytes", DebugAudioSource.silenceBytesFed)
        into.put("fileFedAbortReason", abortReason ?: JSONObject.NULL)
    }
}

// RED stub — replaced in the GREEN step.
internal fun serverPathFeedConfirmed(backend: io.github.ygaray.sttengine.Backend, openCount: Int): Boolean = false

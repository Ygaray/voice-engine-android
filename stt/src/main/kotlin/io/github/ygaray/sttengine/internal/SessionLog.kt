package io.github.ygaray.sttengine.internal

import android.content.Context
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.SttError
import java.io.File
import org.json.JSONObject

/**
 * The opt-in JSONL session diagnostic — one JSON object per line, one file per session.
 *
 * ## Read this before changing the default
 *
 * **This is a diagnostic instrument, not a feature, and it records the user's dictated speech.**
 * That is the most sensitive content this product handles: a private, offline dictation engine whose
 * transcript quietly lands in a file is no longer a private, offline dictation engine (threat
 * T-05-04). Three properties keep that from happening and none of them is optional:
 *
 * 1. **[enabled] defaults to `false`.** A caller has to ask, in writing, at the call site. The demo
 *    must not turn it on by default, and `SessionLogTest` drives a complete engine session with the
 *    default untouched and asserts that not one byte reaches the filesystem.
 * 2. **It writes only to the application's own external files directory** — `getExternalFilesDir`,
 *    which is sandboxed to this app and needs no storage permission, while still being readable back
 *    over adb for a diagnostic run.
 * 3. **Recognized text never reaches the system log.** This class does not use `android.util.Log` at
 *    all, at any level, and neither does anything else in the library. An always-on logcat
 *    transcript would be a leak that survives uninstall in a bug report.
 *
 * Do not "helpfully" default this on, and do not add a convenience that logs text elsewhere.
 *
 * ## The schema is a contract
 *
 * Plan 08's `SessionLogAnalyzer` scores the AND-02 verdict off these lines, so the field names below
 * are fixed. Every line carries `t` (milliseconds since the session started, from a monotonic
 * source) and `kind`; the rest is per kind:
 *
 * | kind | fields |
 * |---|---|
 * | `state` | `value` — the wire name of an [EngineState] |
 * | `rung` | `value` — the [Rung] name |
 * | `partial` | `segmentId`, `text` |
 * | `final` | `segmentId`, `text` |
 * | `restart` | `epoch`, `reason`, `delayMs`, `advanceRung` |
 * | `ready` | `epoch`, `deafWindowMs` |
 * | `error` | `code`, `platformDetail` (present as JSON `null` when there is none) |
 *
 * ## Failure is always a no-op
 *
 * Every write is wrapped. An unwritable path, a full disk, a revoked directory — all of them degrade
 * to writing nothing. A diagnostic that can break dictation is worse than no diagnostic, and this
 * one runs inside main-looper recognizer callbacks where an escaping exception would take the host
 * app with it.
 *
 * Lines are flushed individually rather than buffered to a close: a session that ends because the
 * process was killed is exactly the session whose log you most want, and an unflushed buffer would
 * lose precisely that.
 *
 * @param enabled off unless the caller explicitly asks. See above.
 * @param directory where to write. `null` — which is what `getExternalFilesDir` returns when
 *   external storage is unavailable — degrades to a no-op.
 * @param sessionStartEpochMs names the file, so a pulled log is orderable and attributable without
 *   opening it.
 * @param monotonicMs the source of `t`. Monotonic rather than wall-clock so a clock adjustment
 *   mid-session cannot make a restart appear to precede the callback that caused it.
 */
internal class SessionLog(
    private val enabled: Boolean = false,
    private val directory: File? = null,
    private val sessionStartEpochMs: Long = System.currentTimeMillis(),
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private val startedAtMs: Long = monotonicMs()

    private var resolved = false
    private var target: File? = null

    /** The file this session is writing to, or `null` while disabled or unwritable. */
    val file: File?
        get() = target

    // --- line kinds ---------------------------------------------------------------------------------

    fun state(value: EngineState) {
        write(KIND_STATE) { it.put("value", value.wireName) }
    }

    fun rung(value: Rung) {
        write(KIND_RUNG) { it.put("value", value.name) }
    }

    fun partial(segmentId: Int, text: String) {
        write(KIND_PARTIAL) {
            it.put("segmentId", segmentId)
            it.put("text", text)
        }
    }

    fun final(segment: FinalSegment) {
        write(KIND_FINAL) {
            it.put("segmentId", segment.segmentId)
            it.put("text", segment.text)
        }
    }

    fun restart(epoch: Long, reason: String, delayMs: Long, advanceRung: Boolean) {
        write(KIND_RESTART) {
            it.put("epoch", epoch)
            it.put("reason", reason)
            it.put("delayMs", delayMs)
            it.put("advanceRung", advanceRung)
        }
    }

    /**
     * @param deafWindowMs elapsed monotonic milliseconds between the callback that triggered the
     *   restart and this ready callback — the audio the engine could not hear. [NO_DEAF_WINDOW] on a
     *   ready that no restart preceded, because a fabricated `0` there would drag the measured
     *   distribution down for plan 08.
     */
    fun ready(epoch: Long, deafWindowMs: Long) {
        write(KIND_READY) {
            it.put("epoch", epoch)
            it.put("deafWindowMs", deafWindowMs)
        }
    }

    fun error(error: SttError) {
        write(KIND_ERROR) {
            it.put("code", error.code.value)
            // JSONObject.put(key, null) REMOVES the key. The analyzer's schema has to be stable, so
            // an absent platform detail is an explicit JSON null instead of an absent field.
            it.put("platformDetail", error.platformDetail ?: JSONObject.NULL)
        }
    }

    // --- internals ------------------------------------------------------------------------------------

    private inline fun write(kind: String, fill: (JSONObject) -> Unit) {
        if (!enabled) return
        val sink = resolveTarget() ?: return
        val line = JSONObject()
        line.put("t", monotonicMs() - startedAtMs)
        line.put("kind", kind)
        fill(line)
        runCatching { sink.appendText(line.toString() + "\n") }
    }

    private fun resolveTarget(): File? {
        if (resolved) return target
        resolved = true
        val dir = directory ?: return null
        target = runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
            val candidate = File(dir, "stt-session-$sessionStartEpochMs.jsonl")
            // Touch it now so an unwritable path fails HERE, once, rather than on every line.
            candidate.appendText("")
            candidate
        }.getOrNull()
        return target
    }

    internal companion object {

        /** `deafWindowMs` on a ready that no restart preceded — "not applicable", not "zero". */
        const val NO_DEAF_WINDOW: Long = -1L

        const val KIND_STATE = "state"
        const val KIND_RUNG = "rung"
        const val KIND_PARTIAL = "partial"
        const val KIND_FINAL = "final"
        const val KIND_RESTART = "restart"
        const val KIND_READY = "ready"
        const val KIND_ERROR = "error"

        /**
         * A log rooted at the application's own external files directory.
         *
         * @param enabled still defaults to `false` here. This factory exists to resolve the
         *   directory, not to make the decision.
         */
        fun forContext(context: Context, enabled: Boolean = false): SessionLog =
            SessionLog(enabled = enabled, directory = context.getExternalFilesDir(null))
    }
}

package io.github.ygaray.sttengine.analyzer

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/**
 * The machine that decides AND-02.
 *
 * AND-02 asks for two minutes of continuous dictation with no duplicated or dropped text. That is
 * not something a person can reliably eyeball across a hundred-line transcript, so this turns it
 * into a computed verdict over the JSONL session log plan 06 froze: nine assertions, each with a
 * pass/fail and a reason, six of which are **structural invariants that hold regardless of what
 * produced the speech**. The same analyzer therefore scores a file-fed run, a speaker-loopback run,
 * or a human talking for two minutes — which is exactly why a negative stimulus probe can never
 * block the requirement.
 *
 * ## Why this lives in the unit-test source set
 *
 * It is a verification instrument, not library API. Keeping it out of `main` means it never appears
 * in the frozen public API dump, adds nothing to the shipped AAR, and still runs in the ordinary
 * suite against the recorded fixtures beside it.
 *
 * ## The nine assertions
 *
 * | # | Assertion | Gates? |
 * |---|---|---|
 * | 1 | at least 120000 ms from the first `listening` state to the stop event | yes |
 * | 2 | at least 3 `restart` events | yes |
 * | 3 | the `final` segment-id sequence is 0, 1, 2, … — no repeats, no gaps | yes |
 * | 4 | no consecutive pair of finals shares a k-token boundary, k = 3..5 (duplication) | yes |
 * | 5 | the last partial before each restart reaches **exactly one** final (dropping) | yes |
 * | 6 | no final has blank text | yes |
 * | 7 | the state sequence is legal, and `error` appears at most once and is terminal | yes |
 * | 8 | no `reconnecting` state around an ordinary restart (D-17) | yes |
 * | 9 | the deaf-window distribution | **no — reported only** |
 *
 * Assertions 3 through 8 are the stimulus-independent six.
 *
 * ## Token normalization — defined HERE, explicitly, not inherited
 *
 * The repeated-tail and dropped-partial comparisons are on **token sequences**, never on string
 * lengths, byte counts or code-unit counts: an accented or non-ASCII word must not change the answer
 * depending on how it happens to be encoded. [normalizeToTokens] is the whole rule:
 *
 * 1. normalize to Unicode **composed** form (NFC), so `cafe` + U+0301 and `caf` + U+00E9 are one word;
 * 2. lowercase with **`Locale.ROOT`**, so the phone's language setting cannot change the verdict
 *    (Turkish would lowercase `I` to a dotless `ı`);
 * 3. strip every character in Unicode general category **P** (punctuation), so a final's trailing
 *    full stop cannot hide the fact that it repeats the previous final's tail;
 * 4. split on Unicode whitespace (categories **Z** plus the ASCII set) and drop empty tokens.
 *
 * Comparison is then by `List<String>` equality or contiguous-sublist containment. Nothing here
 * looks at a length.
 *
 * ## Timing
 *
 * Every `t` in the log is integer milliseconds from a **monotonic** source (plan 06 chose monotonic
 * precisely so a mid-session clock adjustment cannot make a restart appear to precede the callback
 * that caused it). This class does `Long` arithmetic on those values and nothing else — there is no
 * floating point anywhere in it, so no rounding, overflow or tie-breaking rule can silently decide a
 * pass or a fail. The two thresholds compare with an inclusive **at-least**, so a run landing exactly
 * on 120000 ms or exactly on 3 restarts passes.
 *
 * The deaf window is **reported, never gated**: no requirement states a deaf-window threshold, and
 * inventing one here could fail an otherwise-correct build. `deafWindowMs == -1`
 * ([SessionLogAnalyzer.NO_DEAF_WINDOW]) means "no restart preceded this ready" and is excluded from
 * the distribution rather than treated as a zero-length window.
 *
 * ## A note on restarts, post plan 09
 *
 * The ladder on this device is `[RawAudioSegmented, SilenceSegmented, RestartingSingleSession]`, and
 * a segmented session can commit a dozen finals with **zero** restarts. Assertions 5 and 8 therefore
 * pass vacuously on a restart-free log — they never assume a restart exists. Only assertion 2
 * objects, and it objects on purpose: AND-02 says "across forced restarts", so a two-minute run that
 * never restarted has not exercised the thing the requirement is about. If a scored run reports
 * fewer than three restarts, that is a statement about the *stimulus*, not about the engine.
 *
 * ## Forward compatibility
 *
 * Unknown line kinds and unknown fields are ignored rather than fatal, so a later additive schema
 * change (plan 05 recommended a `lastCommitSource` on `final` lines, for instance) does not
 * invalidate the verdict. A line that is not JSON, or that carries no `t` or no `kind`, is counted
 * as malformed and reported — never silently dropped.
 */
object SessionLogAnalyzer {

    /** AND-02's "≥2 min continuous dictation", in milliseconds. Inclusive. */
    const val MIN_DURATION_MS: Long = 120_000L

    /** AND-02's "across forced restarts". Inclusive. */
    const val MIN_RESTARTS: Int = 3

    /** `SessionLog.NO_DEAF_WINDOW` — "no restart preceded this ready", NOT a zero-length window. */
    const val NO_DEAF_WINDOW: Long = -1L

    /** The system property `run-and02-uat.sh` passes the pulled device log through. */
    const val LOG_PATH_PROPERTY: String = "stt.sessionLog"

    /** The environment fallback, for a runner that cannot set a system property on the test JVM. */
    const val LOG_PATH_ENV: String = "STT_SESSION_LOG"

    private const val STATE_IDLE = "idle"
    private const val STATE_CONNECTING = "connecting"
    private const val STATE_LISTENING = "listening"
    private const val STATE_RECONNECTING = "reconnecting"
    private const val STATE_ERROR = "error"

    /** `RestartReason` values that are an ORDINARY restart — the gap AND-02 requires to be invisible. */
    private val ORDINARY_RESTART_REASONS = setOf("NormalFinal", "SegmentedSessionEnded")

    /**
     * Legal successors per state. The engine starts at [STATE_IDLE] without logging it, so that is
     * the assumed state before the first line. [STATE_ERROR] has no successors: it is terminal.
     */
    private val LEGAL_TRANSITIONS: Map<String, Set<String>> = mapOf(
        STATE_IDLE to setOf(STATE_CONNECTING, STATE_ERROR),
        STATE_CONNECTING to setOf(STATE_LISTENING, STATE_RECONNECTING, STATE_IDLE, STATE_ERROR),
        STATE_LISTENING to setOf(STATE_RECONNECTING, STATE_IDLE, STATE_ERROR),
        STATE_RECONNECTING to setOf(STATE_LISTENING, STATE_IDLE, STATE_ERROR),
        STATE_ERROR to emptySet(),
    )

    private val PUNCTUATION = Regex("\\p{P}")
    private val WHITESPACE = Regex("[\\p{Z}\\s]+")

    // --- entry points -------------------------------------------------------------------------------

    /**
     * The path of a log to score, or `null` when none was supplied.
     *
     * Blank is "not supplied", not "supplied as blank": an unset shell variable expanding into the
     * generated Gradle init script must not become a path the analyzer then fails to open.
     */
    fun requestedLogPath(): String? =
        System.getProperty(LOG_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(LOG_PATH_ENV)?.takeIf { it.isNotBlank() }

    fun analyzeFile(file: File): AnalysisReport =
        analyze(file.readText(StandardCharsets.UTF_8), source = file.absolutePath)

    fun analyze(text: String, source: String = "<in-memory>"): AnalysisReport {
        val parsed = parse(text)
        val events = parsed.events

        val states = events.filterIsInstance<LogEvent.State>()
        val finals = events.filterIsInstance<LogEvent.Final>()
        val partials = events.filterIsInstance<LogEvent.Partial>()
        val restarts = events.filterIsInstance<LogEvent.Restart>()
        val readys = events.filterIsInstance<LogEvent.Ready>()

        val firstListeningMs = states.firstOrNull { it.value == STATE_LISTENING }?.t
        val stop = stopEvent(events)
        val durationMs = if (firstListeningMs == null || stop == null) {
            null
        } else {
            stop.t - firstListeningMs
        }
        val samples = readys.map { it.deafWindowMs }.filter { it != NO_DEAF_WINDOW }

        val stats = SessionStats(
            lineCount = parsed.lineCount,
            malformedLineCount = parsed.malformedLineCount,
            firstListeningMs = firstListeningMs,
            stopMs = stop?.t,
            durationMs = durationMs,
            restartCount = restarts.size,
            partialCount = partials.size,
            finalCount = finals.size,
            deafWindowSamples = samples,
            rungs = events.filterIsInstance<LogEvent.Rung>().map { it.value }.distinct(),
            errorCodes = events.filterIsInstance<LogEvent.Error>().map { it.code },
        )

        val results = listOf(
            assertDuration(firstListeningMs, stop, durationMs),
            assertRestartCount(restarts),
            assertSegmentIdSequence(finals),
            assertNoDuplicatedTail(finals),
            assertNoDroppedPartial(events, finals),
            assertNoBlankFinal(finals),
            assertLegalStateMachine(states),
            assertNoReconnectingOnOrdinaryRestart(events),
            reportDeafWindow(stats),
        )

        return AnalysisReport(source = source, results = results, stats = stats)
    }

    /**
     * The normalization rule, in one function. See this class's KDoc for the reasoning; the code is
     * the specification.
     */
    fun normalizeToTokens(text: String): List<String> {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        val lowered = composed.lowercase(Locale.ROOT)
        val stripped = PUNCTUATION.replace(lowered, "")
        return stripped.split(WHITESPACE).filter { it.isNotEmpty() }
    }

    // --- assertion 1 ---------------------------------------------------------------------------------

    private fun assertDuration(
        firstListeningMs: Long?,
        stop: LogEvent?,
        durationMs: Long?,
    ): AssertionResult {
        if (firstListeningMs == null) {
            return AssertionResult(
                AssertionId.MinimumDuration,
                passed = false,
                detail = "no state=listening line in the log, so the run has no measurable start",
            )
        }
        if (durationMs == null || stop == null) {
            return AssertionResult(
                AssertionId.MinimumDuration,
                passed = false,
                detail = "no stop event after the first listening state at t=$firstListeningMs",
            )
        }
        val how = when (stop) {
            is LogEvent.State -> "the ${stop.value} state at t=${stop.t}"
            else -> "the last line at t=${stop.t} (the log has no idle or error state)"
        }
        return AssertionResult(
            AssertionId.MinimumDuration,
            passed = durationMs >= MIN_DURATION_MS,
            detail = "the run measured $durationMs ms from the first listening state at " +
                "t=$firstListeningMs to $how; AND-02 requires at least $MIN_DURATION_MS ms",
        )
    }

    /**
     * The stop event is the first terminal state — `idle` or `error` — after the first `listening`.
     *
     * `stop()` sets `idle` unless the engine already failed, in which case the terminal state is
     * `error`; both end the run. A log with neither (a killed process, a truncated pull) falls back
     * to its last line, and the detail text says so rather than quietly measuring to nothing.
     */
    private fun stopEvent(events: List<LogEvent>): LogEvent? {
        val firstListening = events.indexOfFirst {
            it is LogEvent.State && it.value == STATE_LISTENING
        }
        if (firstListening < 0) return null
        for (i in firstListening + 1 until events.size) {
            val event = events[i]
            if (event is LogEvent.State && (event.value == STATE_IDLE || event.value == STATE_ERROR)) {
                return event
            }
        }
        return events.lastOrNull()
    }

    // --- assertion 2 ---------------------------------------------------------------------------------

    private fun assertRestartCount(restarts: List<LogEvent.Restart>): AssertionResult =
        AssertionResult(
            AssertionId.MinimumRestarts,
            passed = restarts.size >= MIN_RESTARTS,
            detail = "the run contains ${restarts.size} restart events " +
                "(${restarts.groupingBy { it.reason }.eachCount()}); " +
                "AND-02 requires at least $MIN_RESTARTS",
        )

    // --- assertion 3 ---------------------------------------------------------------------------------

    private fun assertSegmentIdSequence(finals: List<LogEvent.Final>): AssertionResult {
        val violations = finals.mapIndexedNotNull { index, final ->
            if (final.segmentId == index) {
                null
            } else {
                "final #$index at t=${final.t} carries segment id ${final.segmentId} " +
                    "where the sequence requires $index"
            }
        }
        return violation(
            AssertionId.SegmentIdSequence,
            violations,
            passDetail = "${finals.size} finals carry segment ids 0..${finals.size - 1}, " +
                "strictly increasing by 1 with no repeats and no gaps",
        )
    }

    // --- assertion 4 ---------------------------------------------------------------------------------

    private fun assertNoDuplicatedTail(finals: List<LogEvent.Final>): AssertionResult {
        val tokens = finals.map { normalizeToTokens(it.text) }
        val violations = mutableListOf<String>()
        for (index in 0 until tokens.size - 1) {
            val earlier = tokens[index]
            val later = tokens[index + 1]
            for (k in 3..5) {
                if (earlier.size < k || later.size < k) continue
                val tail = earlier.takeLast(k)
                if (tail == later.take(k)) {
                    violations += "final #$index and final #${index + 1} share a $k-token " +
                        "boundary: \"${tail.joinToString(" ")}\" — text was duplicated across the " +
                        "segment boundary at t=${finals[index + 1].t}"
                }
            }
        }
        return violation(
            AssertionId.NoDuplicatedTail,
            violations,
            passDetail = "no consecutive pair of the ${finals.size} finals shares a 3-, 4- or " +
                "5-token boundary",
        )
    }

    // --- assertion 5 ---------------------------------------------------------------------------------

    private fun assertNoDroppedPartial(
        events: List<LogEvent>,
        finals: List<LogEvent.Final>,
    ): AssertionResult {
        val finalTokens = finals.map { normalizeToTokens(it.text) }
        val violations = mutableListOf<String>()
        var checked = 0

        events.forEachIndexed { index, event ->
            if (event !is LogEvent.Restart) return@forEachIndexed
            val lastPartial = lastPartialBefore(events, index) ?: return@forEachIndexed
            val partialTokens = normalizeToTokens(lastPartial.text)
            if (partialTokens.isEmpty()) return@forEachIndexed
            checked += 1
            val matches = finalTokens.count { containsInOrder(it, partialTokens) }
            if (matches != 1) {
                violations += "the last partial before the restart at t=${event.t} " +
                    "(\"${lastPartial.text}\") appears in $matches finals; exactly 1 is required — " +
                    (if (matches == 0) "that text was DROPPED" else "that text was DUPLICATED")
            }
        }

        return violation(
            AssertionId.NoDroppedPartial,
            violations,
            passDetail = if (checked == 0) {
                "no restart in this run had a partial before it — vacuously satisfied"
            } else {
                "each of the $checked pre-restart partials reaches exactly one final"
            },
        )
    }

    private fun lastPartialBefore(events: List<LogEvent>, index: Int): LogEvent.Partial? {
        for (i in index - 1 downTo 0) {
            val event = events[i]
            if (event is LogEvent.Partial) return event
        }
        return null
    }

    /** Contiguous-sublist containment on token sequences. Never a length comparison. */
    private fun containsInOrder(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    // --- assertion 6 ---------------------------------------------------------------------------------

    private fun assertNoBlankFinal(finals: List<LogEvent.Final>): AssertionResult {
        val violations = finals.mapIndexedNotNull { index, final ->
            if (final.text.isBlank()) {
                "final #$index (segment id ${final.segmentId}) at t=${final.t} has blank text"
            } else {
                null
            }
        }
        return violation(
            AssertionId.NoBlankFinal,
            violations,
            passDetail = "all ${finals.size} finals carry non-blank text",
        )
    }

    // --- assertion 7 ---------------------------------------------------------------------------------

    private fun assertLegalStateMachine(states: List<LogEvent.State>): AssertionResult {
        val violations = mutableListOf<String>()
        var current = STATE_IDLE
        var sawError = false

        states.forEach { state ->
            val next = state.value
            when {
                next !in LEGAL_TRANSITIONS ->
                    violations += "unknown state \"$next\" at t=${state.t}"

                sawError ->
                    violations += "the state line \"$next\" at t=${state.t} follows the terminal " +
                        "error state; error must be the last state of the session"

                next == current -> Unit // a repeated identical state line carries no transition

                next !in LEGAL_TRANSITIONS.getValue(current) ->
                    violations += "illegal state transition $current -> $next at t=${state.t}"
            }
            if (next == STATE_ERROR) sawError = true
            if (next in LEGAL_TRANSITIONS) current = next
        }

        val errorCount = states.count { it.value == STATE_ERROR }
        if (errorCount > 1) {
            violations += "state=error appears $errorCount times; it must appear at most once " +
                "and be terminal"
        }

        return violation(
            AssertionId.LegalStateMachine,
            violations,
            passDetail = "the ${states.size}-state sequence " +
                "(${states.joinToString(" -> ") { it.value }}) is legal, with $errorCount error states",
        )
    }

    // --- assertion 8 ---------------------------------------------------------------------------------

    private fun assertNoReconnectingOnOrdinaryRestart(events: List<LogEvent>): AssertionResult {
        val violations = mutableListOf<String>()

        events.forEachIndexed { index, event ->
            if (event !is LogEvent.State || event.value != STATE_RECONNECTING) return@forEachIndexed
            // The engine calls enterReconnecting() BEFORE scheduleRestart(), so the restart this
            // state belongs to is the next one before any ready re-opens the microphone.
            val restart = nextRestartBeforeReady(events, index)
            when {
                restart == null ->
                    violations += "a reconnecting state at t=${event.t} is followed by no restart " +
                        "before the next ready; nothing was reconnecting"

                restart.reason in ORDINARY_RESTART_REASONS ->
                    violations += "a reconnecting state at t=${event.t} precedes an ordinary " +
                        "restart (reason=${restart.reason}) at t=${restart.t}; D-17 requires the " +
                        "ordinary restart gap to stay invisible to the host"
            }
        }

        val reconnecting = events.count {
            it is LogEvent.State && it.value == STATE_RECONNECTING
        }
        return violation(
            AssertionId.NoReconnectingOnRestart,
            violations,
            passDetail = if (reconnecting == 0) {
                "no reconnecting state appears anywhere in the run"
            } else {
                "all $reconnecting reconnecting states belong to genuine reconnections"
            },
        )
    }

    private fun nextRestartBeforeReady(events: List<LogEvent>, index: Int): LogEvent.Restart? {
        for (i in index + 1 until events.size) {
            when (val event = events[i]) {
                is LogEvent.Ready -> return null
                is LogEvent.Restart -> return event
                else -> Unit
            }
        }
        return null
    }

    // --- assertion 9 — informational, and structurally incapable of failing ----------------------------

    private fun reportDeafWindow(stats: SessionStats): AssertionResult {
        val samples = stats.deafWindowSamples
        val detail = if (samples.isEmpty()) {
            "no samples — no ready in this run followed a restart " +
                "(deafWindowMs=$NO_DEAF_WINDOW means 'not applicable', never 'zero')"
        } else {
            "median ${stats.deafWindowMedianMs} ms, max ${stats.deafWindowMaxMs} ms, " +
                "min ${samples.min()} ms over ${samples.size} restart boundaries"
        }
        // Always passed = true. No requirement states a deaf-window threshold and inventing one
        // here could fail an otherwise-correct build.
        return AssertionResult(AssertionId.DeafWindow, passed = true, detail = detail)
    }

    // --- parsing ---------------------------------------------------------------------------------------

    private fun parse(text: String): ParsedLog {
        val events = mutableListOf<LogEvent>()
        var lineCount = 0
        var malformed = 0

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            lineCount += 1
            val event = parseLine(line)
            if (event == null) malformed += 1 else events += event
        }

        return ParsedLog(events = events, lineCount = lineCount, malformedLineCount = malformed)
    }

    /** `null` for a malformed line: unparseable, or missing the two fields every kind carries. */
    private fun parseLine(line: String): LogEvent? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (!json.has("t") || !json.has("kind")) return null
        val t = runCatching { json.getLong("t") }.getOrNull() ?: return null
        return when (val kind = json.optString("kind")) {
            "state" -> LogEvent.State(t, json.optString("value"))
            "rung" -> LogEvent.Rung(t, json.optString("value"))
            "partial" -> LogEvent.Partial(t, json.optInt("segmentId", -1), json.optString("text"))
            "final" -> LogEvent.Final(t, json.optInt("segmentId", -1), json.optString("text"))
            "restart" -> LogEvent.Restart(
                t = t,
                epoch = json.optLong("epoch", -1L),
                reason = json.optString("reason"),
                delayMs = json.optLong("delayMs", -1L),
                advanceRung = json.optBoolean("advanceRung", false),
            )

            "ready" -> LogEvent.Ready(
                t = t,
                epoch = json.optLong("epoch", -1L),
                deafWindowMs = json.optLong("deafWindowMs", NO_DEAF_WINDOW),
            )

            "error" -> LogEvent.Error(
                t = t,
                code = json.optString("code"),
                // Always present in the schema, as JSON null when there is no raw integer.
                platformDetail = if (json.isNull("platformDetail")) null else json.optInt("platformDetail"),
            )

            // A kind this analyzer does not know is a FUTURE kind, not a broken log.
            else -> LogEvent.Unknown(t, kind)
        }
    }

    // --- shared ----------------------------------------------------------------------------------------

    private fun violation(
        id: AssertionId,
        violations: List<String>,
        passDetail: String,
    ): AssertionResult = if (violations.isEmpty()) {
        AssertionResult(id, passed = true, detail = passDetail)
    } else {
        AssertionResult(
            id,
            passed = false,
            detail = violations.joinToString("; ") +
                if (violations.size > 1) " (${violations.size} violations)" else "",
        )
    }

    private data class ParsedLog(
        val events: List<LogEvent>,
        val lineCount: Int,
        val malformedLineCount: Int,
    )
}

/** One line of the session log, typed. Unknown kinds survive as [LogEvent.Unknown]. */
internal sealed class LogEvent {
    abstract val t: Long

    data class State(override val t: Long, val value: String) : LogEvent()
    data class Rung(override val t: Long, val value: String) : LogEvent()
    data class Partial(override val t: Long, val segmentId: Int, val text: String) : LogEvent()
    data class Final(override val t: Long, val segmentId: Int, val text: String) : LogEvent()
    data class Restart(
        override val t: Long,
        val epoch: Long,
        val reason: String,
        val delayMs: Long,
        val advanceRung: Boolean,
    ) : LogEvent()

    data class Ready(override val t: Long, val epoch: Long, val deafWindowMs: Long) : LogEvent()
    data class Error(override val t: Long, val code: String, val platformDetail: Int?) : LogEvent()
    data class Unknown(override val t: Long, val kind: String) : LogEvent()
}

/** The nine assertions, in the order the research document numbers them. */
enum class AssertionId(
    val number: Int,
    val title: String,
    val informational: Boolean = false,
) {
    MinimumDuration(1, "the run lasted at least ${SessionLogAnalyzer.MIN_DURATION_MS} ms"),
    MinimumRestarts(2, "the run crossed at least ${SessionLogAnalyzer.MIN_RESTARTS} restarts"),
    SegmentIdSequence(3, "segment ids step by exactly 1 from 0"),
    NoDuplicatedTail(4, "no final repeats the previous final's tail"),
    NoDroppedPartial(5, "every pre-restart partial reaches exactly one final"),
    NoBlankFinal(6, "no final is blank"),
    LegalStateMachine(7, "the state sequence is legal; error is single and terminal"),
    NoReconnectingOnRestart(8, "no reconnecting state on an ordinary restart"),
    DeafWindow(9, "deaf-window distribution", informational = true),
}

data class AssertionResult(val id: AssertionId, val passed: Boolean, val detail: String)

data class SessionStats(
    val lineCount: Int,
    val malformedLineCount: Int,
    val firstListeningMs: Long?,
    val stopMs: Long?,
    val durationMs: Long?,
    val restartCount: Int,
    val partialCount: Int,
    val finalCount: Int,
    val deafWindowSamples: List<Long>,
    val rungs: List<String>,
    val errorCodes: List<String>,
) {

    /**
     * The LOWER median of the sorted samples — element `(n - 1) / 2`.
     *
     * Stated rather than implied: with an even sample count there is no single middle value, and
     * averaging the two would introduce the one piece of non-integer arithmetic in the analyzer for
     * a number that gates nothing.
     */
    val deafWindowMedianMs: Long? =
        deafWindowSamples.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }

    val deafWindowMaxMs: Long? = deafWindowSamples.maxOrNull()
}

data class AnalysisReport(
    val source: String,
    val results: List<AssertionResult>,
    val stats: SessionStats,
) {

    val failures: List<AssertionResult> = results.filterNot { it.passed }

    val passed: Boolean = failures.isEmpty()

    fun resultOf(id: AssertionId): AssertionResult = results.first { it.id == id }

    fun detailOf(id: AssertionId): String = resultOf(id).detail

    /** The whole verdict as text — what a scored device run prints into the harness's output. */
    fun render(): String = buildString {
        appendLine("SessionLogAnalyzer — $source")
        appendLine("  AND-02 VERDICT: ${if (passed) "PASS" else "FAIL"}")
        appendLine("  lines: ${stats.lineCount} (malformed: ${stats.malformedLineCount})")
        appendLine(
            "  duration: ${stats.durationMs ?: "n/a"} ms · restarts: ${stats.restartCount} · " +
                "finals: ${stats.finalCount} · partials: ${stats.partialCount}",
        )
        appendLine("  rungs: ${stats.rungs.ifEmpty { listOf("n/a") }.joinToString(", ")}")
        if (stats.errorCodes.isNotEmpty()) {
            appendLine("  errors: ${stats.errorCodes.joinToString(", ")}")
        }
        results.sortedBy { it.id.number }.forEach { result ->
            val mark = when {
                result.id.informational -> "INFO"
                result.passed -> "PASS"
                else -> "FAIL"
            }
            appendLine("  [${result.id.number}] $mark  ${result.id.title}")
            appendLine("        ${result.detail}")
        }
    }
}

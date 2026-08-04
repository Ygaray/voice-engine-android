package io.github.ygaray.sttengine.internal

import io.github.ygaray.sttengine.FinalSegment

/**
 * Where a committed segment's text came from.
 *
 * This is a **named policy outcome, not an implementation detail**. Plan 08's `SessionLogAnalyzer`
 * scores AND-02 off which path actually fired, and plan 09 may change which rung is active — so the
 * decision has to be inspectable rather than buried in an elvis operator.
 */
internal enum class FinalTextSource {
    /** The recognition service delivered real text through `onResults` / `onSegmentResults`. */
    PLATFORM_FINAL,

    /**
     * The platform's final was blank, so the segment committed its last non-blank partial.
     *
     * **On the tester this is the ordinary case, not the exceptional one.** See [FinalTextPolicy].
     */
    LAST_NON_BLANK_PARTIAL,

    /** Neither source carried text. Nothing is emitted — a final never has blank text. */
    NONE,
}

/**
 * The one place that decides what a segment's final text **is**.
 *
 * ## The measurement that shapes this
 *
 * `05-PROBE-REPORT.json` → `part2.finalResultDelivery`, measured on the tester (SM-S908U, Android 15
 * / API 35) across three reproducing runs: **`onResults` delivers a Bundle containing ZERO keys** —
 * `resultsBundles: [{ keys: [] }]`, `finalText: ""`, `anyNonBlankFinal: false` — on **both** natural
 * silence-end and explicit `stopListening()`. Partial bundles dumped by the same code carry three
 * populated keys (`current_locale`, `results_recognition`,
 * `android.speech.extra.UNSTABLE_TEXT`), which is what rules out an extraction bug. Corroborating:
 * 0 of 11 loopback sessions produced a non-blank final.
 *
 * Rung (a) — `RestartingSingleSession` — is the ladder's **terminal floor**. There is no lower rung
 * to degrade onto. So on this device, "commit the last non-blank partial" is the only way rung (a)
 * produces committed text at all: without it, dictation yields **zero** text on the only device that
 * matters, and AND-02 ("no duplicated or dropped text across restarts") is unachievable by
 * construction.
 *
 * Hence [LAST_NON_BLANK_PARTIAL][FinalTextSource.LAST_NON_BLANK_PARTIAL] is the **primary** path and
 * this policy is written happy-path-first for it. A non-blank platform final is *preferred when
 * present* — some other device, or rung (b)'s `onSegmentResults` (which IS populated here, precision
 * 0.985–1.000), will deliver one — but no logic anywhere may **depend** on one arriving.
 *
 * D-02's rule still holds unchanged: the final **replaces** the last partial, never appends to it.
 * All that changed is where the final's text comes from.
 */
internal object FinalTextPolicy {

    /** Which source [resolve] would draw this segment's final text from. */
    fun sourceFor(platformFinalText: String, livePartial: String): FinalTextSource = when {
        platformFinalText.isNotBlank() -> FinalTextSource.PLATFORM_FINAL
        livePartial.isNotBlank() -> FinalTextSource.LAST_NON_BLANK_PARTIAL
        else -> FinalTextSource.NONE
    }

    /**
     * The segment's final text, or `null` when there is nothing to commit.
     *
     * Trimmed, so a final can never carry leading/trailing whitespace — and never blank text, which
     * would show up in a host's transcript as a phantom empty line.
     */
    fun resolve(platformFinalText: String, livePartial: String): String? =
        when (sourceFor(platformFinalText, livePartial)) {
            FinalTextSource.PLATFORM_FINAL -> platformFinalText.trim()
            FinalTextSource.LAST_NON_BLANK_PARTIAL -> livePartial.trim()
            FinalTextSource.NONE -> null
        }
}

/**
 * The dedup / segment layer (D-02), sitting **beneath** the rung ladder so all three rungs share one
 * implementation. Pure: no timers, no coroutines, no Android types, no synchronization.
 *
 * ## The two rules that carry all the weight
 *
 * 1. **Within a segment**, `onPartialResults` is cumulative for the current utterance and each
 *    partial *replaces* the previous one. The final then *replaces* the live partial. **Never
 *    append** — appending a final onto its own last partial is the single most common way this class
 *    of library duplicates words, and both reference implementations replace.
 * 2. **Across segments**, each final is a genuinely new segment for the host to concatenate.
 *    [segmentId] therefore advances on a session or segment boundary **only**, never on a partial —
 *    and only on a boundary that closes a segment which actually committed, so the ids a host sees
 *    run `0, 1, 2, …` with no gaps. [onSegmentBoundary] carries the measurement behind that.
 *
 * ## Ordering contract the engine must respect
 *
 * On a forced teardown (a restart, or `stop()`), the engine must:
 *
 * ```
 * 1. accumulator.commitLivePartialAsFinal()   ← commit BEFORE teardown…
 * 2. epoch.advance()                          ← …then fence
 * 3. tear the recognizer session down
 * 4. accumulator.onSegmentBoundary()          ← before the next session's first result
 * ```
 *
 * Step 1 before step 2 is the whole point: teardown is *what causes* the final that never arrives,
 * and the fence is what stops the dying session re-emitting the same words. This class makes the
 * ordering safe rather than merely documented, by keeping the commit **idempotent per segment** — a
 * late final for an already-committed segment returns `null` instead of a duplicate.
 *
 * ## What this class deliberately does NOT do
 *
 * No text-overlap or common-suffix diff merging. The duplicates in this domain are **lifecycle**
 * artifacts, not text artifacts. A text merge would corrupt legitimately repeated speech — a
 * stutter, or a dictated "no, no, no" — while still missing the real duplicate, which is two
 * *different* renderings of the same audio from two sessions.
 *
 * ## Threading
 *
 * Main-thread-confined, for the same reason [SessionEpoch] is. Plain `var` fields — no volatile
 * markers, no atomic types, no locks. See [SessionEpoch] for why that is correct and not an
 * oversight.
 */
internal class SegmentAccumulator {

    /**
     * Library-owned, monotonic, **starts at 0** — mirroring `server/src/stt_server/segmenter.py`'s
     * `_next_segment_id = 0`, so the two backends number identically.
     *
     * The id the currently open segment will commit under. It is spent by a **commit** and by
     * nothing else: see [onSegmentBoundary] for why the sequence a host observes must have no gaps.
     *
     * Opaque and per-session: it is **not** comparable across engines, backends or sessions, and a
     * host must not persist it or infer anything from the gap between two values.
     */
    var segmentId: Int = 0
        private set

    /**
     * The last non-blank partial seen for the current segment. `""` when nothing is in flight.
     *
     * This is the buffer that [commitLivePartialAsFinal] flushes, and — per [FinalTextPolicy] — the
     * source of the committed text on every device whose `onResults` is blank.
     */
    var livePartial: String = ""
        private set

    /** Which source the most recent successful commit drew its text from. Diagnostic; for logs. */
    var lastCommitSource: FinalTextSource = FinalTextSource.NONE
        private set

    /**
     * True once this segment has emitted its final. Reset by [onSegmentBoundary].
     *
     * This is the *second* line of defence against duplication; the epoch fence is the first. It
     * covers the same-epoch race the fence cannot see: a teardown commit immediately followed by
     * the real (or blank) `onResults` for the very same session.
     *
     * It carries a second job since AND-02's gap defect: it is also the record of whether this
     * segment id has been **spent**, which is what [onSegmentBoundary] reads to decide whether the
     * boundary advances anything at all.
     */
    private var committed: Boolean = false

    /**
     * A partial arrived. Replaces the live partial — never concatenates.
     *
     * A blank partial is **ignored** rather than allowed to clear a good one. The recognizer does
     * emit blank partials (the probe measured 9 of 11 loopback sessions ending on one immediately
     * before the blank final); letting one through would wipe the very text
     * [commitLivePartialAsFinal] exists to rescue.
     *
     * Never touches [segmentId] and never re-opens a committed segment. A partial landing in an
     * already-committed segment is still **retained** — it becomes that segment's live partial and
     * commits under the next [segmentId] after [onSegmentBoundary]. Retaining rather than
     * discarding is deliberate: silently dropping speech at a restart boundary would pass a naive
     * duplicate check while destroying the user's dictation, which in a note-taking product is data
     * loss, not a rendering glitch.
     */
    fun onPartial(text: String) {
        if (text.isNotBlank()) livePartial = text
    }

    /**
     * The recognition service delivered a final for this segment — `onResults` on rung (a), or
     * `onSegmentResults` on rungs (b)/(c).
     *
     * @param platformFinalText the text the platform delivered. **Pass `""` when the bundle was
     *   empty** — do not pre-substitute the live partial at the call site. Routing the blank case
     *   through here is what keeps the commit decision in one named, tested place
     *   ([FinalTextPolicy]) instead of scattered across the engine's callbacks.
     * @return the committed segment, or `null` when this segment was already committed (a late
     *   duplicate) **or** neither the platform final nor the live partial carried any text.
     */
    fun onFinal(platformFinalText: String): FinalSegment? = commit(platformFinalText)

    /**
     * The forced-teardown path: commit the live partial as this segment's final.
     *
     * Called by the engine **before** it advances the epoch and tears the session down. Identical
     * in every respect to `onFinal("")` — both go through [FinalTextPolicy] — and named separately
     * only because the call site's intent is different and worth reading at a glance.
     *
     * @return the committed segment, or `null` when the segment is already committed or the live
     *   partial is blank. A final never carries empty or whitespace-only text.
     */
    fun commitLivePartialAsFinal(): FinalSegment? = commit(NO_PLATFORM_FINAL)

    /**
     * A session or segment boundary was crossed: re-open the segment for commit, advancing
     * [segmentId] **only if the segment being closed actually emitted a final**.
     *
     * Called on `onEndOfSegmentedSession`, after a committed final, and on a restart — **never** on
     * a partial. Does not clear [livePartial]: a commit already cleared it, and if one did not
     * (because the segment was already committed) those words still belong to the user and must
     * survive into the new segment.
     *
     * ## Why the advance is conditional — AND-02, measured
     *
     * An id is consumed by a COMMIT, never by a boundary. A boundary that committed nothing leaves
     * the segment open under the same id, because nothing the host can ever observe was numbered
     * with it.
     *
     * That is not a tidiness argument. `segmentId` is the only handle a host has on segment
     * identity, and `SessionLogAnalyzer`'s assertion 3 reads the finals' id sequence as this
     * project's **dropped-text detector**: `0, 1, 2, …` with no repeats and no gaps. An
     * unconditional advance put holes in it, because the engine announces boundaries on three
     * paths that routinely commit nothing:
     *
     * | Path | Why nothing is committed |
     * |---|---|
     * | `onSegmentResults` then `onEndOfSegmentedSession` (rungs b/c) | the segment result already committed and opened the next segment; the session then ends on the speaker's pause with an empty buffer, and the restart announces a **second** boundary |
     * | a session that recognised nothing (`ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT`) | the user paused between sentences; the restart's flush has no live partial |
     * | `stop()` with an empty buffer | nothing was said before the host stopped |
     *
     * MEASURED: a 138-second file-fed AND-02 run on the tester (SM-S908U, API 35) on rung (c)
     * produced 14 verbatim-correct finals numbered `0, 1, 2, 3, 5, … 14` — id 4 never appeared on a
     * final **or on a partial**, reproducing at the same stimulus boundary across two independent
     * runs. The text was all there; the sequence said it was not, and a host cannot tell those two
     * apart from a log.
     *
     * The conditional advance makes the guarantee structural rather than incidental: `commit`
     * stamps a segment and sets [committed]; the first boundary afterwards spends that id and
     * re-opens; any further boundary before the next commit is a no-op. The emitted sequence is
     * therefore `0, 1, 2, …` by construction, whatever the lifecycle does above it.
     */
    fun onSegmentBoundary() {
        // Nothing was emitted under this id, so there is nothing to close and no id to spend. The
        // segment stays open — including any live partial that arrived, which will commit here.
        if (!committed) return
        segmentId += 1
        committed = false
    }

    private fun commit(platformFinalText: String): FinalSegment? {
        if (committed) return null
        val source = FinalTextPolicy.sourceFor(platformFinalText, livePartial)
        val text = FinalTextPolicy.resolve(platformFinalText, livePartial) ?: return null
        committed = true
        lastCommitSource = source
        livePartial = ""
        return FinalSegment(text = text, segmentId = segmentId)
    }

    private companion object {
        /** Sentinel for "the platform gave us nothing" — the ordinary case on the tester. */
        const val NO_PLATFORM_FINAL = ""
    }
}

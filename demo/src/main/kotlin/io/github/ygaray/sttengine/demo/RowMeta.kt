package io.github.ygaray.sttengine.demo

import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.FallbackReason
import io.github.ygaray.sttengine.FinalSegment
import io.github.ygaray.sttengine.LanguageSource

/**
 * The demo-side snapshot taken beside a committed final (Phase 31, DEMO-01, D-08): which backend
 * the engine reported and why it fell back, read at the moment the final arrived.
 *
 * It lives next to [FinalSegment], never inside it: `FinalSegment` is the frozen `:stt` contract and
 * gains no backend field (a deferred idea, to be revisited only if snapshot mislabelling ever matters).
 */
internal data class RowMeta(val backend: Backend, val fallbackReason: FallbackReason?)

/**
 * The placeholder every empty metadata value renders. U+2014, the same character the `stt-language`
 * node already renders for a segment with no language, so an empty value reads the same everywhere.
 */
internal const val EMPTY_VALUE = "—"

/**
 * What the metadata line of one row says, decided here in plain Kotlin so plain JUnit can prove it
 * (no Compose UI test library may be added). `*Loud` means the value renders in the error colour;
 * the word itself still carries the meaning, so colour is never the only signal.
 */
internal data class RowLabels(
    val source: String,
    val sourceLoud: Boolean,
    val backend: String,
    val reason: String,
    val reasonLoud: Boolean,
)

/**
 * The labels for one row: `src` from the segment's [FinalSegment.languageSource], `backend` and
 * `reason` from the [meta] snapshot, with [EMPTY_VALUE] for anything absent (a null source, a null
 * or blank reason, or a missing [meta] entry: the screen reads the parallel list with
 * `getOrNull(index)` and a short list must never throw).
 *
 * Only a [LanguageSource.FALLBACK] source and a non-null fallback reason are loud (a reason the
 * build does not name is shown raw and is loud too; a blank reason value shows the dash but is
 * still a present reason, so it stays loud).
 *
 * **Snapshot semantics (D-08/D-09, `SttEngine.fallbackReason` KDoc).** [meta] is what the engine
 * reported when this final ARRIVED, not what the session is doing now. After a mid-session drop the
 * handoff final therefore reads `Server` with no reason, and later finals read `Native` with
 * `dropped`. That is the documented contract, not a demo bug; do not file it as one.
 */
internal fun rowLabels(segment: FinalSegment, meta: RowMeta?): RowLabels {
    val source = segment.languageSource
    val reason = meta?.fallbackReason
    return RowLabels(
        source = source?.name ?: EMPTY_VALUE,
        sourceLoud = source == LanguageSource.FALLBACK,
        backend = meta?.backend?.name ?: EMPTY_VALUE,
        reason = reason?.value?.takeIf { it.isNotBlank() } ?: EMPTY_VALUE,
        reasonLoud = reason != null,
    )
}

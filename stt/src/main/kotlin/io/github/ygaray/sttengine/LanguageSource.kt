package io.github.ygaray.sttengine

/**
 * Where a [FinalSegment.language] label came from.
 *
 * It tells a host whether the label is something the recognizer actually decided for that segment,
 * or only a default the engine stamped because it had nothing better. A host that treats every label
 * alike will present a guess as a detection.
 *
 * `FinalSegment.languageSource` is `null` in two situations, and a host must treat `null` as
 * "no claim", never as a fallback:
 *
 * - the segment has no language label at all (every segment of a session whose language is not
 *   `"auto"`, and an `"auto"` segment for which no detection was produced);
 * - the segment has a label but no source, which is what a label from an older server that predates
 *   this field looks like. The label is real; the engine just cannot say where it came from.
 *
 * The set is closed on purpose, so a host may write an exhaustive `when` over this type without an
 * `else` branch. No value will ever be added. A session whose language is not `"auto"` reads `null`,
 * not a third value.
 */
public enum class LanguageSource {

    /**
     * The engine's language detection produced the label for this segment: a confident on-device
     * detection, or a detection made by the server (including a label the server kept because a
     * competing language did not clear its switch margin).
     */
    DETECTED,

    /**
     * The label is a default stamp, not a detection result: it was applied because the runtime
     * cannot detect language (an older Android version running an `"auto"` session), or because the
     * server had no usable detection evidence for the segment.
     */
    FALLBACK,
}

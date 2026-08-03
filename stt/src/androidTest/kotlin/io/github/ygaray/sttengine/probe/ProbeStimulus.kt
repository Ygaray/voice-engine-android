package io.github.ygaray.sttengine.probe

/**
 * The committed stimulus corpus for the D-04 behavioural probe (plan 05-03).
 *
 * `probe-stimulus-16k-mono.wav` in `src/androidTest/assets/` is the on-device Google-TTS rendering
 * of exactly these fourteen utterances, concatenated with [GAP_MS] of digital silence between them
 * (plus [LEAD_MS] of lead-in and trail-out). Regenerate it with `StimulusGeneratorTest` — see that
 * file's KDoc for the exact three-step procedure.
 *
 * **The gaps are load-bearing, not padding.** They are what makes the WAV a legitimate stimulus for
 * the `EXTRA_SEGMENTED_SESSION` discriminator: a segmenting recognizer must emit an
 * `onSegmentResults` at each of them, and a recognizer that silently no-ops the extra will instead
 * end the whole session at the first one. A gapless read would make the two behaviours
 * indistinguishable and the measurement worthless.
 *
 * The vocabulary is deliberately ordinary — this measures the *plumbing*, not the acoustic model, so
 * any word an English dictation model could plausibly miss would only add noise to the similarity
 * scores that decide the AND-02 stimulus verdict.
 */
internal object ProbeStimulus {

    /**
     * Digital silence inserted between utterances, in milliseconds.
     *
     * 3 s, not 2 s: the recognizer's own default complete-silence threshold sits around 2 s, so a
     * gap that merely matched it would leave the discriminator racing the endpointer. 3 s clears it
     * unambiguously — every gap MUST look like an utterance boundary to any recognizer, otherwise a
     * "no segments" reading would be ambiguous between "the extra no-ops" and "the pause was too
     * short to segment on".
     */
    const val GAP_MS: Int = 3_000

    /** Digital silence at the head and tail of the file, in milliseconds. */
    const val LEAD_MS: Int = 500

    const val SAMPLE_RATE_HZ: Int = 16_000

    /** The asset path, relative to the androidTest assets root. */
    const val ASSET_NAME: String = "probe-stimulus-16k-mono.wav"

    val UTTERANCES: List<String> = listOf(
        "The morning light came through the kitchen window and fell across the table " +
            "where the newspaper lay open to the weather page.",
        "She poured a second cup of coffee and read the forecast twice, because the " +
            "first time she had not really been paying attention.",
        "Rain was expected before noon, followed by a long clear evening with a light " +
            "wind coming in from the north.",
        "He wrote the shopping list on the back of an envelope: bread, eggs, two lemons, " +
            "a bag of rice, and something for dinner on Thursday.",
        "The train to the city leaves at seven minutes past eight, and it takes about " +
            "forty minutes to reach the station near the river.",
        "If the meeting runs late, we can walk to the small restaurant on the corner and " +
            "talk about the project over a quiet dinner.",
        "Remember to call the office before five o'clock, and ask them to send the signed " +
            "copy of the agreement by the end of the week.",
        "By the time the rain stopped, the street was washed clean and the whole " +
            "neighborhood smelled like wet leaves and warm bread.",
        "The old bookshelf in the hallway holds a dictionary, three atlases, and a stack " +
            "of photographs nobody has sorted in years.",
        "On Saturday we will drive north along the coast, stop for lunch at the harbor, " +
            "and be home again well before dark.",
        "The report needs one more paragraph about the budget, a short summary at the " +
            "top, and a list of the people who reviewed it.",
        "When you finish reading this, please put the book back on the shelf and turn off " +
            "the lamp beside the green chair.",
        "There is a wooden bench under the tall tree at the end of the garden, and it is " +
            "the best place to sit on a warm afternoon.",
        "Before we leave, check that the windows are closed, the back door is locked, and " +
            "the key is under the second flower pot.",
    )

    /** The whole corpus as one string — the reference the similarity scores are computed against. */
    val TRANSCRIPT: String = UTTERANCES.joinToString(" ")
}

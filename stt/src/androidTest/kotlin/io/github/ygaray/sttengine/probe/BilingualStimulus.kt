package io.github.ygaray.sttengine.probe

/**
 * The committed bilingual EN<->ES stimulus corpus for the native language-switch probe
 * (plan 149-02, [LanguageSwitchProbeTest]).
 *
 * `bilingual-stimulus-16k-mono.wav` in `src/androidTest/assets/` is built by
 * `android/scripts/build-bilingual-stimulus.py` from real human speech — two spliced
 * inter-sentence clips (`intersentence_en_es_01`, `intersentence_en_es_02`) drawn from plan
 * 149-01's already-committed server-side spike corpus (`server/fixtures/bilingual_en_es/`,
 * itself LibriSpeech + OpenSLR) — plus, best-effort, two single-voice mixed-language code-switch
 * utterances synthesised by [BilingualStimulusGeneratorTest] when the on-device es-US Google TTS
 * voice is present (it was, on the SM-S908U tester rig, at build time).
 *
 * **The gaps are load-bearing, not padding**, exactly like [ProbeStimulus.GAP_MS]: every language
 * boundary below sits behind at least [GAP_MS] of digital silence — the stitcher pads the source
 * clips' own shorter internal gap up to this floor — so a genuine utterance/language boundary is
 * unambiguous to the `EXTRA_ENABLE_LANGUAGE_SWITCH` mechanism under test, the same reasoning
 * `ProbeStimulus`'s KDoc gives for `EXTRA_SEGMENTED_SESSION`.
 *
 * Regenerate with `android/scripts/build-bilingual-stimulus.py` — see that file's docstring for
 * the full procedure, including the optional `--tts-parts` step.
 */
internal object BilingualStimulus {

    /** Digital silence enforced at every language boundary, in milliseconds. */
    const val GAP_MS: Int = 3_000

    /** Digital silence at the head and tail of the file, in milliseconds. */
    const val LEAD_MS: Int = 500

    const val SAMPLE_RATE_HZ: Int = 16_000

    /** The asset path, relative to the androidTest assets root. */
    const val ASSET_NAME: String = "bilingual-stimulus-16k-mono.wav"

    /** One utterance's expected text and its region-qualified BCP-47 language tag. */
    data class Utterance(val text: String, val languageTag: String)

    /**
     * Ordered exactly as they appear in the committed WAV, so the probe can compare the
     * recognizer's output against a known per-utterance language sequence. `es-US`, never a bare
     * `es` — matches DICT-03's region-qualified convention already established across this
     * milestone.
     *
     * The first four are spliced real human speech (verbatim source transcripts, from
     * `server/fixtures/bilingual_en_es/README.md`'s "Source clip ids" table); the last two are
     * single-voice TTS code-switch utterances (RESEARCH.md's "ES-dominant with English
     * insertions" and "single-word code-switch" categories), spoken entirely by the es-US Google
     * TTS voice — a mixed-language sentence in ONE voice, which splicing cannot produce.
     */
    val UTTERANCES: List<Utterance> = listOf(
        Utterance("HE COULD WAIT NO LONGER", "en-US"),
        Utterance("Los niños tienen mucha imaginación", "es-US"),
        Utterance("OJO EXAMINED THIS CURIOUS CONTRIVANCE WITH WONDER", "en-US"),
        Utterance("¿Cuál es el hospital más cercano?", "es-US"),
        Utterance("Voy a hacer un backup del archivo", "es-US"),
        Utterance("Send me el link por WhatsApp", "es-US"),
    )

    /** The whole corpus as one string, for a rough end-to-end reference if ever needed. */
    val TRANSCRIPT: String = UTTERANCES.joinToString(" ") { it.text }
}

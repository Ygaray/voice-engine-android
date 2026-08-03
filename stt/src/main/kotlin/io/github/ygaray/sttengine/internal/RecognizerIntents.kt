package io.github.ygaray.sttengine.internal

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import io.github.ygaray.sttengine.SttConfig

/**
 * The one place a rung's identity becomes intent extras (RESEARCH §Pattern 4).
 *
 * The entire difference between the three rungs lives in [forRung]. Plan 06's engine and plan 09's
 * rung work both go through here, so a change to what a rung *means* is a change to exactly one
 * function with one test suite in front of it.
 *
 * ## No API-level scaffolding
 *
 * Every extra used here — `EXTRA_SEGMENTED_SESSION`, all four `EXTRA_AUDIO_SOURCE*` — is available
 * since API 33, and this module's `minSdk` **is** 33 (D-24). The reference implementation guards
 * these behind a Tiramisu API-level annotation only because its floor is lower; carrying that
 * scaffolding here would imply a runtime branch that cannot exist and would hide the fact that the
 * real gate is device behaviour. An acceptance gate greps this file to prove no such annotation
 * crept back in, so do not reintroduce one.
 */
internal object RecognizerIntents {

    // --- Tunable constants ---------------------------------------------------------------------
    // TUNABLE, NOT CONTRACTUAL. These are timing knobs measured on one device; nothing in the public
    // contract depends on their values and plan 09 is free to move them.

    /**
     * Rung (a): silence, in ms, after which the recognizer considers the utterance complete and ends
     * the session.
     *
     * **Trade-off:** shorter ends the session sooner, which means a faster committed final but also
     * a restart (and its 164–259 ms deaf window, plus a beep) after every natural pause. Longer
     * rides through mid-sentence pauses but delays the final. 2500 ms is the reference
     * implementation's measured value and sits past a normal breath without feeling stuck.
     */
    const val RESTART_COMPLETE_SILENCE_MS: Int = 2_500

    /**
     * Rungs (a) and (b): silence, in ms, after which the recognizer treats the utterance as
     * *possibly* complete — the softer endpointer that shapes where partials settle.
     *
     * **Trade-off:** lower makes partials firm up sooner (a livelier dictation feel) at the cost of
     * chopping a speaker who pauses to think. Held below [RESTART_COMPLETE_SILENCE_MS] on purpose;
     * inverting the two is meaningless.
     */
    const val POSSIBLY_COMPLETE_SILENCE_MS: Int = 1_800

    /**
     * Rung (b): the silence bound for a **segmented session** — and the single most consequential
     * constant in this file.
     *
     * **This value bounds the WHOLE SESSION, not one segment.** That is documented behaviour and it
     * was measured: at 1500 ms the entire segmented session died after 1.8 s having produced one
     * segment, because 1.5 s of dead air *before the speaker began* was enough to end it. At 8000 ms
     * the identical code produced one continuous **56.9 s session carrying 13 segment results with
     * zero restarts** — segment gaps of 600–10285 ms rode through comfortably.
     *
     * **Trade-off:** the only cost of a generous value is how long a genuinely finished session
     * lingers before it self-terminates, and the engine ends sessions explicitly anyway. The cost of
     * a stingy value is that rung (b) truncates itself after two seconds and looks like a rung
     * failure. Choose generously; a value tuned as if this were a per-utterance endpointer is the
     * documented way to make a working rung look broken.
     */
    const val SEGMENTED_SESSION_COMPLETE_SILENCE_MS: Int = 8_000

    /**
     * Rung (c): the sample rate the app-owned `AudioRecord` feeds through the pipe.
     *
     * Not really tunable — 16 kHz mono 16-bit is what on-device recognition models expect, and it is
     * the same wire format the web backend and the server's VAD use. Named so the intent and the
     * recorder can never drift apart.
     */
    const val AUDIO_SOURCE_SAMPLE_RATE_HZ: Int = 16_000

    /**
     * Build the fully populated recognition intent for [rung].
     *
     * @param rung which rung's extras to apply.
     * @param config supplies the language. Read from here rather than hardcoded so v2 multilingual
     *   is additive (D-13) — this is the one place [SttConfig.language] reaches the platform.
     * @param context supplies only `packageName` for `EXTRA_CALLING_PACKAGE`.
     * @param audioSource the read end of the app-owned pipe. **Required** for
     *   [Rung.RawAudioSegmented] and ignored by every other rung.
     * @throws IllegalArgumentException when [Rung.RawAudioSegmented] is requested without an
     *   [audioSource]. Emitting a rung-(c) intent with no descriptor would silently produce a
     *   session that behaves like rung (b), which would then degrade for a reason that has nothing
     *   to do with raw audio — fail where the mistake is, not three callbacks later.
     */
    fun forRung(
        rung: Rung,
        config: SttConfig,
        context: Context,
        audioSource: ParcelFileDescriptor? = null,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // Common to every rung.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Belt and braces on top of createOnDeviceSpeechRecognizer(): the factory already pins us to
        // on-device recognition, and this says so again in the intent. Free, and it makes the
        // "no metered cloud API, ever" constraint legible at the call site.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

        when (rung) {
            Rung.RestartingSingleSession -> {
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    RESTART_COMPLETE_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS,
                )
            }

            Rung.SilenceSegmented -> {
                // Same KEYS as rung (a), different value for the complete-silence bound — because
                // here it bounds the session rather than the utterance. See the constant's KDoc.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    SEGMENTED_SESSION_COMPLETE_SILENCE_MS,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    POSSIBLY_COMPLETE_SILENCE_MS,
                )
                // The VALUE is the NAME of another extra key, not a duration. The javadoc requires
                // that the referenced key also be populated in the same intent with a proper value —
                // which the putExtra above does. Naming a key you did not populate is the documented
                // way to get the extra silently ignored.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                )
            }

            Rung.RawAudioSegmented -> {
                requireNotNull(audioSource) {
                    "Rung.RawAudioSegmented requires the read end of the app-owned audio pipe"
                }
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AUDIO_SOURCE_SAMPLE_RATE_HZ)
                // Segmentation is driven by the audio stream itself here, so no silence extras: the
                // stream's end is the session's end. Same javadoc rule as rung (b) — the referenced
                // key is populated above.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }
    }
}

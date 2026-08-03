package io.github.ygaray.sttengine.internal

/**
 * The three interchangeable ways this library can run a continuous-dictation session (D-01).
 *
 * Declared **most capable first**, which is the order [RungLadder.candidates] hands them out and
 * therefore the direction degradation travels: down the list, never up.
 *
 * The rungs differ in exactly one place — the intent extras [RecognizerIntents] builds for them.
 * Everything beneath the ladder (the epoch fence, the segment accumulator, the restart policy) is
 * shared, implemented once, and identical on every rung. That is the whole point of the ladder
 * shape: a better rung is additive and never touches the public contract.
 *
 * There is deliberately **no API-level scaffolding** here. `EXTRA_SEGMENTED_SESSION`, all four
 * `EXTRA_AUDIO_SOURCE*` extras, `onSegmentResults` and `onEndOfSegmentedSession` are all available
 * since API 33, and this module's `minSdk` is 33 (D-24). The reference implementation wraps rungs
 * (b)/(c) in a Tiramisu API-level annotation only because its floor is lower. **The ladder's only
 * gate is device *behaviour*, not API level** — which is why an acceptance gate greps this file to
 * prove no such annotation crept back in. Keep a version guard only where the API genuinely sits
 * above the floor, which in this phase is the model-download listener surface at 34.
 */
internal enum class Rung {

    /**
     * **(c)** App-owned microphone: `AudioRecord` → `ParcelFileDescriptor.createPipe()` → the
     * recognizer reads our pipe, as a segmented session.
     *
     * **Its one unique upside**, and the only reason it is worth the risk: an app-owned microphone
     * is the only path to *buffering PCM across a restart and replaying it*, which turns "no dropped
     * speech" from a hope into engineering — and it is the only credible route to suppressing the
     * recognizer's start beep (D-06 rules out any device-wide audio manipulation).
     *
     * **Its known landmine.** Closing the pipe's write end while the recognizer thread is still
     * reading kills the open segment with `ERROR_CLIENT(5)` and loses the **entire** utterance
     * despite perfect partials — 4/4 reproduced on a Pixel 6a / Android 16, upstream fix still open
     * (expo-speech-recognition #158, #163). See [RungLadder.shouldDegrade] for the trigger that
     * matches that signature exactly.
     *
     * *Measured on the tester (`05-RUNG-VERDICT.md`): **SHIP**. 164 partials, 6 segment finals,
     * precision 1.000 — and the data-loss signature above **did not reproduce**; this device treats
     * a closed write end as a clean end-of-stream. That is a device-specific negative and refutes
     * the upstream report on this hardware only, so the code-5 trigger ships regardless.*
     */
    RawAudioSegmented,

    /**
     * **(b)** The recognizer's own microphone, run as a segmented session bounded by a silence
     * length: `EXTRA_SEGMENTED_SESSION` naming `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`.
     *
     * The platform javadoc warns the extra *"may have no effect"*, and on a non-cooperating OEM it
     * no-ops **silently** — which is why [RungLadder.shouldDegrade] carries a crisp binary
     * discriminator for it rather than trusting the extra.
     *
     * *Measured on the tester: **SHIP**, and better than hoped. One continuous 57-second session
     * carrying 13 segment results with **zero restarts**, therefore zero restart deaf windows and
     * one start beep instead of thirteen. It is also the primary source of populated final text on
     * this device — see [FinalTextPolicy].*
     */
    SilenceSegmented,

    /**
     * **(a)** The classic restart-on-result loop: one ordinary session, restarted each time it ends.
     *
     * **Mandatory and unconditional.** It is the only rung that works on every device, it is the
     * floor the ladder degrades onto, and it is never itself degraded away from — [RungLadder.next]
     * returns it forever and [RungLadder.shouldDegrade] is always `false` for it. There is nothing
     * below it.
     *
     * Its costs are real and are the reason the other two rungs exist: a measured **164–259 ms deaf
     * window** on every restart (median 198 ms over 13 cycles on the tester), and a start beep per
     * session. The deaf window is visible in the acoustic-loopback measurement as clipped utterance
     * onsets — recall 0.559 against precision 0.968, i.e. the words that survived were right, and
     * the ones that vanished were the first of each sentence.
     */
    RestartingSingleSession,
}

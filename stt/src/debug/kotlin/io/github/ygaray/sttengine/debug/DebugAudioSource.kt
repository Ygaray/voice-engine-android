package io.github.ygaray.sttengine.debug

import io.github.ygaray.sttengine.internal.DebugAudioSourceHolder

/**
 * Feed rung (c) a fixed WAV instead of the microphone. **Debug builds only, and structurally so.**
 *
 * ## What this is for
 *
 * AND-02 is scored by an analyzer over a real session trace, so the evidence run needs a stimulus,
 * and the two stimuli available without this were both poor instruments — acoustic speaker-to-mic
 * loopback measured **recall 0.559** (the restart deaf window clips the onset of every utterance, so
 * ~44% of the words never reach the recognizer at all), and a human speaking is not reproducible, so
 * a red verdict cannot be re-run against the same input to see whether a fix worked.
 *
 * Arming a WAV here makes the run deterministic: the same bytes, in the same order, at the same
 * wall-clock pace, every time. See [PacedWavFeed] for why the pacing and the clock-derived read
 * position are load-bearing rather than incidental.
 *
 * ## Why it is safe for the shipped library
 *
 * This whole file lives in `stt/src/debug/`. The debug source set shares a Kotlin compilation with
 * `main` for the debug variant — which is how it can see the `internal`
 * [DebugAudioSourceHolder] — and is absent from `compileReleaseKotlin` entirely. `api/stt.api` is a
 * dump of the **release** compilation, so nothing here can reach the frozen public surface, and the
 * dump is byte-identical with and without this seam (verified, not assumed).
 *
 * The property that actually matters is the one `ReleaseAudioSourceSeamTest` asserts: the holder is
 * `null` in a release compilation **and nothing there can assign it**, because every assignment site
 * in the module is in this directory. Rung (c) in a shipped build resolves to the microphone exactly
 * as it did before the seam existed.
 *
 * ## Usage
 *
 * ```kotlin
 * DebugAudioSource.armWavFile("/sdcard/Android/data/<pkg>/files/and02-stimulus.wav")
 * engine.start()          // rung (c) now reads the file
 * // …
 * DebugAudioSource.disarm()
 * ```
 *
 * [armWavFile] **throws** on a path that does not exist, cannot be read, or is not 16 kHz mono
 * 16-bit PCM. Callers must let that failure be loud and must not start a session anyway: a run that
 * believes it is file-fed but is actually listening to a quiet room produces a plausible-looking
 * trace and a meaningless verdict, which is strictly worse than no run because it is evidence-shaped.
 */
public object DebugAudioSource {

    /**
     * The armed feed, or `null` when rung (c) is on the microphone.
     *
     * Held here as well as in [DebugAudioSourceHolder] because this type knows the WAV facts
     * (duration, bytes fed) and the holder is deliberately just a [io.github.ygaray.sttengine.internal.PcmSourceFactory] cell.
     */
    @Volatile
    private var feed: PacedWavFeed? = null

    /** The armed file's path, or `null` when nothing is armed. */
    public val armedPath: String? get() = feed?.path

    /** How long the armed passage plays for, or `0` when nothing is armed. */
    public val armedDurationMs: Long get() = feed?.durationMs ?: 0L

    /**
     * How many times rung (c) actually opened the armed feed — **the anti-mislabelling fact**.
     *
     * Zero after a run means the pipe was never opened, which means the ladder was not on
     * `RawAudioSegmented`, which means the session listened to the microphone (or to nothing) while
     * the operator believed it was reading the file. A caller that reports a file-fed run without
     * checking this is reporting a guess.
     */
    public val openCount: Int get() = feed?.openCount ?: 0

    /** Bytes of the passage served so far. Zero means no audio reached the recognizer from the file. */
    public val pcmBytesFed: Long get() = feed?.pcmBytesFed ?: 0L

    /** Bytes of tail silence served after the passage ran out. */
    public val silenceBytesFed: Long get() = feed?.silenceBytesFed ?: 0L

    /** True once the wall clock has run past the end of the armed passage. */
    public val exhausted: Boolean get() = feed?.exhausted ?: false

    /**
     * Arm [path] as rung (c)'s audio source for every session until [disarm].
     *
     * Reads and validates the whole file **now**, on the calling thread, so a bad path fails at the
     * moment a human can still fix it rather than inside a two-minute run. The file is held in
     * memory (a 130 s 16 kHz mono passage is ~4 MB) because the pump must never block on storage:
     * a read that stalls is audio the recognizer does not get, which is indistinguishable from the
     * dropped text AND-02 exists to detect.
     *
     * @return the validated passage's duration in milliseconds, so a harness can size its hold from
     *   the file rather than from a hardcoded guess that silently truncates the stimulus.
     * @throws java.io.IOException when [path] is not a readable regular file.
     * @throws IllegalArgumentException when it is not a 16 kHz mono 16-bit PCM WAVE. The message
     *   always names what was actually found.
     */
    public fun armWavFile(path: String): Long {
        val wav = WavPcm16Reader.read(path)
        val armed = PacedWavFeed(wav = wav, path = path)
        feed = armed
        DebugAudioSourceHolder.factory = armed
        return wav.durationMs
    }

    /**
     * Return rung (c) to the microphone.
     *
     * Idempotent, and safe to call when nothing was armed. Clears the holder **first** so that a
     * session starting concurrently cannot pick up a feed this call is in the middle of dropping.
     */
    public fun disarm() {
        DebugAudioSourceHolder.factory = null
        feed = null
    }

    /**
     * One line naming every fact a verdict depends on, for a log or an on-screen banner.
     *
     * Deliberately includes [openCount] and [pcmBytesFed]: those two numbers are the difference
     * between "this run was file-fed" as a claim and as a measurement.
     */
    public fun describe(): String {
        val armed = feed ?: return "audio-source=microphone (nothing armed)"
        return "audio-source=file path=${armed.path} durationMs=${armed.durationMs} " +
            "opens=${armed.openCount} pcmBytes=${armed.pcmBytesFed} " +
            "silenceBytes=${armed.silenceBytesFed} exhausted=${armed.exhausted}"
    }
}

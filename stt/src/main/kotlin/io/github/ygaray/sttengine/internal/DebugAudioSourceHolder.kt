package io.github.ygaray.sttengine.internal

/**
 * The one mutable cell that lets a **debug** build feed rung (c) from something other than the
 * microphone. `null` in every shipped build, and structurally unreachable there.
 *
 * ## Why this cell exists at all
 *
 * AND-02 — "dictation continues seamlessly across forced restarts with no duplicated or dropped
 * text" — is scored by an analyzer over a real session trace, so the evidence run needs a *stimulus*.
 * The two stimuli available without this seam are both bad instruments:
 *
 * - **Acoustic loopback** (speaker → microphone) measured recall **0.559**: roughly 44% of the words
 *   never reached the recognizer at all, because the restart deaf window clips the onset of every
 *   utterance. An instrument that loses 44% of its input cannot distinguish "the engine dropped
 *   text" from "the room dropped text", which is the exact question AND-02 asks.
 * - **A human speaking** is not reproducible, so a red verdict cannot be re-run against the same
 *   input to see whether a fix worked.
 *
 * Feeding a fixed WAV through the app-owned pipe makes the run **deterministic**: the same bytes, in
 * the same order, at the same wall-clock pace, every time. Rung (c) already owns an app-side audio
 * source — this only changes *where those bytes come from*, and only in a debug build.
 *
 * ## Why the cell is in `main` and the setter is not
 *
 * [AudioSourcePipe]'s `sourceFactory` default argument is compiled into `main`, so the cell it reads
 * has to live here too. Nothing in `main` ever *writes* it: the only assignment site in the whole
 * module is `stt/src/debug/`, which shares a Kotlin compilation with `main` for the debug variant
 * (hence it can see this `internal` cell) and is absent from `compileReleaseKotlin` entirely.
 *
 * The consequence is the property that matters: **in a release compilation this is a `null` that
 * nothing can assign**, so rung (c) resolves to [AudioRecordPcmSource] exactly as it did before the
 * seam existed. That is not a claim resting on discipline — `ReleaseAudioSourceSeamTest` asserts the
 * absence of any write outside `src/debug/`, and `api/stt.api` (a dump of the RELEASE compilation)
 * is unchanged by any of this because every symbol involved is `internal` or debug-only.
 *
 * ## The mislabelling hazard this cell must never create
 *
 * A run that *believes* it is file-fed but is actually listening to a quiet room produces a
 * plausible-looking trace and a meaningless verdict — strictly worse than no run, because it is
 * evidence-shaped. Two things guard against it and neither is optional:
 *
 * 1. The file source counts its own opens and bytes, so "was the pipe ever opened?" is a fact rather
 *    than an assumption. See the debug source set's `DebugAudioSource`.
 * 2. The harness requires the session trace to name `RawAudioSegmented` as the active rung. If the
 *    ladder picked (b) or (a), the pipe is never opened, this cell is never consulted, and the run
 *    is aborted rather than scored.
 */
internal object DebugAudioSourceHolder {

    /**
     * Where rung (c)'s PCM comes from, or `null` for the microphone.
     *
     * `@Volatile` because it is written from whichever thread armed it (a broadcast receiver on the
     * main looper, in practice) and read on the main looper inside [AudioSourcePipe.open].
     *
     * Read at **construction** of each [AudioSourcePipe], which is once per recognition session —
     * so arming it before `start()` covers the whole run including every restart, and each restart
     * gets a fresh [PcmSource] from the same factory.
     */
    @Volatile
    internal var factory: PcmSourceFactory? = null
}

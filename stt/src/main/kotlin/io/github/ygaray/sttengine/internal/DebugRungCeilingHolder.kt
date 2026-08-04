package io.github.ygaray.sttengine.internal

/**
 * The one mutable cell that lets a **debug** build cap how high the capability ladder may climb.
 * `null` in every shipped build, and structurally unreachable there.
 *
 * ## The measurement that made this necessary
 *
 * AND-02 asks for dictation that survives **forced restarts**. Producing that evidence on this
 * device turns out to be impossible without a cap, and the reason is worth writing down because it
 * is a property of the shipped design rather than an accident:
 *
 * - Rung (c) [Rung.RawAudioSegmented] sets `EXTRA_SEGMENTED_SESSION` to `EXTRA_AUDIO_SOURCE`, so the
 *   recognition session ends when the **audio source is exhausted** — not on a silence timeout. The
 *   app-owned source is a microphone, or a paced file feed that serves silence rather than
 *   end-of-stream. Neither ever ends. Measured: a 140 s file-fed run and a 75 s quiet-microphone run
 *   both produced **zero** restart events and zero errors.
 * - Nothing degrades off rung (c) either. Its two triggers are `ERROR_CLIENT(5)` while our pipe is
 *   open, and a session that ended having produced no segment results — and a session that only ends
 *   when the host stops it is exempt from both by [RungLadder.shouldDegrade]'s `STOPPED_BY_HOST`
 *   rule, which is correct.
 *
 * So on a device where rung (c) is viable, rung (c) is *absorbing*: a normal run can neither leave it
 * nor restart on it. The restart loop that AND-02 is actually about lives on rung (a)
 * [Rung.RestartingSingleSession], and no runtime input reaches it. This cell is that input, and it
 * exists for exactly one caller: the AND-02 evidence run.
 *
 * ## Why the cell is in `main` and the setter is not
 *
 * [SttEngineImpl] resolves the ladder's capabilities in `main`, so the cell it reads has to live
 * here. Nothing in `main` ever *writes* it: the only assignment site in the whole module is
 * `stt/src/debug/`, which shares a Kotlin compilation with `main` for the debug variant (hence it can
 * see this `internal` cell) and is absent from `compileReleaseKotlin` entirely.
 *
 * The consequence is the property that matters: **in a release compilation this is a `null` that
 * nothing can assign**, so the ladder resolves exactly as it did before the seam existed.
 * `ReleaseRungCeilingSeamTest` asserts that rather than trusting it, and `api/stt.api` (a dump of the
 * RELEASE compilation) is unchanged because every symbol involved is `internal` or debug-only.
 *
 * This is the same shape, for the same reason, as [DebugAudioSourceHolder]: a seam that makes an
 * otherwise-unobtainable measurement obtainable, priced so that a shipped build cannot pay for it.
 *
 * ## The mislabelling hazard
 *
 * A capped run that is reported without checking which rung actually ran is evidence-shaped noise —
 * the same hazard the file-feed seam guards against, and guarded the same way. The engine writes a
 * `rung` line into the session trace for **every** session, and the AND-02 harness refuses to score a
 * capped run unless every one of those lines names the rung that was asked for. A cap is a request;
 * the trace is the fact.
 */
internal object DebugRungCeilingHolder {

    /**
     * The highest rung the ladder may offer, or `null` for the device's own answer.
     *
     * `@Volatile` because it is written from whichever thread set it (a broadcast receiver on the
     * main looper, in practice) and read on the main looper inside [SttEngineImpl.start].
     *
     * Read once per `start()`, at the same point the constructor's capability seeds and this engine's
     * runtime withdrawals are combined — so setting it before `start()` covers the whole run
     * including every restart, and a session already running is never re-levelled underneath itself.
     */
    @Volatile
    internal var ceiling: Rung? = null
}

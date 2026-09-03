package io.github.ygaray.sttengine.recorder

/**
 * Host-configurable Recorder behavior — the seam that closes the two silent parity gaps between the
 * hub `Recorder` and a consumer's own recording pipeline (AUDIO-02, D-01): the amplitude poll cadence
 * on [io.github.ygaray.sttengine.recorder.Recorder.level] (D-03) and the three MediaRecorder
 * audio-encoding parameters.
 *
 * **Every default reproduces this module's own behavior as of tag `v0.3.1`** — a consumer that never
 * constructs a [RecorderConfig] observes the identical cadence and encoding instruction sequence it
 * always has. This is a behavioral/API-compatibility claim, not a byte-identity claim: the constructor
 * validation below and the wider internal seams it enables are genuinely new code.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2), mirroring [RecordedFile] and
 * [RecorderError]: a generated `copy()` signature is part of the published ABI, and a `data class`
 * would permanently foreclose adding a field later without breaking every already-published consumer.
 * A plain class with a defaulted primary constructor and hand-written `equals`/`hashCode`/`toString`
 * stays additively extensible instead.
 *
 * A consumer overrides only the fields it needs — every field is independently defaulted, per D-01.
 *
 * @property levelIntervalMs How often [io.github.ygaray.sttengine.recorder.Recorder.level] ticks,
 *   in milliseconds. Defaults to `150L`, this module's cadence as of tag `v0.3.1`. Must be at least
 *   [MIN_LEVEL_INTERVAL_MS] — see that constant's KDoc for why a smaller value is rejected outright
 *   rather than merely discouraged.
 * @property sampleRateHz The `MediaRecorder.setAudioSamplingRate` value, or `null` to call no such
 *   setter at all and let the platform choose its own default — exactly what happens today when this
 *   field does not exist. Must be strictly positive when non-null.
 * @property encodingBitRate The `MediaRecorder.setAudioEncodingBitRate` value, or `null` for the same
 *   platform-default behavior. Must be strictly positive when non-null.
 * @property channelCount The `MediaRecorder.setAudioChannels` value, or `null` for the same
 *   platform-default behavior. Must be strictly positive when non-null.
 */
public class RecorderConfig(
    public val levelIntervalMs: Long = 150L,
    public val sampleRateHz: Int? = null,
    public val encodingBitRate: Int? = null,
    public val channelCount: Int? = null,
) {

    init {
        require(levelIntervalMs >= MIN_LEVEL_INTERVAL_MS) {
            "levelIntervalMs ($levelIntervalMs) must be at least MIN_LEVEL_INTERVAL_MS " +
                "($MIN_LEVEL_INTERVAL_MS) — a smaller interval saturates Dispatchers.Default with " +
                "getMaxAmplitude() calls and widens the window in which a poll iteration can " +
                "outlive its session (see MIN_LEVEL_INTERVAL_MS's own KDoc)"
        }
        sampleRateHz?.let {
            require(it > 0) { "sampleRateHz must be strictly positive when non-null, was $it" }
        }
        encodingBitRate?.let {
            require(it > 0) { "encodingBitRate must be strictly positive when non-null, was $it" }
        }
        channelCount?.let {
            require(it > 0) { "channelCount must be strictly positive when non-null, was $it" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecorderConfig) return false
        return levelIntervalMs == other.levelIntervalMs &&
            sampleRateHz == other.sampleRateHz &&
            encodingBitRate == other.encodingBitRate &&
            channelCount == other.channelCount
    }

    override fun hashCode(): Int {
        var result = levelIntervalMs.hashCode()
        result = 31 * result + (sampleRateHz ?: 0)
        result = 31 * result + (encodingBitRate ?: 0)
        result = 31 * result + (channelCount ?: 0)
        return result
    }

    override fun toString(): String =
        "RecorderConfig(levelIntervalMs=$levelIntervalMs, sampleRateHz=$sampleRateHz, " +
            "encodingBitRate=$encodingBitRate, channelCount=$channelCount)"

    public companion object {

        /**
         * The smallest [levelIntervalMs] this Recorder accepts, in milliseconds. A strictly-positive
         * check alone is not enough once the cadence is host-controlled (D-03): a positive-yet-tiny
         * interval turns the poll loop's `delay` into a near-busy-spin against the microphone,
         * saturating `Dispatchers.Default` with `getMaxAmplitude()` calls, and — per the
         * poll-loop-versus-release investigation this plan's review triggered — widens the window in
         * which a poll iteration can outlive the [io.github.ygaray.sttengine.recorder.Recorder]
         * session it reads from.
         *
         * `10` is well below any perceptible meter refresh (a 60fps UI refreshes roughly every
         * 16ms), so the floor costs a real host nothing, while it stays comfortably below both this
         * module's own 150ms default and a known consumer's 100ms — neither is affected.
         */
        public const val MIN_LEVEL_INTERVAL_MS: Long = 10L
    }
}

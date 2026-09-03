package io.github.ygaray.sttengine.recorder

import android.app.NotificationManager
import android.app.PendingIntent

/**
 * Host-supplied notification cosmetics for [RecorderForegroundService]'s ongoing foreground
 * notification (D-02 Arm A, AUDIO-03). The library owns notification LIFECYCLE — creating the
 * channel, promoting to the foreground, refreshing content as the [Recorder]'s state changes, and
 * removing it on teardown; the host owns notification CONTENT — icon, title, optional text, channel
 * identity/importance, notification id and an optional tap target. `:recorder` has no drawables,
 * strings or activities of its own to put here, and [RecorderForegroundService] must never invent
 * SecondBrain-specific (or any other consumer-specific) content — see that class's own KDoc.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2, mirrors [RecordedFile] and
 * [RecorderConfig]): a generated `copy()` is part of the published ABI, and a `data class` would
 * permanently foreclose adding a field later without breaking every already-published consumer. A
 * plain class with a defaulted primary constructor and hand-written `equals`/`hashCode`/`toString`
 * stays additively extensible instead.
 *
 * **Validates loudly and early (design decision 9, REVIEW-OC-06b)** — because the tag that freezes
 * this class's shape freezes its failure behavior along with it:
 * - [smallIconResId] and [notificationId] must be non-zero: the platform rejects a zero resource id
 *   / a zero notification id outright, and failing at this constructor is more debuggable than
 *   discovering it mid-`attach`.
 * - [contentTitle], [channelId] and [channelName] must be non-blank: a blank [channelId] in
 *   particular produces a channel the host can never find again in system settings.
 * - If [contentIntent] is supplied, it MUST be immutable (`PendingIntent.isImmutable` must return
 *   `true`). A mutable `PendingIntent` attached to a foreground-service notification lets whoever
 *   receives it rewrite the intent's target — a genuine privilege-escalation surface — and this
 *   library never fills in extras, so it has no legitimate need for mutability.
 *   `PendingIntent.FLAG_IMMUTABLE` has been Google's own guidance since API 31, and because this
 *   module's `minSdk` is 33, `PendingIntent.isImmutable` is available, so the requirement is
 *   *enforced* here, not merely requested in a comment. The library cannot set the flag itself — the
 *   host already built the [PendingIntent] — so rejecting a mutable one with
 *   [PendingIntent.FLAG_IMMUTABLE] named in the exception message is the only enforcement available.
 *
 * **Reusing a [channelId] the host already created elsewhere is a real platform behavior, not a
 * library bug, and this class cannot detect or prevent it:** [RecorderForegroundService] creates the
 * channel on every `attach` (a documented platform no-op if it already exists), and if a channel
 * with this id already exists at a DIFFERENT importance, the platform keeps the EXISTING channel's
 * settings and silently ignores [channelImportance] passed here. Choosing a [channelId] unique to
 * this recording capability avoids the collision; that is the host's responsibility.
 *
 * @property smallIconResId A drawable resource id in the HOST's own resources — this library
 *   publishes none of its own. Must be non-zero. [RecorderForegroundService.attach] additionally
 *   validates that this id actually resolves against the host's resources, which this constructor
 *   cannot check because it has no [android.content.Context].
 * @property contentTitle The notification's title, refreshed by [RecorderForegroundService] as the
 *   attached [Recorder]'s state changes. Must be non-blank.
 * @property contentText Optional secondary line (e.g. a host-rendered elapsed-time string). `null`
 *   renders no second line.
 * @property channelId Defaults to a library-neutral id naming the recording capability, never a
 *   consumer app. Must be non-blank. See the channel-reuse caveat above.
 * @property channelName Defaults to a library-neutral, user-visible channel name. Must be non-blank.
 * @property channelImportance A `NotificationManager.IMPORTANCE_*` value. Defaults to
 *   [NotificationManager.IMPORTANCE_LOW] — visible but not intrusive, appropriate for an ongoing
 *   recording indicator.
 * @property notificationId The system notification id [RecorderForegroundService] promotes under.
 *   Defaults to a library-chosen constant. Must be non-zero.
 * @property contentIntent Optional tap target, fired when the user taps the notification. MUST be
 *   immutable ([PendingIntent.FLAG_IMMUTABLE]) if supplied — see the validation note above.
 */
public class RecorderNotificationConfig(
    public val smallIconResId: Int,
    public val contentTitle: String,
    public val contentText: String? = null,
    public val channelId: String = DEFAULT_CHANNEL_ID,
    public val channelName: String = DEFAULT_CHANNEL_NAME,
    public val channelImportance: Int = NotificationManager.IMPORTANCE_LOW,
    public val notificationId: Int = DEFAULT_NOTIFICATION_ID,
    public val contentIntent: PendingIntent? = null,
) {

    init {
        require(smallIconResId != 0) {
            "smallIconResId must be non-zero — a zero resource id is rejected by the platform"
        }
        require(notificationId != 0) {
            "notificationId must be non-zero — a zero notification id is rejected by the platform"
        }
        require(contentTitle.isNotBlank()) { "contentTitle must not be blank" }
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(channelImportance in NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_MAX) {
            "channelImportance must be a valid NotificationManager.IMPORTANCE_* value " +
                "(IMPORTANCE_NONE..IMPORTANCE_MAX) — an out-of-range value is rejected by the platform"
        }
        contentIntent?.let { intent ->
            require(intent.isImmutable) {
                "contentIntent must be immutable (built with PendingIntent.FLAG_IMMUTABLE) — a " +
                    "mutable PendingIntent attached to a foreground-service notification lets " +
                    "whoever receives it rewrite the intent's target, a genuine " +
                    "privilege-escalation surface this library has no legitimate need for (it " +
                    "never fills in extras). Rebuild the PendingIntent with " +
                    "PendingIntent.FLAG_IMMUTABLE set."
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecorderNotificationConfig) return false
        return smallIconResId == other.smallIconResId &&
            contentTitle == other.contentTitle &&
            contentText == other.contentText &&
            channelId == other.channelId &&
            channelName == other.channelName &&
            channelImportance == other.channelImportance &&
            notificationId == other.notificationId &&
            contentIntent == other.contentIntent
    }

    override fun hashCode(): Int {
        var result = smallIconResId
        result = 31 * result + contentTitle.hashCode()
        result = 31 * result + (contentText?.hashCode() ?: 0)
        result = 31 * result + channelId.hashCode()
        result = 31 * result + channelName.hashCode()
        result = 31 * result + channelImportance
        result = 31 * result + notificationId
        result = 31 * result + (contentIntent?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RecorderNotificationConfig(smallIconResId=$smallIconResId, contentTitle=$contentTitle, " +
            "contentText=$contentText, channelId=$channelId, channelName=$channelName, " +
            "channelImportance=$channelImportance, notificationId=$notificationId, " +
            "contentIntent=$contentIntent)"

    public companion object {

        /** Library-neutral default channel id — names the recording capability, never a consumer app. */
        public const val DEFAULT_CHANNEL_ID: String = "sttengine_recorder_recording"

        /** Library-neutral default channel name, user-visible in the system's notification settings. */
        public const val DEFAULT_CHANNEL_NAME: String = "Recording"

        /** Library-chosen default notification id, used when the host does not supply its own. */
        public const val DEFAULT_NOTIFICATION_ID: Int = 481_210
    }
}

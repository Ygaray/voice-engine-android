package io.github.ygaray.sttengine.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The hub's reusable foreground-service seam for [Recorder] (D-02 Arm A, AUDIO-03). A host binds
 * this service, hands it a [Recorder] plus its own [RecorderNotificationConfig] through
 * [RecorderBinder], and the service promotes itself to the foreground with
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE], owning the notification's lifecycle for as long
 * as the recorder is actively recording.
 *
 * ## The public API is binder-only
 *
 * [onBind] returns a [RecorderBinder] exposing exactly two operations, [RecorderBinder.attach] and
 * [RecorderBinder.detach]. There are no Intent actions, no extras-driven start/stop, and no public
 * Intent constants — a host never constructs a command Intent for this service. [onUnbind] returns
 * `false` and leaves any live attachment alone: a UI client going away must not kill an in-progress
 * recording, because that is what makes backgrounding survivable (see the started-and-bound section
 * below for the mechanism that actually makes this safe rather than merely advisory).
 *
 * ## Started-and-bound lifecycle (design decision 2, REVIEW-OC-02)
 *
 * A bound-only service is destroyed by the platform when its last client unbinds, so a host binding
 * from an Activity would lose this service — and with it the microphone foreground-service backing
 * — across an ordinary configuration change. [RecorderBinder.attach] therefore self-starts this
 * service (via a plain `startService` call, AFTER promoting to the foreground — see `attach`'s own
 * comment for why that order matters) so the service holds the STARTED state independently of any
 * client binding. [onStartCommand] does nothing but return [START_NOT_STICKY] — it exists ONLY to
 * make that self-start legal, not as a started-service command protocol. `START_NOT_STICKY` is
 * deliberate: a service the system recreates after process death has no [Recorder] attached and
 * nothing to record, so resurrecting it would only post a notification with nothing behind it. Do
 * not "improve" this to `START_STICKY`. Teardown calls `stopSelf()` to leave the started state —
 * without that, the started state would keep this service alive forever after a recording ends.
 *
 * ## The armed teardown rule, v2 (design decision 7, REVIEW-OC-01 the phase's #1 blocker, refined
 * for NEW-MEDIUM-1)
 *
 * [Recorder.state] is a `StateFlow` that replays its current value — initially [RecorderState.Idle]
 * — to every new collector the instant it subscribes. An observer whose rule was naively "tear down
 * on Idle or Error" would therefore tear this service down on the very first dispatch after
 * `attach`, before recording ever starts. The observer launched in `attach` instead carries a
 * private `armed` flag and applies exactly these three branches:
 * - [RecorderState.Recording] / [RecorderState.Paused] → `armed = true`, refresh the notification.
 * - [RecorderState.Idle] → tear down ONLY IF `armed` is already true. An unarmed `Idle` — the
 *   replayed constructor-initial value — is the ONE state this service ignores.
 * - [RecorderState.Error] → tear down ALWAYS, armed or not. `Error` is never a "waiting to start"
 *   state: the replayed initial value is always `Idle` and never `Error`, so exempting an unarmed
 *   `Error` (a v1 mistake, corrected in review cycle 2) protects nothing while leaving a
 *   microphone-typed foreground notification claiming a recording is in progress when a `start()`
 *   has actually just failed (`Idle → Error`) — exactly the ordinary attach-before-record path a
 *   host UI exercises.
 *
 * This service closes every one of those cases by itself: a failed `start()`, any other transition
 * into `Error`, a recording that ends normally, and an external `stop()` on the [Recorder] (which
 * drives it to `Idle` while armed) all tear this service down without host involvement.
 *
 * ## The one thing this service cannot close by itself — a stated host obligation
 *
 * A host that calls [RecorderBinder.attach] and then abandons the recorder without ever calling
 * `start()` keeps the notification and the started state indefinitely: an unarmed `Idle` is
 * indistinguishable, from state alone, between "about to start" and "abandoned". No library-side
 * timeout can safely resolve this (it would freeze a policy constant into an immutable tag and kill
 * the legitimate slow-start case — a host binding when a screen opens and the user pressing record
 * minutes later), so it is not attempted. **A host that attaches MUST call [RecorderBinder.detach]
 * if recording never starts, wired to its own lifecycle teardown.** This is the ONLY leak path this
 * service does not close on its own.
 *
 * **Recovering from a failed `start()` (NEW-LOW-5):** because `Error` always tears this service
 * down, and [Recorder.start] can be re-called from `Error`, a host recovering from a failed start
 * must call `start()` on the [Recorder] BEFORE re-attaching it to this service — re-attaching to a
 * recorder still sitting at `Error` immediately re-tears-down (the replayed value is `Error`), and a
 * `start()` that then succeeds afterward would have no foreground service watching it.
 *
 * ## Double-attach, re-attach, and stop-without-detach (design decision 8, REVIEW-OC-03)
 *
 * Attaching while already attached REPLACES rather than tears down: exactly one observer job is
 * ever live, the notification is refreshed in place rather than removed-and-re-posted, and `armed`
 * is re-derived from the newly attached recorder's replayed current state. Attaching AFTER a
 * completed teardown is also supported and needs no special case — because `onUnbind` returns
 * `false`, a service that tore itself down on an armed terminal state stays bound, and a host may
 * legitimately `attach` again to record a second clip without rebinding.
 *
 * ## Threading contract (design decision 12, NEW-MEDIUM-2)
 *
 * This service is bound with a LOCAL binder: because it is non-exported and bound in-process,
 * [onBind] hands the host the [Binder] object itself, and the host's `attach`/`detach` calls execute
 * directly on whichever thread the host calls from — no marshalling, no framework-provided thread
 * hop. **`attach`, `detach` and this service's lifecycle callbacks are main-thread-only, and this
 * service is NOT thread-safe against concurrent binder calls from arbitrary threads** — that is a
 * STATED CONTRACT, not an inferred guarantee, precisely because a local binder promises nothing
 * here. The service's own coroutine scope names its dispatcher explicitly —
 * `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` — deliberately unlike
 * `RecorderImpl`'s `Dispatchers.Default` amplitude-poll scope, where thread affinity does not
 * matter; here serialization of lifecycle transitions is the whole point, so the state observer, and
 * every teardown it initiates, runs main-confined. Do not "harmonize" the two scopes later.
 *
 * As defense-in-depth on TOP of that contract — not a replacement for it, and NOT an endorsement of
 * cross-thread use — the mutable fields below are `@Volatile`, and the teardown counter and the
 * attachment generation are [AtomicInteger]. The generation guard specifically NARROWS (it does not
 * close — that is the main-thread-confinement contract's job) the one window volatility alone would
 * not: observer-job cancellation is asynchronous, so an observer already past its branch check when
 * a new `attach` cancels it could otherwise call `stopSelf()` after the new `attach` has re-promoted
 * and re-started this service for a brand-new recording. Every `attach` bumps the attachment
 * generation; each observer captures it at launch; an observer-initiated teardown is a no-op unless
 * its captured generation is still the current one.
 *
 * ## Manifest declaration and permission inheritance (REVIEW-OC-07, T-142-06)
 *
 * This service is declared `android:exported="false"` in this module's manifest — no other app on
 * the device may bind to it. Declaring it here also means every consumer of `:recorder` inherits
 * `android.permission.FOREGROUND_SERVICE` and `android.permission.FOREGROUND_SERVICE_MICROPHONE` by
 * manifest merge, whether or not it ever binds this service — see this module's
 * `AndroidManifest.xml` header for the full rationale and the rejected alternative (a separate
 * opt-in artifact).
 *
 * This service adds NO external dependency to `:recorder` — `kotlinx-coroutines-android` (which
 * supplies [Dispatchers.Main]) is already an `api` dependency of this module.
 */
public class RecorderForegroundService : Service() {

    // --- mutable service state, hardened per design decision 12 (defense-in-depth ON TOP of the
    // main-thread-confinement contract stated in the class KDoc above, NOT a substitute for it) ---

    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var attachedRecorder: Recorder? = null

    @Volatile
    private var attachedConfig: RecorderNotificationConfig? = null

    @Volatile
    private var observerJob: Job? = null

    private val teardownCount = AtomicInteger(0)
    private val attachmentGeneration = AtomicInteger(0)

    private lateinit var notificationManager: NotificationManager

    /**
     * Main-confined so the state observer — and every teardown it initiates — is serialized on the
     * main thread (design decision 12). Deliberately NOT `Dispatchers.Default`, unlike
     * `RecorderImpl`'s amplitude-poll scope: this one drives lifecycle transitions, where
     * serialization is the entire point, not an incidental convenience.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder = RecorderBinder()

    /**
     * Returns `false` and leaves any live attachment untouched: a UI client going away (e.g. an
     * Activity destroyed by a configuration change) must not stop an in-progress recording. The
     * started state entered by [RecorderBinder.attach] — not this callback — is what actually keeps
     * this service alive once every client has unbound; see the class KDoc's started-and-bound
     * section.
     */
    override fun onUnbind(intent: Intent?): Boolean = false

    /**
     * A deliberate no-op returning [START_NOT_STICKY]. This exists ONLY to make the self-start
     * inside [RecorderBinder.attach] legal — it is NOT a started-service command protocol: there are
     * no Intent actions, no extras and no public Intent constants read here, and no host ever
     * constructs a start Intent for this service. `START_NOT_STICKY` specifically: a service the
     * system recreates after process death would have no [Recorder] attached and nothing to record,
     * so resurrecting it would post a notification with nothing behind it. Do not change this to
     * `START_STICKY`.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        teardown()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Called when the user removes the host app from the recent-tasks list. Routed through the same
     * single [teardown] as every other exit path (T-142-02) rather than duplicating the ordering.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        teardown()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The ONLY host-facing API surface of [RecorderForegroundService] — see the class KDoc's
     * "public API is binder-only" section. Obtained from [onBind]; both methods below are
     * main-thread-only (see the class KDoc's threading-contract section).
     */
    public inner class RecorderBinder internal constructor() : Binder() {

        /**
         * Attach [recorder] to this service and promote it to a microphone-typed foreground
         * service, rendering [notification]'s content. Calling this while a DIFFERENT recorder is
         * already attached REPLACES it (design decision 8): the previous observer is cancelled, the
         * notification is rebuilt in place, and `armed` is re-derived from [recorder]'s own current
         * state — it does not tear this service down mid-swap. Calling this again after a completed
         * [detach] (or after this service tore itself down on its own) is also supported and
         * re-promotes from scratch.
         *
         * Throws [IllegalArgumentException] if [notification]'s `smallIconResId` does not resolve
         * against this service's resources. That check runs BEFORE anything observable happens, so
         * a rejected config leaves no half-promoted service and no orphan notification behind — and
         * if a different recorder was already attached, it is left completely untouched.
         *
         * **Host obligation:** if recording never starts after this call, you MUST call [detach]
         * yourself — see the class KDoc's "one thing this service cannot close by itself" section
         * for why no library-side timeout exists for this. Recovering from a failed `start()`
         * (NEW-LOW-5): call `start()` on [recorder] again BEFORE re-attaching it here, not after —
         * re-attaching a recorder still sitting at [RecorderState.Error] tears this service back
         * down immediately.
         *
         * Main-thread-only. This service is not thread-safe against a call to this method from a
         * thread other than the one the rest of the host's calls arrive from.
         */
        public fun attach(recorder: Recorder, notification: RecorderNotificationConfig) {
            this@RecorderForegroundService.attach(recorder, notification)
        }

        /**
         * Explicitly end the current attachment, if any: cancels the observer, removes the
         * notification, and leaves the started state via `stopSelf()`. A harmless no-op if nothing
         * is currently attached — including when the service already tore itself down on its own
         * (e.g. an armed terminal state, or an unarmed `Error`) and this call arrives afterward.
         *
         * Main-thread-only — see the class KDoc's threading-contract section.
         */
        public fun detach() {
            this@RecorderForegroundService.teardown()
        }
    }

    private fun attach(recorder: Recorder, notification: RecorderNotificationConfig) {
        // Step 1 (design decision 9): validate the icon resolves BEFORE anything else is touched —
        // a bad config must leave no half-promoted service and no orphan notification behind, and
        // if a DIFFERENT recorder is already attached it must be left completely untouched.
        require(runCatching { resources.getResourceName(notification.smallIconResId) }.isSuccess) {
            "RecorderNotificationConfig.smallIconResId (${notification.smallIconResId}) does not " +
                "resolve against this service's resources — attach() rejects it before any " +
                "foreground promotion so a bad config leaves no half-promoted service behind"
        }

        // Step 2 (design decision 8, 12): bump the attachment generation and REPLACE — cancel only
        // the existing observer job, never route through teardown() here, which would remove the
        // notification / leave the started state / stopSelf() in the middle of a legitimate re-attach.
        val generation = attachmentGeneration.incrementAndGet()
        observerJob?.cancel()

        // Step 3: store the new attachment and reset armed — re-derived below from the replayed
        // current state of `recorder`.
        attachedRecorder = recorder
        attachedConfig = notification
        armed = false

        // Step 4: creating an already-existing channel is a documented platform no-op.
        notificationManager.createNotificationChannel(
            NotificationChannel(notification.channelId, notification.channelName, notification.channelImportance),
        )

        // Steps 5-6: build and promote. On a replace, this updates the existing notification in
        // place rather than removing and re-posting it.
        startForeground(
            notification.notificationId,
            buildNotification(notification),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        // Step 7 (design decision 2, REVIEW-OC-02): enter the STARTED state AFTER the foreground
        // promotion, not before — once this process already hosts a running foreground service it
        // is not subject to the background-service-start restriction that would otherwise reject
        // this call. A second self-start on an already-started service is harmless: it only
        // delivers another onStartCommand, which is a no-op. Plain platform Context API — no
        // support-library helper.
        startService(Intent(this, RecorderForegroundService::class.java))

        // Step 8 (design decision 7 v2, 12): the armed-rule observer, generation-guarded on
        // teardown. See the class KDoc's armed-teardown-rule section for the branch rationale.
        //
        // CoroutineStart.LAZY + an explicit start() below is REQUIRED, not stylistic. Because the
        // service scope runs on Dispatchers.Main.immediate, a recorder that is ALREADY at Error when
        // attached (e.g. Test 17) drives this coroutine's body SYNCHRONOUSLY and REENTRANTLY inside
        // this very `attach` call, before a plain `launch { ... }` would otherwise return — and that
        // reentrant path calls teardown(), which reads and clears the [observerJob] field. If the
        // job were assigned the ordinary way (`observerJob = serviceScope.launch { ... }`), the
        // right-hand side's synchronous execution would run teardown() (setting observerJob = null)
        // BEFORE the assignment happened, and the assignment would then immediately clobber that
        // null with a stale-but-still-running job reference — leaving the observer looking "active"
        // after a teardown that already happened. Assigning the job to observerJob BEFORE calling
        // start() means a reentrant teardown() cancels the correct (self-same) job, and no later
        // assignment can clobber the null it leaves behind.
        val observer = serviceScope.launch(start = CoroutineStart.LAZY) {
            recorder.state.collect { recorderState ->
                when (recorderState) {
                    RecorderState.Recording, RecorderState.Paused -> {
                        armed = true
                        refreshNotification()
                    }
                    RecorderState.Idle -> {
                        // The ONE exempt state, and ONLY while unarmed — see class KDoc. Widening
                        // this exemption to Error is the exact NEW-MEDIUM-1 regression; do not.
                        if (armed) teardownIfGenerationCurrent(generation)
                    }
                    RecorderState.Error -> {
                        // Unconditional — armed or not. See class KDoc for why Error is never exempt.
                        teardownIfGenerationCurrent(generation)
                    }
                }
            }
        }
        observerJob = observer
        observer.start()
    }

    /**
     * The generation guard (design decision 12, NEW-MEDIUM-2): an observer-initiated teardown is a
     * no-op unless the generation it captured at launch is still the current one. Cancelling
     * [observerJob] in `attach` is not enough by itself — cancellation is asynchronous, so an
     * observer already past its branch check when a new `attach` cancels it could otherwise reach
     * here after that new `attach` has already re-promoted and re-started this service. This
     * NARROWS that window (it does not, by itself, CLOSE it — the main-thread-confinement contract
     * in the class KDoc is what actually closes it for a host that honors that contract).
     */
    private fun teardownIfGenerationCurrent(capturedGeneration: Int) {
        if (attachmentGeneration.get() == capturedGeneration) {
            teardown()
        }
    }

    /**
     * The ONE teardown implementation (design decision 4), routed to from FOUR call sites: the
     * binder's `detach()`, the observer's armed-terminal-state branches (via
     * [teardownIfGenerationCurrent]), [onDestroy] and [onTaskRemoved]. Idempotent — a call when
     * nothing is attached (already torn down, or never attached) is a no-op that does not touch any
     * platform API and does not advance [teardownCount] again; this is what makes an external
     * `stop()` (which self-triggers this via the observer) followed by a host `detach()` safe rather
     * than a double teardown.
     *
     * Ordering here is the entire T-142-02 (ghost-notification) mitigation and MUST NOT be
     * reordered: the observer is cancelled and the attachment references cleared BEFORE the
     * notification is touched, so a state emission racing this very teardown can never re-post it —
     * see [refreshNotification]'s own guard. `stopSelf()` is last, to leave the STARTED state
     * entered in `attach` (design decision 2) only once every other cleanup step has run.
     */
    private fun teardown() {
        if (attachedRecorder == null) return // idempotent no-op — already torn down, or never attached

        val notificationId = attachedConfig?.notificationId

        observerJob?.cancel()
        observerJob = null
        attachedRecorder = null
        attachedConfig = null
        armed = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        // Belt-and-suspenders: stopForeground(STOP_FOREGROUND_REMOVE) SHOULD remove the
        // notification on its own, but SecondBrain's RecordingService needed this extra explicit
        // cancel in practice (BUG-04) to close a gap between that call and a straggling notify().
        if (notificationId != null) notificationManager.cancel(notificationId)

        teardownCount.incrementAndGet()
        // Leave the started state entered in attach() — without this, the started state would keep
        // this service alive forever after a recording ends (design decision 2, REVIEW-OC-02).
        stopSelf()
    }

    /** Guarded on the still-attached config: a post-teardown emission finds this `null` and no-ops. */
    private fun refreshNotification() {
        val config = attachedConfig ?: return
        notificationManager.notify(config.notificationId, buildNotification(config))
    }

    private fun buildNotification(config: RecorderNotificationConfig): Notification {
        val builder = Notification.Builder(this, config.channelId)
            .setSmallIcon(config.smallIconResId)
            .setContentTitle(config.contentTitle)
            .setOngoing(true)
        config.contentText?.let { builder.setContentText(it) }
        config.contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    // --- internal test hooks (module-visible only; not on the frozen public surface) --------------

    internal fun isAttachedForTest(): Boolean = attachedRecorder != null

    internal fun isObserverActiveForTest(): Boolean = observerJob?.isActive == true

    internal fun isArmedForTest(): Boolean = armed

    internal fun teardownCountForTest(): Int = teardownCount.get()

    internal fun attachmentGenerationForTest(): Int = attachmentGeneration.get()
}

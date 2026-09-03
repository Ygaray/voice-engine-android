package io.github.ygaray.sttengine.recorder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [RecorderForegroundService] + [RecorderNotificationConfig] — the started-and-bound foreground
 * seam (D-02 Arm A, AUDIO-03). Tests 1-7 are the original lifecycle contract; tests 8-15 close three
 * places cross-AI review cycle 1 found the original contract underspecified; tests 16-19 close two
 * gaps cycle 1's OWN fixes introduced (an over-broad unarmed-terminal exemption and an unstated
 * threading model). Every fake-recorder state change is driven by writing to the fake's backing
 * [MutableStateFlow] and then idling the Robolectric main looper, so each assertion is a real
 * observation rather than a race the test happened to win.
 *
 * ## What each test rules out
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | 1 | The manifest fails to declare the microphone type, non-export, or either permission (shadow-free) |
 * | 2 | `onBind` returns null or the wrong binder type |
 * | 3 | `attach` never actually promotes to the foreground |
 * | 4 | `detach` leaves the foreground state or the notification behind |
 * | 5 | A post-`detach` state emission re-posts a "ghost" notification (T-142-02) |
 * | 6a/6b | `onDestroy`/`onTaskRemoved` skip notification removal |
 * | 7 | `onUnbind` kills an in-progress recording |
 * | 8 | The service tears down on the replayed initial `Idle` before recording ever starts (REVIEW-OC-01, the phase's #1 blocker) |
 * | 9 | `Recording → Idle` or `Recording → Error` is undefined instead of tearing down exactly once (REVIEW-OC-01/03) |
 * | 10 | Attaching to an already-`Recording` recorder fails to arm immediately |
 * | 11 | The service is destroyed by the platform on the last unbind, killing a live recording across a configuration change (REVIEW-OC-02, the phase's #2 blocker) |
 * | 12 | Double-attach tears the service down instead of replacing the attachment |
 * | 13 | An external `stop()` leaks a bound service, or a late `detach()` double-tears-down |
 * | 14 | `RecorderNotificationConfig` accepts a zero id, a blank string, an out-of-range `channelImportance` (IN-01), or a mutable `PendingIntent` |
 * | 15 | `attach` half-promotes the service before discovering a bad icon id |
 * | 16 | An unarmed `Idle → Error` (a failed `start()`) is left running with a false "recording" notification (NEW-MEDIUM-1) |
 * | 17 | Attaching to a recorder already at `Error` is left running the same way |
 * | 18 | An abandoned unarmed `Idle` is torn down anyway (violating the documented host-obligation contract), or re-attach after teardown is broken (NEW-MEDIUM-1 residual, NEW-LOW-3) |
 * | 19 | A superseded observer's terminal-state emission tears down a newer attachment (NEW-MEDIUM-2 — logic on one looper, NOT a concurrency stress test) |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderForegroundServiceTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    private val validIcon = android.R.drawable.ic_btn_speak_now
    private val unresolvableIcon = 0x7f999999

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    // --- plumbing ---------------------------------------------------------------------------------

    private fun newService(): RecorderForegroundService = Robolectric.setupService(RecorderForegroundService::class.java)

    private fun bind(service: RecorderForegroundService): RecorderForegroundService.RecorderBinder =
        service.onBind(Intent(context, RecorderForegroundService::class.java)) as RecorderForegroundService.RecorderBinder

    private fun notificationConfig(id: Int, icon: Int = validIcon): RecorderNotificationConfig =
        RecorderNotificationConfig(smallIconResId = icon, contentTitle = "Recording", notificationId = id)

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * A publicly-settable backing [MutableStateFlow] so a test can push any state sequence directly
     * — including starting already at [RecorderState.Recording]/[RecorderState.Error] (tests 10, 17)
     * and holding two independent fakes at once (tests 12, 19). [level]/[errors] are empty; nothing
     * under test observes them.
     */
    private class FakeRecorder(initialState: RecorderState = RecorderState.Idle) : Recorder {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<RecorderState> = mutableState.asStateFlow()
        override val level: Flow<Float> = emptyFlow()
        override val errors: Flow<RecorderError> = emptyFlow()
        override suspend fun start() {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun stop(): RecordedFile =
            RecordedFile(uri = "fake-uri", durationMs = 0L, format = "audio/mp4", createdAt = "2024-01-01T00:00:00Z")
        override suspend fun delete(uri: String) {}
    }

    /**
     * The `:recorder` module directory. Tries `user.dir` first (AGP's Gradle test-task working
     * directory is the module directory) and falls back to walking up from this test's own class
     * output — `RecorderModuleBoundaryTest`'s technique, which is NOT reliable here on its own: that
     * test is plain JUnit, while this one runs under `RobolectricTestRunner`'s instrumenting
     * classloader, where `protectionDomain.codeSource.location` is not always a normal jar/dir path.
     */
    private fun moduleDir(): File {
        val marker = "src/main/kotlin/io/github/ygaray/sttengine/recorder/Recorder.kt"

        val userDirCandidate = File(System.getProperty("user.dir") ?: "").absoluteFile
        if (File(userDirCandidate, marker).isFile) return userDirCandidate

        val start = javaClass.protectionDomain?.codeSource?.location?.path
        if (start != null) {
            var candidate: File? = File(start).absoluteFile
            while (candidate != null) {
                if (File(candidate, marker).isFile) return candidate
                candidate = candidate.parentFile
            }
        }
        throw AssertionError(
            "could not locate the :recorder module directory from user.dir=" +
                "\"${System.getProperty("user.dir")}\" or from this test's class output (\"$start\")",
        )
    }

    // --- Tests 1-7: the original lifecycle contract ------------------------------------------------

    @Test
    fun `Test 1 - manifest declares microphone type, non-exported service, and both foreground-service permissions`() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml")
        assertTrue("expected the manifest at $manifest", manifest.isFile)
        val text = manifest.readText()

        assertTrue(
            "manifest missing android:foregroundServiceType=\"microphone\"",
            text.contains("android:foregroundServiceType=\"microphone\""),
        )
        assertTrue("manifest missing a RecorderForegroundService element", text.contains("RecorderForegroundService"))
        assertTrue("manifest missing android:exported=\"false\"", text.contains("android:exported=\"false\""))
        assertTrue(
            "manifest missing the FOREGROUND_SERVICE permission",
            text.contains("android.permission.FOREGROUND_SERVICE\""),
        )
        assertTrue(
            "manifest missing the FOREGROUND_SERVICE_MICROPHONE permission",
            text.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"),
        )
    }

    @Test
    fun `Test 2 - onBind returns a non-null RecorderBinder`() {
        val service = newService()
        val binder = service.onBind(Intent(context, RecorderForegroundService::class.java))
        assertNotNull(binder)
        assertTrue(binder is RecorderForegroundService.RecorderBinder)
    }

    @Test
    fun `Test 3 - attach promotes to the foreground under the config's notification id`() {
        val service = newService()
        val binder = bind(service)

        binder.attach(FakeRecorder(), notificationConfig(id = 5003))
        idle()

        assertEquals(5003, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(notificationManager).getNotification(5003))
    }

    @Test
    fun `Test 4 - detach tears down foreground state and removes the notification`() {
        val service = newService()
        val binder = bind(service)
        binder.attach(FakeRecorder(), notificationConfig(id = 5004))
        idle()

        binder.detach()

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).notificationShouldRemoved)
        assertNull(shadowOf(notificationManager).getNotification(5004))
        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 5 - a state emission after detach posts no notification (T-142-02, the ghost-notification proof)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder()
        binder.attach(fake, notificationConfig(id = 5005))
        idle()

        binder.detach()
        idle()
        assertFalse(service.isObserverActiveForTest())

        fake.mutableState.value = RecorderState.Recording
        idle()

        assertFalse(service.isObserverActiveForTest())
        assertNull(shadowOf(notificationManager).getNotification(5005))
    }

    @Test
    fun `Test 6a - onDestroy removes the notification and leaves the observer inactive`() {
        val service = newService()
        val binder = bind(service)
        binder.attach(FakeRecorder(), notificationConfig(id = 5006))
        idle()

        service.onDestroy()

        assertFalse(service.isObserverActiveForTest())
        assertNull(shadowOf(notificationManager).getNotification(5006))
        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 6b - onTaskRemoved removes the notification and leaves the observer inactive`() {
        val service = newService()
        val binder = bind(service)
        binder.attach(FakeRecorder(), notificationConfig(id = 5007))
        idle()

        service.onTaskRemoved(null)

        assertFalse(service.isObserverActiveForTest())
        assertNull(shadowOf(notificationManager).getNotification(5007))
        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 7 - onUnbind leaves the attachment and the notification in place`() {
        val service = newService()
        val binder = bind(service)
        binder.attach(FakeRecorder(), notificationConfig(id = 5008))
        idle()

        val stillBound = service.onUnbind(Intent(context, RecorderForegroundService::class.java))

        assertFalse(stillBound)
        assertEquals(0, service.teardownCountForTest())
        assertTrue(service.isAttachedForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5008))
    }

    // --- Tests 8-15: cycle-1 review findings (REVIEW-OC-01/02/03/06b) -------------------------------

    @Test
    fun `Test 8 - attach to a never-recorded recorder does NOT tear down (REVIEW-OC-01, the phase's #1 blocker)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder(initialState = RecorderState.Idle)

        binder.attach(fake, notificationConfig(id = 5009))
        idle()

        assertNotNull(shadowOf(notificationManager).getNotification(5009))
        assertTrue(service.isObserverActiveForTest())
        assertFalse(service.isArmedForTest())
        assertEquals(0, service.teardownCountForTest())

        fake.mutableState.value = RecorderState.Recording
        idle()

        assertNotNull(shadowOf(notificationManager).getNotification(5009))
        assertTrue(service.isArmedForTest())
        assertEquals(0, service.teardownCountForTest())
    }

    @Test
    fun `Test 9 - Recording then Idle, and Recording then Error, each tear down exactly once (REVIEW-OC-01, REVIEW-OC-03)`() {
        // Idle case.
        run {
            val service = newService()
            val binder = bind(service)
            val fake = FakeRecorder()
            binder.attach(fake, notificationConfig(id = 5010))
            idle()
            fake.mutableState.value = RecorderState.Recording
            idle()

            fake.mutableState.value = RecorderState.Idle
            idle()

            assertEquals(1, service.teardownCountForTest())
            assertNull(shadowOf(notificationManager).getNotification(5010))
            assertFalse(service.isObserverActiveForTest())
        }
        // Error case — identical outcome, proving Error routes through the same single teardown.
        run {
            val service = newService()
            val binder = bind(service)
            val fake = FakeRecorder()
            binder.attach(fake, notificationConfig(id = 5011))
            idle()
            fake.mutableState.value = RecorderState.Recording
            idle()

            fake.mutableState.value = RecorderState.Error
            idle()

            assertEquals(1, service.teardownCountForTest())
            assertNull(shadowOf(notificationManager).getNotification(5011))
            assertFalse(service.isObserverActiveForTest())
        }
    }

    @Test
    fun `Test 10 - attach to an already-Recording recorder arms immediately without tearing down`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder(initialState = RecorderState.Recording)

        binder.attach(fake, notificationConfig(id = 5012))
        idle()

        assertTrue(service.isArmedForTest())
        assertEquals(0, service.teardownCountForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5012))

        fake.mutableState.value = RecorderState.Idle
        idle()

        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 11 - a recording survives unbind then rebind on the same service instance (REVIEW-OC-02, the phase's #2 blocker)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder()
        binder.attach(fake, notificationConfig(id = 5013))
        idle()
        fake.mutableState.value = RecorderState.Recording
        idle()

        // Prove the MECHANISM (a start-service Intent was issued), not merely that Robolectric
        // declined to destroy the service — see the class KDoc's Test 11 row and the SUMMARY's
        // Flagged Assumptions restatement for exactly what this does and does not prove.
        val startedIntent = shadowOf(service).nextStartedService
        assertNotNull("expected attach() to self-start this service", startedIntent)
        assertEquals(RecorderForegroundService::class.java.name, startedIntent!!.component?.className)

        val intent = Intent(context, RecorderForegroundService::class.java)
        val stillBoundAfterUnbind = service.onUnbind(intent)
        assertFalse(stillBoundAfterUnbind)
        // onUnbind returned false, so a subsequent bind calls onBind again (not onRebind).
        service.onBind(intent)

        assertEquals(0, service.teardownCountForTest())
        assertTrue(service.isAttachedForTest())
        assertTrue(service.isObserverActiveForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5013))
    }

    @Test
    fun `Test 12 - double-attach REPLACES - one live observer, no teardown, the new config and recorder win (REVIEW-OC-03)`() {
        val service = newService()
        val binder = bind(service)
        val fakeA = FakeRecorder()
        binder.attach(fakeA, notificationConfig(id = 5014))
        idle()
        fakeA.mutableState.value = RecorderState.Recording
        idle()

        val fakeB = FakeRecorder()
        binder.attach(fakeB, notificationConfig(id = 5015))
        idle()

        assertEquals(0, service.teardownCountForTest())
        assertTrue(service.isObserverActiveForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5015))

        // A's flow no longer drives anything — its observer was replaced, not merely ignored.
        fakeA.mutableState.value = RecorderState.Idle
        idle()
        assertEquals(0, service.teardownCountForTest())

        // B's flow does drive the (single, current) observer.
        fakeB.mutableState.value = RecorderState.Recording
        idle()
        assertTrue(service.isArmedForTest())
        fakeB.mutableState.value = RecorderState.Idle
        idle()
        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 13 - an external stop() tears down without detach, and a later detach is a harmless no-op (REVIEW-OC-03)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder()
        binder.attach(fake, notificationConfig(id = 5016))
        idle()
        fake.mutableState.value = RecorderState.Recording
        idle()

        // Simulate the host calling stop() directly on the recorder (not through the binder).
        fake.mutableState.value = RecorderState.Idle
        idle()

        assertEquals(1, service.teardownCountForTest())
        assertNull(shadowOf(notificationManager).getNotification(5016))
        assertFalse(service.isObserverActiveForTest())

        binder.detach() // late — harmless no-op, does not double-teardown.

        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 14 - RecorderNotificationConfig rejects invalid input and accepts an immutable PendingIntent (REVIEW-OC-06b)`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = 0, contentTitle = "t")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = validIcon, contentTitle = "t", notificationId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = validIcon, contentTitle = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = validIcon, contentTitle = "t", channelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = validIcon, contentTitle = "t", channelName = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(
                smallIconResId = validIcon,
                contentTitle = "t",
                channelImportance = NotificationManager.IMPORTANCE_MAX + 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(
                smallIconResId = validIcon,
                contentTitle = "t",
                channelImportance = NotificationManager.IMPORTANCE_NONE - 1,
            )
        }

        val mutableIntent = PendingIntent.getActivity(
            context,
            0,
            Intent("recorder-test-mutable"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RecorderNotificationConfig(smallIconResId = validIcon, contentTitle = "t", contentIntent = mutableIntent)
        }

        val immutableIntent = PendingIntent.getActivity(
            context,
            0,
            Intent("recorder-test-immutable"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val configWithImmutableIntent = RecorderNotificationConfig(
            smallIconResId = validIcon,
            contentTitle = "t",
            contentIntent = immutableIntent,
        )
        assertNotNull(configWithImmutableIntent)
    }

    @Test
    fun `Test 15 - attach rejects an unresolvable icon id with no side effects (REVIEW-OC-06b)`() {
        val service = newService()
        val binder = bind(service)
        val cfg = notificationConfig(id = 5017, icon = unresolvableIcon)

        assertThrows(IllegalArgumentException::class.java) {
            binder.attach(FakeRecorder(), cfg)
        }
        idle()

        assertFalse(service.isAttachedForTest())
        assertFalse(service.isObserverActiveForTest())
        assertNull(shadowOf(notificationManager).getNotification(5017))
        assertNull(shadowOf(service).peekNextStartedService())
    }

    // --- Tests 16-19: cycle-2 review findings (NEW-MEDIUM-1, NEW-MEDIUM-2) --------------------------

    @Test
    fun `Test 16 - unarmed Idle to Error tears down exactly once, the failed-start case (NEW-MEDIUM-1)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder(initialState = RecorderState.Idle)
        binder.attach(fake, notificationConfig(id = 5018))
        idle()
        assertFalse(service.isArmedForTest())

        fake.mutableState.value = RecorderState.Error
        idle()

        assertEquals(1, service.teardownCountForTest())
        assertNull(shadowOf(notificationManager).getNotification(5018))
        assertFalse(service.isObserverActiveForTest())
        assertFalse(service.isArmedForTest())

        binder.detach()
        assertEquals(1, service.teardownCountForTest())
    }

    @Test
    fun `Test 17 - attach to a recorder already sitting at Error tears down exactly once, the replay variant (NEW-MEDIUM-1)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder(initialState = RecorderState.Error)

        binder.attach(fake, notificationConfig(id = 5019))
        idle()

        assertEquals(1, service.teardownCountForTest())
        assertNull(shadowOf(notificationManager).getNotification(5019))
        assertFalse(service.isObserverActiveForTest())
    }

    @Test
    fun `Test 18 - an abandoned unarmed Idle is NOT torn down, detach is the escape hatch, and re-attach after teardown works (NEW-MEDIUM-1 residual, NEW-LOW-3)`() {
        val service = newService()
        val binder = bind(service)
        val fake = FakeRecorder(initialState = RecorderState.Idle)

        binder.attach(fake, notificationConfig(id = 5020))
        idle()
        assertNotNull("first attach should self-start", shadowOf(service).nextStartedService)
        idle()
        idle() // repeated idling — still nothing happens without a real state change.

        assertEquals(0, service.teardownCountForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5020))
        assertTrue(service.isObserverActiveForTest())
        assertFalse(service.isArmedForTest())

        binder.detach()
        assertEquals(1, service.teardownCountForTest())
        assertNull(shadowOf(notificationManager).getNotification(5020))

        val fake2 = FakeRecorder()
        binder.attach(fake2, notificationConfig(id = 5021))
        idle()

        assertNotNull(shadowOf(notificationManager).getNotification(5021))
        assertTrue(service.isObserverActiveForTest())
        assertEquals(1, service.teardownCountForTest())
        assertNotNull("re-attach should self-start again", shadowOf(service).nextStartedService)
    }

    @Test
    fun `Test 19 - a superseded observer cannot tear down the current attachment (NEW-MEDIUM-2, generation-guard logic on one looper)`() {
        val service = newService()
        val binder = bind(service)
        val fakeA = FakeRecorder()
        binder.attach(fakeA, notificationConfig(id = 5022))
        idle()
        fakeA.mutableState.value = RecorderState.Recording
        idle()

        val fakeB = FakeRecorder()
        binder.attach(fakeB, notificationConfig(id = 5023))
        idle()

        assertEquals(2, service.attachmentGenerationForTest())

        // A's own flow is now superseded. Push terminal states on it and confirm B is untouched.
        // NOTE (per class KDoc, and restated in the SUMMARY): this proves the generation guard's
        // LOGIC on the stale-observer path on a single Robolectric looper. It is NOT a concurrency
        // stress test — a single-looper suite cannot exercise a genuine cross-thread interleaving.
        fakeA.mutableState.value = RecorderState.Idle
        idle()
        fakeA.mutableState.value = RecorderState.Error
        idle()

        assertEquals(0, service.teardownCountForTest())
        assertTrue(service.isAttachedForTest())
        assertTrue(service.isObserverActiveForTest())
        assertNotNull(shadowOf(notificationManager).getNotification(5023))
    }
}

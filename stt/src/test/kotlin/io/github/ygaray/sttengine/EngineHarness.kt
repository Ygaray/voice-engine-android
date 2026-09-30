package io.github.ygaray.sttengine

import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.internal.AudioSourcePipe
import io.github.ygaray.sttengine.internal.FakeRecognizerHandle
import io.github.ygaray.sttengine.internal.PcmSource
import io.github.ygaray.sttengine.internal.LanguageAvailability
import io.github.ygaray.sttengine.internal.RecognizerHandle
import io.github.ygaray.sttengine.internal.RecognizerHandleFactory
import io.github.ygaray.sttengine.internal.PermissionChecker
import io.github.ygaray.sttengine.internal.SessionLog
import io.github.ygaray.sttengine.internal.SttEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The one harness all five of this plan's suites drive `SttEngineImpl` through.
 *
 * It exists for the same reason `web/tests/errors.test.ts`'s `setup()` does: every suite here needs
 * the same four injected collaborators wired the same way, and re-deriving that per file is how two
 * suites end up quietly testing two different engines.
 *
 * ## Why `UnconfinedTestDispatcher` and not `StandardTestDispatcher`
 *
 * The production dispatcher is `Dispatchers.Main.immediate`, whose defining property is that a
 * `launch` from a thread already on the main looper runs its body **synchronously** up to the first
 * suspension. That is load bearing: the restart sequence (commit → teardown → advance the epoch) has
 * to complete inside the recognizer callback that triggered it, before any other callback can be
 * delivered. `UnconfinedTestDispatcher` is the test dispatcher with that same eager semantics, and
 * because it shares the [TestCoroutineScheduler] every `delay` is still virtual time. A
 * `StandardTestDispatcher` would queue the restart body and let a test observe a half-torn-down
 * engine that the real one can never be in.
 *
 * ## Robolectric is mandatory for any suite that asserts on TEXT
 *
 * `android.os.Bundle` retains nothing on the plain-JVM mockable `android.jar` (measured in plan 04 —
 * see [FakeRecognizerHandle]'s header). This harness also needs a real `Context` for
 * `RecognizerIntents.forRung`. Every suite using it is therefore
 * `@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])` — 35 because Robolectric refuses SDK
 * 36 below Java 21 and this module is Java 17 end to end, and because 35 is the tester's own
 * `SDK_INT`.
 */
// `runTest`, `backgroundScope`, `advanceUntilIdle` and `UnconfinedTestDispatcher` are all still
// marked experimental in coroutines 1.11 despite being the documented way to test coroutine code.
// Opting in here keeps the module's build warning-free.
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineHarness(
    scheduler: TestCoroutineScheduler,
    permissionGranted: Boolean = true,
    languageAvailability: LanguageAvailability = LanguageAvailability.INSTALLED,
    languageAvailabilityOverrides: Map<String, LanguageAvailability> = emptyMap(),
    private val factoryThrows: Boolean = false,
    config: SttConfig = SttConfig(),
    log: SessionLog = SessionLog(),
    supportsSegmented: Boolean = false,
    supportsRawAudio: Boolean = false,
    private val audioPipeOpenFails: Boolean = false,
    deviceLocale: Locale = Locale.US,
    sdkInt: Int = 35,
) {

    /** The seam the engine actually talks to. Fire callbacks into it to drive the engine. */
    val fake: FakeRecognizerHandle = FakeRecognizerHandle()

    /** How many times the engine asked the factory for a recognizer. */
    var handleCreateCount: Int = 0
        private set

    /** Eager, virtual-time dispatcher — see the class KDoc. */
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(scheduler)

    /**
     * Every app-owned audio pipe the engine opened, oldest first. Empty on any rung but (c).
     *
     * The pipes are real [AudioSourcePipe]s over a real `ParcelFileDescriptor` pipe — only the PCM
     * source is faked, because a unit test must never open the host's microphone and because an
     * `AudioRecord` is not something Robolectric can honestly stand in for. That keeps the pipe's
     * own resource discipline (which is rung (c)'s entire risk) exercised by these tests rather than
     * stubbed past.
     */
    val audioPipes: MutableList<AudioSourcePipe> = mutableListOf()

    private val handle: RecognizerHandle =
        PreflightHandle(fake, languageAvailability, languageAvailabilityOverrides)

    val engine: SttEngineImpl = SttEngineImpl(
        appContext = ApplicationProvider.getApplicationContext(),
        config = config,
        handleFactory = RecognizerHandleFactory {
            handleCreateCount += 1
            if (factoryThrows) {
                throw UnsupportedOperationException("On-device recognition is not available")
            }
            handle
        },
        permissions = PermissionChecker { permissionGranted },
        log = log,
        supportsSegmented = supportsSegmented,
        supportsRawAudio = supportsRawAudio,
        audioPipeFactory = {
            AudioSourcePipe(
                sourceFactory = {
                    if (audioPipeOpenFails) {
                        error("the microphone is held by another app")
                    }
                    SilentPcmSource()
                },
            ).also { audioPipes += it }
        },
        mainDispatcher = dispatcher,
        deviceLocale = deviceLocale,
        sdkInt = sdkInt,
    )

    // --- recorded output ------------------------------------------------------------------------

    val finals: MutableList<FinalSegment> = mutableListOf()
    val errors: MutableList<SttError> = mutableListOf()
    val states: MutableList<EngineState> = mutableListOf()
    val levels: MutableList<Float> = mutableListOf()
    val events: MutableList<SttEvent> = mutableListOf()

    /**
     * Attach one collector per property. Pass `runTest`'s `backgroundScope` so the collectors are
     * torn down with the test rather than counting as unfinished coroutines.
     *
     * Note that [finals] and [errors] are channel-backed and single-consumer by construction, so a
     * test must not collect them a second time itself — it would steal from this collector.
     */
    fun collectInto(scope: CoroutineScope) {
        scope.launch(dispatcher) { engine.finals.collect { finals += it } }
        scope.launch(dispatcher) { engine.errors.collect { errors += it } }
        scope.launch(dispatcher) { engine.state.collect { states += it } }
        scope.launch(dispatcher) { engine.level.collect { levels += it } }
        scope.launch(dispatcher) { engine.events.collect { events += it } }
    }

    /** The listener registered for session [index], oldest first — index 0 is the first session's. */
    fun listenerFor(index: Int) = fake.registeredListeners[index]

    /**
     * Drive the engine from `idle` to `listening` — the shared steady state every behavioural test
     * starts from, the same job `reachListening()` does in `web/tests/errors.test.ts`.
     *
     * `start()` deliberately does not return until the engine is listening or has failed terminally
     * (D-10), so it has to be running concurrently when the recognizer's `onReadyForSpeech` is
     * fired. The eager dispatcher makes that a two-liner: the launch runs synchronously up to
     * `start()`'s internal await, which is exactly where the real engine would be.
     */
    suspend fun reachListening(withSpeech: Boolean = true): Unit = coroutineScope {
        val started = launch(dispatcher) { engine.start() }
        fake.reachListening(withSpeech = withSpeech)
        started.join()
    }
}

/**
 * A [PcmSource] that is immediately at end of stream.
 *
 * The engine's tests are about the *ladder* and the *lifecycle*, not about audio content, and the
 * recognizer here is a fake that never reads the pipe anyway. Ending at once means the pump thread
 * exits on its first read instead of writing megabytes into a Robolectric-backed pipe for the
 * duration of a suite.
 */
internal class SilentPcmSource : PcmSource {
    var stopCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    override fun read(buffer: ByteArray): Int = -1

    override fun stop() {
        stopCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}

/**
 * [FakeRecognizerHandle] plus a canned answer to the language preflight.
 *
 * Kotlin interface delegation rather than a subclass: `FakeRecognizerHandle` is deliberately not
 * `open` (plan 04 owns it and this plan does not modify it), and delegation gives the fake's full
 * recording behaviour with exactly one member overridden.
 *
 * The override is the whole point. The tester device has `en-US` **installed**
 * (`05-PROBE-REPORT.json` → `part1.languageSupport.installedOnDeviceLanguages`), so the
 * `no-offline-model` preflight branch can never fire naturally on the only device this phase runs
 * on. Forcing the answer here is what makes that D-18 row genuinely exercised rather than shipped
 * unproven.
 */
internal class PreflightHandle(
    private val delegate: FakeRecognizerHandle,
    private val availability: LanguageAvailability,
    private val availabilityOverrides: Map<String, LanguageAvailability> = emptyMap(),
) : RecognizerHandle by delegate {

    override suspend fun languageAvailability(language: String): LanguageAvailability =
        availabilityOverrides[language] ?: availability

    // MUST be overridden here, even though its body is byte-identical to RecognizerHandle's
    // interface default (languages.associateWith { languageAvailability(it) }). `PreflightHandle`
    // uses Kotlin CLASS DELEGATION ("RecognizerHandle by delegate") — any member NOT overridden on
    // this class is auto-forwarded straight to `delegate` (FakeRecognizerHandle), bypassing this
    // class's own `languageAvailability(String)` override entirely and silently answering every
    // language UNDETERMINED (delegate's inherited default), which never fails a dual-language
    // preflight and hangs `start()` forever awaiting a session that a test never drives. Overriding
    // it here makes `languageAvailability(it)` resolve virtually to THIS class's override, composing
    // through `availabilityOverrides` — proven by a hang-reproducing PreflightTest run before this
    // override was added.
    override suspend fun languageAvailability(languages: List<String>): Map<String, LanguageAvailability> =
        languages.associateWith { languageAvailability(it) }
}

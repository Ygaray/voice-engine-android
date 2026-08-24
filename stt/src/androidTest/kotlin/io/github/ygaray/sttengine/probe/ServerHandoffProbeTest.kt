package io.github.ygaray.sttengine.probe

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.ygaray.sttengine.Backend
import io.github.ygaray.sttengine.BackendPreference
import io.github.ygaray.sttengine.EngineState
import io.github.ygaray.sttengine.SttConfig
import io.github.ygaray.sttengine.internal.BackendSelector
import io.github.ygaray.sttengine.internal.ContextPermissionChecker
import io.github.ygaray.sttengine.internal.PlatformRecognizerHandleFactory
import io.github.ygaray.sttengine.internal.WebSocketFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The D-03/D-05 on-device handoff probe — measures TWO real, on-hardware quantities this phase's
 * own decisions explicitly refuse to derive from the codebase alone:
 *
 *  1. **connectRttMs** — the real try-server connect round trip (WS-upgrade through the server's
 *     own `ready` frame), the quantity [BackendSelector]'s `tryServerTimeoutMs` bounded window is
 *     tuned from (D-05: *"the exact value set by a measured phone-on-tailnet round trip... not
 *     derivable from the codebase"*).
 *  2. **handoffGapMs** — the real wall-clock gap between a mid-session server drop and the native
 *     `SpeechRecognizer` fallback reaching `Listening` again (SEL-04, D-03: *"measure the actual
 *     sub-second gap on the tester device"*).
 *
 * Like the sibling probes in this package ([RecognizerCapabilityProbeTest],
 * [RecognizerBehaviourProbeTest]) this is a **reporting instrument, not a test of the device or the
 * server** — it asserts nothing about either measurement's VALUE. A slow connect or a long handoff
 * gap is a legitimate, useful result to record, not a failure; the harness fails loudly only when a
 * measurement could not be TAKEN at all (see below), never on a negative-but-real answer.
 *
 * ## Drives a REAL [BackendSelector], not a fake
 *
 * Constructed exactly like [io.github.ygaray.sttengine.createSttEngine]'s server-eligible branch —
 * a real `OkHttpClient`, [PlatformRecognizerHandleFactory], [ContextPermissionChecker] — with ONE
 * addition: the [WebSocketFactory] is wrapped in a thin pass-through that stashes the real
 * [WebSocket] reference [BackendSelector]/its `ServerBackend` opens, so THIS TEST can later force a
 * genuine transport-level drop via [WebSocket.cancel] — OkHttp's own "immediately and violently
 * release resources... no close handshake" primitive, exactly what a real network failure looks
 * like from the client's side. No production code is modified and no debug-only seam is added to
 * make this measurable; the test only observes the real `WebSocket` the real factory already
 * constructs.
 *
 * ## Never a hardcoded tailnet address (CLAUDE.md invariant 5, T-09-08)
 *
 * The target `wss://` URL is read ONLY from the `-e sttServerUrl <url>` instrumentation-runner
 * argument. This source file ships in the public JitPack mirror, so a literal tailnet hostname/IP
 * here would leak a private network detail to every consumer of the published library. Absent that
 * argument the probe fails LOUDLY with a clear message naming the missing flag — it does not
 * silently skip (the one place this probe DOES assert; see [measureConnectRttAndHandoffGap]).
 *
 * ## Running it
 *
 * ```
 * cd android && ANDROID_SERIAL=<tester-ip>:<port> ./gradlew :stt:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.ygaray.sttengine.probe.ServerHandoffProbeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.sttServerUrl=wss://example.invalid:8444/ \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 * ```
 *
 * Pull `<externalFilesDir>/server-handoff-probe-report.json` IMMEDIATELY afterwards —
 * `connectedAndroidTest` wipes app data on the next instrumented run, taking the report with it.
 * Target the TESTER device explicitly with `-s <ip>:<port>` (never the personal phone), and never
 * toggle airplane mode to simulate offline — see `context/devices/common.md`.
 */
@RunWith(AndroidJUnit4::class)
class ServerHandoffProbeTest {

    /** D-11: the HOST requests `RECORD_AUDIO`. An unattended instrumented run has no host. */
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun measureConnectRttAndHandoffGap() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext.applicationContext
            ?: instrumentation.targetContext
        val serverUrl = InstrumentationRegistry.getArguments().getString(SERVER_URL_ARG)

        if (serverUrl.isNullOrBlank()) {
            // The one place this probe asserts: a missing argument means NO measurement can even be
            // attempted, which is categorically different from a real negative connect/handoff
            // result — that must fail loudly, never silently no-op (acceptance criterion).
            fail(
                "Missing required instrumentation-runner argument '-e $SERVER_URL_ARG <wss://...>'. " +
                    "This probe measures the REAL Phase 7 server over the tailnet (D-03/D-05) and " +
                    "must never hardcode a tailnet hostname in android/ source (CLAUDE.md invariant " +
                    "5). Re-run with, e.g.: " +
                    "-Pandroid.testInstrumentationRunnerArguments.$SERVER_URL_ARG=" +
                    "wss://<host>.ts.net:8444/",
            )
            return@runBlocking
        }

        val trials = JSONArray()
        val connectRttSamples = mutableListOf<Long>()
        val handoffGapSamples = mutableListOf<Long>()

        repeat(TRIAL_COUNT) { index ->
            val trial = runTrial(context, serverUrl, index)
            trials.put(trial)
            trial.optLong("connectRttMs", -1L).takeIf { it >= 0 }?.let { connectRttSamples += it }
            trial.optLong("handoffGapMs", -1L).takeIf { it >= 0 }?.let { handoffGapSamples += it }
        }

        val report = JSONObject()
            .put("capturedAt", Instant.now().toString())
            .put("device", deviceIdentity())
            .put("trialCount", TRIAL_COUNT)
            .put("trials", trials)
            .put("connectRttMsStats", ProbeText.statsOf(connectRttSamples))
            .put("handoffGapMsStats", ProbeText.statsOf(handoffGapSamples))
        writeReport(context, report)
    }

    /**
     * One full connect→drop→handoff cycle, budget-bounded and independently isolated so one bad
     * trial (e.g. a single flaky tailnet round trip) never costs the other two — mirrors
     * [RecognizerBehaviourProbeTest]'s own `measure {}` per-measurement isolation.
     */
    private suspend fun runTrial(context: Context, serverUrl: String, index: Int): JSONObject {
        val trial = JSONObject().put("trial", index)
        return try {
            withTimeoutOrNull(TRIAL_BUDGET_MS) { runTrialBody(context, serverUrl, trial) }
                ?: trial.put("measurementError", "trial exceeded its ${TRIAL_BUDGET_MS}ms budget")
        } catch (t: Throwable) {
            trial.put("measurementError", "${t.javaClass.name}: ${t.message}")
        }
    }

    private suspend fun runTrialBody(context: Context, serverUrl: String, trial: JSONObject): JSONObject {
        val liveSocket = AtomicReference<WebSocket?>(null)
        val httpClient = OkHttpClient()
        // Thin pass-through: opens the REAL WebSocket exactly like OkHttpWebSocketFactory does,
        // and stashes the reference so this test can force a real transport-level drop below.
        val recordingFactory = WebSocketFactory { url, listener ->
            httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
                .also { liveSocket.set(it) }
        }

        val selector = BackendSelector(
            appContext = context,
            config = SttConfig(prefer = BackendPreference.Auto, serverUrl = serverUrl),
            handleFactory = PlatformRecognizerHandleFactory,
            permissions = ContextPermissionChecker(context),
            webSocketFactory = recordingFactory,
        )

        try {
            // --- Measurement 1: try-server connect RTT (D-05) ---
            // start() is documented to complete ONLY once EngineState.Listening is reached OR on a
            // terminal error — so timing the call itself IS timing "invocation to Listening".
            val connectStartNs = System.nanoTime()
            selector.start()
            val connectRttMs = (System.nanoTime() - connectStartNs) / NANOS_PER_MS
            val reachedServer = selector.state.value == EngineState.Listening &&
                selector.backend.value == Backend.Server
            trial.put("connectRttMs", connectRttMs)
            trial.put("reachedServerListening", reachedServer)
            trial.put("backendAfterConnect", selector.backend.value.name)
            trial.put("stateAfterConnect", selector.state.value.name)

            if (!reachedServer) {
                trial.put(
                    "handoffSkippedReason",
                    "session did not reach Listening on the Server backend — a mid-session " +
                        "handoff cannot be measured without an established server session first " +
                        "(a legitimate negative connect result, not an instrument failure)",
                )
                return trial
            }

            // --- Measurement 2: mid-session handoff gap (SEL-04) ---
            val socket = liveSocket.get()
            if (socket == null) {
                trial.put(
                    "handoffSkippedReason",
                    "no live WebSocket reference was captured — cannot force a transport drop",
                )
                return trial
            }

            val dropStartNs = System.nanoTime()
            // Violently release the real transport, no close handshake — the same signal a genuine
            // network failure produces client-side, exercising ServerBackend's real
            // onFailure/onClosed path and BackendSelector's real one-way handoff (never a fake).
            socket.cancel()

            var handedOff = false
            val deadlineNs = System.nanoTime() + HANDOFF_WAIT_TIMEOUT_MS * NANOS_PER_MS
            while (System.nanoTime() < deadlineNs) {
                if (selector.backend.value == Backend.Native) {
                    handedOff = true
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
            val handoffGapMs = (System.nanoTime() - dropStartNs) / NANOS_PER_MS
            trial.put("handoffCompleted", handedOff)
            if (handedOff) {
                trial.put("handoffGapMs", handoffGapMs)
            } else {
                trial.put(
                    "handoffSkippedReason",
                    "backend did not flip back to Native within ${HANDOFF_WAIT_TIMEOUT_MS}ms of " +
                        "forcing the transport drop",
                )
            }
            trial.put("stateAfterHandoffWait", selector.state.value.name)
        } finally {
            runCatching { selector.stop() }
        }
        return trial
    }

    private fun deviceIdentity(): JSONObject = JSONObject().apply {
        put("MANUFACTURER", Build.MANUFACTURER)
        put("MODEL", Build.MODEL)
        put("DEVICE", Build.DEVICE)
        put("FINGERPRINT", Build.FINGERPRINT)
        put("SDK_INT", Build.VERSION.SDK_INT)
        // Both tester phones are S22 Ultras — MODEL alone cannot disambiguate them (the documented
        // naming footgun). ro.serialno can, so the artifact carries its own provenance.
        put("ro.serialno", systemProperty("ro.serialno"))
    }

    private fun systemProperty(key: String): Any = try {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", key))
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        value.ifEmpty { JSONObject.NULL }
    } catch (t: Throwable) {
        "unavailable: ${t.javaClass.simpleName}: ${t.message}"
    }

    private fun writeReport(context: Context, report: JSONObject) {
        val dir = requireNotNull(context.getExternalFilesDir(null)) {
            "external files dir unavailable — cannot write the probe report"
        }
        File(dir, REPORT_FILE_NAME).writeText(report.toString(2))
    }

    private companion object {
        const val SERVER_URL_ARG = "sttServerUrl"
        const val REPORT_FILE_NAME = "server-handoff-probe-report.json"
        const val TRIAL_COUNT = 3
        const val NANOS_PER_MS = 1_000_000L
        const val HANDOFF_WAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 20L
        const val TRIAL_BUDGET_MS = 30_000L
    }
}

package io.github.ygaray.sttengine.probe

import android.Manifest
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The bilingual language-switch probe (plan 149-02) — a **reporting instrument, not a test of the
 * device**, following the exact doctrine [RecognizerCapabilityProbeTest] and
 * [RecognizerBehaviourProbeTest] already established.
 *
 * It answers whether this Samsung/Google on-device recognizer honours
 * `EXTRA_ENABLE_LANGUAGE_SWITCH` / fires `onLanguageDetection(Bundle)`. Because it is an
 * instrument, it **asserts nothing about the device's answer.** A negative/no-effect result is
 * valid and must be recorded, not failed — the extra's own documentation admits "depending on the
 * recognizer implementation, this value may have no effect." The test fails ONLY when a
 * measurement could not be *completed* (a precondition callback timeout, or the live session never
 * reaching a terminal callback at all) — never on the device's actual (negative) answer.
 *
 * This is a **new, independently-named class** per CONTEXT.md D-01's correction (RESEARCH.md
 * "Correction to CONTEXT.md D-01"): `RecognizerCapabilityProbeTest`'s `part1`/`part2` envelope is
 * the closed v1.0 spike's own evidence trail and is never touched here. This probe writes its own
 * file under its own top-level key.
 *
 * Three measurements, in order, into one JSON report:
 *  1. **Precondition** — is `es-US` actually *installed* (not merely supported)? If not,
 *     `triggerModelDownload` is called and `checkRecognitionSupport` is polled on a bounded
 *     schedule to see whether the download actually *completes* — the existing part-1 probe only
 *     ever proved the call was *reachable*, never that a download finished; that gap is new scope
 *     this probe closes.
 *  2. **Live session** — the committed [BilingualStimulus] WAV is fed into a real `startListening`
 *     session via the exact file-fed-audio mechanism [RecognizerBehaviourProbeTest.measureFileFedAudio]
 *     already proved on this exact SM-S908U at 99.4% precision/recall, extended with
 *     `EXTRA_ENABLE_LANGUAGE_SWITCH` / `EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES` and a
 *     `RecognitionListener` overriding `onLanguageDetection` — neither existing probe implements
 *     that callback.
 *  3. **Outcome** — the verbatim final recognized text, for a human reviewer's manual read (D-03).
 *     No pass threshold, no verdict field: the go/no-go is Yahir's call over these findings.
 *
 * `LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL` (value 3) is watched for specifically: it is the value
 * that separates "the Spanish pack is missing" from "this recognizer ignores the extra", and those
 * two findings lead to opposite recommendations.
 *
 * Output: `<externalFilesDir>/language-switch-probe-report.json`, single top-level key
 * `languageSwitch`. Pull it IMMEDIATELY — `connectedAndroidTest` wipes app data on the NEXT
 * instrumented run.
 *
 * ### Running it
 * ```
 * cd android && ANDROID_SERIAL=<tester serial, read fresh from ~/.config/test-device.env> \
 *   ./gradlew :stt:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.ygaray.sttengine.probe.LanguageSwitchProbeTest \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 * adb -s <tester> pull \
 *   /sdcard/Android/data/io.github.ygaray.sttengine.test/files/language-switch-probe-report.json .
 * ```
 * Both flags are mandatory, same reasoning as every other probe in this package: `--tests` is a
 * Gradle JVM `Test` option AGP's `connectedDebugAndroidTest` rejects outright, and without
 * `leaveApksInstalledAfterRun` the task uninstalls the test APK during its own teardown, taking the
 * app's external files dir — and the report this run just wrote — with it.
 */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchProbeTest {

    /** D-11: the HOST requests `RECORD_AUDIO`. An unattended instrumented run has no host. */
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun probeLanguageSwitch() {
        val report = JSONObject()
        var incompleteMeasurement: String? = null
        var stimulus: File? = null
        var recognizer: SpeechRecognizer? = null

        try {
            report.put("capturedAt", Instant.now().toString())
            report.put("device", deviceIdentity())

            recognizer = createRecognizer()
            val live = recognizer

            // --- 1. Precondition: is es-US actually INSTALLED, not merely supported? ---
            val precondition = checkPrecondition(live)
            report.put("requestedLanguage", ES_US)
            report.put("installedOnDeviceLanguages", precondition.installed)
            report.put("supportedOnDeviceLanguages", precondition.supported)
            report.put("pendingOnDeviceLanguages", precondition.pending)
            report.put("esPackInstalled", precondition.esInstalled)
            if (precondition.timedOut) {
                incompleteMeasurement = "checkRecognitionSupport did not call back within " +
                    "$SUPPORT_TIMEOUT_SECONDS s for es-US — the installed-pack precondition is " +
                    "unmeasured."
            }

            val downloadOutcome = if (!precondition.esInstalled && !precondition.timedOut) {
                triggerAndPollDownload(live)
            } else {
                JSONObject()
                    .put("attempted", false)
                    .put(
                        "reason",
                        if (precondition.esInstalled) {
                            "es-US already installed"
                        } else {
                            "precondition check incomplete — see checkRecognitionSupport timeout above"
                        },
                    )
            }
            report.put("triggerModelDownloadOutcome", downloadOutcome)

            // --- 2. Live session: feed the bilingual stimulus with the switch extra set ---
            stimulus = copyStimulusToCache()
            report.put("stimulus", stimulusMetadata(stimulus))

            val session = measureLanguageSwitchSession(live, stimulus)
            report.put("extrasSet", session.extrasSet)
            report.put("languageDetectionFired", session.languageDetectionFired)
            report.put("languageDetectionEvents", session.languageDetectionEvents)
            report.put("finalText", session.finalText)
            report.put("sessionCompleted", session.sessionCompleted)
            // --- 3. Outcome: expected sequence alongside the actual text, for D-03's manual read ---
            report.put("expectedUtteranceSequence", expectedUtteranceSequenceJson())
            report.put("session", session.sessionJson)

            if (!session.sessionCompleted) {
                incompleteMeasurement = incompleteMeasurement ?: (
                    "the live startListening session never reached a terminal callback within " +
                        "budget — languageSwitch behaviour is unmeasured, not merely negative."
                    )
            }
        } finally {
            recognizer?.let { live ->
                instrumentation.runOnMainSync {
                    runCatching { live.cancel() }
                    runCatching { live.destroy() }
                }
            }
            stimulus?.delete()
            writeReport(report)
        }

        incompleteMeasurement?.let { fail(it) }
    }

    // ------------------------------------------------------------------------------------------
    // 1. Precondition — checkRecognitionSupport(es-US) + triggerModelDownload completion polling
    // ------------------------------------------------------------------------------------------

    private data class Precondition(
        val installed: JSONArray,
        val supported: JSONArray,
        val pending: JSONArray,
        val esInstalled: Boolean,
        val timedOut: Boolean,
    )

    private fun checkPrecondition(recognizer: SpeechRecognizer): Precondition {
        val intent = supportIntent()
        val result = AtomicReference<RecognitionSupport?>(null)
        val errorCode = AtomicInteger(NO_ERROR)
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            recognizer.checkRecognitionSupport(
                intent,
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        result.set(recognitionSupport)
                        latch.countDown()
                    }

                    override fun onError(error: Int) {
                        errorCode.set(error)
                        latch.countDown()
                    }
                },
            )
            val answered = latch.await(SUPPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val support = result.get()
            return if (!answered) {
                Precondition(JSONArray(), JSONArray(), JSONArray(), esInstalled = false, timedOut = true)
            } else if (support == null) {
                // Answered with an error, not a timeout — a complete (negative) measurement.
                Precondition(JSONArray(), JSONArray(), JSONArray(), esInstalled = false, timedOut = false)
            } else {
                val installed = support.installedOnDeviceLanguages
                Precondition(
                    installed = JSONArray(installed),
                    supported = JSONArray(support.supportedOnDeviceLanguages),
                    pending = JSONArray(support.pendingOnDeviceLanguages),
                    esInstalled = installed.contains(ES_US),
                    timedOut = false,
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * New scope beyond the part-1 probe: that probe only proved `triggerModelDownload` was
     * *reachable*. This polls `checkRecognitionSupport` on a bounded schedule to record whether a
     * triggered download actually *completes* — an unfinished download is a recorded precondition
     * state, not a failure (RESEARCH.md § native probe, point 2).
     */
    private fun triggerAndPollDownload(recognizer: SpeechRecognizer): JSONObject {
        val outcome = JSONObject().put("attempted", true)
        try {
            recognizer.triggerModelDownload(supportIntent())
            outcome.put("triggerThrew", false)
        } catch (t: Throwable) {
            outcome.put("triggerThrew", true)
            outcome.put("triggerThrowable", "${t.javaClass.name}: ${t.message}")
            outcome.put("installedAfterPolling", false)
            outcome.put("pollAttempts", 0)
            outcome.put("timedOut", true)
            return outcome
        }

        var installed = false
        var attempts = 0
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(DOWNLOAD_POLL_BUDGET_SECONDS)
        while (System.nanoTime() < deadlineNs) {
            Thread.sleep(DOWNLOAD_POLL_INTERVAL_MS)
            attempts++
            val polled = checkPrecondition(recognizer)
            if (polled.esInstalled) {
                installed = true
                break
            }
        }
        outcome.put("installedAfterPolling", installed)
        outcome.put("pollAttempts", attempts)
        outcome.put("timedOut", !installed)
        outcome.put(
            "pollBudgetSeconds",
            "$DOWNLOAD_POLL_BUDGET_SECONDS s at ${DOWNLOAD_POLL_INTERVAL_MS}ms intervals — a spike " +
                "time-box, not evidence the download would never finish given more time",
        )
        return outcome
    }

    private fun supportIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, ES_US)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    // ------------------------------------------------------------------------------------------
    // 2. Live session — file-fed bilingual stimulus + EXTRA_ENABLE_LANGUAGE_SWITCH
    // ------------------------------------------------------------------------------------------

    private data class SessionResult(
        val extrasSet: JSONObject,
        val languageDetectionFired: Boolean,
        val languageDetectionEvents: JSONArray,
        val finalText: String,
        val sessionCompleted: Boolean,
        val sessionJson: JSONObject,
    )

    private fun measureLanguageSwitchSession(
        recognizer: SpeechRecognizer,
        stimulus: File,
    ): SessionResult {
        val pcm = ProbeWav.parse(stimulus)
        val recorder = LanguageSwitchRecorder()
        val stopPump = AtomicBoolean(false)
        val pumpError = AtomicReference<String?>(null)
        val fedFully = AtomicBoolean(false)

        val extrasSet = JSONObject()
            .put("EXTRA_AUDIO_SOURCE", "ParcelFileDescriptor pipe (file-fed, real-time paced)")
            .put("EXTRA_AUDIO_SOURCE_CHANNEL_COUNT", 1)
            .put("EXTRA_AUDIO_SOURCE_ENCODING", "ENCODING_PCM_16BIT")
            .put("EXTRA_AUDIO_SOURCE_SAMPLING_RATE", BilingualStimulus.SAMPLE_RATE_HZ)
            .put("EXTRA_SEGMENTED_SESSION", "EXTRA_AUDIO_SOURCE")
            .put("EXTRA_ENABLE_LANGUAGE_SWITCH", "LANGUAGE_SWITCH_BALANCED (String extra, not boolean)")
            .put("EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES", JSONArray(listOf(EN_US, ES_US)))

        var pipe: Array<ParcelFileDescriptor>? = null
        var pump: Thread? = null

        try {
            pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            val bytesPerSecond = pcm.sampleRate.toLong() * pcm.channels * (pcm.bitsPerSample / 8)

            val out = FileOutputStream(writeEnd.fileDescriptor)
            pump = Thread {
                try {
                    RandomAccessFile(stimulus, "r").use { raf ->
                        raf.seek(pcm.dataOffset)
                        val buffer = ByteArray(FEED_CHUNK_BYTES)
                        val startNs = System.nanoTime()
                        var sent = 0L
                        while (sent < pcm.dataLength && !stopPump.get()) {
                            val want = minOf(buffer.size.toLong(), pcm.dataLength - sent).toInt()
                            val read = raf.read(buffer, 0, want)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            out.flush()
                            sent += read
                            // Real-time pacing is deliberate, not incidental: a recognizer whose
                            // endpointer has any wall-clock component would segment nonsensically
                            // on a firehose, silently invalidating the whole measurement.
                            val targetMs = sent * 1000 / bytesPerSecond
                            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                            if (targetMs > elapsedMs) Thread.sleep(targetMs - elapsedMs)
                        }
                        fedFully.set(sent >= pcm.dataLength)
                    }
                } catch (t: Throwable) {
                    pumpError.set("${t.javaClass.simpleName}: ${t.message}")
                }
            }.apply {
                isDaemon = true
                name = "language-switch-file-pump"
                start()
            }

            instrumentation.runOnMainSync {
                recognizer.setRecognitionListener(recorder.listener)
                recognizer.startListening(languageSwitchIntent(readEnd))
            }

            pump.join(pcm.durationMs + FILE_FEED_SLACK_MS)
            stopPump.set(true)
            runCatching { writeEnd.close() }

            var completed = recorder.terminal.await(POST_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                completed = recorder.terminal.await(EXTRA_TERMINAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }

            return SessionResult(
                extrasSet = extrasSet,
                languageDetectionFired = recorder.languageDetectionFired,
                languageDetectionEvents = JSONArray(recorder.languageDetectionEvents),
                finalText = recorder.recognizedText(),
                sessionCompleted = completed,
                sessionJson = JSONObject()
                    .put("fedFully", fedFully.get())
                    .put("pumpError", pumpError.get() ?: JSONObject.NULL)
                    .put("terminalKind", recorder.terminalKind ?: JSONObject.NULL)
                    .put("partialCount", recorder.partials.size)
                    .put("errorCodes", JSONArray(recorder.errors))
                    .put("partials", JSONArray(recorder.partials.takeLast(MAX_PARTIALS_LOGGED))),
            )
        } finally {
            stopPump.set(true)
            pipe?.forEach { descriptor -> runCatching { descriptor.close() } }
            pump?.join(PUMP_JOIN_MS)
        }
    }

    private fun languageSwitchIntent(readEnd: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, EN_US)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                BilingualStimulus.SAMPLE_RATE_HZ,
            )
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // The single most important extra under test. A STRING value, not a boolean — matches
            // this codebase's EXTRA_SEGMENTED_SESSION convention above. Implicitly enables
            // EXTRA_ENABLE_LANGUAGE_DETECTION per the extra's own documentation.
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            )
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                arrayListOf(EN_US, ES_US),
            )
        }

    // ------------------------------------------------------------------------------------------
    // Recognizer plumbing, stimulus, reporting
    // ------------------------------------------------------------------------------------------

    private fun createRecognizer(): SpeechRecognizer {
        val created = AtomicReference<SpeechRecognizer?>(null)
        val failure = AtomicReference<String?>(null)
        instrumentation.runOnMainSync {
            try {
                created.set(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
            } catch (e: UnsupportedOperationException) {
                failure.set(e.message ?: e.toString())
            }
        }
        return created.get() ?: error("on-device recognizer unavailable: ${failure.get()}")
    }

    /** The asset lives in the androidTest APK, read from the instrumentation context. */
    private fun copyStimulusToCache(): File {
        val destination = File(context.cacheDir, BilingualStimulus.ASSET_NAME)
        instrumentation.context.assets.open(BilingualStimulus.ASSET_NAME).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun stimulusMetadata(file: File): JSONObject {
        val pcm = ProbeWav.parse(file)
        return JSONObject()
            .put("asset", BilingualStimulus.ASSET_NAME)
            .put("bytes", file.length())
            .put("sampleRate", pcm.sampleRate)
            .put("channels", pcm.channels)
            .put("bitsPerSample", pcm.bitsPerSample)
            .put("durationMs", pcm.durationMs)
            .put("utteranceCount", BilingualStimulus.UTTERANCES.size)
    }

    private fun expectedUtteranceSequenceJson(): JSONArray = JSONArray(
        BilingualStimulus.UTTERANCES.map {
            JSONObject().put("text", it.text).put("languageTag", it.languageTag)
        },
    )

    private fun deviceIdentity(): JSONObject = JSONObject().apply {
        put("MANUFACTURER", Build.MANUFACTURER)
        put("MODEL", Build.MODEL)
        put("DEVICE", Build.DEVICE)
        put("FINGERPRINT", Build.FINGERPRINT)
        put("SDK_INT", Build.VERSION.SDK_INT)
        put("SECURITY_PATCH", Build.VERSION.SECURITY_PATCH)
    }

    /** Writes the envelope under a NEW top-level key — never `part1`/`part2`. */
    private fun writeReport(report: JSONObject) {
        val envelope = JSONObject().put("languageSwitch", report)
        val dir = requireNotNull(context.getExternalFilesDir(null)) {
            "external files dir unavailable — cannot write the probe report"
        }
        File(dir, REPORT_FILE_NAME).writeText(envelope.toString(2))
    }

    /**
     * Deliberately NOT [ProbeRecorder]: that class's anonymous [RecognitionListener] never
     * overrides `onLanguageDetection` (a default method), so reusing it here would silently
     * swallow the one callback this probe exists to observe.
     */
    private class LanguageSwitchRecorder {
        private val startedAtMs: Long = SystemClock.elapsedRealtime()

        val partials: MutableList<String> = mutableListOf()
        val finals: MutableList<String> = mutableListOf()
        val errors: MutableList<Int> = mutableListOf()
        val languageDetectionEvents: MutableList<JSONObject> = mutableListOf()
        var languageDetectionFired: Boolean = false
        var terminalKind: String? = null
        val terminal: CountDownLatch = CountDownLatch(1)

        val listener: RecognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                errors += error
                markTerminal("onError($error)")
            }

            override fun onResults(results: Bundle?) {
                finals += firstResult(results)
                markTerminal("onResults")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partials += firstResult(partialResults)
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                finals += firstResult(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                markTerminal("onEndOfSegmentedSession")
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onLanguageDetection(results: Bundle) {
                languageDetectionFired = true
                languageDetectionEvents += JSONObject()
                    .put(
                        "detectedLanguage",
                        results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: JSONObject.NULL,
                    )
                    .put(
                        "confidenceLevel",
                        results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, -1),
                    )
                    .put(
                        "switchResult",
                        results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, -1),
                    )
                    .put("elapsedMs", SystemClock.elapsedRealtime() - startedAtMs)
            }
        }

        private fun markTerminal(kind: String) {
            if (terminalKind == null) terminalKind = kind
            terminal.countDown()
        }

        private fun firstResult(bundle: Bundle?): String =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

        fun recognizedText(): String = finals.filter { it.isNotBlank() }.joinToString(" ")
    }

    private companion object {
        const val EN_US = "en-US"
        const val ES_US = "es-US"
        const val NO_ERROR = Int.MIN_VALUE
        const val SUPPORT_TIMEOUT_SECONDS = 30L

        // A spike time-box, not evidence the download would never finish given more time.
        const val DOWNLOAD_POLL_BUDGET_SECONDS = 90L
        const val DOWNLOAD_POLL_INTERVAL_MS = 10_000L

        const val FEED_CHUNK_BYTES = 3_200 // 100 ms at 16 kHz mono 16-bit
        const val FILE_FEED_SLACK_MS = 20_000L
        const val POST_CLOSE_TIMEOUT_MS = 20_000L
        const val EXTRA_TERMINAL_TIMEOUT_MS = 15_000L
        const val PUMP_JOIN_MS = 3_000L
        const val MAX_PARTIALS_LOGGED = 30

        const val REPORT_FILE_NAME = "language-switch-probe-report.json"
    }
}

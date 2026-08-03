package io.github.ygaray.sttengine.probe

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The D-04 capability probe, part 1 — **a reporting instrument, not a test of the device.**
 *
 * It answers the phase's central unknown with evidence instead of documentation: does *this*
 * Samsung device actually honour the on-device / segmented-session recognition APIs? Plans 03, 06
 * and 09 read the committed report and branch on it; no plan may assume the positive branch.
 *
 * Because it is an instrument, it **asserts nothing about the device's answers.** A negative answer
 * (no on-device recognizer, no installed `en-US` pack) is a valid and useful result that must be
 * recorded, not failed. The test fails only when a measurement could not be *completed* — the
 * language-support callback timing out is the one realistic case — and even then it writes
 * everything it did collect first.
 *
 * Needs no speech, so it runs unattended over wireless adb. Leave the network ON: the language-pack
 * inventory is the precondition D-26 depends on.
 *
 * Output: `<externalFilesDir>/probe-report.json`, already shaped as the committed evidence file
 * (`part1` = these measurements, `part2` = null, reserved for plan 03's behavioural probe). Pull it
 * IMMEDIATELY after the run — `connectedAndroidTest` wipes app data on the next instrumented run.
 */
@RunWith(AndroidJUnit4::class)
class RecognizerCapabilityProbeTest {

    /**
     * D-11 says the HOST requests `RECORD_AUDIO`; an unattended instrumented run has no host, so the
     * rule grants it. Without it, recognizer construction could fail for a permission reason and be
     * misread as "this device has no on-device recognizer".
     */
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun probeOnDeviceRecognitionCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val part1 = JSONObject()
        var incompleteMeasurement: String? = null

        // Held across measurements and destroyed in the outer finally. checkRecognitionSupport and
        // triggerModelDownload both need a live instance, and constructing one per measurement would
        // multiply the main-thread hops for no gain.
        var recognizer: SpeechRecognizer? = null

        try {
            part1.put("capturedAt", Instant.now().toString())
            part1.put("device", deviceIdentity())

            // --- Availability -------------------------------------------------------------------
            // Finding C: isOnDeviceRecognitionAvailable() and createOnDeviceSpeechRecognizer()'s
            // UnsupportedOperationException are the SAME signal, so both are recorded and compared.
            part1.put(
                "isOnDeviceRecognitionAvailable",
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            )

            // --- Construction -------------------------------------------------------------------
            // Finding B: the create* factories call checkIsCalledFromMainThread() and hard-throw off
            // it. Constructing inside runOnMainSync is therefore mandatory here AND simultaneously
            // proves the main-thread contract the whole engine design (D-09) rests on.
            val constructionError = AtomicReference<String?>(null)
            instrumentation.runOnMainSync {
                try {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } catch (e: UnsupportedOperationException) {
                    constructionError.set(e.message ?: e.toString())
                }
            }
            part1.put("onDeviceConstruction", constructionError.get() ?: "ok")

            // --- Installed recognition services -------------------------------------------------
            // Obstacle 3 / Pitfall 5: an EMPTY list here means the library manifest's <queries>
            // element is missing, NOT that the device has no recognizer. Suspect the manifest first.
            val services = context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
            part1.put("installedRecognitionServices", JSONArray(services))

            // Which package the system has actually selected to back recognition — a separate
            // question from which packages are installed.
            part1.put(
                "voiceRecognitionServiceSetting",
                Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
                    ?: JSONObject.NULL,
            )

            val offlineIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, EN_US)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            val live = recognizer
            if (live == null) {
                // A legitimate negative result, not an incomplete measurement: with no recognizer
                // there is nothing to ask. Record why the downstream measurements are absent.
                val skipped = JSONObject().put("skipped", "on-device recognizer unavailable")
                part1.put("languageSupport", skipped)
                part1.put("modelDownloadTriggerReachable", skipped)
            } else {
                // --- Language support matrix ----------------------------------------------------
                // Grounds D-26's precondition (is the en-US pack ALREADY installed?) and the three
                // non-onError `no-offline-model` rows of the D-18 disposition table.
                // Finding B: checkRecognitionSupport does NOT require the main thread.
                val supportResult = AtomicReference<RecognitionSupport?>(null)
                val supportError = AtomicInteger(NO_ERROR)
                val latch = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                try {
                    live.checkRecognitionSupport(
                        offlineIntent,
                        executor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                                supportResult.set(recognitionSupport)
                                latch.countDown()
                            }

                            override fun onError(error: Int) {
                                supportError.set(error)
                                latch.countDown()
                            }
                        },
                    )
                    val answered = latch.await(SUPPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    part1.put(
                        "languageSupport",
                        languageSupport(answered, supportResult.get(), supportError.get()),
                    )
                    if (!answered) {
                        // The one genuine "could not complete a measurement" case.
                        incompleteMeasurement =
                            "checkRecognitionSupport did not call back within " +
                                "$SUPPORT_TIMEOUT_SECONDS s — languageSupport is unmeasured, so " +
                                "D-26's precondition is unanswered."
                    }
                } finally {
                    executor.shutdownNow()
                }

                // --- Model-download trigger -----------------------------------------------------
                // The `no-offline-model` recovery action. Only reachability is probed; whether a
                // download actually completes is out of scope for an unattended run.
                part1.put(
                    "modelDownloadTriggerReachable",
                    triggerModelDownloadReachable(live, offlineIntent),
                )
            }
        } finally {
            recognizer?.let { live ->
                // Finding B: destroy() is NOT main-thread-restricted, but OEM implementations vary
                // and the cost of being consistent with every other lifecycle call is nil.
                instrumentation.runOnMainSync { live.destroy() }
            }
            writeReport(context, part1)
        }

        incompleteMeasurement?.let { fail(it) }
    }

    private fun deviceIdentity(): JSONObject = JSONObject().apply {
        put("MANUFACTURER", Build.MANUFACTURER)
        put("MODEL", Build.MODEL)
        put("DEVICE", Build.DEVICE)
        put("FINGERPRINT", Build.FINGERPRINT)
        put("SDK_INT", Build.VERSION.SDK_INT)
        put("SECURITY_PATCH", Build.VERSION.SECURITY_PATCH)
    }

    private fun languageSupport(
        answered: Boolean,
        support: RecognitionSupport?,
        errorCode: Int,
    ): JSONObject = JSONObject().apply {
        put("requestedLanguage", EN_US)
        put("preferOffline", true)
        when {
            !answered -> put(
                "supportError",
                "timeout after $SUPPORT_TIMEOUT_SECONDS s (no callback)",
            )

            support != null -> {
                put(
                    "installedOnDeviceLanguages",
                    JSONArray(support.installedOnDeviceLanguages),
                )
                put(
                    "supportedOnDeviceLanguages",
                    JSONArray(support.supportedOnDeviceLanguages),
                )
                put("pendingOnDeviceLanguages", JSONArray(support.pendingOnDeviceLanguages))
                put("onlineLanguages", JSONArray(support.onlineLanguages))
                put("supportError", JSONObject.NULL)
            }

            else -> put("supportError", errorCode)
        }
    }

    private fun triggerModelDownloadReachable(
        recognizer: SpeechRecognizer,
        intent: Intent,
    ): JSONObject = JSONObject().apply {
        try {
            recognizer.triggerModelDownload(intent)
            put("threw", false)
        } catch (t: Throwable) {
            // Deliberately broad: this measures *reachability*, and any throwable is the answer.
            put("threw", true)
            put("throwable", "${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * Writes the committed-evidence envelope, not just the raw measurements, so the pulled file
     * needs no host-side reshaping before it lands in `.planning/`.
     */
    private fun writeReport(
        context: android.content.Context,
        part1: JSONObject,
    ) {
        val envelope = JSONObject()
            .put("part1", part1)
            // Reserved for plan 03's behavioural probe (segmented-session reality, restart deaf
            // window, rung (c) viability) — the part that needs a human voice or a played WAV.
            .put("part2", JSONObject.NULL)

        val dir = requireNotNull(context.getExternalFilesDir(null)) {
            "external files dir unavailable — cannot write the probe report"
        }
        File(dir, REPORT_FILE_NAME).writeText(envelope.toString(2))
    }

    private companion object {
        const val EN_US = "en-US"
        const val SUPPORT_TIMEOUT_SECONDS = 30L
        const val NO_ERROR = Int.MIN_VALUE
        const val REPORT_FILE_NAME = "probe-report.json"
    }
}

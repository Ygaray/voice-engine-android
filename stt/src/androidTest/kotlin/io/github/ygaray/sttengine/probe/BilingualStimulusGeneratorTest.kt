package io.github.ygaray.sttengine.probe

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Best-effort generator for SINGLE-VOICE mixed-language EN<->ES utterances — the one thing
 * splicing two single-language corpora (`BilingualStimulus`'s primary source, real human speech
 * from plan 149-01's fixture corpus) cannot produce, because a spliced language change is also a
 * speaker change.
 *
 * Opt-in — a tool, not a test — following [StimulusGeneratorTest]'s exact `assumeTrue` shape so a
 * plain `connectedDebugAndroidTest` never triggers it. Pass
 * `-Pandroid.testInstrumentationRunnerArguments.generateStimulus=true`.
 *
 * **Best-effort, not required.** `build-bilingual-stimulus.py` builds a complete, valid stimulus
 * from spliced real-human-speech clips alone; this generator only adds richer single-voice
 * code-switch material when the on-device es-US Google TTS voice happens to be present. If
 * `setLanguage(Locale("es", "US"))` returns below `LANG_AVAILABLE` (e.g. `LANG_MISSING_DATA`),
 * that absence is itself recorded as a finding (written to
 * `<externalFilesDir>/bilingual-stimulus-parts/tts-availability.json`) and the test completes
 * successfully having produced zero utterance parts — never a failure, matching this probe
 * suite's own negative-result-is-data doctrine ([RecognizerCapabilityProbeTest],
 * [RecognizerBehaviourProbeTest]).
 *
 * **Mechanical gotcha (do not parallelise):** `TextToSpeech.setLanguage` is engine-global, so every
 * utterance is synthesised strictly one at a time — set the locale, `synthesizeToFile`, await that
 * utterance's own `onDone` latch, THEN move to the next. Queueing every utterance before changing
 * locale renders the whole queue in whichever locale was set last.
 *
 * ### Regeneration procedure
 * ```
 * cd android && ANDROID_SERIAL=<tester serial, read fresh from ~/.config/test-device.env> \
 *   ./gradlew :stt:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.ygaray.sttengine.probe.BilingualStimulusGeneratorTest \
 *   -Pandroid.testInstrumentationRunnerArguments.generateStimulus=true \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 * adb -s <tester> pull \
 *   /sdcard/Android/data/io.github.ygaray.sttengine.test/files/bilingual-stimulus-parts <dest>
 * python3 android/scripts/build-bilingual-stimulus.py --tts-parts <dest> \
 *   android/stt/src/androidTest/assets/bilingual-stimulus-16k-mono.wav
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BilingualStimulusGeneratorTest {

    @Test
    fun synthesiseSingleVoiceCodeSwitchUtterances() {
        val args = InstrumentationRegistry.getArguments()
        // A tool, not a test: no-op unless explicitly requested.
        assumeTrue(
            "BilingualStimulusGeneratorTest is opt-in; pass -Pandroid.testInstrumentationRunner" +
                "Arguments.generateStimulus=true to attempt single-voice code-switch synthesis.",
            args.getString("generateStimulus") == "true",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(
            requireNotNull(context.getExternalFilesDir(null)) {
                "external files dir unavailable — cannot write the stimulus parts"
            },
            "bilingual-stimulus-parts",
        )
        outDir.deleteRecursively()
        check(outDir.mkdirs()) { "could not create ${outDir.absolutePath}" }

        var tts: TextToSpeech? = null
        try {
            val initStatus = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            // Pin the engine, same reasoning as StimulusGeneratorTest: the default synth is not a
            // stable contract across runs.
            tts = TextToSpeech(
                context,
                { s ->
                    status = s
                    initStatus.countDown()
                },
                GOOGLE_TTS_PACKAGE,
            )
            assertTrue(
                "TextToSpeech did not initialise within $INIT_TIMEOUT_SECONDS s",
                initStatus.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            assertTrue("TextToSpeech init returned $status", status == TextToSpeech.SUCCESS)
            val engine = requireNotNull(tts)

            // Critical check BEFORE synthesising anything Spanish: a result below LANG_AVAILABLE
            // (LANG_MISSING_DATA / LANG_NOT_SUPPORTED) means the Spanish voice is absent. Record
            // the finding and stop here — no queue, no partial synthesis, no failure.
            val esResult = engine.setLanguage(Locale("es", "US"))
            val spanishAvailable = esResult >= TextToSpeech.LANG_AVAILABLE
            File(outDir, "tts-availability.json").writeText(
                JSONObject()
                    .put("spanishVoiceAvailable", spanishAvailable)
                    .put("setLanguageResult", esResult)
                    .toString(2),
            )
            if (!spanishAvailable) {
                // A complete, valid, reportable finding — not an incomplete measurement. The
                // stitcher builds from the spliced clips alone when this file says false.
                return
            }

            engine.setSpeechRate(1.0f)
            engine.setPitch(1.0f)

            UTTERANCES.forEachIndexed { index, text ->
                // One utterance at a time, per the KDoc gotcha. Re-asserting the locale here
                // (even though it hasn't changed within this loop) means a future addition of an
                // EN-voice utterance to this list cannot silently regress into the shared-queue
                // bug by forgetting to re-set it.
                val langResult = engine.setLanguage(Locale("es", "US"))
                check(langResult >= TextToSpeech.LANG_AVAILABLE) {
                    "es-US became unavailable mid-run (result $langResult)"
                }

                val id = "codeswitch-%02d".format(index)
                val done = CountDownLatch(1)
                var failure: String? = null
                engine.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            done.countDown()
                        }

                        @Deprecated("required override", ReplaceWith(""))
                        override fun onError(utteranceId: String?) {
                            failure = "unspecified error"
                            done.countDown()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            failure = "code $errorCode"
                            done.countDown()
                        }
                    },
                )
                val queued = engine.synthesizeToFile(
                    text,
                    /* params = */ null,
                    File(outDir, "$id.wav"),
                    id,
                )
                assertTrue("synthesizeToFile queueing failed for $id", queued == TextToSpeech.SUCCESS)
                assertTrue(
                    "synthesis of $id did not complete within $SYNTH_TIMEOUT_SECONDS s",
                    done.await(SYNTH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertTrue("synthesis error for $id: $failure", failure == null)
            }
        } finally {
            tts?.let {
                it.stop()
                it.shutdown()
            }
        }
    }

    private companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        const val INIT_TIMEOUT_SECONDS = 30L
        const val SYNTH_TIMEOUT_SECONDS = 60L

        /**
         * Single-voice mixed-language utterances — realistic owner-habit code-switch examples
         * (RESEARCH.md's "ES-dominant with English insertions" and "single-word code-switch"
         * categories), spoken by ONE es-US Google TTS voice. This is exactly what splicing two
         * single-language corpora cannot produce.
         */
        val UTTERANCES: List<String> = listOf(
            "Voy a hacer un backup del archivo",
            "Send me el link por WhatsApp",
        )
    }
}

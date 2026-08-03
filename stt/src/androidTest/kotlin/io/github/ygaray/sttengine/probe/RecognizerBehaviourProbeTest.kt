package io.github.ygaray.sttengine.probe

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The D-04 device probe, **part 2 — behaviour**. Like part 1
 * ([RecognizerCapabilityProbeTest]) it is a *reporting instrument*: it asserts nothing about the
 * device's answers, and a negative answer is a valid result. Its output, merged into
 * `05-PROBE-REPORT.json` under `part2`, is the sole evidence behind `05-RUNG-VERDICT.md`, which is
 * in turn plan 09's only input.
 *
 * Part 1 answered what the device *claims* (`isOnDeviceRecognitionAvailable`, the installed language
 * packs, which service backs recognition). This answers what it actually *does*, which is a
 * different question and the only one that can settle rungs (b) and (c) — `EXTRA_SEGMENTED_SESSION`
 * is documented as *"depending on the recognizer implementation, this value may have no effect"*,
 * with no error and no log when it is ignored (Pitfall 3). Documentation cannot answer this; only a
 * measurement on this device can.
 *
 * ### Seven measurements
 *
 *  1. `segmentedSession` — the binary no-op discriminator for rung (b).
 *  2. `deafWindow` — restart gap in ms over ≥10 cycles; quantifies AND-02's known risk.
 *  3. `rawAudioSource` — rung (c) viability, plus the deliberate D-05 data-loss repro.
 *  4. `fileFedAudio` — can a WAV be fed as the audio source? A yes unlocks a fully deterministic
 *     AND-02 harness and is the single most valuable thing this probe can find.
 *  5. `speakerLoopback` — the fallback AND-02 stimulus.
 *  6. `partialQuality` — partial count, cumulative-vs-replacing, last-partial→final distance.
 *  7. `beepObserved` — human-reported; written null here, resolved in the verdict.
 *
 * Plus `finalResultDelivery`, which is **not** in the plan's table: the first run on this device
 * showed rung-(a) sessions producing rich partials and then a blank `onResults`, and that claim is
 * far too consequential for the engine's whole final-commit design to leave resting on one
 * incidental observation. See [measureFinalResultDelivery].
 *
 * ### Main-thread contract
 *
 * Every `SpeechRecognizer` construction and every `setRecognitionListener` / `startListening` /
 * `stopListening` / `cancel` call goes through `runOnMainSync` — those five hard-throw
 * `RuntimeException` off the main thread (RESEARCH Finding B). `destroy()` is exempt but is routed
 * the same way for uniformity. All callbacks arrive on the main looper (Finding A), so
 * [ProbeRecorder]'s counters need no synchronization; the test thread waits on a `CountDownLatch`.
 *
 * ### Robustness
 *
 * Each measurement is independently wrapped, so one failing measurement records its own error rather
 * than costing the other six — a ~9-minute run on a remote device is far too expensive to have to
 * repeat because one branch threw. The report is written in a `finally` on every path.
 *
 * ### Running it
 *
 * ```
 * cd android && ANDROID_SERIAL=100.113.42.22:1496 ./gradlew :stt:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.ygaray.sttengine.probe.RecognizerBehaviourProbeTest \
 *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
 * ```
 *
 * Both flags are mandatory and both were learned the hard way in plan 01: `--tests` is a Gradle JVM
 * `Test` option that AGP's `connectedDebugAndroidTest` rejects outright, and without
 * `leaveApksInstalledAfterRun` the task uninstalls the test APK during its own teardown, taking the
 * app's external files dir — and the report this run just wrote — with it. Pull the report
 * immediately afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RecognizerBehaviourProbeTest {

    /** D-11: the HOST requests `RECORD_AUDIO`. An unattended instrumented run has no host. */
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun probeRecognizerBehaviour() {
        val part2 = JSONObject()
        var stimulus: File? = null
        val audioManager = context.getSystemService(AudioManager::class.java)
        val originalVolume =
            runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()

        try {
            part2.put("capturedAt", Instant.now().toString())
            part2.put("device", deviceIdentity())

            stimulus = copyStimulusToCache()
            part2.put("stimulus", stimulusMetadata(stimulus))
            part2.put("mediaVolume", maximiseMediaVolume(audioManager))

            val file = stimulus
            part2.put("segmentedSession", measure { measureSegmentedSession(file) })
            part2.put("deafWindow", measure { measureDeafWindow(file) })
            part2.put("rawAudioSource", measure { measureRawAudioSource(file) })
            part2.put("fileFedAudio", measure { measureFileFedAudio(file) })

            val loopback = measure { measureSpeakerLoopback(file) }
            val partialQuality = loopback.remove("partialQuality") as? JSONObject
                ?: JSONObject().put(
                    "measurementError",
                    "partial quality is derived from the speaker-loopback run, which did not complete",
                )
            part2.put("speakerLoopback", loopback)
            part2.put("partialQuality", partialQuality)

            part2.put("finalResultDelivery", measure { measureFinalResultDelivery(file) })
            part2.put("beepObserved", beepObserved())
        } finally {
            // Restore the volume we raised. D-06 forbids the *library* from touching device audio;
            // this is a harness raising media volume so the speaker can act as a stimulus, and
            // leaving the tester at max volume afterwards would be rude to its next user.
            originalVolume?.let {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            }
            stimulus?.delete()
            writeReport(part2)
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1. segmentedSession — the binary no-op discriminator
    // ------------------------------------------------------------------------------------------

    /**
     * The discriminator is exact, which is why one session is enough evidence:
     * `onEndOfSegmentedSession` is delivered **only** in segmented mode. Segment results plus that
     * callback ⇒ the extra is real. Ending on `onResults` with neither segmented callback ever
     * firing ⇒ the extra was silently ignored.
     */
    private fun measureSegmentedSession(stimulus: File): JSONObject {
        // The primary run is exactly the plan's specification, so the discriminator is answered on
        // the specified terms. The follow-up varies ONLY the silence value, because the primary run
        // showed the session ending seconds after it began — and "rung (b) is real but the session
        // died early" and "rung (b) is real and usable" are different verdicts for plan 09.
        val primary = segmentedRun(
            stimulus,
            completeSilenceMs = COMPLETE_SILENCE_MS,
            possiblySilenceMs = POSSIBLY_COMPLETE_SILENCE_MS,
            playBeforeListening = false,
            budgetMs = SEGMENTED_PLAY_MS,
            label = "segmentedSession",
        )
        val longSilence = measure {
            segmentedRun(
                stimulus,
                completeSilenceMs = LONG_COMPLETE_SILENCE_MS,
                possiblySilenceMs = LONG_POSSIBLY_SILENCE_MS,
                // Start the audio first so the recognizer is not handed a lead-in of dead air that
                // could itself trip the session's silence bound.
                playBeforeListening = true,
                budgetMs = SEGMENTED_LONG_PLAY_MS,
                label = "segmentedSession-longSilence",
            )
        }
        return primary.put("longSilenceRun", longSilence)
    }

    private fun segmentedRun(
        stimulus: File,
        completeSilenceMs: Int,
        possiblySilenceMs: Int,
        playBeforeListening: Boolean,
        budgetMs: Long,
        label: String,
    ): JSONObject {
        val result = JSONObject()
            .put("completeSilenceMs", completeSilenceMs)
            .put("possiblyCompleteSilenceMs", possiblySilenceMs)
            .put("playbackStartedBeforeListening", playBeforeListening)
        val recorder = ProbeRecorder(label)
        val intent = baseIntent(completeSilenceMs, possiblySilenceMs).putExtra(
            RecognizerIntent.EXTRA_SEGMENTED_SESSION,
            // The javadoc requires the referenced key to also be populated in the same intent —
            // baseIntent() sets it.
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        )
        result.put("segmentedSessionExtraValue", RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)

        var recognizer: SpeechRecognizer? = null
        var player: MediaPlayer? = null
        try {
            recognizer = createRecognizer()
            if (playBeforeListening) {
                player = startPlayback(stimulus)
                Thread.sleep(PLAYBACK_LEAD_IN_MS)
                startListening(recognizer, recorder, intent)
            } else {
                startListening(recognizer, recorder, intent)
                player = startPlayback(stimulus)
            }

            val endedEarly = recorder.terminal.await(budgetMs, TimeUnit.MILLISECONDS)
            result.put("endedBeforeStopListening", endedEarly)
            if (!endedEarly) {
                stopListening(recognizer)
                result.put(
                    "terminalAfterStopListening",
                    recorder.terminal.await(TERMINAL_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                )
            }
        } finally {
            releasePlayer(player)
            releaseRecognizer(recognizer)
        }

        val segments = recorder.segmentTexts.size
        val verdict = when {
            segments >= 1 && recorder.endOfSegmentedSession -> SEGMENTED_REAL
            recorder.onResultsCount > 0 && segments == 0 && !recorder.endOfSegmentedSession ->
                SEGMENTED_NO_OP

            else -> SEGMENTED_INCONCLUSIVE
        }
        return result.apply {
            put("onSegmentResultsCount", segments)
            put("endOfSegmentedSessionFired", recorder.endOfSegmentedSession)
            put("endedWithOnResults", recorder.onResultsCount > 0)
            put("segmentGapsMs", JSONArray(recorder.segmentGapsMs()))
            put("verdict", verdict)
            put("score", ProbeText.score(ProbeStimulus.TRANSCRIPT, recorder.recognizedText()))
            put("session", recorder.toJson())
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. deafWindow — the restart gap, in milliseconds
    // ------------------------------------------------------------------------------------------

    /**
     * Two passes. The primary restarts with **zero** delay, so the number reported is the
     * *platform's* contribution and nothing else — inserting openclaw's 200 ms restart delay into
     * the primary pass would have measured our own `sleep` and called it a device characteristic.
     * The comparison pass adds that 200 ms so the two are directly subtractable, and so a
     * `ERROR_RECOGNIZER_BUSY` storm on immediate restart (which would itself be a finding about how
     * v1c must pace restarts) is visible rather than inferred.
     */
    private fun measureDeafWindow(stimulus: File): JSONObject {
        val primary = deafWindowPass(stimulus, restartDelayMs = 0)
        val comparison = measure { deafWindowPass(stimulus, restartDelayMs = DELAYED_RESTART_MS) }
        return primary.put("comparisonPass", comparison)
    }

    private fun deafWindowPass(stimulus: File, restartDelayMs: Long): JSONObject {
        val cycles = mutableListOf<ProbeRecorder>()
        var recognizer: SpeechRecognizer? = null
        var player: MediaPlayer? = null
        try {
            // ONE recognizer reused across restarts — the shape the restart ladder actually has.
            // Constructing a fresh instance per cycle would measure service binding, not restarting.
            recognizer = createRecognizer()
            player = startPlayback(stimulus)
            repeat(DEAF_WINDOW_CYCLES) { index ->
                if (restartDelayMs > 0) Thread.sleep(restartDelayMs)
                val recorder = ProbeRecorder("cycle-$index")
                cycles += recorder
                startListening(recognizer, recorder, baseIntent())
                var done = recorder.terminal.await(CYCLE_LISTEN_MS, TimeUnit.MILLISECONDS)
                if (!done) {
                    stopListening(recognizer)
                    done = recorder.terminal.await(CYCLE_TERMINAL_MS, TimeUnit.MILLISECONDS)
                }
                if (!done) {
                    cancel(recognizer)
                    Thread.sleep(CYCLE_RECOVERY_MS)
                }
            }
        } finally {
            releasePlayer(player)
            releaseRecognizer(recognizer)
        }

        val samples = mutableListOf<Long>()
        val cycleJson = JSONArray()
        val terminalKinds = JSONObject()
        cycles.forEachIndexed { index, recorder ->
            val ready = recorder.readyAtMs
            val previousEnd = cycles.getOrNull(index - 1)?.terminalAtMs
            val deafMs = if (ready != null && previousEnd != null) ready - previousEnd else null
            if (deafMs != null && deafMs >= 0) samples += deafMs
            recorder.terminalKind?.let {
                terminalKinds.put(it, terminalKinds.optInt(it, 0) + 1)
            }
            cycleJson.put(
                JSONObject()
                    .put("cycle", index)
                    .put("terminalKind", recorder.terminalKind ?: JSONObject.NULL)
                    .put("deafWindowMs", deafMs ?: JSONObject.NULL)
                    .put(
                        "readyToSpeechMs",
                        recorder.beginningOfSpeechAtMs?.let { speech ->
                            recorder.readyAtMs?.let { speech - it }
                        } ?: JSONObject.NULL,
                    )
                    .put("errorCodes", JSONArray(recorder.errors))
                    .put("partialCount", recorder.partials.size)
                    .put("finalText", recorder.recognizedText())
                    .put("lastNonBlankPartial", recorder.lastNonBlankPartial() ?: JSONObject.NULL),
            )
        }

        return ProbeText.statsOf(samples).apply {
            put("restartDelayMs", restartDelayMs)
            put("cycleCount", cycles.size)
            put("terminalKinds", terminalKinds)
            put("cycles", cycleJson)
            put(
                "definition",
                "deafWindowMs = onReadyForSpeech(cycle N) - terminating callback(cycle N-1); " +
                    "one SpeechRecognizer instance reused across restarts; " +
                    "restartDelayMs is the delay this pass deliberately inserted before each start.",
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. rawAudioSource — rung (c) viability and the D-05 data-loss repro
    // ------------------------------------------------------------------------------------------

    private fun measureRawAudioSource(stimulus: File): JSONObject {
        val clean = measure { rawAudioRun(stimulus, closeWriteEndAfterMs = null) }
        val closed = measure { rawAudioRun(stimulus, closeWriteEndAfterMs = MID_UTTERANCE_CLOSE_MS) }
        val viable = clean.optInt("partialCount", 0) > 0 && clean.optBoolean("producedFinal", false)
        return JSONObject()
            .put("cleanRun", clean)
            .put("midUtteranceCloseRun", closed)
            .put("pipeSessionViable", viable)
            .put(
                "dataLossSignatureReproduced",
                closed.opt("dataLossSignatureReproduced") ?: JSONObject.NULL,
            )
    }

    /**
     * Opens a real microphone into a real pipe and hands the read end to the recognizer.
     *
     * When [closeWriteEndAfterMs] is non-null the write end is deliberately closed mid-utterance to
     * reproduce jamsch/expo-speech-recognition#158 — the recognizer thread is interrupted mid-read
     * instead of finalizing, so good partials can exist for text that never reaches any final. That
     * is data loss in a dictation app, so whether it reproduces *here* decides whether rung (c) is
     * safe on this device even if it is technically available.
     *
     * Both descriptors and the recorder are released in the `finally` on every path (threat
     * T-05-09): a leaked descriptor is a resource-exhaustion risk on a long session, and the
     * ordering below matters — the recorder and the pipe are torn down *before* the pump is joined,
     * because a pump blocked on a full 64 KB pipe buffer is only unblocked by closing the read end.
     */
    private fun rawAudioRun(stimulus: File, closeWriteEndAfterMs: Long?): JSONObject {
        val result = JSONObject().put("closeWriteEndAfterMs", closeWriteEndAfterMs ?: JSONObject.NULL)
        val recorder = ProbeRecorder(if (closeWriteEndAfterMs == null) "rawAudio-clean" else "rawAudio-close")
        val stopPump = AtomicBoolean(false)
        val bytesPumped = AtomicLong(0)
        val pumpError = AtomicReference<String?>(null)

        var pipe: Array<ParcelFileDescriptor>? = null
        var audioRecord: AudioRecord? = null
        var recognizer: SpeechRecognizer? = null
        var player: MediaPlayer? = null
        var pump: Thread? = null

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = maxOf(minBuffer, SAMPLE_RATE_HZ * 2)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            result.put("audioRecordInitialised", audioRecord.state == AudioRecord.STATE_INITIALIZED)
            result.put("audioRecordBufferBytes", bufferBytes)

            pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]

            audioRecord.startRecording()
            result.put(
                "audioRecordRecording",
                audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING,
            )

            val source = audioRecord
            // Single-ownership on purpose: the pump borrows the descriptor's stream and NEVER
            // closes it. The test's finally owns every descriptor, so there is exactly one place
            // to audit for leaks.
            val out = FileOutputStream(writeEnd.fileDescriptor)
            pump = Thread {
                try {
                    val buffer = ByteArray(FEED_CHUNK_BYTES)
                    while (!stopPump.get()) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        out.flush()
                        bytesPumped.addAndGet(read.toLong())
                    }
                } catch (t: Throwable) {
                    pumpError.set("${t.javaClass.simpleName}: ${t.message}")
                }
            }.apply {
                isDaemon = true
                name = "probe-mic-pump"
                start()
            }

            recognizer = createRecognizer()
            startListening(recognizer, recorder, rawAudioIntent(readEnd))
            player = startPlayback(stimulus)

            if (closeWriteEndAfterMs == null) {
                val done = recorder.terminal.await(RAW_CLEAN_BUDGET_MS, TimeUnit.MILLISECONDS)
                if (!done) {
                    stopListening(recognizer)
                    recorder.terminal.await(TERMINAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            } else {
                Thread.sleep(closeWriteEndAfterMs)
                val partialsBeforeClose = recorder.partials.toList()
                result.put("partialsBeforeClose", JSONArray(partialsBeforeClose.takeLast(10)))
                result.put(
                    "lastPartialBeforeClose",
                    partialsBeforeClose.lastOrNull() ?: JSONObject.NULL,
                )
                stopPump.set(true)
                writeEnd.close()
                result.put("writeEndClosed", true)
                recorder.terminal.await(POST_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                val lastPartial = partialsBeforeClose.lastOrNull { it.isNotBlank() }
                val lostUtterance = lastPartial != null &&
                    recorder.finals.none { final ->
                        final.isNotBlank() &&
                            ProbeText.words(final).containsAll(ProbeText.words(lastPartial).take(3))
                    }
                result.put("errorClient5Received", recorder.errors.contains(ERROR_CLIENT))
                result.put("goodPartialLostFromFinals", lostUtterance)
                result.put(
                    "dataLossSignatureReproduced",
                    recorder.errors.contains(ERROR_CLIENT) && lostUtterance,
                )
            }
        } finally {
            stopPump.set(true)
            releasePlayer(player)
            releaseRecognizer(recognizer)
            audioRecord?.let { record ->
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                }
                record.release()
            }
            pipe?.forEach { descriptor -> runCatching { descriptor.close() } }
            pump?.join(PUMP_JOIN_MS)
        }

        return result.apply {
            put("bytesPumped", bytesPumped.get())
            put("pumpError", pumpError.get() ?: JSONObject.NULL)
            put("partialCount", recorder.partials.size)
            put("producedFinal", recorder.finals.any { it.isNotBlank() })
            put("onSegmentResultsCount", recorder.segmentTexts.size)
            put("endOfSegmentedSessionFired", recorder.endOfSegmentedSession)
            put("score", ProbeText.score(ProbeStimulus.TRANSCRIPT, recorder.recognizedText()))
            put("session", recorder.toJson())
        }
    }

    // ------------------------------------------------------------------------------------------
    // 4. fileFedAudio — the deterministic AND-02 stimulus candidate
    // ------------------------------------------------------------------------------------------

    /**
     * Feeds the committed WAV's PCM payload — header skipped via [ProbeWav], never a hardcoded 44 —
     * into the recognizer through a pipe, paced at real time.
     *
     * Real-time pacing rather than "as fast as the pipe accepts" is deliberate: it is what
     * `expo.kt`'s `DelayedFileStreamer` does, and a recognizer whose endpointer has any wall-clock
     * component would segment nonsensically on a firehose, producing a false negative on the single
     * most valuable question this probe asks.
     */
    private fun measureFileFedAudio(stimulus: File): JSONObject {
        val pcm = ProbeWav.parse(stimulus)
        val result = JSONObject()
            .put("dataOffset", pcm.dataOffset)
            .put("dataLength", pcm.dataLength)
            .put("sampleRate", pcm.sampleRate)
            .put("channels", pcm.channels)
            .put("bitsPerSample", pcm.bitsPerSample)
            .put("durationMs", pcm.durationMs)
        val recorder = ProbeRecorder("fileFedAudio")
        val stopPump = AtomicBoolean(false)
        val bytesFed = AtomicLong(0)
        val pumpError = AtomicReference<String?>(null)
        val fedFully = AtomicBoolean(false)

        var pipe: Array<ParcelFileDescriptor>? = null
        var recognizer: SpeechRecognizer? = null
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
                            bytesFed.set(sent)
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
                name = "probe-file-pump"
                start()
            }

            recognizer = createRecognizer()
            startListening(recognizer, recorder, rawAudioIntent(readEnd))

            // Wait for the feed to finish (or the budget to expire), then close the write end: EOF
            // is the natural end-of-stream signal for a file source, so this is not a teardown, it
            // is the last thing the recognizer needs in order to finalize.
            pump.join(pcm.durationMs + FILE_FEED_SLACK_MS)
            stopPump.set(true)
            runCatching { writeEnd.close() }
            result.put("fedFully", fedFully.get())
            result.put(
                "terminalAfterEof",
                recorder.terminal.await(POST_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
        } finally {
            stopPump.set(true)
            releaseRecognizer(recognizer)
            pipe?.forEach { descriptor -> runCatching { descriptor.close() } }
            pump?.join(PUMP_JOIN_MS)
        }

        val text = recorder.recognizedText()
        return result.apply {
            put("bytesFed", bytesFed.get())
            put("pumpError", pumpError.get() ?: JSONObject.NULL)
            put("partialCount", recorder.partials.size)
            put("onSegmentResultsCount", recorder.segmentTexts.size)
            put("endOfSegmentedSessionFired", recorder.endOfSegmentedSession)
            put("producedText", text.isNotBlank())
            put("score", ProbeText.score(ProbeStimulus.TRANSCRIPT, text))
            put("session", recorder.toJson())
        }
    }

    // ------------------------------------------------------------------------------------------
    // 5 + 6. speakerLoopback and partialQuality
    // ------------------------------------------------------------------------------------------

    /**
     * Plays the stimulus out the speaker while a rung-(a) restart loop listens on the microphone.
     *
     * A restart loop rather than one long session because that is the shape v1c actually ships, and
     * because a single rung-(a) session ends at the first 3 s gap — one utterance would be far too
     * little text to score the stimulus on. The platform may apply echo cancellation to the
     * `VOICE_RECOGNITION` source, which is precisely why this is measured rather than assumed;
     * `maxRmsDb` in each session record is the direct answer to whether the microphone heard the
     * speaker at all.
     */
    private fun measureSpeakerLoopback(stimulus: File): JSONObject {
        val sessions = mutableListOf<ProbeRecorder>()
        var recognizer: SpeechRecognizer? = null
        var player: MediaPlayer? = null
        try {
            recognizer = createRecognizer()
            player = startPlayback(stimulus)
            val deadline = SystemClock.elapsedRealtime() + LOOPBACK_BUDGET_MS
            var index = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val recorder = ProbeRecorder("loopback-$index")
                index++
                sessions += recorder
                startListening(recognizer, recorder, baseIntent())
                val done = recorder.terminal.await(LOOPBACK_SESSION_MS, TimeUnit.MILLISECONDS)
                if (!done) {
                    cancel(recognizer)
                    Thread.sleep(CYCLE_RECOVERY_MS)
                }
            }
        } finally {
            releasePlayer(player)
            releaseRecognizer(recognizer)
        }

        val text = sessions.joinToString(" ") { it.recognizedText() }.trim()
        val maxRms = sessions.mapNotNull { session ->
            session.maxRmsDb.takeIf { it != Float.NEGATIVE_INFINITY }
        }.maxOrNull()

        // The acoustically recoverable text, scored separately from the finals-based text.
        // Both numbers are needed and they answer different questions: `score` says whether the
        // documented final path yields anything (on this device it does not), while
        // `recoverableScore` says whether the SPEAKER→MIC path carries intelligible speech at all.
        // Conflating them would let a platform defect in the final path masquerade as "acoustic
        // loopback does not work", and would wrongly eliminate AND-02's fallback stimulus.
        val recoverableParts = sessions.mapNotNull { it.lastNonBlankPartial() }
        val recoverableText = recoverableParts.joinToString(" ").trim()

        return JSONObject()
            .put("playedMs", LOOPBACK_BUDGET_MS)
            .put("sessionCount", sessions.size)
            .put("recognizedText", text)
            .put("producedText", text.isNotBlank())
            .put("maxRmsDbAcrossSessions", maxRms?.toDouble() ?: JSONObject.NULL)
            .put("score", ProbeText.score(ProbeStimulus.TRANSCRIPT, text))
            .put("sessionsContributingRecoverableText", recoverableParts.size)
            .put("recoverableText", recoverableText)
            .put("recoverableScore", ProbeText.score(ProbeStimulus.TRANSCRIPT, recoverableText))
            .put("sessions", JSONArray(sessions.map { it.toJson(includeEvents = false) }))
            .put("partialQuality", partialQuality(sessions))
    }

    /**
     * Answers whether partials are cumulative within an utterance — the assumption D-02's
     * append-vs-replace rule rests on. Measured by asking, for each consecutive partial pair,
     * whether the earlier one is a word-wise prefix of the later.
     */
    private fun partialQuality(sessions: List<ProbeRecorder>): JSONObject {
        var cumulativeTransitions = 0
        var replacingTransitions = 0
        var totalPartials = 0
        val lastPartialToFinal = mutableListOf<Long>()

        sessions.forEach { session ->
            totalPartials += session.partials.size
            session.partials.zipWithNext { earlier, later ->
                val earlierWords = ProbeText.words(earlier)
                val laterWords = ProbeText.words(later)
                if (earlierWords.isNotEmpty() &&
                    laterWords.size >= earlierWords.size &&
                    laterWords.take(earlierWords.size) == earlierWords
                ) {
                    cumulativeTransitions++
                } else {
                    replacingTransitions++
                }
            }
            val lastPartial = session.partials.lastOrNull { it.isNotBlank() }
            val finalText = session.finals.lastOrNull { it.isNotBlank() }
            if (lastPartial != null && finalText != null) {
                lastPartialToFinal += ProbeText.levenshtein(
                    ProbeText.words(lastPartial),
                    ProbeText.words(finalText),
                ).toLong()
            }
        }

        val transitions = cumulativeTransitions + replacingTransitions
        val sessionsWithFinal = sessions.count { session -> session.finals.any { it.isNotBlank() } }
        return JSONObject()
            .put("source", "speakerLoopback sessions")
            .put("totalPartials", totalPartials)
            .put("sessionCount", sessions.size)
            .put("sessionsWithNonBlankFinal", sessionsWithFinal)
            .put(
                "sessionsEndingOnBlankPartial",
                sessions.count { it.endedWithBlankPartial() },
            )
            .put(
                "lastPartialToFinalNote",
                if (sessionsWithFinal == 0) {
                    "NOT MEASURABLE on this device: every rung-(a) session delivered a BLANK " +
                        "onResults, so there is no final to measure a distance to. This is not a " +
                        "missing measurement — it is the finalResultDelivery finding, and it is " +
                        "the reason the sample list below is empty."
                } else {
                    "measured over the $sessionsWithFinal session(s) that produced a non-blank final"
                },
            )
            .put("cumulativeTransitions", cumulativeTransitions)
            .put("replacingTransitions", replacingTransitions)
            .put(
                "cumulativeFraction",
                if (transitions == 0) JSONObject.NULL
                else ProbeText.round3(cumulativeTransitions.toDouble() / transitions),
            )
            .put(
                "partialsAreCumulative",
                if (transitions == 0) JSONObject.NULL
                else cumulativeTransitions > replacingTransitions,
            )
            .put(
                "lastPartialToFinalWordDistance",
                ProbeText.statsOf(lastPartialToFinal).put(
                    "unit",
                    "word-level edit distance (the key is named …Ms by statsOf convention; " +
                        "these are word counts, not milliseconds)",
                ),
            )
    }

    // ------------------------------------------------------------------------------------------
    // finalResultDelivery — does rung (a)'s `onResults` actually carry text on this device?
    // ------------------------------------------------------------------------------------------

    /**
     * Not in the plan's measurement table, added because the first run made it load-bearing
     * (deviation Rule 2).
     *
     * Every rung-(a) session in the deaf-window and loopback measurements produced rich, accurate
     * partials and then an **empty** `onResults`. If that is real, it invalidates the most basic
     * assumption of the whole engine — that `onResults` is where a final comes from (D-02: the
     * final *replaces* the last partial) — and rung (a) is the ladder's terminal floor, so the
     * failure would have no fallback. Shipping v1c on the assumption that `onResults` carries text,
     * when on the only device that matters it does not, is precisely the silent corruption this
     * probe exists to prevent.
     *
     * Two runs isolate the cause, because they are the two ways a session can end:
     *  - `naturalSilenceEnd` — the recognizer endpoints on its own silence timer.
     *  - `explicitStopListening` — we call `stopListening()` while speech is still in flight.
     *
     * Both dump the raw `onResults` bundle key-by-key, so "empty" is distinguished from "absent"
     * from "under a different key" rather than being an artifact of how this probe reads it.
     */
    private fun measureFinalResultDelivery(stimulus: File): JSONObject {
        val natural = measure { finalDeliveryRun(stimulus, explicitStopAfterMs = null) }
        val explicit = measure {
            finalDeliveryRun(stimulus, explicitStopAfterMs = EXPLICIT_STOP_AFTER_MS)
        }
        return JSONObject()
            .put("naturalSilenceEnd", natural)
            .put("explicitStopListening", explicit)
            .put(
                "question",
                "Does onResults carry text in a plain rung-(a) session on this device, and does " +
                    "the answer depend on whether the session ended by silence timeout or by an " +
                    "explicit stopListening()?",
            )
    }

    private fun finalDeliveryRun(stimulus: File, explicitStopAfterMs: Long?): JSONObject {
        val label = if (explicitStopAfterMs == null) "final-natural" else "final-explicitStop"
        val recorder = ProbeRecorder(label)
        var recognizer: SpeechRecognizer? = null
        var player: MediaPlayer? = null
        try {
            recognizer = createRecognizer()
            // Playback first, then a lead-in wait, so the session opens onto live speech rather
            // than onto the stimulus's own leading silence.
            player = startPlayback(stimulus)
            Thread.sleep(PLAYBACK_LEAD_IN_MS)
            startListening(recognizer, recorder, baseIntent())
            if (explicitStopAfterMs != null) {
                Thread.sleep(explicitStopAfterMs)
                stopListening(recognizer)
            }
            recorder.terminal.await(FINAL_DELIVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            releasePlayer(player)
            releaseRecognizer(recognizer)
        }

        val lastPartial = recorder.partials.lastOrNull { it.isNotBlank() }
        val finalText = recorder.finals.lastOrNull()
        return JSONObject()
            .put("explicitStopAfterMs", explicitStopAfterMs ?: JSONObject.NULL)
            .put("terminalKind", recorder.terminalKind ?: JSONObject.NULL)
            .put("partialCount", recorder.partials.size)
            .put("lastPartial", lastPartial ?: JSONObject.NULL)
            .put("finalText", finalText ?: JSONObject.NULL)
            .put("finalIsBlank", finalText.isNullOrBlank())
            .put("anyNonBlankFinal", recorder.finals.any { it.isNotBlank() })
            .put("resultsBundles", JSONArray(recorder.resultsBundles))
            .put("partialBundles", JSONArray(recorder.partialBundles))
            .put("errorCodes", JSONArray(recorder.errors))
            .put("session", recorder.toJson(includeEvents = false))
    }

    // ------------------------------------------------------------------------------------------
    // 7. beepObserved
    // ------------------------------------------------------------------------------------------

    private fun beepObserved(): JSONObject = JSONObject()
        .put("rungA", JSONObject.NULL)
        .put("rungB", JSONObject.NULL)
        .put("rungC", JSONObject.NULL)
        .put(
            "note",
            "Not measured by this instrument. The probe runs unattended over wireless adb with no " +
                "listener in the room, and the device's own microphone is occupied by the loopback " +
                "stimulus for the whole run, so an acoustic self-measurement could not be " +
                "distinguished from the stimulus itself. Filled in from direct human listening, or " +
                "recorded as unmeasured in 05-RUNG-VERDICT.md — an unmeasured rung is a deferred " +
                "rung.",
        )

    // ------------------------------------------------------------------------------------------
    // Recognizer plumbing — every one of these hops to the main thread
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
        return created.get()
            ?: error("on-device recognizer unavailable: ${failure.get()}")
    }

    private fun startListening(
        recognizer: SpeechRecognizer,
        recorder: ProbeRecorder,
        intent: Intent,
    ) = instrumentation.runOnMainSync {
        recognizer.setRecognitionListener(recorder.listener)
        recognizer.startListening(intent)
    }

    private fun stopListening(recognizer: SpeechRecognizer) =
        instrumentation.runOnMainSync { recognizer.stopListening() }

    private fun cancel(recognizer: SpeechRecognizer) =
        instrumentation.runOnMainSync { recognizer.cancel() }

    private fun releaseRecognizer(recognizer: SpeechRecognizer?) {
        recognizer ?: return
        // destroy() is NOT main-thread-restricted (Finding B), but is routed the same way for
        // uniformity — the cost of consistency here is nil.
        instrumentation.runOnMainSync {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private fun baseIntent(
        completeSilenceMs: Int = COMPLETE_SILENCE_MS,
        possiblySilenceMs: Int = POSSIBLY_COMPLETE_SILENCE_MS,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, EN_US)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            completeSilenceMs,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            possiblySilenceMs,
        )
    }

    private fun rawAudioIntent(readEnd: ParcelFileDescriptor): Intent = baseIntent().apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
    }

    // ------------------------------------------------------------------------------------------
    // Stimulus, playback, reporting
    // ------------------------------------------------------------------------------------------

    /**
     * The asset lives in the androidTest APK, so it is read from the *instrumentation* context.
     * Copied out rather than read via `openFd` because `AssetManager.open` works regardless of
     * whether aapt chose to compress it, and both the MediaPlayer and the file-fed pump need a real
     * seekable file.
     */
    private fun copyStimulusToCache(): File {
        val destination = File(context.cacheDir, ProbeStimulus.ASSET_NAME)
        instrumentation.context.assets.open(ProbeStimulus.ASSET_NAME).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun stimulusMetadata(file: File): JSONObject {
        val pcm = ProbeWav.parse(file)
        return JSONObject()
            .put("asset", ProbeStimulus.ASSET_NAME)
            .put("bytes", file.length())
            .put("sampleRate", pcm.sampleRate)
            .put("channels", pcm.channels)
            .put("bitsPerSample", pcm.bitsPerSample)
            .put("durationMs", pcm.durationMs)
            .put("utteranceCount", ProbeStimulus.UTTERANCES.size)
            .put("gapMs", ProbeStimulus.GAP_MS)
            .put("transcriptWordCount", ProbeText.words(ProbeStimulus.TRANSCRIPT).size)
    }

    private fun maximiseMediaVolume(audioManager: AudioManager): JSONObject = JSONObject().apply {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        put("max", max)
        put("before", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }
            .onFailure { put("setError", "${it.javaClass.simpleName}: ${it.message}") }
        put("after", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    private fun startPlayback(file: File): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setDataSource(file.absolutePath)
        prepare()
        start()
    }

    private fun releasePlayer(player: MediaPlayer?) {
        player ?: return
        runCatching { if (player.isPlaying) player.stop() }
        runCatching { player.release() }
    }

    private fun deviceIdentity(): JSONObject = JSONObject().apply {
        put("MANUFACTURER", Build.MANUFACTURER)
        put("MODEL", Build.MODEL)
        put("DEVICE", Build.DEVICE)
        put("FINGERPRINT", Build.FINGERPRINT)
        put("SDK_INT", Build.VERSION.SDK_INT)
        put("SECURITY_PATCH", Build.VERSION.SECURITY_PATCH)
        // Plan 01's summary flagged that MODEL alone cannot tell the tester from the personal phone
        // — both are S22 Ultras, the documented naming footgun. ro.serialno can, so the artifact
        // now carries its own provenance instead of relying on the operator's notes.
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

    /**
     * Wraps one measurement so a failure costs only that measurement. A ~9-minute run on a remote
     * device is far too expensive to repeat because one branch threw, and a measurement that could
     * not be taken is a legitimate — and explicitly deferrable — result.
     */
    private fun measure(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (t: Throwable) {
        JSONObject().put("measurementError", "${t.javaClass.name}: ${t.message}")
    }

    private fun writeReport(part2: JSONObject) {
        val directory = requireNotNull(context.getExternalFilesDir(null)) {
            "external files dir unavailable — cannot write the probe report"
        }
        File(directory, REPORT_FILE_NAME).writeText(part2.toString(2))
    }

    private companion object {
        const val EN_US = "en-US"
        const val REPORT_FILE_NAME = "probe-report-part2.json"
        const val SAMPLE_RATE_HZ = 16_000

        /** `ERROR_CLIENT` — D-05's exact repro signature. */
        const val ERROR_CLIENT = 5

        // Both comfortably inside the stimulus's 3 s inter-utterance gaps.
        const val COMPLETE_SILENCE_MS = 1_500
        const val POSSIBLY_COMPLETE_SILENCE_MS = 1_200

        const val SEGMENTED_REAL = "real — onSegmentResults AND onEndOfSegmentedSession delivered"
        const val SEGMENTED_NO_OP = "silent-no-op — ended on onResults, no segmented callback ever fired"
        const val SEGMENTED_INCONCLUSIVE = "inconclusive — see the session event log"

        const val SEGMENTED_PLAY_MS = 45_000L
        const val TERMINAL_TIMEOUT_MS = 15_000L

        // The follow-up segmented run: same rung (b), only the silence bound differs.
        const val LONG_COMPLETE_SILENCE_MS = 8_000
        const val LONG_POSSIBLY_SILENCE_MS = 5_000
        const val SEGMENTED_LONG_PLAY_MS = 55_000L

        /** Skips the stimulus's 500 ms lead-in plus MediaPlayer prepare/start latency. */
        const val PLAYBACK_LEAD_IN_MS = 1_800L
        const val EXPLICIT_STOP_AFTER_MS = 6_000L
        const val FINAL_DELIVERY_TIMEOUT_MS = 25_000L

        /** ≥10 samples is the plan's floor; 14 cycles yields 13, with room for a failed cycle. */
        const val DEAF_WINDOW_CYCLES = 14
        const val DELAYED_RESTART_MS = 200L
        const val CYCLE_LISTEN_MS = 4_500L
        const val CYCLE_TERMINAL_MS = 12_000L
        const val CYCLE_RECOVERY_MS = 300L

        const val FEED_CHUNK_BYTES = 3_200 // 100 ms at 16 kHz mono 16-bit
        const val RAW_CLEAN_BUDGET_MS = 35_000L
        const val MID_UTTERANCE_CLOSE_MS = 8_000L
        const val POST_CLOSE_TIMEOUT_MS = 20_000L
        const val PUMP_JOIN_MS = 3_000L
        const val FILE_FEED_SLACK_MS = 20_000L

        const val LOOPBACK_BUDGET_MS = 90_000L
        const val LOOPBACK_SESSION_MS = 20_000L
    }
}

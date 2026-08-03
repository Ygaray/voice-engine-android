package io.github.ygaray.sttengine.probe

import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Support types for [RecognizerBehaviourProbeTest]. Deliberately holds **no** `SpeechRecognizer`
 * calls and **no** file descriptors — every lifecycle call and every closeable resource stays in the
 * test file where the main-thread and `finally` guarantees are auditable in one place.
 */

/**
 * Records everything one recognition session emits, with timestamps.
 *
 * All [RecognitionListener] callbacks are delivered on the **main looper** (RESEARCH Finding A —
 * `SpeechRecognizerImpl.InternalRecognitionListener` posts every incoming Binder call through a
 * `Handler(Looper.getMainLooper())`), and callback ordering is FIFO and total. So these counters are
 * plain fields with no synchronization; the single crossing to the test thread is [terminal].
 */
internal class ProbeRecorder(val label: String) {

    val startedAtMs: Long = SystemClock.elapsedRealtime()

    val events: MutableList<JSONObject> = mutableListOf()
    val partials: MutableList<String> = mutableListOf()
    val finals: MutableList<String> = mutableListOf()
    val segmentTexts: MutableList<String> = mutableListOf()
    val segmentAtMs: MutableList<Long> = mutableListOf()
    val errors: MutableList<Int> = mutableListOf()

    var readyAtMs: Long? = null
    var beginningOfSpeechAtMs: Long? = null
    var terminalAtMs: Long? = null
    var terminalKind: String? = null
    var endOfSegmentedSession: Boolean = false
    var onResultsCount: Int = 0
    var rmsCount: Int = 0
    var maxRmsDb: Float = Float.NEGATIVE_INFINITY

    /** Counts down on the FIRST terminal callback (`onResults`/`onError`/`onEndOfSegmentedSession`). */
    val terminal: CountDownLatch = CountDownLatch(1)

    val listener: RecognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            readyAtMs = SystemClock.elapsedRealtime()
            log("readyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            beginningOfSpeechAtMs = SystemClock.elapsedRealtime()
            log("beginningOfSpeech")
        }

        // Deliberately NOT logged as an event: it fires many times a second and would bury the
        // report. The max is kept because it is the one direct answer to "did the microphone
        // actually hear the speaker?" — load-bearing for the loopback verdict.
        override fun onRmsChanged(rmsdB: Float) {
            rmsCount++
            if (rmsdB > maxRmsDb) maxRmsDb = rmsdB
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = log("endOfSpeech")

        override fun onError(error: Int) {
            errors += error
            log("error", error)
            markTerminal("onError($error)")
        }

        override fun onResults(results: Bundle?) {
            onResultsCount++
            val text = firstResult(results)
            finals += text
            log("results", text)
            markTerminal("onResults")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            partials += text
            log("partial", text)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val text = firstResult(segmentResults)
            segmentTexts += text
            segmentAtMs += SystemClock.elapsedRealtime()
            finals += text
            log("segmentResults", text)
        }

        override fun onEndOfSegmentedSession() {
            endOfSegmentedSession = true
            log("endOfSegmentedSession")
            markTerminal("onEndOfSegmentedSession")
        }

        override fun onEvent(eventType: Int, params: Bundle?) = log("event", eventType)
    }

    private fun markTerminal(kind: String) {
        if (terminalAtMs == null) {
            terminalAtMs = SystemClock.elapsedRealtime()
            terminalKind = kind
        }
        terminal.countDown()
    }

    private fun log(kind: String, detail: Any? = null) {
        events += JSONObject()
            .put("t", SystemClock.elapsedRealtime() - startedAtMs)
            .put("kind", kind)
            .apply { if (detail != null) put("detail", detail) }
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    /** Wall-clock gaps between consecutive `onSegmentResults`, in ms. */
    fun segmentGapsMs(): List<Long> =
        segmentAtMs.zipWithNext { a, b -> b - a }

    /** The concatenated recognized text — segment results in segmented mode, finals otherwise. */
    fun recognizedText(): String = finals.filter { it.isNotBlank() }.joinToString(" ")

    fun toJson(includeEvents: Boolean = true): JSONObject = JSONObject().apply {
        put("label", label)
        // Counts are complete; the text/event lists are capped. The report is a committed planning
        // artifact that a human has to read — an uncapped file-fed session would bury the six
        // answers under a few hundred incremental partials.
        put("readyAtMs", readyAtMs ?: JSONObject.NULL)
        put("beginningOfSpeechAtMs", beginningOfSpeechAtMs ?: JSONObject.NULL)
        put("terminalKind", terminalKind ?: JSONObject.NULL)
        put("terminalAfterMs", terminalAtMs?.minus(startedAtMs) ?: JSONObject.NULL)
        put("partialCount", partials.size)
        put("finalCount", finals.size)
        put("segmentResultsCount", segmentTexts.size)
        put("endOfSegmentedSession", endOfSegmentedSession)
        put("onResultsCount", onResultsCount)
        put("errorCodes", JSONArray(errors))
        put("rmsCallbackCount", rmsCount)
        put(
            "maxRmsDb",
            if (maxRmsDb == Float.NEGATIVE_INFINITY) JSONObject.NULL else maxRmsDb.toDouble(),
        )
        put("partials", capped(partials))
        put("segmentTexts", capped(segmentTexts))
        put("recognizedText", recognizedText())
        if (includeEvents) put("events", JSONArray(events.takeLast(MAX_EVENTS)))
        if (events.size > MAX_EVENTS) put("eventsTruncatedFrom", events.size)
    }

    private fun capped(values: List<String>): JSONArray =
        JSONArray(values.takeLast(MAX_TEXTS)).apply {
            if (values.size > MAX_TEXTS) put("…(${values.size - MAX_TEXTS} earlier entries omitted)")
        }

    private companion object {
        const val MAX_TEXTS = 30
        const val MAX_EVENTS = 200
    }
}

/** Word-level similarity scoring against the committed transcript. */
internal object ProbeText {

    private val NON_WORD = Regex("[^a-z0-9' ]")

    fun words(text: String): List<String> =
        NON_WORD.replace(text.lowercase().replace('’', '\''), " ")
            .split(' ')
            .filter { it.isNotBlank() }

    /** Longest common subsequence length over word sequences. */
    fun lcs(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.size + 1)
        var cur = IntArray(b.size + 1)
        for (i in a.indices) {
            for (j in b.indices) {
                cur[j + 1] = if (a[i] == b[j]) prev[j] + 1 else maxOf(prev[j + 1], cur[j])
            }
            val swap = prev
            prev = cur
            cur = swap
            java.util.Arrays.fill(cur, 0)
        }
        return prev[b.size]
    }

    /** Word-level edit distance. */
    fun levenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = cur.copyOf()
        }
        return prev[b.size]
    }

    /**
     * Scores a hypothesis against the reference with **both** precision and recall, rather than a
     * single "similarity" number.
     *
     * That split is what makes a partial-playback run interpretable: a measurement that only played
     * 45 s of a 130 s stimulus SHOULD score low recall, and reading that as failure would be wrong.
     * Precision — "is what came out actually our text" — is the number that answers whether the
     * stimulus path works at all, independent of how much of it was played.
     */
    fun score(reference: String, hypothesis: String): JSONObject {
        val ref = words(reference)
        val hyp = words(hypothesis)
        val common = lcs(ref, hyp)
        return JSONObject().apply {
            put("referenceWordCount", ref.size)
            put("hypothesisWordCount", hyp.size)
            put("commonWordsInOrder", common)
            put("precision", if (hyp.isEmpty()) 0.0 else round3(common.toDouble() / hyp.size))
            put("recall", if (ref.isEmpty()) 0.0 else round3(common.toDouble() / ref.size))
        }
    }

    fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    fun medianOf(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    fun statsOf(values: List<Long>): JSONObject = JSONObject().apply {
        put("samples", JSONArray(values))
        put("count", values.size)
        put("minMs", values.minOrNull() ?: JSONObject.NULL)
        put("medianMs", medianOf(values) ?: JSONObject.NULL)
        put("maxMs", values.maxOrNull() ?: JSONObject.NULL)
    }
}

/**
 * Locates the PCM payload inside a RIFF/WAVE file.
 *
 * The probe cannot assume the canonical 44-byte header: Android's own `TextToSpeech` writer and the
 * host stitcher may emit extra chunks (`LIST`, `fact`), and feeding a header — or a few stray bytes
 * of one — into `EXTRA_AUDIO_SOURCE` would corrupt the first frames of the very measurement that
 * decides the AND-02 stimulus.
 */
internal object ProbeWav {

    data class Pcm(
        val dataOffset: Long,
        val dataLength: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    ) {
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond == 0L) 0 else dataLength * 1000 / bytesPerSecond
            }
    }

    fun parse(file: File): Pcm {
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") {
                "${file.name} is not a RIFF/WAVE file"
            }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var pos = 12L
            val length = raf.length()
            while (pos + 8 <= length) {
                raf.seek(pos)
                val header = ByteArray(8)
                raf.readFully(header)
                val id = String(header, 0, 4)
                val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    .toLong() and 0xFFFFFFFFL
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.seek(body)
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short // audio format
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int // byte rate
                        bb.short // block align
                        bits = bb.short.toInt()
                    }

                    "data" -> return Pcm(body, minOf(size, length - body), sampleRate, channels, bits)
                }
                // Chunks are word-aligned: an odd size carries a pad byte.
                pos = body + size + (size and 1L)
            }
            error("${file.name} has no data chunk")
        }
    }
}

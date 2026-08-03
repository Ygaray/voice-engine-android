package io.github.ygaray.sttengine.internal

import android.content.Context
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import io.github.ygaray.sttengine.SttConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rung → intent extras (D-01, RESEARCH §Pattern 4). The whole difference between the three rungs is
 * this one function, so its output is asserted key-by-key rather than spot-checked.
 *
 * Robolectric, because `Intent` extras are a real platform behaviour and `Intent.getExtras()` is not
 * a data structure worth faking.
 *
 * **Pinned to SDK 35**, and the pin is load-bearing in both directions:
 *  - *not 36* — Robolectric refuses SDK 36 on anything below Java 21
 *    (`Android SDK 36 requires Java 21 (have Java 17)`), and this module targets Java 17
 *    deliberately. Raising the toolchain to satisfy a test fixture would put an unproven JVM in
 *    front of the release path for zero benefit.
 *  - *not 33* — that image is not cached on this host, and the suite must run offline.
 *
 * SDK 35 is cached, runs on Java 17, and is the tester's own API level (SM-S908U, Android 15). The
 * module's `minSdk` is 33 and every extra used here has been available since 33, so the SDK level
 * under test is a fixture detail rather than part of what is being proven.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizerIntentsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun keysOf(rung: Rung, audioSource: ParcelFileDescriptor? = null): Set<String> =
        RecognizerIntents.forRung(rung, SttConfig(), context, audioSource).extras!!.keySet()

    /** The five extras every rung carries, whatever else it adds. */
    private val commonKeys = setOf(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.EXTRA_LANGUAGE,
        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
        RecognizerIntent.EXTRA_CALLING_PACKAGE,
        RecognizerIntent.EXTRA_PREFER_OFFLINE,
    )

    private val silenceKeys = setOf(
        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
    )

    // --- common to every rung -------------------------------------------------------------------

    @Test
    fun `every rung's intent carries the recognize-speech action and the five common extras`() {
        for (rung in Rung.entries) {
            val source = if (rung == Rung.RawAudioSegmented) ParcelFileDescriptor.createPipe()[0] else null
            try {
                val intent = RecognizerIntents.forRung(rung, SttConfig(), context, source)
                assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
                assertTrue("$rung is missing a common extra", intent.extras!!.keySet().containsAll(commonKeys))
                assertEquals(
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
                )
                assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
                assertTrue(
                    "prefer-offline is belt and braces on top of the on-device factory",
                    intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false),
                )
                assertEquals(
                    context.packageName,
                    intent.getStringExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE),
                )
            } finally {
                source?.close()
            }
        }
    }

    @Test
    fun `the language comes from the supplied config, not a hardcoded string`() {
        val intent = RecognizerIntents.forRung(Rung.RestartingSingleSession, SttConfig(language = "es"), context)
        assertEquals("es", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        val default = RecognizerIntents.forRung(Rung.RestartingSingleSession, SttConfig(), context)
        assertEquals("en", default.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    // --- (a) RestartingSingleSession — the terminal rung -----------------------------------------

    @Test
    fun `the terminal rung's intent carries exactly the common plus both silence extras`() {
        assertEquals(commonKeys + silenceKeys, keysOf(Rung.RestartingSingleSession))
    }

    @Test
    fun `the terminal rung's intent omits the segmented-session and audio-source extras`() {
        val keys = keysOf(Rung.RestartingSingleSession)
        assertFalse(
            "rung (a) is the classic restart loop — a segmented extra here would change what it IS",
            keys.contains(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
        )
        for (audioKey in audioSourceKeys) {
            assertFalse("rung (a) does not own the microphone: $audioKey", keys.contains(audioKey))
        }
    }

    @Test
    fun `the terminal rung's silence extras carry the named tunable constants`() {
        val intent = RecognizerIntents.forRung(Rung.RestartingSingleSession, SttConfig(), context)
        assertEquals(
            RecognizerIntents.RESTART_COMPLETE_SILENCE_MS,
            intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1),
        )
        assertEquals(
            RecognizerIntents.POSSIBLY_COMPLETE_SILENCE_MS,
            intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1),
        )
    }

    // --- (b) SilenceSegmented -------------------------------------------------------------------

    @Test
    fun `the silence-segmented rung carries everything the terminal rung does plus the segmented extra`() {
        assertEquals(commonKeys + silenceKeys + RecognizerIntent.EXTRA_SEGMENTED_SESSION, keysOf(Rung.SilenceSegmented))
        assertTrue(
            "rung (b) must be a superset of rung (a)'s keys",
            keysOf(Rung.SilenceSegmented).containsAll(keysOf(Rung.RestartingSingleSession)),
        )
    }

    @Test
    fun `the silence-segmented rung's segmented-session value names an extra present in the same intent`() {
        val intent = RecognizerIntents.forRung(Rung.SilenceSegmented, SttConfig(), context)
        val referencedKey = intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION)

        assertEquals(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            referencedKey,
        )
        assertTrue(
            "the javadoc REQUIRES the referenced key be populated in the same intent — that is why " +
                "the value is a key name rather than a duration",
            intent.hasExtra(referencedKey!!),
        )
    }

    @Test
    fun `the silence-segmented rung's session bound is generous, not a per-utterance endpointer`() {
        val intent = RecognizerIntents.forRung(Rung.SilenceSegmented, SttConfig(), context)
        val bound = intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1)

        assertEquals(RecognizerIntents.SEGMENTED_SESSION_COMPLETE_SILENCE_MS, bound)
        assertTrue(
            "MEASURED (05-RUNG-VERDICT.md): this value bounds the WHOLE SESSION, not a segment. " +
                "At 1500 ms the session died after 1.8 s with one segment; at 8000 ms the same code " +
                "ran 57 s across 13 segments with ZERO restarts. A value tuned as if it were an " +
                "endpointer silently truncates sessions and looks like a rung-(b) failure.",
            bound >= 8_000,
        )
        assertTrue(
            "and it must exceed rung (a)'s per-utterance value, or rung (b) buys nothing",
            bound > RecognizerIntents.RESTART_COMPLETE_SILENCE_MS,
        )
    }

    // --- (c) RawAudioSegmented ------------------------------------------------------------------

    @Test
    fun `the raw-audio rung carries the audio-source descriptor and its three format extras`() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = RecognizerIntents.forRung(Rung.RawAudioSegmented, SttConfig(), context, pipe[0])

            assertEquals(commonKeys + audioSourceKeys + RecognizerIntent.EXTRA_SEGMENTED_SESSION, intent.extras!!.keySet())
            assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, -1))
            assertEquals(
                AudioFormat.ENCODING_PCM_16BIT,
                intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, -1),
            )
            assertEquals(16_000, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, -1))
            assertEquals(RecognizerIntents.AUDIO_SOURCE_SAMPLE_RATE_HZ, 16_000)
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun `the raw-audio rung's segmented-session extra names the audio-source key`() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = RecognizerIntents.forRung(Rung.RawAudioSegmented, SttConfig(), context, pipe[0])
            val referencedKey = intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION)

            assertEquals(RecognizerIntent.EXTRA_AUDIO_SOURCE, referencedKey)
            assertTrue(intent.hasExtra(referencedKey!!))
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the raw-audio rung refuses to build an intent without an audio source`() {
        // Silently emitting a rung-(c) intent with no descriptor would produce a session that looks
        // like rung (b) and degrades for the wrong reason. Fail where the mistake is, not later.
        RecognizerIntents.forRung(Rung.RawAudioSegmented, SttConfig(), context, audioSource = null)
    }

    private val audioSourceKeys = setOf(
        RecognizerIntent.EXTRA_AUDIO_SOURCE,
        RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
        RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
        RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
    )
}

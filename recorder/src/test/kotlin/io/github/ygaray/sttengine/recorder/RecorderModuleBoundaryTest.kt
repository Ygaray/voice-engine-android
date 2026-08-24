package io.github.ygaray.sttengine.recorder

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The JUnit-level proof that `:recorder`'s own test/compile classpath carries zero `:stt`
 * (recognition) code — REC-05's structural guarantee, exercised the way
 * `ReleaseAudioSourceSeamTest` exercises the debug-seam guarantee in `:stt`.**
 *
 * `verifyNoRecognitionDependency` (a Gradle task in `recorder/build.gradle.kts`) and
 * `check-no-recognition-dependency.sh` (a static grep) both already assert this at build time.
 * Nyquist gap analysis on this rework flagged that neither is a JUnit test runnable via
 * `:recorder:testDebugUnitTest`/`testReleaseUnitTest` the way this repo's established precedent
 * (`ReleaseAudioSourceSeamTest`) asserts an equivalent boundary — so this suite adds that layer:
 * a real classloader probe plus a cross-check against both frozen API dumps, runnable by the same
 * command that runs every other Robolectric test in this module.
 *
 * ## What each test rules out
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [recognitionOnlyClassesAreUnreachableFromRecorderClasspath] | `:recorder`'s own compiled/test classpath can load a `:stt`-only symbol (a real dependency edge, not just declared source text) |
 * | [buildScriptNamesNoSttReference] | `recorder/build.gradle.kts` re-declares `implementation(project(":stt"))` (or the published coordinate) in source text |
 * | [frozenRecorderApiNamesNoRecognitionSymbol] | `:recorder`'s own published surface accidentally widens to include a recognition type |
 * | [frozenSttApiNamesNoRecorderSymbol] | `:stt`'s frozen surface still names a Recorder symbol post-move (an incomplete extraction) |
 */
class RecorderModuleBoundaryTest {

    /** Fully-qualified names that exist ONLY in `:stt` (recognition), never in `:recorder`. */
    private val recognitionOnlyClasses = listOf(
        "io.github.ygaray.sttengine.SttEngine",
        "io.github.ygaray.sttengine.EngineState",
        "io.github.ygaray.sttengine.SttEngineKt",
    )

    /** A class that genuinely IS on `:recorder`'s own classpath — the vacuous-pass-prevention check. */
    private val recorderOwnClass = "io.github.ygaray.sttengine.recorder.Recorder"

    // --- the load-bearing one -------------------------------------------------------------------

    /**
     * `:recorder`'s own test/compile classpath cannot resolve a single `:stt`-only class.
     *
     * This is the runtime analogue of `verifyNoRecognitionDependency`'s resolved-graph inspection,
     * but proven from inside a running JVM on the exact classpath a `:recorder`-only consumer would
     * get, rather than by asking Gradle's dependency-resolution API about it. If a future edit ever
     * re-adds `implementation(project(":stt"))` (or the published coordinate), this class becomes
     * loadable and this test goes red — independent of whether the Gradle task or the shell script
     * are ever run.
     */
    @Test
    fun recognitionOnlyClassesAreUnreachableFromRecorderClasspath() {
        // Vacuous-pass-prevention: prove this JVM's classloader can resolve something real from
        // :recorder's own package BEFORE trusting a ClassNotFoundException on the recognition side.
        val ownClassLoaded = runCatching { Class.forName(recorderOwnClass) }
        assertTrue(
            "expected $recorderOwnClass to be loadable from :recorder's own classpath — if it is " +
                "not, this test's classloader is broken and every assertion below would pass " +
                "vacuously. Cause: ${ownClassLoaded.exceptionOrNull()}",
            ownClassLoaded.isSuccess,
        )

        for (name in recognitionOnlyClasses) {
            val loaded = runCatching { Class.forName(name) }
            assertTrue(
                "\"$name\" resolved on :recorder's own test classpath. :recorder must never carry " +
                    "a real dependency edge to :stt (T-08-10, REC-05) — this class is :stt-only, so " +
                    "its presence here means a :stt dependency leaked back into :recorder.",
                loaded.exceptionOrNull() is ClassNotFoundException,
            )
        }
    }

    // --- the two belt-and-suspenders static checks, made runnable as JUnit too -------------------

    /** `recorder/build.gradle.kts` names no `:stt` dependency in its own source text. */
    @Test
    fun buildScriptNamesNoSttReference() {
        val buildFile = File(moduleDir(), "build.gradle.kts")
        assertTrue("expected build.gradle.kts at $buildFile", buildFile.isFile)

        val cleaned = buildFile.readLines()
            .joinToString("\n") { it.replace(Regex("""//.*$"""), "") }

        assertTrue(
            "expected the build script to at least mention 'recorder' — otherwise this isn't " +
                "reading the file this test thinks it is",
            cleaned.contains("recorder", ignoreCase = true),
        )

        val joined = cleaned.replace(Regex("""\s+"""), " ")
        assertFalse(
            "recorder/build.gradle.kts declares a real dependency on :stt " +
                "(project(\":stt\") or the published coordinate). Remove it — this is the exact " +
                "regression T-08-10/REC-05 exists to prevent.",
            Regex("""project\(\s*":stt"\s*\)""").containsMatchIn(joined) ||
                joined.contains("com.github.Ygaray:stt-engine-android"),
        )
    }

    /** `:recorder`'s own frozen API dump names only Recorder symbols, never a recognition one. */
    @Test
    fun frozenRecorderApiNamesNoRecognitionSymbol() {
        val dump = File(moduleDir(), "api/recorder.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the dump does not name Recorder, so this isn't the dump this test thinks it is " +
                "reading and every assertion below would pass vacuously",
            text.contains("sttengine/recorder/Recorder"),
        )

        for (symbol in listOf("SttEngine", "EngineState", "FinalSegment", "SttConfig")) {
            assertFalse(
                "\"$symbol\" appears in :recorder's frozen API dump — the Recorder module's " +
                    "published surface has picked up a recognition symbol.",
                text.contains(symbol),
            )
        }
    }

    /** `:stt`'s frozen API dump, post-extraction, names no Recorder symbol. */
    @Test
    fun frozenSttApiNamesNoRecorderSymbol() {
        val dump = File(sttModuleDir(), "api/stt.api")
        assertTrue("expected :stt's frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the dump does not name createSttEngine, so this isn't the dump this test thinks it " +
                "is reading and every assertion below would pass vacuously",
            text.contains("createSttEngine"),
        )

        for (symbol in listOf("sttengine/recorder/Recorder", "RecordedFile", "RecorderState")) {
            assertFalse(
                "\"$symbol\" still appears in :stt's frozen API dump post-extraction — the move " +
                    "of the Recorder package out of :stt is incomplete.",
                text.contains(symbol),
            )
        }
    }

    // --- plumbing -------------------------------------------------------------------------------

    /** The `:recorder` module directory, found by walking up from this test's class output. */
    private fun moduleDir(): File {
        val start = javaClass.protectionDomain?.codeSource?.location?.path
        assertTrue("cannot locate this test's own class output", start != null)
        val marker = "src/main/kotlin/io/github/ygaray/sttengine/recorder/Recorder.kt"

        var candidate: File? = File(start!!).absoluteFile
        while (candidate != null) {
            if (File(candidate, marker).isFile) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("walked up from \"$start\" without finding a directory containing $marker")
    }

    /** The sibling `:stt` module directory, found relative to `:recorder`'s own directory. */
    private fun sttModuleDir(): File {
        val recorder = moduleDir()
        val stt = File(recorder.parentFile, "stt")
        assertTrue("expected a sibling :stt module directory at $stt", stt.isDirectory)
        return stt
    }
}

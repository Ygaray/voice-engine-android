package io.github.ygaray.sttengine.transcription

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The JUnit-level proof that `:transcription`'s own test/compile classpath carries zero
 * `:stt`/`:recorder`/`:voicenotes` code — HUB-01's structural isolation guarantee, exercised the
 * way `RecorderModuleBoundaryTest` exercises the equivalent `:recorder` guarantee.**
 *
 * `verifyIndependentModule` (a Gradle task in `transcription/build.gradle.kts`) and
 * `verify-no-cross-module-dependency.sh` (a static grep) both already assert this at build time.
 * This suite adds the JUnit layer: a real classloader probe plus a cross-check against the frozen
 * API dump, runnable by the same command that runs every other test in this module.
 *
 * ## What each test rules out
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [classesUnreachableFromTranscriptionClasspath] | `:transcription`'s own compiled/test classpath can load a `:stt`/`:recorder`/`:voicenotes`-only symbol (a real dependency edge, not just declared source text) |
 * | [buildScriptNamesNoForbiddenDependency] | `transcription/build.gradle.kts` re-declares `project(":stt"\|":recorder"\|":voicenotes")` (or a published coordinate) in source text |
 * | [frozenTranscriptionApiNamesNoForbiddenSymbol] | `:transcription`'s own published surface accidentally widens to include a `:stt`/`:recorder`/`:voicenotes` symbol |
 *
 * Unlike `RecorderModuleBoundaryTest`, this suite has no reverse "sibling module's dump names no
 * Transcription symbol" check — that check exists there because `:recorder` was MOVED OUT of
 * `:stt` (an incomplete-extraction risk to a *pre-existing* module's frozen dump).
 * `:transcription`'s source was never itself a public `:voicenotes` symbol (it was always
 * `internal`), so there is no equivalent stale-symbol risk in a sibling's dump to prove absent.
 */
class TranscriptionModuleBoundaryTest {

    /** Fully-qualified names that exist ONLY in `:stt`/`:recorder`/`:voicenotes`, never here. */
    private val forbiddenClasses = listOf(
        "io.github.ygaray.sttengine.SttEngine",
        "io.github.ygaray.sttengine.recorder.Recorder",
        "io.github.ygaray.sttengine.voicenotes.VoiceNotes",
    )

    /** A class that genuinely IS on `:transcription`'s own classpath — the vacuous-pass check. */
    private val ownClass = "io.github.ygaray.sttengine.transcription.TranscriptionErrorCode"

    // --- the load-bearing one -------------------------------------------------------------------

    /**
     * `:transcription`'s own test/compile classpath cannot resolve a single forbidden class.
     *
     * This is the runtime analogue of `verifyIndependentModule`'s resolved-graph inspection, but
     * proven from inside a running JVM on the exact classpath a `:transcription`-only consumer
     * would get, rather than by asking Gradle's dependency-resolution API about it. If a future
     * edit ever re-adds `implementation(project(":stt"))` (or a published coordinate), one of
     * these classes becomes loadable and this test goes red — independent of whether the Gradle
     * task or the shell script are ever run.
     */
    @Test
    fun classesUnreachableFromTranscriptionClasspath() {
        // Vacuous-pass-prevention: prove this JVM's classloader can resolve something real from
        // :transcription's own package BEFORE trusting a ClassNotFoundException on the other side.
        val ownClassLoaded = runCatching { Class.forName(ownClass) }
        assertTrue(
            "expected $ownClass to be loadable from :transcription's own classpath — if it is " +
                "not, this test's classloader is broken and every assertion below would pass " +
                "vacuously. Cause: ${ownClassLoaded.exceptionOrNull()}",
            ownClassLoaded.isSuccess,
        )

        for (name in forbiddenClasses) {
            val loaded = runCatching { Class.forName(name) }
            assertTrue(
                "\"$name\" resolved on :transcription's own test classpath. :transcription must " +
                    "never carry a real dependency edge to :stt/:recorder/:voicenotes (HUB-01) — " +
                    "this class is forbidden-module-only, so its presence here means a dependency " +
                    "leaked back into :transcription.",
                loaded.exceptionOrNull() is ClassNotFoundException,
            )
        }
    }

    // --- the belt-and-suspenders static check, made runnable as JUnit too --------------------

    /** `transcription/build.gradle.kts` names no forbidden dependency in its own source text. */
    @Test
    fun buildScriptNamesNoForbiddenDependency() {
        val buildFile = File(moduleDir(), "build.gradle.kts")
        assertTrue("expected build.gradle.kts at $buildFile", buildFile.isFile)

        val cleaned = buildFile.readLines()
            .joinToString("\n") { it.replace(Regex("""//.*$"""), "") }

        assertTrue(
            "expected the build script to at least mention 'transcription' — otherwise this " +
                "isn't reading the file this test thinks it is",
            cleaned.contains("transcription", ignoreCase = true),
        )

        val joined = cleaned.replace(Regex("""\s+"""), " ")
        assertFalse(
            "transcription/build.gradle.kts declares a real dependency on :stt/:recorder/" +
                ":voicenotes (project(...) or the published coordinate). Remove it — this is the " +
                "exact regression HUB-01 exists to prevent.",
            Regex("""project\(\s*":(stt|recorder|voicenotes)"\s*\)""").containsMatchIn(joined) ||
                joined.contains("com.github.Ygaray:voice-engine-android"),
        )

        // IN-01 (136-REVIEW.md): parity with verify-no-cross-module-dependency.sh, which already
        // greps for these two coordinate prefixes alongside the project(...) checks above. The
        // authoritative enforcement is verifyIndependentModule's resolved-graph inspection; this
        // is the static-scan layer staying consistent with its sibling shell script.
        assertFalse(
            "transcription/build.gradle.kts declares a real dependency on Room or WorkManager " +
                "(androidx.room:/androidx.work:). :transcription must never depend on either (HUB-01).",
            Regex("""androidx\.room:|androidx\.work:""").containsMatchIn(joined),
        )
    }

    /** `:transcription`'s own frozen API dump names only Transcription symbols, never a forbidden one. */
    @Test
    fun frozenTranscriptionApiNamesNoForbiddenSymbol() {
        val dump = File(moduleDir(), "api/transcription.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the dump does not name TranscriptionClient, so this isn't the dump this test thinks " +
                "it is reading and every assertion below would pass vacuously",
            text.contains("sttengine/transcription/TranscriptionClient"),
        )

        for (symbol in listOf("sttengine/SttEngine", "sttengine/recorder/Recorder", "sttengine/voicenotes/VoiceNotes")) {
            assertFalse(
                "\"$symbol\" appears in :transcription's frozen API dump — the module's " +
                    "published surface has picked up a forbidden-module symbol.",
                text.contains(symbol),
            )
        }
    }

    // --- plumbing -------------------------------------------------------------------------------

    /** The `:transcription` module directory, found by walking up from this test's class output. */
    private fun moduleDir(): File {
        val start = javaClass.protectionDomain?.codeSource?.location?.path
        assertTrue("cannot locate this test's own class output", start != null)
        val marker = "src/main/kotlin/io/github/ygaray/sttengine/transcription/TranscriptionClient.kt"

        var candidate: File? = File(start!!).absoluteFile
        while (candidate != null) {
            if (File(candidate, marker).isFile) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("walked up from \"$start\" without finding a directory containing $marker")
    }
}

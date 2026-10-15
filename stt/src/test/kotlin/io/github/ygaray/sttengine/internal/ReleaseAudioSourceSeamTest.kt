package io.github.ygaray.sttengine.internal

import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * **The proof that the debug audio-source seam cannot change a shipped build.**
 *
 * The seam lets a debug build feed rung (c) a fixed WAV instead of the microphone, so AND-02's
 * evidence run is deterministic rather than depending on an acoustic loopback that measured recall
 * 0.559. That is worth having, but it puts a mutable cell —
 * [DebugAudioSourceHolder.factory] — in the read path of the one class in this library that owns the
 * user's microphone. The question this file exists to answer is not "do we believe that is safe"
 * but "can a release build reach it at all".
 *
 * ## Why this suite is trustworthy rather than reassuring
 *
 * This class is in `src/test/`, which AGP compiles and runs against **both** variants:
 * `testDebugUnitTest` and `testReleaseUnitTest`. The release unit-test compilation sees `main` and
 * `src/release` and **not** `src/debug` — the same source-set arithmetic that produces the published
 * AAR. So [theSeamClassesExistOnlyInTheDebugVariant] is not a grep and not an assertion about
 * intent: it asks the running classloader for the seam's classes and requires them to be **absent**
 * when this same file runs under the release variant.
 *
 * Two further properties keep the suite from passing vacuously:
 *
 * - Every assertion that could pass by finding nothing also asserts it found the thing it was
 *   looking for. If the seam is deleted, renamed or moved, this suite goes red rather than green.
 * - The variant is resolved from the test class's own code-source path, and a variant that cannot be
 *   resolved is a hard failure. A suite that quietly stopped knowing which variant it was in would
 *   be exactly the "guard that silently stops guarding" this module's CLAUDE.md warns about.
 *
 * ## What each test rules out
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [theSeamClassesExistOnlyInTheDebugVariant] | The WAV feed ships inside the AAR |
 * | [theHolderIsWrittenOnlyFromTheDebugSourceSet] | Some future `main` code arms the seam, so a shipped build reads a file |
 * | [theHolderDeclaresItselfNull] | The cell ships pre-armed |
 * | [aNullHolderResolvesToTheMicrophone] | The seam changes rung (c)'s behaviour even when unarmed |
 * | [armingSwapsTheSourceAndDisarmingRestoresIt] | The seam does not actually work, so the whole exercise is theatre |
 * | [aNullHolderResolvesServerBackendToTheMicrophone] | The server path reads anything but the mic when unarmed (every release build) |
 * | [armingSwapsServerBackendSourceAndDisarmingRestoresIt] | A file-fed Gate-1 run silently uses the mic on the server path |
 * | [anExplicitServerBackendFactoryIsNeverOverriddenByTheHolder] | The seam changes what the server-path suites exercise |
 * | [theFrozenApiDumpNamesNoSeamSymbol] | The seam widens the published binary contract |
 */
class ReleaseAudioSourceSeamTest {

    /** Fully-qualified names of everything that exists only to serve the debug seam. */
    private val debugOnlyClasses = listOf(
        "io.github.ygaray.sttengine.debug.DebugAudioSource",
        "io.github.ygaray.sttengine.debug.PacedWavFeed",
        "io.github.ygaray.sttengine.debug.WavPcm16Reader",
    )

    private var savedFactory: PcmSourceFactory? = null

    @Before
    fun saveHolder() {
        savedFactory = DebugAudioSourceHolder.factory
    }

    @After
    fun restoreHolder() {
        DebugAudioSourceHolder.factory = savedFactory
    }

    // --- the load-bearing one -------------------------------------------------------------------

    /**
     * The seam's classes are on the classpath under the debug variant and **absent** under release.
     *
     * This is the whole deliverable. `api/stt.api` proves the seam did not widen the *public* ABI;
     * this proves the code is not in the release compilation at all, which is the stronger and more
     * useful statement — a private class that shipped could still be reached by reflection, and a
     * `main`-side cell that some future edit armed would still be reading a file on a user's phone.
     */
    @Test
    fun theSeamClassesExistOnlyInTheDebugVariant() {
        val variant = resolveVariant()
        for (name in debugOnlyClasses) {
            val loaded = runCatching { Class.forName(name) }
            when (variant) {
                Variant.DEBUG -> assertTrue(
                    "$name must be present in the DEBUG compilation — the seam is what makes a " +
                        "deterministic AND-02 run possible, so its absence here means the debug " +
                        "source set stopped being compiled and every other assertion below is " +
                        "passing vacuously. Cause: ${loaded.exceptionOrNull()}",
                    loaded.isSuccess,
                )

                Variant.RELEASE -> assertTrue(
                    "$name MUST NOT exist in the RELEASE compilation. It resolved, which means the " +
                        "seam has leaked out of stt/src/debug/ and into the published AAR: a " +
                        "shipped build can now be made to read audio from a file instead of the " +
                        "user's microphone.",
                    loaded.exceptionOrNull() is ClassNotFoundException,
                )
            }
        }
    }

    // --- nothing outside src/debug/ can arm it ---------------------------------------------------

    /**
     * Every assignment to the holder in the entire module lives under `stt/src/debug/`.
     *
     * [theSeamClassesExistOnlyInTheDebugVariant] proves the *feed* cannot ship. This proves the
     * *cell* cannot be armed by anything that does, which is the other half: the cell itself is in
     * `main` (it has to be — [AudioSourcePipe]'s default argument reads it) and would be writable
     * from release code if anyone ever added such a write.
     */
    @Test
    fun theHolderIsWrittenOnlyFromTheDebugSourceSet() {
        val sourceRoot = File(moduleDir(), "src")
        val debugRoot = File(sourceRoot, "debug")
        assertTrue(
            "expected the debug source set at $debugRoot — without it this test proves nothing",
            debugRoot.isDirectory,
        )

        // Only the source sets that CONTRIBUTE TO A VARIANT. `test`/`androidTest` are excluded
        // because nothing in them is compiled into the AAR — this very file arms the holder, and
        // counting that as a leak would make the assertion impossible to satisfy honestly.
        val shipping = listOf("main", "release", "debug")
            .map { File(sourceRoot, it) }
            .filter { it.isDirectory }
        assertTrue("expected at least src/main and src/debug under $sourceRoot", shipping.size >= 2)

        val assignment = Regex("""DebugAudioSourceHolder\.factory\s*=""")
        val writers = shipping
            .flatMap { root -> root.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { assignment.containsMatchIn(it.readText()) }
            .map { it.absoluteFile }

        assertTrue(
            "found no assignment to DebugAudioSourceHolder.factory in any shipping source set. " +
                "Either the seam was removed (delete this suite too) or it was renamed and this " +
                "guard has silently stopped guarding.",
            writers.isNotEmpty(),
        )

        val strays = writers.filterNot { it.path.startsWith(debugRoot.absolutePath + File.separator) }
        assertEquals(
            "these files arm the debug audio source from OUTSIDE stt/src/debug/, so a release " +
                "build could feed rung (c) from a file instead of the microphone: $strays",
            emptyList<File>(),
            strays,
        )
    }

    /**
     * The cell's declaration initialises it to `null`, so an un-armed build is a microphone build.
     *
     * Asserted against the source rather than the runtime value because a runtime read only tells us
     * what some earlier test left behind; the declaration is what a fresh process starts from.
     */
    @Test
    fun theHolderDeclaresItselfNull() {
        val holder = File(
            moduleDir(),
            "src/main/kotlin/io/github/ygaray/sttengine/internal/DebugAudioSourceHolder.kt",
        )
        assertTrue("expected the holder's source at $holder", holder.isFile)

        val declarations = holder.readLines()
            .map { it.trim() }
            .filter { it.startsWith("internal var factory") }

        assertEquals(
            "expected exactly one declaration of the holder cell in $holder",
            1,
            declarations.size,
        )
        assertEquals(
            "the holder must start life null — a non-null initialiser would ship a build whose " +
                "rung (c) never touches the microphone",
            "internal var factory: PcmSourceFactory? = null",
            declarations.single(),
        )
    }

    // --- behaviour ------------------------------------------------------------------------------

    /** With nothing armed, rung (c)'s source is the microphone — bit for bit the pre-seam behaviour. */
    @Test
    fun aNullHolderResolvesToTheMicrophone() {
        DebugAudioSourceHolder.factory = null
        assertSame(
            "an un-armed build must resolve rung (c) to AudioRecordPcmSource, exactly as it did " +
                "before the seam existed",
            AudioRecordPcmSource,
            defaultSourceFactoryOf(AudioSourcePipe()),
        )
    }

    /**
     * Arming swaps the source for newly constructed pipes; disarming puts the microphone back.
     *
     * The seam being *inert* is the property every other test here defends. This is the one that
     * proves it is not inert when it is supposed to work — a guard around a mechanism that never
     * functioned would be a comfortable way to prove nothing.
     */
    @Test
    fun armingSwapsTheSourceAndDisarmingRestoresIt() {
        val armed = PcmSourceFactory { fail("the fake source is never opened by this test"); error("unreachable") }

        DebugAudioSourceHolder.factory = armed
        assertSame(
            "a pipe constructed while the holder is armed must read the armed factory",
            armed,
            defaultSourceFactoryOf(AudioSourcePipe()),
        )

        DebugAudioSourceHolder.factory = null
        assertSame(
            "disarming must return rung (c) to the microphone for subsequently built pipes",
            AudioRecordPcmSource,
            defaultSourceFactoryOf(AudioSourcePipe()),
        )
    }

    /**
     * An explicitly injected factory still wins, so the seam perturbs no existing test.
     *
     * `AudioSourcePipeTest` drives every resource-discipline path through an injected counting fake.
     * If the holder could override an explicit argument, the seam would have quietly changed what
     * nine existing tests actually exercise.
     */
    @Test
    fun anExplicitFactoryIsNeverOverriddenByTheHolder() {
        val armed = PcmSourceFactory { error("the armed factory must not be consulted") }
        val injected = PcmSourceFactory { error("the injected factory is never opened by this test") }

        DebugAudioSourceHolder.factory = armed
        assertSame(
            "an explicitly injected source factory must win over the debug holder",
            injected,
            defaultSourceFactoryOf(AudioSourcePipe(sourceFactory = injected)),
        )
    }

    // --- the server path (Phase 21, SRV-05, D-08) ----------------------------------------------

    /**
     * With nothing armed — which is every release build, where nothing can write the holder — the
     * server path's capture source is the microphone.
     */
    @Test
    fun aNullHolderResolvesServerBackendToTheMicrophone() {
        DebugAudioSourceHolder.factory = null
        assertSame(
            "an un-armed build must resolve ServerBackend's capture source to AudioRecordPcmSource",
            AudioRecordPcmSource,
            defaultPcmSourceFactoryOf(serverBackend()),
        )
    }

    /**
     * Arming the holder before a [ServerBackend] is constructed feeds that backend from the armed
     * factory (what a reproducible file-fed Gate-1 server session needs); disarming restores the
     * microphone for backends built afterwards. The default is read per construction, so each
     * attempt the selector builds sees the holder's current value.
     */
    @Test
    fun armingSwapsServerBackendSourceAndDisarmingRestoresIt() {
        val armed = PcmSourceFactory { fail("the fake source is never opened by this test"); error("unreachable") }

        DebugAudioSourceHolder.factory = armed
        assertSame(
            "a ServerBackend constructed while the holder is armed must read the armed factory",
            armed,
            defaultPcmSourceFactoryOf(serverBackend()),
        )

        DebugAudioSourceHolder.factory = null
        assertSame(
            "disarming must return the server path to the microphone for subsequently built backends",
            AudioRecordPcmSource,
            defaultPcmSourceFactoryOf(serverBackend()),
        )
    }

    /** An explicitly injected factory wins, so the seam perturbs none of the server-path suites. */
    @Test
    fun anExplicitServerBackendFactoryIsNeverOverriddenByTheHolder() {
        val armed = PcmSourceFactory { error("the armed factory must not be consulted") }
        val injected = PcmSourceFactory { error("the injected factory is never opened by this test") }

        DebugAudioSourceHolder.factory = armed
        assertSame(
            "an explicitly injected pcmSourceFactory must win over the debug holder",
            injected,
            defaultPcmSourceFactoryOf(serverBackend(pcmSourceFactory = injected)),
        )
    }

    // --- the frozen surface ---------------------------------------------------------------------

    /** The release API dump names nothing from the seam. */
    @Test
    fun theFrozenApiDumpNamesNoSeamSymbol() {
        val dump = File(moduleDir(), "api/stt.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the API dump does not name createSttEngine, so it is not the dump this test thinks " +
                "it is reading and every assertion below would pass vacuously",
            text.contains("createSttEngine"),
        )

        for (symbol in listOf("DebugAudioSource", "PacedWavFeed", "WavPcm16", "sttengine/debug")) {
            assertFalse(
                "\"$symbol\" appears in the frozen release API dump. The seam has widened the " +
                    "published binary contract, which is a permanent commitment to every future " +
                    "consumer in exchange for a test fixture.",
                text.contains(symbol),
            )
        }
    }

    // --- plumbing -------------------------------------------------------------------------------

    private enum class Variant { DEBUG, RELEASE }

    /**
     * Which variant this test run is compiled against, read from its own class output directory
     * (`…/kotlin-classes/debugUnitTest` vs `…/kotlin-classes/releaseUnitTest`).
     *
     * A hard failure when it cannot be determined. The alternative — assuming one — would make the
     * release half of this suite silently stop running.
     */
    private fun resolveVariant(): Variant {
        val location = javaClass.protectionDomain?.codeSource?.location?.path
        assertNotNull("cannot locate this test's own class output; variant is undecidable", location)
        val path = location!!.lowercase()
        return when {
            path.contains("releaseunittest") -> Variant.RELEASE
            path.contains("debugunittest") -> Variant.DEBUG
            else -> {
                fail("cannot tell the variant from the test class output path \"$location\"")
                error("unreachable")
            }
        }
    }

    /**
     * The `:stt` module directory, found by walking up from this test's class output until a
     * directory carrying the module's own sources appears.
     *
     * Deliberately not the working directory: the working directory of a Gradle test JVM is not a
     * stable contract, which is the same reason `ErrorCodeParityTest` is handed an absolute path.
     */
    private fun moduleDir(): File {
        val start = javaClass.protectionDomain?.codeSource?.location?.path
        assertNotNull("cannot locate this test's own class output", start)
        val marker = "src/main/kotlin/io/github/ygaray/sttengine/internal/AudioSourcePipe.kt"

        var candidate: File? = File(start!!).absoluteFile
        while (candidate != null) {
            if (File(candidate, marker).isFile) return candidate
            candidate = candidate.parentFile
        }
        fail("walked up from \"$start\" without finding a directory containing $marker")
        error("unreachable")
    }

    /** The factory an [AudioSourcePipe] resolved for itself, without opening anything. */
    private fun defaultSourceFactoryOf(pipe: AudioSourcePipe): PcmSourceFactory {
        val field = AudioSourcePipe::class.java.getDeclaredField("sourceFactory")
        field.isAccessible = true
        val value = field.get(pipe)
        assertNotNull("AudioSourcePipe.sourceFactory resolved to null", value)
        return value as PcmSourceFactory
    }

    /**
     * A [ServerBackend] built the way the selector builds one, minus the network: construction opens
     * no socket and no microphone. [Dispatchers.Unconfined] keeps this plain-JVM suite off the
     * Android main looper. A `null` [pcmSourceFactory] leaves the constructor default in force.
     */
    private fun serverBackend(pcmSourceFactory: PcmSourceFactory? = null): ServerBackend =
        if (pcmSourceFactory == null) {
            ServerBackend(
                webSocketFactory = FakeWebSocketFactory(),
                serverUrl = "wss://example.ts.net:8444/",
                language = "en",
                mainDispatcher = Dispatchers.Unconfined,
            )
        } else {
            ServerBackend(
                webSocketFactory = FakeWebSocketFactory(),
                serverUrl = "wss://example.ts.net:8444/",
                language = "en",
                mainDispatcher = Dispatchers.Unconfined,
                pcmSourceFactory = pcmSourceFactory,
            )
        }

    /** The capture factory a [ServerBackend] resolved for itself, without opening anything. */
    private fun defaultPcmSourceFactoryOf(backend: ServerBackend): PcmSourceFactory {
        val field = ServerBackend::class.java.getDeclaredField("pcmSourceFactory")
        field.isAccessible = true
        val value = field.get(backend)
        assertNotNull("ServerBackend.pcmSourceFactory resolved to null", value)
        return value as PcmSourceFactory
    }

    /** Guards against a future refactor turning the holder into something that is not a plain cell. */
    @Test
    fun theHolderIsAPlainNullableCell() {
        DebugAudioSourceHolder.factory = null
        assertNull("the holder must read back exactly what was written", DebugAudioSourceHolder.factory)
    }
}

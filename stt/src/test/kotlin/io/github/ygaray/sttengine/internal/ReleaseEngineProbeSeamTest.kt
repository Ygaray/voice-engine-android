package io.github.ygaray.sttengine.internal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * **The proof that the debug engine probe cannot reach a shipped build.**
 *
 * `DebugEngineProbe` reads the live engine's rung and restart count so a real-microphone run, whose
 * session trace is off, can still say which rung it used and whether it silently restarted. It must
 * exist only in the debug variant: nothing in the release AAR, in the frozen public dump, or in any
 * shipping source set may name it.
 *
 * Built to the same shape as [ReleaseRungCeilingSeamTest], for the same reason: every assertion that
 * could pass by finding nothing also asserts it found the thing it was looking for, and the variant is
 * resolved from this test's own class-output path so the release half can never silently stop
 * running. It deliberately does not use Robolectric, whose sandbox classloader breaks
 * [resolveVariant]; the behavior of the probe is proven in `DebugEngineProbeTest`.
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [theProbeClassExistsOnlyInTheDebugVariant] | The probe ships inside the AAR |
 * | [theFrozenApiDumpNamesNoProbeSymbol] | The probe widens the published binary contract |
 * | [noShippingSourceSetReferencesTheProbe] | Some future `main` code depends on a debug-only class |
 */
class ReleaseEngineProbeSeamTest {

    private val probeClasses = listOf(
        "io.github.ygaray.sttengine.debug.DebugEngineProbe",
        "io.github.ygaray.sttengine.debug.EngineProbeSnapshot",
    )

    /** Both classes are on the classpath under the debug variant and **absent** under release. */
    @Test
    fun theProbeClassExistsOnlyInTheDebugVariant() {
        for (name in probeClasses) {
            val loaded = runCatching { Class.forName(name) }
            when (resolveVariant()) {
                Variant.DEBUG -> assertTrue(
                    "$name must be present in the DEBUG compilation: its absence means the debug " +
                        "source set stopped being compiled and the live-path evidence is gone. " +
                        "Cause: ${loaded.exceptionOrNull()}",
                    loaded.isSuccess,
                )

                Variant.RELEASE -> assertTrue(
                    "$name MUST NOT exist in the RELEASE compilation. It resolved, which means the " +
                        "probe has leaked out of stt/src/debug/ and into the published AAR.",
                    loaded.exceptionOrNull() is ClassNotFoundException,
                )
            }
        }
    }

    /** The release API dump names nothing from the probe. */
    @Test
    fun theFrozenApiDumpNamesNoProbeSymbol() {
        val dump = File(moduleDir(), "api/stt.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the API dump does not name createSttEngine, so it is not the dump this test thinks " +
                "it is reading and every assertion below would pass vacuously",
            text.contains("createSttEngine"),
        )

        for (symbol in listOf("DebugEngineProbe", "EngineProbeSnapshot", "sttengine/debug")) {
            assertFalse(
                "\"$symbol\" appears in the frozen release API dump. The probe has widened the " +
                    "published binary contract.",
                text.contains(symbol),
            )
        }
    }

    /** No file under `src/main` or `src/release` mentions the probe or its snapshot type. */
    @Test
    fun noShippingSourceSetReferencesTheProbe() {
        val sourceRoot = File(moduleDir(), "src")
        val mainRoot = File(sourceRoot, "main")
        assertTrue("expected the main source set at $mainRoot", mainRoot.isDirectory)

        val shipping = listOf("main", "release")
            .map { File(sourceRoot, it) }
            .filter { it.isDirectory }

        var scanned = 0
        val offenders = mutableListOf<File>()
        for (root in shipping) {
            val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertTrue(
                "scanned no .kt file under $root, so this guard would pass vacuously",
                files.isNotEmpty(),
            )
            scanned += files.size
            files.filter { file ->
                val text = file.readText()
                text.contains("DebugEngineProbe") || text.contains("EngineProbeSnapshot")
            }.forEach { offenders += it }
        }

        assertTrue("expected to scan at least one shipping source file", scanned > 0)
        assertTrue(
            "these shipping files reference the debug-only probe, which does not exist in a " +
                "release compilation: $offenders",
            offenders.isEmpty(),
        )
    }

    // --- plumbing -------------------------------------------------------------------------------

    private enum class Variant { DEBUG, RELEASE }

    /**
     * Which variant this test run is compiled against, read from its own class output directory.
     *
     * A hard failure when it cannot be determined. The alternative, assuming one, would make the
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

    /** The `:stt` module directory, found by walking up from this test's class output. */
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
}

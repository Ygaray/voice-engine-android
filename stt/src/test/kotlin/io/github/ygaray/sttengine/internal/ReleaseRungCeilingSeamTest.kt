package io.github.ygaray.sttengine.internal

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * **The proof that the debug rung-ceiling seam cannot change a shipped build — and that it works.**
 *
 * The seam lets a debug build cap the capability ladder, which is the only way to put a
 * restart-crossing session in front of the AND-02 analyzer: rung (c) ends a recognition session when
 * the app-owned audio source is exhausted, an app-owned source never exhausts, and nothing degrades
 * off a rung whose sessions only ever end because the host stopped them. Measured on the tester, a
 * 140 s file-fed run and a 75 s quiet-microphone run each recorded zero restarts.
 *
 * That is worth having, but it puts a mutable cell — [DebugRungCeilingHolder.ceiling] — in the read
 * path of the decision that chooses how this library talks to the recognizer. The question this file
 * exists to answer is not "do we believe that is safe" but "can a release build reach it at all".
 *
 * Deliberately built to the same shape as [ReleaseAudioSourceSeamTest], because it is defending the
 * same property against the same hazard: every assertion that could pass by finding nothing also
 * asserts it found the thing it was looking for, and the variant is resolved from this test's own
 * class-output path so the release half can never silently stop running.
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [theSeamClassExistsOnlyInTheDebugVariant] | The ceiling setter ships inside the AAR |
 * | [theHolderIsWrittenOnlyFromTheDebugSourceSet] | Some future `main` code caps the ladder in a shipped build |
 * | [theHolderDeclaresItselfNull] | The cell ships pre-capped |
 * | [theFrozenApiDumpNamesNoCeilingSymbol] | The seam widens the published binary contract |
 *
 * ## Where the "it actually works" half lives
 *
 * Proving the seam is **not** inert when it is supposed to work needs a driven engine, which needs
 * the Robolectric runner — and Robolectric's sandbox classloader breaks [resolveVariant], which reads
 * this test's own class-output path to tell debug from release. Mixing the two in one class would
 * make the release half silently stop running, which is the exact failure this suite exists to
 * prevent. So the three ladder-effect tests are in `RungDegradationTest` beside the rest of the
 * ladder's behaviour: `an unset ceiling leaves the ladder exactly as it was`, `a ceiling of
 * RestartingSingleSession forces the floor`, and `a ceiling of SilenceSegmented removes only rung c`.
 */
class ReleaseRungCeilingSeamTest {

    private var saved: Rung? = null

    @Before
    fun saveHolder() {
        saved = DebugRungCeilingHolder.ceiling
        DebugRungCeilingHolder.ceiling = null
    }

    @After
    fun restoreHolder() {
        DebugRungCeilingHolder.ceiling = saved
    }

    // --- the load-bearing one -------------------------------------------------------------------

    /**
     * The setter is on the classpath under the debug variant and **absent** under release.
     *
     * `api/stt.api` proves the seam did not widen the public ABI; this proves the code is not in the
     * release compilation at all, which is the stronger statement — a `main`-side cell that some
     * future edit capped would still be re-levelling the ladder on a user's phone.
     */
    @Test
    fun theSeamClassExistsOnlyInTheDebugVariant() {
        val name = "io.github.ygaray.sttengine.debug.DebugRungCeiling"
        val loaded = runCatching { Class.forName(name) }
        when (resolveVariant()) {
            Variant.DEBUG -> assertTrue(
                "$name must be present in the DEBUG compilation — it is what makes a " +
                    "restart-crossing AND-02 run possible, so its absence here means the debug " +
                    "source set stopped being compiled and every other assertion below is passing " +
                    "vacuously. Cause: ${loaded.exceptionOrNull()}",
                loaded.isSuccess,
            )

            Variant.RELEASE -> assertTrue(
                "$name MUST NOT exist in the RELEASE compilation. It resolved, which means the " +
                    "seam has leaked out of stt/src/debug/ and into the published AAR: a shipped " +
                    "build can now be forced off the rung the device measured as best.",
                loaded.exceptionOrNull() is ClassNotFoundException,
            )
        }
    }

    // --- nothing outside src/debug/ can cap it ---------------------------------------------------

    /** Every assignment to the holder in the entire module lives under `stt/src/debug/`. */
    @Test
    fun theHolderIsWrittenOnlyFromTheDebugSourceSet() {
        val sourceRoot = File(moduleDir(), "src")
        val debugRoot = File(sourceRoot, "debug")
        assertTrue(
            "expected the debug source set at $debugRoot — without it this test proves nothing",
            debugRoot.isDirectory,
        )

        // Only the source sets that CONTRIBUTE TO A VARIANT. `test`/`androidTest` are excluded
        // because nothing in them is compiled into the AAR — this very file caps the ladder, and
        // counting that as a leak would make the assertion impossible to satisfy honestly.
        val shipping = listOf("main", "release", "debug")
            .map { File(sourceRoot, it) }
            .filter { it.isDirectory }
        assertTrue("expected at least src/main and src/debug under $sourceRoot", shipping.size >= 2)

        val assignment = Regex("""DebugRungCeilingHolder\.ceiling\s*=""")
        val writers = shipping
            .flatMap { root -> root.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { assignment.containsMatchIn(it.readText()) }
            .map { it.absoluteFile }

        assertTrue(
            "found no assignment to DebugRungCeilingHolder.ceiling in any shipping source set. " +
                "Either the seam was removed (delete this suite too) or it was renamed and this " +
                "guard has silently stopped guarding.",
            writers.isNotEmpty(),
        )

        val strays = writers.filterNot { it.path.startsWith(debugRoot.absolutePath + File.separator) }
        assertEquals(
            "these files cap the ladder from OUTSIDE stt/src/debug/, so a release build could be " +
                "forced off the rung the device measured as best: $strays",
            emptyList<File>(),
            strays,
        )
    }

    /**
     * The cell's declaration initialises it to `null`, so an un-capped build is a normal build.
     *
     * Asserted against the source rather than the runtime value because a runtime read only tells us
     * what some earlier test left behind; the declaration is what a fresh process starts from.
     */
    @Test
    fun theHolderDeclaresItselfNull() {
        val holder = File(
            moduleDir(),
            "src/main/kotlin/io/github/ygaray/sttengine/internal/DebugRungCeilingHolder.kt",
        )
        assertTrue("expected the holder's source at $holder", holder.isFile)

        val declarations = holder.readLines()
            .map { it.trim() }
            .filter { it.startsWith("internal var ceiling") }

        assertEquals(
            "expected exactly one declaration of the holder cell in $holder",
            1,
            declarations.size,
        )
        assertEquals(
            "the holder must start life null — a non-null initialiser would ship a build whose " +
                "ladder is capped below what the device can actually do",
            "internal var ceiling: Rung? = null",
            declarations.single(),
        )
    }

    // --- the frozen surface ---------------------------------------------------------------------

    /** The release API dump names nothing from the seam. */
    @Test
    fun theFrozenApiDumpNamesNoCeilingSymbol() {
        val dump = File(moduleDir(), "api/stt.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the API dump does not name createSttEngine, so it is not the dump this test thinks " +
                "it is reading and every assertion below would pass vacuously",
            text.contains("createSttEngine"),
        )

        for (symbol in listOf("DebugRungCeiling", "RungCeilingHolder", "sttengine/debug")) {
            assertFalse(
                "\"$symbol\" appears in the frozen release API dump. The seam has widened the " +
                    "published binary contract, which is a permanent commitment to every future " +
                    "consumer in exchange for a test fixture.",
                text.contains(symbol),
            )
        }
    }

    /** Guards against a future refactor turning the holder into something that is not a plain cell. */
    @Test
    fun theHolderIsAPlainNullableCell() {
        DebugRungCeilingHolder.ceiling = null
        assertNull("the holder must read back exactly what was written", DebugRungCeilingHolder.ceiling)
        DebugRungCeilingHolder.ceiling = Rung.SilenceSegmented
        assertEquals(Rung.SilenceSegmented, DebugRungCeilingHolder.ceiling)
    }

    // --- plumbing -------------------------------------------------------------------------------

    private enum class Variant { DEBUG, RELEASE }

    /**
     * Which variant this test run is compiled against, read from its own class output directory.
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

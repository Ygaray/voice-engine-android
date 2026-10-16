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
 * **The proof that the debug silence-extras seam's WRITER cannot exist in a shipped build.**
 *
 * The seam lets a debug build change the POSSIBLY / COMPLETE / MINIMUM_LENGTH extras of the intent
 * the real engine hands to the recognizer (the Phase 26 spike's lever, D-01). That puts a mutable
 * cell, [DebugSilenceExtrasHolder.overrides], in the read path of the intent builder. The question
 * this file answers is not "do we believe that is safe" but "can a release build write it at all".
 *
 * Stated plainly, what this suite proves and what it does not: it proves the WRITER class is
 * absent from the release compilation, that every assignment to the holder lives under
 * `stt/src/debug/`, that the holder declares itself null, and that the frozen API dump names none of
 * it. What stays in the release compilation is the null-initialized internal holder and the one read
 * hook in `SttEngineImpl.startSession`: a mutable cell no Kotlin source set that ships can assign,
 * exactly as for `DebugRungCeilingHolder`. The proof is textual and about Kotlin source: Kotlin
 * `internal` is public bytecode, so Java or reflection in a consumer process is outside what it covers.
 *
 * Built to the same shape as [ReleaseRungCeilingSeamTest], defending the same property against the
 * same hazard: every assertion that could pass by finding nothing also asserts it found the thing it
 * was looking for, and the variant is resolved from this test's own class-output path so the release
 * half can never silently stop running. No Robolectric, for the same variant-resolution reason that
 * suite documents. The engine-level "the override reaches the intent" tests are in
 * `SilenceExtrasSeamEngineTest`.
 *
 * | Test | The failure it makes impossible |
 * |---|---|
 * | [theSeamClassExistsOnlyInTheDebugVariant] | The silence-extras writer ships inside the AAR |
 * | [theHolderIsWrittenOnlyFromTheDebugSourceSet] | Some future `main` code overrides endpointing in a shipped build |
 * | [noShippingCodeOutsideTheHolderCanAliasOrAssignTheCell] | The same, through an alias, `with(...)`, an import alias or a bare `overrides =` |
 * | [theHolderDeclaresItselfNull] | The cell ships pre-overridden |
 * | [theFrozenApiDumpNamesNoSilenceExtrasSymbol] | The seam widens the published binary contract |
 */
class ReleaseSilenceExtrasSeamTest {

    private var saved: SilenceExtrasOverride? = null

    @Before
    fun saveHolder() {
        saved = DebugSilenceExtrasHolder.overrides
        DebugSilenceExtrasHolder.overrides = null
    }

    @After
    fun restoreHolder() {
        DebugSilenceExtrasHolder.overrides = saved
    }

    // --- the load-bearing one -------------------------------------------------------------------

    /** The writer is on the classpath under the debug variant and **absent** under release. */
    @Test
    fun theSeamClassExistsOnlyInTheDebugVariant() {
        val name = "io.github.ygaray.sttengine.debug.DebugSilenceExtras"
        val loaded = runCatching { Class.forName(name) }
        when (resolveVariant()) {
            Variant.DEBUG -> assertTrue(
                "$name must be present in the DEBUG compilation — it is what makes the endpointing " +
                    "spike possible, so its absence here means the debug source set stopped being " +
                    "compiled and every other assertion below is passing vacuously. " +
                    "Cause: ${loaded.exceptionOrNull()}",
                loaded.isSuccess,
            )

            Variant.RELEASE -> assertTrue(
                "$name MUST NOT exist in the RELEASE compilation. It resolved, which means the " +
                    "writer has leaked out of stt/src/debug/ and into the published AAR: a shipped " +
                    "build can now change its own endpointing.",
                loaded.exceptionOrNull() is ClassNotFoundException,
            )
        }
    }

    // --- nothing outside src/debug/ can write it ------------------------------------------------

    /** Every assignment to the holder in the entire module lives under `stt/src/debug/`. */
    @Test
    fun theHolderIsWrittenOnlyFromTheDebugSourceSet() {
        val sourceRoot = File(moduleDir(), "src")
        val debugRoot = File(sourceRoot, "debug")
        assertTrue(
            "expected the debug source set at $debugRoot — without it this test proves nothing",
            debugRoot.isDirectory,
        )

        // Only the source sets that CONTRIBUTE TO A VARIANT. `test`/`testDebug`/`androidTest` are
        // excluded because nothing in them is compiled into the AAR — the engine tests assign the
        // holder directly, and counting that as a leak would make the assertion impossible to satisfy
        // honestly.
        val shipping = listOf("main", "release", "debug")
            .map { File(sourceRoot, it) }
            .filter { it.isDirectory }
        assertTrue("expected at least src/main and src/debug under $sourceRoot", shipping.size >= 2)

        val assignment = Regex("""DebugSilenceExtrasHolder\.overrides\s*=""")
        val writers = shipping
            .flatMap { root -> root.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { assignment.containsMatchIn(it.readText()) }
            .map { it.absoluteFile }

        assertTrue(
            "found no assignment to DebugSilenceExtrasHolder.overrides in any shipping source set. " +
                "Either the seam was removed (delete this suite too) or it was renamed and this " +
                "guard has silently stopped guarding.",
            writers.isNotEmpty(),
        )

        val strays = writers.filterNot { it.path.startsWith(debugRoot.absolutePath + File.separator) }
        assertEquals(
            "these files write the override from OUTSIDE stt/src/debug/, so a release build could " +
                "change its own endpointing: $strays",
            emptyList<File>(),
            strays,
        )
    }

    /**
     * The textual assignment guard above only recognises the literal `DebugSilenceExtrasHolder.overrides =`.
     * Anything in a shipping source set that could write the cell another way must not exist either: an
     * import alias, `with(DebugSilenceExtrasHolder)`, a `val h = DebugSilenceExtrasHolder`, a scope function
     * on the object, or a bare `overrides = ...` in a file that names the holder.
     */
    @Test
    fun noShippingCodeOutsideTheHolderCanAliasOrAssignTheCell() {
        val sourceRoot = File(moduleDir(), "src")
        val debugRoot = File(sourceRoot, "debug").absoluteFile
        val holderFile = File(
            moduleDir(),
            "src/main/kotlin/io/github/ygaray/sttengine/internal/DebugSilenceExtrasHolder.kt",
        ).absoluteFile
        val shippingFiles = listOf("main", "release")
            .map { File(sourceRoot, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absoluteFile }
            .filter { it != holderFile && !it.path.startsWith(debugRoot.path + File.separator) }
        val naming = shippingFiles.filter { it.readText().contains("DebugSilenceExtrasHolder") }
        assertTrue(
            "expected at least the engine's read hook to name the holder; found none, so this guard " +
                "scanned nothing (the holder was renamed or the read hook removed)",
            naming.isNotEmpty(),
        )

        val risky = listOf(
            Regex("""\boverrides\s*=(?!=)"""),
            Regex("""DebugSilenceExtrasHolder\s+as\s+"""),
            Regex("""\bwith\s*\(\s*DebugSilenceExtrasHolder"""),
            Regex("""=\s*DebugSilenceExtrasHolder\b(?!\s*\.)"""),
            Regex("""DebugSilenceExtrasHolder\s*\.\s*(run|apply|let|also)\b"""),
        )
        val offenders = naming.filter { file ->
            val text = file.readText()
            risky.any { it.containsMatchIn(text) }
        }
        assertEquals(
            "these shipping files name the holder and can write it other than through the literal " +
                "assignment the primary guard looks for: $offenders",
            emptyList<File>(),
            offenders,
        )
    }

    /**
     * The cell's declaration initialises it to `null`, so an un-overridden build is a normal build.
     *
     * Asserted against the source rather than the runtime value because a runtime read only tells us
     * what some earlier test left behind; the declaration is what a fresh process starts from.
     */
    @Test
    fun theHolderDeclaresItselfNull() {
        val holder = File(
            moduleDir(),
            "src/main/kotlin/io/github/ygaray/sttengine/internal/DebugSilenceExtrasHolder.kt",
        )
        assertTrue("expected the holder's source at $holder", holder.isFile)

        val declarations = holder.readLines()
            .map { it.trim() }
            .filter { it.startsWith("internal var overrides") }

        assertEquals(
            "expected exactly one declaration of the holder cell in $holder",
            1,
            declarations.size,
        )
        assertEquals(
            "the holder must start life null — a non-null initialiser would ship a build whose " +
                "endpointing is overridden",
            "internal var overrides: SilenceExtrasOverride? = null",
            declarations.single(),
        )
    }

    // --- the frozen surface ---------------------------------------------------------------------

    /** The release API dump names nothing from the seam. */
    @Test
    fun theFrozenApiDumpNamesNoSilenceExtrasSymbol() {
        val dump = File(moduleDir(), "api/stt.api")
        assertTrue("expected the frozen API dump at $dump", dump.isFile)
        val text = dump.readText()

        assertTrue(
            "the API dump does not name createSttEngine, so it is not the dump this test thinks " +
                "it is reading and every assertion below would pass vacuously",
            text.contains("createSttEngine"),
        )

        for (symbol in listOf("DebugSilenceExtras", "SilenceExtrasOverride", "DebugSilenceExtrasHolder", "sttengine/debug")) {
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
        DebugSilenceExtrasHolder.overrides = null
        assertNull("the holder must read back exactly what was written", DebugSilenceExtrasHolder.overrides)
        val request = SilenceExtrasOverride(possibly = 900, complete = null, minimum = null)
        DebugSilenceExtrasHolder.overrides = request
        assertEquals(request, DebugSilenceExtrasHolder.overrides)
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

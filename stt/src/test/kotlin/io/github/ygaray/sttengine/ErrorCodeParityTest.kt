package io.github.ygaray.sttengine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * D-19: the Kotlin half of the three-language error-code mirror.
 *
 * The same eleven kebab-case strings exist in Python (`server/src/stt_server/protocol.py`),
 * TypeScript (`web/src/errors.ts`) and Kotlin ([SttErrorCode.SHARED]). Nothing in any one build
 * can see the other two, so without a gate the three drift silently and a host's shared
 * error-handling code quietly stops matching on one backend.
 *
 * **This file is the FAST LOCAL FEEDBACK half, not the whole gate.** The real three-way comparison
 * lives in `scripts/check-error-code-parity.sh` at the repository root, because the repo root is
 * the only location where all three languages coexist. This test covers the Kotlin↔TypeScript pair
 * for a developer working inside `android/`, and hard-fails on the release path.
 *
 * **Why the skip exists (Obstacle 1 — D-19 vs D-21).** `android/` is a self-contained Gradle root
 * that `git subtree split --prefix=android` promotes to the root of the public mirror. `web/` is a
 * SIBLING of `android/`, above the split prefix, so it is absent from that mirror — while this test
 * file travels into it. A curious consumer running `./gradlew build` against the public mirror
 * would otherwise hit a hard failure over a file that cannot exist there. So: skip when the
 * TypeScript source is absent, and pair that skip with `-Pstt.requireWebParity=true`, which
 * converts it into an explicit failure. The release script always passes that flag, so a release
 * can never be tagged off a skipped parity check (threat T-05-08).
 */
public class ErrorCodeParityTest {

    /**
     * The in-language mirror, matching `web/tests/errors.test.ts:127` and
     * `server/tests/test_protocol.py:146`. Hardcoded on purpose: it needs no external file, so it
     * runs in the mono-repo and in the split mirror alike, and it is the assertion that survives if
     * every other gate is removed.
     */
    @Test
    public fun `the frozen union has 11 codes (7 server + 4 client)`() {
        assertEquals(
            listOf(
                "busy",
                "internal-error",
                "invalid-config",
                "mic-denied",
                "protocol-violation",
                "reconnect-exhausted",
                "send-backpressure",
                "server-unreachable",
                "unsupported-language",
                "unsupported-sample-rate",
                "version-mismatch",
            ),
            SttErrorCode.SHARED.map { it.value }.sorted(),
        )
    }

    /**
     * The cross-file half: parse `web/src/errors.ts` and assert set-equality with
     * [SttErrorCode.SHARED].
     *
     * The path arrives as the `stt.web.errorsTs` system property, injected by `build.gradle.kts`.
     * Injecting it rather than resolving a path relative to the test's working directory removes
     * the entire class of CWD-dependent breakage — the working directory of a Gradle test JVM is
     * not something this test should have an opinion about.
     */
    @Test
    public fun `kotlin SHARED equals the TypeScript frozen union`() {
        val path: String? = System.getProperty("stt.web.errorsTs")
        val required: Boolean = System.getProperty("stt.requireWebParity").toBoolean()
        val file: File? = path?.let(::File)

        if (file == null || !file.exists()) {
            assertFalse(
                "the D-19 parity check was REQUIRED (-Pstt.requireWebParity=true) but the " +
                    "TypeScript source is absent at: ${path ?: "<stt.web.errorsTs unset>"}",
                required,
            )
            Assume.assumeTrue(
                "mono-repo only — web/ is above the android/ split prefix and is absent from " +
                    "the public mirror (Obstacle 1)",
                false,
            )
            return
        }

        val tsCodes: Set<String> = parseFrozenCodes(file.readText(), file.path)
        val kotlinCodes: Set<String> = SttErrorCode.SHARED.map { it.value }.toSet()

        val missingInKotlin = tsCodes - kotlinCodes
        val missingInTypeScript = kotlinCodes - tsCodes
        assertEquals(
            "error-code taxonomy DRIFT between ${file.path} and SttErrorCode.SHARED.\n" +
                "  in TypeScript but not Kotlin: ${missingInKotlin.sorted()}\n" +
                "  in Kotlin but not TypeScript: ${missingInTypeScript.sorted()}\n" +
                "A code is a cross-language contract in three languages; fix the mirror, do not " +
                "relax this assertion.",
            tsCodes,
            kotlinCodes,
        )
    }

    /**
     * Extract the string literals from the two `Object.freeze([...])` declarations in
     * `web/src/errors.ts`.
     *
     * A regex over that file is sufficient and honest — the arrays are frozen literals BY DESIGN
     * (that is the whole premise of the contract), so there is no dynamic construction to miss and
     * a TypeScript parser would buy nothing. It anchors on the two `export const` declarations
     * specifically rather than slicing the file, so prose in the header comment can never be
     * mistaken for a code, and a rename of either array fails loudly here instead of silently
     * shrinking the compared set.
     */
    private fun parseFrozenCodes(source: String, path: String): Set<String> {
        val declarations = Regex(
            """export const (ERROR_CODES|CLIENT_ERROR_CODES)\s*=\s*Object\.freeze\(\[(.*?)]""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(source).toList()

        assertEquals(
            "expected exactly two frozen code arrays (ERROR_CODES, CLIENT_ERROR_CODES) in $path, " +
                "found ${declarations.map { it.groupValues[1] }}",
            2,
            declarations.size,
        )

        val byName = declarations.associate { match ->
            match.groupValues[1] to Regex("""'([a-z-]+)'""")
                .findAll(match.groupValues[2])
                .map { it.groupValues[1] }
                .toSet()
        }

        assertEquals("ERROR_CODES is the frozen SEVEN server codes", 7, byName.getValue("ERROR_CODES").size)
        assertEquals(
            "CLIENT_ERROR_CODES is the frozen FOUR client codes",
            4,
            byName.getValue("CLIENT_ERROR_CODES").size,
        )

        val all = byName.getValue("ERROR_CODES") + byName.getValue("CLIENT_ERROR_CODES")
        assertTrue("the two TypeScript arrays must be disjoint", all.size == 11)
        return all
    }
}

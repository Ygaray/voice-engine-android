package io.github.ygaray.sttengine.analyzer

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The AND-02 verdict machine, proven against recorded fixtures.
 *
 * ## Why this suite is the deliverable, not the analyzer
 *
 * An analyzer that passes everything and an analyzer that fails everything are equally useless, and
 * both look identical from a green build. So every failure fixture here is asserted to fail **for
 * its own named reason and for nothing else**: exactly one of the nine assertions goes red, it is
 * the expected one, the other eight stay green, and the failure text names the defect. That is what
 * makes a red run in plan 10 diagnostic rather than merely alarming.
 *
 * Each `fail-*.jsonl` fixture is **one surgical change** away from `pass-clean-run.jsonl` — `diff`
 * them and the change is the whole story:
 *
 * | Fixture | The one change | Fails |
 * |---|---|---|
 * | `fail-duplicated-tail` | final 4 grows a tail repeating final 5's first four tokens | 4 |
 * | `fail-dropped-partial` | segment 7's last partial before a restart names text no final carries | 5 |
 * | `fail-segmentid-gap` | segments 5..9 renumbered 6..10 | 3 |
 * | `fail-reconnecting-on-restart` | a `reconnecting` state around an ordinary restart | 8 |
 * | `fail-empty-final` | final 6's text becomes whitespace | 6 |
 * | `fail-double-error-state` | the closing `idle` becomes two `error` states | 7 |
 *
 * The clean run itself is synthesized from first-party scripted sentences, never lifted from a real
 * dictation (threat T-05-04), and is shaped like a real post-plan-09 session: five sessions on
 * `RawAudioSegmented`, ten segment finals, four restarts, and the deaf-window values actually
 * measured on the tester (164 / 198 / 259 ms).
 *
 * ## Robolectric
 *
 * `org.json` is one of the classes the plain-JVM mockable `android.jar` stubs out; a parser test
 * there would parse nothing and pass anyway. `@Config(sdk = [35])` deliberately, **not 36** — 36
 * requires Java 21 and this project is Java 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionLogAnalyzerTest {

    // --- the clean run passes all nine -----------------------------------------------------------

    @Test
    fun `the clean fixture passes all nine assertions`() {
        val report = analyzeFixture("pass-clean-run.jsonl")

        assertTrue(report.render(), report.passed)
        assertEquals(9, report.results.size)
        assertEquals(emptyList<AssertionId>(), report.failures.map { it.id })
        assertEquals(0, report.stats.malformedLineCount)
    }

    @Test
    fun `the clean fixture is the shape a real two-minute run has`() {
        val stats = analyzeFixture("pass-clean-run.jsonl").stats

        assertEquals(4, stats.restartCount)
        assertEquals(10, stats.finalCount)
        assertEquals(130_445L, stats.durationMs)
        // The first ready carries the -1 sentinel and must NOT be in the distribution.
        assertEquals(listOf(164L, 198L, 259L, 210L), stats.deafWindowSamples)
        assertEquals(198L, stats.deafWindowMedianMs)
        assertEquals(259L, stats.deafWindowMaxMs)
    }

    // --- each failure fixture fails for its OWN reason ---------------------------------------------

    @Test
    fun `the duplicated-tail fixture fails assertion 4 and only assertion 4`() {
        val report = analyzeFixture("fail-duplicated-tail.jsonl")

        assertFailsExactly(report, AssertionId.NoDuplicatedTail)
        val detail = report.detailOf(AssertionId.NoDuplicatedTail)
        assertTrue(detail, detail.contains("4-token"))
        assertTrue(detail, detail.contains("caf"))
    }

    @Test
    fun `the dropped-partial fixture fails assertion 5 and only assertion 5`() {
        val report = analyzeFixture("fail-dropped-partial.jsonl")

        assertFailsExactly(report, AssertionId.NoDroppedPartial)
        val detail = report.detailOf(AssertionId.NoDroppedPartial)
        assertTrue(detail, detail.contains("appears in 0 finals"))
        assertTrue(detail, detail.contains("ledger"))
    }

    @Test
    fun `the segment-id-gap fixture fails assertion 3 and only assertion 3`() {
        val report = analyzeFixture("fail-segmentid-gap.jsonl")

        assertFailsExactly(report, AssertionId.SegmentIdSequence)
        val detail = report.detailOf(AssertionId.SegmentIdSequence)
        assertTrue(detail, detail.contains("segment id 6"))
        assertTrue(detail, detail.contains("requires 5"))
    }

    @Test
    fun `the reconnecting-on-restart fixture fails assertion 8 and only assertion 8`() {
        val report = analyzeFixture("fail-reconnecting-on-restart.jsonl")

        assertFailsExactly(report, AssertionId.NoReconnectingOnRestart)
        val detail = report.detailOf(AssertionId.NoReconnectingOnRestart)
        assertTrue(detail, detail.contains("SegmentedSessionEnded"))
        assertTrue(detail, detail.contains("t=32719"))
    }

    @Test
    fun `the empty-final fixture fails assertion 6 and only assertion 6`() {
        val report = analyzeFixture("fail-empty-final.jsonl")

        assertFailsExactly(report, AssertionId.NoBlankFinal)
        val detail = report.detailOf(AssertionId.NoBlankFinal)
        assertTrue(detail, detail.contains("segment id 6"))
        assertTrue(detail, detail.contains("blank"))
    }

    @Test
    fun `the double-error fixture fails assertion 7 and only assertion 7`() {
        val report = analyzeFixture("fail-double-error-state.jsonl")

        assertFailsExactly(report, AssertionId.LegalStateMachine)
        val detail = report.detailOf(AssertionId.LegalStateMachine)
        assertTrue(detail, detail.contains("error"))
        assertTrue(detail, detail.contains("2 times"))
    }

    // --- the thresholds, at the line and one step either side ---------------------------------------

    @Test
    fun `a run measuring exactly 120000 ms passes the duration assertion`() {
        val report = analyze(syntheticRun(stopAtMs = FIRST_LISTENING_MS + 120_000L))

        assertTrue(report.render(), report.passed)
        assertEquals(120_000L, report.stats.durationMs)
    }

    @Test
    fun `a run measuring 119999 ms fails the duration assertion and nothing else`() {
        val report = analyze(syntheticRun(stopAtMs = FIRST_LISTENING_MS + 119_999L))

        assertFailsExactly(report, AssertionId.MinimumDuration)
        assertEquals(119_999L, report.stats.durationMs)
        assertTrue(report.detailOf(AssertionId.MinimumDuration).contains("119999"))
    }

    @Test
    fun `exactly 3 restarts passes the restart assertion`() {
        val report = analyze(syntheticRun(restarts = 3))

        assertTrue(report.render(), report.passed)
        assertEquals(3, report.stats.restartCount)
    }

    @Test
    fun `2 restarts fails the restart assertion and nothing else`() {
        val report = analyze(syntheticRun(restarts = 2))

        assertFailsExactly(report, AssertionId.MinimumRestarts)
        assertTrue(report.detailOf(AssertionId.MinimumRestarts).contains("2 restart"))
    }

    @Test
    fun `a segment id sequence stepping by 1 passes while a repeat and a gap each fail`() {
        assertTrue(analyze(syntheticRun(finalSegmentIds = listOf(0, 1, 2, 3))).passed)

        val repeat = analyze(syntheticRun(finalSegmentIds = listOf(0, 1, 1, 2)))
        assertFailsExactly(repeat, AssertionId.SegmentIdSequence)
        assertTrue(repeat.detailOf(AssertionId.SegmentIdSequence).contains("requires 2"))

        val gap = analyze(syntheticRun(finalSegmentIds = listOf(0, 1, 3, 4)))
        assertFailsExactly(gap, AssertionId.SegmentIdSequence)
        assertTrue(gap.detailOf(AssertionId.SegmentIdSequence).contains("requires 2"))
    }

    @Test
    fun `a sequence that does not start at 0 fails`() {
        val report = analyze(syntheticRun(finalSegmentIds = listOf(1, 2, 3, 4)))

        assertFailsExactly(report, AssertionId.SegmentIdSequence)
        assertTrue(report.detailOf(AssertionId.SegmentIdSequence).contains("requires 0"))
    }

    // --- normalization is defined here, and it is defined on tokens ---------------------------------

    @Test
    fun `a decomposed and a composed spelling of the same word are the same token sequence`() {
        val decomposed = SessionLogAnalyzer.normalizeToTokens("A cafe\u0301 on the corner")
        val composed = SessionLogAnalyzer.normalizeToTokens("A caf\u00e9 on the corner")

        assertEquals(composed, decomposed)
        assertEquals(listOf("a", "caf\u00e9", "on", "the", "corner"), composed)
    }

    @Test
    fun `lowercasing uses the root locale, so the default locale cannot change the verdict`() {
        val previous = Locale.getDefault()
        try {
            // Turkish lowercases 'I' to a DOTLESS i. A locale-sensitive lowercase would make the
            // duplication verdict depend on the phone's language setting, which is absurd.
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(listOf("istanbul", "notes"), SessionLogAnalyzer.normalizeToTokens("ISTANBUL notes"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `punctuation is stripped and unicode whitespace splits`() {
        assertEquals(
            listOf("hello", "world", "its", "here"),
            // U+2014 em dash is punctuation and vanishes; U+00A0 no-break space is whitespace and
            // splits. Neither may leave an empty token behind.
            SessionLogAnalyzer.normalizeToTokens("Hello, world \u2014 it\u2019s\u00a0here!"),
        )
        assertEquals(emptyList<String>(), SessionLogAnalyzer.normalizeToTokens("      "))
        assertEquals(emptyList<String>(), SessionLogAnalyzer.normalizeToTokens("... --- ,,,"))
    }

    @Test
    fun `the duplicated-tail comparison is by token sequence, not by length, for non-ASCII text`() {
        // Same token counts either side, no shared boundary: not duplication.
        val distinct = analyze(
            syntheticRun(
                finalTexts = listOf(
                    "uno dos tre\u0301s cuatro cinco",
                    "seis siete ocho nueve diez",
                ),
            ),
        )
        assertTrue(distinct.render(), distinct.passed)

        // A three-token tail repeats — spelled DECOMPOSED on one side and COMPOSED on the other.
        // A byte-length or code-unit comparison misses this; a normalized token sequence does not.
        val repeated = analyze(
            syntheticRun(
                finalTexts = listOf(
                    "uno dos tre\u0301s cuatro cinco",
                    "tr\u00e9s cuatro cinco seis siete",
                ),
            ),
        )
        assertFailsExactly(repeated, AssertionId.NoDuplicatedTail)
        assertTrue(repeated.detailOf(AssertionId.NoDuplicatedTail).contains("3-token"))
    }

    // --- assertion 9 reports; it never gates --------------------------------------------------------

    @Test
    fun `an extreme deaf window is reported and still yields an overall pass`() {
        val report = analyze(syntheticRun(deafWindowsMs = listOf(1L, 999_999_999L, 2L)))

        assertTrue(report.render(), report.passed)
        assertTrue(report.resultOf(AssertionId.DeafWindow).passed)
        assertTrue(AssertionId.DeafWindow.informational)
        assertEquals(999_999_999L, report.stats.deafWindowMaxMs)
        assertEquals(2L, report.stats.deafWindowMedianMs)
        assertTrue(report.render().contains("999999999"))
    }

    @Test
    fun `the minus one sentinel is not a number and is excluded from the distribution`() {
        val text = syntheticRun(deafWindowsMs = listOf(200L, 240L))
        // The synthetic run's FIRST ready carries the sentinel, exactly as the engine emits it.
        assertTrue(text.contains("\"deafWindowMs\":-1"))

        val report = analyze(text)

        assertEquals(listOf(200L, 240L), report.stats.deafWindowSamples)
        assertEquals(240L, report.stats.deafWindowMaxMs)
        assertEquals(200L, report.stats.deafWindowMedianMs)
    }

    @Test
    fun `a run with no measurable deaf window reports that rather than inventing a zero`() {
        val report = analyze(syntheticRun(restarts = 3, deafWindowsMs = emptyList()))

        assertTrue(report.render(), report.passed)
        assertEquals(emptyList<Long>(), report.stats.deafWindowSamples)
        assertEquals(null, report.stats.deafWindowMedianMs)
        assertTrue(report.detailOf(AssertionId.DeafWindow).contains("no samples"))
    }

    // --- surviving a future schema ------------------------------------------------------------------

    @Test
    fun `an unknown line kind and an unknown field are ignored rather than fatal`() {
        val clean = fixture("pass-clean-run.jsonl").trimEnd('\n').lines()
        val widened = clean.map { line ->
            if (line.contains("\"kind\":\"final\"")) {
                line.dropLast(1) + ",\"lastCommitSource\":\"PLATFORM_FINAL\"}"
            } else {
                line
            }
        } + listOf("{\"t\":130743,\"kind\":\"vuMeter\",\"level\":0.42}")

        val report = analyze(widened.joinToString("\n") + "\n")

        assertTrue(report.render(), report.passed)
        assertEquals(0, report.stats.malformedLineCount)
    }

    @Test
    fun `a malformed line is counted and reported without derailing the verdict`() {
        val text = fixture("pass-clean-run.jsonl") + "not json at all\n{\"kind\":\"state\"}\n"

        val report = analyze(text)

        assertTrue(report.render(), report.passed)
        assertEquals(2, report.stats.malformedLineCount)
        assertTrue(report.render().contains("malformed"))
    }

    @Test
    fun `an empty log fails the assertions it cannot evidence and does not throw`() {
        val report = analyze("")

        assertFalse(report.passed)
        assertEquals(null, report.stats.durationMs)
        assertTrue(report.detailOf(AssertionId.MinimumDuration).contains("no state=listening"))
    }

    @Test
    fun `a run with zero restarts still evaluates every boundary assertion vacuously`() {
        // Rung (b) measured a 57 s session with 13 segments and ZERO restarts. The boundary
        // assertions must not assume a restart exists; only assertion 2 may object.
        val report = analyze(syntheticRun(restarts = 0))

        assertEquals(listOf(AssertionId.MinimumRestarts), report.failures.map { it.id })
        assertTrue(report.resultOf(AssertionId.NoDroppedPartial).passed)
        assertTrue(report.resultOf(AssertionId.NoReconnectingOnRestart).passed)
    }

    // --- the entry point plan 10 scores a real device log through -----------------------------------

    /**
     * The whole point of the class: the same nine assertions, run over a log pulled off the tester.
     *
     * `run-and02-uat.sh` passes the pulled path as the `stt.sessionLog` system property (through a
     * generated Gradle init script, because a test JVM does not inherit `-D` from the command line)
     * and propagates this task's exit status as its own. With no path supplied — every ordinary run
     * of the suite — this is a no-op, so it never skips and never fails a normal build.
     */
    @Test
    fun `scores the session log named by the stt sessionLog system property`() {
        val path = SessionLogAnalyzer.requestedLogPath()
        if (path == null) {
            println(
                "SessionLogAnalyzer: no -D${SessionLogAnalyzer.LOG_PATH_PROPERTY} supplied — " +
                    "fixtures only. Pass one to score a pulled device log.",
            )
            return
        }

        val file = File(path)
        assertTrue("no session log at $path", file.isFile)
        val report = SessionLogAnalyzer.analyzeFile(file)
        println(report.render())
        assertTrue("AND-02 FAILED for $path:\n" + report.render(), report.passed)
    }

    @Test
    fun `the requested log path comes from the system property first and blank means absent`() {
        val previous = System.getProperty(SessionLogAnalyzer.LOG_PATH_PROPERTY)
        try {
            System.setProperty(SessionLogAnalyzer.LOG_PATH_PROPERTY, "/tmp/a-log.jsonl")
            assertEquals("/tmp/a-log.jsonl", SessionLogAnalyzer.requestedLogPath())

            // Blank is "not supplied", not "supplied as blank" — an unset shell variable expanding
            // into the init script must not become a path the analyzer then fails to open.
            System.setProperty(SessionLogAnalyzer.LOG_PATH_PROPERTY, "   ")
            assertEquals(
                System.getenv(SessionLogAnalyzer.LOG_PATH_ENV)?.takeIf { it.isNotBlank() },
                SessionLogAnalyzer.requestedLogPath(),
            )
        } finally {
            if (previous == null) {
                System.clearProperty(SessionLogAnalyzer.LOG_PATH_PROPERTY)
            } else {
                System.setProperty(SessionLogAnalyzer.LOG_PATH_PROPERTY, previous)
            }
        }
    }

    @Test
    fun `analyzeFile names its source so a report can be attributed`() {
        val file = File.createTempFile("stt-session-", ".jsonl")
        file.deleteOnExit()
        file.writeText(fixture("pass-clean-run.jsonl"), StandardCharsets.UTF_8)

        val report = SessionLogAnalyzer.analyzeFile(file)

        assertTrue(report.passed)
        assertEquals(file.absolutePath, report.source)
        assertNotNull(report.render())
        assertTrue(report.render().contains(file.name))
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun assertFailsExactly(report: AnalysisReport, expected: AssertionId) {
        assertEquals(
            "expected exactly one failure\n" + report.render(),
            listOf(expected),
            report.failures.map { it.id },
        )
        assertFalse(report.render(), report.passed)
        assertEquals(9, report.results.size)
        assertEquals(8, report.results.count { it.passed })
    }

    private fun analyzeFixture(name: String): AnalysisReport =
        SessionLogAnalyzer.analyze(fixture(name), source = "session-logs/$name")

    private fun analyze(text: String): AnalysisReport = SessionLogAnalyzer.analyze(text)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/session-logs/$name")) {
            "missing fixture session-logs/$name"
        }.use { it.readBytes().toString(StandardCharsets.UTF_8) }

    /**
     * A minimal but fully valid log, parameterised on exactly the thing under test.
     *
     * Everything not named by a parameter is held at a value the clean fixture already proves
     * passes, so a failure in a threshold test can only be the threshold.
     */
    private fun syntheticRun(
        restarts: Int = 3,
        stopAtMs: Long = FIRST_LISTENING_MS + 130_000L,
        finalSegmentIds: List<Int>? = null,
        finalTexts: List<String>? = null,
        deafWindowsMs: List<Long>? = null,
    ): String {
        val texts = finalTexts ?: DEFAULT_UTTERANCES
        val ids = finalSegmentIds ?: texts.indices.toList()
        val segments = maxOf(texts.size, ids.size)
        val windows = deafWindowsMs ?: List(restarts) { 200L + it }

        val lines = mutableListOf<String>()
        lines += """{"t":3,"kind":"state","value":"connecting"}"""
        lines += """{"t":110,"kind":"rung","value":"RestartingSingleSession"}"""
        lines += """{"t":${FIRST_LISTENING_MS - 1},"kind":"ready","epoch":0,"deafWindowMs":-1}"""
        lines += """{"t":$FIRST_LISTENING_MS,"kind":"state","value":"listening"}"""

        var t = FIRST_LISTENING_MS + 1000L
        for (i in 0 until maxOf(segments, restarts)) {
            if (i < segments) {
                val text = escape(texts[i % texts.size])
                val id = ids.getOrElse(i) { i }
                lines += """{"t":$t,"kind":"partial","segmentId":$id,"text":"$text"}"""
                t += 500
                lines += """{"t":$t,"kind":"final","segmentId":$id,"text":"$text"}"""
                t += 500
            }
            if (i < restarts) {
                lines += """{"t":$t,"kind":"restart","epoch":$i,""" +
                    """"reason":"NormalFinal","delayMs":200,"advanceRung":false}"""
                t += 200
                val window = windows.getOrNull(i)
                if (window != null) {
                    lines += """{"t":$t,"kind":"ready","epoch":${i + 1},"deafWindowMs":$window}"""
                    t += 100
                }
            }
        }
        lines += """{"t":$stopAtMs,"kind":"state","value":"idle"}"""
        return lines.joinToString("\n") + "\n"
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val FIRST_LISTENING_MS = 300L

        val DEFAULT_UTTERANCES = listOf(
            "The morning light came through the kitchen window",
            "She poured a second cup of coffee before the meeting",
            "Rain was expected before noon and the sky agreed",
            "The train to the coast leaves from platform nine",
        )
    }
}

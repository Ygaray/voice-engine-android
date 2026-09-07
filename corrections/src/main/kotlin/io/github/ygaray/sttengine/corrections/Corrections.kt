package io.github.ygaray.sttengine.corrections

/**
 * Portable correction core (Phase 13, CORR-01/CORR-02/CORR-05, `13-CONTEXT.md` D-01/D-02/D-04/D-05)
 * — the Kotlin twin of `web/src/corrections.ts`, hand-ported line-for-line for CORR-04 byte parity.
 * No two independently-designed implementations in different languages are guaranteed to agree on
 * tie-breaking behavior, so this file follows the SAME steps as the TS module, not a fresh design.
 * Any change here must be mirrored there.
 *
 * [Corrections.apply] is the only function wired into the emit choke points (Android's three real
 * origin points — `SttEngineImpl.emitFinal()` here, `ServerBackend`'s final branch and
 * `BackendSelector`'s handoff-commit in 13-02). [Corrections.deriveCorrections] stays independently
 * importable for batch/host use (D-05) and is a STUB in this task — 13-03 implements it for real on
 * both platforms together.
 */

/**
 * One word/phrase-level correction pair (CORR-01/D-05). Deliberately named `wrong`/`right`, NOT
 * `recognized`/`corrected` (mirrors `CorrectionPair` in `web/src/corrections-types.ts`) — a
 * different granularity than [CorrectionRevision]'s whole-utterance `recognized`/`corrected`.
 *
 * **Deliberately NOT a `data class`** (`android/CLAUDE.md` invariant #2) — same binary-compatibility
 * discipline as [CorrectionRevision]: a plain class with hand-written `equals`/`hashCode`/`toString`
 * stays additively extensible without breaking a published `copy()`/constructor ABI.
 */
public class CorrectionPair(
    public val wrong: String,
    public val right: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CorrectionPair) return false
        return wrong == other.wrong && right == other.right
    }

    override fun hashCode(): Int = 31 * wrong.hashCode() + right.hashCode()

    override fun toString(): String = "CorrectionPair(wrong=$wrong, right=$right)"
}

/** A bounded, ordered set of [CorrectionPair]s a host supplies to [Corrections.apply]
 *  (CORR-01/CORR-05) — the twin of `web/src/corrections-types.ts`'s `CorrectionRuleset`. */
public typealias CorrectionRuleset = List<CorrectionPair>

/**
 * Pure, zero-I/O correction transforms (D-04): every function here is stateless and synchronous,
 * with no shared or module-level mutable state, so concurrent callers never interfere with each
 * other's results.
 */
public object Corrections {

    /** Default frequency-gated promotion threshold (CORR-05) — twin of TS `DEFAULT_PROMOTION_THRESHOLD`.
     *  RESEARCH.md Assumption A3 — UNCONFIRMED judgment call, no real correction corpus was
     *  available this session to spike against. */
    public const val DEFAULT_PROMOTION_THRESHOLD: Int = 2

    /** Default active-ruleset size cap (CORR-05) — twin of TS `DEFAULT_RULESET_CAP`. RESEARCH.md
     *  Assumption A4 — UNCONFIRMED judgment call, no real corpus was available this session to
     *  spike against. */
    public const val DEFAULT_RULESET_CAP: Int = 200

    /**
     * The explicit, hand-enumerated separator set (D-02) — the SAME 16-character literal set as the
     * TS twin's `SEPARATORS`, hand-transcribed, never derived from either runtime's Unicode tables.
     * `¿`/`¡` are included because real captured ES output contains them (confirmed against
     * `server/fixtures/bilingual_en_es/intersentence_es_en_01.ref.txt`).
     */
    internal val SEPARATORS: Set<Int> =
        setOf(
            ' ', '\t', '\n', '.', ',', '!', '?', ';', ':', '"', '\'', '(', ')', '-', '—', '¿', '¡',
        ).map { it.code }.toSet()

    /** True when `codePoint` is one of [SEPARATORS]. Operates on the code point as an [Int], not a
     *  [Char] — code-point-safe from day one (RESEARCH.md Pitfall 2: Kotlin's `for (ch in string)`
     *  iterates UTF-16 code UNITS, not code points, unlike JS's `for...of`). */
    internal fun isSeparator(codePoint: Int): Boolean = codePoint in SEPARATORS

    /** One classified span of [tokenize]'s output — `internal`, not on the public ABI (`android/
     *  CLAUDE.md` invariant #2 restricts only PUBLIC model types; a `data class` is fine here). */
    internal data class Token(val text: String, val isWord: Boolean)

    /**
     * Split `input` into [Token]s on the [SEPARATORS] predicate, after NFC-normalizing, iterating by
     * Unicode code point via [String.codePoints] (never a bare `for (ch in input)`, which would
     * split an astral-plane surrogate pair into two "characters" — RESEARCH.md Pitfall 2).
     *
     * **D-02 pre-lock spike finding (confirmed 2026-09-06, ported from `web/src/corrections.ts`'s
     * `tokenize` KDoc):** the live GPU pipeline (`faster-whisper`/`Systran/faster-whisper-small`,
     * `int8_float32` on the GTX 1070) was run against two real accented-Spanish fixtures
     * (`server/fixtures/es/cof_00610_00100787111.wav` → "¿Qué tan lejos está usted de su carro?";
     * `server/fixtures/es/cof_00610_00008989777.wav` → "Los niños tienen mucha imaginación.") and
     * `unicodedata.is_normalized('NFC', text)` was `True` (NFD `False`) for both captured finals:
     * `faster-whisper` already emits pre-composed NFC accented characters. Both captured strings
     * were then run through `String.prototype.normalize('NFC')` (Node) and
     * `java.text.Normalizer.normalize(s, NFC)` (JVM, via `jshell`) — the resulting code-point
     * sequences were IDENTICAL on both runtimes for both strings. Assumption A1 (RESEARCH.md) is
     * CONFIRMED on real backend output. The Android `SpeechRecognizer` half of the spike did NOT run
     * this session (no reachable, idle test device — device/app behavioral driving is out of this
     * executor's lane) — an explicit, flagged, UNRESOLVED assumption; `normalize(NFC)` below is safe
     * regardless of which codepoint form either backend emits.
     */
    internal fun tokenize(input: String): List<Token> {
        val nfc = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFC)
        val tokens = mutableListOf<Token>()
        val buf = StringBuilder()
        var i = 0
        while (i < nfc.length) {
            val codePoint = nfc.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val chunk = nfc.substring(i, i + charCount)
            if (isSeparator(codePoint)) {
                if (buf.isNotEmpty()) {
                    tokens.add(Token(buf.toString(), true))
                    buf.clear()
                }
                tokens.add(Token(chunk, false))
            } else {
                buf.append(chunk)
            }
            i += charCount
        }
        if (buf.isNotEmpty()) tokens.add(Token(buf.toString(), true))
        return tokens
    }

    /** One ruleset pair pre-tokenized for matching: the pattern's own token sequence (tokenized
     *  ONCE, never per scan position — RESEARCH.md Pitfall 4) alongside the pair's replacement text. */
    private class CompiledPair(val pattern: List<Token>, val right: String)

    /**
     * Rewrite `text` by replacing every whole-token/whole-phrase match of a ruleset pair's `wrong`
     * text with its `right` text (CORR-01). Matching and equality are computed on NFC-normalized
     * code-point sequences via [tokenize], never on raw bytes, UTF-16 code units, or grapheme
     * clusters (D-02). Corrections apply only at whole-token boundaries — no mid-word corruption
     * (CORR-03) — and adjacent/overlapping matches are each replaced independently (no merge or
     * boundary collision).
     *
     * Zero-I/O, pure — safe to call concurrently from independent callers with no shared state
     * (D-04). Never constructs a `Regex` from host-supplied text (Security Domain V5 / T-13-01):
     * matching is purely a literal, tokenized-array elementwise comparison.
     *
     * @param text The final-segment text to correct.
     * @param ruleset The active correction pairs. An empty ruleset is a fast-path no-op — `apply`
     *   tokenizes nothing and returns `text` unchanged (byte-for-byte).
     */
    public fun apply(text: String, ruleset: CorrectionRuleset): String {
        if (ruleset.isEmpty()) return text

        val inputTokens = tokenize(text)

        // Tokenize each pattern ONCE, then sort by DESCENDING pattern-token-array length, ties
        // broken by original ruleset order — the SAME deterministic tie-break as the TS twin
        // (longest match wins so a multi-token phrase pair is preferred over a shorter pair that
        // happens to match a prefix of the same span).
        val compiled = ruleset.map { pair -> CompiledPair(tokenize(pair.wrong), pair.right) }
        val sorted =
            compiled.withIndex().sortedWith(
                compareByDescending<IndexedValue<CompiledPair>> { it.value.pattern.size }
                    .thenBy { it.index },
            ).map { it.value }

        val out = mutableListOf<Token>()
        var i = 0
        outer@ while (i < inputTokens.size) {
            for (candidate in sorted) {
                val pattern = candidate.pattern
                if (pattern.isEmpty() || i + pattern.size > inputTokens.size) continue
                var matched = true
                for (k in pattern.indices) {
                    if (inputTokens[i + k].text != pattern[k].text) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    // `tokenize` already NFC-normalizes `pair.right` — do not re-normalize.
                    out.addAll(tokenize(candidate.right))
                    i += pattern.size
                    continue@outer
                }
            }
            // No pattern matched at this position — keep the token verbatim and advance by one.
            // This guarantees byte-identical passthrough for every unmatched region of `text`.
            out.add(inputTokens[i])
            i += 1
        }

        return out.joinToString(separator = "") { it.text }
    }

    /** One step of the word-level LCS alignment between `recognized` and `corrected`'s word-token
     *  lists (see [deriveCorrections]). */
    private enum class OpKind { EQUAL, INSERT, DELETE }

    private class DiffOp(val kind: OpKind, val text: String)

    /**
     * Mine [CorrectionPair]s from a single recognized→corrected diff (CORR-02's literal signature)
     * via a word-level LCS alignment — the SAME steps as the TS twin's `deriveCorrections`
     * (`web/src/corrections.ts`), not an independently-designed reimplementation (CORR-04 byte
     * parity): a host mines candidate correction pairs from its own corrected-transcript edits
     * instead of hand-authoring rules.
     */
    public fun deriveCorrections(recognized: String, corrected: String): List<CorrectionPair> {
        val a = tokenize(recognized).filter { it.isWord }.map { it.text }
        val b = tokenize(corrected).filter { it.isWord }.map { it.text }

        // Security Domain T-13-03 (Denial of Service): bound the O(n×m) LCS table's cost before
        // building it — deriveCorrections is specified for single-final/single-utterance text, not
        // arbitrary-length documents.
        if (a.size > 500 || b.size > 500) return emptyList()

        val m = a.size
        val n = b.size

        // Classic LCS dynamic-programming table: dp[i][j] = length of the LCS of a[0..i) and b[0..j).
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] =
                    if (a[i - 1] == b[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
            }
        }

        // Backtrack from (m, n) to (0, 0), collecting ops end-to-start (reversed below). The `>=`
        // tie-break at an insert/delete choice point is the documented, deterministic choice —
        // identical on both platforms.
        val ops = mutableListOf<DiffOp>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> {
                    ops.add(DiffOp(OpKind.EQUAL, a[i - 1]))
                    i -= 1
                    j -= 1
                }
                j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                    ops.add(DiffOp(OpKind.INSERT, b[j - 1]))
                    j -= 1
                }
                else -> {
                    ops.add(DiffOp(OpKind.DELETE, a[i - 1]))
                    i -= 1
                }
            }
        }
        ops.reverse()

        // Walk the ordered ops left to right, grouping maximal runs of consecutive non-EQUAL ops. A
        // run may freely interleave delete/insert ops; only the two collected lists matter, not
        // their interleaving order.
        val pairs = mutableListOf<CorrectionPair>()
        var deletedWords = mutableListOf<String>()
        var insertedWords = mutableListOf<String>()

        fun flushRun() {
            if (deletedWords.isNotEmpty() && insertedWords.isNotEmpty()) {
                val wrong = deletedWords.joinToString(" ")
                val right = insertedWords.joinToString(" ")
                if (wrong != right) pairs.add(CorrectionPair(wrong, right)) // no-op guard
            }
            deletedWords = mutableListOf()
            insertedWords = mutableListOf()
        }

        for (op in ops) {
            when (op.kind) {
                OpKind.EQUAL -> flushRun()
                OpKind.DELETE -> deletedWords.add(op.text)
                OpKind.INSERT -> insertedWords.add(op.text)
            }
        }
        flushRun()

        return pairs
    }
}

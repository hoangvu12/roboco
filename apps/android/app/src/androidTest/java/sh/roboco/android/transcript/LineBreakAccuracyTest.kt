package sh.roboco.android.transcript

import android.graphics.Paint
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.os.Debug
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.core.FontChain
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.debugLineStarts
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * Rust's line breaker against Android's own (ticket 115, the port of
 * upstream iOS `LineBreakAccuracyTests.swift:1-68`): lay out the corpus at
 * 3 faces × 3 sizes × 28 widths with [debugLineStarts] and with
 * [StaticLayout]'s SIMPLE, unhyphenated greedy breaker, and compare the
 * line starts (UTF-16 offsets). The corpus is [TextAccuracy.CORPUS_LITERALS]
 * plus every qualifying fixture paragraph — 14 strings, 3 528 cases.
 *
 * This is a cross-check, not a gate: the app never runs Android's breaker
 * (Rust decides every break), and Minikin is not the breaker Rust was
 * tuned to (CoreText — Apple's UAX 14 tailoring and its 0.0002 pt fit
 * slack). So [rustLineBreaksMatchStaticLayout] asserts only `rate ≥ 99 %`
 * and reports line-count differences instead of asserting them; the hard
 * gate for run agreement is [TextDriftTest].
 *
 * The URL and hard-hyphen strings (#3, #4, #8 — U+2013 EN DASH, #10, #13)
 * are an expected Android divergence (round 29/33 decisions, tickets 115
 * and 15, like the curly quotes before): Minikin tailors URL and hyphen breaks, Rust keeps
 * CoreText's UAX 14, and CoreText parity is the product target. They are
 * scored separately and logged, never asserted; the 99 % covers the rest.
 * So are MONO cases of text with double-width code points (round 35,
 * `MonoWide.plan != NONE`): ticket 63 measures and paints those clusters
 * as 2 mono cells, StaticLayout at 1 em — TextDriftTest skips the same runs.
 *
 * Ground truth runs at density 1: `StaticLayout.Builder` takes an Int
 * width, and `w × d` is fractional for common densities, which would
 * truncate up to 1 px and add false mismatches. Linear text makes
 * density-1 advances scale exactly, so the same breaks are tested.
 * Everything runs on the instrumentation thread (`debugLineStarts` drives
 * the platform measurer synchronously); one paint per (face, size) is
 * pre-built and reused, and ligatures follow Rust's per-style rule
 * (`crates/mobile/src/layout/style.rs`: `liga` and `clig` off for Mono),
 * which is what the app measures and draws with — unlike iOS, where
 * CoreText ligated Mono too. CI-only: no emulator in the worker slots,
 * so this class is written but never run locally.
 */
@RunWith(AndroidJUnit4::class)
class LineBreakAccuracyTest {

    @Test
    fun rustLineBreaksMatchStaticLayout() {
        val fixture = layoutFixtureMarkdown()
        assertFalse("the fixture must stay LF-only (android-app#05 pins it)", fixture.contains('\r'))
        val corpus = TextAccuracy.corpus(fixture)
        assertEquals("the corpus must be the 10 literals + 4 fixture paragraphs", 14, corpus.size)
        // Acceptance preconditions: the literals are Roboco-branded and
        // string 6's invisible joiners survived the copy.
        assertTrue(
            "corpus string 4 must name roboco-text (rename mapping)",
            TextAccuracy.CORPUS_LITERALS[4].contains("roboco-text"),
        )
        assertFalse(
            "no corpus literal may mention zeron (fixture renames are android-app#05's)",
            TextAccuracy.CORPUS_LITERALS.any { it.contains("zeron") },
        )
        assertEquals("string 6 must be 96 UTF-16 chars", 96, TextAccuracy.CORPUS_LITERALS[6].length)
        assertEquals(
            "string 6 must contain two zero-width joiners (the emoji ZWJ sequences)",
            2,
            TextAccuracy.CORPUS_LITERALS[6].count { it == '\u200D' },
        )
        assertEquals(
            "string 6 must contain exactly one combining enclosing keycap",
            1,
            TextAccuracy.CORPUS_LITERALS[6].count { it == '\u20E3' },
        )

        val textSystem = TextAccuracy.textSystem()
        val faces = listOf(FaceRole.SANS, FaceRole.SANS_SEMIBOLD, FaceRole.MONO)
        val sizes = listOf(14f, 16.5f, 19f)
        // Swift stride(from: 180, through: 430, by: 9): 28 widths, 180…423.
        val widths = (180..430 step 9).toList()
        assertEquals("the width stride must yield 28 widths", 28, widths.size)

        val paints = HashMap<Pair<FaceRole, Float>, TextPaint>()
        for (face in faces) {
            for (size in sizes) {
                paints[face to size] = TextPaint(FontChain.TEXT_PAINT_FLAGS).apply {
                    hinting = Paint.HINTING_OFF
                    FontChain.applyTo(this, face, size, face != FaceRole.MONO)
                }
            }
        }

        // The expected Android divergence (decisions, rounds 29 and 33 —
        // tickets 115 and 15): Minikin tailors URL and hard-hyphen breaks
        // (whole URL token to the next line when an earlier word break
        // exists; inside a URL, break before `/`, after `=`/`&`; never after a
        // line-breaking hyphen — U+002D and U+2013 EN DASH among them — with
        // or without hyphenation), where Rust follows CoreText's UAX 14 — and
        // CoreText parity is the product target. Those strings are scored
        // separately and reported, never asserted; any OTHER string's
        // mismatch still counts against the 99 %. Each excluded string is
        // guarded by the content that makes it a case, so a corpus reorder
        // cannot silently exclude the wrong strings.
        for ((index, trigger) in MINIKIN_HYPHEN_URL_CASES) {
            val text = corpus[index]
            assertTrue(
                "excluded corpus string #$index must contain $trigger: ${text.take(48)}",
                text.contains(trigger),
            )
        }

        var cases = 0
        var exact = 0
        var lineDiff = 0
        var excludedCases = 0
        var excludedExact = 0
        // MONO × double-width code points (decision, round 35): ticket 63 measures
        // AND paints a double-width mono cluster as 2 cells (AndroidMeasurer +
        // TextRunPainter via MonoWide.plan) — self-consistent in the app — while
        // StaticLayout lays CJK out at 1 em, so it is no ground truth there (corpus
        // #5: Rust 42 + 8 × 16.8 = 176.4 breaks at 13, Android 42 + 9 × 14 = 168
        // fits 14). TextDriftTest skips exactly these runs (same predicate).
        var monoWideCases = 0
        var monoWideExact = 0
        val report = ArrayList<String>()
        // Diagnostics only (never asserted): mismatches per corpus string and
        // per face, so a run shows WHICH texts diverge — 12 samples cannot.
        val perText = IntArray(corpus.size)
        val perFace = HashMap<FaceRole, Int>()
        // Per text: the face split and its FIRST mismatch (the 12-sample report
        // is all SANS 14pt — it could not show which face #5's 84 misses were).
        val perTextFace = Array(corpus.size) { HashMap<FaceRole, Int>() }
        val firstMiss = arrayOfNulls<String>(corpus.size)
        for (face in faces) {
            for (size in sizes) {
                val paint = paints.getValue(face to size)
                for (w in widths) {
                    for ((index, text) in corpus.withIndex()) {
                        // Breadcrumb (7ea846c0: this loop grew native memory to
                        // ~1.7 GB and was OOM-killed mid-matrix): the last line
                        // before a kill names the case; the native heap tells a
                        // stuck case (one line, memory climbing) from a per-call
                        // leak (cases advancing, memory climbing).
                        Log.d(
                            "RobocoTextAccuracy",
                            "case $cases $face ${size}pt w=$w text#$index len=${text.length} " +
                                "native=${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB",
                        )
                        val rust = debugLineStarts(textSystem, face, size, w.toFloat(), text)
                            .map { it.toInt() }
                        val androidStarts = staticLayoutStarts(text, paint, w)
                        cases++
                        val excluded = index in MINIKIN_HYPHEN_URL_CASES
                        val monoWide = !excluded && face == FaceRole.MONO &&
                            MonoWide.plan(text, 0, text.length) !== MonoWide.NONE
                        if (excluded) excludedCases++
                        if (monoWide) monoWideCases++
                        if (rust == androidStarts) {
                            when {
                                excluded -> excludedExact++
                                monoWide -> monoWideExact++
                                else -> exact++
                            }
                        } else {
                            // iOS counts height (line-count) diffs on mismatches
                            // only; here they are reported, never asserted.
                            if (rust.size != androidStarts.size && !excluded && !monoWide) lineDiff++
                            perText[index]++
                            perFace[face] = (perFace[face] ?: 0) + 1
                            perTextFace[index][face] = (perTextFace[index][face] ?: 0) + 1
                            if (firstMiss[index] == null) {
                                firstMiss[index] = "$face ${size}pt w=$w: rust $rust vs android $androidStarts" +
                                    disputedLine(text, paint, w, rust, androidStarts)
                            }
                            if (!excluded && !monoWide && report.size < 12) {
                                report += "$face ${size}pt w=$w: rust $rust vs " +
                                    "android $androidStarts — ${text.take(48)}"
                            }
                        }
                    }
                }
            }
        }

        val scored = cases - excludedCases - monoWideCases
        val rate = exact.toDouble() / scored
        val summary = "LINEBREAK ACCURACY exact=$exact/$scored " +
            "(${"%.2f".format(Locale.ROOT, rate * 100)}%) " +
            "lineCountDiffs=$lineDiff locale=${TextAccuracy.localeTag()} " +
            "(excluding the Minikin hyphen/URL strings ${MINIKIN_HYPHEN_URL_CASES.keys.sorted()} " +
            "and MONO x double-width text)"
        TextAccuracy.log(summary)
        // Informational: how far Minikin's hyphen/URL tailoring is from Rust's.
        TextAccuracy.log(
            "LINEBREAK HYPHEN/URL (informational, not asserted) exact=$excludedExact/$excludedCases " +
                "(${"%.2f".format(Locale.ROOT, excludedExact * 100.0 / excludedCases)}%)",
        )
        // Informational: MONO x double-width text, by ticket 63's 2-cell rule.
        TextAccuracy.log(
            "LINEBREAK MONO-WIDE (informational, not asserted: ticket 63 measures and paints " +
                "double-width mono clusters as 2 cells, StaticLayout at 1 em; TextDriftTest skips " +
                "the same runs) exact=$monoWideExact/$monoWideCases",
        )
        report.forEach { TextAccuracy.log("  MISMATCH $it") }
        corpus.forEachIndexed { index, text ->
            if (perText[index] > 0) {
                TextAccuracy.log(
                    "  BY TEXT #$index ${perText[index]}/${cases / corpus.size} " +
                        "${perTextFace[index]} — ${text.take(48)}",
                )
                TextAccuracy.log("    FIRST #$index ${firstMiss[index]}")
            }
        }
        perFace.forEach { (face, n) -> TextAccuracy.log("  BY FACE $face $n") }
        assertEquals("the matrix must be 3 faces × 3 sizes × 28 widths × 14 strings", 3528, cases)
        assertTrue(summary, rate >= 0.99)
    }

    /**
     * Diagnostics only: for the first line where the two breakers disagree,
     * Android's own advance (no context, trailing spaces trimmed) of the line
     * as each breaker ended it, against `w` — so a boundary miss (corpus #9's
     * off-by-one grapheme) shows the size of the measurement gap instead of
     * a guess (round 33).
     */
    private fun disputedLine(text: String, paint: TextPaint, w: Int, rust: List<Int>, android: List<Int>): String {
        val k = (1 until minOf(rust.size, android.size)).firstOrNull { rust[it] != android[it] } ?: return ""
        val start = rust[k - 1]
        fun advance(end: Int): Float {
            var e = end
            while (e > start && text[e - 1] == ' ') e--
            return paint.getRunAdvance(text, start, e, start, e, false, e)
        }
        return " | line ${k - 1} from $start: android end ${android[k]} adv=${advance(android[k])}, " +
            "rust end ${rust[k]} adv=${advance(rust[k])}, w=$w"
    }

    private companion object {
        /**
         * Corpus indices scored separately as the expected Android
         * divergence, each with the content that makes it a case (the
         * guard): #3 the URL literal, #4 the hard-hyphen literal
         * (`roboco-text`, round 29), #8 the en-dash literal (Minikin treats
         * U+2013 as a line-breaking hyphen: Rust breaks after `en–`, Android
         * before it — round 33), #10 and #13 the fixture paragraphs with
         * links. See the comment at their use.
         */
        val MINIKIN_HYPHEN_URL_CASES = mapOf(
            3 to "://",
            4 to "roboco-text",
            8 to "–",
            10 to "://",
            13 to "://",
        )
    }

    /**
     * Android's own greedy line starts for `text` at `width` (dp, density
     * 1): SIMPLE strategy, no hyphenation, no justification, normal
     * alignment, no extra padding — the plain platform defaults the iOS
     * harness's CTFramesetter corresponds to. On API 33+ an explicit
     * no-phrase-based LineBreakConfig pins the tailoring off.
     */
    private fun staticLayoutStarts(text: String, paint: TextPaint, width: Int): List<Int> {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_NONE)
        if (Build.VERSION.SDK_INT >= 33) {
            builder.setLineBreakConfig(
                LineBreakConfig.Builder()
                    .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NONE)
                    .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                    .build(),
            )
        }
        val layout = builder.build()
        return (0 until layout.lineCount).map { layout.getLineStart(it) }
    }
}

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
 * The URL and hard-hyphen strings (#3, #4, #10, #13) are an expected
 * Android divergence (round 29 decision, tickets 115 and 15, like the
 * curly quotes before): Minikin tailors URL and hyphen breaks, Rust keeps
 * CoreText's UAX 14, and CoreText parity is the product target. They are
 * scored separately and logged, never asserted; the 99 % covers the rest.
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

        // The expected Android divergence (decision, round 29 — tickets 115
        // and 15): Minikin tailors URL and hard-hyphen breaks (whole URL
        // token to the next line when an earlier word break exists; inside a
        // URL, break before `/`, after `=`/`&`; never after a hard hyphen,
        // with or without hyphenation), where Rust follows CoreText's UAX 14
        // — and CoreText parity is the product target. Those strings are
        // scored separately and reported, never asserted; any OTHER string's
        // mismatch still counts against the 99 %. Guarded by content below,
        // so a corpus reorder cannot silently exclude the wrong strings.
        for (index in MINIKIN_URL_HYPHEN_CASES) {
            val text = corpus[index]
            assertTrue(
                "excluded corpus string #$index must be a URL/hyphen case: ${text.take(48)}",
                text.contains("://") || text.contains("roboco-text"),
            )
        }

        var cases = 0
        var exact = 0
        var lineDiff = 0
        var excludedCases = 0
        var excludedExact = 0
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
                        val excluded = index in MINIKIN_URL_HYPHEN_CASES
                        if (excluded) excludedCases++
                        if (rust == androidStarts) {
                            if (excluded) excludedExact++ else exact++
                        } else {
                            // iOS counts height (line-count) diffs on mismatches
                            // only; here they are reported, never asserted.
                            if (rust.size != androidStarts.size && !excluded) lineDiff++
                            perText[index]++
                            perFace[face] = (perFace[face] ?: 0) + 1
                            perTextFace[index][face] = (perTextFace[index][face] ?: 0) + 1
                            if (firstMiss[index] == null) {
                                firstMiss[index] = "$face ${size}pt w=$w: rust $rust vs android $androidStarts"
                            }
                            if (!excluded && report.size < 12) {
                                report += "$face ${size}pt w=$w: rust $rust vs " +
                                    "android $androidStarts — ${text.take(48)}"
                            }
                        }
                    }
                }
            }
        }

        val scored = cases - excludedCases
        val rate = exact.toDouble() / scored
        val summary = "LINEBREAK ACCURACY exact=$exact/$scored " +
            "(${"%.2f".format(Locale.ROOT, rate * 100)}%) " +
            "lineCountDiffs=$lineDiff locale=${TextAccuracy.localeTag()} " +
            "(excluding the Minikin URL/hyphen strings $MINIKIN_URL_HYPHEN_CASES)"
        TextAccuracy.log(summary)
        // Informational: how far Minikin's URL/hyphen tailoring is from Rust's.
        TextAccuracy.log(
            "LINEBREAK URL/HYPHEN (informational, not asserted) exact=$excludedExact/$excludedCases " +
                "(${"%.2f".format(Locale.ROOT, excludedExact * 100.0 / excludedCases)}%)",
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

    private companion object {
        /**
         * Corpus indices scored separately as the expected Android
         * divergence (round 29): #3 the URL literal, #4 the hard-hyphen
         * literal (`roboco-text`), #10 and #13 the fixture paragraphs with
         * links. See the comment at their use.
         */
        val MINIKIN_URL_HYPHEN_CASES = setOf(3, 4, 10, 13)
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

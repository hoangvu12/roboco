package sh.roboco.android.transcript

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.core.FontChain
import uniffi.roboco_core.ColorRole
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.TranscriptView
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * Run-width drift against the real platform paint (ticket 115, the hard
 * gate — no iOS counterpart; android-app#15's "Run-width drift"): lay the
 * fixture transcript out at 7 widths through the real Rust engine
 * ([TranscriptView] + [TextAccuracy.textSystem]) and check, for every
 * non-wide `TextRun`, that the exact advance of `text[start, end)` with
 * the painter's own [StyleFonts] paint — [FontChain.advance], the same
 * call the measurer measured Rust's widths with — equals
 * `run.width × density` within 0.5 px.
 *
 * The painter (ticket 63) draws exactly `drawText(text, start, end, x, y,
 * paint)` with the paint ticket 62's [StyleFonts] built, so what the
 * painter advances is the exact run advance; if Android's glyph advances
 * disagreed with rustybuzz's, text would overlap or leave gaps and
 * nothing else in the app would notice. The point of using [StyleFonts] —
 * not a test-local paint — is to catch a flag, hinting, feature-setting
 * or typeface difference between the painter and the measurer (ticket 60).
 *
 * Measured with [FontChain.advance] (public `Paint.getRunAdvance`), not
 * `Paint.measureText`: AOSP's `measureText(String, Int, Int)` returns
 * `Math.ceil` of the advance — [FontChain.advance]'s doc exists for
 * exactly that — so a ceiled reading could never sit within 0.5 px of a
 * fractional Rust width, and the ticket's own example report
 * (`paint=123.4400px`) shows fractional values. The exact advance is
 * what both the measurer (AndroidMeasurer) and the painter advance by,
 * which is the property under test.
 *
 * Two densities are checked: the device's own and 1.0 (one [StyleFonts]
 * per density; `useDensity` swaps the cached paints). Everything runs on
 * the instrumentation thread — [StyleFonts]' paint half is single-thread
 * by contract, and here that one thread is the instrumentation thread,
 * never main. CI-only: no emulator in the worker slots, so this class is
 * written but never run locally.
 */
@RunWith(AndroidJUnit4::class)
class TextDriftTest {

    /** The drift widths of android-app#15, in dp: phones and a tablet. */
    private val widthsDp = listOf(320, 360, 375, 390, 412, 430, 768)

    /**
     * One compared run: where it came from (density, width, row, run
     * index), how it was styled (face, size in pt, ligatures, inline-code
     * chip), both widths in px, and its text (for the worst-run report).
     */
    private data class Sample(
        val d: Float,
        val width: Int,
        val row: Int,
        val run: Int,
        val face: FaceRole,
        val size: Float,
        val lig: Boolean,
        val chip: Boolean,
        val rust: Float,
        val paint: Float,
        val text: String,
    ) {
        /** `measured − expected` in px. */
        val delta: Float
            get() = paint - rust
    }

    /** Per-density accumulation: every sample plus the skip counters. */
    private class Tally {
        val samples = ArrayList<Sample>()
        var chipCompared = 0
        var skippedWide = 0
        var skippedTab = 0
    }

    @Test
    fun runWidthsMatchPaint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val textSystem = TextAccuracy.textSystem()
        val densities = listOf(context.resources.displayMetrics.density, 1f).distinct()
        val fonts = densities.associateWith { StyleFonts() }
        val tallies = densities.associateWith { Tally() }

        val view = TranscriptView(textSystem, NoopListener)
        try {
            view.setDebugEntries(
                listOf(
                    DebugEntry(
                        id = "fixture",
                        user = false,
                        text = layoutFixtureMarkdown(),
                        streaming = false,
                    ),
                ),
                working = false,
            )
            for (w in widthsDp) {
                view.setViewport(w.toFloat(), 1f)
                view.awaitFrame(w.toFloat()).use { frame ->
                    for (d in densities) {
                        val styleFonts = fonts.getValue(d)
                        styleFonts.update(frame.styles())
                        styleFonts.useDensity(d)
                        val tally = tallies.getValue(d)
                        for (i in 0u until frame.rowCount()) {
                            val row = frame.display(i)!!
                            for (runIndex in row.runs.indices) {
                                val run = row.runs[runIndex]
                                val start = run.start.toInt()
                                val end = start + run.len.toInt()
                                assertTrue(
                                    "run [$start, $end) exceeds the row text " +
                                        "(${row.text.length} chars) at width $w",
                                    end <= row.text.length,
                                )
                                val entry = styleFonts[run.style]
                                    ?: fail("no StyleFonts entry for style ${run.style} " +
                                        "(width $w, row $i, run $runIndex)")
                                // Ticket 63 paints a double-width mono cluster in a
                                // 2-cell box, so a plain text advance is not what is
                                // drawn there: skip it (counted, expected 0 here).
                                // Never FontChain.hasWide — U+3003/U+301C draw with
                                // plain drawText and must be compared.
                                if (FontChain.isMono(entry.face) &&
                                    MonoWide.plan(row.text, start, end) !== MonoWide.NONE
                                ) {
                                    tally.skippedWide++
                                    continue
                                }
                                // Tabs advance by tab stops, not glyphs; a tab-only
                                // run can never match (the fixture has none — guard).
                                var allTabs = end > start
                                for (j in start until end) {
                                    if (row.text[j] != '\t') {
                                        allTabs = false
                                        break
                                    }
                                }
                                if (allTabs) {
                                    tally.skippedTab++
                                    continue
                                }
                                val chip = run.color == ColorRole.INLINE_CODE_TEXT
                                // The exact (fractional) advance — what the measurer
                                // measured Rust's width with and what drawText
                                // advances. Not Paint.measureText: AOSP ceils it
                                // (Math.ceil of nGetTextAdvances), which can never
                                // match a fractional Rust width within 0.5 px.
                                val measured = FontChain.advance(entry.paint, row.text, start, end)
                                val expected = run.width * d
                                tally.samples += Sample(
                                    d = d,
                                    width = w,
                                    row = i.toInt(),
                                    run = runIndex,
                                    face = entry.face,
                                    size = entry.sizePt,
                                    lig = entry.ligatures,
                                    chip = chip,
                                    rust = expected,
                                    paint = measured,
                                    text = row.text.substring(start, end),
                                )
                                if (chip) tally.chipCompared++
                            }
                        }
                    }
                }
            }
        } finally {
            view.shutdown()
            view.close()
        }

        val locale = TextAccuracy.localeTag()
        for (d in densities) {
            val tally = tallies.getValue(d)
            val samples = tally.samples
            val worst = samples.sortedByDescending { abs(it.delta) }.take(12)
            val maxAbs = samples.maxOfOrNull { abs(it.delta) } ?: 0f
            TextAccuracy.log(
                "TEXT DRIFT d=$d locale=$locale runs=${samples.size} " +
                    "chips=${tally.chipCompared} skippedWide=${tally.skippedWide} " +
                    "skippedTab=${tally.skippedTab} " +
                    "maxAbs=${"%.4f".format(Locale.ROOT, maxAbs)}px limit=0.5px",
            )
            worst.forEach { TextAccuracy.log(it.worstLine()) }
            assertTrue("no runs compared at density $d", samples.isNotEmpty())
            assertTrue(
                "only ${tally.chipCompared} inline-code chip runs compared at density $d " +
                    "(the fixture has 3 code spans, so 7 widths must yield at least 21)",
                tally.chipCompared >= 21,
            )
            assertTrue(
                worst.firstOrNull()?.worstLine()
                    ?: "text widths drifted from the paint at density $d",
                samples.all { abs(it.delta) < 0.5f },
            )
        }
    }

    /** The report line of one sample (two leading spaces, like the summary). */
    private fun Sample.worstLine(): String =
        "  WORST w=$width row=$row run=$run ${face.name} " +
            "${"%.2f".format(Locale.ROOT, size)}pt lig=$lig chip=$chip " +
            "rust=${"%.4f".format(Locale.ROOT, rust)}px " +
            "paint=${"%.4f".format(Locale.ROOT, paint)}px " +
            "delta=${"%.4f".format(Locale.ROOT, delta)}px " +
            "— \"${text.take(48)}\""
}

/** Assertion-failure bridge for the null style guard ([org.junit.Assert.fail]). */
private fun fail(message: String): Nothing = throw AssertionError(message)

package sh.roboco.android.parity

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G3 of the iOS parity gate (android-app#30): metric assertions
 * **measured from the iOS references** (the `pr874/2-settings.png` and
 * `1-off.png` captures: 460 px ≙ 393 pt, 1 pt ≈ 1.1705 px), asserted as
 * sampled pixels and edges on the Android goldens. Values in dp;
 * colours are light mode, ΔE ≤ 3 (the CIE76-style weighted RGB
 * distance below).
 *
 * The ticket's metric table, seeded from the three light-mode
 * post-rewrite captures — deliberately few and tolerant; G2's human
 * sign-off carries the rest. Every UI ticket's acceptance cites its
 * rows here. The rows that need per-element detection the goldens
 * cannot give blind (icon/text leading, the toggle's 62×27 dp block
 * against a golden whose notifications are off, the harness-mark
 * geometry, the accessory capsule, the bubble radius) are carried by
 * `parity/CHECKLIST.md` (S-04, S-05, SL-03, SL-07, TR-01) until their
 * goldens exist.
 *
 * Until the goldens are recorded (the CI recording pass), the setup
 * assertion fails naming the golden — the gate arming, not flakiness.
 * Plain JVM, no Robolectric.
 */
class IosParityMetricsTest {

    // ── the settings screen (pr874/2-settings.png) ──────────────────────

    @Test
    fun settingsPageBackgroundAndCardFill() {
        val golden = golden("settings/settings_screen_top_light.png")
        val histogram = golden.histogram()
        val pageBg = intArrayOf(0xF3, 0xF3, 0xF5)
        val cardFill = intArrayOf(0xFF, 0xFF, 0xFF)
        val bg = histogram.mostCommon(0)
        val card = histogram.mostCommon(1)
        assertTrue(
            "page background is #${bg.hex()} (expected #F3F3F5, ΔE ${bg.delta(pageBg)})",
            bg.delta(pageBg) <= DELTA,
        )
        assertTrue(
            "card fill is #${card.hex()} (expected #FFFFFF, ΔE ${card.delta(cardFill)})",
            card.delta(cardFill) <= DELTA,
        )
    }

    @Test
    fun settingsCardInsets() {
        val golden = golden("settings/settings_screen_top_light.png")
        val left = golden.leftInsetOf(cardish = true)
        val right = golden.rightInsetOf()
        assertDp("card left inset", left, expectedDp = 16, toleranceDp = 1)
        assertDp("card right inset", right, expectedDp = 16, toleranceDp = 1)
    }

    @Test
    fun settingsRowHeights() {
        // 51 / 67 / 83 dp (± 2): rows are delimited by inset hairlines
        // inside a card; the heights measured between them.
        val golden = golden("settings/settings_screen_top_light.png")
        val heights = golden.settingsRowHeightsDp()
        assertTrue("no settings rows measured (hairlines not found)", heights.isNotEmpty())
        for (expected in listOf(51, 67, 83)) {
            assertTrue(
                "no row measures $expected dp ± 2 (measured $heights)",
                heights.any { kotlin.math.abs(it - expected) <= 2f },
            )
        }
    }

    @Test
    fun settingsToggleAndCheckmarkAccent() {
        // The accent (#5B43E8) is the ON toggle and the checkmark colour:
        // the golden must contain a solid block of it.
        val golden = golden("settings/settings_screen_top_light.png")
        val accent = intArrayOf(0x5B, 0x43, 0xE8)
        val pixels = golden.countWithin(accent, DELTA)
        assertTrue(
            "the accent #5B43E8 covers only $pixels px (expected a visible toggle/checkmark block)",
            pixels >= 200,
        )
    }

    // ── the sessions list (pr874/1-off.png) ─────────────────────────────

    @Test
    fun sessionsRowMargins() {
        // SessionCell.swift / research 01 §2.3: rows inset 20 dp from the
        // screen edges; the rows are the widest content below the title.
        // The row's leftmost content is the 20 dp harness mark. The
        // per-scanline mode of leftInsetOf measured the title/meta text
        // column instead (54 dp: more scanlines cross text than marks, the
        // same 20 / 54 split as 1-off.png), so the inset here is the
        // leftmost edge hit by several scanlines.
        val golden = golden("sessions/sessions_root_scrolled_light.png")
        val left = golden.leftmostContentInset()
        assertDp("row left margin", left, expectedDp = 20, toleranceDp = 1)
    }

    // ── the transcript fixture (mob:layout/rows.rs:124-138) ─────────────

    @Test
    fun transcriptMarginX() {
        val golden = golden("transcript/session_transcript_fixture_light_393.png")
        val left = golden.leftInsetOf(cardish = false)
        assertDp("MARGIN_X", left, expectedDp = 18, toleranceDp = 1)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun golden(path: String): GoldenImage {
        val file = ParityPaths.golden(path)
        assertTrue(
            "$path is not recorded yet — record it on CI " +
                "(./gradlew :app:recordRoborazziDebug) and sign off in the manifest",
            file.isFile,
        )
        return GoldenImage(file)
    }

    /** dp are the golden's pixels / 3 (the phone goldens are xxhdpi). */
    private fun assertDp(what: String, px: Int, expectedDp: Int, toleranceDp: Int) {
        val dp = px / 3f
        val within = abs(dp - expectedDp) <= toleranceDp
        assertTrue("$what is $dp dp (expected $expectedDp ± $toleranceDp)", within)
    }

    private companion object {
        /** ΔE-style weighted RGB distance, ≤ 3 per the metric table. */
        const val DELTA = 3.0
    }

    private fun IntArray.hex(): String = joinToString("") { "%02X".format(it) }

    private fun IntArray.delta(other: IntArray): Double {
        val dr = (this[0] - other[0]).toDouble()
        val dg = (this[1] - other[1]).toDouble()
        val db = (this[2] - other[2]).toDouble()
        return sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db)
    }
}

/** A golden PNG plus the pixel probes G3 needs. */
private class GoldenImage(image: BufferedImage) {

    constructor(file: File) : this(
        checkNotNull(ImageIO.read(file)) { "could not read ${file.path}" },
    )

    private val image = image
    private val width = image.width
    private val height = image.height

    /** The two most common colours (page background, card fill). */
    fun histogram(): Bucket = Bucket(
        (0 until height).flatMap { y -> (0 until width).map { x -> image.getRGB(x, y) } }
            .groupingBy { it and 0xFFFFFF }
            .eachCount(),
    )

    /**
     * The left inset (px) of the first wide content block, sampled over
     * the middle band: `cardish` compares against the most common colour
     * (the card fill against the page background) — the settings cards;
     * otherwise any pixel that differs from the page background by more
     * than ΔE counts as content — the rows / transcript margin.
     */
    fun leftInsetOf(cardish: Boolean): Int {
        val bucket = histogram()
        val background = bucket.mostCommon(0)
        val card = bucket.mostCommon(1)
        val startY = height / 4
        val endY = height * 3 / 4
        val insets = (startY until endY step 4).mapNotNull { y ->
            (0 until width).firstOrNull { x ->
                val rgb = rgb(x, y)
                if (cardish) {
                    colorDelta(rgb, card) <= 3.0 && colorDelta(rgb, background) > 3.0
                } else {
                    colorDelta(rgb, background) > 3.0
                }
            }
        }
        check(insets.isNotEmpty()) { "no content found in the middle band" }
        return insets.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    /**
     * The leftmost content edge (px) over the middle band: the smallest
     * first-content x that at least [minHits] scanlines share, so a stray
     * anti-aliased pixel cannot set it. Unlike [leftInsetOf]'s mode, this
     * is the row's outer inset (the leading glyph), not its text column.
     */
    fun leftmostContentInset(minHits: Int = 3): Int {
        val background = histogram().mostCommon(0)
        val startY = height / 4
        val endY = height * 3 / 4
        val firsts = (startY until endY step 4).mapNotNull { y ->
            (0 until width).firstOrNull { x -> colorDelta(rgb(x, y), background) > 3.0 }
        }
        val counts = firsts.groupingBy { it }.eachCount()
        val edge = counts.keys.sorted().firstOrNull { counts.getValue(it) >= minHits }
        return checkNotNull(edge) { "no content edge shared by $minHits scanlines in the middle band" }
    }

    /** The right inset (px), the mirror of [leftInsetOf] (cards only). */
    fun rightInsetOf(): Int {
        val bucket = histogram()
        val background = bucket.mostCommon(0)
        val card = bucket.mostCommon(1)
        val startY = height / 4
        val endY = height * 3 / 4
        val insets = (startY until endY step 4).mapNotNull { y ->
            (width - 1 downTo 0).firstOrNull { x ->
                val rgb = rgb(x, y)
                colorDelta(rgb, card) <= 3.0 && colorDelta(rgb, background) > 3.0
            }?.let { width - 1 - it }
        }
        check(insets.isNotEmpty()) { "no card found in the middle band" }
        return insets.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
    }

    /**
     * The settings rows' heights in dp: rows are the stretches between
     * the inset hairlines (or background) inside a card — a hairline y
     * has no card-fill pixels inside the sampled band and is not the page
     * background.
     */
    fun settingsRowHeightsDp(): List<Float> {
        val bucket = histogram()
        val background = bucket.mostCommon(0)
        val card = bucket.mostCommon(1)
        val left = leftInsetOf(cardish = true)
        val right = width - rightInsetOf()
        if (right <= left) return emptyList()
        // The separators are inset, not full width (as on iOS: from the
        // text column, 72 dp, to 16 dp short of the card's end). A band
        // from the card's edges sees card fill beside every hairline, so
        // rows never split and whole cards were measured (verify run
        // 38622776189: [119.67, 84.67, 156.0, …]). Sample from the card's
        // middle to 20 dp short of its end, inside the hairline's span.
        val x0 = (left + right) / 2
        val x1 = right - 20 * 3
        val rowStates = mutableListOf<Boolean>() // true = content row
        val rowStarts = mutableListOf<Int>()
        for (y in 0 until height) {
            var fill = 0
            var other = 0
            for (x in x0 until x1 step 3) {
                val rgb = rgb(x, y)
                if (colorDelta(rgb, card) <= 3.0) fill++ else other++
            }
            val isBackground = other > 0 &&
                (x0 until x1 step 3).all { x -> colorDelta(rgb(x, y), background) <= 8.0 }
            val content = fill > 0 && !isBackground
            if (rowStates.isEmpty() || rowStates.last() != content) {
                rowStates.add(content)
                rowStarts.add(y)
            }
        }
        return rowStates.indices
            .filter { rowStates[it] }
            .mapNotNull { i ->
                val start = rowStarts[i]
                val end = rowStarts.getOrNull(i + 1) ?: height
                val px = end - start
                // Ignore hairlines themselves (< 2 dp) and non-row bands.
                if (px in 6..(300 * 3)) px / 3f else null
            }
    }

    /** Pixels within [delta] of [rgbTarget] (the accent probes). */
    fun countWithin(rgbTarget: IntArray, delta: Double): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (colorDelta(rgb(x, y), rgbTarget) <= delta) count++
            }
        }
        return count
    }

    private fun rgb(x: Int, y: Int): IntArray =
        intArrayOf(image.getRGB(x, y) shr 16 and 0xFF, image.getRGB(x, y) shr 8 and 0xFF, image.getRGB(x, y) and 0xFF)

    private fun colorDelta(a: IntArray, b: IntArray): Double {
        val dr = (a[0] - b[0]).toDouble()
        val dg = (a[1] - b[1]).toDouble()
        val db = (a[2] - b[2]).toDouble()
        return sqrt(2 * dr * dr + 4 * dg * dg + 3 * db * db)
    }

    /** The colour-count map: `mostCommon(n)` is the n-th most common colour. */
    class Bucket(counts: Map<Int, Int>) {
        private val ranked = counts.entries.sortedByDescending { it.value }
        fun mostCommon(rank: Int): IntArray {
            val (packed, _) = ranked[rank]
            return intArrayOf(packed shr 16 and 0xFF, packed shr 8 and 0xFF, packed and 0xFF)
        }
    }
}

package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.PullRequestState
import kotlin.math.ceil

/**
 * The PR badge's pure math (android-ui-parity #12): the metrics table from
 * `PRBadgeView.intrinsicContentSize`/`layoutSubviews`
 * (`StatusGlyph.swift:203-224`), the a11y label, the state mapping and the
 * icon's stroke width — all plain Kotlin, run in the merge check.
 */
class PrBadgeTest {

    // ------------------------------------------------------------- metrics

    @Test
    fun padRadiusAndHeightMatchTheTicketTable() {
        // s → pad = radius, H (dp): 4/15, 5/17, 5/18, 5/20, 6/23, 8/30.
        fun assert(s: Float, pad: Float, height: Float) {
            val m = prBadgeMetrics(s, 0f)
            assertEquals("pad s=$s", pad, m.pad, 0f)
            assertEquals("radius s=$s (the corner radius is pad)", pad, m.radius, 0f)
            assertEquals("height s=$s", height, m.height, 0f)
        }
        assert(9f, 4f, 15f)
        assert(10f, 5f, 17f)
        assert(11f, 5f, 18f)
        assert(12f, 5f, 20f)
        assert(14f, 6f, 23f)
        assert(18f, 8f, 30f)
    }

    @Test
    fun roundingIsHalfAwayFromZeroNotKotlinRound() {
        // The ticket's pitfall: Swift's .rounded() — and roundHalfAway — send
        // 4.5 → 5 and 16.5 → 17; kotlin.math.round rounds ties to even, so
        // the badge must never go through it.
        assertEquals(4.5f, 10f * 0.45f, 0f)   // exact in Float32…
        assertEquals(16.5f, 10f * 1.65f, 0f)  // …so s = 10 is the odd case:
        assertEquals(5f, prBadgeMetrics(10f, 0f).pad, 0f)
        assertEquals(17f, prBadgeMetrics(10f, 0f).height, 0f)
        assertEquals(5f, roundHalfAway(4.5f), 0f)
        assertEquals(17f, roundHalfAway(16.5f), 0f)
    }

    @Test
    fun widthMatchesTheTicketTableForEveryLabelWidth() {
        // W = 2·pad + s + 3 + lw; lw = ceil(0.6·s·digits) (GeistMono digits
        // advance 600/1000 em). The table's "77"/"102"/"412" columns.
        val rows = listOf(
            9f to listOf(11f to 31f, 17f to 37f),
            10f to listOf(12f to 35f, 18f to 41f),
            11f to listOf(14f to 38f, 20f to 44f),
            12f to listOf(15f to 40f, 22f to 47f),
            14f to listOf(17f to 46f, 26f to 55f),
            18f to listOf(22f to 59f, 33f to 70f),
        )
        for ((s, columns) in rows) {
            for ((lw, w) in columns) {
                assertEquals("W s=$s lw=$lw", w, prBadgeMetrics(s, lw).width, 0f)
            }
        }
    }

    @Test
    fun labelWidthsCeilFromTheMonospaceAdvance() {
        // The pure form of what the composable measures: ceil(0.6·s·digits)
        // (GeistMono digits advance 600/1000 em) — the ticket's lw columns
        // for "77" (2 digits) and "102"/"412" (3 digits).
        val expected2 = listOf(9f to 11f, 10f to 12f, 11f to 14f, 12f to 15f, 14f to 17f, 18f to 22f)
        val expected3 = listOf(9f to 17f, 10f to 18f, 11f to 20f, 12f to 22f, 14f to 26f, 18f to 33f)
        for ((s, lw) in expected2) {
            assertEquals("lw 2 digits s=$s", lw, ceil(0.6f * s * 2f), 0f)
        }
        for ((s, lw) in expected3) {
            assertEquals("lw 3 digits s=$s", lw, ceil(0.6f * s * 3f), 0f)
        }
    }

    @Test
    fun iconAndLabelGeometry() {
        // The icon sits at x = pad, centred vertically: y = (H − s)/2.
        // The number starts 3 dp after the icon: x = pad + s + 3.
        val m = prBadgeMetrics(11f, 14f)
        assertEquals(5f, m.iconX, 0f)
        assertEquals(3.5f, m.iconY, 0f)
        assertEquals(19f, m.labelX, 0f)

        val m14 = prBadgeMetrics(14f, 26f)
        assertEquals(6f, m14.iconX, 0f)
        assertEquals(4.5f, m14.iconY, 0f)   // (23 − 14) / 2
        assertEquals(23f, m14.labelX, 0f)   // 6 + 14 + 3
    }

    // ---------------------------------------------------------- a11y / state

    @Test
    fun toneAlphasQuantiseLikeWithAlphaComponent() {
        // The alpha replaces the tone's (iOS withAlphaComponent) and
        // quantises like ticket 01's rgba: 0.08 → 20, 0.85 → 217.
        val open = rgb(0x15803D)
        assertEquals(20, prToneAlpha(open, 0.08).toArgb() ushr 24)
        assertEquals(217, prToneAlpha(open, 0.85).toArgb() ushr 24)
        // The tone's RGB passes through untouched.
        assertEquals(0x15803D, prToneAlpha(open, 0.85).toArgb() and 0xFFFFFF)
    }

    @Test
    fun a11yLabelsAreVerbatim() {
        assertEquals("Pull request 77, closed", prBadgeA11yLabel(77u, PrState.Closed))
        assertEquals("Pull request 102, open", prBadgeA11yLabel(102u, PrState.Open))
        assertEquals("Pull request 412, merged", prBadgeA11yLabel(412u, PrState.Merged))
        assertEquals("Pull request 9, draft", prBadgeA11yLabel(9u, PrState.Draft))
        // The bare ULong, no '#'.
        assertEquals("Pull request 18446744073709551615, open", prBadgeA11yLabel(ULong.MAX_VALUE, PrState.Open))
    }

    @Test
    fun stateWordsAreLowercaseAndVerbatim() {
        assertEquals("open", PrState.Open.word)
        assertEquals("merged", PrState.Merged.word)
        assertEquals("closed", PrState.Closed.word)
        assertEquals("draft", PrState.Draft.word)
    }

    @Test
    fun coreStateMapsOneToOneAndDraftStaysUnreachable() {
        assertEquals(PrState.Open, PrState.from(PullRequestState.OPEN))
        assertEquals(PrState.Merged, PrState.from(PullRequestState.MERGED))
        assertEquals(PrState.Closed, PrState.from(PullRequestState.CLOSED))
        // The badge keeps Draft for iOS parity even though the core has no
        // draft state to map onto it.
        assertEquals(3, PullRequestState.entries.size)
        assertTrue(PrState.entries.size > PullRequestState.entries.size)
    }

    // ---------------------------------------------------------- icon stroke

    @Test
    fun strokeWidthFloorsAtOneDp() {
        // prIconStrokeWidthPx(side·density, density): 1·density px at sides
        // 11 and 12 dp, 1.00625·density at 14 dp, 1.725·density at 24 dp —
        // checked at density 1 and 3 (the ticket's two densities).
        for (d in listOf(1f, 3f)) {
            assertEquals("11 dp @ $d", 1f * d, prIconStrokeWidthPx(11f * d, d), 1e-6f)
            assertEquals("12 dp @ $d", 1f * d, prIconStrokeWidthPx(12f * d, d), 1e-6f)
            assertEquals("14 dp @ $d", 1.00625f * d, prIconStrokeWidthPx(14f * d, d), 1e-4f)
            assertEquals("24 dp @ $d", 1.725f * d, prIconStrokeWidthPx(24f * d, d), 1e-4f)
        }
    }

    @Test
    fun strokeWidthIsTheBoostedSvgStrokeNotThePastedFloor() {
        // 1.5 · (side/24) · 1.15 above the floor, not #621's 1 px floor: at
        // density 1 a 12 dp side would give 0.8625 without the floor.
        assertEquals(0.8625f, 1.5f * (12f / 24f) * 1.15f, 1e-6f)
        assertEquals(1f, prIconStrokeWidthPx(12f, 1f), 1e-6f)
    }
}

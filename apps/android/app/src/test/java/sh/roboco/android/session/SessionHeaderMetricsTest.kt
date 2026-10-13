package sh.roboco.android.session

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults

/**
 * [SessionHeaderMetrics]' pure math (android-ui-parity #52), as a **plain
 * JUnit** class — no Robolectric runner, no composition, part of the merge
 * check. Every value is pinned by the ticket's acceptance criteria: the
 * worked-widths table, the natural Geist line boxes, and the baselines
 * measured from `ios-bubble-light.png` (title 81.5 − 62 = 19.5, subtitle
 * 99.2 − 62 = 37.2 at @3x).
 */
class SessionHeaderMetricsTest {

    @Test
    fun titleMaxWidthPinsTheWorkedTable() {
        assertEquals(240f, SessionHeaderMetrics.titleMaxWidth(402f, showBack = true), 1e-4f)
        assertEquals(240f, SessionHeaderMetrics.titleMaxWidth(412f, showBack = true), 1e-4f)
        assertEquals(224f, SessionHeaderMetrics.titleMaxWidth(360f, showBack = true), 1e-4f)
        assertEquals(184f, SessionHeaderMetrics.titleMaxWidth(320f, showBack = true), 1e-4f)
        // No back circle: the leading side is 16, but the trailing ⋯ side
        // (60) still bounds it, so the value is unchanged (184).
        assertEquals(184f, SessionHeaderMetrics.titleMaxWidth(320f, showBack = false), 1e-4f)
        assertEquals(0f, SessionHeaderMetrics.titleMaxWidth(100f, showBack = true), 1e-4f)
    }

    @Test
    fun titleMaxWidthBindsTheCapThenTheFormulaThenNeverNegative() {
        // Wide screens: the 240 cap binds first (the reference iPhone's
        // 21 dp gap to each circle).
        assertEquals(240f, SessionHeaderMetrics.titleMaxWidth(500f, showBack = true), 1e-4f)
        // Custom geometry: a wider ⋯ slot binds the formula …
        assertEquals(
            154f, // 402 − 2 × (16 + 100 + 8)
            SessionHeaderMetrics.titleMaxWidth(402f, showBack = true, button = 100f),
            1e-4f,
        )
        // … and showBack never widens it: the trailing ⋯ side (16 + button)
        // always bounds the leading one, exactly as in the AC table
        // (320 true == 320 false).
        assertEquals(
            154f,
            SessionHeaderMetrics.titleMaxWidth(402f, showBack = false, button = 100f),
            1e-4f,
        )
        // A custom cap and a zero gap: 460 − 2 × (16 + 44).
        assertEquals(340f, SessionHeaderMetrics.titleMaxWidth(460f, showBack = false, gap = 0f, cap = 500f), 1e-4f)
        // Never negative, at any width.
        assertEquals(0f, SessionHeaderMetrics.titleMaxWidth(0f, showBack = true), 1e-4f)
    }

    @Test
    fun titleViewHeightsAreTheNaturalLineBoxes() {
        assertEquals(37.4f, SessionHeaderMetrics.titleViewHeight(hasSubtitle = true), 0.01f)
        assertEquals(20.8f, SessionHeaderMetrics.titleViewHeight(hasSubtitle = false), 0.01f)
    }

    @Test
    fun titleViewTopsCentreTheStackInTheRow() {
        assertEquals(3.3f, SessionHeaderMetrics.titleViewTop(hasSubtitle = true), 0.01f)
        assertEquals(11.6f, SessionHeaderMetrics.titleViewTop(hasSubtitle = false), 0.01f)
        // And a custom bar height: (60 − 37.4) / 2.
        assertEquals(11.3f, SessionHeaderMetrics.titleViewTop(hasSubtitle = true, bar = 60f), 0.01f)
    }

    @Test
    fun baselinesMatchTheMeasuredReference() {
        assertEquals(19.38f, SessionHeaderMetrics.titleBaseline(hasSubtitle = true), 0.01f)
        assertEquals(27.68f, SessionHeaderMetrics.titleBaseline(hasSubtitle = false), 0.01f)
        assertEquals(37.16f, SessionHeaderMetrics.subtitleBaseline(), 0.01f)
    }

    @Test
    fun contentTopInsetIsTheBarBottom() {
        // UIKit adjustedContentInset.top: safe-area top + 44.
        assertEquals(68.dp, SessionHeaderDefaults.contentTopInset(24.dp))
        assertEquals(106.dp, SessionHeaderDefaults.contentTopInset(62.dp))
        assertEquals(44.dp, SessionHeaderDefaults.contentTopInset(0.dp))
    }

    @Test
    fun theEdgeBandIsBarPlusSoftExtension() {
        // Ticket 09's default, the same band every bar uses: statusBar + 44
        // + 16. The ticket's 24 dp test inset → 84.
        assertEquals(84.dp, ScrollEdgeEffectDefaults.height(24.dp))
        assertEquals(122.dp, ScrollEdgeEffectDefaults.height(62.dp))
    }

    @Test
    fun defaultsPinTheUIKitBarGeometry() {
        assertEquals(44.dp, SessionHeaderDefaults.BarHeight)
        assertEquals(16.dp, SessionHeaderDefaults.SideMargin)
        assertEquals(44.dp, SessionHeaderDefaults.ButtonSize)
        assertEquals(240.dp, SessionHeaderDefaults.TitleMaxWidth)
        assertEquals(1.dp, SessionHeaderDefaults.TitleSpacing)
        assertEquals(8.dp, SessionHeaderDefaults.TitleItemGap)
        assertEquals(16f, SessionHeaderDefaults.TitlePt)
        assertEquals(12f, SessionHeaderDefaults.SubtitlePt)
        assertEquals(17f, SessionHeaderDefaults.IconPt)
    }
}

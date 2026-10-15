package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.PrState

/**
 * The meta line's pure math (android-ui-parity #38):
 * [SessionRowMetaMetrics.of] against the ticket's Spec table,
 * [metaSlotFrames] against `SessionCell.swift:183-190`, and the
 * [tileName]/[showsBranch]/[showsBadge] rules. Plain Kotlin, run in the
 * merge check — no FFI, no Robolectric.
 */
class SessionRowMetaTest {

    // ------------------------------------------------------------- metrics

    /**
     * The Spec table for k = 0.85, 1.0, 1.15, 1.3 and 1.6 — the rounded
     * sizes exactly, the strut values within the ticket's tolerances
     * (±0.001 for the 4-decimal entries, ±0.01 for the 2-decimal ones).
     */
    @Test
    fun metricsMatchTheSpecTableAtEveryFactor() {
        // (k, metaY, metaH, F, Fb, tile, bside, s, baseline, textTop, tileRise, branchRise)
        assertM(0.85f, 30f, 15f, 11f, 11f, 12f, 10f, 9f, 13.5430f, 2.4880f, 8.915f, 8.905f)
        assertM(1.0f, 35f, 18f, 14f, 13f, 14f, 12f, 11f, 15.0283f, 0.9583f, 10.71f, 10.615f)
        assertM(1.15f, 40f, 21f, 16f, 14f, 16f, 14f, 13f, 16.2333f, 0.1533f, 12.24f, 11.97f)
        assertM(1.3f, 46f, 23f, 18f, 16f, 18f, 16f, 14f, 17.89f, -0.20f, 13.77f, 13.68f)
        assertM(1.6f, 56f, 29f, 22f, 20f, 22f, 19f, 18f, 22.31f, 0.20f, 16.83f, 16.60f)
    }

    /** `assertM` with the two tolerances: 4-decimal strut values, the rest. */
    private fun assertM(
        k: Float,
        metaY: Float, metaH: Float,
        f: Float, fb: Float, tile: Float, bside: Float, s: Float,
        baseline: Float, textTop: Float,
        tileRise: Float, branchRise: Float,
    ) {
        val m = SessionRowMetaMetrics.of(k)
        assertEquals("metaY k=$k", metaY, m.metaY, 0f)
        assertEquals("metaH k=$k", metaH, m.metaH, 0f)
        assertEquals("nameSize k=$k", f, m.nameSize, 0f)
        assertEquals("branchSize k=$k", fb, m.branchSize, 0f)
        assertEquals("tileSide k=$k", tile, m.tileSide, 0f)
        assertEquals("branchSide k=$k", bside, m.branchSide, 0f)
        assertEquals("badgeSize k=$k", s, m.badgeSize, 0f)
        assertEquals("baseline k=$k", baseline, m.baseline, 0.001f)
        assertEquals("textTop k=$k", textTop, m.textTop, 0.001f)
        assertEquals("tileRise k=$k", tileRise, m.tileRise, 0.01f)
        assertEquals("branchRise k=$k", branchRise, m.branchRise, 0.01f)
    }

    @Test
    fun theHalfPointSizesRoundUpAtFactorOne() {
        // `TypeScale.size` rounds half away from zero: 13.5 → 14 and
        // 12.5 → 13 at k = 1 (never kotlin.math.round, and never the raw
        // 13.5/12.5 that #621 pasted).
        val m = SessionRowMetaMetrics.of(1f)
        assertEquals(14f, m.nameSize, 0f)
        assertEquals(13f, m.branchSize, 0f)
    }

    @Test
    fun theSfStrutSitsTheBaselineLowerThanGeistAlone() {
        // UIKit's default 17 pt font fills the attachment runs, so the
        // line box takes SF 17's ascender/descender at small factors and
        // the baseline lands ≈ 1.06 dp lower than a Geist-only line
        // (SessionCell.swift never sets meta.font — the ticket's decision,
        // measured on 1-off.png: title-to-meta baseline distance 23.2–23.3).
        val m = SessionRowMetaMetrics.of(1f)
        val geistOnly = (m.metaH - (1.005f + 0.295f) * m.nameSize) / 2f + 1.005f * m.nameSize
        assertEquals(1.06f, m.baseline - geistOnly, 0.01f)
        // The baseline moves down monotonically as the factor grows (the
        // Geist ascent takes over from SF's above k ≈ 1.16, but the strut
        // math stays continuous).
        assertTrue(SessionRowMetaMetrics.of(0.85f).baseline < SessionRowMetaMetrics.of(1f).baseline)
        assertTrue(SessionRowMetaMetrics.of(1f).baseline < SessionRowMetaMetrics.of(1.6f).baseline)
    }

    @Test
    fun thePlaceholderRisesNeverInflateTheGeistLine() {
        // The rises stay under Geist's ascent at every factor (0.85…1.6),
        // so the AboveBaseline placeholders never extend the line box the
        // text strut computed — the SF-strut baseline holds as drawn.
        for (k in listOf(0.85f, 1f, 1.15f, 1.3f, 1.6f)) {
            val m = SessionRowMetaMetrics.of(k)
            assertTrue("tileRise under ascent k=$k", m.tileRise < 1.005f * m.nameSize)
            assertTrue("branchRise under ascent k=$k", m.branchRise < 1.005f * m.nameSize)
            // The hang below the baseline stays inside Geist's descent, so
            // FadingText's clip box never cuts a glyph.
            assertTrue("tile hang under descent k=$k", m.tileSide - m.tileRise < 0.295f * m.nameSize)
            assertTrue("branch hang under descent k=$k", m.branchSide - m.branchRise < 0.295f * m.branchSize)
        }
    }

    // -------------------------------------------------------------- frames

    @Test
    fun framesPlaceTheBadgeAtTheSlotsRightEnd() {
        // The worked example (W = 402, k = 1, row C): slot 54…382 → 328
        // wide; badge 38×18 → slot-local (290, 0), row (344, 35); the text
        // box ends 10 dp before the badge: 280 wide (row 54…334).
        val f = metaSlotFrames(328f, SessionRowMetaMetrics.of(1f), 38f, 18f)
        assertEquals(280f, f.textW, 0f)
        assertEquals(290f, f.badgeX!!, 0f)
        assertEquals(0f, f.badgeY!!, 0f)
    }

    @Test
    fun framesWithoutABadgeTakeTheWholeSlot() {
        val f = metaSlotFrames(328f, SessionRowMetaMetrics.of(1f), null, null)
        assertEquals(328f, f.textW, 0f)
        assertNull(f.badgeX)
        assertNull(f.badgeY)
        // Either dimension alone missing still means "no badge".
        val wOnly = metaSlotFrames(328f, SessionRowMetaMetrics.of(1f), 38f, null)
        assertEquals(328f, wOnly.textW, 0f)
        assertNull(wOnly.badgeX)
    }

    @Test
    fun framesClampTheTextBoxAtZeroNeverNegative() {
        val f = metaSlotFrames(0f, SessionRowMetaMetrics.of(1f), 38f, 18f)
        assertEquals(0f, f.textW, 0f)
        // The badge is never clamped, only the text box.
        assertEquals(-38f, f.badgeX!!, 0f)
    }

    @Test
    fun framesKeepTheBadgeYUnroundedAtTheClampEnd() {
        // k = 1.6: metaH 29, badge 30 tall → y = −0.5 (iOS leaves the half
        // point; the px placement rounds it).
        val f = metaSlotFrames(100f, SessionRowMetaMetrics.of(1.6f), 44f, 30f)
        assertEquals(-0.5f, f.badgeY!!, 0f)
        assertEquals(46f, f.textW, 0f)
    }

    // ------------------------------------------------------------ helpers

    private fun vm(
        projectName: String = "zeron",
        hasProject: Boolean = true,
        branch: String? = null,
        pr: PrState? = null,
        prNumber: ULong? = null,
    ) = SessionRowVM(
        id = "abc",
        title = "T",
        projectName = projectName,
        hasProject = hasProject,
        colorIndex = 6,
        harness = null,
        branch = branch,
        pr = pr,
        prNumber = prNumber,
        status = SessionRowVM.Status.Idle,
        timeLabel = "1d",
        unseen = false,
        pinned = false,
        sendFailed = false,
    )

    @Test
    fun tileNameUsesTheProjectNameOrHome() {
        assertEquals("zeron", tileName(vm(projectName = "zeron", hasProject = true)))
        // Any projectName with hasProject = false → "Home" (the letter H).
        assertEquals("Home", tileName(vm(projectName = "MacBook Pro", hasProject = false)))
        assertEquals("Home", tileName(vm(projectName = "No project", hasProject = false)))
    }

    @Test
    fun showsBranchMatchesTheNonEmptyRule() {
        assertFalse(showsBranch(vm(branch = null)))
        assertFalse(showsBranch(vm(branch = "")))
        // Not trimmed — a blank branch still shows (SessionCell.swift:121).
        assertTrue(showsBranch(vm(branch = " ")))
        assertTrue(showsBranch(vm(branch = "main")))
    }

    @Test
    fun showsBadgeRequiresBothPrFields() {
        assertFalse(showsBadge(vm(pr = null, prNumber = null)))
        assertFalse(showsBadge(vm(pr = PrState.Open, prNumber = null)))
        assertFalse(showsBadge(vm(pr = null, prNumber = 77u)))
        assertTrue(showsBadge(vm(pr = PrState.Closed, prNumber = 77u)))
        // The same condition 37's a11y label gates its PR fragment on: no
        // PR fragment unless both are set (an Idle row has no corner word —
        // the time label is never part of the label).
        assertEquals("T, zeron", sessionRowA11yLabel(vm(pr = PrState.Open, prNumber = null)))
        assertEquals("T, zeron, Pull request 77, closed", sessionRowA11yLabel(vm(pr = PrState.Closed, prNumber = 77u)))
    }

    @Test
    fun theRowA11yLabelStillMatchesWithTheMetaSlotFilled() {
        // 37 owns the label; this only checks the fixtures' labels — the
        // semantics test asserts them against the rendered node.
        assertEquals(
            "Tool group header colors, zeron, Done, Pull request 77, closed",
            sessionRowA11yLabel(SessionRowMetaFixtures.c),
        )
        assertEquals(
            "Clean up ~/Downloads, MacBook Pro",
            sessionRowA11yLabel(SessionRowMetaFixtures.h),
        )
    }
}

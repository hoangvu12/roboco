package sh.roboco.android.design.reference

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.PrState
import sh.roboco.android.design.roundHalfAway

/**
 * The reference fixture's layout math (android-ui-parity #23): the item
 * order and the PNG's measured item tops, the header/row counts, the PR
 * and tile data, the project colour indexes, and the derived geometry
 * (mark y, tile/branch tops, the bottom chrome's insets). Pure JUnit —
 * no Robolectric; [ReferenceGeometry] is dp-only.
 */
class ReferenceFixtureTest {

    private val rows: List<RefItem.Row> =
        ReferenceFixture.items.filterIsInstance<RefItem.Row>()

    private val headers: List<RefItem.Header> =
        ReferenceFixture.items.filterIsInstance<RefItem.Header>()

    @Test
    fun layoutTopsMatchThePng() {
        val tops = ReferenceFixture.layout().map { it.second }
        assertEquals(
            listOf(-5, 57, 119, 159, 221, 283, 323, 385, 447, 487, 549, 611, 673).map { it.dp },
            tops,
        )
        assertEquals(ReferenceFixture.items.size, ReferenceFixture.layout().size)
    }

    @Test
    fun threeHeadersTenRowsInTicketOrder() {
        assertEquals(13, ReferenceFixture.items.size)
        assertEquals(listOf("P0" to 2, "Mobile" to 2, "Recent" to 4), headers.map { it.title to it.count })
        assertEquals(10, rows.size)
        // The headers sit between the rows exactly as the PNG stacks them.
        assertEquals(
            listOf("Row", "Row", "Header", "Row", "Row", "Header", "Row", "Row", "Header", "Row", "Row", "Row", "Row"),
            ReferenceFixture.items.map { it::class.simpleName },
        )
    }

    @Test
    fun rowTitlesMatchTheDemoFixtureStrings() {
        assertEquals(
            listOf(
                "Streaming veil on transcript rows",
                "Model picker catalog sync",
                "Tool group header colors",
                "Durable Object hibernation flush",
                "Transcript scroll pinning",
                "Keyboard avoidance for composer",
                "多言語テキストのレイアウト 🌏",
                "Clean up ~/Downloads",
                "Wrangler deploy hygiene",
                "Draft the launch post",
            ),
            rows.map { it.title },
        )
    }

    @Test
    fun unseenRowsAreEmphasizedAndSeenRowsCarryTimeLabels() {
        assertEquals(
            listOf(true, true, true, true, true, false, false, false, false, false),
            rows.map { it.emphasized },
        )
        assertEquals(
            listOf(null, null, null, null, null, "5h", "10m", "2h", "1d", "5d"),
            rows.map { it.time },
        )
        assertEquals(
            listOf(
                RefCorner.Done, RefCorner.Input, RefCorner.Done, RefCorner.Failed, RefCorner.Done,
                RefCorner.Time, RefCorner.Time, RefCorner.Time, RefCorner.Time, RefCorner.Time,
            ),
            rows.map { it.corner },
        )
    }

    @Test
    fun prBadgesMatchTheFixtureTable() {
        // A 90 open, B 84 merged, C 77 closed, E 102 open; D and F–J none.
        assertEquals(90uL, rows[0].pr?.first)
        assertEquals(PrState.Open, rows[0].pr?.second)
        assertEquals(84uL, rows[1].pr?.first)
        assertEquals(PrState.Merged, rows[1].pr?.second)
        assertEquals(77uL, rows[2].pr?.first)
        assertEquals(PrState.Closed, rows[2].pr?.second)
        assertEquals(102uL, rows[4].pr?.first)
        assertEquals(PrState.Open, rows[4].pr?.second)
        assertNull(rows[3].pr)
        assertNull(rows[5].pr)
        assertNull(rows[9].pr)
    }

    @Test
    fun rowHDrawsTheHomeTileAndNoBranch() {
        val h = rows[7]
        assertEquals("Home", h.tileName)
        assertNull(h.branch)
        assertEquals("MacBook Pro", h.project)
        // J is the other project-less row: "blog" → B, no branch either.
        assertNull(rows[9].branch)
        assertEquals("blog", rows[9].tileName)
    }

    @Test
    fun colorIndexesMatchTheProjectPathHash() {
        assertEquals(
            listOf(5, 5, 5, 0, 5, 5, 5, 6, 0, 6),
            rows.map { it.colorIndex },
        )
    }

    @Test
    fun lastRowEndsJustAboveTheAccessory() {
        val lastBottom = ReferenceFixture.layout().last().second + ReferenceGeometry.RowHeight
        assertEquals(735.dp, lastBottom)
        assertTrue(lastBottom <= ReferenceGeometry.Accessory.top + 1.dp)
    }

    @Test
    fun bottomChromeGapsMatchThePng() {
        assertEquals(8.dp, ReferenceGeometry.Search.left - ReferenceGeometry.TabPill.right)
        assertEquals(22.dp, ReferenceGeometry.Height - ReferenceGeometry.TabPill.bottom)
        assertEquals(22.dp, ReferenceGeometry.Height - ReferenceGeometry.Search.bottom)
        // The accessory ends 8 dp above the tab pill.
        assertEquals(8.dp, ReferenceGeometry.TabPill.top - ReferenceGeometry.Accessory.bottom)
    }

    @Test
    fun markYMatchesTheSessionCellFormula() {
        // baseline = titleY + (titleH − titleFont.lineHeight)/2 + titleFont.ascender
        val baseline = 10f +
            (22f - GeistMetrics.LINE_HEIGHT * ReferenceGeometry.TITLE_PT) / 2f +
            GeistMetrics.ASCENT * ReferenceGeometry.TITLE_PT
        // round(baseline − xHeight·size/2 − mark/2) — the ticket's table.
        assertEquals(12f, roundHalfAway(baseline - 0.534f * 17f / 2f - 10f), 1e-4f)
        assertEquals(13f, roundHalfAway(baseline - 0.532f * 17f / 2f - 10f), 1e-4f)
        assertEquals(12.dp, ReferenceGeometry.markY(0.534f))
        assertEquals(13.dp, ReferenceGeometry.markY(0.532f))
        // The shipped constants equal the formula at the two faces' x-heights.
        assertEquals(ReferenceGeometry.markY(0.534f), ReferenceGeometry.MarkYEmphasized)
        assertEquals(ReferenceGeometry.markY(0.532f), ReferenceGeometry.MarkYSeen)
    }

    @Test
    fun tileAndBranchTopsSitOnTheMetaBaseline() {
        val baseline = ReferenceGeometry.MetaBaseline.value
        assertEquals(baseline - (0.530f * 14f + 14f) / 2f, ReferenceGeometry.TileTop.value, 0.01f)
        assertEquals(baseline - (0.710f * 13f + 12f) / 2f, ReferenceGeometry.BranchTop.value, 0.01f)
        // The meta slot itself (SessionCell.swift:184-190 at k = 1).
        assertEquals(35.dp, ReferenceGeometry.MetaY)
        assertEquals(18.dp, ReferenceGeometry.MetaH)
        assertEquals(50.03f, ReferenceGeometry.MetaBaseline.value, 1e-4f)
    }
}

package sh.roboco.android.design.reference

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.PrState
import sh.roboco.android.design.roundHalfAway

/**
 * The fixed iOS geometry of the reference board (android-ui-parity #23):
 * the numbers measured on `1-off.png` (460 × 1000 px of a 402 × 874 pt
 * iPhone canvas, 1 pt = 1.14428 px) as **dp**, 1:1 with the iOS points.
 *
 * Everything here is a constant on purpose: the mock is a fixed-geometry
 * reference picture that must never reflow with the device font scale, so
 * [SessionsReferenceMock] draws `fixed(pt)` text at the already-rounded
 * iOS sizes (17/14/13, never 16.5/13.5/12.5) inside these frames.
 */
internal object ReferenceGeometry {
    /** 1-off.png's device canvas: 402 × 874 pt (iPhone 16/17 Pro). */
    val Width = 402.dp
    val Height = 874.dp

    /**
     * The iPhone safe-area top (the status bar band). The edge effect's
     * `statusBarTop` input, so its band lines up with the PNG's; the mock
     * itself draws no status-bar glyphs.
     */
    val StatusBarTop = 62.dp

    /** The collapsed nav-bar row: 44 pt, the inline title centred in it. */
    val BarHeight = 44.dp

    /** The list's first item top (the scrolled-off pinned row). */
    val FirstItemTop = (-5).dp

    /** `SessionCell.swift:49` — `(62 * TypeScale.factor).rounded()` at 1. */
    val RowHeight = 62.dp

    /** `SessionCell.swift:234-306` — `(40 * k).rounded()` at 1. */
    val HeaderHeight = 40.dp

    /** The row's left/right inset (`left`/`right` = 20, `:157`). */
    val Side = 20.dp

    /** `markSide = 20` (`SessionCell.swift:194`). */
    val MarkSide = 20.dp
    val MarkYEmphasized = 12.dp
    val MarkYSeen = 13.dp

    /** `textX = left + mark + 14` = 20 + 20 + 14 (`:183`). */
    val TextX = 54.dp

    /** `titleY`/`titleH` = `(10k).rounded()`/`(22k).rounded()` at 1. */
    val TitleY = 10.dp
    val TitleH = 22.dp

    /** `metaY`/`metaH` = `(35k).rounded()`/`(18k).rounded()` at 1. */
    val MetaY = 35.dp
    val MetaH = 18.dp

    /**
     * The meta line's baseline, row-relative: 35 + (18 − 20.32)/2 + 16.19.
     * UIKit fills the meta label's attribute-less runs (the tile and branch
     * attachments, the bare three spaces) with its default **system 17 pt**
     * font, so the line strut is SF-17, not Geist-14 — ticket 38's
     * `SessionRowMetaMetrics` at k = 1 (baseline 15.0283 from the slot top
     * = 50.03 from the row top). The PNG measures ≈50.2–50.5.
     */
    val MetaBaseline = 50.03.dp

    /** The 14-dp tile's top: baseline − (xHeight·14 + 14)/2 = 39.32. */
    val TileTop = 39.32.dp

    /** The 12-dp branch icon's top: baseline − (capHeight·13 + 12)/2 = 39.41. */
    val BranchTop = 39.41.dp

    /** The 14-dp project tile's side (`side = 14`, `:116`). */
    val TileSide = 14.dp

    /** The branch icon's side (`:121-128`). */
    val BranchSide = 12.dp

    /**
     * The project name's first-glyph origin x: 54 + 14 + 2 × Geist-14
     * space (0.25 em = 3.5). The PNG's 75.85–76.0 is the `z` ink (left
     * bearing 0.71), not the origin.
     */
    val NameX = 75.dp

    /** Name → branch-icon gap: three SF-17 spaces (ticket 38's `BRANCH_GAP`). */
    val NameToBranch = 13.dp

    /** Branch icon → branch text: one Geist-13 space (`:129`). */
    val BranchToText = 3.25.dp

    // The already-rounded iOS font sizes (TypeScale.size at factor 1).
    const val TITLE_PT = 17f
    const val META_PT = 14f
    const val BRANCH_PT = 13f
    const val HEADER_PT = 14f
    const val CORNER_PT = 13f

    /** `h = 20; y = height − h − 6` = 14 (`:295-306`). */
    val HeaderTextY = 14.dp
    val HeaderTextH = 20.dp

    /** `count.maxX + 7` (`:276-279`). */
    val CountGap = 7.dp

    /** The nav-bar ⋯ glass circle: 44 pt centred at (363.5, 84). */
    val More = DpRect(342.dp, 62.dp, 386.dp, 106.dp)

    /** The white glass accessory capsule (system-rendered, measured). */
    val Accessory = DpRect(21.dp, 734.dp, 381.dp, 782.dp)

    /** The white glass tab pill (system-rendered, measured). */
    val TabPill = DpRect(21.dp, 790.dp, 310.dp, 852.dp)

    /** The separate glass search circle (system-rendered, measured). */
    val Search = DpRect(318.dp, 790.dp, 380.dp, 852.dp)

    /** px per dp on the board: the PNGs' 460 px over the 402 dp canvas. */
    const val BOARD_SCALE = 460f / 402f

    /**
     * The harness mark's row y (`SessionCell.swift:163-173` at k = 1):
     * `round(baseline − titleFont.xHeight/2 − mark/2)` with
     * `baseline = titleY + (titleH − titleFont.lineHeight)/2 + titleFont.ascender`.
     * Geist 17 (line 1.3 em, ascent 1.005 em) with xHeight 0.534 (SemiBold)
     * → 12, 0.532 (Medium) → 13 — the same table ticket 37 ships.
     */
    fun markY(xHeight: Float): Dp {
        val baseline = TitleY.value +
            (TitleH.value - GeistMetrics.LINE_HEIGHT * TITLE_PT) / 2f +
            GeistMetrics.ASCENT * TITLE_PT
        return roundHalfAway(baseline - xHeight * TITLE_PT / 2f - MarkSide.value / 2f).dp
    }
}

/**
 * A row's corner state — the `SessionCell.corner` precedence
 * (`ios:Threads/SessionCell.swift:95-104`) reduced to the four states the
 * fixture shows.
 */
internal enum class RefCorner { Done, Failed, Input, Time }

/**
 * One list item of the mock: a section header or a session row, with the
 * demo workspace's verbatim strings (the project names "zeron" and
 * "Zeron iOS" are demo fixtures, not branding — keep them exactly as in
 * the PNG so text widths match on the side-by-side board).
 */
internal sealed interface RefItem {
    data class Header(val title: String, val count: Int) : RefItem

    data class Row(
        val harness: String,
        val title: String,
        /** Unseen → SemiBold title in full `Palette.text`. */
        val emphasized: Boolean,
        /** The visible project/device text (the meta line's name). */
        val project: String,
        /** The monogram's source ("Home" for the project-less rows). */
        val tileName: String,
        /** `project_color_index(path)` mod 8. */
        val colorIndex: Int,
        val branch: String?,
        val corner: RefCorner,
        val time: String? = null,
        val pr: Pair<ULong, PrState>? = null,
    ) : RefItem
}

/**
 * The ten rows and three headers of `1-off.png`, exactly as the upstream
 * demo workspace renders them (rows A–J and the P0 / Mobile / Recent
 * groups). Pinned rows A/B are the two scrolled-off rows under the soft
 * scroll edge; their corners and PRs were read off the (blurred) PNG and
 * cross-checked against the demo fixture's PR data.
 */
internal object ReferenceFixture {

    val items: List<RefItem> = listOf(
        RefItem.Row(
            harness = "claude-code",
            title = "Streaming veil on transcript rows",
            emphasized = true,
            project = "zeron",
            tileName = "zeron",
            colorIndex = 5,
            branch = "veil-fade",
            corner = RefCorner.Done,
            pr = 90uL to PrState.Open,
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Model picker catalog sync",
            emphasized = true,
            project = "zeron",
            tileName = "zeron",
            colorIndex = 5,
            branch = "catalog-sync",
            corner = RefCorner.Input,
            pr = 84uL to PrState.Merged,
        ),
        RefItem.Header("P0", 2),
        RefItem.Row(
            harness = "codex",
            title = "Tool group header colors",
            emphasized = true,
            project = "zeron",
            tileName = "zeron",
            colorIndex = 5,
            branch = "fix/tool-colors",
            corner = RefCorner.Done,
            pr = 77uL to PrState.Closed,
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Durable Object hibernation flush",
            emphasized = true,
            project = "edge",
            tileName = "edge",
            colorIndex = 0,
            branch = "main",
            corner = RefCorner.Failed,
        ),
        RefItem.Header("Mobile", 2),
        RefItem.Row(
            harness = "codex",
            title = "Transcript scroll pinning",
            emphasized = true,
            project = "Zeron iOS",
            tileName = "Zeron iOS",
            colorIndex = 5,
            branch = "scroll-pinning",
            corner = RefCorner.Done,
            pr = 102uL to PrState.Open,
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Keyboard avoidance for composer",
            emphasized = false,
            project = "Zeron iOS",
            tileName = "Zeron iOS",
            colorIndex = 5,
            branch = "main",
            corner = RefCorner.Time,
            time = "5h",
        ),
        RefItem.Header("Recent", 4),
        RefItem.Row(
            harness = "claude-code",
            title = "多言語テキストのレイアウト 🌏",
            emphasized = false,
            project = "zeron",
            tileName = "zeron",
            colorIndex = 5,
            branch = "main",
            corner = RefCorner.Time,
            time = "10m",
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Clean up ~/Downloads",
            emphasized = false,
            project = "MacBook Pro",
            tileName = "Home",
            colorIndex = 6,
            branch = null,
            corner = RefCorner.Time,
            time = "2h",
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Wrangler deploy hygiene",
            emphasized = false,
            project = "edge",
            tileName = "edge",
            colorIndex = 0,
            branch = "main",
            corner = RefCorner.Time,
            time = "1d",
        ),
        RefItem.Row(
            harness = "claude-code",
            title = "Draft the launch post",
            emphasized = false,
            project = "blog",
            tileName = "blog",
            colorIndex = 6,
            branch = null,
            corner = RefCorner.Time,
            time = "5d",
        ),
    )

    /**
     * The screen-y top of each item, in dp, starting at
     * [ReferenceGeometry.FirstItemTop]: plain items stacked with no gaps,
     * 62 dp per row and 40 dp per header — the measured tops −5, 57, 119,
     * 159, 221, 283, 323, 385, 447, 487, 549, 611, 673.
     */
    fun layout(): List<Pair<RefItem, Dp>> {
        var top = ReferenceGeometry.FirstItemTop
        return items.map { item ->
            val at = top
            top += when (item) {
                is RefItem.Row -> ReferenceGeometry.RowHeight
                is RefItem.Header -> ReferenceGeometry.HeaderHeight
            }
            item to at
        }
    }
}

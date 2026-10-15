package sh.roboco.android.sessions

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontWeight
import sh.roboco.android.core.GeistMetrics
import sh.roboco.android.design.roundHalfAway
import kotlin.math.max

/**
 * The meta-line helpers and geometry (android-ui-parity #38): line 2 of
 * the session row — the project tile, project name, branch and PR badge
 * (`ios:Threads/SessionCell.swift:112-137`). The composable that draws it
 * is [SessionRowMetaLine]; [SessionRow]'s `meta` slot (ticket 37) hosts it.
 */

/** iOS: the project-less rows' tile name — `"Home"` (`SessionCell.swift:117`). */
const val HOME_TILE_NAME = "Home"

/**
 * iOS: `vm.hasProject ? vm.projectName : "Home"` (`SessionCell.swift:117`)
 * — the name the tile's monogram is drawn from. The visible text stays
 * [SessionRowVM.projectName] (the engine device name for project-less
 * rows, `"No project"` when there is none — both mapped by 37's
 * `SessionRowVM.from`, not redefined here).
 */
fun tileName(vm: SessionRowVM): String = if (vm.hasProject) vm.projectName else HOME_TILE_NAME

/**
 * iOS: the branch block shows when `branch` is non-nil **and non-empty**
 * (`!b.isEmpty`, `SessionCell.swift:121`). Not trimmed — a branch of
 * `" "` still shows.
 */
fun showsBranch(vm: SessionRowVM): Boolean = !vm.branch.isNullOrEmpty()

/**
 * iOS: the badge shows when both `pr` and `prNumber` are non-nil
 * (`SessionCell.swift:132`) — the same condition 37's
 * [sessionRowA11yLabel] uses for its PR fragment.
 */
fun showsBadge(vm: SessionRowVM): Boolean = vm.pr != null && vm.prNumber != null

/**
 * The meta line's scale-dependent geometry — `SessionCell.swift:112-131,
 * 157-190` as pure dp math, the unit-test oracle for the composable's px
 * placement. All lengths are dp (Float); `k` is
 * [sh.roboco.android.design.LocalTypeScale]'s `factor`.
 *
 * Rounding is [roundHalfAway] (Swift `.rounded()`), so the half-point
 * sizes always round up at `k = 1` (`size(13.5) = 14`, `size(12.5) = 13`)
 * — **never** `kotlin.math.round` (half to even).
 *
 * UIKit's default-font side effects (the ticket's decision): the meta
 * `FadingLabel` never sets `font`, so the attachment runs and the bare
 * `"   "` separator run in system SF 17 pt. This reproduces both measured
 * effects: the [BRANCH_GAP] is three SF-17 spaces (≈ 13.0 dp, measured
 * 12.97–13.17 on `1-off.png`), and the line box takes SF 17's
 * ascender/descender ([SYS_ASC]/[SYS_DESC] × [SYS_SIZE]) whenever those
 * exceed Geist's, which puts the baseline ≈ 1.06 dp lower than Geist
 * alone. The strut is **not** type-scaled — the default font is a fixed
 * 17 pt at every text size.
 */
object SessionRowMetaMetrics {

    /** SF Pro's ascender per em: `UIFont.systemFont(ofSize: 17)` ascends 16.1865. */
    const val SYS_ASC = 1950f / 2048f

    /** SF Pro's descender per em: 4.1006 at 17 pt. */
    const val SYS_DESC = 494f / 2048f

    /** The `UILabel` default font size — fixed, never type-scaled. */
    const val SYS_SIZE = 17f

    /**
     * The name → branch-icon gap: three SF-17 spaces, measured
     * 12.97–13.17 dp on the reference PNG (three Geist-14 spaces would be
     * 10.5). **Not** type-scaled.
     */
    const val BRANCH_GAP = 13f

    /** The meta text box's trailing gap before the PR badge (`:189`). */
    const val BADGE_GAP = 10f

    /**
     * The meta line's metrics at one factor.
     *
     * @property metaY the slot top's row y — 37's `metaSlot.y`; a test
     *   oracle only (the composable never reads it: 37 owns the slot's y).
     * @property metaH the slot height — 37's `metaSlot.h`.
     * @property nameSize `F` — the project name's Geist Regular size,
     *   `r(13.5k)`.
     * @property branchSize `Fb` — the branch's size, `r(12.5k)`.
     * @property tileSide the project tile's side, `r(14k)`.
     * @property branchSide the branch icon's side, `r(12k)`.
     * @property badgeSize `s` — the PR badge size passed to ticket 12's
     *   `PrBadge`, `r(11k)`.
     * @property baseline the text baseline from the slot top:
     *   `(metaH − (A + D)) / 2 + A` with the SF-17 strut (see the object
     *   KDoc) — 15.0283 at `k = 1`, ≈ 1.06 dp lower than Geist alone.
     * @property textTop the text line's top from the slot top
     *   (`baseline − 1.005·F`); the line is `1.3·F` tall, single line.
     * @property tileRise the tile placeholder's height: the tile's rise
     *   above the baseline, `(xHeight·F + tile)/2` — its centre sits
     *   `xHeight/2` above the baseline (`SessionCell.swift:118-120`).
     * @property branchRise the branch icon placeholder's rise above the
     *   baseline, `(capHeight·Fb + bside)/2` (`SessionCell.swift:124-127`).
     */
    @Immutable
    data class M(
        val metaY: Float,
        val metaH: Float,
        val nameSize: Float,
        val branchSize: Float,
        val tileSide: Float,
        val branchSide: Float,
        val badgeSize: Float,
        val baseline: Float,
        val textTop: Float,
        val tileRise: Float,
        val branchRise: Float,
    )

    /**
     * The metrics for `k`. The branch font's ascent/descent never exceed
     * the name font's (`branchSize ≤ nameSize` for every k in
     * [0.85, 1.6]), so they never contribute to the line strut.
     */
    fun of(k: Float): M {
        val r = ::roundHalfAway
        val metaY = r(35f * k)
        val metaH = r(18f * k)
        val nameSize = r(13.5f * k)
        val branchSize = r(12.5f * k)
        val tileSide = r(14f * k)
        val branchSide = r(12f * k)
        val badgeSize = r(11f * k)
        // The line strut: SF 17's metrics where they exceed Geist's
        // (UIKit's default font fills the attachment runs).
        val a = max(SYS_ASC * SYS_SIZE, GeistMetrics.ASCENT * nameSize)
        val d = max(SYS_DESC * SYS_SIZE, GeistMetrics.DESCENT * nameSize)
        val baseline = (metaH - (a + d)) / 2f + a
        return M(
            metaY = metaY,
            metaH = metaH,
            nameSize = nameSize,
            branchSize = branchSize,
            tileSide = tileSide,
            branchSide = branchSide,
            badgeSize = badgeSize,
            baseline = baseline,
            textTop = baseline - GeistMetrics.ASCENT * nameSize,
            tileRise = (GeistMetrics.xHeight(FontWeight.Normal) * nameSize + tileSide) / 2f,
            branchRise = (GeistMetrics.CAP_HEIGHT * branchSize + branchSide) / 2f,
        )
    }
}

/**
 * The meta slot's frames — `SessionCell.swift:183-190` in slot-local
 * coordinates (slot x 0 = row x 54, slot y 0 = row y `metaY`). The dp
 * oracle for [SessionRowMetaLine]'s px placement.
 *
 * @property textW the meta text box's width; its x is always 0 (the slot's
 *   left edge = the title's leading edge) and its y is `m.textTop`.
 * @property badgeX the PR badge's slot-local x — the slot's right end
 *   (`slotW − badgeW`), which is the row's `W − 20 − badgeW`: the slot
 *   ends at `W − 20`, so the pixel position matches iOS's row-space frame.
 *   Null with no badge.
 * @property badgeY the badge's slot-local y, `(metaH − badgeH) / 2`, **not
 *   rounded** (iOS leaves it on a half point; −0.5 at k = 1.6). Null with
 *   no badge.
 */
@Immutable
data class MetaSlotFrames(
    val textW: Float,
    val badgeX: Float?,
    val badgeY: Float?,
)

/**
 * The frames for one slot width. iOS clamps only the text width, never
 * the badge; with 37's `slotW = max(0, W − 74)` this differs from iOS only
 * for rows narrower than 74 dp, which never occur.
 */
fun metaSlotFrames(slotW: Float, m: SessionRowMetaMetrics.M, badgeW: Float?, badgeH: Float?): MetaSlotFrames {
    if (badgeW == null || badgeH == null) return MetaSlotFrames(slotW, null, null)
    val badgeX = slotW - badgeW
    return MetaSlotFrames(
        textW = max(0f, badgeX - SessionRowMetaMetrics.BADGE_GAP),
        badgeX = badgeX,
        badgeY = (m.metaH - badgeH) / 2f,
    )
}

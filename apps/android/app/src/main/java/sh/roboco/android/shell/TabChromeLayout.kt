package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState

/**
 * Test-only (android-ui-parity #28): the chrome layout's morph progress
 * `p`, as [TabChromeLayout] reads it — for `MinimizeOnScrollTest`. Lives on
 * the layout's own semantics node; the value is written from inside the
 * `semantics` block, which the semantics observer re-runs whenever the
 * spring moves, so no recomposition is involved.
 */
val TabMinimizeProgressKey = SemanticsPropertyKey<Float>("TabMinimizeProgress")

/**
 * The chrome layout (android-ui-parity #28, extended by #29): the
 * multi-content `Layout` that places the tab pill, the search circle and
 * the "New session" accessory from [tabChromeGeometry] — every rect
 * lerped between the expanded and minimized geometry by the morph
 * progress. Ticket 26's `BoxWithConstraints`/`Row` placement and 27's
 * fixed capsule row both become this one layout.
 *
 * **The search morph (#29):** [searchProgress] `p` and [searchActive]
 * reshape the same three slots —
 *  - the pill and search rects lerp from their minimize-position
 *    (`pill0`/`search0` at the current `m`) toward
 *    [SearchMorphGeometry.returnTarget] /
 *    [SearchMorphGeometry.fieldTarget] — both parts bottom-aligned with
 *    the row, the outer edges never sliding sideways;
 *  - the accessory's bottom follows `accessoryBottomY(p)` (it never rides
 *    the IME), its horizontal rect staying the geometry's; it is placed
 *    **first**, so the row draws above it (the #577 z-order);
 *  - while the search chrome is on screen (`searchActive` or `p > 0`),
 *    the row's bottom rises to `max(B, imeBottom + 8)` — it rides on the
 *    IME, frame by frame through `WindowInsets.ime` (no spring of our
 *    own). [imeLiftDp] overrides the inset read for the gallery /
 *    screenshot fixture.
 *
 *  - [progress] and [searchProgress] are read **only inside the measure
 *    block** (and the semantics key above): the springs cause a relayout
 *    per frame, never a recomposition. Do not read `progress.value` in a
 *    composable body.
 *  - The horizontal system insets (navigation bar ∪ display cutout) are
 *    read inside the measure block too — the `WindowInsets` getters are
 *    snapshot reads — and converted start/end so the row mirrors exactly
 *    in RTL, like ticket 26.
 *  - When [hasAccessory] is false the accessory slot is still **measured**
 *    (at its would-be rect) but never placed: ticket 30 keeps it in
 *    composition and never removes and re-inserts it.
 *  - The layout never clips its children: `GlassSurface` draws its shadow
 *    outside its bounds (07), so a `clip` here would cut it.
 *  - [onRects] is invoked from the placement block (main thread, once per
 *    layout) — ticket 30's chrome-top hook, ticket 29's IME-lift
 *    publication (`SearchTabState.searchImeLift`).
 *
 * @param progress the minimize morph position 0…1 (unclamped); measure-phase read
 * @param bottomInset ticket 26's `tabBarBottomGap`: the row's bottom gap
 *   above the shell's bottom edge
 * @param hasAccessory false = pill + search only, nothing between them
 * @param searchProgress the search morph position 0…1 (unclamped,
 *   29); measure-phase read, 0 by default = 28's behaviour unchanged
 * @param searchActive Search is the selected tab (the target, 29)
 * @param imeLiftDp the fixture's stand-in for the IME lift (dp, 29);
 *   null = read `WindowInsets.ime`
 * @param modifier the caller's modifier; pass `fillMaxSize()` so the
 *   geometry gets the whole shell
 * @param onRects receives the placed rects each layout pass (ticket 30)
 * @param pill the tab pill ([GlassTabPill], or [SearchReturnCircle] while
 *   the search chrome is on screen); fills its incoming constraints
 * @param search the search slot ([SearchTabField]); fills its incoming
 *   constraints
 * @param accessory the "New session" capsule ([NewSessionAccessory]);
 *   always composed, only conditionally placed
 */
@Composable
fun TabChromeLayout(
    progress: () -> Float,
    bottomInset: Dp,
    hasAccessory: Boolean,
    modifier: Modifier = Modifier,
    onRects: (TabChromeRects) -> Unit = {},
    searchProgress: () -> Float = { 0f },
    searchActive: Boolean = false,
    imeLiftDp: Float? = null,
    pill: @Composable () -> Unit,
    search: @Composable () -> Unit,
    accessory: @Composable () -> Unit,
) {
    // The insets objects are obtained in composition (the getters are
    // @Composable); the *values* are snapshot reads taken in the measure
    // block below, so inset changes re-layout without recomposition.
    val side = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
    val ime = WindowInsets.ime
    Layout(
        contents = listOf(pill, search, accessory),
        modifier = modifier.semantics { this[TabMinimizeProgressKey] = progress() },
    ) { measurables, constraints ->
        val rtl = layoutDirection == LayoutDirection.Rtl
        // The insets getters are physical left/right and direction-agnostic;
        // geometry works in start/end terms and placement mirrors with
        // placeRelative, exactly like ticket 26.
        val insetStart = (if (rtl) side.getRight(this, layoutDirection) else side.getLeft(this, layoutDirection))
            .toDp().value
        val insetEnd = (if (rtl) side.getLeft(this, layoutDirection) else side.getRight(this, layoutDirection))
            .toDp().value

        val m = progress()
        val searchP = searchProgress()
        val searchOn = SearchMorph.chromeShowing(searchActive, searchP)

        // The IME ride (#29): the row's bottom rises with the IME while the
        // search chrome is on screen (rowBottom returns b otherwise, so a
        // focused composer elsewhere never lifts the tab bar).
        val heightDp = constraints.maxHeight.toDp().value
        val imeBottomDp = if (imeLiftDp != null) {
            bottomInset.value + imeLiftDp - SearchMorphGeometry.ImeGap
        } else {
            ime.getBottom(this).toDp().value
        }
        val rowBottom = SearchMorphGeometry.rowBottom(bottomInset.value, imeBottomDp, searchOn)
        val lift = rowBottom - bottomInset.value

        val rects = tabChromeGeometry(
            width = constraints.maxWidth.toDp().value,
            height = heightDp,
            bottomInset = rowBottom,
            hasAccessory = hasAccessory,
            progress = m,
            insetStart = insetStart,
            insetEnd = insetEnd,
        )
        // The accessory is measured at its would-be rect even when it is not
        // placed (hasAccessory = false): ticket 30 keeps it in composition.
        val measureRects = if (hasAccessory) rects else tabChromeGeometry(
            width = constraints.maxWidth.toDp().value,
            height = heightDp,
            bottomInset = rowBottom,
            hasAccessory = true,
            progress = m,
            insetStart = insetStart,
            insetEnd = insetEnd,
        )

        // The search morph (#29): row-local pill0/search0 at the current m,
        // lerped toward the search targets; the accessory's bottom follows
        // p only (it never rides the IME).
        val rowL = rects.rowLeft
        val rowW = rects.rowWidth
        val rowTop = heightDp - rowBottom - TabChromeMetrics.PillHeight

        fun toRow(r: Rect): BarRect =
            BarRect(r.left - rowL, r.top - rowTop, r.width, r.height)

        fun toShell(r: BarRect): Rect = Rect(
            rowL + r.x,
            rowTop + r.y,
            rowL + r.x + r.w,
            rowTop + r.y + r.h,
        )

        /** The accessory's search rect: horizontal from `base`, bottom from p, never lifted. */
        fun searchAccessoryRect(base: Rect?): Rect? {
            if (base == null) return null
            val restRowTop = heightDp - bottomInset.value - TabChromeMetrics.PillHeight
            val bottom = restRowTop + SearchMorphGeometry.accessoryBottomY(searchP)
            return Rect(
                base.left,
                bottom - TabChromeMetrics.AccessoryHeight,
                base.right,
                bottom,
            )
        }

        val pillRect: Rect
        val searchRect: Rect
        val accessoryRect: Rect?
        if (searchOn) {
            pillRect = toShell(SearchMorphGeometry.returnRect(toRow(rects.pill), searchP))
            searchRect = toShell(SearchMorphGeometry.fieldRect(toRow(rects.search), rowW, searchP))
            accessoryRect = searchAccessoryRect(rects.accessory)
        } else {
            pillRect = rects.pill
            searchRect = rects.search
            accessoryRect = rects.accessory
        }
        val accessoryMeasureRect =
            if (hasAccessory) accessoryRect else searchAccessoryRect(measureRects.accessory)

        fun px(value: Float): Int = value.dp.roundToPx()
        val pillPlaceable = measurables.getOrNull(0)?.firstOrNull()
            ?.measure(Constraints.fixed(px(pillRect.width), px(pillRect.height)))
        val searchPlaceable = measurables.getOrNull(1)?.firstOrNull()
            ?.measure(Constraints.fixed(px(searchRect.width), px(searchRect.height)))
        val accessoryMeasure = accessoryMeasureRect
        val accessoryPlaceable = if (accessoryMeasure != null) {
            measurables.getOrNull(2)?.firstOrNull()
                ?.measure(Constraints.fixed(px(accessoryMeasure.width), px(accessoryMeasure.height)))
        } else {
            null
        }

        // Placement coordinates are precomputed in the measure block: the
        // placement scope is not a Density.
        layout(constraints.maxWidth, constraints.maxHeight) {
            // The accessory first: the row draws above it (#29's z-order).
            if (hasAccessory && accessoryRect != null) {
                accessoryPlaceable?.placeRelative(px(accessoryRect.left), px(accessoryRect.top))
            }
            pillPlaceable?.placeRelative(px(pillRect.left), px(pillRect.top))
            searchPlaceable?.placeRelative(px(searchRect.left), px(searchRect.top))
            val chromeTop = minOf(
                pillRect.top,
                searchRect.top,
                accessoryRect?.top ?: Float.POSITIVE_INFINITY,
            )
            onRects(
                TabChromeRects(
                    pill = pillRect,
                    search = searchRect,
                    accessory = accessoryRect,
                    chromeTop = chromeTop,
                    rowLeft = rowL,
                    rowWidth = rowW,
                    imeLift = lift,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (ticket step 12): the chrome over 22's striped backdrop at
// p = 0, 0.5 and 1 — three 402 × 300 frames stacked (each frame is the
// ticket's preview size; the stacked column shows the whole morph at
// once), light and dark, the accessory with live counts working 2 /
// awaiting 1.
// ---------------------------------------------------------------------------

@Composable
private fun ChromeMorphFrame(progress: Float, minimized: Boolean) {
    TabChromeLayout(
        progress = { progress },
        bottomInset = 21.dp,
        hasAccessory = true,
        modifier = Modifier.fillMaxSize(),
        pill = {
            GlassTabPill(
                selected = TabId.Sessions,
                onTabTap = {},
                minimizeProgress = { progress },
                minimized = minimized,
                onRestore = {},
                expandedWidth = tabPillExpandedWidth(402f).dp,
            )
        },
        search = { GlassSearchButton(TabId.Sessions, onTabTap = {}) },
        accessory = {
            NewSessionAccessory(
                counts = LiveCounts(working = 2, awaiting = 1),
                onTap = {},
                inline = minimized,
                labelAlpha = { 1f - progress.coerceIn(0f, 1f) },
            )
        },
    )
}

@Composable
private fun TabChromeMorphPreviewContent() {
    RobocoTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (p in listOf(0f, 0.5f, 1f)) {
                val haze = rememberHazeState()
                CompositionLocalProvider(LocalHazeState provides haze) {
                    GlassStage(
                        modifier = Modifier.requiredWidth(402.dp),
                        height = 300.dp,
                        backdropModifier = Modifier.hazeSource(haze),
                    ) {
                        ChromeMorphFrame(progress = p, minimized = p >= 1f)
                    }
                }
            }
        }
    }
}

@Preview(name = "tab chrome morph light", widthDp = 434, heightDp = 980)
@Composable
private fun TabChromeMorphLightPreview() {
    TabChromeMorphPreviewContent()
}

@Preview(name = "tab chrome morph dark", widthDp = 434, heightDp = 980, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TabChromeMorphDarkPreview() {
    TabChromeMorphPreviewContent()
}

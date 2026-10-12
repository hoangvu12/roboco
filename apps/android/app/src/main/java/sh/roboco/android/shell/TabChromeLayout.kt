package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
 * The chrome layout (android-ui-parity #28): the multi-content `Layout`
 * that places the tab pill, the search circle and the "New session"
 * accessory from [tabChromeGeometry] — every rect lerped between the
 * expanded and minimized geometry by the morph progress. Ticket 26's
 * `BoxWithConstraints`/`Row` placement and 27's fixed capsule row both
 * become this one layout.
 *
 *  - [progress] is read **only inside the measure block** (and the
 *    semantics key above): the spring causes a relayout per frame, never a
 *    recomposition. Do not read `progress.value` in a composable body.
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
 *    layout) — ticket 30's chrome-top hook.
 *
 * @param progress the morph position 0…1 (unclamped); measure-phase read
 * @param bottomInset ticket 26's `tabBarBottomGap`: the row's bottom gap
 *   above the shell's bottom edge
 * @param hasAccessory false = pill + search only, nothing between them
 * @param modifier the caller's modifier; pass `fillMaxSize()` so the
 *   geometry gets the whole shell
 * @param onRects receives the placed rects each layout pass (ticket 30)
 * @param pill the tab pill ([GlassTabPill]); fills its incoming constraints
 * @param search the round search button ([GlassSearchButton])
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
    pill: @Composable () -> Unit,
    search: @Composable () -> Unit,
    accessory: @Composable () -> Unit,
) {
    // The insets object is obtained in composition (the getters are
    // @Composable); the *values* are snapshot reads taken in the measure
    // block below, so inset changes re-layout without recomposition.
    val side = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
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

        val p = progress()
        val rects = tabChromeGeometry(
            width = constraints.maxWidth.toDp().value,
            height = constraints.maxHeight.toDp().value,
            bottomInset = bottomInset.value,
            hasAccessory = hasAccessory,
            progress = p,
            insetStart = insetStart,
            insetEnd = insetEnd,
        )
        // The accessory is measured at its would-be rect even when it is not
        // placed (hasAccessory = false): ticket 30 keeps it in composition.
        val measureRects = if (hasAccessory) rects else tabChromeGeometry(
            width = constraints.maxWidth.toDp().value,
            height = constraints.maxHeight.toDp().value,
            bottomInset = bottomInset.value,
            hasAccessory = true,
            progress = p,
            insetStart = insetStart,
            insetEnd = insetEnd,
        )

        fun px(value: Float): Int = value.dp.roundToPx()
        val pillPlaceable = measurables.getOrNull(0)?.firstOrNull()
            ?.measure(Constraints.fixed(px(rects.pill.width), px(rects.pill.height)))
        val searchPlaceable = measurables.getOrNull(1)?.firstOrNull()
            ?.measure(Constraints.fixed(px(rects.search.width), px(rects.search.height)))
        val accessoryMeasureRect = measureRects.accessory
        val accessoryPlaceable = if (accessoryMeasureRect != null) {
            measurables.getOrNull(2)?.firstOrNull()
                ?.measure(Constraints.fixed(px(accessoryMeasureRect.width), px(accessoryMeasureRect.height)))
        } else {
            null
        }

        // Placement coordinates are precomputed in the measure block: the
        // placement scope is not a Density.
        layout(constraints.maxWidth, constraints.maxHeight) {
            pillPlaceable?.placeRelative(px(rects.pill.left), px(rects.pill.top))
            searchPlaceable?.placeRelative(px(rects.search.left), px(rects.search.top))
            val placedAccessory = rects.accessory
            if (hasAccessory && placedAccessory != null) {
                accessoryPlaceable?.placeRelative(px(placedAccessory.left), px(placedAccessory.top))
            }
            onRects(rects)
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

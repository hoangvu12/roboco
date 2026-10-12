package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.TypeScale
import sh.roboco.android.design.gallery.ScrollEdgeEffectFixtureMeta
import sh.roboco.android.design.gallery.ScrollEdgeEffectFixtureTitles
import sh.roboco.android.design.gallery.ScrollEdgeEffectRowHeight
import sh.roboco.android.design.gallery.ScrollEdgeEffectRowMargin
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassTextCapsule
import sh.roboco.android.design.uiStyle

/**
 * The "Large title scaffold" fixture and gallery section
 * (android-ui-parity #25): ticket 09's row list scrolling under the whole
 * scaffold — the large title, the inline title, the ⋯ circle and the soft
 * edge. Registered in `design/Gallery.kt` (ticket 22) as
 * `GallerySection("Large title scaffold", layout = GalleryLayout.Fill) { LargeTitleGallerySection() }`
 * — `Fill`, because the scaffold owns the `LazyColumn`.
 *
 * The gallery section carries the ticket's five toggles (Mode, Back,
 * Trailing, Long title, Pinned accessory); the Light/Dark and
 * Force-reduce-motion toggles come from the gallery scaffold. Previews and
 * `LargeTitleScaffoldScreenshotTest` render [LargeTitleFixtureScaffold] at
 * a **fixed 24 dp status bar** (Robolectric has no real insets) with
 * `showBack`/`onBack` passed explicitly — 24's locals have no default, so
 * nothing outside a `NavStackHost` may use the scaffold's defaults.
 */

/** The fixture's pinned-accessory placeholder height (the ticket's gallery toggle). */
internal val LargeTitleFixtureAccessoryHeight = 40.dp

/** The gallery's Trailing toggle states. */
private enum class TrailingKind { None, Circle, EditCapsule }

/**
 * The fixture's [LazyListState], pre-scrolled to [offsetDp] within item 0
 * (or with [firstIndex] as the first visible item): `c`-equivalent scroll
 * positions for the goldens and previews, with no runtime scroll so the
 * snap effect never fires in a fixture.
 */
@Composable
internal fun rememberLargeTitleFixtureState(
    firstIndex: Int = 0,
    offsetDp: Float = 0f,
): LazyListState {
    val density = LocalDensity.current
    return rememberLazyListState(
        initialFirstVisibleItemIndex = firstIndex,
        initialFirstVisibleItemScrollOffset = with(density) { offsetDp.dp.roundToPx() },
    )
}

/**
 * The ticket's fixture: "Sessions" over the ten rows of `1-off.png`, a ⋯
 * trailing circle, at a fixed 24 dp status bar. The ticket's ten titles
 * repeat to [rows] = 20 — ten rows leave only 40 dp of scroll range on a
 * 700 dp host, less than the 52 dp collapse, so the gallery's "scroll
 * slowly / release half-way" manual check and the goldens need the headroom
 * (the visible rows are the same either way).
 */
@Composable
internal fun LargeTitleFixtureScaffold(
    mode: TitleMode = TitleMode.Always,
    showBack: Boolean = false,
    title: String = "Sessions",
    topInset: Dp = 24.dp,
    listState: LazyListState = rememberLargeTitleFixtureState(),
    rows: Int = 2 * ScrollEdgeEffectFixtureTitles.size,
    onBack: () -> Unit = {},
    onRowTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LargeTitleScaffold(
        title = title,
        mode = mode,
        listState = listState,
        topInset = topInset,
        showBack = showBack,
        onBack = onBack,
        trailing = { GlassCircleButton(RobocoIcons.Ellipsis, "More", onClick = {}) },
        modifier = modifier,
    ) {
        largeTitleFixtureRows(rows, onRowTap)
    }
}

/**
 * The fixture rows (the goldens pin the order): ticket 09's fixture row —
 * 62 dp tall, 20 dp side margin, title Geist Medium `fixed(16.5)`
 * `Palette.text`, meta Geist Regular `fixed(13.5)` `Palette.secondary` —
 * with a tag and a tap hook for the behaviour tests. The real session row
 * is ticket 37; this is only content to collapse over.
 */
internal fun LazyListScope.largeTitleFixtureRows(count: Int, onRowTap: () -> Unit = {}) {
    itemsIndexed(List(count) { ScrollEdgeEffectFixtureTitles[it % ScrollEdgeEffectFixtureTitles.size] }) { index, title ->
        LargeTitleFixtureRow(title, Modifier.testTag("fixture-row-$index"), onRowTap)
    }
}

@Composable
private fun LargeTitleFixtureRow(title: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier
            .fillMaxWidth()
            .height(ScrollEdgeEffectRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = ScrollEdgeEffectRowMargin),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(title, style = uiStyle(Face.SansMedium, typeScale.fixed(16.5f), palette.text))
        BasicText(ScrollEdgeEffectFixtureMeta, style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.secondary))
    }
}

// ---------------------------------------------------------------------------
// The gallery section
// ---------------------------------------------------------------------------

/**
 * The "Large title scaffold" gallery page: the fixture with the ticket's
 * five toggles — Mode (Always / InlineOnly / None), Back, Trailing
 * (none / ⋯ circle / "Edit" capsule), Long title
 * ("Durable Object hibernation flush and cleanup") and Pinned accessory (a
 * 40 dp placeholder capsule). The toggles are siblings **after** the
 * scaffold (never inside its haze source), so they stay crisp and tappable
 * over the rows.
 */
@Composable
fun LargeTitleGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var mode by remember { mutableStateOf(TitleMode.Always) }
    var back by remember { mutableStateOf(false) }
    var trailingKind by remember { mutableStateOf(TrailingKind.Circle) }
    var longTitle by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScaffold(
            title = if (longTitle) "Durable Object hibernation flush and cleanup" else "Sessions",
            mode = mode,
            showBack = back,
            onBack = {},
            trailing = {
                when (trailingKind) {
                    TrailingKind.None -> {}
                    TrailingKind.Circle ->
                        GlassCircleButton(RobocoIcons.Ellipsis, "More", onClick = {})
                    TrailingKind.EditCapsule ->
                        GlassTextCapsule("Edit", onClick = {})
                }
            },
            pinnedAccessory = if (pinned) ({ LargeTitlePinnedPlaceholder() }) else null,
            pinnedAccessoryHeight = if (pinned) LargeTitleFixtureAccessoryHeight else 0.dp,
        ) {
            largeTitleFixtureRows(2 * ScrollEdgeEffectFixtureTitles.size)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GalleryToggle(
                    "Mode: " + mode.name.lowercase(),
                    palette,
                    typeScale,
                ) { mode = TitleMode.entries[(mode.ordinal + 1) % TitleMode.entries.size] }
                GalleryToggle("Back: ${if (back) "on" else "off"}", palette, typeScale) { back = !back }
                GalleryToggle(trailingLabel(trailingKind), palette, typeScale) {
                    trailingKind = TrailingKind.entries[(trailingKind.ordinal + 1) % TrailingKind.entries.size]
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GalleryToggle("Long title: ${if (longTitle) "on" else "off"}", palette, typeScale) {
                    longTitle = !longTitle
                }
                GalleryToggle("Pinned: ${if (pinned) "on" else "off"}", palette, typeScale) {
                    pinned = !pinned
                }
            }
        }
    }
}

private fun trailingLabel(kind: TrailingKind): String = when (kind) {
    TrailingKind.None -> "Trailing: none"
    TrailingKind.Circle -> "Trailing: ⋯"
    TrailingKind.EditCapsule -> "Trailing: Edit"
}

/**
 * The pinned-accessory placeholder (the ticket's gallery toggle): a 40 dp
 * `controlFill` capsule with a secondary "Search" label — the stacked search
 * field's stand-in until ticket 105.
 */
@Composable
private fun LargeTitlePinnedPlaceholder() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = LargeTitleDefaults.SideMargin)
            .background(palette.controlFill, CapsuleShape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Search", style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.secondary))
    }
}

/** A plain-text toggle (no Material controls in the gallery). */
@Composable
private fun GalleryToggle(
    text: String,
    palette: Palette,
    typeScale: TypeScale,
    onClick: () -> Unit,
) {
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews (step 7): the fixture expanded and collapsed (index 3), light
// and dark, showBack/onBack passed explicitly (no NavStackHost here).
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun LargeTitleExpandedLightPreview() {
    RobocoTheme(Appearance.Light) {
        LargeTitleFixtureScaffold(showBack = false, onBack = {})
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LargeTitleExpandedDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        LargeTitleFixtureScaffold(showBack = false, onBack = {})
    }
}

@Preview(name = "collapsed light")
@Composable
private fun LargeTitleCollapsedLightPreview() {
    RobocoTheme(Appearance.Light) {
        LargeTitleFixtureScaffold(
            showBack = true,
            onBack = {},
            listState = rememberLargeTitleFixtureState(firstIndex = 3),
        )
    }
}

@Preview(name = "collapsed dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LargeTitleCollapsedDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        LargeTitleFixtureScaffold(
            showBack = true,
            onBack = {},
            listState = rememberLargeTitleFixtureState(firstIndex = 3),
        )
    }
}

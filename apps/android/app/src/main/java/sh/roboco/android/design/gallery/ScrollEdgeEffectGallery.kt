package sh.roboco.android.design.gallery

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.ScrollEdgeEffect
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults
import sh.roboco.android.design.glass.contentUnderTopEdge

/**
 * The "Scroll edge effect" page of the debug design gallery (ticket 09):
 * the fixture list of `1-off.png` scrolling under the effect and the inline
 * "Sessions" title, with **Blur** and **Engaged** toggles.
 *
 * Registered by ticket 22's registry in `design/Gallery.kt` as
 * `GallerySection("Scroll edge effect", layout = GalleryLayout.Fill) { ScrollEdgeEffectGallerySection() }`
 * — `Fill`, because the section owns a `LazyColumn`. The Light/Dark and
 * Force-reduce-motion toggles come from the gallery scaffold: the effect
 * reads [sh.roboco.android.design.LocalReduceMotion] through the theme, so
 * the engaged fade snaps when it is forced.
 *
 * The layout follows the per-screen Haze convention (`GlassHaze.kt`): the
 * scrolling list is the *only* `hazeSource`; the effect and the title are
 * siblings drawn after it, never inside it.
 */
@Composable
fun ScrollEdgeEffectGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val statusBarTop = galleryStatusBarTop()
    // The toggle opens at the composition's real tier (blur on a 33+ device,
    // off below or under the kill switch), then forces it either way.
    val blurDefault = ScrollEdgeEffectDefaults.blurSupported()
    var blurForced by remember { mutableStateOf(blurDefault) }
    var mode by remember { mutableStateOf(ScrollEdgeEngagedMode.Auto) }
    val listState = rememberPreScrolledListState()

    ScrollEdgeEffectScreen(
        statusBarTop = statusBarTop,
        engaged = mode.engaged(listState),
        blur = blurForced,
        listState = listState,
    ) {
        // The toggles, bottom chrome: siblings of the source, drawn after
        // (over) the effect, so they stay tappable and crisp.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Toggle(
                "Blur: ${if (blurForced) "on" else "off"}",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
            ) { blurForced = !blurForced }
            Toggle(
                "Engaged: ${mode.name.lowercase()}",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
            ) { mode = mode.next() }
        }
    }
}

// ---------------------------------------------------------------------------
// The shared fixture (goldens, previews and the gallery)
// ---------------------------------------------------------------------------

/** The ticket's fixture row titles, in order (the goldens pin the order). */
internal val ScrollEdgeEffectFixtureTitles = listOf(
    "Streaming veil on transcript rows",
    "Model picker catalog sync",
    "Tool group header colors",
    "Durable Object hibernation flush",
    "Transcript scroll pinning",
    "Keyboard avoidance for composer",
    "多言語テキストのレイアウト",
    "Clean up ~/Downloads",
    "Wrangler deploy hygiene",
    "Draft the launch post",
)

/** The fixture row pitch (≈ the row pitch of `1-off.png`). */
internal val ScrollEdgeEffectRowHeight = 62.dp

/** The fixture row's side margin. */
internal val ScrollEdgeEffectRowMargin = 20.dp

/** The fixture row's meta line. */
internal const val ScrollEdgeEffectFixtureMeta = "zeron · main"

/** How a fixture host derives the effect's `engaged` input. */
internal enum class ScrollEdgeEngagedMode { Auto, Always, Never }

/** [ScrollEdgeEngagedMode.Auto] reads the live list state; the rest force it. */
internal fun ScrollEdgeEngagedMode.engaged(list: LazyListState): Boolean = when (this) {
    ScrollEdgeEngagedMode.Auto -> list.contentUnderTopEdge
    ScrollEdgeEngagedMode.Always -> true
    ScrollEdgeEngagedMode.Never -> false
}

internal fun ScrollEdgeEngagedMode.next(): ScrollEdgeEngagedMode =
    ScrollEdgeEngagedMode.entries[(ordinal + 1) % ScrollEdgeEngagedMode.entries.size]

/**
 * The ticket's screenshot/preview fixture: the pre-scrolled fixture list
 * (rows 1–2 under the band, as in `1-off.png`) with the effect and the
 * inline title, at a **fixed** 24 dp status bar — Robolectric has no real
 * insets, and the goldens must not depend on the host. Previews and
 * `ScrollEdgeEffectScreenshotTest` render this; the gallery section wraps
 * the same [ScrollEdgeEffectScreen] with its own inset read and toggles.
 */
@Composable
internal fun ScrollEdgeEffectFixture(
    mode: ScrollEdgeEngagedMode = ScrollEdgeEngagedMode.Always,
    blur: Boolean,
    statusBarTop: Dp = 24.dp,
) {
    val listState = rememberPreScrolledListState()
    ScrollEdgeEffectScreen(
        statusBarTop = statusBarTop,
        engaged = mode.engaged(listState),
        blur = blur,
        listState = listState,
    ) {}
}

/**
 * The shared screen: the haze-source list, the effect over it and the inline
 * title on top. [chrome] (the gallery's toggles) is placed after the title,
 * over everything, with this box's [androidx.compose.foundation.layout.BoxScope]
 * receiver so it can align itself. The wallpaper-hero variant (ticket 21)
 * will add the hero *inside* the source box, above the list.
 */
@Composable
private fun ScrollEdgeEffectScreen(
    statusBarTop: Dp,
    engaged: Boolean,
    blur: Boolean,
    listState: LazyListState,
    chrome: @Composable BoxScope.() -> Unit = {},
) {
    val palette = RobocoTheme.palette
    val height = ScrollEdgeEffectDefaults.height(statusBarTop)
    ProvideHazeState {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            // 1. The haze source: everything that scrolls.
            Box(Modifier.fillMaxSize().hazeSource(LocalHazeState.current!!)) {
                LazyColumn(
                    state = listState,
                    // Content insets: at rest the content starts below the
                    // band; scrolled content passes under it.
                    contentPadding = PaddingValues(top = height),
                ) {
                    items(ScrollEdgeEffectFixtureTitles) { title ->
                        ScrollEdgeEffectFixtureRow(title)
                    }
                }
            }
            // 2. The effect, between the content and the chrome.
            ScrollEdgeEffect(
                height = height,
                engaged = engaged,
                blur = blur,
            )
            // 3. Chrome, never inside the source: the inline title above the
            //    effect (crisp, as on iOS), then the caller's controls.
            ScrollEdgeEffectInlineTitle(statusBarTop)
            chrome()
        }
    }
}

/**
 * The list state pre-scrolled by two rows (the ticket's pre-scroll), so
 * rows 1–2 sit under the band as in `1-off.png`.
 */
@Composable
private fun rememberPreScrolledListState(): LazyListState {
    val density = LocalDensity.current
    return rememberLazyListState(
        initialFirstVisibleItemIndex = 0,
        initialFirstVisibleItemScrollOffset = with(density) {
            (ScrollEdgeEffectRowHeight * 2).toPx().roundToInt()
        },
    )
}

/**
 * The centred inline nav-bar title (`ios:Shell/MainTabController.swift:131`):
 * Geist SemiBold 17 `Palette.text`, its centre at `statusBarTop + 22.dp` —
 * the middle of the UIKit 44 dp bar row.
 */
@Composable
private fun ScrollEdgeEffectInlineTitle(statusBarTop: Dp) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(ScrollEdgeEffectDefaults.BarHeight)
            .offset(y = statusBarTop),
    ) {
        BasicText(
            "Sessions",
            style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * One fixture row (the ticket's fixture): 62 dp tall with a 20 dp side
 * margin, a Geist Medium 16.5 title over a Geist Regular 13.5 meta line.
 * Sizes are `fixed` so the goldens do not depend on the font scale. The
 * real session row is ticket 37; this is only content to blur.
 */
@Composable
private fun ScrollEdgeEffectFixtureRow(title: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(ScrollEdgeEffectRowHeight)
            .padding(horizontal = ScrollEdgeEffectRowMargin),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(title, style = uiStyle(Face.SansMedium, typeScale.fixed(16.5f), palette.text))
        BasicText(ScrollEdgeEffectFixtureMeta, style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.secondary))
    }
}

/** The status bar's top inset in dp (0 under Robolectric capture). */
@Composable
private fun galleryStatusBarTop(): Dp {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    return with(density) { top.toDp() }
}

/** A plain-text toggle (no Material controls in the gallery). */
@Composable
private fun Toggle(text: String, style: TextStyle, onClick: () -> Unit) {
    BasicText(
        text,
        style = style,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews (step 7): the fixture, pre-scrolled, in light and dark
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun ScrollEdgeEffectLightPreview() {
    RobocoTheme(Appearance.Light) {
        ScrollEdgeEffectFixture(mode = ScrollEdgeEngagedMode.Always, blur = true)
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScrollEdgeEffectDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ScrollEdgeEffectFixture(mode = ScrollEdgeEngagedMode.Always, blur = true)
    }
}

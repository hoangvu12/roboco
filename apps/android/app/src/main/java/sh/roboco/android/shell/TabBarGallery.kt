package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.FadingHorizontalScroll
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle

/**
 * The "Tab bar" page of the debug design gallery (android-ui-parity #26):
 * the pill + search button over 22's striped backdrop, with three plain
 * text chips driving the selection so the platter slide, the interim
 * Search fade and the press swell can be watched, frames at 360 / 440 /
 * 800 dp (the 398 dp cap, centred), a "hold" toggle that emits a
 * `PressInteraction.Press` into the pill source, a "3-button nav" toggle
 * previewing `tabBarBottomGap(48.dp, 48.dp)`, and a fixed 402 × 874 dp
 * frame with a full [MainTabShell] over the placeholder roots (fixed
 * height, so its `LazyColumn`s stay bounded — the section itself scrolls
 * only through the gallery frame).
 *
 * Registered by ticket 22's registry as
 * `GallerySection("Tab bar", screenshotHeightDp = 1700) { TabBarGallerySection() }`
 * (`design/Gallery.kt`). Plain `BasicText` + `clickable` chips; no
 * Material controls.
 */
@Composable
internal fun TabBarGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var selected by remember { mutableStateOf(TabId.Sessions) }
    var hold by remember { mutableStateOf(false) }
    var buttonNav by remember { mutableStateOf(false) }
    val pillSource = remember { MutableInteractionSource() }
    var holdPress by remember { mutableStateOf<PressInteraction.Press?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryLabel("Tab bar", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
        GalleryLabel(
            "The glass pill + round search button. The chips drive the selection: the platter slides " +
                "with Motion.ActionButton, fades out in the interim Search state, and the pill swells " +
                "while held (07's shared interaction source).",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (tab in TabId.entries) {
                GalleryChip(
                    text = tab.label,
                    active = selected == tab,
                    onClick = { selected = tab },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryChip(
                text = if (hold) "Release pill" else "Hold pill",
                active = hold,
                onClick = {
                    hold = !hold
                    if (hold) {
                        val press = PressInteraction.Press(Offset.Zero)
                        holdPress = press
                        pillSource.tryEmit(press)
                    } else {
                        holdPress?.let { pillSource.tryEmit(PressInteraction.Cancel(it)) }
                        holdPress = null
                    }
                },
            )
            GalleryChip(
                text = if (buttonNav) "Gesture nav" else "3-button nav",
                active = buttonNav,
                onClick = { buttonNav = !buttonNav },
            )
        }

        // The interactive stage: one haze source per stage, the bar its
        // sibling (never inside the source, ticket 07's rule).
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            GlassStage(
                modifier = Modifier.requiredWidth(402.dp),
                height = 140.dp,
                backdropModifier = Modifier.hazeSource(haze),
            ) {
                // The 3-button preview: the gallery reports no insets, so the
                // bar's own gap is 21 dp; the extra (60 − 21) dp of padding
                // puts the pill exactly 60 dp above the stage bottom — 12 dp
                // above the fake 48 dp button strip, the real geometry.
                if (buttonNav) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(palette.chip),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "3-button nav (48 dp)",
                            style = uiStyle(Face.Sans, typeScale.fixed(10f), palette.secondary),
                        )
                    }
                }
                val previewGap = tabBarBottomGap(48.dp, 48.dp)
                GlassTabBar(
                    selected = selected,
                    onTabTap = { selected = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = if (buttonNav) previewGap - GlassTabBarDefaults.MinBottomGap else 0.dp,
                        ),
                    pillInteractionSource = pillSource,
                )
            }
        }
        GalleryLabel(
            "tabBarBottomGap(48.dp, 48.dp) = ${tabBarBottomGap(48.dp, 48.dp)}",
            uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )

        // Width frames: the row-width cap keeps the bar phone-sized and
        // centred on wide screens.
        GalleryLabel(
            "Row width: 360 / 440 / 800 dp frames — the 398 dp cap, centred",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        FadingHorizontalScroll(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (width in listOf(360.dp, 440.dp, 800.dp)) {
                WidthFrame(width, selected)
            }
        }

        // The full shell over the placeholder roots.
        GalleryLabel(
            "Full shell with the placeholder tab roots (402 × 874): tap the bar, push rows, " +
                "switch tabs and back — every stack and scroll position is kept.",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Box(
            Modifier
                .requiredWidth(402.dp)
                .height(874.dp)
                .hairlineBorder(palette.hairline, RectangleShape),
        ) {
            // The app mounts the shell inside RootHosts' GlassMenuHost (15);
            // the Sessions root's ⋯ button needs one (rememberGlassMenuState
            // throws without it).
            GlassMenuHost { MainTabShell() }
        }
    }
}

/** One width frame: the backdrop with the bar, and a caption. */
@Composable
private fun WidthFrame(width: Dp, selected: TabId) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            GlassStage(
                modifier = Modifier.width(width),
                height = 140.dp,
                backdropModifier = Modifier.hazeSource(haze),
            ) {
                GlassTabBar(selected, {}, Modifier.align(Alignment.BottomCenter))
            }
        }
        GalleryLabel(
            "${width.value.toInt()} dp",
            uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )
    }
}

/** A plain text chip; a `clickable` BasicText, no Material controls. */
@Composable
private fun GalleryChip(text: String, active: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), if (active) palette.accent else palette.secondary),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A plain text row. */
@Composable
private fun GalleryLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, style = style, modifier = modifier)
}

// ---------------------------------------------------------------------------
// Previews: the section standalone; the real gallery hosts it inside
// GalleryProviders.
// ---------------------------------------------------------------------------

@Preview(name = "tab bar gallery light", widthDp = 434, heightDp = 2100)
@Composable
private fun TabBarGalleryLightPreview() {
    RobocoTheme { TabBarGallerySection() }
}

@Preview(name = "tab bar gallery dark", widthDp = 434, heightDp = 2100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TabBarGalleryDarkPreview() {
    RobocoTheme { TabBarGallerySection() }
}

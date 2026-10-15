package sh.roboco.android.design.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.GlassSearchButton
import sh.roboco.android.shell.GlassTabPill
import sh.roboco.android.shell.LiveCounts
import sh.roboco.android.shell.NewSessionAccessory
import sh.roboco.android.shell.TabChromeLayout
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.minimizeOnScroll
import sh.roboco.android.shell.rememberTabBarMinimizeState
import sh.roboco.android.shell.tabPillExpandedWidth

/**
 * The "Tab bar minimize" page of the debug design gallery
 * (android-ui-parity #28), registered in ticket 22's `Gallery.sections`
 * as `GallerySection("Tab bar minimize", GalleryLayout.Scroll,
 * screenshotHeightDp = 1120) { TabBarMinimizeGallerySection() }`
 * (`design/Gallery.kt`).
 *
 * The real gesture can be tried: a 402 × 520 dp frame with a 60-row
 * `LazyColumn` under the live chrome, the detector installed around it —
 * scroll down and the pill shrinks to the selected icon's circle, the
 * search button to the same size, and the accessory drops inline between
 * them with only "+" and the summary; scroll back up and it springs back.
 * A foundation `draggable` scrubber (not a Material `Slider`) snaps the
 * morph to any position, and plain-text chips drive the animated
 * minimize/expand, the accessory toggle, the selected tab and the
 * 402/360 dp width switch (the narrow-phone inline case; see the ticket's
 * Comments). The gallery's Light/Dark and Force-reduce-motion toggles
 * apply: under reduce motion the morph snaps.
 */
@Composable
internal fun TabBarMinimizeGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val state = rememberTabBarMinimizeState()
    val scope = rememberCoroutineScope()
    var showAccessory by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(TabId.Sessions) }
    var wide by remember { mutableStateOf(true) }
    var taps by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryLabel("Tab bar minimize", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
        GalleryLabel(
            "Scroll the list: down minimizes (the pill becomes the selected icon's circle, the " +
                "accessory goes inline with its label hidden), up / top / a tap on the circle " +
                "restores. The scrubber drags the morph directly.",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryChip("Minimize", active = state.minimized, onClick = { state.minimize() })
            GalleryChip("Expand", active = !state.minimized, onClick = { state.expand() })
            GalleryChip(
                text = if (showAccessory) "Accessory on" else "Accessory off",
                active = showAccessory,
                onClick = { showAccessory = !showAccessory },
            )
            GalleryChip(
                text = if (selectedTab == TabId.Sessions) "Selected: Sessions" else "Selected: Settings",
                active = true,
                onClick = {
                    selectedTab = if (selectedTab == TabId.Sessions) TabId.Settings else TabId.Sessions
                },
            )
            GalleryChip(
                text = if (wide) "402 dp" else "360 dp",
                active = true,
                onClick = { wide = !wide },
            )
        }

        // The interactive stage: the list is the only haze source, the
        // detector wraps it, the chrome is its sibling (07's rule).
        val frameWidth = if (wide) 402f else 360f
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            Box(
                Modifier
                    .requiredWidth(frameWidth.dp)
                    .height(520.dp)
                    .hairlineBorder(palette.hairline, RectangleShape),
            ) {
                Box(Modifier.fillMaxSize().minimizeOnScroll(state) { true }) {
                    LazyColumn(Modifier.fillMaxSize().hazeSource(haze)) {
                        items(60) { index ->
                            val row = index + 1
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(start = 16.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                BasicText(
                                    text = "Row $row",
                                    style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
                                )
                            }
                        }
                    }
                }
                TabChromeLayout(
                    progress = { state.progress.value },
                    bottomInset = 21.dp,
                    hasAccessory = showAccessory,
                    modifier = Modifier.fillMaxSize(),
                    pill = {
                        GlassTabPill(
                            selected = selectedTab,
                            onTabTap = { selectedTab = it },
                            minimizeProgress = { state.progress.value },
                            minimized = state.minimized,
                            onRestore = { state.expand() },
                            expandedWidth = tabPillExpandedWidth(frameWidth).dp,
                        )
                    },
                    search = { GlassSearchButton(selectedTab, onTabTap = { selectedTab = it }) },
                    accessory = {
                        // Always composed: hasAccessory only stops the
                        // placement (30 keeps it in composition).
                        NewSessionAccessory(
                            counts = LiveCounts(working = 2, awaiting = 1),
                            onTap = { taps++ },
                            inline = state.minimized,
                            labelAlpha = { 1f - state.progress.value.coerceIn(0f, 1f) },
                        )
                    },
                )
            }
        }
        GalleryLabel("taps: $taps", uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary))

        // The scrubber: a foundation draggable track, no Material Slider.
        GalleryLabel(
            "Morph scrubber (drags p directly; cancels any running spring)",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        val trackWidth = 240.dp
        val trackPx = with(LocalDensity.current) { trackWidth.toPx() }
        val drag = rememberDraggableState { delta ->
            val next = (state.progress.value + delta / trackPx).coerceIn(0f, 1f)
            scope.launch { state.progress.snapTo(next) }
        }
        Box(
            Modifier
                .width(trackWidth)
                .height(28.dp)
                .draggable(orientation = Orientation.Horizontal, state = drag)
                .background(palette.chip, CapsuleShape),
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

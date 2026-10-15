package sh.roboco.android.design.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.SearchMorph
import sh.roboco.android.shell.SearchMorphFixture
import sh.roboco.android.shell.SearchTabState
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.rememberTabBarMinimizeState

/**
 * The "Search tab morph" page of the debug design gallery
 * (android-ui-parity #29), registered in ticket 22's `Gallery.sections`
 * as `GallerySection("Search tab morph", GalleryLayout.Scroll,
 * screenshotHeightDp = 1240) { SearchTabMorphGallerySection() }`
 * (`design/Gallery.kt`).
 *
 * The real interaction can be tried over 22's striped backdrop: tapping
 * the search circle (or the "Search selected" chip) runs the real morph
 * with [SearchMorph.Spring], the field focuses with the IME (on a device),
 * the "Close" circle returns, and typing lands in the field. The
 * scrubbers drag the morphs directly: **p** (the search morph), **m**
 * (ticket 28's minimize, on a real [rememberTabBarMinimizeState]) and
 * **imeLift** (0…320 dp — the row's IME ride, showing the accessory fade
 * and its disabled taps). Plain-text chips drive the text
 * ("zeron" / empty) and the field's focus. The gallery's Light/Dark and
 * Force-reduce-motion toggles apply: under reduce motion the morph snaps.
 */
@Composable
internal fun SearchTabMorphGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current

    val searchState = remember { SearchTabState() }
    val minimize = rememberTabBarMinimizeState()

    // The live morph: chips and bar taps flip the target; the spring (or
    // the reduce-motion snap) runs in a LaunchedEffect, like the shell's
    // rememberSearchMorphProgress — kept local so the scrubber can drive
    // the same Animatable directly.
    val morph = remember { Animatable(0f) }
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(searchActive, reduce) {
        val target = if (searchActive) 1f else 0f
        if (reduce) morph.snapTo(target) else morph.animateTo(target, SearchMorph.Spring.spec())
    }

    var hasText by remember { mutableStateOf(false) }
    var focusRequested by remember { mutableStateOf(false) }
    var imeLift by remember { mutableFloatStateOf(0f) }
    var newSessionTaps by remember { mutableStateOf(0) }
    var returnTaps by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryLabel("Search tab morph", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
        GalleryLabel(
            "Tap the search circle: the pill folds into the Close circle, the field stretches " +
                "and focuses (the IME rides the row up on a device; the imeLift scrubber stands " +
                "in for it here, fading the accessory). Close returns to the tab you came from.",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryChip(
                text = if (searchActive) "Search selected" else "Search unselected",
                active = searchActive,
                onClick = { searchActive = !searchActive },
            )
            GalleryChip(
                text = if (hasText) "Text: zeron" else "Text: empty",
                active = hasText,
                onClick = {
                    hasText = !hasText
                    if (hasText) {
                        searchState.text.setTextAndPlaceCursorAtEnd("zeron")
                    } else {
                        searchState.text.clearText()
                    }
                },
            )
            GalleryChip(
                text = if (focusRequested) "Focused" else "Focus",
                active = focusRequested,
                onClick = {
                    focusRequested = !focusRequested
                    if (focusRequested) {
                        searchActive = true
                        // One activation token: the field focuses a frame later.
                        searchState.onTabSelected(TabId.Sessions, TabId.Search)
                    } else {
                        focusManager.clearFocus()
                    }
                },
            )
        }

        SearchMorphFixture(
            state = searchState,
            progress = { morph.value },
            active = searchActive,
            minimizeProgress = { minimize.progress.value },
            minimized = minimize.minimized,
            imeLift = imeLift,
            height = 420.dp,
            onSearchTabTap = { searchActive = true },
            onReturn = {
                returnTaps++
                searchActive = false
            },
            onNewSession = { newSessionTaps++ },
        )
        GalleryLabel(
            "close taps: $returnTaps · new-session taps: $newSessionTaps",
            uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )

        // The scrubbers: foundation draggable tracks, no Material Slider.
        val trackWidth = 240.dp
        val trackPx = with(LocalDensity.current) { trackWidth.toPx() }
        GalleryLabel("p scrubber (drags the search morph; cancels the spring)",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary))
        val pDrag = rememberDraggableState { delta ->
            val next = (morph.value + delta / trackPx).coerceIn(0f, 1f)
            scope.launch { morph.snapTo(next) }
        }
        Box(
            Modifier
                .width(trackWidth)
                .height(28.dp)
                .draggable(orientation = Orientation.Horizontal, state = pDrag)
                .background(palette.chip, CapsuleShape),
        )
        GalleryLabel("m scrubber (ticket 28's minimize morph)",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary))
        val mDrag = rememberDraggableState { delta ->
            val next = (minimize.progress.value + delta / trackPx).coerceIn(0f, 1f)
            scope.launch { minimize.progress.snapTo(next) }
        }
        Box(
            Modifier
                .width(trackWidth)
                .height(28.dp)
                .draggable(orientation = Orientation.Horizontal, state = mDrag)
                .background(palette.chip, CapsuleShape),
        )
        GalleryLabel(
            "imeLift scrubber (0…320 dp: the row rides the IME, the accessory fades)",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        val liftDrag = rememberDraggableState { delta ->
            imeLift = (imeLift + delta * 320f / trackPx).coerceIn(0f, 320f)
        }
        Box(
            Modifier
                .width(trackWidth)
                .height(28.dp)
                .draggable(orientation = Orientation.Horizontal, state = liftDrag)
                .background(palette.chip, CapsuleShape),
        )
        GalleryLabel(
            "imeLift: ${imeLift.toInt()} dp · accessory alpha " +
                (accessoryAlphaPercent(imeLift / 56f)) + "%",
            uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )
    }
}

/** `100 − 100·clamp(lift/56)` as a whole percent, for the scrubber readout. */
private fun accessoryAlphaPercent(liftOverSpan: Float): Int =
    ((1f - liftOverSpan.coerceIn(0f, 1f)) * 100f).toInt()

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

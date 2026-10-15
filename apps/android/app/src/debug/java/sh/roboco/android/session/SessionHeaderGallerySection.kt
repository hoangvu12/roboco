package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.gallery.ScrollEdgeEngagedMode
import sh.roboco.android.design.gallery.engaged
import sh.roboco.android.design.gallery.next
import sh.roboco.android.design.uiStyle

/** The gallery's Title toggle states (the ticket's three titles). */
private enum class GalleryTitleKind(val label: String, val text: String) {
    Short("short", "Fix login"),
    Reference("reference", SessionHeaderReferenceTitle),
    VeryLong(
        "very long",
        "Refactor the transcript painter so long session titles fade instead of ellipsizing",
    ),
}

/** The gallery's Subtitle toggle states (engine / "No project" / empty). */
private enum class GallerySubtitleKind(val label: String, val text: String) {
    Engine("engine", "roboco @ MacBook Pro"),
    NoProject("no project", "No project"),
    Empty("empty", ""),
}

/** The gallery's Width toggle states: `null` = the section's full width. */
private enum class GalleryWidthKind(val label: String, val width: Dp?) {
    Full("full", null),
    W360("360", 360.dp),
    W320("320", 320.dp),
}

private inline fun <reified E : Enum<E>> E.next(): E {
    val values = enumValues<E>()
    return values[(ordinal + 1) % values.size]
}

/**
 * The "Session header" gallery page (android-ui-parity #52): the ticket's
 * fixture transcript under [SessionHeader], with the ticket's five toggles —
 * Title (short / reference / very long), Subtitle (engine / "No project" /
 * empty), Back (on/off), Engaged (auto from the fixture list scroll /
 * always / never) and Width (full / 360 / 320, a centred fixed-width frame
 * that shrinks the title cap). It follows the gallery's Light/Dark and
 * Force-reduce-motion toggles through [RobocoTheme].
 *
 * The toggles are siblings **after** the fixture (never inside its haze
 * source), so they stay crisp and tappable over the transcript. Tapping ⋯
 * shows a small toast-like caption with the anchor rect (the menu itself is
 * ticket 80's).
 *
 * Registered in `design/Gallery.kt` (ticket 22) as
 * `GallerySection("Session header", GalleryLayout.Fill, screenshotHeightDp = 420)`.
 */
@Composable
fun SessionHeaderGallerySection() {
    val palette = RobocoTheme.palette
    var titleKind by remember { mutableStateOf(GalleryTitleKind.Reference) }
    var subtitleKind by remember { mutableStateOf(GallerySubtitleKind.Engine) }
    var back by remember { mutableStateOf(true) }
    var engagedMode by remember { mutableStateOf(ScrollEdgeEngagedMode.Auto) }
    var widthKind by remember { mutableStateOf(GalleryWidthKind.Full) }
    var anchorNote by remember { mutableStateOf<String?>(null) }
    val listState = rememberSessionHeaderFixtureListState()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.Center)
                .then(
                    when (val frameWidth = widthKind.width) {
                        null -> Modifier.fillMaxSize()
                        else -> Modifier.width(frameWidth).fillMaxHeight()
                    }
                ),
        ) {
            SessionHeaderFixture(
                title = titleKind.text,
                subtitle = subtitleKind.text,
                showBack = back,
                engaged = engagedMode.engaged(listState),
                statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                listState = listState,
                onBack = {},
                onMore = { anchor -> anchorNote = "⋯ anchor $anchor" },
            )
        }
        // The ⋯ tap's stand-in for the menu (ticket 80 owns the real one):
        // a small caption with the anchor rect in window px.
        anchorNote?.let { note ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp)
                    .background(palette.controlFill, CapsuleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                BasicText(
                    note,
                    style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(12f), palette.secondary),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GalleryToggle("Title: ${titleKind.label}") { titleKind = titleKind.next() }
                GalleryToggle("Subtitle: ${subtitleKind.label}") { subtitleKind = subtitleKind.next() }
                GalleryToggle("Back: ${if (back) "on" else "off"}") { back = !back }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                GalleryToggle("Engaged: ${engagedMode.name.lowercase()}") { engagedMode = engagedMode.next() }
                GalleryToggle("Width: ${widthKind.label}") { widthKind = widthKind.next() }
            }
        }
    }
}

/** A plain-text toggle (no Material controls in the gallery). */
@Composable
private fun GalleryToggle(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        style = uiStyle(
            Face.SansMedium,
            LocalTypeScale.current.fixed(14f),
            RobocoTheme.palette.accent,
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    )
}

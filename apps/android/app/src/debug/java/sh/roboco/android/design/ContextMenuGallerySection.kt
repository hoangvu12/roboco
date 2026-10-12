package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.uiStyle

/**
 * The "Context menu" page of the debug design gallery (android-ui-parity
 * #16), registered by ticket 22's registry as
 * `GallerySection("Context menu", GalleryLayout.Fill, screenshotHeightDp = 891) { ContextMenuGallerySection() }`.
 *
 * A vertically scrolling `background` page (rows can be scrolled near both
 * screen edges), so each preset, the live preview and the placements can
 * be checked against iOS side by side:
 *
 *  - three 62 dp session-like rows (`ctx-row-1`…`ctx-row-3`) with the
 *    `SessionRow` preset, a `clickable` that appends to a log line, and the
 *    `Pin` / `Rename…` / `Archive` (destructive) / `Remove row` (removes
 *    that row from the list **while the overlay is open**) menu;
 *  - two multi-line transcript-like rows with the `TranscriptRow` preset
 *    and the `Copy` / `Select Text` menu;
 *  - a "tick" row whose text is a counter incremented every second (the
 *    live-preview check); frozen under Roborazzi capture (`capture = true`
 *    in [GalleryProviders]), like every other running loop in the gallery;
 *  - a row whose `menu()` returns `null` (no haptic, no lift, the tap
 *    still clicks);
 *  - a 900 dp tall row for the clipping and the "neither fits" overlap
 *    placement.
 *
 * The section itself needs no host of its own: `GalleryProviders` wraps
 * the gallery in [ContextMenuHost] (outermost inside `RobocoTheme`), so
 * the top bar is dimmed and blurred too. No Material controls anywhere.
 */
@Composable
fun ContextMenuGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val capture = LocalGalleryCapture.current

    var rows by remember { mutableStateOf(listOf("Session 1", "Session 2", "Session 3")) }
    var lastEvent by remember { mutableStateOf("—") }
    var ticks by remember { mutableIntStateOf(0) }

    // The live-preview row: the counter keeps streaming while the row is
    // lifted, so the preview visibly updates. Stopped under capture so the
    // goldens stay byte-stable (ticket 22's "no running loops" rule).
    LaunchedEffect(capture) {
        if (!capture) {
            while (true) {
                delay(1000)
                ticks++
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Label("Context menu", uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
        Label(
            "Long-press a row; last event: $lastEvent",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Spacer(Modifier.height(8.dp))

        rows.forEach { name ->
            SessionLikeRow(
                name = name,
                tag = "ctx-row-${name.removePrefix("Session ")}",
                onLog = { lastEvent = it },
                onRemove = { rows = rows.filterNot { it == name } },
            )
        }

        Spacer(Modifier.height(16.dp))
        Label(
            "Transcript rows — background fill, inset 8/0, radius 16:",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        TranscriptLikeRow(
            tag = "ctx-transcript-1",
            text = "A transcript line long enough to wrap, so the lifted preview shows two lines of text under the background fill.",
            onLog = { lastEvent = it },
        )
        TranscriptLikeRow(
            tag = "ctx-transcript-2",
            text = "Another transcript row: hold this one while the agent streams and the preview stays live.",
            onLog = { lastEvent = it },
        )

        Spacer(Modifier.height(16.dp))
        Label(
            "The tick row updates every second — lift it and watch the preview:",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        TranscriptLikeRow(
            tag = "ctx-tick",
            text = "tick: $ticks",
            onLog = { lastEvent = it },
        )

        Spacer(Modifier.height(16.dp))
        Label(
            "No menu: long-pressing does nothing, a tap still clicks:",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        TranscriptLikeRow(
            tag = "ctx-no-menu",
            text = "No menu",
            menu = { null },
            onLog = { lastEvent = "tapped No menu" },
        )

        Spacer(Modifier.height(16.dp))
        Label(
            "A 900 dp row — the preview clips to the visible part and the menu overlaps at the touch:",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        SessionLikeRow(
            name = "Tall row",
            tag = "ctx-tall",
            height = 900.dp,
            onLog = { lastEvent = it },
            onRemove = {},
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** One session-like gallery row: the `SessionRow` preset + a click log. */
@Composable
private fun SessionLikeRow(
    name: String,
    tag: String,
    onLog: (String) -> Unit,
    onRemove: () -> Unit,
    height: Dp = 62.dp,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .testTag(tag)
            .contextMenu(
                highlight = LiftHighlight.SessionRow,
                menu = { sessionMenu(name, onLog, onRemove) },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onLog("tapped $name") },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            name,
            Modifier.padding(start = 20.dp),
            style = uiStyle(Face.SansMedium, typeScale.fixed(16.5f), palette.text),
        )
    }
}

/** One transcript-like gallery row: the `TranscriptRow` preset. */
@Composable
private fun TranscriptLikeRow(
    tag: String,
    text: String,
    onLog: (String) -> Unit,
    menu: () -> Menu? = { transcriptMenu(onLog) },
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .contextMenu(highlight = LiftHighlight.TranscriptRow, menu = menu)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onLog("tapped $text") },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            Modifier.padding(horizontal = 20.dp),
            style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.text),
        )
    }
}

/** The session-row demo menu (tickets 44's items, trimmed to this gallery). */
private fun sessionMenu(name: String, onLog: (String) -> Unit, onRemove: () -> Unit): Menu = Menu(
    children = listOf(
        MenuItem("Pin", icon = MenuIcon.Symbol(RobocoIcons.Pin)) { onLog("Pin $name") },
        MenuItem("Rename…", icon = MenuIcon.Symbol(RobocoIcons.Pencil)) { onLog("Rename…") },
        MenuItem("Archive", icon = MenuIcon.Symbol(RobocoIcons.Archivebox), destructive = true) { onLog("Archive $name") },
        MenuItem("Remove row", icon = MenuIcon.Symbol(RobocoIcons.Trash), destructive = true) { onRemove() },
    ),
)

/** The transcript-row demo menu (ticket 128's items, trimmed to this gallery). */
private fun transcriptMenu(onLog: (String) -> Unit): Menu = Menu(
    children = listOf(
        MenuItem("Copy", icon = MenuIcon.Symbol(RobocoIcons.DocOnDoc)) { onLog("Copy") },
        MenuItem("Select Text", icon = MenuIcon.Symbol(RobocoIcons.CharacterCursorIbeam)) { onLog("Select Text") },
    ),
)

@Composable
private fun Label(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews (light and dark).
// ---------------------------------------------------------------------------

@Preview(name = "Context menu light")
@Composable
private fun ContextMenuGallerySectionLightPreview() {
    GalleryProviders(Appearance.Light) { ContextMenuGallerySection() }
}

@Preview(name = "Context menu dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ContextMenuGallerySectionDarkPreview() {
    GalleryProviders(Appearance.Dark) { ContextMenuGallerySection() }
}

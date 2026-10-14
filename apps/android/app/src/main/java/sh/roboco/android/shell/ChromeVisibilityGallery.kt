package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.hairlineBorder
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle

/**
 * The "Chrome visibility" page of the debug design gallery
 * (android-ui-parity #30), registered in ticket 22's `Gallery.sections`
 * as `GallerySection("Chrome visibility", GalleryLayout.Scroll,
 * screenshotHeightDp = 1200) { ChromeVisibilityGallerySection() }`
 * (`design/Gallery.kt`).
 *
 * A fixed 402 × 874 dp frame with a full [MainTabShell] over the
 * placeholder roots, plus a demo hiding route ("Push session" pushes 24's
 * `DemoRoute("Session", hides = true)` onto the selected tab's stack, so
 * the whole group — pill, search, accessory — hides over it and rides the
 * pop). Predictive back works inside the gallery activity: swipe back
 * from the session and the chrome is already laid out at its final place
 * while "New session"'s content fades in with the gesture; release back
 * toward the edge and it springs away (a cancelled back re-derives, never
 * flips). "Search → Sessions" replays the after-Search content fade
 * (empty glass capsule for ~120 ms, then 250 ms ease-out), and "Show
 * toast" anchors ticket 14's toast 12 dp above the live chrome top. The
 * live readout under the frame shows the controller's frame and content
 * alpha. The gallery's Light/Dark and "Force reduce motion" toggles flow
 * through `GalleryProviders`.
 *
 * Plain `BasicText` + `clickable` rows; no Material controls.
 */
@Composable
internal fun ChromeVisibilityGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()
    val state = remember { MainTabState(TabId.Sessions) }
    var chrome by remember { mutableStateOf<ChromeVisibilityController?>(null) }
    val toaster = LocalToaster.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryChromeLabel(
            "The bottom chrome group's visibility: hidden over a pushed Session, riding and " +
                "covered through the push/pop, revealed with the pop progress, its content faded " +
                "back in after leaving Search. The group never leaves composition (upstream #577).",
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
            GlassMenuHost {
                MainTabShell(
                    state = state,
                    counts = LiveCounts(working = 2, awaiting = 1),
                    onNewSession = {},
                    onChrome = { chrome = it },
                )
            }
        }

        GalleryChromeRow("Push session") {
            state.stack(state.selected).push(DemoRoute("Session", hides = true))
        }
        GalleryChromeRow("Pop") {
            state.stack(state.selected).pop()
        }
        GalleryChromeRow("Search → Sessions") {
            // Selects Search, then Sessions 600 ms later, replaying the
            // after-Search content fade (250 ms after a 120 ms delay).
            state.select(TabId.Search)
            scope.launch {
                delay(600)
                state.select(TabId.Sessions)
            }
        }
        GalleryChromeRow("Show toast") {
            toaster.show("Archived", "Undo") {}
        }

        // The live readout: recomposes this one text while a transition or
        // a fade runs (debug-only; the shell itself never does).
        val c = chrome
        GalleryChromeLabel(
            if (c == null) "chrome: —" else chromeReadout(c),
            uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
        )
    }
}

/** `placed · tx · clip · dim · content α · chrome top`, the controller's live values. */
private fun chromeReadout(c: ChromeVisibilityController): String {
    val f = c.frame()
    val clip = if (f.clipLeft.isInfinite() && f.clipRight.isInfinite()) {
        "—"
    } else {
        "clip %.0f..%.0f".format(f.clipLeft, f.clipRight)
    }
    // The published top (what ticket 14 anchors toasts to): the live
    // chrome top with the Search IME-lift subtraction applied.
    val top = c.publishedChromeTop()
    val topText = if (top.isNaN()) "—" else "%.1f".format(top)
    return "placed ${f.placed} · tx %.1f · $clip · dim %.2f · α %.2f · top $topText"
        .format(f.translationX, f.dim, c.accessoryContentAlpha())
}

/** A 44 dp hit row with an accent label (26's demo rows). */
@Composable
private fun GalleryChromeRow(text: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent),
        )
    }
}

/** A plain text row. */
@Composable
private fun GalleryChromeLabel(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews: the section, and the ticket's scrubbed pop at 0.5 — the shell
// built with 24's holdHostlessOps() + freeze(0.5f) so a hostless push/pop
// stays mid-transition (without the hold the op completes synchronously
// and freeze throws).
// ---------------------------------------------------------------------------

@Preview(name = "chrome visibility light", widthDp = 434, heightDp = 1300)
@Composable
private fun ChromeVisibilityGalleryLightPreview() {
    RobocoTheme { ChromeVisibilityGallerySection() }
}

@Preview(name = "chrome visibility dark", widthDp = 434, heightDp = 1300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChromeVisibilityGalleryDarkPreview() {
    RobocoTheme { ChromeVisibilityGallerySection() }
}

/** The shell frozen mid-pop from a session at 0.5: the reveal. */
@Composable
private fun ChromePopHalfContent() {
    val state = remember {
        MainTabState(TabId.Sessions).apply {
            val s = stack(TabId.Sessions)
            s.push(DemoRoute("Session", hides = true))
            s.holdHostlessOps()
            s.pop()
            s.freeze(0.5f)
        }
    }
    MainTabShell(
        state = state,
        counts = LiveCounts(working = 0, awaiting = 1),
    )
}

@Preview(name = "chrome pop 0.5 light", widthDp = 402, heightDp = 874)
@Composable
private fun ChromePopHalfLightPreview() {
    RobocoTheme { ChromePopHalfContent() }
}

@Preview(name = "chrome pop 0.5 dark", widthDp = 402, heightDp = 874, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChromePopHalfDarkPreview() {
    RobocoTheme { ChromePopHalfContent() }
}

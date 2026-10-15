package sh.roboco.android.design.gallery

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastChromeFixture
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.ToastLongText
import sh.roboco.android.design.uiStyle

/**
 * The "Toast" page of the debug design gallery (ticket 14): every caller
 * shape over the striped backdrop — action, error, long text, replacement —
 * plus the chrome-bar toggle and the keyboard interaction from the ticket's
 * manual steps.
 *
 * Registered by ticket 22's registry as
 * `GallerySection("Toast", GalleryLayout.Fill) { ToastGallerySection() }`,
 * with `GalleryProviders` wrapping `content()` in `ToastHost { }` — **until
 * 22 lands this section stays unregistered and `LocalToaster` is the Noop**
 * (the buttons log-and-drop). The previews below mount their own host so
 * the section is still checkable.
 *
 * No Material controls: plain `BasicText` + `clickable` buttons, the fake
 * chrome bar is a plain `Box` with `palette.chip` fill (from
 * [ToastChromeFixture]), which writes `LocalBottomChromeTop` exactly as the
 * real shell will (ticket 30) — toggling it off drops the toast to the
 * safe-area bottom, live.
 */
@Composable
fun ToastGallerySection() {
    val toaster = LocalToaster.current
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var chromeVisible by remember { mutableStateOf(true) }
    var field by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        ToastChromeFixture(Modifier.fillMaxSize(), chromeVisible = chromeVisible)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GalleryLabel(
                "Toast",
                uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            GalleryLabel(
                "The fake chrome bar writes LocalBottomChromeTop; the toast follows it live. " +
                    "Hiding it drops the toast to the safe-area bottom.",
                uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
            GalleryLabel(
                if (chromeVisible) "Hide chrome bar" else "Show chrome bar",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                Modifier.clickable { chromeVisible = !chromeVisible },
            )
            GalleryLabel(
                "Archived · Undo",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                Modifier.clickable {
                    toaster.show("Archived", "Undo") {
                        Log.i("ToastGallery", "Undo tapped: restoring")
                        toaster.show("Restored")
                    }
                },
            )
            GalleryLabel(
                "Couldn't open session. Try again.",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                Modifier.clickable { toaster.show("Couldn't open session. Try again.") },
            )
            GalleryLabel(
                "Long text",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                Modifier.clickable { toaster.show(ToastLongText) },
            )
            GalleryLabel(
                "Show twice",
                uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
                Modifier.clickable {
                    // Two shows in one frame: only the second appears.
                    toaster.show("First")
                    toaster.show("Second")
                },
            )
            GalleryLabel(
                "Focus the field, then show a toast — the keyboard counts as a bottom edge:",
                uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.chip)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
            )
        }
    }
}

/** A plain text row; with a `clickable` modifier attached it is a button (no Material controls). */
@Composable
private fun GalleryLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(text, style = style, modifier = modifier.padding(vertical = 2.dp))
}

// ---------------------------------------------------------------------------
// Previews: the section needs a ToastHost to show anything — its own mount,
// which ticket 22's GalleryProviders will replace for the real gallery.
// ---------------------------------------------------------------------------

@Preview(name = "Toast gallery light")
@Composable
private fun ToastGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { ToastHost { ToastGallerySection() } }
}

@Preview(name = "Toast gallery dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToastGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { ToastHost { ToastGallerySection() } }
}

package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Settings · Appearance" page of the debug design gallery
 * (android-ui-parity #96): the live section over a gallery-local pick —
 * tapping a row moves the checkmark **locally only**. It never touches
 * [sh.roboco.android.core.Prefs] or the platform night-mode push: the
 * gallery's own System/Light/Dark segment (22, its top bar) drives the
 * theme, and writing the real preference from a debug screen would
 * re-theme the user's actual app.
 *
 * Registered in ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Settings · Appearance", screenshotHeightDp = 400)
 * { AppearanceGallerySection() }` — the default `Scroll` layout, like
 * ticket 100's "Settings · Sessions": the section needs no in-window
 * host. The column is capped at the reference phone's 402 dp width on
 * wider bodies, so the card sits at the reference size.
 */
@Composable
fun AppearanceGallerySection() {
    // The stored-int shape, mapped on read, exactly like the real pref.
    var pick by rememberSaveable { mutableIntStateOf(0) }
    val current = Appearance.fromStored(pick)
    Column(
        Modifier
            .widthIn(max = 402.dp)
            .background(RobocoTheme.palette.background),
    ) {
        AppearanceGalleryCaption("live · the checkmark moves locally; the theme follows the gallery's own segment")
        AppearanceRowsColumn(current = current, onPick = { pick = it.storedValue })
    }
}

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun AppearanceGalleryCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

@Preview(name = "Appearance gallery - light")
@Composable
private fun AppearanceGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            AppearanceGallerySection()
        }
    }
}

@Preview(name = "Appearance gallery - dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppearanceGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            AppearanceGallerySection()
        }
    }
}

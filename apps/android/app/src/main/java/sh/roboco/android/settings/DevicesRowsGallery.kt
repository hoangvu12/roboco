package sh.roboco.android.settings

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Devices rows" page of the debug design gallery (android-ui-parity
 * #102): the ticket's step-8 fixture — the "Devices" header plus five
 * rows drawn non-lazily (this gallery page is not a list — [DevicesRow]
 * is the drawing unit, positions from the row count, no clicks):
 *
 *  1. "MacBook Pro", "Connected", a `success` dot (the side-by-side
 *     partner of `2-settings.png`'s "Choose Wallpaper…" row: same 68 dp
 *     two-line row, icon at x 32…56, title at x 72, dot then chevron);
 *  2. "Studio", "Reconnecting…", a `tertiary` dot;
 *  3. "Mac mini", "Session revoked";
 *  4. "Build box", the incompatible sentence as a multi-line subtitle —
 *     titles and subtitles wrap freely, so the row grows taller;
 *  5. "Pair an engine".
 *
 * Below, captioned "pressed", a second copy of row 1 as a `Single` card
 * held pressed (`previewPressed = true`) — ticket 70's `controlFill`
 * highlight. Then, captioned "no engines", the header with the `pair`
 * row alone (`Single`): the shape the section has before the first pair.
 *
 * The fixture is built from [DevicesRowSpec]s directly — no `EngineView`
 * (no core, no FFI), so the page renders in any host. Registered in
 * ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Devices rows", screenshotHeightDp = 900)
 * { DevicesRowsGallerySection() }` — the default `Scroll` layout, like
 * ticket 100's "Settings · Sessions": the section needs no in-window
 * host (no sheet host, no menu host — the rows' clicks are no-ops here).
 * The column is capped at the reference phone's 402 dp width on wider
 * bodies, so the cards sit at the reference size.
 *
 * The Roborazzi goldens of `DevicesRowsScreenshotTest` host this section
 * on the ticket's `w402dp-h900dp` phone; nothing animates, so the
 * goldens are byte-stable without freezing the clock.
 */
@Composable
fun DevicesRowsGallerySection() {
    Column(Modifier.widthIn(max = 402.dp)) {
        DevicesRowsCaption("fixture \u00b7 one row per paired engine, then pair")
        GroupedHeader(DevicesCopy.Header)
        devicesGalleryFixture.forEachIndexed { index, spec ->
            DevicesRow(spec, GroupedPosition.of(index, devicesGalleryFixture.size), onClick = {})
        }

        DevicesRowsCaption("pressed")
        GroupedHeader(DevicesCopy.Header)
        DevicesRow(devicesGalleryFixture.first(), GroupedPosition.Single, onClick = {}, previewPressed = true)

        DevicesRowsCaption("no engines")
        GroupedHeader(DevicesCopy.Header)
        DevicesRow(DevicesRowSpec.Pair, GroupedPosition.Single, onClick = {})
    }
}

/**
 * The step-8 fixture, `DevicesRowSpec`s directly (no `EngineView`): the
 * `…` is U+2026, the incompatible sentence android-app#06 always sets as
 * `lastError` for `INCOMPATIBLE`.
 */
private val devicesGalleryFixture = listOf(
    DevicesRowSpec.Engine("e1", "MacBook Pro", "Connected", connected = true),
    DevicesRowSpec.Engine("e2", "Studio", "Reconnecting\u2026", connected = false),
    DevicesRowSpec.Engine("e3", "Mac mini", "Session revoked", connected = false),
    DevicesRowSpec.Engine(
        "e4",
        "Build box",
        "Build box needs a newer Roboco app. Update the app to connect.",
        connected = false,
    ),
    DevicesRowSpec.Pair,
)

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun DevicesRowsCaption(text: String) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    BasicText(
        text,
        style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        modifier = Modifier.padding(start = 32.dp, top = 16.dp),
    )
}

@Preview(name = "light")
@Composable
private fun DevicesRowsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            DevicesRowsGallerySection()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DevicesRowsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            DevicesRowsGallerySection()
        }
    }
}

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GroupedHeader
import sh.roboco.android.design.GroupedPosition
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Settings · Sessions" page of the debug design gallery
 * (android-ui-parity #100): the section's ticket-step-7 fixtures. The
 * **live** block renders the real [SessionsSettingsRowContent] pair over a
 * gallery-local `mutableStateOf(true)` — the switch toggles for real
 * (through ticket 98's `RobocoSwitch` tap path, haptic included, via
 * `onGroupByProjectChange`), and tapping "Archived Sessions" shows
 * ticket 14's "Archived" toast through the gallery's `ToastHost`
 * (`LocalToaster` is `Toaster.Noop` outside one, so previews stay silent).
 * Below it, the second copy the ticket asks for: the switch off and the
 * Archived row held pressed (`previewPressed = true`), captioned
 * "off · pressed".
 *
 * Registered in ticket 22's `design/Gallery.kt` (debug source set) as
 * `GallerySection("Settings · Sessions", screenshotHeightDp = 520)
 * { SessionsSettingsGallerySection() }` — the default `Scroll` layout,
 * like ticket 98's "Switch": the section needs no in-window host (no sheet
 * host, no menu host), and the frame's 16 dp padding and background serve
 * as the page. The column is capped at the reference phone's 402 dp width
 * on wider bodies, so the cards sit at the reference size.
 *
 * The Roborazzi goldens of `SessionsSettingsScreenshotTest` host the
 * pinned states directly (not this section): the live block's state is
 * gallery-interactive, not a fixture.
 */
@Composable
fun SessionsSettingsGallerySection() {
    val strings = sessionsSettingsStrings()
    Column(Modifier.widthIn(max = 402.dp)) {
        SessionsSettingsGalleryCaption("live \u00b7 the switch toggles; Archived toasts")
        var on by remember { mutableStateOf(true) }
        val toaster = LocalToaster.current
        GroupedHeader(strings.header)
        SessionsSettingsRowContent(
            row = SessionsSettingsRow.GroupByProject,
            position = GroupedPosition.First,
            strings = strings,
            groupByProject = on,
            onGroupByProjectChange = { on = it },
            onOpenArchived = { toaster.show("Archived") },
        )
        SessionsSettingsRowContent(
            row = SessionsSettingsRow.Archived,
            position = GroupedPosition.Last,
            strings = strings,
            groupByProject = on,
            onGroupByProjectChange = { on = it },
            onOpenArchived = { toaster.show("Archived") },
        )

        SessionsSettingsGalleryCaption("off \u00b7 pressed")
        GroupedHeader(strings.header)
        SessionsSettingsRowContent(
            row = SessionsSettingsRow.GroupByProject,
            position = GroupedPosition.First,
            strings = strings,
            groupByProject = false,
            onGroupByProjectChange = { },
            onOpenArchived = { },
        )
        SessionsSettingsRowContent(
            row = SessionsSettingsRow.Archived,
            position = GroupedPosition.Last,
            strings = strings,
            groupByProject = false,
            onGroupByProjectChange = { },
            onOpenArchived = { },
            previewPressed = true,
        )
    }
}

/** The block caption: Geist 12 fixed, secondary, at the header's inset. */
@Composable
private fun SessionsSettingsGalleryCaption(text: String) {
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
private fun SessionsSettingsGalleryLightPreview() {
    RobocoTheme(Appearance.Light) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            SessionsSettingsGallerySection()
        }
    }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionsSettingsGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        Box(Modifier.background(RobocoTheme.palette.background)) {
            SessionsSettingsGallerySection()
        }
    }
}

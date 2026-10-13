package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading

/**
 * The placeholder New Session sheet (android-ui-parity #31): ✕ plus a
 * centred "New Session" over an empty page — `MainTabController`'s sheet
 * (`MainTabController.swift:136-148`) until ticket 93 replaces the page
 * with the real canvas. The header is ticket 17's [SheetHeaderRow] (the
 * 52 dp row, Geist SemiBold 17 title, the ✕ glass circle with tag
 * `sheet-close` and label "Close") — no header code of its own; the
 * grabber that the frame draws overlaps the row's top.
 */
@Composable
fun NewSessionSheetPlaceholder(scope: PageSheetScope) {
    Column(Modifier.fillMaxSize()) {
        SheetHeaderRow(
            title = "New Session",
            leading = SheetLeading.Close,
            onLeading = { scope.dismiss(true) },
        )
        // The empty page: the frame's `Palette.background` fill shows.
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The preview stage: one settled New Session sheet over a busy backdrop,
 * so the 0.92 recession, the 38 dp top corners, the black side gaps and
 * the scrim all read at a glance.
 */
@Composable
private fun NewSessionSheetPlaceholderStage(appearance: Appearance) {
    RobocoTheme(appearance) {
        val state = rememberSheetHostState()
        state.presentSettledForPreview("new-session", SheetStyle.Page(grabber = true)) {
            NewSessionSheetPlaceholder(this)
        }
        SheetHost(state) {
            Box(Modifier.fillMaxSize()) {
                GalleryBackdrop(Modifier.fillMaxSize())
            }
        }
    }
}

@Preview(name = "New Session sheet light", widthDp = 411, heightDp = 891)
@Composable
private fun NewSessionSheetPlaceholderLightPreview() {
    NewSessionSheetPlaceholderStage(Appearance.Light)
}

@Preview(name = "New Session sheet dark", widthDp = 411, heightDp = 891, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewSessionSheetPlaceholderDarkPreview() {
    NewSessionSheetPlaceholderStage(Appearance.Dark)
}

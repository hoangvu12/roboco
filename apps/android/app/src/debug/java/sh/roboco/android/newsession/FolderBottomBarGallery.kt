package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertField
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Folder bottom bar" section of the debug design gallery
 * (android-ui-parity #111): the status line, the plain New Folder…
 * button and the full-width Use capsule over folder rows — the ticket's
 * four fixtures (a)–(d), each in a fixed-height page whose rows run
 * underneath the background-less stack, plus the New Folder alert in
 * its retry-error state (through [LocalAlerts] — the gallery's own
 * `AlertHost` from [sh.roboco.android.design.GalleryProviders]).
 *
 * The fixtures and the stage are shared verbatim with the screenshot
 * test `FolderBrowserBottomBarTest` (the debug source set is visible to
 * `testDebugUnitTest`). Registered in `design/Gallery.kt` as
 * `GallerySection("Folder bottom bar", screenshotHeightDp = 600) { FolderBottomBarGallerySection() }`.
 */

// ---------------------------------------------------------------------------
// Fixtures (shared with the screenshot tests)
// ---------------------------------------------------------------------------

/** One bottom-bar state: the five inputs of [FolderBrowserBottomBar]. */
data class FolderBottomBarSpec(
    val status: String,
    val useTitle: String,
    val useEnabled: Boolean,
    val useBusy: Boolean,
    val newFolderEnabled: Boolean,
)

/** The status of fixture (d): past two lines of the 13 pt label, so it ellipsizes. */
const val FOLDER_BOTTOM_BAR_LONG_STATUS: String =
    "/Volumes/Seagate Backup Plus Drive/2026 Photography Archive/a folder name that runs past " +
        "the two line limit of the thirteen point status label and ellipsizes at the tail"

/** The ticket's four fixtures (a)–(d), in gallery order. */
fun folderBottomBarFixtures(): List<Pair<String, FolderBottomBarSpec>> = listOf(
    "a — a good listing" to FolderBottomBarSpec(
        status = "/srv",
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = false,
        newFolderEnabled = true,
    ),
    "b — loading (everything disabled)" to FolderBottomBarSpec(
        status = "Loading…",
        useTitle = "",
        useEnabled = false,
        useBusy = false,
        newFolderEnabled = false,
    ),
    "c — creating the project (busy Use)" to FolderBottomBarSpec(
        status = "/srv",
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = true,
        newFolderEnabled = false,
    ),
    "d — a two-line status that ellipsizes" to FolderBottomBarSpec(
        status = FOLDER_BOTTOM_BAR_LONG_STATUS,
        useTitle = "Use \u201Csrv\u201D",
        useEnabled = true,
        useBusy = false,
        newFolderEnabled = true,
    ),
)

/** The alert the retry flow re-shows: the invalid-name error, the name kept. */
fun newFolderRetryAlert(): RobocoAlert = RobocoAlert(
    title = "New Folder",
    message = "Couldn't create the folder. Enter a folder name without path separators.",
    field = AlertField(
        text = "../outside",
        placeholder = "Folder name",
        capitalization = KeyboardCapitalization.None,
        autocorrect = false,
    ),
    buttons = listOf(
        AlertButton("Cancel", AlertButtonRole.Cancel),
        AlertButton("Create", AlertButtonRole.Default, requiresText = true),
    ),
)

// ---------------------------------------------------------------------------
// The stage (shared with the screenshot tests and the previews)
// ---------------------------------------------------------------------------

/**
 * One fixture page: plain folder rows tall enough to run under the bar
 * (the stack has no background — that underlap is the point), with the
 * bottom bar pinned to the page's bottom edge. The page is clipped, so
 * the rows that overshoot a short page (the gallery's cards) stop at
 * its edge instead of drawing over the next card; the caller owns the
 * page height — the gallery fixes it, the screenshot test fills the
 * frame so the 12 dp bottom inset is measured against the screen.
 */
@Composable
internal fun FolderBottomBarStage(spec: FolderBottomBarSpec, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().clipToBounds()) {
        FolderFixtureRows(Modifier.fillMaxSize())
        FolderBrowserBottomBar(
            status = spec.status,
            useTitle = spec.useTitle,
            useEnabled = spec.useEnabled,
            useBusy = spec.useBusy,
            newFolderEnabled = spec.newFolderEnabled,
            onUse = {},
            onNewFolder = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Plain 52 dp folder rows — enough to fill the tallest page (the test
 * frame is 874 dp), so rows run under the background-less bar in every
 * fixture page, like the list in the iOS reference captures.
 */
@Composable
private fun FolderFixtureRows(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val names = listOf("Desktop", "Documents", "Downloads", "Projects", "roboco", "zeron")
    Column(modifier.background(palette.background)) {
        repeat(17) { index ->
            Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
                BasicText(
                    if (index < names.size) names[index] else "${names[index % names.size]} ${index + 1}",
                    style = uiStyle(Face.Sans, ts.fixed(16f), palette.text),
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The gallery section
// ---------------------------------------------------------------------------

@Composable
fun FolderBottomBarGallerySection() {
    val alerts = LocalAlerts.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((label, spec) in folderBottomBarFixtures()) {
            FixtureCard(label, spec)
        }
        // The retry state of the New Folder alert (ticket 18 renders it).
        BasicText(
            "Open the New Folder alert in its retry-error state",
            Modifier.clickable(role = Role.Button) { alerts.show(newFolderRetryAlert()) },
            style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(15f), RobocoTheme.palette.accent),
        )
    }
}

/** One fixture: the label, then the 180 dp stage with rows under the bar. */
@Composable
private fun FixtureCard(label: String, spec: FolderBottomBarSpec) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
        FolderBottomBarStage(spec, Modifier.height(180.dp))
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: one fixture on a full page, light or dark. */
@Composable
private fun FolderBottomBarPreviewStage(appearance: Appearance, spec: FolderBottomBarSpec) {
    RobocoTheme(appearance) {
        FolderBottomBarStage(spec, Modifier.fillMaxSize())
    }
}

private fun spec(label: Char): FolderBottomBarSpec =
    folderBottomBarFixtures().first { it.first.startsWith(label) }.second

@Preview(name = "a light")
@Composable
private fun FolderBottomBarALightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('a'))
}

@Preview(name = "a dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarADarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('a'))
}

@Preview(name = "b light — loading")
@Composable
private fun FolderBottomBarBLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('b'))
}

@Preview(name = "b dark — loading", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarBDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('b'))
}

@Preview(name = "c light — busy create")
@Composable
private fun FolderBottomBarCLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('c'))
}

@Preview(name = "c dark — busy create", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarCDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('c'))
}

@Preview(name = "d light — two-line status")
@Composable
private fun FolderBottomBarDLightPreview() {
    FolderBottomBarPreviewStage(Appearance.Light, spec('d'))
}

@Preview(name = "d dark — two-line status", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBottomBarDDarkPreview() {
    FolderBottomBarPreviewStage(Appearance.Dark, spec('d'))
}

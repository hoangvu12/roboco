package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.DriveEntry
import uniffi.roboco_core.PreparedPath

/**
 * The "Folder browser locations" section of the debug design gallery
 * (android-ui-parity #112): the ticket's four fixtures (A–D) stacked, each
 * with a caption — the rail and the search/typed-path field on the plain
 * background, at the phone width (402 dp) the ticket's geometry is
 * measured against.
 *
 * The fixtures and the stage are shared verbatim with the screenshot test
 * `FolderLocationsScreenshotTest` (the debug source set is visible to
 * `testDebugUnitTest`). Registered in `design/Gallery.kt` as
 * `GallerySection("Folder browser locations", screenshotHeightDp = 560) { FolderLocationsGallerySection() }`.
 */

// ---------------------------------------------------------------------------
// Fixtures (shared with the screenshot tests)
// ---------------------------------------------------------------------------

/** One header state: the four inputs of [FolderLocationsHeader]. */
data class FolderLocationsSpec(
    val chips: List<LocationChipModel>,
    val selected: Int?,
    val query: String,
    val enabled: Boolean = true,
)

/**
 * The ticket's fixture A (Linux): engine order — `finish_drives` puts the
 * `/` root first, then case-insensitive names. Selected 0 (Home), empty
 * query. `selectedLocation` with home `/home/wing`: `/home/wing/src` →
 * Home, `/etc` → System.
 */
fun folderLocationsFixtureA(): FolderLocationsSpec = FolderLocationsSpec(
    chips = locationChips(
        listOf(
            "System" to "/",
            "backup-2024-archive" to "/media/wing/backup-2024-archive",
            "data" to "/mnt/data",
        ),
    ),
    selected = 0,
    query = "",
)

/** Fixture B: the same chips, System selected, a typed path in the field. */
fun folderLocationsFixtureB(): FolderLocationsSpec = FolderLocationsSpec(
    chips = folderLocationsFixtureA().chips,
    selected = 1,
    query = "~/projects/new-app",
)

/** Fixture C (Windows): Home + three drive letters, `D:` selected, a filter typed. */
fun folderLocationsFixtureC(): FolderLocationsSpec = FolderLocationsSpec(
    chips = locationChips(listOf("C:" to "C:\\", "D:" to "D:\\", "E:" to "E:\\")),
    selected = 2,
    query = "doc",
)

/** Fixture D: fixture A while 111 submits — chips disabled, field disabled. */
fun folderLocationsFixtureD(): FolderLocationsSpec =
    folderLocationsFixtureA().copy(enabled = false)

/** The ticket's four fixtures, in gallery order. */
fun folderLocationsFixtures(): List<Pair<String, FolderLocationsSpec>> = listOf(
    "a — Linux, Home selected" to folderLocationsFixtureA(),
    "b — System selected, typed path" to folderLocationsFixtureB(),
    "c — Windows, D: selected, filter" to folderLocationsFixtureC(),
    "d — submitting (disabled)" to folderLocationsFixtureD(),
)

// ---------------------------------------------------------------------------
// The fake calls (gallery + previews + 110's screen test)
// ---------------------------------------------------------------------------

/**
 * Debug-only fake of the calls seam (112's gallery, previews and 110's
 * screen test — like [FakeProjectWriteApi] stands in for 111's write api).
 * `listDrives` answers the fixture map; `prepareSpacePath` answers
 * `exists = createIfMissing` (a probe misses, a create succeeds), like
 * 111's fake; `join` rides [FakeHostPaths]. Every call is recorded.
 */
class FakeFolderCalls(
    private val drivesByDevice: Map<String, List<DriveEntry>> = emptyMap(),
    private val paths: HostPathOps = FakeHostPaths,
) : FolderBrowserCalls {

    /** One line per FFI call, in order. */
    val calls = mutableListOf<String>()

    override suspend fun listDrives(deviceId: String): List<DriveEntry> {
        calls += "listDrives $deviceId"
        return drivesByDevice[deviceId] ?: emptyList()
    }

    override suspend fun prepareSpacePath(
        deviceId: String,
        path: String,
        createIfMissing: Boolean,
    ): PreparedPath {
        calls += "prepare $deviceId create=$createIfMissing $path"
        return PreparedPath(path = path, exists = createIfMissing, gitDetected = false)
    }

    override fun join(base: String, name: String): String = paths.join(base, name)
}

/**
 * The fixture drives each gallery engine reports — POSIX volumes for the
 * macOS fixtures, drive letters for the Windows one, nothing for the rest
 * (offline / loading / no engines: the Home-only rail, like a silent
 * `ListDrives` failure).
 */
fun fixtureDrives(fixture: FolderBrowserFixtureId): List<DriveEntry> = when (fixture) {
    FolderBrowserFixtureId.POSIX_HOME -> listOf(
        DriveEntry(name = "Macintosh HD", path = "/"),
        DriveEntry(name = "Seagate", path = "/Volumes/Seagate"),
    )
    FolderBrowserFixtureId.ROOT -> listOf(DriveEntry(name = "Macintosh HD", path = "/"))
    FolderBrowserFixtureId.WINDOWS -> listOf(
        DriveEntry(name = "C:", path = "C:\\"),
        DriveEntry(name = "D:", path = "D:\\"),
    )
    else -> emptyList()
}

// ---------------------------------------------------------------------------
// The stage (shared with the screenshot tests and the previews)
// ---------------------------------------------------------------------------

/**
 * One fixture page: the header at the ticket's phone width over the plain
 * background. The query starts at the fixture's value and stays editable
 * (the rail's taps and the field's Go no-op here — the interactive
 * behaviour lives in the sheet gallery and the tests).
 */
@Composable
internal fun FolderLocationsStage(spec: FolderLocationsSpec, modifier: Modifier = Modifier) {
    Box(modifier.width(402.dp)) {
        var query by remember(spec) { mutableStateOf(spec.query) }
        FolderLocationsHeader(
            chips = spec.chips,
            selected = spec.selected,
            query = query,
            enabled = spec.enabled,
            onPickLocation = {},
            onQueryChange = { query = it },
            onGo = {},
        )
    }
}

// ---------------------------------------------------------------------------
// The gallery section
// ---------------------------------------------------------------------------

@Composable
fun FolderLocationsGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((label, spec) in folderLocationsFixtures()) {
            LocationsFixtureCard(label, spec)
        }
    }
}

/** One fixture: the caption, then the 90 dp header at the phone width. */
@Composable
private fun LocationsFixtureCard(label: String, spec: FolderLocationsSpec) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
        FolderLocationsStage(spec, Modifier.height(90.dp))
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: fixture A (or the given spec) on the plain background. */
@Composable
private fun FolderLocationsPreviewStage(appearance: Appearance, spec: FolderLocationsSpec) {
    RobocoTheme(appearance) {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
            FolderLocationsStage(spec, Modifier.fillMaxWidth())
        }
    }
}

@Preview(name = "light", heightDp = 120)
@Composable
private fun FolderLocationsLightPreview() {
    FolderLocationsPreviewStage(Appearance.Light, folderLocationsFixtureA())
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 120)
@Composable
private fun FolderLocationsDarkPreview() {
    FolderLocationsPreviewStage(Appearance.Dark, folderLocationsFixtureA())
}

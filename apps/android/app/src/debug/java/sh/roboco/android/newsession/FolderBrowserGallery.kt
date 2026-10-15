package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.PageSheetScope
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetStyle
import sh.roboco.android.shell.rememberSheetHostState
import uniffi.roboco_core.FolderEntry
import uniffi.roboco_core.FolderListing

/**
 * The "New Project browser" page of the debug design gallery
 * (android-ui-parity #110): the whole sheet — nav bar, engine menu,
 * folder rows — over a fake source and [FakeHostPaths], in a settled page
 * sheet (ticket 31's `presentSettledForPreview`), so the browser can be
 * driven without an engine. The fixture switcher lives on the presenting
 * screen behind the sheet: ✕ dismisses, a fixture pick re-presents the
 * sheet settled (the insertion is idempotent while the sheet is up).
 *
 * The fixtures are the ticket's, shared verbatim with the screen test
 * `NewProjectScreenTest` (the debug source set is visible to
 * `testDebugUnitTest`). Registered in
 * `design/Gallery.kt` as
 * `GallerySection("New Project browser", GalleryLayout.Fill, screenshotHeightDp = 874) { FolderBrowserGallerySection() }`.
 */

// ---------------------------------------------------------------------------
// Fixtures (shared with the screenshot tests)
// ---------------------------------------------------------------------------

/** The ticket's fixture engines: MacBook Pro online, hetzner-01 offline. */
fun folderBrowserFixtureHosts(): List<HostOption> = listOf(
    HostOption("dev-mbp", "MacBook Pro", online = true),
    HostOption("dev-htz", "hetzner-01", online = false),
)

/** The ticket's POSIX home fixture: `Desktop … zeron`, plus a file that must not list. */
fun posixHomeListing(): FolderListing = FolderListing(
    path = "/Users/alice",
    entries = listOf(
        FolderEntry(name = "Desktop", isDir = true, isRepo = false),
        FolderEntry(name = "Documents", isDir = true, isRepo = false),
        FolderEntry(name = "Projects", isDir = true, isRepo = false),
        FolderEntry(name = "roboco", isDir = true, isRepo = true),
        FolderEntry(name = "zeron", isDir = true, isRepo = true),
        FolderEntry(name = "notes.txt", isDir = false, isRepo = false),
    ),
    truncated = false,
)

/** The ticket's root fixture: no Parent row, title `/`. */
fun rootListing(): FolderListing = FolderListing(
    path = "/",
    entries = listOf(
        FolderEntry(name = "Applications", isDir = true, isRepo = false),
        FolderEntry(name = "Users", isDir = true, isRepo = false),
    ),
    truncated = false,
)

/** The ticket's Windows fixture: `C:\Users\alice`, Parent → `C:\Users`. */
fun windowsHomeListing(): FolderListing = FolderListing(
    path = "C:\\Users\\alice",
    entries = listOf(
        FolderEntry(name = "Projects", isDir = true, isRepo = false),
        FolderEntry(name = "roboco", isDir = true, isRepo = true),
    ),
    truncated = false,
)

/** A synthetic listing for any folder the gallery descends into. */
fun syntheticFolderListing(path: String): FolderListing = FolderListing(
    path = path,
    entries = listOf(
        FolderEntry(name = "Documents", isDir = true, isRepo = false),
        FolderEntry(name = "repo", isDir = true, isRepo = true),
    ),
    truncated = false,
)

/** An immediate source: [listingFor] runs on the caller's context and never suspends. */
class ImmediateFolderBrowserSource(
    private val listingFor: (String?, Boolean) -> FolderListing?,
) : FolderBrowserSource {
    override suspend fun listFolders(
        deviceId: String,
        path: String?,
        showHidden: Boolean,
    ): FolderListing? = listingFor(path, showHidden)
}

/** A source that never answers — the "Loading…" fixture. */
object HangingFolderBrowserSource : FolderBrowserSource {
    override suspend fun listFolders(
        deviceId: String,
        path: String?,
        showHidden: Boolean,
    ): FolderListing? = awaitCancellation()
}

/** A source where every call fails (the iOS `try?` → nil). */
object FailingFolderBrowserSource : FolderBrowserSource {
    override suspend fun listFolders(
        deviceId: String,
        path: String?,
        showHidden: Boolean,
    ): FolderListing? = null
}

/** The gallery's six fixtures. */
enum class FolderBrowserFixtureId { POSIX_HOME, ROOT, WINDOWS, LOADING, OFFLINE, NO_ENGINES }

/** The hosts each fixture browses with. */
fun folderBrowserFixtureHosts(id: FolderBrowserFixtureId): List<HostOption> = when (id) {
    FolderBrowserFixtureId.OFFLINE -> listOf(HostOption("dev-htz", "hetzner-01", online = false))
    FolderBrowserFixtureId.NO_ENGINES -> emptyList()
    else -> folderBrowserFixtureHosts()
}

/** The source each fixture browses through: the fixture's home listing, then synthetic descents. */
fun folderBrowserFixtureSource(id: FolderBrowserFixtureId): FolderBrowserSource = when (id) {
    FolderBrowserFixtureId.POSIX_HOME -> immediateSource(null to posixHomeListing())
    FolderBrowserFixtureId.ROOT -> immediateSource(null to rootListing())
    FolderBrowserFixtureId.WINDOWS -> immediateSource(null to windowsHomeListing())
    FolderBrowserFixtureId.LOADING -> HangingFolderBrowserSource
    FolderBrowserFixtureId.OFFLINE -> FailingFolderBrowserSource
    FolderBrowserFixtureId.NO_ENGINES -> FailingFolderBrowserSource // never called
}

/** The fixture's home answer; any other path descends synthetically (so tapping rows works). */
private fun immediateSource(vararg fixed: Pair<String?, FolderListing>): FolderBrowserSource =
    ImmediateFolderBrowserSource { path, _ ->
        fixed.firstOrNull { it.first == path }?.second ?: path?.let(::syntheticFolderListing)
    }

// ---------------------------------------------------------------------------
// The gallery section
// ---------------------------------------------------------------------------

@Composable
fun FolderBrowserGallerySection() {
    val state = rememberSheetHostState()
    var fixture by remember { mutableStateOf(FolderBrowserFixtureId.POSIX_HOME) }
    GlassMenuHost {
        // Presented settled (idempotent while up): recompositions after a
        // dismissal — a fixture pick — re-present it with the new fixture.
        state.presentSettledForPreview("new-project", SheetStyle.Page(grabber = false)) {
            FolderBrowserFixtureSheetPage(fixture)
        }
        SheetHost(state) { FakePresentingScreen(fixture) { fixture = it } }
    }
}

/** The screen behind the sheet: the backdrop plus the fixture switcher. */
@Composable
private fun FakePresentingScreen(
    fixture: FolderBrowserFixtureId,
    onPick: (FolderBrowserFixtureId) -> Unit,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Box(Modifier.fillMaxSize()) {
        GalleryBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FixtureToggle("POSIX home", fixture == FolderBrowserFixtureId.POSIX_HOME) {
                    onPick(FolderBrowserFixtureId.POSIX_HOME)
                }
                FixtureToggle("Root /", fixture == FolderBrowserFixtureId.ROOT) {
                    onPick(FolderBrowserFixtureId.ROOT)
                }
                FixtureToggle("Windows C:\\Users\\alice", fixture == FolderBrowserFixtureId.WINDOWS) {
                    onPick(FolderBrowserFixtureId.WINDOWS)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FixtureToggle("Loading", fixture == FolderBrowserFixtureId.LOADING) {
                    onPick(FolderBrowserFixtureId.LOADING)
                }
                FixtureToggle("Offline", fixture == FolderBrowserFixtureId.OFFLINE) {
                    onPick(FolderBrowserFixtureId.OFFLINE)
                }
                FixtureToggle("No engines", fixture == FolderBrowserFixtureId.NO_ENGINES) {
                    onPick(FolderBrowserFixtureId.NO_ENGINES)
                }
            }
            BasicText(
                "The sheet is re-presented settled on every fixture pick (✕ dismisses it " +
                    "first). Tap the capsule for the engine menu, tap rows to descend, " +
                    "\"Parent Folder\" to ascend — FakeHostPaths keeps the engine's own " +
                    "separator style. The Offline fixture fails every load: the rows of the " +
                    "last good folder stay, taps stop working (the status string itself is " +
                    "ticket 111's).",
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary),
            )
        }
    }
}

/** One fixture toggle: SemiBold `text` when selected, Regular `secondary` when not. */
@Composable
private fun FixtureToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    BasicText(
        label,
        Modifier.clickable(role = Role.Button, onClick = onSelect),
        style = uiStyle(
            if (selected) Face.SansSemibold else Face.Sans,
            ts.fixed(14f),
            if (selected) palette.text else palette.secondary,
        ),
    )
}

// ---------------------------------------------------------------------------
// The shared sheet page (gallery + previews)
// ---------------------------------------------------------------------------

/**
 * The sheet's content over a fixture: the hosts flow replays the fixture
 * engine list (so `rememberFolderBrowserController` performs the
 * first-call load right after the first frame), the fixture source
 * answers immediately, and the paths come from [FakeHostPaths]. 111 adds
 * the write actions over [FakeProjectWriteApi] (New Folder… browses into
 * the fake new folder; Use calls the fake create and dismisses the sheet
 * — a fixture pick re-presents it settled), and 112 the locations rail
 * and the search/typed-path field over [FakeFolderCalls] (the fixture's
 * drives; a typed path probes the fake, so `Create and add` shows).
 */
@Composable
internal fun PageSheetScope.FolderBrowserFixtureSheetPage(fixture: FolderBrowserFixtureId) {
    val hosts = remember(fixture) { folderBrowserFixtureHosts(fixture) }
    val source = remember(fixture) { folderBrowserFixtureSource(fixture) }
    val flow = remember(fixture) { MutableStateFlow<List<HostOption>?>(hosts) }
    val controller = rememberFolderBrowserController(flow, source, paths = FakeHostPaths)
    val writeApi = remember(fixture) { FakeProjectWriteApi() }
    val actions = rememberFolderBrowserActions(
        controller = controller,
        paths = FakeHostPaths,
        api = writeApi,
        onCreated = {},
        dismissSheet = { dismiss(animated = true) },
    )
    val drives = remember(fixture) { fixtureDrives(fixture) }
    val calls = remember(fixture) {
        FakeFolderCalls(hosts.associate { it.id to drives })
    }
    val nav = rememberFolderNavState(controller, actions, calls)
    NewProjectScreen(sheet = this, controller = controller, actions = actions, nav = nav)
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: one settled New Project sheet over the backdrop, POSIX fixture. */
@Composable
private fun FolderBrowserPreviewStage(appearance: Appearance) {
    RobocoTheme(appearance) {
        GlassMenuHost {
            val state = rememberSheetHostState()
            state.presentSettledForPreview("new-project", SheetStyle.Page(grabber = false)) {
                FolderBrowserFixtureSheetPage(FolderBrowserFixtureId.POSIX_HOME)
            }
            SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
        }
    }
}

@Preview(name = "light")
@Composable
private fun FolderBrowserLightPreview() {
    FolderBrowserPreviewStage(Appearance.Light)
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FolderBrowserDarkPreview() {
    FolderBrowserPreviewStage(Appearance.Dark)
}

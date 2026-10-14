package sh.roboco.android.newsession

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.NewSessionSheetPlaceholder
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import uniffi.roboco_core.DriveEntry
import uniffi.roboco_core.FolderEntry
import uniffi.roboco_core.FolderListing

/**
 * The New Project screen's goldens and behaviour (android-ui-parity #110),
 * recorded by `recordRoborazziDebug` and verified by `verifyRoborazziDebug`
 * at the ticket's phone frame `w402dp-h874dp-xxhdpi`, inside a **settled**
 * page sheet (ticket 31's `presentSettledForPreview` — the captured frame
 * is at rest, never mid-presentation).
 *
 * The controller is built outside composition over an immediate source and
 * [FakeHostPaths] (the debug source set), with a `Dispatchers.Unconfined`
 * scope so the first load applies synchronously — the captured frame needs
 * no clock at all. `Glass.forceFallback` and Reduce Motion follow the
 * gallery-capture convention (ticket 22), so the goldens stay byte-stable.
 * Paths are relative to the `roborazzi { outputDir }` (`src/test/screenshots`),
 * landing under `newsession/`.
 *
 * Also the pure [navTitleX] unit tests (the ticket's three examples) and
 * the row-geometry pins of the 402 dp reference width (card x 16, 52 dp
 * rows, title x 72).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class NewProjectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    // --------------------------------------------------- navTitleX (pure)

    @Test
    fun navTitleXCentresOnTheSheetWhenItFits() {
        assertEquals(400, navTitleX(width = 1000, titleWidth = 200, regionStart = 68, regionEnd = 800))
    }

    @Test
    fun navTitleXSlidesRightWhenTheCloseButtonWouldOverlap() {
        assertEquals(68, navTitleX(width = 1000, titleWidth = 900, regionStart = 68, regionEnd = 990))
    }

    @Test
    fun navTitleXSlidesLeftWhenTheCapsuleWouldOverlap() {
        assertEquals(300, navTitleX(width = 1000, titleWidth = 300, regionStart = 68, regionEnd = 600))
    }

    // --------------------------------------------------------- the fakes

    /** A source whose answer the test swaps mid-flight; never suspends. */
    private class ResolvingSource(
        var listingFor: (String?) -> FolderListing?,
    ) : FolderBrowserSource {
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun listFolders(
            deviceId: String,
            path: String?,
            showHidden: Boolean,
        ): FolderListing? {
            calls += deviceId to path
            return listingFor(path)
        }
    }

    /**
     * A controller whose first load has already applied: `Unconfined` plus
     * a never-suspending source runs the whole load synchronously inside
     * `updateEngines`, so composition and capture need no clock.
     */
    private fun settled(source: FolderBrowserSource, hosts: List<HostOption>): FolderBrowserController {
        val controller = FolderBrowserController(
            source,
            FakeHostPaths,
            CoroutineScope(Dispatchers.Unconfined),
            initialHosts = hosts,
        )
        controller.updateEngines(hosts)
        return controller
    }

    /**
     * 111's actions over a settled controller: the debug
     * [FakeProjectWriteApi] (never called — these tests never tap Use)
     * and a dropped-alerts [sh.roboco.android.design.Alerts.Noop].
     */
    private fun actionsFor(controller: FolderBrowserController): FolderBrowserActions =
        FolderBrowserActions(
            controller,
            FakeHostPaths,
            FakeProjectWriteApi(),
            Alerts.Noop,
            CoroutineScope(Dispatchers.Unconfined),
            onCreated = {},
            dismissSheet = {},
        )

    /** 112's nav over the same pair: the debug [FakeFolderCalls] with the macOS drives. */
    private fun navFor(
        controller: FolderBrowserController,
        actions: FolderBrowserActions,
    ): FolderNavState = FolderNavState(
        FolderBrowserHostAdapter(controller, actions),
        FakeFolderCalls(
            mapOf(
                "dev-mbp" to listOf(
                    DriveEntry(name = "Macintosh HD", path = "/"),
                    DriveEntry(name = "Seagate", path = "/Volumes/Seagate"),
                ),
            ),
        ),
        CoroutineScope(Dispatchers.Unconfined),
    )

    /** Presents the screen in a settled page sheet over the backdrop. */
    private fun present(
        controller: FolderBrowserController,
        appearance: Appearance = Appearance.Light,
    ) {
        val actions = actionsFor(controller)
        val nav = navFor(controller, actions)
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("new-project", SheetStyle.Page(grabber = false)) {
                NewProjectScreen(sheet = this, controller = controller, actions = actions, nav = nav)
            }
        }
        compose.setContent {
            RobocoTheme(appearance) {
                GlassMenuHost {
                    SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(
        controller: FolderBrowserController,
        appearance: Appearance,
        file: String,
        openMenu: Boolean = false,
    ) {
        present(controller, appearance)
        if (openMenu) {
            compose.onNodeWithTag("folder-engine").performClick()
            compose.mainClock.advanceTimeBy(600) // past the open spring
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("newsession/$file")
    }

    /** The ticket's POSIX home fixture, always answered. */
    private fun posixController(): FolderBrowserController =
        settled(ResolvingSource { posixHomeListing() }, folderBrowserFixtureHosts())

    // ---------------------------------------------------------- goldens

    @Test
    fun posixLight() {
        shot(posixController(), Appearance.Light, "folder_browser_light.png")
    }

    @Test
    fun posixDark() {
        shot(posixController(), Appearance.Dark, "folder_browser_dark.png")
    }

    @Test
    fun windowsLight() {
        shot(
            settled(ResolvingSource { windowsHomeListing() }, folderBrowserFixtureHosts()),
            Appearance.Light,
            "folder_browser_windows_light.png",
        )
    }

    @Test
    fun rootLight() {
        shot(
            settled(ResolvingSource { rootListing() }, folderBrowserFixtureHosts()),
            Appearance.Light,
            "folder_browser_root_light.png",
        )
    }

    @Test
    fun noEnginesLight() {
        shot(
            settled(ResolvingSource { null }, emptyList()),
            Appearance.Light,
            "folder_browser_no_engines_light.png",
        )
    }

    @Test
    fun menuOpenLight() {
        shot(posixController(), Appearance.Light, "folder_browser_menu_light.png", openMenu = true)
    }

    // -------------------------------------------------------- behaviour

    @Test
    fun theEngineMenuOpensOnTapAndPicksReloadAtHome() {
        val source = ResolvingSource { posixHomeListing() }
        val controller = settled(source, folderBrowserFixtureHosts())
        present(controller)
        assertEquals(listOf("dev-mbp" to null), source.calls)

        // Tap the capsule: the menu opens with the web title rule and one
        // row per engine (no checkmark — iOS marks no current engine).
        compose.onNodeWithTag("folder-engine").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithText("Engines").assertExists()
        assertEquals(2, compose.onAllNodesWithTag("folder-engine-row").fetchSemanticsNodes().size)

        // Picking hetzner-01 re-titles the capsule at once and reloads at home.
        compose.onAllNodesWithTag("folder-engine-row")[1].performClick()
        compose.mainClock.advanceTimeBy(250) // the pick runs on the next frame
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame() // the close fade finishes
        compose.waitForIdle()
        assertEquals("dev-htz" to null, source.calls.last())
        compose.onNodeWithTag("folder-engine").assertTextEquals("hetzner-01")
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun theCapsuleOpensNothingWithNoEngines() {
        val controller = settled(ResolvingSource { null }, emptyList())
        present(controller)

        compose.onNodeWithTag("folder-engine").assertTextEquals("No paired engines yet.")
        // 112: the locations block is absent with no paired engine.
        compose.onNodeWithTag("folder-locations").assertDoesNotExist()
        compose.onNodeWithTag("folder-search").assertDoesNotExist()
        compose.onNodeWithTag("folder-engine").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    @Test
    fun theCloseButtonDismissesOnlyTheNewProjectSheet() {
        val controller = posixController()
        val actions = actionsFor(controller)
        val nav = navFor(controller, actions)
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("new-session", SheetStyle.Page(grabber = true)) {
                NewSessionSheetPlaceholder(this)
            }
            state.presentSettledForPreview("new-project", SheetStyle.Page(grabber = false)) {
                NewProjectScreen(sheet = this, controller = controller, actions = actions, nav = nav)
            }
        }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost {
                    SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()

        // Both sheets have a ✕ (tag "sheet-close"); the host composes
        // entries bottom → top, so index 1 is the New Project sheet's.
        compose.onAllNodesWithTag("sheet-close")[1].performClick()
        compose.mainClock.advanceTimeBy(2000) // past the dismissal spring
        compose.waitForIdle()
        compose.onNodeWithTag("page-sheet:new-project").assertDoesNotExist()
        compose.onNodeWithTag("page-sheet:new-session").assertExists()
    }

    // -------------------------------------------------------- geometry

    @Test
    fun rowGeometryMatchesTheReference() {
        val controller = posixController()
        present(controller)

        compose.onNodeWithTag("folder-list").assertExists()
        compose.onNodeWithText("Parent Folder").assertExists()
        val rows = compose.onAllNodesWithTag("folder-row")
        assertEquals(5, rows.fetchSemanticsNodes().size)

        // The card's inset (the row node carries ticket 70's 16 dp card
        // padding), the 52 dp single-line row, the title at 16 + 56.
        val first = rows[0]
        first.assertLeftPositionInRootIsEqualTo(16.dp)
        first.assertHeightIsEqualTo(52.dp)
        compose.onNodeWithText("Desktop", useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(72.dp)
        val parent = compose.onNodeWithTag("folder-parent")
        parent.assertLeftPositionInRootIsEqualTo(16.dp)
        parent.assertHeightIsEqualTo(52.dp)
        // The nav bar's title region: the sheet-centred title for "alice".
        compose.onNodeWithTag("folder-title").assertTextEquals("alice")
    }

    // ------------------------------------------------- scroll retention

    @Test
    fun aNewListingKeepsTheFirstVisibleIndexClamped() {
        val source = ResolvingSource { bigListing("/Users/alice", 30) }
        val controller = settled(source, folderBrowserFixtureHosts())
        // Gesture injection (the scroll below) needs the live clock; the
        // listings themselves still apply synchronously (Unconfined scope).
        compose.mainClock.autoAdvance = true
        present(controller)
        compose.waitForIdle()

        compose.onNodeWithTag("folder-list").performScrollToIndex(30) // row-29, the last
        compose.waitForIdle()

        // A new listing (21 rows) replaces the rows with new keys: the old
        // first-visible index is kept, clamped to the new item count — never
        // reset to the top.
        compose.runOnUiThread {
            source.listingFor = { bigListing("/Users/alice/Projects", 20) }
            controller.load("/Users/alice/Projects")
        }
        compose.waitForIdle()
        compose.onNodeWithText("row-0").assertDoesNotExist()
        compose.onNodeWithText("row-19").assertExists()
    }
}

/** The scroll test's many-row listing. */
private fun bigListing(path: String, count: Int): FolderListing = FolderListing(
    path = path,
    entries = (0 until count).map { index ->
        FolderEntry(name = "row-$index", isDir = true, isRepo = false)
    },
    truncated = false,
)

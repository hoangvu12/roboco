package sh.roboco.android.newsession

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.hasContent
import uniffi.roboco_core.FolderListing

/**
 * [FolderBrowserController] (android-ui-parity #110): every acceptance row
 * of the ticket's load-guard table — the synchronous default engine from
 * `initialHosts` (no `No paired engines yet.` flash, no call until the
 * first `updateEngines`), the generation guard (out-of-order replies,
 * engine switches mid-flight), the failure strings and the
 * "last good folder stays usable only on the same engine" rule, the
 * `pr685` tap guard, the live host-list rules, the `onListingApplied` /
 * `listingIsRepo` hooks, and the `engineMenu` model (the web
 * Engines/Devices rule, one row per engine, picks reload at home).
 *
 * Robolectric, logic-style (the `FolderListing`/`FolderEntry` records are
 * uniffi types — constructing them never loads the native core; the
 * codebase's convention for record-constructing suites), with
 * [FakeHostPaths] from the debug source set and the gallery's fixtures.
 * `runTest` passes its `backgroundScope` as the controller's scope, so a
 * gate left pending at the test's end cannot fail the test; the
 * [GatedSource] suspends each call on its own [CompletableDeferred], so
 * replies can be completed in any order. No snapshot reads: Compose
 * `mutableStateOf` works in plain tests and is asserted directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderBrowserControllerTest {

    /** The ticket's fixture engines: MacBook Pro online, hetzner-01 offline. */
    private val hosts = folderBrowserFixtureHosts()

    /** A source whose replies the test completes by call index, in any order. */
    private class GatedSource : FolderBrowserSource {
        val calls = mutableListOf<Pair<String, String?>>()

        /** The `showHidden` flag of each recorded call (112's `.`-query reload). */
        val hiddenFlags = mutableListOf<Boolean>()
        private val gates = mutableListOf<CompletableDeferred<FolderListing?>>()

        override suspend fun listFolders(
            deviceId: String,
            path: String?,
            showHidden: Boolean,
        ): FolderListing? {
            calls += deviceId to path
            hiddenFlags += showHidden
            val gate = CompletableDeferred<FolderListing?>()
            synchronized(gates) { gates += gate }
            return gate.await()
        }

        fun complete(index: Int, listing: FolderListing?) {
            synchronized(gates) { gates[index] }.complete(listing)
        }
    }

    /** A controller over [GatedSource] and this test's virtual-clock scope. */
    private fun TestScope.browser(
        source: FolderBrowserSource,
        initialHosts: List<HostOption>? = null,
    ): FolderBrowserController =
        FolderBrowserController(source, FakeHostPaths, backgroundScope, initialHosts = initialHosts)

    /** Opens the browser: first `updateEngines`, then the call is recorded but the reply pending. */
    private fun TestScope.open(controller: FolderBrowserController, hosts: List<HostOption>) {
        controller.updateEngines(hosts)
        runCurrent()
    }

    // ---------------------------------------------------- the open and pick

    @Test
    fun constructionWithInitialHostsPrePicksTheDefaultEngineWithoutLoading() = runTest {
        val source = GatedSource()
        val controller = browser(source, initialHosts = hosts)

        // Synchronous, before any coroutine runs: the capsule already shows
        // the default engine, never "No paired engines yet.".
        assertEquals("MacBook Pro", controller.capsuleTitle)
        assertTrue("no load before the first updateEngines", source.calls.isEmpty())
        assertNull(controller.listing)
        assertFalse(controller.loading)

        // The replayed hosts flow performs the first load.
        controller.updateEngines(hosts)
        runCurrent()
        assertEquals(listOf("dev-mbp" to null), source.calls)
    }

    @Test
    fun openPicksTheFirstOnlineEngineAndLoadsItsHome() = runTest {
        val source = GatedSource()
        val controller = browser(source)

        controller.updateEngines(hosts)
        assertEquals("MacBook Pro", controller.capsuleTitle)
        assertTrue(controller.loading)
        assertEquals(FolderBrowserCopy.Loading, controller.status)

        runCurrent()
        assertEquals(listOf("dev-mbp" to null), source.calls)
        assertTrue("the reply is still pending", controller.loading)

        source.complete(0, posixHomeListing())
        runCurrent()
        assertFalse(controller.loading)
        assertEquals("/Users/alice", controller.status)
        assertEquals("/Users/alice", controller.path)
        assertEquals("dev-mbp", controller.listingEngineId)
        assertNull("home carries no repo hint", controller.listingIsRepo)
        assertEquals(1, controller.listingSerial)
        assertTrue(controller.listingUsable)
    }

    @Test
    fun theFirstEngineIsPickedWhenNothingIsOnline() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, listOf(HostOption("dev-htz", "hetzner-01", online = false)))

        assertEquals("hetzner-01", controller.capsuleTitle)
        assertEquals(listOf("dev-htz" to null), source.calls)
    }

    @Test
    fun noEnginesSetsTheStatusAndNeverCallsTheCore() = runTest {
        val source = GatedSource()
        val controller = browser(source)

        controller.updateEngines(emptyList())
        assertEquals(FolderBrowserCopy.NoEngines, controller.status)
        assertEquals(FolderBrowserCopy.NoEngines, controller.capsuleTitle)
        assertFalse(controller.loading)
        assertTrue(source.calls.isEmpty())

        controller.load("/some/path") // explicit load with no engine
        assertTrue(source.calls.isEmpty())
        assertEquals(FolderBrowserCopy.NoEngines, controller.status)
        assertFalse(controller.loading)
    }

    @Test
    fun selectEngineRetitlesAtOnceAndReloadsAtHome() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()

        controller.selectEngine("dev-htz")
        assertEquals("the capsule re-titles at once", "hetzner-01", controller.capsuleTitle)
        runCurrent()
        assertEquals(listOf("dev-mbp" to null, "dev-htz" to null), source.calls)

        // Picking the already-selected engine also reloads at home.
        controller.selectEngine("dev-htz")
        runCurrent()
        assertEquals(listOf("dev-mbp" to null, "dev-htz" to null, "dev-htz" to null), source.calls)

        // An unknown id is a no-op (no pick, no load).
        controller.selectEngine("nope")
        assertEquals(3, source.calls.size)
    }

    // ------------------------------------------------- the live host list

    @Test
    fun aLiveListRefreshesTheSelectedEngineWithoutAReload() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()
        val callsBefore = source.calls.size

        val refreshed = listOf(
            HostOption("dev-mbp", "MacBook Pro 2", online = true),
            HostOption("dev-htz", "hetzner-01", online = true),
        )
        controller.updateEngines(refreshed)

        assertEquals("the same-id entry replaces the engine (fresh name)", "MacBook Pro 2", controller.capsuleTitle)
        assertEquals("no reload when the selected id stays", callsBefore, source.calls.size)
        assertEquals(1, controller.listingSerial)
    }

    @Test
    fun aVanishedSelectedEngineReRunsTheDefaultPickAndLoads() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()

        controller.updateEngines(listOf(HostOption("dev-htz", "hetzner-01", online = false)))
        assertEquals("hetzner-01", controller.capsuleTitle)
        runCurrent()
        assertEquals(listOf("dev-mbp" to null, "dev-htz" to null), source.calls)
    }

    @Test
    fun aNullEngineWithANonEmptyListPicksAndLoads() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        controller.updateEngines(emptyList())
        runCurrent()
        assertTrue(source.calls.isEmpty())

        controller.updateEngines(hosts)
        assertEquals("MacBook Pro", controller.capsuleTitle)
        runCurrent()
        assertEquals(listOf("dev-mbp" to null), source.calls)
    }

    // ------------------------------------------------------ generation guard

    @Test
    fun aSupersededReplyIsDroppedWithoutAnyStateChange() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)

        controller.load("/a")
        controller.load("/b")
        runCurrent()
        assertEquals(listOf("dev-mbp" to null, "dev-mbp" to "/a", "dev-mbp" to "/b"), source.calls)

        // B answers first, A's and the home reply land later — only B applies.
        source.complete(2, posixHomeListing())
        runCurrent()
        assertEquals("/Users/alice", controller.path)
        assertEquals(1, controller.listingSerial)

        source.complete(1, windowsHomeListing())
        runCurrent()
        source.complete(0, rootListing())
        runCurrent()
        assertEquals("the late replies change nothing", "/Users/alice", controller.path)
        assertEquals(1, controller.listingSerial)
        assertEquals("/Users/alice", controller.status)
        assertFalse(controller.loading)
    }

    @Test
    fun anOlderReplyLeavesLoadingAndTheLoadingStatusUnchanged() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)

        controller.load("/a")
        controller.load("/b")
        runCurrent()

        source.complete(1, windowsHomeListing()) // A completes first — superseded
        runCurrent()
        assertTrue(controller.loading)
        assertEquals(FolderBrowserCopy.Loading, controller.status)
        assertNull(controller.listing)

        source.complete(2, posixHomeListing()) // B completes last — only B applies
        runCurrent()
        assertFalse(controller.loading)
        assertEquals("/Users/alice", controller.path)
        assertEquals(1, controller.listingSerial)
    }

    @Test
    fun switchingEnginesDropsTheLateReplyAndGuardsUsability() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()
        assertTrue(controller.listingUsable)

        controller.selectEngine("dev-htz")
        runCurrent()
        assertFalse("the last good listing came from another engine", controller.listingUsable)
        // The old engine's rows and title stay on screen.
        assertEquals("/Users/alice", controller.path)
        assertEquals("alice", controller.title)
        assertEquals(1, controller.listingSerial)

        source.complete(1, rootListing())
        runCurrent()
        assertTrue(controller.listingUsable)
        assertEquals("/", controller.path)
    }

    @Test
    fun aForgottenEngineCancelsItsOwnInFlightReply() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts) // gate 0 pending on dev-mbp

        controller.updateEngines(emptyList())
        assertNull(controller.engine)
        assertFalse(controller.loading)
        assertEquals(FolderBrowserCopy.NoEngines, controller.status)
        assertEquals(FolderBrowserCopy.NoEngines, controller.capsuleTitle)

        source.complete(0, posixHomeListing())
        runCurrent()
        assertNull("the late reply changes nothing", controller.listing)
        assertEquals(FolderBrowserCopy.NoEngines, controller.status)
        assertEquals(0, controller.listingSerial)
    }

    // ------------------------------------------------------------ failures

    @Test
    fun aFailedDescendKeepsTheLastGoodFolderUsableOnTheSameEngine() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()

        controller.tap(FolderRow.Folder("Projects", isRepo = false))
        runCurrent()
        source.complete(1, null)
        runCurrent()

        assertFalse(controller.loading)
        assertEquals(FolderBrowserCopy.ReadFailed, controller.status)
        assertEquals("/Users/alice", controller.path)
        assertEquals("alice", controller.title)
        assertEquals(posixHomeListing(), controller.listing)
        assertTrue("same engine: the last good folder is still usable", controller.listingUsable)
    }

    @Test
    fun anOfflineEngineNamesItselfInTheStatus() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, listOf(HostOption("dev-htz", "hetzner-01", online = false)))

        source.complete(0, null)
        runCurrent()
        assertEquals("hetzner-01 is offline.", controller.status)
        assertFalse(controller.listingUsable)
    }

    @Test
    fun aFailedSwitchKeepsTheRowsButNotTheUsability() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()

        controller.selectEngine("dev-htz")
        runCurrent()
        source.complete(1, null)
        runCurrent()

        assertEquals("hetzner-01 is offline.", controller.status)
        assertEquals("/Users/alice", controller.path)
        assertEquals("alice", controller.title)
        assertEquals(posixHomeListing(), controller.listing)
        assertFalse("another engine's listing is not usable", controller.listingUsable)
    }

    // ----------------------------------------------------------- tap guard

    @Test
    fun tapsAreIgnoredWhileLoadingWhileSubmittingAndAcrossEngines() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()

        // While a load is in flight.
        controller.tap(FolderRow.Parent)
        runCurrent()
        val withPendingTap = source.calls.size
        controller.tap(FolderRow.Folder("Desktop", isRepo = false))
        assertEquals("ignored while loading", withPendingTap, source.calls.size)
        source.complete(withPendingTap - 1, posixHomeListing())
        runCurrent()

        // While ticket 111's submitting flag is set.
        controller.submitting = true
        controller.tap(FolderRow.Folder("Desktop", isRepo = false))
        assertEquals("ignored while submitting", withPendingTap, source.calls.size)
        controller.submitting = false

        // When the listing came from another engine (the switch failed, so
        // nothing is loading and the old rows stay on screen).
        controller.selectEngine("dev-htz")
        runCurrent()
        source.complete(source.calls.lastIndex, null)
        runCurrent()
        controller.tap(FolderRow.Folder("Desktop", isRepo = false))
        assertEquals("ignored across engines: only the engine-switch home load", withPendingTap + 1, source.calls.size)
    }

    @Test
    fun tapsDescendAndAscendWithTheEnginePathRules() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, windowsHomeListing())
        runCurrent()

        // Ascend from the home listing: C:\\Users\\alice → C:\\Users.
        controller.tap(FolderRow.Parent)
        runCurrent()
        assertEquals("dev-mbp" to "C:\\Users", source.calls.last())
        // Return to the home listing (the gate must clear before the next tap).
        source.complete(source.calls.lastIndex, windowsHomeListing())
        runCurrent()

        // Descend from the home listing: C:\\Users\\alice + Projects.
        controller.tap(FolderRow.Folder("Projects", isRepo = false))
        runCurrent()
        assertEquals("dev-mbp" to "C:\\Users\\alice\\Projects", source.calls.last())
    }

    @Test
    fun theParentTapLoadsNothingAtARoot() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, rootListing())
        runCurrent()
        val callsAtRoot = source.calls.size

        controller.tap(FolderRow.Parent)
        assertEquals("paths.parent(\"/\") is null: no load", callsAtRoot, source.calls.size)
    }

    // ------------------------------------------------------- derived state

    @Test
    fun rowsAndTitleFollowTheListing() = runTest {
        val source = GatedSource()
        val controller = browser(source)

        assertNull(controller.listing)
        assertEquals(FolderBrowserCopy.DefaultTitle, controller.title)
        assertTrue(controller.rows.isEmpty())

        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()
        assertEquals(
            listOf(
                FolderRow.Parent,
                FolderRow.Folder("Desktop", isRepo = false),
                FolderRow.Folder("Documents", isRepo = false),
                FolderRow.Folder("Projects", isRepo = false),
                FolderRow.Folder("roboco", isRepo = true),
                FolderRow.Folder("zeron", isRepo = true),
            ),
            controller.rows,
        )
        assertEquals("alice", controller.title)

        controller.load("/")
        runCurrent()
        source.complete(1, rootListing())
        runCurrent()
        assertEquals(
            listOf(
                FolderRow.Folder("Applications", isRepo = false),
                FolderRow.Folder("Users", isRepo = false),
            ),
            controller.rows,
        )
        assertEquals("/", controller.title)

        controller.load("C:\\Users\\alice")
        runCurrent()
        source.complete(2, windowsHomeListing())
        runCurrent()
        assertEquals(
            listOf(
                FolderRow.Parent,
                FolderRow.Folder("Projects", isRepo = false),
                FolderRow.Folder("roboco", isRepo = true),
            ),
            controller.rows,
        )
        assertEquals("alice", controller.title)

        // A drive root: no parent row, the title is the path itself.
        controller.load("C:\\")
        runCurrent()
        source.complete(3, FolderListing(path = "C:\\", entries = emptyList(), truncated = false))
        runCurrent()
        assertTrue(controller.rows.isEmpty())
        assertEquals("C:\\", controller.title)
    }

    // --------------------------------------------------------- the hooks

    @Test
    fun onListingAppliedFiresOncePerAppliedListingAndNeverForStaleOrFailedReplies() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        val applied = mutableListOf<Pair<String, String>>()
        controller.onListingApplied = { engineId, listing -> applied += engineId to listing.path }

        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()
        assertEquals(listOf("dev-mbp" to "/Users/alice"), applied)

        controller.load("/a")
        controller.load("/b")
        runCurrent()
        source.complete(1, windowsHomeListing()) // superseded — never fires
        source.complete(2, null) // failed — never fires
        runCurrent()
        assertEquals(1, applied.size)
    }

    @Test
    fun listingIsRepoTracksTheRowThatReachedTheCurrentFolder() = runTest {
        val source = GatedSource()
        val controller = browser(source)
        open(controller, hosts)
        source.complete(0, posixHomeListing())
        runCurrent()
        assertNull(controller.listingIsRepo)

        controller.tap(FolderRow.Folder("roboco", isRepo = true))
        runCurrent()
        source.complete(1, syntheticFolderListing("/Users/alice/roboco"))
        runCurrent()
        assertEquals(true, controller.listingIsRepo)

        controller.tap(FolderRow.Folder("Desktop", isRepo = false))
        runCurrent()
        source.complete(2, syntheticFolderListing("/Users/alice/Desktop"))
        runCurrent()
        assertEquals(false, controller.listingIsRepo)

        controller.tap(FolderRow.Parent)
        runCurrent()
        source.complete(3, posixHomeListing())
        runCurrent()
        assertNull("the parent carries no hint", controller.listingIsRepo)

        controller.selectEngine("dev-htz")
        runCurrent()
        source.complete(4, rootListing())
        runCurrent()
        assertNull("an engine switch resets the hint", controller.listingIsRepo)
    }

    // -------------------------------------------------------- engine menu

    @Test
    fun theEngineMenuFollowsTheWebTitleRuleAndPicksEngines() = runTest {
        val source = GatedSource()
        val controller = browser(source, initialHosts = hosts)

        val menu = engineMenu(controller)
        assertEquals("Engines", menu.title)
        val rows = menu.children.filterIsInstance<MenuItem>()
        assertEquals(listOf("MacBook Pro", "hetzner-01"), rows.map { it.title })
        assertEquals(listOf("Online", "Offline"), rows.map { it.subtitle })
        assertTrue(rows.all { !it.checked })
        assertTrue(rows.all { it.icon is MenuIcon.Symbol })
        assertTrue(rows.all { it.testTag == "folder-engine-row" })

        // One engine → "Devices"; no engines → no rows and no content.
        val single = browser(GatedSource(), initialHosts = listOf(hosts[0]))
        assertEquals("Devices", engineMenu(single).title)
        val none = browser(GatedSource(), initialHosts = emptyList())
        val emptyMenu = engineMenu(none)
        assertTrue(emptyMenu.children.isEmpty())
        assertFalse("with no engines the capsule opens nothing", emptyMenu.hasContent())

        // A row pick selects the engine and reloads at home.
        rows[1].action()
        assertEquals("hetzner-01", controller.capsuleTitle)
        runCurrent()
        assertEquals(listOf("dev-htz" to null), source.calls)
    }
}

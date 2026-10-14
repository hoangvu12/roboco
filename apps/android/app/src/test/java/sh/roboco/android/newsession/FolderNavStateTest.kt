package sh.roboco.android.newsession

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.DriveEntry
import uniffi.roboco_core.FolderEntry
import uniffi.roboco_core.FolderListing
import uniffi.roboco_core.PreparedPath

/**
 * [FolderNavState] (android-ui-parity #112): the rail's drives load and
 * its staleness rules, the search field's filter/hidden-toggle edits, the
 * IME-Go split (probe / first-row browse / blank no-op), the probe →
 * Missing → **Create and add** flow over 111's seams, and the status/Use
 * overrides — every state-level acceptance row of the ticket.
 *
 * Plain JUnit, `runTest` with the standard test dispatcher: the uniffi
 * records (`DriveEntry`/`FolderListing`/`FolderEntry`/`PreparedPath`) and
 * `CoreException` are plain classes whose construction never loads the
 * native core (the generated facade's only eager initializer is a pure
 * `UniffiHandleMap`), and [FolderNavState] itself touches no Android API.
 * `runTest` passes its `backgroundScope` as the state's scope, so a gate
 * left pending at the test's end cannot fail it; [FakeCalls] suspends
 * every call on its own [CompletableDeferred], so replies can be
 * completed in any order and staleness can be scripted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderNavStateTest {

    private companion object {
        const val DEV = "dev-1"
    }

    // --------------------------------------------------------------- fakes

    /** The 110/111 seam: mutable browsing state + recorded write calls. */
    private class FakeHost(engineId: String? = DEV) : FolderBrowserHost {
        // Snapshot-backed so the derived reads (selectedChip, useOverride)
        // recompute after a mutation, like the real controller's states.
        override var deviceId: String? by mutableStateOf(engineId)
        override var deviceOnline: Boolean by mutableStateOf(true)
        override var listing: FolderListing? by mutableStateOf(null)
        override var listingDeviceId: String? by mutableStateOf(null)
        override var home: String? by mutableStateOf(null)
        override var loading: Boolean by mutableStateOf(false)
        // Not `override var submitting`: its JVM setter setSubmitting(Z)V
        // would clash with the interface's fun setSubmitting(busy) below.
        var submittingNow: Boolean by mutableStateOf(false)
        override val submitting: Boolean get() = submittingNow

        /** (path, showHidden, gitHint) per [load]. */
        val loads = mutableListOf<Triple<String?, Boolean, Boolean?>>()
        val submits = mutableListOf<Boolean>()
        var createResult = true
        val creates = mutableListOf<Pair<String, Boolean>>()

        override fun load(path: String?, showHidden: Boolean, gitHint: Boolean?) {
            loads += Triple(path, showHidden, gitHint)
        }

        override fun setSubmitting(busy: Boolean) {
            submittingNow = busy
            submits += busy
        }

        override suspend fun createProjectAt(path: String, gitDetected: Boolean): Boolean {
            creates += path to gitDetected
            return createResult
        }
    }

    /** The calls seam: every call gated, every call recorded. */
    private class FakeCalls : FolderBrowserCalls {
        val drivesCalls = mutableListOf<String>()
        val prepareCalls = mutableListOf<Triple<String, String, Boolean>>()
        val joins = mutableListOf<Pair<String, String>>()
        var driveError: Throwable? = null
        private val drivesGates = mutableListOf<CompletableDeferred<List<DriveEntry>>>()
        private val prepareGates = mutableListOf<CompletableDeferred<PreparedPath>>()

        override suspend fun listDrives(deviceId: String): List<DriveEntry> {
            drivesCalls += deviceId
            driveError?.let { throw it }
            val gate = CompletableDeferred<List<DriveEntry>>()
            drivesGates += gate
            return gate.await()
        }

        override suspend fun prepareSpacePath(
            deviceId: String,
            path: String,
            createIfMissing: Boolean,
        ): PreparedPath {
            prepareCalls += Triple(deviceId, path, createIfMissing)
            val gate = CompletableDeferred<PreparedPath>()
            prepareGates += gate
            return gate.await()
        }

        override fun join(base: String, name: String): String {
            joins += base to name
            return "$base/$name"
        }

        fun completeDrives(index: Int, drives: List<DriveEntry>) {
            drivesGates[index].complete(drives)
        }

        fun completePrepare(index: Int, prepared: PreparedPath) {
            prepareGates[index].complete(prepared)
        }

        fun failPrepare(index: Int, error: Throwable) {
            prepareGates[index].completeExceptionally(error)
        }
    }

    // ------------------------------------------------------------ harness

    private fun TestScope.navState(
        host: FakeHost = FakeHost(),
        calls: FakeCalls = FakeCalls(),
    ): FolderNavState = FolderNavState(host, calls, backgroundScope).also { runCurrent() }

    /** A listing on [DEV] with the given directory entries. */
    private fun listing(path: String, vararg dirs: Pair<String, Boolean>): FolderListing =
        FolderListing(
            path = path,
            entries = dirs.map { (name, isRepo) -> FolderEntry(name = name, isDir = true, isRepo = isRepo) },
            truncated = false,
        )

    // -------------------------------------------------------- the drives

    @Test
    fun anEngineChangeResetsTheRailThenAppliesThatEnginesDrives() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onDevice(DEV, online = true)
        runCurrent()
        assertEquals(listOf(DEV), calls.drivesCalls)

        // The switch (the selected engine moves with it) resets to Home
        // only before the new load answers.
        host.deviceId = "dev-2"
        nav.onDevice("dev-2", online = true)
        runCurrent()
        assertEquals(emptyList<DriveEntry>(), nav.drives)
        assertEquals(1, nav.chips.size)
        assertEquals(LocationChipModel("Home", null), nav.chips.single())

        // The old engine's late reply is dropped (drivesGen moved on).
        calls.completeDrives(0, listOf(DriveEntry(name = "System", path = "/")))
        runCurrent()
        assertEquals(emptyList<DriveEntry>(), nav.drives)

        // The new engine's reply applies.
        calls.completeDrives(1, listOf(DriveEntry(name = "C:", path = "C:\\")))
        runCurrent()
        assertEquals(listOf("C:"), nav.drives.map { it.name })
        assertEquals(2, nav.chips.size)
    }

    @Test
    fun anEngineChangeAlsoClearsTheQueryAndTypedState() = runTest {
        val host = FakeHost()
        val nav = navState(host)
        nav.onDevice(DEV, online = true)
        runCurrent()
        nav.onQueryChange("~/new-app")
        nav.onDevice("dev-2", online = true)
        runCurrent()
        assertEquals("", nav.query)
        assertEquals(TypedPath.Idle, nav.typed)
    }

    @Test
    fun aFailingDrivesLoadLeavesHomeOnlyWithNoErrorState() = runTest {
        val calls = FakeCalls().apply { driveError = CoreException.Unsupported("no ListDrives") }
        val nav = navState(calls = calls)
        nav.onDevice(DEV, online = true)
        runCurrent()
        assertEquals(emptyList<DriveEntry>(), nav.drives)
        assertEquals(1, nav.chips.size)
        assertEquals(TypedPath.Idle, nav.typed)
        assertNull(nav.statusOverride)
    }

    @Test
    fun anOfflineToOnlineFlipReloadsOnlyTheDrives() = runTest {
        val calls = FakeCalls()
        val nav = navState(calls = calls)
        nav.onDevice(DEV, online = false)
        runCurrent()
        assertEquals(listOf(DEV), calls.drivesCalls)
        // Staying offline: no second load.
        nav.onDevice(DEV, online = false)
        runCurrent()
        assertEquals(1, calls.drivesCalls.size)
        // Back online: the drives reload.
        nav.onDevice(DEV, online = true)
        runCurrent()
        assertEquals(listOf(DEV, DEV), calls.drivesCalls)
    }

    // ------------------------------------------------------------ the rail

    @Test
    fun pickingADriveChipBrowsesItsRootAndClearsTheQuery() = runTest {
        val host = FakeHost()
        val nav = navState(host)
        nav.onQueryChange("doc")
        nav.onPickLocation(LocationChipModel("data", "/mnt/data"))
        assertEquals(listOf(Triple("/mnt/data", false, null)), host.loads)
        assertEquals("", nav.query)
        assertEquals(TypedPath.Idle, nav.typed)
    }

    @Test
    fun pickingTheHomeChipBrowsesTheEngineHome() = runTest {
        val host = FakeHost()
        val nav = navState(host)
        nav.onPickLocation(LocationChipModel("Home", null))
        assertEquals(listOf(Triple<String?, Boolean, Boolean?>(null, false, null)), host.loads)
    }

    @Test
    fun chipTapsAndGoAreIgnoredWhileSubmitting() = runTest {
        val host = FakeHost().apply { submittingNow = true }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onPickLocation(LocationChipModel("data", "/mnt/data"))
        nav.onQueryChange("~/exists")
        nav.onGo()
        runCurrent()
        assertTrue(host.loads.isEmpty())
        assertTrue(calls.prepareCalls.isEmpty())
        assertTrue(calls.joins.isEmpty())
    }

    @Test
    fun theSelectedChipIsTheLongestMatchingRootOnTheCurrentEngineOnly() = runTest {
        val host = FakeHost().apply { home = "/home/wing" }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onDevice(DEV, online = true)
        runCurrent()
        calls.completeDrives(0, listOf(DriveEntry(name = "System", path = "/")))
        runCurrent()

        host.listing = listing("/home/wing/src")
        host.listingDeviceId = DEV
        assertEquals(0, nav.selectedChip) // Home

        host.listing = listing("/etc")
        assertEquals(1, nav.selectedChip) // System

        // A listing from another engine selects nothing.
        host.listingDeviceId = "dev-2"
        assertNull(nav.selectedChip)
    }

    // -------------------------------------------------------- the filter

    @Test
    fun plainTextFiltersRowsAndHidesTheParentRow() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true, "gitlab" to false, "notes" to false)
            listingDeviceId = DEV
        }
        val nav = navState(host)
        nav.onQueryChange("git")
        val rows = nav.rows(host.listing!!.entries, { it.name }, { it.isDir })
        assertEquals(listOf("github", "gitlab"), rows.folders.map { it.name })
        assertFalse(rows.parentAllowed)
        assertFalse(rows.noMatch)
    }

    @Test
    fun noSurvivingRowIsNoMatch() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true)
            listingDeviceId = DEV
        }
        val nav = navState(host)
        nav.onQueryChange("zzz")
        val rows = nav.rows(host.listing!!.entries, { it.name }, { it.isDir })
        assertTrue(rows.folders.isEmpty())
        assertTrue(rows.noMatch)
        assertFalse(rows.parentAllowed)
    }

    @Test
    fun pathShapedTextNeverFiltersAndSendsNothing() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true, ".config" to false)
            listingDeviceId = DEV
        }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("/tmp/x")
        val rows = nav.rows(host.listing!!.entries, { it.name }, { it.isDir })
        assertTrue(rows.parentAllowed)
        assertEquals(listOf("github"), rows.folders.map { it.name })
        assertFalse(rows.noMatch)
        assertTrue(host.loads.isEmpty())
        assertTrue(calls.prepareCalls.isEmpty())
    }

    @Test
    fun aDotQueryReloadsWithHiddenNamesAndDeletingItReloadsWithout() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true)
            listingDeviceId = DEV
        }
        val nav = navState(host)
        nav.onQueryChange(".gi")
        assertEquals(listOf(Triple("/home/wing", true, null)), host.loads)
        nav.onQueryChange("")
        assertEquals(
            listOf(Triple("/home/wing", true, null), Triple("/home/wing", false, null)),
            host.loads,
        )
    }

    @Test
    fun aDotQueryThenAPathShapedEditTriggersNoHiddenFlagReload() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true)
            listingDeviceId = DEV
        }
        val nav = navState(host)
        nav.onQueryChange(".")
        assertEquals(1, host.loads.size)
        nav.onQueryChange("/tmp")
        assertEquals("the path-shaped edit never toggles the flag", 1, host.loads.size)
    }

    @Test
    fun theHiddenReloadNeedsTheListingOnTheSelectedEngine() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true)
            listingDeviceId = "dev-2"
        }
        val nav = navState(host)
        nav.onQueryChange(".gi")
        assertTrue("a listing from another engine is not reloaded", host.loads.isEmpty())
    }

    // ------------------------------------------------------- IME Go

    @Test
    fun goOnABlankQueryDoesNothing() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onGo()
        runCurrent()
        assertTrue(host.loads.isEmpty())
        assertTrue(calls.prepareCalls.isEmpty())
        assertTrue(calls.joins.isEmpty())
    }

    @Test
    fun goOnPlainTextBrowsesTheFirstMatchingRow() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true, "gitlab" to false)
            listingDeviceId = DEV
        }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("git")
        nav.onGo()
        assertEquals(listOf("/home/wing" to "github"), calls.joins)
        assertEquals(listOf(Triple("/home/wing/github", false, true)), host.loads)
        assertEquals("", nav.query)
    }

    @Test
    fun goOnPlainTextWithNoSurvivingRowDoesNothing() = runTest {
        val host = FakeHost().apply {
            listing = listing("/home/wing", "github" to true)
            listingDeviceId = DEV
        }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("zzz")
        nav.onGo()
        assertTrue(calls.joins.isEmpty())
        assertTrue(host.loads.isEmpty())
    }

    // ---------------------------------------------------- typed paths

    @Test
    fun goOnATypedPathProbesWithoutCreating() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/exists")
        nav.onGo()
        runCurrent()
        assertEquals(listOf(Triple(DEV, "~/exists", false)), calls.prepareCalls)
        assertEquals(TypedPath.Probing, nav.typed)
        assertEquals(FolderBrowserCopy.Loading, nav.statusOverride)
        assertEquals(false, nav.useOverride?.enabled)
        assertNull(nav.useOverride?.title)

        calls.completePrepare(0, PreparedPath(path = "/home/wing/exists", exists = true, gitDetected = true))
        runCurrent()
        assertEquals(listOf(Triple("/home/wing/exists", false, true)), host.loads)
        assertEquals("", nav.query)
        assertEquals(TypedPath.Idle, nav.typed)
    }

    @Test
    fun goOnAMissingTypedPathTurnsUseIntoCreateAndAdd() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/new-app")
        nav.onGo()
        runCurrent()
        calls.completePrepare(0, PreparedPath(path = "/home/wing/new-app", exists = false, gitDetected = false))
        runCurrent()
        assertEquals(TypedPath.Missing("/home/wing/new-app", DEV), nav.typed)
        assertEquals("/home/wing/new-app", nav.statusOverride)
        assertEquals(FolderHeaderTokens.CreateAndAdd, nav.useOverride?.title)
        assertTrue(nav.useOverride!!.enabled)
        // Nothing was created, nothing browsed.
        assertTrue(host.loads.isEmpty())
        assertTrue(host.creates.isEmpty())
    }

    @Test
    fun createAndAddProbesWithCreateThenRunsTheCreateBody() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/new-app")
        nav.onGo()
        runCurrent()
        calls.completePrepare(0, PreparedPath(path = "/home/wing/new-app", exists = false, gitDetected = false))
        runCurrent()

        val tap = nav.useOverride?.onClick ?: error("Missing state must override the Use click")
        tap()
        runCurrent()
        // setSubmitting(true) first; the create probe sends the
        // NORMALIZED path with createIfMissing = true.
        assertEquals(listOf(true), host.submits)
        assertEquals(
            listOf(Triple(DEV, "~/new-app", false), Triple(DEV, "/home/wing/new-app", true)),
            calls.prepareCalls,
        )

        calls.completePrepare(1, PreparedPath(path = "/home/wing/new-app", exists = true, gitDetected = true))
        runCurrent()
        assertEquals(listOf("/home/wing/new-app" to true), host.creates)
        assertEquals(listOf(true, false), host.submits)
        assertEquals(TypedPath.Idle, nav.typed)
        assertEquals("", nav.query)
    }

    @Test
    fun createAndAddWithADisabledGuardDoesNothing() = runTest {
        val host = FakeHost().apply {
            listing = null
            loading = true
        }
        val calls = FakeCalls()
        val nav = navState(host, calls)
        // Sneak into Missing with loading set (the guard is the Use
        // button's own enabled rule, re-checked in createTyped).
        nav.onQueryChange("~/new-app")
        nav.onGo()
        runCurrent()
        calls.completePrepare(0, PreparedPath(path = "/home/wing/new-app", exists = false, gitDetected = false))
        runCurrent()
        host.loading = true
        assertFalse(nav.useOverride!!.enabled)
        nav.useOverride!!.onClick!!.invoke()
        runCurrent()
        assertEquals(1, calls.prepareCalls.size) // only the probe
        assertTrue(host.submits.isEmpty())
        // The same for submitting and a foreign device.
        host.loading = false
        host.submittingNow = true
        nav.useOverride!!.onClick!!.invoke()
        runCurrent()
        host.submittingNow = false
        host.deviceId = "dev-2"
        nav.useOverride!!.onClick!!.invoke()
        runCurrent()
        assertEquals(1, calls.prepareCalls.size)
        assertTrue(host.creates.isEmpty())
    }

    @Test
    fun aFailedCreateKeepsCreateAndAddWithTheEngineMessageForRetry() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/new-app")
        nav.onGo()
        runCurrent()
        calls.completePrepare(0, PreparedPath(path = "/home/wing/new-app", exists = false, gitDetected = false))
        runCurrent()
        nav.useOverride!!.onClick!!.invoke()
        runCurrent()
        calls.failPrepare(1, CoreException.HostException("Permission denied (os error 13)"))
        runCurrent()
        assertEquals(
            TypedPath.Missing("/home/wing/new-app", DEV, "Permission denied (os error 13)"),
            nav.typed,
        )
        assertEquals("Permission denied (os error 13)", nav.statusOverride)
        assertEquals(FolderHeaderTokens.CreateAndAdd, nav.useOverride?.title)
        assertTrue("retry allowed", nav.useOverride!!.enabled)
        assertEquals(listOf(true, false), host.submits)
        assertTrue(host.creates.isEmpty())
    }

    @Test
    fun aFailedProbeShowsTheEngineMessageVerbatimWithNoOverride() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("/etc/hostname")
        nav.onGo()
        runCurrent()
        calls.failPrepare(0, CoreException.HostException("the path is a file, not a folder"))
        runCurrent()
        assertEquals(TypedPath.Failed("the path is a file, not a folder"), nav.typed)
        assertEquals("the path is a file, not a folder", nav.statusOverride)
        assertNull(nav.useOverride)
    }

    @Test
    fun aNonCoreProbeFailureUsesTheReadFallback() = runTest {
        val calls = FakeCalls()
        val nav = navState(calls = calls)
        nav.onQueryChange("/tmp/x")
        nav.onGo()
        runCurrent()
        calls.failPrepare(0, IllegalStateException(null as String?))
        runCurrent()
        assertEquals(TypedPath.Failed(FolderBrowserCopy.ReadFailed), nav.typed)
    }

    @Test
    fun editingTheQueryAfterGoDropsTheProbeReply() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/exists")
        nav.onGo()
        runCurrent()
        nav.onQueryChange("~/other")
        assertEquals(TypedPath.Idle, nav.typed)
        calls.completePrepare(0, PreparedPath(path = "/home/wing/exists", exists = true, gitDetected = true))
        runCurrent()
        assertEquals(TypedPath.Idle, nav.typed)
        assertTrue(host.loads.isEmpty())
    }

    @Test
    fun switchingTheEngineMidProbeDropsTheReply() = runTest {
        val host = FakeHost()
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/exists")
        nav.onGo()
        runCurrent()
        host.deviceId = "dev-2"
        nav.onDevice("dev-2", online = true) // the screen's LaunchedEffect on the switch
        calls.completePrepare(0, PreparedPath(path = "/home/wing/exists", exists = true, gitDetected = true))
        runCurrent()
        assertEquals(TypedPath.Idle, nav.typed)
        assertTrue(host.loads.isEmpty())
    }

    @Test
    fun goWithNoEngineProbesNothing() = runTest {
        val host = FakeHost(engineId = null)
        val calls = FakeCalls()
        val nav = navState(host, calls)
        nav.onQueryChange("~/exists")
        nav.onGo()
        runCurrent()
        assertTrue(calls.prepareCalls.isEmpty())
    }
}

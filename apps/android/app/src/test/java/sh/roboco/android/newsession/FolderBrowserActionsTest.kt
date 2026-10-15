package sh.roboco.android.newsession

import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.FolderEntry
import uniffi.roboco_core.FolderListing
import uniffi.roboco_core.PreparedPath

/**
 * [FolderBrowserActions] (android-ui-parity #111): every acceptance row
 * of the ticket's write flows — the create ordering (`onCreated` before
 * the dismiss), the failure copy, the act guard (loading, submitting,
 * another engine's listing), the gitDetected seeds, 112's
 * [FolderBrowserActions.createProjectAt] contract, and the whole New
 * Folder flow (client validation, the probe/create/browse order, the
 * re-prompt content and the status/submitting at every step) — the port
 * of `pr685:apps/ios/ZeronUITests/SessionFlowTests.swift:292-332`
 * (`testNewProjectCreatesFolderAndRetriesInvalidName`) on the JVM.
 *
 * Robolectric, logic-style (like 110's controller test): the
 * `FolderListing`/`PreparedPath`/`CoreException` uniffi types are
 * record/exception classes whose construction never loads the native
 * core, `FolderBrowserController` needs a looper for its main-thread
 * assertion, and `android.util.Log` must not throw. `runTest` passes its
 * `backgroundScope` to both the controller and the actions, and the
 * [ScriptedWriteApi] gates every FFI call on its own
 * [CompletableDeferred], so each step's state is asserted mid-flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderBrowserActionsTest {

    /** The engine the sheet browses: `hetzner-01`. */
    private val engine = HostOption("dev-htz", "hetzner-01", online = true)

    /** A second, default-picked engine (its listing, then a failed switch). */
    private val other = HostOption("dev-mbp", "MacBook Pro", online = true)

    // ------------------------------------------------------------- the fakes

    /** A write API that records every call and gates each on a deferred answer. */
    private class ScriptedWriteApi : ProjectWriteApi {
        val calls = mutableListOf<String>()
        private val gates = mutableListOf<CompletableDeferred<Any>>()

        /** Completes call [index] with a result, or a throwable to throw. */
        fun answer(index: Int, value: Any) = synchronized(gates) { gates[index] }.complete(value)

        private suspend fun await(op: String): Any {
            calls += op
            val gate = CompletableDeferred<Any>()
            synchronized(gates) { gates += gate }
            val value = gate.await()
            if (value is Throwable) throw value
            return value
        }

        override suspend fun createProject(deviceId: String, path: String, gitDetected: Boolean): String =
            await("createProject $deviceId $path git=$gitDetected") as String

        override suspend fun prepareSpacePath(
            deviceId: String,
            path: String,
            createIfMissing: Boolean,
        ): PreparedPath = await("prepare $deviceId create=$createIfMissing $path") as PreparedPath
    }

    /** The alerts the flow shows, captured. */
    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }

        override fun updateCurrent(alert: RobocoAlert) {}

        override fun dismissAll() {}
    }

    /** A source that echoes the asked path (or hangs / fails on demand). */
    private class EchoSource : FolderBrowserSource {
        var listingFor: (String?) -> FolderListing? = { path -> listing(path ?: "/srv") }
        var hang = false
        val calls = mutableListOf<Pair<String, String?>>()

        override suspend fun listFolders(
            deviceId: String,
            path: String?,
            showHidden: Boolean,
        ): FolderListing? {
            calls += deviceId to path
            if (hang) kotlinx.coroutines.awaitCancellation()
            return listingFor(path)
        }
    }

    /** The harness under test: everything a test needs, one line to build. */
    private class Harness(
        val controller: FolderBrowserController,
        val actions: FolderBrowserActions,
        val api: ScriptedWriteApi,
        val alerts: RecordingAlerts,
        val source: EchoSource,
        val order: MutableList<String>,
    )

    /** A settled browser on `/srv` (the listing already applied) plus its actions. */
    private fun TestScope.harness(
        hosts: List<HostOption> = listOf(engine),
        source: EchoSource = EchoSource(),
    ): Harness {
        val controller = FolderBrowserController(source, FakeHostPaths, backgroundScope, initialHosts = hosts)
        val api = ScriptedWriteApi()
        val alerts = RecordingAlerts()
        val order = mutableListOf<String>()
        val actions = FolderBrowserActions(
            controller,
            FakeHostPaths,
            api,
            alerts,
            backgroundScope,
            onCreated = { id -> order += "onCreated $id" },
            dismissSheet = { order += "dismiss" },
        )
        controller.updateEngines(hosts)
        runCurrent()
        return Harness(controller, actions, api, alerts, source, order)
    }

    /** Drives the alert's Create button with the typed text. */
    private fun RobocoAlert.createWith(text: String) {
        buttons.last().onClick(text)
    }

    // ------------------------------------------------------ the Use action

    @Test
    fun useCreatesTheProjectAndCallsOnCreatedBeforeTheDismiss() = runTest {
        val h = harness()
        h.actions.createProject()
        runCurrent()
        assertEquals(listOf("createProject dev-htz /srv git=false"), h.api.calls)
        assertTrue("submitting while the create runs", h.controller.submitting)
        assertTrue(h.actions.creatingProject)

        h.api.answer(0, "space-1")
        runCurrent()
        // iOS :248-251: the draft updates first, then the sheet dismisses.
        assertEquals(listOf("onCreated space-1", "dismiss"), h.order)
        assertFalse(h.controller.submitting)
        assertFalse(h.actions.creatingProject)
        // The status line still shows the listing path on success.
        assertEquals("/srv", h.controller.status)
    }

    @Test
    fun aFailedCreateShowsTheSingleStatusLineAndNeverDismisses() = runTest {
        val h = harness()
        h.actions.createProject()
        runCurrent()
        h.api.answer(0, RuntimeException("boom"))
        runCurrent()
        assertEquals("Couldn't create the project on hetzner-01.", h.controller.status)
        assertTrue("no onCreated, no dismiss", h.order.isEmpty())
        assertFalse(h.controller.submitting)
        // A second tap is allowed again and retries.
        h.actions.createProject()
        runCurrent()
        assertEquals(2, h.api.calls.size)
        h.api.answer(1, "space-2")
        runCurrent()
        assertEquals(listOf("onCreated space-2", "dismiss"), h.order)
    }

    @Test
    fun useIsGuardedWhileALoadRuns() = runTest {
        val h = harness()
        h.source.hang = true
        h.controller.load("/srv/elsewhere")
        assertTrue(h.controller.loading)
        h.actions.createProject()
        h.actions.promptForFolder()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
        assertTrue(h.alerts.shown.isEmpty())
    }

    @Test
    fun useIsGuardedWhileSubmitting() = runTest {
        val h = harness()
        h.controller.submitting = true
        h.actions.createProject()
        h.actions.promptForFolder()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
        assertTrue(h.alerts.shown.isEmpty())
    }

    @Test
    fun useIsGuardedWhenTheListingCameFromAnotherEngine() = runTest {
        // The default pick is dev-mbp; its listing applies, then the switch
        // to hetzner-01 fails, so the old rows stay but are not usable.
        val h = harness(hosts = listOf(other, engine))
        assertEquals("dev-mbp", h.controller.listingEngineId)
        h.source.listingFor = { null }
        h.controller.selectEngine(engine.id)
        runCurrent()
        // The switch failed (dev-htz is online, so the copy is ReadFailed):
        // the old rows stay but are not usable.
        assertEquals(FolderBrowserCopy.ReadFailed, h.controller.status)
        h.actions.createProject()
        h.actions.promptForFolder()
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
        assertTrue(h.alerts.shown.isEmpty())
    }

    @Test
    fun gitDetectedIsSeededFromTheRepoHintAndTheDotGitEntry() = runTest {
        val h = harness()

        // Home: hint null and no .git entry → false.
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv git=false", h.api.calls[0])
        h.api.answer(0, "space-1")
        runCurrent()

        // Descended from a repo row (110's hint) → true.
        h.controller.load("/srv/roboco", isRepoHint = true)
        runCurrent()
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv/roboco git=true", h.api.calls[1])
        h.api.answer(1, "space-2")
        runCurrent()

        // A listing that carries its own .git entry → true, hint or not.
        h.source.listingFor = { path ->
            listing(path ?: "/srv/dots", entries = listOf(FolderEntry(name = ".git", isDir = true, isRepo = false)))
        }
        h.controller.load("/srv/dots")
        runCurrent()
        h.actions.createProject()
        runCurrent()
        assertEquals("createProject dev-htz /srv/dots git=true", h.api.calls[2])
        h.api.answer(2, "space-3")
    }

    @Test
    fun createProjectAtLeavesSubmittingUntouchedAndReportsTheResult() = runTest {
        val h = harness()
        h.controller.submitting = true // 112 brackets its own call
        var created = false
        val job = launch { created = h.actions.createProjectAt("/srv", gitDetected = false) }
        runCurrent()
        assertTrue(h.controller.submitting)
        h.api.answer(0, "space-9")
        runCurrent()
        job.join()
        assertTrue(created)
        assertTrue("still untouched after success", h.controller.submitting)
        assertEquals(listOf("onCreated space-9", "dismiss"), h.order)

        var retryCreated = true
        val retry = launch { retryCreated = h.actions.createProjectAt("/srv", gitDetected = false) }
        runCurrent()
        h.api.answer(1, CoreException.HostUnavailable("Engine is offline; reconnecting"))
        runCurrent()
        retry.join()
        assertFalse(retryCreated)
        assertTrue(h.controller.submitting)
        assertEquals("Couldn't create the project on hetzner-01.", h.controller.status)
    }

    // ------------------------------------------------------ the Use title

    @Test
    fun theUseTitleQuotesTheLastPathComponent() = runTest {
        val h = harness()
        assertEquals("Use \u201Csrv\u201D", h.actions.useTitle)
        h.controller.load("C:\\")
        runCurrent()
        assertEquals("Use \u201CC:\\\u201D", h.actions.useTitle)
        h.controller.load("/")
        runCurrent()
        assertEquals("Use \u201C/\u201D", h.actions.useTitle)
        h.controller.load("/home/dev/My App")
        runCurrent()
        assertEquals("Use \u201CMy App\u201D", h.actions.useTitle)
        // A later load keeps the last title until it applies.
        h.source.hang = true
        h.controller.load("/srv/loading")
        assertEquals("Use \u201CMy App\u201D", h.actions.useTitle)
    }

    @Test
    fun theUseTitleIsEmptyBeforeTheFirstListing() = runTest {
        val h = harness(source = EchoSource().apply { hang = true })
        assertTrue(h.controller.loading)
        assertEquals("", h.actions.useTitle)
    }

    // -------------------------------------------------- the New Folder flow

    @Test
    fun thePromptShowsTheAlertContent() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        val alert = h.alerts.shown.single()
        assertEquals("New Folder", alert.title)
        assertEquals("Create a folder in /srv on hetzner-01.", alert.message)
        val field = alert.field!!
        assertEquals("", field.text)
        assertEquals("Folder name", field.placeholder)
        assertEquals(KeyboardCapitalization.None, field.capitalization)
        assertFalse(field.autocorrect)
        val cancel = alert.buttons.first()
        val create = alert.buttons.last()
        assertEquals("Cancel", cancel.title)
        assertEquals(AlertButtonRole.Cancel, cancel.role)
        assertFalse(cancel.requiresText)
        assertEquals("Create", create.title)
        assertEquals(AlertButtonRole.Default, create.role)
        assertTrue("Create is disabled while the field is blank", create.requiresText)
    }

    @Test
    fun anInvalidNameRePromptsClientSideWithoutAnyRpc() = runTest {
        for (name in listOf("../outside", ".", "..", "a\\b", "a\u0000b")) {
            val h = harness()
            h.actions.promptForFolder()
            h.alerts.shown.single().createWith(name)
            // Fully synchronous: no state change, no coroutine, one retry alert.
            assertEquals("the field keeps the name ($name)", 2, h.alerts.shown.size)
            val retry = h.alerts.shown[1]
            assertEquals(FolderNameRules.INVALID, retry.message)
            assertEquals(name, retry.field!!.text)
            assertTrue(h.api.calls.isEmpty())
            assertEquals("/srv", h.controller.status)
            assertFalse(h.controller.submitting)
        }
    }

    @Test
    fun aValidNameIsTrimmedJoinedProbedAndCreatedThenBrowsedInto() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.alerts.shown.single().createWith("  My new project  ")
        runCurrent()
        assertEquals("Creating folder…", h.controller.status)
        assertTrue(h.controller.submitting)
        assertEquals(listOf("prepare dev-htz create=false /srv/My new project"), h.api.calls)

        h.api.answer(0, PreparedPath(path = "/srv/My new project", exists = false, gitDetected = false))
        runCurrent()
        assertEquals(
            listOf(
                "prepare dev-htz create=false /srv/My new project",
                "prepare dev-htz create=true /srv/My new project",
            ),
            h.api.calls,
        )
        assertTrue("still submitting during the create", h.controller.submitting)

        h.api.answer(1, PreparedPath(path = "/srv/My new project", exists = true, gitDetected = true))
        runCurrent()
        assertFalse(h.controller.submitting)
        // 110's load took over: the new folder, with the row's git hint.
        assertEquals("/srv/My new project", h.controller.path)
        assertEquals(true, h.controller.listingIsRepo)
        assertEquals("/srv/My new project", h.controller.status)
        assertEquals("Use \u201CMy new project\u201D", h.actions.useTitle)
        assertEquals("My new project", h.controller.title)
    }

    @Test
    fun anExistingNameRePromptsAndNeverCreates() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.alerts.shown.single().createWith("dup")
        runCurrent()
        h.api.answer(0, PreparedPath(path = "/srv/dup", exists = true, gitDetected = false))
        runCurrent()
        assertEquals(1, h.api.calls.size)
        assertFalse(h.controller.submitting)
        assertEquals("the status returns to the listing path", "/srv", h.controller.status)
        val retry = h.alerts.shown[1]
        assertEquals("Couldn't create the folder. A file or folder with that name already exists.", retry.message)
        assertEquals("dup", retry.field!!.text)
    }

    @Test
    fun aFileWithTheFolderNameMapsToTheAlreadyExistsCopy() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.alerts.shown.single().createWith("dup")
        runCurrent()
        h.api.answer(0, CoreException.HostException("the path is a file, not a folder"))
        runCurrent()
        assertEquals(1, h.api.calls.size)
        assertEquals("/srv", h.controller.status)
        assertEquals(
            "Couldn't create the folder. A file or folder with that name already exists.",
            h.alerts.shown[1].message,
        )
        assertEquals("dup", h.alerts.shown[1].field!!.text)
    }

    @Test
    fun anOfflineEngineShowsItsMessageAfterThePrefix() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.alerts.shown.single().createWith("new one")
        runCurrent()
        h.api.answer(0, CoreException.HostUnavailable("Engine is offline; reconnecting"))
        runCurrent()
        assertEquals("the status returns to the listing path", "/srv", h.controller.status)
        assertFalse(h.controller.submitting)
        assertEquals(
            "Couldn't create the folder. Engine is offline; reconnecting",
            h.alerts.shown[1].message,
        )
        assertEquals("new one", h.alerts.shown[1].field!!.text)
    }

    @Test
    fun folderErrorMapsEveryEngineErrorFamily() = runTest {
        val h = harness()
        assertEquals(
            "Couldn't create the folder. Roboco is disconnected.",
            h.actions.folderError(CoreException.Closed()),
        )
        assertEquals(
            "Couldn't create the folder. Permission denied (os error 13)",
            h.actions.folderError(CoreException.HostException("Permission denied (os error 13)")),
        )
        assertEquals(
            "Couldn't create the folder. A file or folder with that name already exists.",
            h.actions.folderError(CoreException.HostException("the path is a file, not a folder")),
        )
        // CoreProjectWriteApi's no-client throw maps through the platform message.
        assertEquals(
            "Couldn't create the folder. Roboco is disconnected.",
            h.actions.folderError(IllegalStateException("Roboco is disconnected.")),
        )
    }

    // ------------------------------------------- the submit re-check

    @Test
    fun theCreateIsDroppedWhenTheFolderMovedWhileTheAlertWasUp() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.controller.load("/srv/elsewhere")
        runCurrent()
        h.alerts.shown.single().createWith("new one")
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
        assertFalse(h.controller.submitting)
    }

    @Test
    fun theCreateIsDroppedWhenTheEngineMovedWhileTheAlertWasUp() = runTest {
        val h = harness(hosts = listOf(other, engine))
        h.actions.promptForFolder()
        h.source.listingFor = { null }
        h.controller.selectEngine(engine.id)
        runCurrent()
        h.alerts.shown.single().createWith("new one")
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
        assertFalse(h.controller.submitting)
    }

    @Test
    fun theCreateIsDroppedWhenAWriteStartedWhileTheAlertWasUp() = runTest {
        val h = harness()
        h.actions.promptForFolder()
        h.controller.submitting = true
        h.alerts.shown.single().createWith("new one")
        runCurrent()
        assertTrue(h.api.calls.isEmpty())
    }

    // -------------------------------------------------------------- fixture

    /** The default listing: two plain folders at [path]. */
    private companion object {
        fun listing(path: String, entries: List<FolderEntry> = defaultEntries()): FolderListing =
            FolderListing(path = path, entries = entries, truncated = false)

        fun defaultEntries(): List<FolderEntry> = listOf(
            FolderEntry(name = "Documents", isDir = true, isRepo = false),
            FolderEntry(name = "Projects", isDir = true, isRepo = false),
        )
    }
}

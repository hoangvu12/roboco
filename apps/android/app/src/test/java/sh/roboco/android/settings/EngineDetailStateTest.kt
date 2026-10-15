package sh.roboco.android.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.Toaster
import sh.roboco.android.pairing.CredentialStoreException
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.ParkReason
import java.io.IOException

/**
 * The Engine detail page's behaviour (android-ui-parity #140) on the
 * plain JVM: [EngineDetailState] over a recording
 * [EngineDetailBackend] fake — the Copied 1.5 s timer (restarted by a
 * second tap), the Rename alert's structure and the "dialog closes
 * first" submit rule with the error table, and the whole forget
 * sequence behind 19's action sheet (the revoke cap, the order, the
 * failure paths, the one-pop guard, the double-tap guard, the 84/92
 * drafts hook). `runTest` + `StandardTestDispatcher` for `uiScope`,
 * `workScope` and `io`, so the timers and the 3 s revoke cap run on
 * virtual time.
 *
 * The ticket's `HostError` is the UniFFI `CoreException.HostException`
 * (the reason-carrying engine-answer variant); the table is the same.
 */
class EngineDetailStateTest {

    // ------------------------------------------------------------ the fakes

    /** Records every call in order; knobs for the failure paths. */
    private class FakeEngineDetailBackend : EngineDetailBackend {
        val calls = mutableListOf<String>()
        var revokeHangs = false
        var revokeThrows: Throwable? = null
        var renameThrows: Throwable? = null
        var forgetThrows: Throwable? = null
        var removeThrows: Throwable? = null

        override suspend fun rename(engineId: String, name: String) {
            calls += "rename:$name"
            renameThrows?.let { throw it }
        }

        override suspend fun revokeOwnSession(engineId: String) {
            calls += "revoke"
            revokeThrows?.let { throw it }
            if (revokeHangs) awaitCancellation()
        }

        override suspend fun forget(engineId: String) {
            calls += "forget"
            forgetThrows?.let { throw it }
        }

        override suspend fun removeStored(engineId: String) {
            calls += "remove"
            removeThrows?.let { throw it }
        }
    }

    private class FakeToaster : Toaster {
        val toasts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            toasts += text
        }
    }

    private class FakeAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }

        override fun updateCurrent(alert: RobocoAlert) {}

        override fun dismissAll() {}
    }

    private class FakeSheets : ActionSheetPresenter {
        val shown = mutableListOf<ActionSheetModel>()
        override fun show(model: ActionSheetModel) {
            shown += model
        }
    }

    // --------------------------------------------------------- the fixture

    private val backend = FakeEngineDetailBackend()
    private val toaster = FakeToaster()
    private val alerts = FakeAlerts()
    private val sheets = FakeSheets()
    private val pops = mutableListOf<Unit>()
    private val drafted = mutableListOf<Pair<String, ForgetDraftsContext>>()
    private val forgottenNotifications = mutableListOf<String>()
    private var contextReads = 0
    private var callsAtContextRead: List<String>? = null

    private val connectedView = engineView(id = "e1", name = "MacBook Pro", state = EngineState.Connected)
    private val reconnectingView = engineView(id = "e1", name = "MacBook Pro", state = EngineState.Reconnecting)
    private val revokedView = engineView(
        id = "e1",
        name = "MacBook Pro",
        state = EngineState.Parked(ParkReason.REVOKED),
        lastError = "invalid credential",
    )

    @After
    fun restoreLog() {
        engineDetailLogW = { message -> android.util.Log.w("EngineDetail", message) }
    }

    /** A state over the fakes; the log seam is silenced for the plain JVM. */
    private fun TestScope.state(engineId: String = "e1"): EngineDetailState {
        engineDetailLogW = {}
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EngineDetailState(
            engineId = engineId,
            backend = backend,
            uiScope = CoroutineScope(SupervisorJob() + dispatcher),
            workScope = CoroutineScope(SupervisorJob() + dispatcher),
            toaster = toaster,
            alerts = alerts,
            sheets = sheets,
            requestPop = { pops += Unit },
            forgetDrafts = ForgetDrafts { id, context -> drafted += id to context },
            onEngineForgotten = { id -> forgottenNotifications += id },
            forgetContext = {
                contextReads++
                callsAtContextRead = backend.calls.toList()
                ForgetDraftsContext(snapshot = null, enginesLeft = 2)
            },
            io = dispatcher,
        )
    }

    /** The sheet's Forget action, as 19 runs it (after the dismissal). */
    private fun selectForget() {
        sheets.shown.last().actions.single().onSelect()
    }

    // -------------------------------------------------------- the copy timer

    @Test
    fun copyingTheDeviceIdCopiesTheRawIdHoldsCopiedAndFiresTheHaptic() = runTest {
        val page = state()
        val copiedTexts = mutableListOf<String>()
        val haptics = mutableListOf<Unit>()
        page.onCopyId(
            "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95",
            copy = { copiedTexts += it },
            haptic = { haptics += Unit },
        )
        assertEquals(listOf("3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95"), copiedTexts)
        assertEquals("one light haptic", 1, haptics.size)
        assertTrue("Copied at once", page.copied)
    }

    @Test
    fun copiedHoldsFor1500msExactly() = runTest {
        val page = state()
        page.onCopyId("device-1", copy = {}, haptic = {})
        advanceTimeBy(1_499)
        runCurrent()
        assertTrue("still Copied at +1499ms", page.copied)
        advanceTimeBy(1)
        runCurrent()
        assertFalse("the short id again at +1500ms", page.copied)
    }

    @Test
    fun aSecondTapRestartsTheHold() = runTest {
        val page = state()
        page.onCopyId("device-1", copy = {}, haptic = {})
        advanceTimeBy(1_000)
        runCurrent()
        page.onCopyId("device-1", copy = {}, haptic = {})
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("the first timer was cleared: still Copied at +2000ms", page.copied)
        advanceTimeBy(500)
        runCurrent()
        assertFalse("the restarted hold ends at +2500ms", page.copied)
    }

    // ------------------------------------------------------------ the rename

    @Test
    fun renameWhileNotConnectedToastsAndNeverOpensTheAlert() = runTest {
        val page = state()
        page.onRename(reconnectingView)
        assertEquals(listOf("Engine not connected"), toaster.toasts)
        assertTrue("no alert", alerts.shown.isEmpty())
        advanceUntilIdle()
        assertTrue("no FFI", backend.calls.isEmpty())
    }

    @Test
    fun renameShowsTicket18sAlertWithTheCurrentNamePrefilled() = runTest {
        val page = state()
        page.onRename(connectedView)
        val alert = alerts.shown.single()
        assertEquals("Rename device", alert.title)
        assertEquals(null, alert.message)
        val field = alert.field!!
        assertEquals("MacBook Pro", field.text)
        assertEquals("Device name", field.placeholder)
        assertTrue("autocorrect on by default", field.autocorrect)
        assertEquals(2, alert.buttons.size)
        assertEquals("Cancel", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[0].role)
        assertEquals("Rename", alert.buttons[1].title)
        assertEquals(AlertButtonRole.Default, alert.buttons[1].role)
        assertFalse("requiresText stays off", alert.buttons[1].requiresText)
    }

    @Test
    fun theRenameButtonTrimsAndSubmitsOnce() = runTest {
        val page = state()
        page.onRename(connectedView)
        page.submitRename("  New name ")
        advanceUntilIdle()
        assertEquals(listOf("rename:New name"), backend.calls)
        assertTrue("success stays silent", toaster.toasts.isEmpty())
    }

    @Test
    fun aBlankRenameIsSilentlyIgnored() = runTest {
        val page = state()
        page.submitRename("   ")
        advanceUntilIdle()
        assertTrue("no FFI call", backend.calls.isEmpty())
        assertTrue("no toast", toaster.toasts.isEmpty())
    }

    @Test
    fun theCancelButtonCallsNothing() = runTest {
        val page = state()
        page.onRename(connectedView)
        val alert = alerts.shown.single()
        alert.buttons[0].onClick("MacBook Pro")
        advanceUntilIdle()
        assertTrue("no FFI call", backend.calls.isEmpty())
    }

    @Test
    fun theRenameErrorTableMapsEveryCase() = runTest {
        assertEquals("Engine not connected", renameErrorText(CoreException.HostUnavailable("x")))
        assertEquals("Rename failed: device not found", renameErrorText(CoreException.HostException("device not found")))
        assertEquals("Rename failed: Closed", renameErrorText(CoreException.Closed()))
        assertEquals("Rename failed: boom", renameErrorText(IllegalStateException("boom")))
        assertEquals("Rename failed: RuntimeException", renameErrorText(RuntimeException()))
        assertEquals(
            "Rename failed: NotFound",
            renameErrorText(CoreException.NotFound("  ")),
        )
        assertEquals(
            "Rename failed: HostException",
            renameErrorText(CoreException.HostException("   ")),
        )
    }

    @Test
    fun aFailedRenameToastsTheTableText() = runTest {
        backend.renameThrows = CoreException.HostException("device not found")
        val page = state()
        page.submitRename("New name")
        advanceUntilIdle()
        assertEquals(listOf("rename:New name"), backend.calls)
        assertEquals(listOf("Rename failed: device not found"), toaster.toasts)
    }

    // ------------------------------------------------------------- the forget

    @Test
    fun theForgetTapShowsExactlyOneConfirmSheetAndNothingElse() = runTest {
        val page = state()
        page.onForget(connectedView)
        assertEquals(1, sheets.shown.size)
        val sheet = sheets.shown.single()
        assertEquals("Forget MacBook Pro?", sheet.title)
        assertEquals(null, sheet.message)
        assertEquals(1, sheet.actions.size)
        val action = sheet.actions.single()
        assertEquals("Forget", action.title)
        assertEquals(ActionSheetStyle.Destructive, action.style)
        assertEquals("Cancel", sheet.cancelTitle)
        assertEquals(ForgetPhase.Idle, page.forgetPhase)
        assertTrue("no backend call yet", backend.calls.isEmpty())
        assertTrue("no pop", pops.isEmpty())
    }

    @Test
    fun cancellingTheSheetRunsNoSequenceAndATapShowsItAgain() = runTest {
        val page = state()
        page.onForget(connectedView)
        sheets.shown.single().onCancel()
        advanceUntilIdle()
        assertTrue("no FFI", backend.calls.isEmpty())
        assertEquals(ForgetPhase.Idle, page.forgetPhase)
        page.onForget(connectedView)
        assertEquals("the sheet shows again", 2, sheets.shown.size)
    }

    @Test
    fun forgettingAConnectedEngineRevokesFirstThenForgetsThenRemoves() = runTest {
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals("one pop", 1, pops.size)
        assertEquals(ForgetPhase.Forgetting, page.forgetPhase)
    }

    @Test
    fun forgettingAParkedOrOfflineEngineSkipsTheRevoke() = runTest {
        for (view in listOf(reconnectingView, revokedView)) {
            backend.calls.clear()
            pops.clear()
            val page = state()
            page.onForget(view)
            selectForget()
            advanceUntilIdle()
            assertEquals(listOf("forget", "remove"), backend.calls)
            assertEquals(1, pops.size)
        }
    }

    @Test
    fun theDraftsHookRunsAfterRemoveStoredWithThePreForgetContext() = runTest {
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("e1" to ForgetDraftsContext(snapshot = null, enginesLeft = 2)), drafted)
        assertEquals("the context is read once", 1, contextReads)
        assertEquals("read before any FFI call", emptyList<String>(), callsAtContextRead)
    }

    /**
     * /code-review F6 (ticket 28: "all of an engine's notifications when
     * that engine is forgotten"): a successful forget clears them once; a
     * failed forget leaves them.
     */
    @Test
    fun aSuccessfulForgetClearsTheEnginesNotifications() = runTest {
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("e1"), forgottenNotifications)
    }

    @Test
    fun aFailedForgetLeavesTheEnginesNotifications() = runTest {
        backend.forgetThrows = CoreException.HostException("engine refused")
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertTrue("nothing cancelled", forgottenNotifications.isEmpty())
    }

    @Test
    fun aHangingRevokeIsCappedAtThreeSeconds() = runTest {
        backend.revokeHangs = true
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals("still waiting at +2999ms", listOf("revoke"), backend.calls)
        assertEquals("no pop yet", 0, pops.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals(1, pops.size)
    }

    @Test
    fun aThrownRevokeIsIgnoredAndForgetRunsAtOnce() = runTest {
        backend.revokeThrows = IOException("engine gone")
        val page = state()
        page.onForget(connectedView)
        selectForget()
        runCurrent()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals(1, pops.size)
        assertTrue("no toast for the revoke", toaster.toasts.isEmpty())
    }

    @Test
    fun aFailedForgetToastsResetsThePhaseAndStops() = runTest {
        backend.forgetThrows = CoreException.HostException("engine refused")
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("revoke", "forget"), backend.calls)
        assertEquals(listOf("Could not forget engine"), toaster.toasts)
        assertEquals(ForgetPhase.Idle, page.forgetPhase)
        assertTrue("no pop", pops.isEmpty())
        assertTrue("the drafts hook never ran", drafted.isEmpty())
    }

    @Test
    fun aFailingRemoveStoredIsLoggedOnlyAndStillPops() = runTest {
        backend.removeThrows = CredentialStoreException(IOException("disk"))
        val page = state()
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals("no toast for the store", 0, toaster.toasts.size)
        assertEquals("still one pop", 1, pops.size)
    }

    @Test
    fun tapsDuringForgettingAreIgnored() = runTest {
        backend.revokeHangs = true
        val page = state()
        page.onForget(connectedView)
        selectForget()
        runCurrent()
        assertEquals(ForgetPhase.Forgetting, page.forgetPhase)
        // A new tap shows no sheet; re-selecting the old sheet's action
        // starts no second sequence.
        page.onForget(connectedView)
        assertEquals("no second sheet", 1, sheets.shown.size)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals(1, pops.size)
    }

    @Test
    fun aBlankEngineNameFallsBackToTheHostInTheSheetTitle() = runTest {
        val page = state()
        page.onForget(engineView(id = "e1", name = "  ", state = EngineState.Connected, host = "10.0.0.7:27655"))
        assertEquals("Forget 10.0.0.7:27655?", sheets.shown.single().title)
    }

    @Test
    fun theRenameErrorsDoNotInterruptTheForgetFlow() = runTest {
        // The two flows are independent: a rename failure leaves forget
        // fully armed (the ticket's scopes rule).
        backend.renameThrows = CoreException.HostUnavailable("down")
        val page = state()
        page.submitRename("New name")
        advanceUntilIdle()
        assertEquals(listOf("Engine not connected"), toaster.toasts)
        page.onForget(connectedView)
        selectForget()
        advanceUntilIdle()
        assertEquals(listOf("rename:New name", "revoke", "forget", "remove"), backend.calls)
        assertEquals(1, pops.size)
    }
}

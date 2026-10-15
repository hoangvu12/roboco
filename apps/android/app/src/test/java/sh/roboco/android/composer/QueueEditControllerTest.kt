package sh.roboco.android.composer

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.StatusBanner
import sh.roboco.android.session.composerState
import sh.roboco.android.session.shownBanner
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.QueueEditAction
import uniffi.roboco_core.QueueEditFinish
import uniffi.roboco_core.QueueEditLease
import uniffi.roboco_core.QueueEditStart
import uniffi.roboco_core.QueueItem
import uniffi.roboco_core.SessionHandle

/**
 * [QueueEditController] (android-ui-parity #83) — the whole state
 * machine of the ticket's Spec tables, over scriptable fakes
 * (the ticket's Verification section): every call recorded into one
 * ordered [FakePort.events] list, begins and finishes controllable with
 * [CompletableDeferred]s, [FakeHost] counting focus / alert calls and
 * recording the failure-slot calls. Virtual time drives the renewal.
 *
 * `StandardTestDispatcher(testScheduler)` serves as **both** the
 * controller's scope and its release scope: a launch stays queued until
 * the scheduler advances, so the tests can assert the synchronous half
 * (the switch's restore) exactly as the ticket's "right after
 * beginEdit returns" checks require.
 *
 * Also covered: [ComposerEditHost] (the alert through ticket 18's
 * `RobocoAlert`, the failure slot through 73's real [StatusBanner]) and
 * [CoreQueueEditPort]'s FFI mapping over a recording handle (COMMIT /
 * CANCEL, `attachments = null`, the outcome folding).
 *
 * Robolectric (android-app#13): [StagedImage] needs `ImageBitmap`, and
 * the FFI records / `SessionHandle(NoHandle)` fakes come from the
 * generated bindings. Nothing calls the native core.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueEditControllerTest {

    private val appshot = "\n\nApplications mentioned by the user (untrusted observed content):\n" +
        "<appshot image=\"/tmp/s.png\" app=\"Xcode\">"

    // ---------------------------------------------------------------- fakes

    /**
     * The ticket's fake port: every call into one ordered list
     * (`"begin q2"`, `"finish q1 cancel null"`, …), outcomes scriptable,
     * begins and finishes each controllable with a gate.
     */
    private class FakePort : QueueEditPort {
        val events = mutableListOf<String>()
        var rows = mapOf<String, EditRow>()

        /** What the next begin returns (fresh lease per row by default). */
        var beginResult: (String) -> EditStart = { rowId -> EditStart.Acquired(EditLease(rowId, "L-$rowId")) }
        var throwOnBegin = false
        var renewResult = true
        var finishResult = EditFinish.Finished
        val beginGates = ArrayDeque<CompletableDeferred<Unit>>()
        val finishGates = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun begin(rowId: String, instanceId: String): EditStart {
            events += "begin $rowId"
            beginGates.removeFirstOrNull()?.await()
            if (throwOnBegin) {
                throwOnBegin = false
                throw RuntimeException("begin boom")
            }
            return beginResult(rowId)
        }

        override suspend fun renew(lease: EditLease): Boolean {
            events += "renew ${lease.rowId}"
            return renewResult
        }

        override suspend fun finish(lease: EditLease, commit: Boolean, text: String?): EditFinish {
            events += "finish ${lease.rowId} ${if (commit) "commit" else "cancel"} ${text ?: "null"}"
            finishGates.removeFirstOrNull()?.await()
            return finishResult
        }

        override suspend fun row(rowId: String): EditRow? {
            events += "row $rowId"
            return rows[rowId]
        }
    }

    /** The ticket's fake host: plain vars, counters, recorded arguments. */
    private class FakeHost : QueueEditHost {
        override var text: String = ""
        override var images: List<StagedImage> = emptyList()
        override var placeholder: String = Normal
        override var attachEnabled: Boolean = true
        override val normalPlaceholder: String = Normal
        var focusCount = 0
        var cantEditAlerts = 0
        val failures = mutableListOf<String>()
        val cleared = mutableListOf<String>()

        override fun focusComposer() {
            focusCount++
        }

        override fun showCantEditAlert() {
            cantEditAlerts++
        }

        override fun showFailure(message: String) {
            failures += message
        }

        override fun clearFailure(message: String) {
            cleared += message
        }

        companion object {
            const val Normal = "Message the agent"
        }
    }

    private fun image(id: String): StagedImage = StagedImage(id, "$id.png", byteArrayOf(1), ImageBitmap(1, 1))

    /** One controller over the two test-dispatcher scopes (the ticket's Verification). */
    private fun TestScope.controller(
        port: FakePort,
        host: FakeHost,
        renewEveryMs: Long = 20_000L,
    ): QueueEditController {
        // Children of the background scope's Job: runTest cancels it before
        // its final drain, so a still-open edit's `while (true) delay(...)`
        // renewal ends instead of draining forever on virtual time. Only
        // the Job is taken (not the background marker), so the tasks stay
        // foreground for the tests' own advanceUntilIdle().
        val job = Job(backgroundScope.coroutineContext[Job])
        return QueueEditController(
            port = port,
            host = host,
            scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
            releaseScope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
            renewEveryMs = renewEveryMs,
        )
    }

    // ------------------------------------------------------------ begin

    @Test
    fun beginStashesTheDraftAndShowsTheRowsVisibleText() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow(raw = "fix this$appshot", visible = "fix this"))
        }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent() // runs the begin coroutine; the renewal parks on its delay

        assertEquals("fix this", host.text)
        assertEquals(emptyList<StagedImage>(), host.images)
        assertEquals("Edit queued message", host.placeholder)
        assertFalse("+ is disabled while editing", host.attachEnabled)
        assertEquals(1, host.focusCount)
        assertEquals("q1", edit.editingId)
        assertEquals("my draft", edit.stashedDraftOrNull)
        assertTrue(edit.isEditing)

        // 73's display overlay: Editing over every chrome banner.
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.None, edit.isEditing))
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.NotDelivered, edit.isEditing))

        assertEquals(listOf("begin q1", "row q1"), port.events)
    }

    @Test
    fun aSecondBeginWhileTheFirstIsUnresolvedIsIgnored() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("one", "one"), "q2" to EditRow("two", "two"))
        }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        val gate = CompletableDeferred<Unit>()
        port.beginGates += gate

        edit.beginEdit("q1")
        runCurrent() // the begin coroutine runs and parks on the gate
        edit.beginEdit("q2") // pending: ignored
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, port.events.count { it.startsWith("begin") })
        assertEquals("begin q1", port.events.first { it.startsWith("begin") })
        assertEquals("q1", edit.editingId)
    }

    @Test
    fun aLockedBeginShowsTheAlertAndLeavesTheComposerAlone() = runTest {
        refusedBeginShowsTheAlert(EditStart.Locked)
    }

    @Test
    fun aMissingBeginShowsTheAlertAndLeavesTheComposerAlone() = runTest {
        refusedBeginShowsTheAlert(EditStart.Missing)
    }

    @Test
    fun anUnavailableBeginShowsTheAlertAndLeavesTheComposerAlone() = runTest {
        refusedBeginShowsTheAlert(EditStart.Unavailable)
    }

    @Test
    fun aThrownBeginFoldsToUnavailableAndShowsTheAlert() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("one", "one"))
            throwOnBegin = true
        }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        advanceUntilIdle()

        assertEquals(1, host.cantEditAlerts)
        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertTrue(host.attachEnabled)
        assertNull(edit.editingId)
        assertEquals(0, host.focusCount)
        assertEquals("the begin never reached the row lookup", listOf("begin q1"), port.events)
    }

    /** The shared refusal assertions: alert once, composer untouched. */
    private fun TestScope.refusedBeginShowsTheAlert(result: EditStart) {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("one", "one"))
            beginResult = { result }
        }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        advanceUntilIdle()

        assertEquals(1, host.cantEditAlerts)
        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertTrue(host.attachEnabled)
        assertNull(edit.editingId)
        assertEquals(0, host.focusCount)
        assertEquals("the begin never reached the row lookup", listOf("begin q1"), port.events)
    }

    // ------------------------------------------------------------ switching

    @Test
    fun switchingRestoresTheDraftSynchronouslyAndFinishesTheOldLeaseFirst() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("one", "one"), "q2" to EditRow("two", "two"))
        }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        assertEquals("one", host.text)

        val gate = CompletableDeferred<Unit>()
        port.beginGates += gate
        edit.beginEdit("q2")

        // Right after beginEdit returns, before any coroutine runs: the
        // draft is back (StandardTestDispatcher keeps the launch queued).
        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertTrue("attach is re-enabled synchronously", host.attachEnabled)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertNull(edit.editingId)
        assertNull(edit.stashedDraftOrNull)
        val beforeSwitch = port.events.toList()

        gate.complete(Unit)
        runCurrent()

        // The old lease was finished with cancel BEFORE the new begin.
        assertTrue("no finish before the switch ran", beforeSwitch.none { it.startsWith("finish") })
        val finishAt = port.events.indexOfFirst { it == "finish q1 cancel null" }
        val beginAt = port.events.indexOf("begin q2")
        assertTrue("finish q1 before begin q2: ${port.events}", finishAt in 0 until beginAt)

        // The stash is the user's draft again, never q1's text.
        assertEquals("q2", edit.editingId)
        assertEquals("two", host.text)
        assertEquals("my draft", edit.stashedDraftOrNull)
    }

    // ------------------------------------------------------------ commit

    @Test
    fun anUnchangedCommitCancelsAndRestoresTheDraft() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this", "fix this")) }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()

        edit.commit("fix this") // exactly the visible text: cancel, no text

        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertTrue(host.attachEnabled)
        assertNull(edit.stashedDraftOrNull)
        assertFalse(edit.isEditing)
        advanceUntilIdle()
        assertEquals(listOf("begin q1", "row q1", "finish q1 cancel null"), port.events)
    }

    @Test
    fun aChangedCommitSendsTheMergedText() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this$appshot", "fix this")) }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()

        edit.commit("fix this please")

        advanceUntilIdle()
        assertEquals(listOf("begin q1", "row q1", "finish q1 commit fix this please$appshot"), port.events)
        assertEquals("my draft", host.text)
        assertTrue("a Finished commit reports nothing", host.failures.isEmpty())
    }

    // ------------------------------------------------- the QueuedEditGate

    @Test
    fun theGateIsInertWhileIdle() = runTest {
        val port = FakePort()
        val host = FakeHost()
        val edit = controller(port, host)

        assertNull(edit.placeholderOverride)
        assertNull(edit.interceptSend("x"))
        assertTrue("no port call while idle", port.events.isEmpty())
    }

    @Test
    fun theGateCommitsWhileEditing() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this", "fix this")) }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()

        assertEquals("Edit queued message", edit.placeholderOverride)
        assertEquals(SendResult.Replaced, edit.interceptSend("fix this better"))

        // Exactly the commit(body) transitions: the draft is restored, the
        // edit is over, the finish went out with the merged text.
        assertFalse(edit.isEditing)
        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        advanceUntilIdle()
        assertEquals(listOf("begin q1", "row q1", "finish q1 commit fix this better"), port.events)
    }

    // ------------------------------------------------------------ cancel

    @Test
    fun cancelRestoresTheDraftAndCancelsOnce() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()

        edit.cancelEdit()

        // Synchronous: the draft is back before anything is awaited.
        assertEquals("my draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertTrue(host.attachEnabled)
        assertNull(edit.stashedDraftOrNull)
        assertFalse(edit.isEditing)
        advanceUntilIdle()
        assertEquals(listOf("begin q1", "row q1", "finish q1 cancel null"), port.events)

        // A second cancel, and idle cancels/commits, make no port call.
        edit.cancelEdit()
        edit.commit("x")
        advanceUntilIdle()
        assertEquals(1, port.events.count { it.startsWith("finish") })

        // onScreenLeft alone: no port call, the composer untouched.
        val textBefore = host.text
        edit.onScreenLeft()
        advanceUntilIdle()
        assertEquals(1, port.events.count { it.startsWith("finish") })
        assertEquals(textBefore, host.text)
        assertTrue("the flag only", !edit.isEditing)
    }

    // ------------------------------------------------ acquired after leaving

    @Test
    fun acquiringAfterTheScreenLeftReleasesTheRowAndLeavesTheComposerAlone() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        val gate = CompletableDeferred<Unit>()
        port.beginGates += gate
        edit.beginEdit("q1")
        runCurrent()
        edit.onScreenLeft()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("begin q1", "row q1", "finish q1 cancel null"), port.events)
        assertEquals("my draft", host.text)
        assertEquals(0, host.focusCount)
        assertFalse(edit.isEditing)
    }

    @Test
    fun acquiringAfterDisposeReleasesTheRowAndLeavesTheComposerAlone() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        val gate = CompletableDeferred<Unit>()
        port.beginGates += gate
        edit.beginEdit("q1")
        runCurrent()
        edit.dispose()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("begin q1", "row q1", "finish q1 cancel null"), port.events)
        assertEquals("my draft", host.text)
        assertEquals(0, host.focusCount)
    }

    // ---------------------------------------------------------- renewal

    @Test
    fun theRenewalTicksEveryIntervalAfterAcquisition() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost()
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()

        advanceTimeBy(19_999)
        runCurrent()
        assertEquals("no renew at 19 999 ms", 0, port.events.count { it.startsWith("renew") })
        advanceTimeBy(1)
        runCurrent()
        assertEquals("one renew at 20 000 ms", 1, port.events.count { it.startsWith("renew") })
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("two renews at 40 000 ms", 2, port.events.count { it.startsWith("renew") })
    }

    @Test
    fun noRenewRunsAfterACancel() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost()
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        edit.cancelEdit()
        advanceUntilIdle()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, port.events.count { it.startsWith("renew") })
    }

    @Test
    fun noRenewRunsAfterACommit() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost()
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        edit.commit("new text")
        advanceUntilIdle()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, port.events.count { it.startsWith("renew") })
    }

    // ------------------------------------------------------ recover-lost

    @Test
    fun aLostRenewalRecoversTheEditedTextIntoTheComposer() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this$appshot", "fix this")) }
        val host = FakeHost().apply {
            text = "my draft"
            images = listOf(image("i1"))
        }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        host.text = "fix this better"

        port.renewResult = false
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals("fix this better\n\nmy draft", host.text)
        assertEquals(listOf(image("i1")), host.images)
        assertEquals(FakeHost.Normal, host.placeholder)
        assertTrue(host.attachEnabled)
        assertEquals(listOf(QueueEditCopy.RenewLost), host.failures)
        assertFalse(edit.isEditing)
        assertTrue("no finish after a lost renewal", port.events.none { it.startsWith("finish") })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no further renews", 1, port.events.count { it.startsWith("renew") })
    }

    @Test
    fun aLostRenewalWithABlankDraftYieldsTheEditedText() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this", "fix this")) }
        val host = FakeHost()
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        host.text = "fix this better"

        port.renewResult = false
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals("fix this better", host.text)
    }

    // ---------------------------------------------------- recover-commit

    @Test
    fun failedCommitsMergeTheEditedRawTextAndReportTheCopy() = runTest {
        for (outcome in listOf(EditFinish.Conflict, EditFinish.Missing, EditFinish.Lost, EditFinish.Unavailable)) {
            val port = FakePort().apply {
                rows = mapOf("q1" to EditRow("fix this", "fix this"))
                finishResult = outcome
            }
            val host = FakeHost().apply { text = "current" }
            val edit = controller(port, host)
            edit.beginEdit("q1")
            runCurrent()
            host.text = "edited" // the user's edit, un-encoded
            edit.commit("encoded body")
            advanceUntilIdle()

            assertEquals("outcome $outcome", "edited\n\ncurrent", host.text)
            assertEquals("outcome $outcome", listOf(QueueEditCopy.finishMessage(outcome)), host.failures)
        }
    }

    @Test
    fun aFinishedCommitChangesNothing() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("fix this", "fix this")) }
        val host = FakeHost().apply { text = "current" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        host.text = "edited"
        edit.commit("encoded body")
        advanceUntilIdle()

        assertEquals("current", host.text)
        assertTrue(host.failures.isEmpty())
    }

    @Test
    fun cancelOutcomesNeverRecover() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("fix this", "fix this"))
            finishResult = EditFinish.Conflict
        }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        edit.cancelEdit()
        advanceUntilIdle()

        assertEquals("my draft", host.text)
        assertTrue(host.failures.isEmpty())
    }

    @Test
    fun nothingRecoversAfterDispose() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("fix this", "fix this"))
            finishResult = EditFinish.Conflict
        }
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        port.finishGates += gate
        edit.commit("new text")
        edit.dispose() // the user already left; the finish is still in flight
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("my draft", host.text)
        assertTrue(host.failures.isEmpty())
        assertTrue(port.events.any { it == "finish q1 commit new text" })
    }

    @Test
    fun aCommitFailureWhileANewEditIsOpenMergesIntoTheStash() = runTest {
        val port = FakePort().apply {
            rows = mapOf("q1" to EditRow("one", "one"), "q2" to EditRow("two", "two"))
            finishResult = EditFinish.Conflict
        }
        val host = FakeHost().apply { text = "draft" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        host.text = "edited1"

        val gate = CompletableDeferred<Unit>()
        port.finishGates += gate
        edit.commit("edited1") // the finish parks on the gate
        edit.beginEdit("q2") // a new edit opens before the failure lands
        runCurrent()
        assertEquals("two", host.text)
        assertEquals("draft", edit.stashedDraftOrNull)

        gate.complete(Unit)
        runCurrent()

        // The merge went into the stash, never the composer.
        assertEquals("two", host.text)
        assertEquals("edited1\n\ndraft", edit.stashedDraftOrNull)

        edit.cancelEdit()
        assertEquals("edited1\n\ndraft", host.text)
    }

    @Test
    fun theNextAcquiredEditClearsTheRecoveryMessageOnce() = runTest {
        val port = FakePort().apply { rows = mapOf("q1" to EditRow("one", "one")) }
        val host = FakeHost()
        val edit = controller(port, host)
        edit.beginEdit("q1")
        runCurrent()
        port.renewResult = false
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(QueueEditCopy.RenewLost), host.failures)
        assertTrue(host.cleared.isEmpty())

        port.renewResult = true
        edit.beginEdit("q1")
        runCurrent()

        assertEquals(listOf(QueueEditCopy.RenewLost), host.cleared)
        assertEquals("one renew before the loss", 1, port.events.count { it.startsWith("renew") })
    }

    // ------------------------------------------- the acquired-but-gone row

    @Test
    fun acquiringARowThatLeftTheSnapshotReleasesTheLeaseAndAlerts() = runTest {
        val port = FakePort().apply { rows = emptyMap() } // begin still acquires
        val host = FakeHost().apply { text = "my draft" }
        val edit = controller(port, host)
        edit.beginEdit("q1")
        advanceUntilIdle()

        assertEquals(listOf("begin q1", "row q1", "finish q1 cancel null"), port.events)
        assertEquals(1, host.cantEditAlerts)
        assertEquals("my draft", host.text)
        assertFalse(edit.isEditing)
    }

    // ------------------------------------------------- ComposerEditHost

    /** Records every alert; the host's alert path is ticket 18's. */
    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }
        override fun updateCurrent(alert: RobocoAlert) {}
        override fun dismissAll() {}
    }

    @Test
    fun theHostShowsTheCantEditAlertVerbatim() = runTest {
        val alerts = RecordingAlerts()
        val banner = StatusBanner(retryDelivery = {})
        val controller = ComposerController(this, NoFeedback)
        val host = ComposerEditHost(
            composer = controller,
            morph = previewMorphState(0f, card = false),
            keyboard = { null },
            alerts = { alerts },
            banner = banner,
            chromePlaceholder = { FakeHost.Normal },
        )

        host.showCantEditAlert()

        val alert = alerts.shown.single()
        assertEquals(QueueEditCopy.AlertTitle, alert.title)
        assertEquals(QueueEditCopy.AlertMessage, alert.message)
        val button = alert.buttons.single()
        assertEquals(QueueEditCopy.AlertOk, button.title)
        assertEquals(AlertButtonRole.Default, button.role)
    }

    @Test
    fun theHostsFailureSlotIs73sStickySendFailure() = runTest {
        val alerts = RecordingAlerts()
        val banner = StatusBanner(retryDelivery = {})
        val controller = ComposerController(this, NoFeedback)
        val host = ComposerEditHost(
            composer = controller,
            morph = previewMorphState(0f, card = false),
            keyboard = { null },
            alerts = { alerts },
            banner = banner,
            chromePlaceholder = { FakeHost.Normal },
        )
        val input = ChromeInput(chatId = "c", composer = composerState(), row = null, engine = null)

        // The recovery copy lands verbatim (no Couldn't send: prefix).
        host.showFailure("m1")
        assertEquals(SessionChrome.Banner.Failed("m1"), banner.fill(SessionChrome(), input).banner)
        host.showFailure("m2")
        assertEquals(SessionChrome.Banner.Failed("m2"), banner.fill(SessionChrome(), input).banner)

        // A later genuine send failure replaces it, and clearFailure never
        // wipes a message it does not still hold.
        banner.noteSendFailed(RuntimeException("x"))
        assertEquals(SessionChrome.Banner.Failed("Couldn't send: x"), banner.fill(SessionChrome(), input).banner)
        host.clearFailure("m2")
        assertEquals("the different failure is untouched", SessionChrome.Banner.Failed("Couldn't send: x"), banner.fill(SessionChrome(), input).banner)

        // Clearing the message that IS held empties the slot (with a
        // missing engine the derivation falls through to EngineOff).
        host.clearFailure("Couldn't send: x")
        assertEquals(SessionChrome.Banner.EngineOff, banner.fill(SessionChrome(), input).banner)
    }

    // ---------------------------------------------------- CoreQueueEditPort

    /** A recording [SessionHandle] fake (no Rust object behind it). */
    private class RecordingEditHandle : SessionHandle(NoHandle) {
        var beginResult: QueueEditStart = QueueEditStart.Acquired(
            QueueEditLease(rowId = "q1", leaseId = "L1", text = "one", baseTextHash = "h", expiresAtMs = 1L),
        )
        var renewResult = true
        var finishResult = QueueEditFinish.FINISHED
        var throwOnBegin = false
        var throwOnRenew = false
        var throwOnFinish = false
        var composerQueue = listOf<QueueItem>()
        val finishCalls = mutableListOf<Triple<QueueEditAction, String?, List<String>?>>()

        override suspend fun beginQueuedEdit(id: String, instanceId: String): QueueEditStart {
            if (throwOnBegin) {
                throwOnBegin = false
                throw RuntimeException("ffi begin boom")
            }
            return beginResult
        }

        override suspend fun renewQueuedEdit(lease: QueueEditLease): Boolean {
            if (throwOnRenew) {
                throwOnRenew = false
                throw RuntimeException("ffi renew boom")
            }
            return renewResult
        }

        override suspend fun finishQueuedEdit(
            lease: QueueEditLease,
            action: QueueEditAction,
            text: String?,
            attachments: List<String>?,
        ): QueueEditFinish {
            finishCalls += Triple(action, text, attachments)
            if (throwOnFinish) {
                throwOnFinish = false
                throw RuntimeException("ffi finish boom")
            }
            return finishResult
        }

        override fun composer(): ComposerState = composerState().copy(queue = composerQueue)
    }

    @Test
    fun theCorePortMapsBegin() = runTest {
        val handle = RecordingEditHandle()
        val port = CoreQueueEditPort(handle, io = StandardTestDispatcher(testScheduler))

        val acquired = port.begin("q1", "inst-1")
        val lease = (acquired as EditStart.Acquired).lease
        assertEquals("L1", lease.leaseId)
        assertEquals("q1", lease.rowId)
        assertSame(
            "the uniffi lease rides along",
            (handle.beginResult as QueueEditStart.Acquired).lease,
            lease.core,
        )

        handle.beginResult = QueueEditStart.Locked
        assertEquals(EditStart.Locked, port.begin("q1", "inst-2"))
        handle.beginResult = QueueEditStart.Missing
        assertEquals(EditStart.Missing, port.begin("q1", "inst-3"))
        handle.beginResult = QueueEditStart.Unavailable
        assertEquals(EditStart.Unavailable, port.begin("q1", "inst-4"))
        handle.throwOnBegin = true
        assertEquals("a thrown begin folds to Unavailable", EditStart.Unavailable, port.begin("q1", "inst-5"))
    }

    @Test
    fun theCorePortMapsRenew() = runTest {
        val handle = RecordingEditHandle()
        val port = CoreQueueEditPort(handle, io = StandardTestDispatcher(testScheduler))
        val lease = (port.begin("q1", "i") as EditStart.Acquired).lease

        assertTrue(port.renew(lease))
        handle.renewResult = false
        assertFalse(port.renew(lease))
        handle.throwOnRenew = true
        assertFalse("a thrown renew folds to false", port.renew(lease))
    }

    @Test
    fun theCorePortMapsFinishWithNullAttachments() = runTest {
        val handle = RecordingEditHandle()
        val port = CoreQueueEditPort(handle, io = StandardTestDispatcher(testScheduler))
        val lease = (port.begin("q1", "i") as EditStart.Acquired).lease

        assertEquals(EditFinish.Finished, port.finish(lease, commit = true, text = "new text"))
        assertEquals(Triple(QueueEditAction.COMMIT, "new text", null), handle.finishCalls.single())

        assertEquals(EditFinish.Finished, port.finish(lease, commit = false, text = null))
        assertEquals(Triple(QueueEditAction.CANCEL, null, null), handle.finishCalls.last())

        handle.finishResult = QueueEditFinish.CONFLICT
        assertEquals(EditFinish.Conflict, port.finish(lease, commit = true, text = "x"))
        handle.finishResult = QueueEditFinish.MISSING
        assertEquals(EditFinish.Missing, port.finish(lease, commit = true, text = "x"))
        handle.finishResult = QueueEditFinish.LOST
        assertEquals(EditFinish.Lost, port.finish(lease, commit = true, text = "x"))
        handle.finishResult = QueueEditFinish.UNAVAILABLE
        assertEquals(EditFinish.Unavailable, port.finish(lease, commit = true, text = "x"))
        handle.throwOnFinish = true
        assertEquals("a thrown finish folds to Unavailable", EditFinish.Unavailable, port.finish(lease, commit = true, text = "x"))
    }

    @Test
    fun theCorePortReadsTheRowFromTheComposerSnapshot() = runTest {
        val handle = RecordingEditHandle()
        val port = CoreQueueEditPort(handle, io = StandardTestDispatcher(testScheduler))
        handle.composerQueue = listOf(
            QueueItem(
                id = "q1",
                text = "raw one",
                visibleText = "one",
                attachments = emptyList(),
                holdForTurnEnd = false,
                issuedBy = "phone",
                fromThisDevice = true,
                issuedAtMs = 0L,
                editedAtMs = null,
                gate = null,
                actionPending = false,
            ),
        )

        assertEquals(EditRow(raw = "raw one", visible = "one"), port.row("q1"))
        assertNull(port.row("gone"))
    }

    @Test
    fun theFixturePortAlwaysAcquires() = runTest {
        val port = FixtureQueueEditPort(rows = mapOf("q1" to EditRow("one", "one")))
        val first = port.begin("q1", "i1") as EditStart.Acquired
        val second = port.begin("q1", "i2") as EditStart.Acquired
        assertTrue("each begin is a fresh lease", first.lease != second.lease)
        assertTrue(port.renew(first.lease))
        port.renewOk = false
        assertFalse(port.renew(first.lease))
        assertEquals(EditFinish.Finished, port.finish(first.lease, commit = false, text = null))
        port.finishOutcome = EditFinish.Lost
        assertEquals(EditFinish.Lost, port.finish(first.lease, commit = true, text = "x"))
        assertEquals(EditRow("one", "one"), port.row("q1"))
        assertNull(port.row("q2"))
    }
}

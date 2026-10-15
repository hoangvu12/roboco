package sh.roboco.android.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry-over state machine (android-ui-parity #108) — every case of
 * the ticket's Verification list, plain JUnit + `kotlinx-coroutines-test`
 * (no Robolectric, no Compose: the frame and the fence are injected).
 *
 * The frame gate is the seam the whole timing hangs on: [Rig.frameGate]
 * stands in for `withFrameNanos`, so a test can run the transition to
 * the gate, mutate the state "between frames" (the capture-before /
 * read-after asymmetry), then complete the gate and let the steps run.
 */
class CarryOverTest {

    // ------------------------------------------------------------ the rig

    /**
     * One carry-over over recording fakes. [gateFence] makes
     * `awaitDetached` also suspend on [detachGate], so a test can cancel
     * a run **after** the detail was cleared but **before** the phone
     * open (case 16).
     */
    private class Rig(
        currentChatId: String? = null,
        isCollapsed: Boolean = false,
        shownChatId: String? = null,
        detailIsEmpty: Boolean = false,
    ) {
        val log = mutableListOf<String>()
        val selection = FakeSelection(currentChatId, isCollapsed)
        val detail = FakeDetail(log, shownChatId, detailIsEmpty)
        val tabs = FakeTabs(log)
        val frameGate = CompletableDeferred<Unit>()
        val detachGate = CompletableDeferred<Unit>()
        var gateFence = false

        val carryOver = CarryOver(
            selection = selection,
            detail = detail,
            tabs = tabs,
            awaitFrame = {
                log += "frame"
                frameGate.await()
            },
            awaitDetached = { chatId ->
                log += "fence($chatId)"
                if (gateFence) detachGate.await()
            },
        )
    }

    /** The selection: plain mutable fields, so a test can write "between frames". */
    private class FakeSelection(
        currentChatId: String?,
        isCollapsed: Boolean,
    ) : CarryOverSelection {
        override var currentChatId: String? = currentChatId
        override var isCollapsed: Boolean = isCollapsed
    }

    /** The detail column: logs every call, tracks what it shows. */
    private class FakeDetail(
        private val log: MutableList<String>,
        shownChatId: String?,
        detailIsEmpty: Boolean,
    ) : SplitDetailColumn {
        override var shownChatId: String? = shownChatId
        override var isEmpty: Boolean = detailIsEmpty

        override fun clear() {
            log += "detail.clear"
            shownChatId = null
            isEmpty = true
        }

        override fun showSession(chatId: String) {
            log += "detail.show($chatId)"
            shownChatId = chatId
            isEmpty = false
        }

        override fun showDraft() {
            log += "detail.showDraft"
            shownChatId = null
            isEmpty = false
        }
    }

    /** The tab shell: logs every call. */
    private class FakeTabs(private val log: MutableList<String>) : TabsColumn {
        override fun openSession(chatId: String) {
            log += "tabs.open($chatId)"
        }

        override fun popToFrontPage() {
            log += "tabs.popToFrontPage"
        }

        override fun popSearchSessions() {
            log += "tabs.popSearchSessions"
        }

        override fun popAllToRoot() {
            log += "tabs.popAll"
        }
    }

    // ------------------------------------------------- Split → Phone

    @Test
    fun collapse_sessionInDetail_clearsThenOpensInTabs() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = true, shownChatId = "c1")
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone)
        // Facts 1-4: one frame, the column lets go of its copy, the fence,
        // then the phone push — and currentChatId is untouched.
        assertEquals(listOf("frame", "detail.clear", "fence(c1)", "tabs.open(c1)"), r.log)
        assertEquals("c1", r.selection.currentChatId)
    }

    @Test
    fun collapse_draftInDetail_noCurrent_doesNothing() = runTest {
        val r = Rig(currentChatId = null, isCollapsed = true, shownChatId = null, detailIsEmpty = false)
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone)
        // A draft in the detail stays (iOS fact 2); nothing was open.
        assertEquals(listOf("frame"), r.log)
    }

    @Test
    fun collapse_emptyDetail_withCurrent_opensOnly() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = true, shownChatId = null, detailIsEmpty = true)
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone)
        // The column is already empty: only the phone push.
        assertEquals(listOf("frame", "fence(c1)", "tabs.open(c1)"), r.log)
    }

    @Test
    fun collapse_capturesBeforeFrame() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = true, shownChatId = "c1")
        val job = launch { r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone) }
        runCurrent()
        // Fact 1: the id was captured before the deferral — an appear
        // landing between capture and frame changes nothing.
        assertEquals(listOf("frame"), r.log)
        r.selection.currentChatId = "c2"
        r.frameGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("frame", "detail.clear", "fence(c1)", "tabs.open(c1)"), r.log)
        assertTrue(job.isCompleted)
    }

    // ------------------------------------------------- Phone → Split

    @Test
    fun expand_bringsCurrentIntoEmptyDetail() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = false, shownChatId = null, detailIsEmpty = true)
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        // Facts 5-7: the pops first (Sessions, then the Search
        // adaptation), then the fenced detail open.
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "fence(c1)", "detail.show(c1)"),
            r.log,
        )
    }

    @Test
    fun expand_alreadyShown_noReplace() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = false, shownChatId = "c1")
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        // The column already shows it: only the pops.
        assertEquals(listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions"), r.log)
        assertEquals("c1", r.detail.shownChatId)
    }

    @Test
    fun expand_otherSessionShown_replaces() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = false, shownChatId = "c2")
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "fence(c1)", "detail.show(c1)"),
            r.log,
        )
    }

    @Test
    fun expand_noCurrent_emptyDetail_showsDraft() = runTest {
        val r = Rig(currentChatId = null, isCollapsed = false, shownChatId = null, detailIsEmpty = true)
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        // Fact 8: the draft only when nothing is open AND the column is empty.
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "detail.showDraft"),
            r.log,
        )
    }

    @Test
    fun expand_noCurrent_draftDetail_keepsIt() = runTest {
        val r = Rig(currentChatId = null, isCollapsed = false, shownChatId = null, detailIsEmpty = false)
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        // A draft that stayed through the collapse is kept as is (fact 8).
        assertEquals(listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions"), r.log)
        assertFalse(r.detail.isEmpty)
    }

    @Test
    fun expand_readsCurrentAfterFrame() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = false, shownChatId = null, detailIsEmpty = true)
        val job = launch { r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split) }
        runCurrent()
        // Fact 5: the id is read INSIDE the deferred block — an appear
        // landing before the frame is the one carried.
        assertEquals(listOf("frame"), r.log)
        r.selection.currentChatId = "c2"
        r.frameGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "fence(c2)", "detail.show(c2)"),
            r.log,
        )
        assertTrue(job.isCompleted)
    }

    // ------------------------------------------------------ presence

    @Test
    fun presence_appear_setsInBothModes() {
        val selection = FakeSelection(currentChatId = null, isCollapsed = true)
        val presence = CarryOverPresence(selection)
        presence.sessionDidAppear("c1")
        assertEquals("c1", selection.currentChatId)
        selection.isCollapsed = false
        presence.sessionDidAppear("c2")
        assertEquals("c2", selection.currentChatId)
    }

    @Test
    fun presence_close_onlyWhenCollapsedAndMatching() {
        // Collapsed, another id: nothing.
        val collapsed = FakeSelection(currentChatId = "c1", isCollapsed = true)
        val presence = CarryOverPresence(collapsed)
        presence.sessionDidClose("c9")
        assertEquals("c1", collapsed.currentChatId)

        // Collapsed + matching: cleared (widen then shows the draft).
        presence.sessionDidClose("c1")
        assertNull(collapsed.currentChatId)

        // Expanded: nothing (only the tab shell's closes count).
        val expanded = FakeSelection(currentChatId = "c1", isCollapsed = false)
        CarryOverPresence(expanded).sessionDidClose("c1")
        assertEquals("c1", expanded.currentChatId)
    }

    @Test
    fun closeInCompact_thenExpand_showsDraft() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = true, shownChatId = null, detailIsEmpty = true)
        // The session was popped (back) in the tab shell: widening must
        // not bring it back.
        CarryOverPresence(r.selection).sessionDidClose("c1")
        assertNull(r.selection.currentChatId)
        r.selection.isCollapsed = false
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "detail.showDraft"),
            r.log,
        )
    }

    @Test
    fun appearInCompact_thenExpand_showsIt() = runTest {
        val r = Rig(currentChatId = null, isCollapsed = true, shownChatId = null, detailIsEmpty = true)
        // A session appeared in the tab shell while collapsed: it is the
        // one carried into the column.
        CarryOverPresence(r.selection).sessionDidAppear("c3")
        r.selection.isCollapsed = false
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        assertEquals(
            listOf("frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "fence(c3)", "detail.show(c3)"),
            r.log,
        )
    }

    // ---------------------------------------------------- cancellation

    @Test
    fun collapseCancelledBeforeFrame_thenExpand_keepsDetail() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = false, shownChatId = "c1")
        val job = launch { r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        // The collapse was cut off before its frame: the detail still
        // shows the session, so the expand finds it and replaces nothing.
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        assertTrue(r.log.none { it == "detail.clear" || it.startsWith("detail.show") })
        assertEquals(listOf("frame", "frame", "tabs.popToFrontPage", "tabs.popSearchSessions"), r.log)
        assertEquals("c1", r.detail.shownChatId)
    }

    @Test
    fun collapseCancelledAfterClear_thenExpand_restores() = runTest {
        val r = Rig(currentChatId = "c1", isCollapsed = true, shownChatId = "c1")
        r.gateFence = true
        val job = launch { r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Phone) }
        runCurrent()
        r.frameGate.complete(Unit)
        runCurrent()
        // The column was cleared; the run now sits at the fence before
        // the phone open — and is cut off there.
        assertEquals(listOf("frame", "detail.clear", "fence(c1)"), r.log)
        job.cancel()
        advanceUntilIdle()
        // The expand finds an empty column and re-opens the captured id.
        r.gateFence = false
        r.selection.isCollapsed = false
        r.carryOver.onShellKindChanged(RootKind.Phone, RootKind.Split)
        assertEquals(
            listOf(
                "frame", "detail.clear", "fence(c1)",
                "frame", "tabs.popToFrontPage", "tabs.popSearchSessions", "fence(c1)", "detail.show(c1)",
            ),
            r.log,
        )
        assertEquals("c1", r.detail.shownChatId)
    }

    // ------------------------------------- first composition / teardown

    @Test
    fun pairingAndFirstComposition_doNothing() = runTest {
        val r = Rig()
        r.frameGate.complete(Unit)
        r.carryOver.onShellKindChanged(null, RootKind.Split)
        r.carryOver.onShellKindChanged(null, RootKind.Phone)
        r.carryOver.onShellKindChanged(RootKind.Split, RootKind.Split)
        // Launch, Pairing → shell, a same-kind "change": no transition
        // runs, not even a frame.
        assertTrue(r.log.isEmpty())
    }

    @Test
    fun releaseAll_popsAllThenClears() {
        val r = Rig(shownChatId = "c1")
        r.carryOver.releaseAll()
        // Shell teardown: every tab stack popped, then the column cleared.
        assertEquals(listOf("tabs.popAll", "detail.clear"), r.log)
    }
}

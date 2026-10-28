package sh.roboco.android.sessions

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.ActionSheetHostState
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastAnnouncer
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.ToasterState
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import uniffi.roboco_core.SectionView
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The folder screens' behaviour (android-ui-parity #45): the section page
 * with its ⋯ menu, the Rename/Delete flows through ticket 43's launchers,
 * the per-kind trailing item and row flags, the empty/loading states and
 * the live workspace updates — Robolectric + `createAndroidComposeRule` at
 * `w412dp-h700dp-xxhdpi` (1 dp = 3 px; `topInset` is fixed at 24 dp, the
 * goldens' value, because Robolectric reports no insets).
 *
 * The host mirrors ticket 35's mount order with the ⋯ menu inside:
 * `AlertHost { ToastHost { ActionSheetHost { GlassMenuHost {
 * NavStackHost { … } } } } }` — the sheet draws above the fading menu and
 * the alert above both. [RowActions] is a recording fake whose `onResult`
 * fires synchronously with a configurable outcome (the real dispatch and
 * its error toasts are ticket 42's, tested there); the sheet rows are
 * looked up in the unmerged tree (the overlay root is focusable and merges
 * descendants), the alert nodes in the merged one, as in
 * `SessionPromptsTest`. After each tap the clock is advanced past the
 * menu's 150 ms close fade (ticket 15 runs the item action on the next
 * frame) and, after a sheet pick, past its 250 ms dismiss and the 0.5 s pop
 * spring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class FolderScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ the fakes

    /**
     * The [RowActions] fake: records every call (the ticket's log format)
     * and invokes `onResult` synchronously with [outcome] (default `Ok`).
     */
    private class RecordingRowActions : RowActions {
        val calls = mutableListOf<String>()
        var outcome = ActionOutcome.Ok

        private fun result(onResult: (ActionOutcome) -> Unit) = onResult(outcome)

        override fun setPinned(chatId: String, pinned: Boolean, onResult: (ActionOutcome) -> Unit) {
            calls += "setPinned($chatId, $pinned)"
            result(onResult)
        }

        override fun archive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "archive($chatId)"
            result(onResult)
        }

        override fun unarchive(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "unarchive($chatId)"
            result(onResult)
        }
        override fun delete(chatId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "delete($chatId)"
            result(onResult)
        }

        override fun move(chatId: String, toSection: String?, onResult: (ActionOutcome) -> Unit) {
            calls += "move($chatId, ${toSection ?: "null"})"
            result(onResult)
        }

        override fun movePin(chatId: String, after: String?, before: String?, onResult: (ActionOutcome) -> Unit) {
            calls += "movePin($chatId)"
            result(onResult)
        }

        override fun rename(chatId: String, title: String, onResult: (ActionOutcome) -> Unit) {
            calls += "rename($chatId, $title)"
            result(onResult)
        }

        override fun createSection(
            name: String,
            engineId: String?,
            onResult: (ActionOutcome) -> Unit,
            onCreated: (sectionId: String) -> Unit,
        ) {
            calls += "createSection($name, $engineId)"
            if (outcome == ActionOutcome.Ok) onCreated("s:$name")
            result(onResult)
        }

        override fun renameSection(sectionId: String, name: String, onResult: (ActionOutcome) -> Unit) {
            calls += "renameSection($sectionId, $name)"
            result(onResult)
        }

        override fun deleteSection(sectionId: String, onResult: (ActionOutcome) -> Unit) {
            calls += "deleteSection($sectionId)"
            result(onResult)
        }

        override fun sectionsFor(engineId: String): List<SectionView> = emptyList()

        override fun report(error: Throwable) {
            calls += "report($error)"
        }
    }

    private val alerts = AlertHostState()
    private val sheets = ActionSheetHostState()
    private val toaster = ToasterState()
    private val actions = RecordingRowActions()
    private val contexts = CopyOnWriteArrayList<SessionRowContext>()
    private val stack = NavStack(TabRootRoute(TabId.Sessions))
    private var workspace by mutableStateOf<WorkspaceSnapshot?>(null)

    @Before
    fun mount() {
        workspace = FolderFixtures.sectionWorkspace()
        // Pushed hostless before the host exists: the transition completes
        // at call time, so the screen is the resting top of the stack.
        stack.push(FolderFixtures.sectionRoute, animated = false)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Frozen: the loading test's live spinner would otherwise keep the clock busy.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                AlertHost(state = alerts) {
                    ToastHost(state = toaster, announcer = ToastAnnouncer { }) {
                        ActionSheetHost(state = sheets) {
                            GlassMenuHost {
                                CompositionLocalProvider(LocalRowActions provides actions) {
                                    NavStackHost(stack = stack, active = true) { entry ->
                                        val route = entry.route
                                        if (route is FolderRoute) {
                                            FolderScreen(
                                                route = route,
                                                workspace = workspace,
                                                loading = workspace == null,
                                                topInset = 24.dp,
                                                rowWrapper = { ctx, row ->
                                                    contexts += ctx
                                                    row(Modifier)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ helpers

    /** px of [v] dp at the test density (3). */
    private fun dp(v: Float): Float = with(compose.density) { v.dp.toPx() }

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    /** The sheet nodes: **unmerged** — the overlay root is focusable and merges descendants. */
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** Text lookups in the unmerged tree (sheet titles, rows). */
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    /** The last captured [SessionRowContext] per chat id (recompositions re-emit). */
    private fun lastContexts(): Map<String, SessionRowContext> = contexts.associateBy { it.vm.id }

    /** Opens the ⋯ menu and picks Rename, running the menu's close out. */
    private fun openRenameAlert() {
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-rename").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the 150 ms close fade + 15's next-frame action
        compose.waitForIdle()
    }

    /** Types [raw] into the rename alert and taps Rename. */
    private fun renameTo(raw: String) {
        openRenameAlert()
        compose.onNodeWithTag("alert-field").performTextReplacement(raw)
        compose.waitForIdle()
        compose.onNodeWithTag("alert-primary").performClick()
        compose.waitForIdle()
    }

    /** Opens ⋯ → Delete Section and confirms the sheet, running the pop out. */
    private fun confirmDelete() {
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-delete").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        node("action-sheet-row-0").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the 250 ms sheet dismiss before onSelect
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800) // the 0.5 s pop spring
        compose.waitForIdle()
    }

    /**
     * Pops the section page, waits for its composition to leave (the
     * leaving entry stays composed until the host's sweep finalizes it —
     * its ⋯ must be gone before the per-kind assertions), then pushes
     * [route] non-animated with [ws] as the workspace.
     */
    private fun show(route: FolderRoute, ws: WorkspaceSnapshot?) {
        compose.runOnUiThread { stack.popToRoot(animated = false) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnUiThread {
            stack.push(route, animated = false)
            workspace = ws
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /**
     * A slow, zero-velocity swipe of [distanceDp] upward (content scrolls
     * up), starting well below the nav row: no fling, so the release state
     * is exactly the drag state.
     */
    private fun dragUp(distanceDp: Float) {
        compose.onRoot().performTouchInput {
            swipeWithVelocity(
                start = Offset(width / 2f, height * 0.6f),
                end = Offset(width / 2f, height * 0.6f - dp(distanceDp)),
                endVelocity = 0f,
            )
        }
        compose.waitForIdle()
    }

    // ---------------------------------------------------- the section page

    @Test
    fun theSectionPageShowsTheLargeTitleAndTheMoreCircle() {
        compose.onNodeWithTag("folder-screen").assertExists()
        compose.onNodeWithTag("nav-large-title").assert(hasText("P0"))
        compose.onNodeWithTag("nav-back").assertExists() // the pushed back circle (25)
        compose.onNodeWithTag("session-chat-veil").assertExists()
        compose.onNodeWithTag("session-chat-launch").assertExists()

        // 44 × 44, 16 dp from the end edge, centred in the 44 dp nav row
        // under the 24 dp status bar; "More" is its a11y label.
        val button = compose.onNodeWithTag("folder-menu").pxBounds()
        assertEquals(dp(44f), button.width, 1f)
        assertEquals(dp(44f), button.height, 1f)
        assertEquals(dp(412f - 16f), button.right, 1f)
        assertEquals(dp(24f + 22f), button.center.y, 1f)
        compose.onNodeWithTag("folder-menu").assert(hasContentDescription("More"))
    }

    @Test
    fun tappingTheMoreCircleOpensTheAnchoredTwoRowMenu() {
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600) // past the 0.35 s open spring
        compose.waitForIdle()

        compose.onNodeWithTag("glass-menu").assertExists()
        compose.onNodeWithText("Rename Section\u2026").assertExists()
        compose.onNodeWithText("Delete Section").assertExists()
        compose.onNodeWithTag("folder-menu-rename").assertExists()
        compose.onNodeWithTag("folder-menu-delete").assertExists()
        // Exactly two rows: no submenu, back or loading line; no title line
        // (the panel's paneTitle is the "no title" fallback).
        compose.onNodeWithTag("glass-menu-back").assertDoesNotExist()
        compose.onNodeWithTag("glass-menu-loading").assertDoesNotExist()
        assertEquals(
            "Menu",
            compose.onNodeWithTag("glass-menu").fetchSemanticsNode().config[SemanticsProperties.PaneTitle],
        )

        // Anchored to the ⋯ button (15's cover placement): the panel's right
        // edge aligns to the button's and it starts at the button's top.
        val menu = compose.onNodeWithTag("glass-menu").pxBounds()
        val button = compose.onNodeWithTag("folder-menu").pxBounds()
        assertEquals(button.right, menu.right, dp(1f))
        assertEquals(button.top, menu.top, dp(1f))

        // An outside tap closes it.
        compose.onNodeWithTag("glass-menu-scrim").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
    }

    // -------------------------------------------------------- the ⋯ Rename

    @Test
    fun renameShowsThePrefilledAlertAndRetitlesBothTitlesInPlace() {
        // Scroll first so "the offset is unchanged" is a real assertion:
        // 12 rows give the section list scroll range. Only to the collapsed
        // edge (the release snaps 26 < c < 52 to c = 52): the large title is
        // the list's first item and stays composed in the top padding only
        // while c <= 52 — a 150 dp drag scrolled it out of composition, so
        // "nav-large-title" no longer existed to be retitled.
        compose.runOnUiThread {
            workspace = FolderFixtures.sectionWorkspace(rows = FolderFixtures.scrollRows)
        }
        compose.waitForIdle()
        dragUp(45f)
        val rowTopBefore = compose.onNodeWithTag("session-chat-scroll-0").fetchSemanticsNode().boundsInRoot.top

        openRenameAlert()

        compose.onNodeWithText("Rename Section").assertExists()
        val field = compose.onNodeWithTag("alert-field")
        assertEquals("P0", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Rename").assertExists()

        field.performTextReplacement("  P1  ")
        compose.waitForIdle()
        compose.onNodeWithTag("alert-primary").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertEquals(listOf("renameSection(eng1:sec-p0, P1)"), actions.calls)
        // Both titles change in place (25 supports a changing title)…
        compose.onNodeWithTag("nav-large-title").assert(hasText("P1"))
        compose.onNodeWithTag("nav-inline-title").assert(hasText("P1"))
        // …and the scroll position is not reset.
        val rowTopAfter = compose.onNodeWithTag("session-chat-scroll-0").fetchSemanticsNode().boundsInRoot.top
        assertEquals(rowTopBefore, rowTopAfter, 1f)
    }

    @Test
    fun aRefusedRenameLeavesTheTitle() {
        actions.outcome = ActionOutcome.EngineOffline
        renameTo("  P1  ")
        // The write is attempted (42 toasts its failure); the title stays.
        assertEquals(listOf("renameSection(eng1:sec-p0, P1)"), actions.calls)
        compose.onNodeWithTag("nav-large-title").assert(hasText("P0"))
    }

    @Test
    fun aBlankRenameDismissesWithoutAWrite() {
        renameTo("   ")
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertTrue("a blank name must dismiss without a write", actions.calls.isEmpty())
    }

    @Test
    fun aCancelledRenameMakesNoCall() {
        openRenameAlert()
        compose.onNodeWithTag("alert-cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alert").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun aSecondRenamePrefillsTheLiveWorkspaceNameNotThePushTimeTitle() {
        renameTo("  P1  ")
        assertEquals(listOf("renameSection(eng1:sec-p0, P1)"), actions.calls)
        compose.onNodeWithTag("nav-large-title").assert(hasText("P1"))

        // The workspace now carries the renamed section (a rename elsewhere
        // on this engine); the prefill must follow it, not the push-time "P0".
        compose.runOnUiThread { workspace = FolderFixtures.sectionWorkspace(name = "P1") }
        compose.waitForIdle()
        openRenameAlert()
        assertEquals(
            "P1",
            compose.onNodeWithTag("alert-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    // -------------------------------------------------------- the ⋯ Delete

    @Test
    fun deleteShowsTheConfirmSheetThenWritesOnceAndPops() {
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-delete").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        node("action-sheet").assertExists()
        text("Delete \u201CP0\u201D?").assertExists()
        text("Its sessions move back to the main list.").assertExists()
        text("Delete Section").assertExists()
        node("action-sheet-cancel").assertExists()

        node("action-sheet-row-0").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // the 250 ms dismiss before onSelect
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800) // the 0.5 s pop spring
        compose.waitForIdle()

        assertEquals(listOf("deleteSection(eng1:sec-p0)"), actions.calls)
        assertEquals("the delete pops the folder page", 1, stack.entries.size)
    }

    @Test
    fun deletePopsOnGoneToo() {
        actions.outcome = ActionOutcome.Gone
        confirmDelete()
        assertEquals(listOf("deleteSection(eng1:sec-p0)"), actions.calls)
        assertEquals("Gone means the section is gone either way", 1, stack.entries.size)
    }

    @Test
    fun aRefusedDeleteKeepsTheScreen() {
        actions.outcome = ActionOutcome.EngineOffline
        confirmDelete()
        assertEquals(listOf("deleteSection(eng1:sec-p0)"), actions.calls)
        assertEquals(2, stack.entries.size)
        compose.onNodeWithTag("folder-screen").assertExists()
        compose.onNodeWithTag("nav-large-title").assert(hasText("P0"))
    }

    @Test
    fun aCancelledDeleteMakesNoCall() {
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-delete").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        node("action-sheet-cancel").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        node("action-sheet").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
        assertEquals(2, stack.entries.size)
    }

    // ---------------------------------------------------- Pinned / Archived

    @Test
    fun thePinnedPageHasNoMenuItemAndItsRowsAreReorderable() {
        show(FolderFixtures.pinnedRoute, FolderFixtures.pinnedWorkspace())
        compose.onNodeWithTag("nav-large-title").assert(hasText("Pinned"))
        compose.onNodeWithTag("folder-menu").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-hibernation").assertExists()
        compose.onNodeWithTag("session-chat-scroll-pinning").assertExists()

        val byId = lastContexts()
        assertTrue("reorderable = true (47's drag handles)", byId.getValue("chat-hibernation").reorderable)
        assertTrue("editing = false at rest (47's capsule toggles it)", !byId.getValue("chat-scroll-pinning").editing)
        assertTrue("archivedRows stays false", !byId.getValue("chat-hibernation").archived)
    }

    @Test
    fun theArchivedPageHasNoMenuItemAndItsRowsAreArchived() {
        show(FolderFixtures.archivedRoute, FolderFixtures.archivedWorkspace())
        compose.onNodeWithTag("nav-large-title").assert(hasText("Archived"))
        compose.onNodeWithTag("folder-menu").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-deploy").assertExists()
        compose.onNodeWithTag("session-chat-downloads").assertExists()

        val byId = lastContexts()
        assertTrue("archivedRows = true (44's archived variants)", byId.getValue("chat-deploy").archived)
        assertTrue(byId.getValue("chat-downloads").archived)
        assertTrue("no reorder on Archived", !byId.getValue("chat-deploy").reorderable)
    }

    // ------------------------------------------------------ empty / loading

    @Test
    fun anEmptyPinnedListDrawsNothing() {
        show(FolderFixtures.pinnedRoute, FolderFixtures.pinnedWorkspace(emptyList()))
        compose.onNodeWithTag("nav-large-title").assert(hasText("Pinned"))
        compose.onNodeWithTag("sessions-empty").assertDoesNotExist()
        compose.onNodeWithTag("sessions-loading").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-hibernation").assertDoesNotExist()
    }

    @Test
    fun anEmptyLoadedArchiveShowsTheArchivedEmptyState() {
        show(FolderFixtures.archivedRoute, FolderFixtures.archivedWorkspace(emptyList()))
        compose.onNodeWithTag("nav-large-title").assert(hasText("Archived"))
        compose.onNodeWithTag("sessions-empty").assert(hasText("No archived sessions"))
        compose.onNodeWithTag("sessions-loading").assertDoesNotExist()
    }

    @Test
    fun aMissingWorkspaceShowsTheLoadingSpinner() {
        compose.runOnUiThread { workspace = null }
        compose.waitForIdle()
        compose.onNodeWithTag("sessions-loading").assertExists()
        compose.onNodeWithTag("sessions-empty").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-veil").assertDoesNotExist()
    }

    // ---------------------------------------------------- live updates

    @Test
    fun theRowsFollowTheWorkspaceLiveAndTheScreenSurvivesADeletedSection() {
        // A row removed elsewhere leaves the list while the page is open.
        compose.runOnUiThread {
            workspace = FolderFixtures.sectionWorkspace(rows = listOf(FolderFixtures.veil, FolderFixtures.models))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("session-chat-launch").assertDoesNotExist()
        compose.onNodeWithTag("session-chat-veil").assertExists()

        // The section itself deleted elsewhere: an empty list (iOS `?? []`),
        // the screen stays open…
        compose.runOnUiThread { workspace = FolderFixtures.noSectionWorkspace() }
        compose.waitForIdle()
        compose.onNodeWithTag("session-chat-veil").assertDoesNotExist()
        compose.onNodeWithTag("sessions-empty").assertDoesNotExist() // a section blanks, no copy
        compose.onNodeWithTag("folder-screen").assertExists()
        assertEquals(2, stack.entries.size)

        // …and the ⋯ still opens, but both items do nothing (the live raw
        // name is gone, so no prompt is shown and nothing is called).
        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-rename").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag("alert").assertDoesNotExist()

        compose.onNodeWithTag("folder-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("folder-menu-delete").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        node("action-sheet").assertDoesNotExist()

        assertTrue(actions.calls.isEmpty())
        assertEquals(2, stack.entries.size)
    }
}

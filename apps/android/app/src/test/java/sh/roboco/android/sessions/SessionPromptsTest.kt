package sh.roboco.android.sessions

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastAnnouncer
import sh.roboco.android.design.ToasterState
import sh.roboco.android.design.ToastHost
import uniffi.roboco_core.SectionView

/**
 * Ticket 43's launcher behaviour (Robolectric, `createAndroidComposeRule`):
 * the five prompts through the real hosts — ticket 35's mount order
 * `AlertHost { ToastHost { ActionSheetHost { … } } }` — with
 * [rememberSessionPrompts] over a fake backend and a recording fake
 * [RowActions] whose `onResult` fires synchronously with a configurable
 * outcome (the real dispatch, its IO lane and its error toasts are ticket
 * 42's, tested there). Written per the test policy; the batch run
 * executes it.
 *
 * Clocks follow the neighbours' proven patterns: the frozen clock
 * (`autoAdvance = false`) for the sheet timelines — `onSelect` only after
 * the 250 ms dismiss, the Create on → New Section chain — and the auto
 * clock for the alert flows (focus, typing, the 200 ms fade-out), as in
 * `AlertHostTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionPromptsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ the fakes

    /** The [SessionPromptBackend] fake: a mutable engine list, read at invocation time. */
    private class FakePromptBackend(var engines: List<EngineChoice>) : SessionPromptBackend {
        override fun engines(): List<EngineChoice> = engines
    }

    /**
     * The [RowActions] fake: records every call (the ticket's log format,
     * e.g. `createSection(Infra 2, linux)`) and invokes `onResult`
     * synchronously with [outcome] (default `Ok`).
     */
    private class RecordingRowActions : RowActions {
        val calls = mutableListOf<String>()
        var sections: List<SectionView> = emptyList()
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

        override fun sectionsFor(engineId: String): List<SectionView> = sections.filter { it.engineId == engineId }

        override fun report(error: Throwable) {
            calls += "report($error)"
        }
    }

    private val alerts = AlertHostState()
    private val sheets = ActionSheetHostState()
    private val toaster = ToasterState()
    private val feedback = RecordingFeedback()
    private val actions = RecordingRowActions()
    private val backend = FakePromptBackend(
        listOf(
            EngineChoice("studio", "Studio Mac", true),
            EngineChoice("linux", "Linux box", true),
            EngineChoice("windows", "Windows desktop", false),
        ),
    )
    private var prompts: SessionPrompts = SessionPrompts.Noop

    /** The row ticket 44 passes: id, engine and displayed title are all that matters. */
    private val c1 = row("c1", engineId = "studio").copy(title = "Fix flaky test")

    @Before
    fun mount() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                AlertHost(state = alerts) {
                    ToastHost(state = toaster, announcer = ToastAnnouncer { }) {
                        ActionSheetHost(state = sheets) {
                            CompositionLocalProvider(
                                LocalSessionPromptBackend provides backend,
                                LocalRowActions provides actions,
                                LocalFeedback provides feedback,
                            ) {
                                prompts = rememberSessionPrompts()
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ helpers

    /** The alert nodes (merged tree, as in `AlertHostTest`). */
    private fun alert(tag: String) = compose.onNodeWithTag(tag)

    /** The sheet nodes: **unmerged** — the overlay root is focusable and merges descendants. */
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** Text lookups in the unmerged tree (sheet titles, rows, toasts). */
    private fun text(value: String) = compose.onNodeWithText(value, useUnmergedTree = true)

    /** `getBoundsInRoot` is a [androidx.compose.ui.unit.DpRect]; the assertions compare px. */
    private fun SemanticsNodeInteraction.pxBounds(): Rect =
        with(compose.density) { getBoundsInRoot().toRect() }

    /** Presents on the frozen clock: settle, invoke on main, advance past the animations. */
    private fun launch(block: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread(block)
        // Frozen clock: the host's state write only reaches the Recomposer
        // once apply notifications are sent (GlobalSnapshotManager posts that
        // to the paused main looper, which advanceTimeBy does not idle).
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(500)
    }

    /** A row pick: click, flush the gesture's frame, then run out the 250 ms dismiss. */
    private fun pickRow(tag: String) {
        node(tag).performClick()
        compose.mainClock.advanceTimeByFrame() // flush the gesture's frame
        compose.mainClock.advanceTimeBy(251) // the 250 ms dismiss, past its boundary
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    // ------------------------------------------------------- Rename Session

    @Test
    fun renameSessionShowsTheAlertPrefilledAndSubmitsOnce() {
        compose.runOnUiThread { prompts.renameSession(c1) }
        compose.waitForIdle()

        alert("alert").assertExists()
        compose.onNodeWithText("Rename Session").assertExists()
        val field = alert("alert-field")
        field.assertIsFocused()
        // Decision FOUNDATION-D2: the caret at the end of "Fix flaky test".
        assertEquals(TextRange(14), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        // EditableText is an AnnotatedString: compare its plain text.
        assertEquals("Fix flaky test", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        field.performTextInput(" CI")
        compose.waitForIdle()
        assertEquals("Fix flaky test CI", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        field.performTextReplacement("Deflake CI")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle() // runs the 200 ms fade-out

        alert("alert").assertDoesNotExist()
        assertEquals(listOf("rename(c1, Deflake CI)"), actions.calls)
    }

    @Test
    fun aBlankRenameClosesTheAlertWithoutAWrite() {
        compose.runOnUiThread { prompts.renameSession(c1) }
        compose.waitForIdle()

        alert("alert-field").performTextClearance()
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        alert("alert").assertDoesNotExist()
        assertTrue("a blank title must dismiss without a write", actions.calls.isEmpty())
    }

    // ---------------------------------------------------- New Section (⋯)

    @Test
    fun theMenuNewSectionChainsChooserThenAlert() {
        launch { prompts.newSection() }

        // The chooser: title, message, the connected engines in pairing
        // order, the offline one absent, only two action rows.
        node("action-sheet").assertExists()
        text("New Section").assertExists()
        text("Create on").assertExists()
        text("Studio Mac").assertExists()
        text("Linux box").assertExists()
        text("Windows desktop").assertDoesNotExist()
        node("action-sheet-row-2").assertDoesNotExist()
        node("action-sheet-cancel").assertExists()

        // Pick "Linux box": the alert presents only once the sheet is gone.
        node("action-sheet-row-1").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(249)
        alert("alert").assertDoesNotExist()
        node("action-sheet").assertExists() // still dismissing

        compose.mainClock.advanceTimeBy(1)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(300) // the alert's present
        node("action-sheet").assertDoesNotExist()
        alert("alert").assertExists()

        // Type and create — on the engine the chooser picked.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        text("Name").assertExists() // the empty field's placeholder
        val field = alert("alert-field")
        field.performTextInput("Infra 2")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        alert("alert").assertDoesNotExist()
        assertEquals(listOf("createSection(Infra 2, linux)"), actions.calls)
    }

    @Test
    fun theMenuNewSectionWithOneEngineSkipsTheChooser() {
        backend.engines = listOf(
            EngineChoice("studio", "Studio Mac", true),
            EngineChoice("linux", "Linux box", false),
        )
        compose.runOnUiThread { prompts.newSection() }
        compose.waitForIdle()

        node("action-sheet").assertDoesNotExist()
        alert("alert").assertExists()
        val field = alert("alert-field")
        field.performTextInput("Infra 2")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        alert("alert").assertDoesNotExist()
        assertEquals(listOf("createSection(Infra 2, studio)"), actions.calls)
    }

    @Test
    fun theMenuNewSectionWithNoEngineToastsAndShowsNothing() {
        backend.engines = listOf(EngineChoice("studio", "Studio Mac", false))
        launch { prompts.newSection() }

        alert("alert").assertDoesNotExist()
        node("action-sheet").assertDoesNotExist()
        text("Engine not connected").assertExists() // ticket 14 tags the toast
        assertTrue(actions.calls.isEmpty())
    }

    // --------------------------------------------------- New Section (row)

    @Test
    fun theRowNewSectionWithAnOfflineEngineToastsUpFront() {
        launch { prompts.newSection("windows") }

        alert("alert").assertDoesNotExist()
        node("action-sheet").assertDoesNotExist()
        text("Engine not connected").assertExists()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun theRowNewSectionBindsToTheRowsEngineWithoutAChooser() {
        compose.runOnUiThread { prompts.newSection("studio") }
        compose.waitForIdle()

        node("action-sheet").assertDoesNotExist()
        alert("alert").assertExists()
        alert("alert-field").performTextInput("Infra 2")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        alert("alert").assertDoesNotExist()
        // Creates on the chat's engine and never moves the chat (iOS :292-294).
        assertEquals(listOf("createSection(Infra 2, studio)"), actions.calls)
    }

    // ---------------------------------------------------- Move to Section

    @Test
    fun moveToSectionListsOnlyTheRowsEngineSectionsAndNoSectionMoves() {
        actions.sections = listOf(
            section("backend", name = "Backend", engineId = "studio"),
            section("design", name = "Design review", engineId = "studio"),
            section("infra", name = "Infra", engineId = "linux"),
        )
        launch { prompts.moveToSection(c1) }

        node("action-sheet").assertExists()
        text("Move to Section").assertExists()
        text("Infra").assertDoesNotExist() // another engine's section
        for (i in 0..2) {
            // No checkmark on the current section (pr621 adds one; iOS does
            // not) — the row never carries `Selected` at all.
            assertTrue(
                "row $i must not be selected",
                SemanticsProperties.Selected !in node("action-sheet-row-$i").fetchSemanticsNode().config,
            )
        }

        // In order: Backend, Design review, No Section, then the Cancel card.
        val backendTitle = text("Backend").pxBounds()
        val design = text("Design review").pxBounds()
        val noSection = text("No Section").pxBounds()
        val cancel = node("action-sheet-cancel").pxBounds()
        assertTrue(backendTitle.top < design.top && design.top < noSection.top)
        assertTrue(noSection.bottom <= cancel.top)

        // "No Section" runs the move only after the sheet's 250 ms dismiss.
        // The frozen clock only moves in whole 16 ms frames (advanceTimeBy
        // rounds up), so "249 ms" was really 256 ms on top of the click's
        // and the flush frame — past the 250 ms fall. Check well inside the
        // dismiss instead (≤ 208 ms of it elapsed), then run it out.
        node("action-sheet-row-2").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(200)
        assertTrue("the move must not run before the dismiss ends", actions.calls.isEmpty())
        node("action-sheet").assertExists()

        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf("move(c1, null)"), actions.calls)
        node("action-sheet").assertDoesNotExist()
    }

    // ------------------------------------------------------ Rename Section

    @Test
    fun renameSectionSubmitsTheTrimmedNameAndRunsOnRenamedOnOk() {
        val renamed = mutableListOf<String>()
        compose.runOnUiThread { prompts.renameSection("backend", "Backend") { renamed += it } }
        compose.waitForIdle()

        alert("alert").assertExists()
        assertEquals("Backend", alert("alert-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)

        alert("alert-field").performTextReplacement("  API ")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        alert("alert").assertDoesNotExist()
        assertEquals(listOf("renameSection(backend, API)"), actions.calls)
        assertEquals(listOf("API"), renamed)
    }

    @Test
    fun renameSectionSkipsOnRenamedWhenTheWriteFails() {
        actions.outcome = ActionOutcome.EngineOffline
        val renamed = mutableListOf<String>()
        compose.runOnUiThread { prompts.renameSection("backend", "Backend") { renamed += it } }
        compose.waitForIdle()

        alert("alert-field").performTextReplacement("  API ")
        compose.waitForIdle()
        alert("alert-primary").performClick()
        compose.waitForIdle()

        // The write is attempted (42 toasts its failure); the title update does not run.
        assertEquals(listOf("renameSection(backend, API)"), actions.calls)
        assertTrue(renamed.isEmpty())
    }

    // ---------------------------------------------- Delete session (#967)

    @Test
    fun confirmDeleteSessionShowsTheAlertAndDeletesOnDelete() {
        var deleted = 0
        compose.runOnUiThread { prompts.confirmDeleteSession("c1", "Fix flaky test") { deleted++ } }
        compose.waitForIdle()

        alert("alert").assertExists()
        compose.onNodeWithText("Delete session?").assertExists()
        compose.onNodeWithText("\u201CFix flaky test\u201D will be permanently deleted. This can\u2019t be undone.")
            .assertExists()
        alert("alert-cancel").assertExists()

        alert("alert-primary").performClick()
        compose.waitForIdle()
        alert("alert").assertDoesNotExist()
        assertEquals(listOf("delete(c1)"), actions.calls)
        assertEquals(1, deleted)
    }

    @Test
    fun cancellingDeleteSessionKeepsTheSessionAndARefusedDeleteStays() {
        compose.runOnUiThread { prompts.confirmDeleteSession("c1", "Fix flaky test") { fail("must not leave") } }
        compose.waitForIdle()
        alert("alert-cancel").performClick()
        compose.waitForIdle()
        alert("alert").assertDoesNotExist()
        assertTrue("cancel keeps the session", actions.calls.isEmpty())

        actions.outcome = ActionOutcome.EngineOffline
        compose.runOnUiThread { prompts.confirmDeleteSession("c1", "") { fail("a refused delete stays") } }
        compose.waitForIdle()
        compose.onNodeWithText("\u201CNew session\u201D will be permanently deleted. This can\u2019t be undone.")
            .assertExists()
        alert("alert-primary").performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete(c1)"), actions.calls)
    }

    // ---------------------------------------------------- Delete (confirm)

    @Test
    fun confirmDeleteSectionDeletesAfterTheDismissAndPopsOnOk() {
        var deleted = 0
        launch { prompts.confirmDeleteSection("backend", "Backend") { deleted++ } }

        node("action-sheet").assertExists()
        text("Delete “Backend”?").assertExists()
        text("Its sessions move back to the main list.").assertExists()
        text("Delete Section").assertExists()
        node("action-sheet-cancel").assertExists()

        pickRow("action-sheet-row-0")
        assertEquals(listOf("deleteSection(backend)"), actions.calls)
        assertEquals(1, deleted)
        node("action-sheet").assertDoesNotExist()
    }

    @Test
    fun confirmDeleteSectionPopsOnGoneToo() {
        actions.outcome = ActionOutcome.Gone
        var deleted = 0
        launch { prompts.confirmDeleteSection("backend", "Backend") { deleted++ } }
        pickRow("action-sheet-row-0")
        assertEquals(listOf("deleteSection(backend)"), actions.calls)
        assertEquals("Gone means the section is gone either way", 1, deleted)
    }

    @Test
    fun confirmDeleteSectionSkipsOnDeletedWhenTheEngineIsOffline() {
        actions.outcome = ActionOutcome.EngineOffline
        var deleted = 0
        launch { prompts.confirmDeleteSection("backend", "Backend") { deleted++ } }
        pickRow("action-sheet-row-0")
        assertEquals(listOf("deleteSection(backend)"), actions.calls)
        assertTrue("an offline engine must not pop the folder page", deleted == 0)
    }

    @Test
    fun cancelBackAndScrimTapNeverDelete() {
        compose.runOnUiThread { prompts.confirmDeleteSection("backend", "Backend") { fail("must not pop") } }
        compose.waitForIdle()
        node("action-sheet-cancel").performClick()
        compose.waitForIdle() // runs the 250 ms exit on the auto clock
        node("action-sheet").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())

        compose.runOnUiThread { prompts.confirmDeleteSection("backend", "Backend") { fail("must not pop") } }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) } // the scrim
        compose.waitForIdle()
        node("action-sheet").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())

        compose.runOnUiThread { prompts.confirmDeleteSection("backend", "Backend") { fail("must not pop") } }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        node("action-sheet").assertDoesNotExist()
        assertTrue(actions.calls.isEmpty())
    }

    // ---------------------------------------------------------- haptics

    @Test
    fun noLauncherPerformsAHapticOrACue() {
        actions.sections = listOf(section("backend", name = "Backend", engineId = "studio"))

        compose.runOnUiThread { prompts.renameSession(c1) }
        compose.waitForIdle()
        alert("alert-cancel").performClick()
        compose.waitForIdle()

        compose.runOnUiThread { prompts.newSection() } // the chooser
        compose.waitForIdle()
        node("action-sheet-cancel").performClick()
        compose.waitForIdle()

        compose.runOnUiThread { prompts.newSection("windows") } // the toast
        compose.waitForIdle()

        compose.runOnUiThread { prompts.renameSection("backend", "Backend") {} }
        compose.waitForIdle()
        alert("alert-cancel").performClick()
        compose.waitForIdle()

        compose.runOnUiThread { prompts.moveToSection(c1) }
        compose.waitForIdle()
        node("action-sheet-cancel").performClick()
        compose.waitForIdle()

        compose.runOnUiThread { prompts.confirmDeleteSection("backend", "Backend") {} }
        compose.waitForIdle()
        node("action-sheet-cancel").performClick()
        compose.waitForIdle()

        assertTrue("no iOS call site of these prompts creates a generator", feedback.haptics.isEmpty())
        assertTrue(feedback.cues.isEmpty())
    }
}

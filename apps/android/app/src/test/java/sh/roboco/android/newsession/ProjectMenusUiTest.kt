package sh.roboco.android.newsession

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GalleryBackdrop
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.SheetEntry
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle

/**
 * The Project / Run on menus' end-to-end behaviour (android-ui-parity
 * #113) — Robolectric + compose-ui-test over 92's real model with fakes
 * (the fixture options, an empty model catalog, `GalleryLabels`, a fake
 * `NewSessionPresenter` where the test needs the callback). The chip row
 * itself is 109's [NewSessionChipsEffect] + 59's strip, the panel is 15's.
 *
 * Ports of the iOS `SessionFlowTests`:
 *
 * - `testProjectPickerGroupsClonesAndHostPicksCheckout` (`iosui:Session/
 *   SessionFlowTests.swift:314-342`): one row for both clones, the host
 *   menu lists exactly the engines holding a checkout, and picking the
 *   other clone re-titles both chips;
 * - the New Project presentation (phone: stacked on the New Session
 *   sheet; embedded: from the root host) and the `onCreated` liveness
 *   guard.
 *
 * `Glass.forceFallback` follows the convention; the main clock runs
 * (`autoAdvance = true`) because the menu's open/close animation has to
 * settle between the taps. The phone presentation test uses the real
 * `rememberNewProjectPresenter` + 110's `presentNewProject` — the browser
 * body composes over the app's real (core-less, engine-less) graph, so
 * no FFI is touched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ProjectMenusUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun runClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = true
        Glass.forceFallback = true
        ModelCatalogCache.clear()
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ── the SessionFlowTests port ──────────────────────────────────────

    @Test
    fun theProjectPickerGroupsClonesAndHostPicksCheckout() {
        val model = newFixturePage()
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    ProjectMenusHarness(model, composer, NewProjectPresenter { })
                }
            }
        }
        compose.waitForIdle()

        // The project chip (the draft defaults to the first project, blog).
        compose.onNodeWithTag("composer-chip-project").performClick()
        compose.waitUntil(5_000) { nodesOf("project-menu-group").isNotEmpty() }
        // One entry for both clones (iOS: `label BEGINSWITH 'zeron'`,
        // case-sensitive — "Roboco Mobile" is a different group).
        val roboco = compose.onAllNodes(hasTestTag("project-menu-group") and hasText("roboco", substring = false))
        assertEquals("one entry for both clones", 1, roboco.fetchSemanticsNodes().size)
        roboco[0].performClick()
        compose.waitUntil(5_000) { nodesOf("glass-menu").isEmpty() }
        waitChipContains("composer-chip-project", "roboco")

        // The host chip: exactly the engines holding a roboco checkout.
        compose.onNodeWithTag("composer-chip-host").performClick()
        compose.waitUntil(5_000) { nodesOf("run-on-row").size == 2 }
        assertEquals(
            1,
            compose.onAllNodes(hasTestTag("run-on-row") and hasText("hetzner-01", substring = false))
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            1,
            compose.onAllNodes(hasTestTag("run-on-row") and hasText("MacBook Pro", substring = false))
                .fetchSemanticsNodes().size,
        )
        assertTrue(
            "only hosts with a checkout appear",
            compose.onAllNodes(hasTestTag("run-on-row") and hasText("Mac Studio", substring = true))
                .fetchSemanticsNodes().isEmpty(),
        )

        // Pick hetzner-01's checkout: the host chip changes, the project
        // chip keeps the group, and the draft holds the VPS checkout.
        compose.onAllNodes(hasTestTag("run-on-row") and hasText("hetzner-01", substring = false))[0].performClick()
        compose.waitUntil(5_000) { nodesOf("glass-menu").isEmpty() }
        waitChipContains("composer-chip-host", "hetzner-01")
        compose.onNodeWithTag("composer-chip-project").assertTextContains("roboco")
        assertEquals("e2:space-roboco-vps", model.draft.projectId)
        assertNull(model.draft.hostId)
    }

    @Test
    fun tappingTheHostChipWithNothingToShowOpensNoPanel() {
        val model = newFixturePage(MutableStateFlow(NewSessionOptions(emptyList(), emptyList())))
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    ProjectMenusHarness(model, composer, NewProjectPresenter { })
                }
            }
        }
        compose.waitForIdle()

        // runOn() is null: 59 falls through to the no-op chip tap, so no
        // title-only panel appears (UIKit's empty-menu behaviour).
        compose.onNodeWithTag("composer-chip-host").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertEquals("nothing changed", NewSessionDraft(), model.draft)
    }

    // ── the New Project presentation (110's overloads through the
    //    page's own presenter) ──────────────────────────────────────────

    @Test
    fun newProjectStacksASheetOnTheNewSessionSheetAndBlursTheComposer() {
        val model = newFixturePage()
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        val state = SheetHostState()
        compose.runOnUiThread {
            state.presentSettledForPreview("new-session", SheetStyle.Page(grabber = true)) {
                ProjectMenusSheetHarness(model, composer)
            }
        }
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    SheetHost(state) { GalleryBackdrop(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("focus-probe").assertIsFocused()

        // "New Project…" → 110's presentNewProject on the sheet's own scope.
        compose.onNodeWithTag("composer-chip-project").performClick()
        compose.waitUntil(5_000) { nodesOf("project-menu-new").isNotEmpty() }
        compose.onNodeWithTag("project-menu-new").performClick()
        compose.waitUntil(5_000) { nodesOf("page-sheet:new-project").isNotEmpty() }

        var entries: List<SheetEntry> = emptyList()
        compose.runOnUiThread { entries = state.entries }
        assertEquals("the browser stacks on the New Session sheet", 2, entries.size)
        assertEquals("new-project", entries[1].key)
        assertEquals(SheetStyle.Page(grabber = false), entries[1].style)
        compose.onNodeWithTag("page-sheet:new-session").assertExists()
        // The composer was blurred before presenting (the IME goes with it).
        compose.onNodeWithTag("focus-probe").assertIsNotFocused()
    }

    @Test
    fun embeddedNewProjectPresentsFromTheRootHost() {
        val model = newFixturePage()
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        val state = SheetHostState()
        // Inside SheetHost's root content there is no sheet scope (106's
        // embedded column): the presenter must go through LocalSheetHost.
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    SheetHost(state) {
                        ProjectMenusHarness(model, composer, rememberNewProjectPresenter(), focusProbe = true)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("focus-probe").assertIsFocused()

        compose.onNodeWithTag("composer-chip-project").performClick()
        compose.waitUntil(5_000) { nodesOf("project-menu-new").isNotEmpty() }
        compose.onNodeWithTag("project-menu-new").performClick()
        compose.waitUntil(5_000) { nodesOf("page-sheet:new-project").isNotEmpty() }

        var entries: List<SheetEntry> = emptyList()
        compose.runOnUiThread { entries = state.entries }
        assertEquals("the embedded page presents through the root host", 1, entries.size)
        assertEquals("new-project", entries[0].key)
        assertEquals(SheetStyle.Page(grabber = false), entries[0].style)
    }

    @Test
    fun onCreatedAppliesTheCreatedProjectOnlyWhileThePageIsAlive() {
        val options = MutableStateFlow<NewSessionOptions?>(projectMenusFixtureOptions())
        val model = newFixturePage(options)
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        var captured: ((String) -> Unit)? = null
        val presenter = NewProjectPresenter { onCreated -> captured = onCreated }
        var showPage by mutableStateOf(true)

        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    if (showPage) ProjectMenusHarness(model, composer, presenter)
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("composer-chip-project").performClick()
        compose.waitUntil(5_000) { nodesOf("project-menu-new").isNotEmpty() }
        compose.onNodeWithTag("project-menu-new").performClick()
        compose.waitUntil(5_000) { nodesOf("glass-menu").isEmpty() }
        assertNotNull("the presenter got the callback", captured)

        // android-app#09's poll: the new space is in the options by the
        // time onCreated fires; a branch the create must clear.
        compose.runOnUiThread {
            options.value = NewSessionOptions(
                projectMenusFixtureProjects() + projectMenusNewProjectOption(),
                projectMenusFixtureHosts(),
            )
            model.update { it.copy(branch = "main") }
        }
        compose.waitForIdle()
        compose.runOnUiThread { captured!!("e2:space-new") }
        compose.waitForIdle()
        assertEquals("e2:space-new", model.draft.projectId)
        assertNull(model.draft.hostId)
        assertNull(model.draft.branch)

        // The page is gone (the iOS [weak self] guard): the callback is inert.
        showPage = false
        compose.waitForIdle()
        compose.runOnUiThread { captured!!("e1:space-mobile") }
        compose.waitForIdle()
        assertEquals("e2:space-new", model.draft.projectId)
    }

    // ── the harness and the fakes ──────────────────────────────────────

    /**
     * The page's chip wiring: 109's effect + 59's strip over this ticket's
     * [ProjectRunOnMenus], with the page's own liveness guard (its
     * [DisposableEffect] flips it when the harness leaves composition).
     */
    @Composable
    private fun ProjectMenusHarness(
        model: NewSessionModel,
        composer: ComposerController,
        presenter: NewProjectPresenter,
        focusProbe: Boolean = false,
    ) {
        val alive = remember { AtomicBoolean(true) }
        DisposableEffect(Unit) { onDispose { alive.set(false) } }
        val projectMenus = remember { ProjectRunOnMenus(model, presenter, alive::get) }
        // Remembered like NewSessionScreenBody's map: providers() builds fresh
        // lambdas per call, so an unremembered map is never equal, restarts
        // the effect's LaunchedEffect(composer, menus) and its chipMenus write
        // recomposes this harness every frame (waitForIdle never settles).
        val menus = remember(projectMenus) { newSessionChipMenus(projectMenus.providers()) }
        NewSessionChipsEffect(model, composer, menus, labels = GalleryLabels)
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            if (focusProbe) ProjectMenusFocusProbe()
            ChipStrip(composer.chips, composer.chipMenus, canWrite = true, onChipTap = {})
        }
    }

    /** The sheet-entry form: the real presenter, from inside the sheet. */
    @Composable
    private fun ProjectMenusSheetHarness(model: NewSessionModel, composer: ComposerController) {
        ProjectMenusHarness(model, composer, rememberNewProjectPresenter(), focusProbe = true)
    }

    /** A focusable target standing in for the composer's field. */
    @Composable
    private fun ProjectMenusFocusProbe() {
        val requester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        Box(Modifier.testTag("focus-probe").size(64.dp).focusRequester(requester).focusable())
    }

    /** 92's fake catalog: no built-ins, no fetches (no FFI on the JVM). */
    private object ProjectMenusUiEmptyCatalog : ModelCatalogSource {
        override fun builtIn(): List<ModelChoice> = emptyList()
        override suspend fun fetch(deviceId: String): List<ModelChoice> = emptyList()
    }

    /** A page over the fakes: Unconfined everywhere, like 109's binding test. */
    private fun newFixturePage(
        options: MutableStateFlow<NewSessionOptions?> = MutableStateFlow(projectMenusFixtureOptions()),
    ): NewSessionModel {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val drafts = Drafts(FakePrefsStore(), scope, Dispatchers.Unconfined)
        val store = NewSessionDraftStore(
            FakePrefsStore(),
            drafts,
            scope,
            io = Dispatchers.Unconfined,
            launchHarness = { null },
        )
        return NewSessionModel(
            prompt = null,
            store = store,
            options = options,
            catalog = ProjectMenusUiEmptyCatalog,
            pageScope = scope,
            appScope = scope,
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
    }

    private fun nodesOf(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()

    /** Waits until the chip's merged text contains [text] (a pick's next frame). */
    private fun waitChipContains(tag: String, text: String) {
        compose.waitUntil(5_000) {
            compose.onNodeWithTag(tag).fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true
        }
    }
}

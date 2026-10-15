package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ChipStrip
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.Drafts
import sh.roboco.android.composer.FakePrefsStore
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * The New Session chip set's goldens and binding (android-ui-parity #109):
 *
 * - the "New-session chips" gallery section in light and dark on a
 *   420 × 900 dp mdpi window (the fixtures render through ticket 59's
 *   [ChipStrip] over [GalleryLabels], so nothing here touches the native
 *   library). In the golden: fixture B's host chip carries the warning
 *   fill and ink, A's Claude mark stays `#D97757` in both themes, B's
 *   codex mark takes the chip ink, and A's effort chip reads `Xhigh`
 *   (the `efforts[7 / 2]` default through [String.swiftCapitalized]).
 *   Ticket 22's parameterized `GalleryScreenshotTest` also records
 *   `gallery_new_session_chips_{light,dark}.png` from the same section
 *   through the registry.
 * - the [NewSessionChipsEffect] binding over a fake [NewSessionModel]
 *   input set (93's `newPage` shape: fakes for the store, the catalog and
 *   the options): the first composition already seeds `composer.chips`
 *   (no empty first frame), a draft change updates the effort chip in the
 *   next frame, `chipMenus` holds exactly the five ids, and the effect
 *   never assigns `onChipTap` or `canWrite` (93 and 94 own them).
 *
 * Robolectric with NATIVE graphics; [Glass.forceFallback] and the frozen
 * main clock follow the gallery golden convention (tickets 07/22). Record
 * with `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.newsession.NewSessionChipsScreenshotTest`, verify with
 * `:app:verifyRoborazziDebug --tests …`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w420dp-h900dp-mdpi")
class NewSessionChipsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun stableGoldens() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ModelCatalogCache.clear()
    }

    @After
    fun restoreTiers() {
        Glass.forceFallback = false
    }

    // ── goldens ─────────────────────────────────────────────────────────

    @Test
    fun galleryLight() {
        goldenShot(Appearance.Light, "newsession/newsession_chips_light.png")
    }

    @Test
    fun galleryDark() {
        goldenShot(Appearance.Dark, "newsession/newsession_chips_dark.png")
    }

    private fun goldenShot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background).padding(16.dp)) {
                    NewSessionChipsGallerySection()
                }
            }
        }
        compose.waitForIdle()
        // One settle frame before the capture (the frozen-clock recipe).
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    // ── the binding (NewSessionChipsEffect) ─────────────────────────────

    @Test
    fun theEffectSeedsChipsInCompositionAndTracksDraftChanges() {
        val model = newBindingPage()
        val composer = ComposerController(CoroutineScope(Dispatchers.Unconfined), NoFeedback)
        var taps = 0
        val noTap: (String) -> Unit = { taps++ }
        composer.onChipTap = noTap
        composer.canWrite = false // 94's flag: the effect must never write it
        val menus = newSessionChipMenus()

        // What the strip saw at its first composition — the seed must have
        // run before it, so this is the full list, not an empty one.
        val firstCompositionChips = mutableListOf<List<ComposerChip>>()

        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    NewSessionChipsEffect(model, composer, menus, labels = GalleryLabels)
                    remember {
                        firstCompositionChips += composer.chips
                        true
                    }
                    ChipStrip(composer.chips, emptyMap(), canWrite = true, onChipTap = {})
                }
            }
        }
        // Settle the first frames (the glass flag flip included) — the
        // frozen-clock host recipe.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val expected = newSessionChips(
            model.draft,
            model.project,
            model.currentOptions?.hosts.orEmpty(),
            model.models,
            GalleryLabels,
        )
        assertEquals(expected, composer.chips)
        assertEquals("the first composition already had the chips", 1, firstCompositionChips.size)
        assertEquals(expected, firstCompositionChips.single())
        assertEquals(NewSessionChipIds.All, composer.chipMenus.keys.toList())

        compose.onNodeWithTag("composer-chip-effort").assertTextContains("Xhigh", substring = true)
        compose.onNodeWithTag("composer-chip-model").assertTextContains("Fable 5.1", substring = true)
        compose.onNodeWithTag("composer-chip-project").assertTextContains("roboco", substring = true)

        // A menu pick (113/114's path) changes only the draft; the chips
        // follow — the draft write, the snapshotFlow collector's dispatch
        // hop, then the strip recomposition, so three frames cover every
        // hop deterministically (the frozen-clock state recipe).
        compose.runOnUiThread { model.update { it.copy(effort = "low") } }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-chip-effort").assertTextContains("Low", substring = true)

        // The effect owns only chips and chipMenus.
        assertSame("onChipTap stays 93's", noTap, composer.onChipTap)
        assertEquals(0, taps)
        assertFalse("canWrite stays 94's", composer.canWrite)
    }

    // ── the fake page (93's newPage shape) ──────────────────────────────

    /** The one project the fake options list (the gallery's fixture A). */
    private val BindingProject = ProjectOption(
        id = "p1",
        name = "roboco",
        path = "/srv/roboco",
        device = "d1",
        deviceName = "Workstation",
        online = true,
        git = true,
        groupKey = "repo:r",
        groupName = "roboco",
        colorIndex = 2,
    )

    /** 92's fake catalog: the two Claude rows, no fetches (no FFI on the JVM). */
    private class BindingCatalog : ModelCatalogSource {
        override fun builtIn(): List<ModelChoice> = listOf(
            ModelChoice(
                "claude-code",
                "Claude Code",
                "claude-fable-5-1",
                "Fable 5.1",
                listOf("low", "medium", "high", "xhigh", "max", "ultracode", "ultrathink"),
            ),
            ModelChoice("claude-code", "Claude Code", "claude-haiku-4-5", "Haiku 4.5", emptyList()),
        )

        override suspend fun fetch(deviceId: String): List<ModelChoice> = emptyList()
    }

    /** A page over the fakes: an options flow with the one project, Unconfined everywhere. */
    private fun newBindingPage(): NewSessionModel {
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
            options = MutableStateFlow(NewSessionOptions(projects = listOf(BindingProject), hosts = emptyList())),
            catalog = BindingCatalog(),
            pageScope = scope,
            appScope = scope,
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
        )
    }
}

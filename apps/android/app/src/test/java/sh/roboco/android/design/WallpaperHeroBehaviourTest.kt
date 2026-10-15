package sh.roboco.android.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wallpaper hero's behaviour (android-ui-parity #21) through the
 * testable core ([WallpaperHero] with a gated fake `load`): the
 * fade-in/snap rules of `Wallpaper.swift:230-240`, the tag's presence, the
 * touch pass-through and the not-a-TalkBack-node rule.
 *
 * Robolectric + compose-ui-test with `mainClock.autoAdvance = false`,
 * exactly the `ToastHostTest` method: the fake load parks on a
 * [CompletableDeferred] the test opens, so the render "arrives" exactly when
 * the test says so. The clock timings below are playtime — the fade's
 * launch costs one frame of alignment, so each timing advances single
 * frames until the value first ticks, then clocks the ticket's ms.
 *
 * `RobocoTheme` provides `LocalReduceMotion` (ticket 04's wrap);
 * `ReduceMotion.debugOverride` is process-wide, hence the [After] reset.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
class WallpaperHeroBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture by lazy { wallpaperFixture() }

    private var changeKey by mutableStateOf(0)
    private var appearance by mutableStateOf(Appearance.Light)

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    @After
    fun reset() {
        ReduceMotion.resetForTest()
    }

    // ------------------------------------------------------------ helpers

    /**
     * The store seam: every `load` call parks on a gate the test opens with
     * [deliver]. Records the `dark` flag each call arrived with.
     */
    private class GatedLoad {
        private var gate = CompletableDeferred<WallpaperRender?>()
        val darks = mutableListOf<Boolean>()

        suspend fun load(dark: Boolean): WallpaperRender? {
            darks += dark
            val render = gate.await()
            gate = CompletableDeferred()
            return render
        }

        fun deliver(render: WallpaperRender?) {
            gate.complete(render)
        }
    }

    private fun mount(load: suspend (Boolean) -> WallpaperRender?) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize()) {
                    WallpaperHero(changeKey = changeKey, load = load, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Lets an arrival land on the frozen clock: the gate's resumption runs
     * on the main Looper (waitForIdle), the `render` / key writes are
     * applied (`sendApplyNotifications`, normally posted to the paused
     * Looper), and one frame recomposes — the node appears, a keyed effect
     * relaunches, a fade's first frame records its start. Without the
     * frame, a frozen clock never recomposes and the tag never exists.
     */
    private fun land() {
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** The hero's current `appear` value, read off the tagged node. */
    private fun appear(): Float = compose.onNodeWithTag("wallpaper").fetchSemanticsNode().config[WallpaperAppearKey]

    /** Advances single frames until the fade first ticks (16 ms of playtime). */
    private fun advanceToFirstTick() {
        var frames = 0
        while (appear() <= 0.001f && frames < 12) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("the fade never ticked within 12 frames", frames < 12)
    }

    /** A settled first render: delivered, faded in, clock run past 120 ms. */
    private fun settledFirstRender(fake: GatedLoad) {
        fake.deliver(WallpaperRender(fixture, 1f))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1f, appear(), 1e-4f)
    }

    // -------------------------------------------------------- presence

    @Test
    fun whenLoadReturnsNullNoNodeIsComposed() {
        val fake = GatedLoad()
        mount(fake::load)
        fake.deliver(null)
        compose.waitForIdle()
        // No wallpaper set (including while the very first render loads):
        // nothing is drawn, `testTag("wallpaper")` does not exist.
        compose.onNodeWithTag("wallpaper").assertDoesNotExist()
    }

    // ------------------------------------------------------ the fade-in

    @Test
    fun theFirstRenderFadesInOverOneHundredTwentyMs() {
        val fake = GatedLoad()
        mount(fake::load)
        fake.deliver(WallpaperRender(fixture, 1f))
        land()
        assertEquals("appear is 0 right after arrival", 0f, appear(), 1e-4f)

        // The launch costs one frame: step single frames until the fade's
        // first tick (16 ms of playtime), then clock +44 ms ≈ the ticket's
        // "+60 ms" — easeInOut(0.5) = 0.5, mid-fade.
        advanceToFirstTick()
        compose.mainClock.advanceTimeBy(44)
        compose.waitForIdle()
        val mid = appear()
        assertTrue("appear at +60 ms of playtime is $mid", mid > 0.3f && mid < 0.7f)

        // The rest of the 120 ms plus one frame: settled at exactly 1.
        compose.mainClock.advanceTimeBy(76)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(1f, appear(), 1e-4f)
    }

    @Test
    fun reduceMotionSnapsTheFirstRenderIn() {
        ReduceMotion.debugOverride = true
        val fake = GatedLoad()
        mount(fake::load)
        fake.deliver(WallpaperRender(fixture, 1f))
        land()
        assertEquals("the first render appears at once under reduce motion", 1f, appear(), 1e-4f)
    }

    // ------------------------------------------------------ later loads

    @Test
    fun aLaterRenderArrivesWithoutAFade() {
        val fake = GatedLoad()
        mount(fake::load)
        settledFirstRender(fake)

        // The store changed: the old artwork stays until the new one lands.
        compose.runOnIdle { changeKey++ }
        land() // the keyed effect relaunches and parks on the gate
        compose.onNodeWithTag("wallpaper").assertExists()
        fake.deliver(WallpaperRender(fixture, 0.8f))
        land()
        compose.onNodeWithTag("wallpaper").assertExists()
        assertEquals("a later render snaps in", 1f, appear(), 1e-4f)
        // A half-fade would have shown mid-values here.
        compose.mainClock.advanceTimeBy(60)
        compose.waitForIdle()
        assertEquals("no intermediate value: the later render never re-fades", 1f, appear(), 1e-4f)
    }

    @Test
    fun anAppearanceFlipReloadsWithTheNewDarkFlagAndSnaps() {
        val fake = GatedLoad()
        mount(fake::load)
        settledFirstRender(fake)
        assertEquals(listOf(false), fake.darks)

        compose.runOnIdle { appearance = Appearance.Dark }
        land() // recompose: the (changeKey, dark) effect relaunches with dark = true
        // The old render stays while the reload runs.
        compose.onNodeWithTag("wallpaper").assertExists()
        fake.deliver(WallpaperRender(fixture, 0.8f))
        land()
        assertEquals("the reload used the new appearance", listOf(false, true), fake.darks)
        assertEquals("a dark flip snaps, no cross-fade", 1f, appear(), 1e-4f)
    }

    @Test
    fun aRenderAfterARemovalFadesInAgain() {
        val fake = GatedLoad()
        mount(fake::load)
        settledFirstRender(fake)

        // The wallpaper is removed: the hero disappears at once.
        compose.runOnIdle { changeKey++ }
        land()
        fake.deliver(null)
        land()
        compose.onNodeWithTag("wallpaper").assertDoesNotExist()

        // The next non-null render counts as "first" again and fades in.
        compose.runOnIdle { changeKey++ }
        land()
        fake.deliver(WallpaperRender(fixture, 1f))
        land()
        assertEquals("0 at arrival", 0f, appear(), 1e-4f)
        compose.mainClock.advanceTimeBy(60)
        compose.waitForIdle()
        assertTrue("still mid-fade at +60 ms: ${appear()}", appear() < 1f)
    }

    // ------------------------------------------------------- touch, a11y

    @Test
    fun tapsFallThroughToWhateverLiesUnderneath() {
        var clicks = 0
        val fake = GatedLoad()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    // The content underneath, then the hero on top of it.
                    Box(Modifier.fillMaxSize().clickable { clicks++ })
                    WallpaperHero(changeKey = changeKey, load = fake::load, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        fake.deliver(WallpaperRender(fixture, 1f))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400) // fully faded in and drawn
        compose.waitForIdle()

        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("the hero never blocks taps on content overlapping it", 1, clicks)
    }

    @Test
    fun theHeroIsNotATalkBackNode() {
        val fake = GatedLoad()
        mount(fake::load)
        settledFirstRender(fake)
        val config = compose.onNodeWithTag("wallpaper").fetchSemanticsNode().config
        assertFalse("no ContentDescription", config.contains(SemanticsProperties.ContentDescription))
        assertFalse("no Role", config.contains(SemanticsProperties.Role))
        assertFalse("no click action", config.contains(SemanticsActions.OnClick))
    }
}

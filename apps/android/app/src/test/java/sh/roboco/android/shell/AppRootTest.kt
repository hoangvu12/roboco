package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The window root's selection and swap rules (android-ui-parity #35):
 * `Loading → Ready` composes the first root without animation (the
 * `SceneDelegate.swift:95-97` first-root rule), a width change across
 * 840 dp switches phone/split **in place** — no fade, no frame where both
 * shells are composed — `AppRootState.kind` follows, `CoreFailed` shows
 * the diagnostic screen, and `RegisterRootPresentation` only registers
 * while active.
 */
@RunWith(RobolectricTestRunner::class)
// A window wider than every frame below: Modifier.width is clamped to the
// incoming constraints (Robolectric's default window is 320 dp wide).
@Config(sdk = [36], qualifiers = "w1280dp-h900dp")
class AppRootTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = AppRootState()
    private val input = mutableStateOf<RootInput>(RootInput.Loading)
    private val width = mutableStateOf(900.dp)
    private val presentations = RootPresentations()

    private fun start() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val palette = RobocoTheme.palette
                Box(Modifier.width(width.value).height(800.dp)) {
                    AppRoot(
                        input = input.value,
                        state = state,
                        presentations = presentations,
                        pairing = { FakeRoot("Pairing", palette.accent, palette.text) },
                        phone = { FakeRoot("Phone", palette.background, palette.text) },
                        split = { FakeRoot("Split", palette.codeBackground, palette.text) },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // --------------------------------------------------- the first root

    @Test
    fun loadingDrawsNothingAndReadyDrawsTheFirstRootWithoutAnimation() {
        // A phone-width frame: the class default (900 dp) is the split now
        // that the 1280 dp window no longer clamps it to 320 dp.
        width.value = 600.dp
        start()
        assertNull(state.kind)
        compose.onNodeWithTag("root-pairing").assertDoesNotExist()
        compose.onNodeWithTag("root-phone").assertDoesNotExist()
        compose.onNodeWithTag("root-split").assertDoesNotExist()

        compose.runOnUiThread { input.value = RootInput.Ready(hasEngines = true) }
        compose.waitForIdle()

        // The first root appears at alpha 1 on its first frame: no leaving
        // layer, no input blocker (RootCrossfade is first composed with
        // shown = target).
        compose.onNodeWithTag("root-phone").assertExists()
        compose.onNodeWithTag("root-pairing").assertDoesNotExist()
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
        assertEquals(RootKind.Phone, state.kind)
    }

    // -------------------------------------------------- the width switch

    @Test
    fun aWidthChangeAcrossTheBreakpointSwitchesInPlaceWithoutAFade() {
        input.value = RootInput.Ready(hasEngines = true)
        start()

        compose.onNodeWithTag("root-split").assertExists()
        compose.onNodeWithTag("root-phone").assertDoesNotExist()
        assertEquals(RootKind.Split, state.kind)

        // 900 → 600: an in-place collapse — both shells are never composed
        // at once and no fade starts (the shell group never changes).
        compose.runOnUiThread { width.value = 600.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("root-phone").assertExists()
        compose.onNodeWithTag("root-split").assertDoesNotExist()
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
        assertEquals(RootKind.Phone, state.kind)

        // 600 → 900: an in-place expand, same rules.
        compose.runOnUiThread { width.value = 900.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("root-split").assertExists()
        compose.onNodeWithTag("root-phone").assertDoesNotExist()
        compose.onNodeWithTag("root-swap-blocker").assertDoesNotExist()
        assertEquals(RootKind.Split, state.kind)
    }

    @Test
    fun exactly744DpSplitsAnd743DpIsThePhone() {
        input.value = RootInput.Ready(hasEngines = true)
        width.value = 744.dp
        start()
        compose.onNodeWithTag("root-split").assertExists()

        compose.runOnUiThread { width.value = 743.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("root-phone").assertExists()
        compose.onNodeWithTag("root-split").assertDoesNotExist()
    }

    // -------------------------------------------------- the core failure

    @Test
    fun coreFailedShowsTheDiagnosticScreenAndNeverThePairingRoot() {
        input.value = RootInput.CoreFailed("libroboco_core.so not found")
        start()

        compose.onNodeWithTag("root-core-failed").assertExists()
        compose.onNodeWithTag("root-pairing").assertDoesNotExist()
        compose.onNodeWithText("Couldn't load the Roboco core: libroboco_core.so not found")
            .assertExists()
        assertNull(state.kind)
    }

    // --------------------------------------- the presentation registry

    @Test
    fun registerRootPresentationOnlyRegistersWhileActive() {
        var dismissedInactive = 0
        var dismissedActive = 0
        compose.setContent {
            CompositionLocalProvider(LocalRootPresentations provides presentations) {
                Box {
                    RegisterRootPresentation(active = false) { dismissedInactive++ }
                    RegisterRootPresentation(active = true) { dismissedActive++ }
                }
            }
        }
        compose.waitForIdle()

        presentations.dismissAllInstantly()
        assertEquals("inactive registers nothing", 0, dismissedInactive)
        assertEquals(1, dismissedActive)
    }
}

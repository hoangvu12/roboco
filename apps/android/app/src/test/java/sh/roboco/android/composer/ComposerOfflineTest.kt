package sh.roboco.android.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.chrisbanes.haze.rememberHazeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The composer's offline behaviour (android-ui-parity #79): the whole
 * [ComposerBar] exactly as ticket 78's gallery fixture assembles it, with
 * `canWrite = false` and a running turn holding a draft —
 *
 * - `composer-send` is not enabled, draws the disabled **send** look (the
 *   a11y label stays "Send message" — the drawn glyph is the arrow, not
 *   Stop) and a click invokes neither `onSend` nor `onStop`;
 * - the typed text is untouched (the offline mode never touches text,
 *   images or focus);
 * - the chips are inert: the tap is consumed but the `chipMenus` provider
 *   (and `onChipTap`) never runs;
 * - the attach `+` stays enabled — staging is local, the upload only
 *   happens inside a send (android-app#08).
 *
 * Robolectric + compose-ui-test (the ComposerActionButtonTest
 * conventions: software glass, mdpi window). The visuals of the disabled
 * look are 58's `ComposerActionButtonTest`; the chips' disabled paint is
 * 59's `ChipStripTest` — this class covers the wiring 79 feeds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
class ComposerOfflineTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun forceFallbackTier() {
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    @Test
    fun offlineComposerKeepsTextButBlocksSendStopAndChipMenus() {
        var sends = 0
        var stops = 0
        var provided = 0
        var taps = 0
        val modelMenu: ChipMenuProvider = {
            provided++
            null
        }

        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // The session screen's host stack: the menu host the chip
                // strip needs, the haze state and the mention host 78's
                // ComposerBar registers with.
                GlassMenuHost(hazeState = rememberHazeState()) {
                    ProvideHazeState {
                        MentionSuggestionsHost(Modifier.fillMaxWidth()) {
                            val scope = rememberCoroutineScope()
                            val controller = remember(scope) {
                                ComposerController(scope, NoFeedback, initialText = "x").apply {
                                    running = true
                                    canWrite = false
                                    alwaysCard = true
                                    chips = listOf(
                                        ComposerChip("model", "Fable 5", icon = ChipIcon.Brand("mock")),
                                        ComposerChip(
                                            "effort",
                                            "High",
                                            symbol = "gauge.with.dots.needle.67percent",
                                        ),
                                    )
                                    chipMenus = mapOf("model" to modelMenu)
                                    onSend = { _, _, _ ->
                                        sends++
                                        SendResult.Sent
                                    }
                                    onStop = { stops++ }
                                    onChipTap = { taps++ }
                                }
                            }
                            ComposerBar(
                                controller,
                                rememberComposerMorphState(
                                    alwaysCard = true,
                                    hasImages = { controller.images.isNotEmpty() },
                                ),
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // running + a draft would derive action Queue, but offline every
        // action renders the disabled send look: the a11y label is
        // "Send message", the node is disabled.
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()

        // A click does nothing: neither onSend nor onStop runs.
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitForIdle()
        assertEquals("onSend must not run while offline", 0, sends)
        assertEquals("onStop must not run while offline", 0, stops)

        // The typed text is untouched.
        assertEquals(
            "the draft stays",
            "x",
            compose.onNodeWithTag("composer-input")
                .fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )

        // The chips are inert: the tap is consumed, but neither the menu
        // provider nor onChipTap runs.
        compose.onNodeWithTag("composer-chip-model").assertIsNotEnabled()
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitForIdle()
        assertEquals("the menu provider must not run while offline", 0, provided)
        assertEquals("onChipTap must not run while offline", 0, taps)

        // The attach + stays enabled (staging is local).
        compose.onNodeWithTag("composer-attach").assertIsEnabled()
    }
}

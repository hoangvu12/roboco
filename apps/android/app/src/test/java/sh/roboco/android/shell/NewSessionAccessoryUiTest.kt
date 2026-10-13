package sh.roboco.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlyphClock
import sh.roboco.android.design.LocalGlyphClock
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The accessory's semantics and click behaviour (android-ui-parity #27):
 * one merged button node carrying the counts label, no child text nodes,
 * the whole 48 dp capsule as the hit area, cancel-on-leave, the `enabled`
 * gate, and the glyph clock's subscription rule. The visual half of the
 * acceptance criteria (the 0.6 press dim, the inline label's alpha, the
 * `labelAlpha` hook) is draw-phase-only and lives in the Roborazzi goldens
 * and the device review.
 *
 * Robolectric + compose-ui-test at `w402dp-h400dp-xhdpi` (the golden's
 * frame), the clock frozen wherever the animation itself is not under
 * test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h400dp-xhdpi")
class NewSessionAccessoryUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val tag = NewSessionAccessoryMetrics.TestTag

    /** Composes the accessory at the top of the frame, on the page background. */
    private fun host(
        counts: LiveCounts,
        onTap: () -> Unit = {},
        inline: Boolean = false,
        labelAlpha: (() -> Float)? = null,
        enabled: Boolean = true,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize()) {
                        NewSessionAccessory(
                            counts = counts,
                            onTap = onTap,
                            inline = inline,
                            labelAlpha = labelAlpha,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    private fun nodeCenter(): Offset {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.positionInRoot + Offset(node.size.width / 2f, node.size.height / 2f)
    }

    // ------------------------------------------------------------ semantics

    @Test
    fun oneButtonNodeCarriesTheCountsLabel() {
        host(LiveCounts(working = 2, awaiting = 1))
        compose.onNodeWithTag(tag)
            .assert(hasButtonRole())
            .assertIsEnabled()
            .assertHeightIsEqualTo(NewSessionAccessoryMetrics.Height)
            .assertWidthIsEqualTo(402.dp) // fillMaxWidth over the w402dp frame
        compose.onNodeWithContentDescription("New session, 2 working, 1 needs you").assertExists()
    }

    @Test
    fun idleCountsUseThePlainLabel() {
        host(LiveCounts())
        compose.onNodeWithContentDescription("New session").assertExists()
        compose.onNodeWithContentDescription("New session, 2 working").assertDoesNotExist()
    }

    @Test
    fun noChildTextNodesAreExposed() {
        host(LiveCounts(working = 2, awaiting = 1))
        // clearAndSetSemantics clears the descendants: the label and the
        // summary are announced only through the button's label.
        assertEquals(0, compose.onAllNodesWithText("New session").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("2 working \u00B7 1 needs you").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------ taps

    @Test
    fun aTapFiresOnTapExactlyOnce() {
        var taps = 0
        host(LiveCounts(), onTap = { taps++ })
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun theWholeCapsuleHeightIsTheHitArea() {
        var taps = 0
        host(LiveCounts(), onTap = { taps++ })
        // 2 dp from the capsule's top edge: the whole 48 dp height is one
        // button (MainTabController.swift:111, 298).
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val nearTop = node.positionInRoot + Offset(20f, 4f)
        compose.onRoot().performTouchInput { click(nearTop) }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun releasingOutsideTheCapsuleCancelsWithoutFiring() {
        var taps = 0
        host(LiveCounts(), onTap = { taps++ })
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val outside = node.positionInRoot + Offset(0f, node.size.height + 100f)
        compose.onRoot().performTouchInput {
            down(nodeCenter())
            advanceEventTime(200) // held: past the 150 ms press dim
            moveTo(outside)
            up()
        }
        compose.waitForIdle()
        assertEquals("a release outside the capsule is a cancel, not a tap", 0, taps)
    }

    @Test
    fun aDisabledAccessoryIgnoresTaps() {
        var taps = 0
        host(LiveCounts(), onTap = { taps++ }, enabled = false)
        compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onRoot().performTouchInput { click(nodeCenter()) }
        compose.waitForIdle()
        assertEquals(0, taps)
    }

    // ------------------------------------------------------------ the clock

    @Test
    fun idleCountsSubscribeNothingToTheGlyphClock() {
        var clock: GlyphClock? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock {
                    clock = LocalGlyphClock.current
                    NewSessionAccessory(counts = LiveCounts(), onTap = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, requireNotNull(clock).subscribers)
    }

    @Test
    fun theSpinnerSubscribesAndTheDotDoesNot() {
        var counts by mutableStateOf(LiveCounts())
        var clock: GlyphClock? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Frozen: the subscription count is the same on a frozen
                // clock (StatusGlyph's DisposableEffect), but a live clock
                // runs its withFrameNanos loop forever while the spinner is
                // subscribed — by design — so waitForIdle never idles.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    clock = LocalGlyphClock.current
                    NewSessionAccessory(counts = counts, onTap = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals("idle: no glyph is composed", 0, requireNotNull(clock).subscribers)

        compose.runOnUiThread { counts = LiveCounts(working = 2) }
        compose.waitForIdle()
        assertEquals("a spinner holds the shared clock", 1, requireNotNull(clock).subscribers)

        compose.runOnUiThread { counts = LiveCounts(awaiting = 1) }
        compose.waitForIdle()
        assertEquals("a dot never subscribes", 0, requireNotNull(clock).subscribers)

        compose.runOnUiThread { counts = LiveCounts() }
        compose.waitForIdle()
        assertEquals("the empty slot releases the clock", 0, requireNotNull(clock).subscribers)
    }

    // ------------------------------------------------------------- inline

    @Test
    fun inlineAndLabelAlphaChangeOnlyTheDrawingNotTheSemantics() {
        // The label is hidden in the draw phase (graphicsLayer alpha); the
        // semantics label is the counts label either way, and the button
        // still taps.
        // One setContent (a compose rule allows only one per test); the
        // second configuration is switched in through state.
        var inlineTaps = 0
        var inline by mutableStateOf(true)
        var labelAlpha by mutableStateOf<(() -> Float)?>(null)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize()) {
                        NewSessionAccessory(
                            counts = LiveCounts(awaiting = 1),
                            onTap = { inlineTaps++ },
                            inline = inline,
                            labelAlpha = labelAlpha,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New session, 1 needs you").assertExists()
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        assertEquals(1, inlineTaps)

        compose.runOnUiThread {
            inline = false
            labelAlpha = { 0.5f }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New session, 1 needs you").assertExists()
    }
}

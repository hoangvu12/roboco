package sh.roboco.android.pairing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.BusySpinnerTag

/**
 * The pairing root's geometry and behaviour (android-ui-parity #34) —
 * Robolectric + compose-ui-test on the ticket's device
 * (`w393dp-h852dp-xxhdpi`, safe insets 59/34 injected via [PairingRoot]'s
 * `safeInsets`, since Robolectric reports zero system-bar insets):
 *
 * - the root fills the whole window (the top stack is centred on the full
 *   window, centre Y = 346 = 852 / 2 − 80, never the safe area);
 * - the mark is 44 × 44 at top ≈ 278.85, the title/tagline line boxes are
 *   44.2 / 22.1 and unchanged at font scale 2.0 (`fixed` sizes);
 * - the capsule spans x 24…369, bottom 794, height ≈ 52, and the status
 *   line (when present) is full-width, 12 dp above it;
 * - `None` composes no status node, `Redeeming` shows the redeeming copy
 *   with ticket 08's busy spinner and ignores capsule taps, `Error` shows
 *   the message verbatim and taps pair;
 * - zero insets move only the bottom stack; a 300 dp window wraps the
 *   tagline to two centred lines inside the 24 dp margins;
 * - semantics: `pair-engine` is a `Role.Button`, the mark is decorative,
 *   the status line is a polite live region; the status → copy/colour
 *   mapping is pinned by the plain-JVM [PairingRootStatusLineTest]
 *   (the drawn pixels are the Roborazzi goldens' job).
 *
 * Bounds are read as Dp (`getBoundsInRoot`), so the assertions are the
 * ticket's dp numbers directly. The busy state runs on a frozen clock —
 * the spinner's frame loop would otherwise chase `waitForIdle` forever.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PairingRootTest {

    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0

    /** The ticket's phone: safe insets top 59 / bottom 34, sides 0. */
    private val phoneInsets = WindowInsets(top = 59.dp, bottom = 34.dp)

    // ------------------------------------------------------------ fixtures

    /** Hosts the root in the light theme; [freezeClock] pins the spinner. */
    private fun host(
        status: PairingRootStatus,
        insets: WindowInsets = phoneInsets,
        freezeClock: Boolean = false,
    ) {
        if (freezeClock) compose.mainClock.autoAdvance = false
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                PairingRoot(status = status, onPair = { clicks++ }, safeInsets = insets)
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private fun textBounds(value: String): DpRect =
        compose.onNodeWithText(value).getBoundsInRoot()

    /** The node's width in dp (DpRect has only left/top/right/bottom). */
    private fun DpRect.widthDp(): Float = right.value - left.value

    /** The node's height in dp. */
    private fun DpRect.heightDp(): Float = bottom.value - top.value

    /** The node's horizontal centre in dp. */
    private fun DpRect.centreXDp(): Float = (left.value + right.value) / 2f

    // ------------------------------------------------------------ geometry

    @Test
    fun rootFillsTheWholeWindow() {
        host(PairingRootStatus.None)
        val root = bounds("pairing-root")
        assertEquals(0f, root.left.value, 0.5f)
        assertEquals(0f, root.top.value, 0.5f)
        assertEquals(393f, root.right.value, 0.5f)
        assertEquals(852f, root.bottom.value, 0.5f)
    }

    @Test
    fun topStackIsCentredOnTheWindowEightyAboveItsCentre() {
        host(PairingRootStatus.None)
        val logo = bounds("pairing-root-logo")
        // android-next #03 replaced the 44 dp glyph with the 64 dp artwork
        // tile (user decision), so the expected box and stack grow by 20:
        // a 64 × 64 tile, horizontally centred (x 164.5…228.5).
        assertEquals(64f, logo.widthDp(), 1f)
        assertEquals(64f, logo.heightDp(), 1f)
        assertEquals(196.5f, logo.centreXDp(), 1f)
        // Top ≈ 268.85 = (852 − 154.3) / 2 − 80: the whole 154.3 dp stack
        // (64 + 12 + 44.2 + 12 + 22.1, one-line tagline).
        assertEquals(268.85f, logo.top.value, 1f)
        // The stack's centre Y is 346 = 852 / 2 − 80 — measured from the
        // mark's top to the tagline's bottom, on the FULL window.
        val tagline = textBounds("This phone has no paired engine yet.")
        assertEquals(346f, (logo.top.value + tagline.bottom.value) / 2f, 1f)
        // Line boxes: 1.3 × 34 = 44.2 and 1.3 × 17 = 22.1; the tagline is
        // one line at 393 dp (it stays one line on any window ≥ 334 dp).
        assertEquals(44.2f, textBounds("Roboco").heightDp(), 1f)
        assertEquals(22.1f, tagline.heightDp(), 1f)
    }

    @Test
    fun capsuleSpansX24To369Bottom794() {
        host(PairingRootStatus.None)
        val capsule = bounds("pair-engine")
        assertEquals(24f, capsule.left.value, 1f)
        assertEquals(369f, capsule.right.value, 1f)
        // 852 − 34 (inset) − 24 (padding).
        assertEquals(794f, capsule.bottom.value, 1f)
        // 22.1 (SemiBold 17 line) + 30 (15/15 pairing padding).
        assertEquals(52.1f, capsule.heightDp(), 1.5f)
    }

    @Test
    fun errorStatusSitsTwelveAboveTheCapsuleAndMatchesItsWidth() {
        host(PairingRootStatus.Error("x"))
        val status = bounds("pairing-root-status")
        val capsule = bounds("pair-engine")
        assertEquals(741.9f, capsule.top.value, 1f)
        assertEquals(729.9f, status.bottom.value, 1.5f)
        assertEquals(12f, capsule.top.value - status.bottom.value, 1.5f)
        assertEquals(24f, status.left.value, 1f)
        assertEquals(capsule.widthDp(), status.widthDp(), 1f)
        // One 14 pt line: 1.3 × 14 = 18.2.
        assertEquals(18.2f, status.heightDp(), 1f)
    }

    @Test
    fun zeroSafeInsetsMoveOnlyTheBottomStack() {
        host(PairingRootStatus.None, insets = WindowInsets(top = 0.dp, bottom = 0.dp))
        val logo = bounds("pairing-root-logo")
        // The top stack is centred on the window, not the safe area: it
        // does not move when the insets go to zero. (268.85 since
        // android-next #03's 64 dp tile; was 278.85 with the 44 dp glyph.)
        assertEquals(268.85f, logo.top.value, 1f)
        assertEquals(346f, (logo.top.value + textBounds("This phone has no paired engine yet.").bottom.value) / 2f, 1f)
        // Only the bottom stack consumes insets: the capsule's bottom
        // becomes 852 − 24.
        val capsule = bounds("pair-engine")
        assertEquals(828f, capsule.bottom.value, 1f)
        assertEquals(24f, capsule.left.value, 1f)
        assertEquals(369f, capsule.right.value, 1f)
    }

    @Test
    fun titleTaglineAndCapsuleSizesIgnoreFontScale() {
        RuntimeEnvironment.setFontScale(2f)
        host(PairingRootStatus.None)
        assertEquals(44.2f, textBounds("Roboco").heightDp(), 1f)
        assertEquals(22.1f, textBounds("This phone has no paired engine yet.").heightDp(), 1f)
        val capsule = bounds("pair-engine")
        assertEquals(52.1f, capsule.heightDp(), 1.5f)
        assertEquals(794f, capsule.bottom.value, 1f)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w300dp-h852dp-xxhdpi")
    fun taglineWrapsToTwoCentredLinesAt300dp() {
        host(PairingRootStatus.None)
        val tagline = textBounds("This phone has no paired engine yet.")
        // 286 dp of copy does not fit the 252 dp (300 − 48) line: two
        // centred lines, 2 × 22.1.
        assertEquals(44.2f, tagline.heightDp(), 1.5f)
        assertEquals(150f, tagline.centreXDp(), 1f)
        assertTrue("tagline clips the start margin: left ${tagline.left.value}", tagline.left.value >= 23f)
        assertTrue("tagline clips the end margin: right ${tagline.right.value}", tagline.right.value <= 277f)
        // The title is still one line.
        assertEquals(44.2f, textBounds("Roboco").heightDp(), 1f)
    }

    @Test
    fun aLongErrorWrapsAndGrowsTheStackUpwardWithoutMovingTheCapsule() {
        // ~4 lines of 14 pt copy (the ticket's 4-line error case).
        val long = "pair ".repeat(40).trim()
        host(PairingRootStatus.Error(long))
        val status = bounds("pairing-root-status")
        val capsule = bounds("pair-engine")
        // The capsule keeps its no-status position exactly.
        assertEquals(794f, capsule.bottom.value, 1f)
        assertEquals(741.9f, capsule.top.value, 1.5f)
        // The status wraps to several 18.2 dp lines and the stack grows
        // upward: the status's bottom stays 12 above the capsule's top,
        // full width and centred on the window.
        assertTrue("the error did not wrap: ${status.heightDp()} dp", status.heightDp() >= 50f)
        assertEquals(729.9f, status.bottom.value, 1.5f)
        assertEquals(capsule.widthDp(), status.widthDp(), 1f)
        assertEquals(196.5f, status.centreXDp(), 1f)
    }

    // ------------------------------------------------------------ states

    @Test
    fun noneComposesNoStatusNode() {
        host(PairingRootStatus.None)
        compose.onNodeWithTag("pairing-root-status").assertDoesNotExist()
        compose.onNodeWithText("Redeeming the pairing link…").assertDoesNotExist()
        compose.onNodeWithTag("pair-engine").assertIsEnabled()
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun redeemingShowsTheLineAndIgnoresCapsuleClicks() {
        // Frozen clock: the busy spinner's frame loop must not spin.
        host(PairingRootStatus.Redeeming, freezeClock = true)
        compose.onNodeWithText("Redeeming the pairing link…").assertExists()
        val status = bounds("pairing-root-status")
        val capsule = bounds("pair-engine")
        assertEquals(729.9f, status.bottom.value, 1.5f)
        assertEquals(capsule.widthDp(), status.widthDp(), 1f)
        // Ticket 08's busy look: the spinner leads the still-visible title.
        compose.onNodeWithTag(BusySpinnerTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Pair an engine").assertExists()
        compose.onNodeWithTag("pair-engine").assertIsNotEnabled()
        // Raw taps: performClick would fail on the missing click action.
        compose.onNodeWithTag("pair-engine").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    @Test
    fun errorShowsTheMessageAndPairsOnTap() {
        host(PairingRootStatus.Error("x"))
        compose.onNodeWithText("x").assertExists()
        compose.onNodeWithTag("pair-engine").assertIsEnabled()
        compose.onNodeWithTag("pair-engine").performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
        // The screen never clears an Error by itself; only the caller
        // replaces it. (Nothing to re-tap: the click must not mutate it.)
        compose.onNodeWithText("x").assertExists()
    }

    // ------------------------------------------------------------ semantics

    @Test
    fun capsuleIsAButtonTheLogoIsDecorativeAndTheStatusIsAPoliteLiveRegion() {
        host(PairingRootStatus.Error("x"))
        assertEquals(
            Role.Button,
            compose.onNodeWithTag("pair-engine").fetchSemanticsNode().config[SemanticsProperties.Role],
        )
        // android-next #03: the artwork tile is labelled "Roboco" (the
        // ticket's acceptance), no longer a decorative unlabelled glyph.
        val logo = compose.onNodeWithTag("pairing-root-logo").fetchSemanticsNode()
        assertEquals(listOf("Roboco"), logo.config.getOrNull(SemanticsProperties.ContentDescription))
        // Android-only: TalkBack announces a redeem error politely.
        assertEquals(
            LiveRegionMode.Polite,
            compose.onNodeWithTag("pairing-root-status").fetchSemanticsNode()
                .config[SemanticsProperties.LiveRegion],
        )
    }
}

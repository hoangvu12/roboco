package sh.roboco.android.newsession

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.BusySpinnerTag
import sh.roboco.android.design.glass.Glass

/**
 * The folder browser bottom bar's goldens and geometry
 * (android-ui-parity #111), recorded by `recordRoborazziDebug` and
 * verified by `verifyRoborazziDebug` at the ticket's phone frame
 * `w402dp-h874dp-xxhdpi` — the same frame as 110's screen test, so the
 * measured margins line up with the iOS capture table (362 dp capsule
 * at 20 dp sides, 12 above the bottom, 8 dp gaps, ≈36 dp New Folder,
 * ≈50 dp Use).
 *
 * The fixtures come from the debug gallery file
 * (`FolderBottomBarFixtures`, shared verbatim with
 * `FolderBottomBarGallerySection`), the stage is the gallery's
 * `FolderBottomBarStage`, and the alert golden uses ticket 18's real
 * `AlertHost` with the `newFolderRetryAlert` fixture.
 * `Glass.forceFallback` and Reduce Motion follow the gallery-capture
 * convention, so the goldens stay byte-stable. The busy fixture's
 * spinner sits at step 0 under the frozen test clock (ticket 08).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class FolderBrowserBottomBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restore() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    // ------------------------------------------------------------ fixtures

    /** The ticket's fixtures (a)–(d), in order. */
    private fun specs(): List<FolderBottomBarSpec> = folderBottomBarFixtures().map { it.second }

    private fun present(spec: FolderBottomBarSpec, appearance: Appearance) {
        compose.setContent {
            RobocoTheme(appearance) {
                FolderBottomBarStage(spec, Modifier.fillMaxSize())
            }
        }
        compose.waitForIdle()
    }

    private fun shot(spec: FolderBottomBarSpec, appearance: Appearance, file: String) {
        present(spec, appearance)
        compose.onRoot().captureRoboImage("newsession/$file")
    }

    /** Presents fixture (a) behind a real `AlertHost` and shows the retry alert. */
    private fun presentRetryAlert(appearance: Appearance) {
        val state = AlertHostState()
        compose.setContent {
            RobocoTheme(appearance) {
                AlertHost(state = state) {
                    FolderBottomBarStage(specs()[0], Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnUiThread { state.show(newFolderRetryAlert()) }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeBy(400) // present + settle
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private fun hasButtonRole(): SemanticsMatcher =
        SemanticsMatcher("has Button role") { it.config[SemanticsProperties.Role] == Role.Button }

    // -------------------------------------------------------------- goldens

    @Test
    fun fixtureAGoodListingLight() {
        shot(specs()[0], Appearance.Light, "folder_bottom_bar_a_light.png")
    }

    @Test
    fun fixtureAGoodListingDark() {
        shot(specs()[0], Appearance.Dark, "folder_bottom_bar_a_dark.png")
    }

    @Test
    fun fixtureBLoadingLight() {
        shot(specs()[1], Appearance.Light, "folder_bottom_bar_b_light.png")
    }

    @Test
    fun fixtureBLoadingDark() {
        shot(specs()[1], Appearance.Dark, "folder_bottom_bar_b_dark.png")
    }

    @Test
    fun fixtureCBusyCreateLight() {
        shot(specs()[2], Appearance.Light, "folder_bottom_bar_c_light.png")
    }

    @Test
    fun fixtureCBusyCreateDark() {
        shot(specs()[2], Appearance.Dark, "folder_bottom_bar_c_dark.png")
    }

    @Test
    fun fixtureDLongStatusLight() {
        shot(specs()[3], Appearance.Light, "folder_bottom_bar_d_light.png")
    }

    @Test
    fun fixtureDLongStatusDark() {
        shot(specs()[3], Appearance.Dark, "folder_bottom_bar_d_dark.png")
    }

    @Test
    fun theRetryAlertOverTheBarLight() {
        presentRetryAlert(Appearance.Light)
        compose.onRoot().captureRoboImage("newsession/folder_bottom_bar_alert_light.png")
    }

    @Test
    fun theRetryAlertOverTheBarDark() {
        presentRetryAlert(Appearance.Dark)
        compose.onRoot().captureRoboImage("newsession/folder_bottom_bar_alert_dark.png")
    }

    // ------------------------------------------------------------ geometry

    @Test
    fun theBottomStackMatchesTheReferenceGeometry() {
        present(specs()[0], Appearance.Light)

        val use = bounds("use-folder")
        val newFolder = bounds("new-folder")
        val status = bounds("folder-status")
        val stack = bounds("folder-bottom-bar")

        // The tag sits inside the 20 dp side paddings, so the stack's own
        // node is the inset column: [20, 382] wide 362, bottom 862.
        assertEquals(20f, stack.left.value, 1e-3f)
        assertEquals(382f, stack.right.value, 1e-3f)
        assertEquals(362f, stack.width.value, 1e-3f)
        // Every child is the full stack width (UIStackView .fill): the
        // New Folder tap area spans it, the Use capsule is 362 dp wide
        // (iOS ≈ 362 pt).
        assertEquals(362f, newFolder.width.value, 1e-3f)
        assertEquals(20f, newFolder.left.value, 1e-3f)
        assertEquals(20f, use.left.value, 1e-3f)
        assertEquals(382f, use.right.value, 1e-3f)
        assertEquals(362f, use.width.value, 1e-3f)

        // 12 above the bottom safe area (no navigation bar in the frame:
        // 874 − 12 = 862).
        assertEquals(862f, use.bottom.value, 1e-3f)

        // 8 dp gaps between the children, measured from the bounds.
        assertEquals(8f, newFolder.top.value - status.bottom.value, 1e-3f)
        assertEquals(8f, use.top.value - newFolder.bottom.value, 1e-3f)

        // Ticket 08's default-padding Use (14 + 17 pt line + 14 ≈ 50) and
        // the `.plain()` New Folder (7 + max(20.4, 17 pt line) + 7 ≈ 36;
        // iOS ≈ 35, ± 2 accepted).
        assertTrue("use was ${use.height.value}", use.height.value in 49f..51f)
        assertTrue("new-folder was ${newFolder.height.value}", newFolder.height.value in 34f..38f)
    }

    @Test
    fun theNewFolderContentIsCentredInTheFullWidthTapArea() {
        present(specs()[0], Appearance.Light)
        val button = bounds("new-folder")
        // The content is the icon + 8 dp + title row (UIButton centres
        // image and title together); the title alone sits (icon + gap) / 2
        // right of centre, so measure the row.
        val content = compose.onNodeWithTag("new-folder-content", useUnmergedTree = true).getBoundsInRoot()
        val buttonCentre = (button.left.value + button.right.value) / 2f
        val contentCentre = (content.left.value + content.right.value) / 2f
        assertEquals("the content centres in the tap area", buttonCentre, contentCentre, 1.5f)
    }

    @Test
    fun anEmptyStatusAddsNoRow() {
        present(
            FolderBottomBarSpec(
                status = "",
                useTitle = "Use \u201Csrv\u201D",
                useEnabled = true,
                useBusy = false,
                newFolderEnabled = true,
            ),
            Appearance.Light,
        )
        compose.onNodeWithTag("folder-status").assertDoesNotExist()
        // Only the single stack spacing remains between the buttons.
        val use = bounds("use-folder")
        val newFolder = bounds("new-folder")
        assertEquals(8f, use.top.value - newFolder.bottom.value, 1e-3f)
    }

    @Test
    fun aLongStatusWrapsToTwoLines() {
        present(specs()[3], Appearance.Light)
        val status = bounds("folder-status")
        // Two lines of the fixed 13 pt Geist (≈ 17.5 dp each): more than
        // one line, less than three.
        assertTrue("status was ${status.height.value} dp", status.height.value in 30f..44f)
        // The Use capsule below keeps its own height — the status never
        // pushes the stack past the 12 dp bottom inset.
        assertEquals(862f, bounds("use-folder").bottom.value, 1e-3f)
    }

    // ---------------------------------------------------------- semantics

    @Test
    fun theGoodFixtureEnablesBothActions() {
        present(specs()[0], Appearance.Light)
        compose.onNodeWithTag("use-folder").assertIsEnabled()
        compose.onNodeWithTag("new-folder").assertIsEnabled()
        compose.onNodeWithTag("new-folder").assert(hasButtonRole())
        compose.onNodeWithTag("new-folder").assertTextEquals("New Folder…")
        compose.onNodeWithTag("folder-status").assertTextEquals("/srv")
    }

    @Test
    fun theLoadingFixtureDisablesBothActions() {
        present(specs()[1], Appearance.Light)
        compose.onNodeWithTag("use-folder").assertIsNotEnabled()
        compose.onNodeWithTag("new-folder").assertIsNotEnabled()
    }

    @Test
    fun theBusyCreateShowsTheSpinnerAndIgnoresUseClicks() {
        var useTaps = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    FolderBrowserBottomBar(
                        status = "/srv",
                        useTitle = "Use \u201Csrv\u201D",
                        useEnabled = true,
                        useBusy = true,
                        newFolderEnabled = false,
                        onUse = { useTaps++ },
                        onNewFolder = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        // Ticket 08's busy: the spinner (unmerged — it merges into the
        // button node) and clicks that do nothing.
        compose.onNodeWithTag(BusySpinnerTag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("use-folder").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, useTaps)
    }

    // ------------------------------------------------------- hit testing

    @Test
    fun tapsBetweenTheButtonsReachTheContentBelow() {
        var behindTaps = 0
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().clickable { behindTaps++ }) {
                    FolderBottomBarStage(specs()[0], Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        val use = bounds("use-folder")
        val newFolder = bounds("new-folder")
        // Not the gap's centre: Compose extends a sub-48 dp pointer target
        // to the 48 dp minimum touch size, so the ~36 dp New Folder button
        // owns up to (48 − 34) / 2 = 7 dp of the 8 dp gap below it. Tap the
        // gap's last half dp, above the 50 dp Use capsule (never extended).
        val gapPoint: Dp = use.top - 0.5.dp
        compose.onRoot().performTouchInput { click(Offset(center.x, gapPoint.toPx())) }
        compose.waitForIdle()
        assertEquals("the stack consumes no touches outside its children", 1, behindTaps)

        // A tap on the button itself never reaches the content below.
        compose.onNodeWithTag("new-folder").performClick()
        compose.waitForIdle()
        assertEquals(1, behindTaps)
    }
}

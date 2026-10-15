package sh.roboco.android.design

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gallery activity end to end under Robolectric (android-ui-parity #22):
 * an index row opens its section, back returns to the index, the Force
 * reduce motion toggle drives the process-wide `ReduceMotion.debugOverride`,
 * and the Dark segment re-renders the status line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DesignGalleryActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<DesignGalleryActivity>()

    @After
    fun reset() {
        ReduceMotion.resetForTest()
    }

    @Test
    fun openingASectionShowsItsTitleAndBackReturnsToTheIndex() {
        compose.onNodeWithText("Motion lab").performClick()

        // The section is open: the back control appears, the index title is gone.
        compose.onNodeWithContentDescription("Sections").assertExists()
        compose.onNodeWithText("Design Gallery").assertDoesNotExist()
        compose.onNodeWithText("Motion lab").assertExists() // now the title

        Espresso.pressBack()

        compose.onNodeWithText("Design Gallery").assertExists()
        compose.onNodeWithContentDescription("Sections").assertDoesNotExist()
    }

    @Test
    fun forceReduceMotionTogglesTheProcessWideOverride() {
        compose.onNodeWithText("Force reduce motion").performClick()
        compose.waitForIdle()
        assertEquals(true, ReduceMotion.debugOverride)

        compose.onNodeWithText("Force reduce motion").performClick()
        compose.waitForIdle()
        assertNull(ReduceMotion.debugOverride)
    }

    @Test
    fun darkSegmentRecoloursTheStatusLine() {
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("dark=true", substring = true).assertExists()
    }
}

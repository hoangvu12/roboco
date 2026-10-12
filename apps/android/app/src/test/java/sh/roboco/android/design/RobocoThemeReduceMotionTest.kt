package sh.roboco.android.design

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ticket 04's wrap (added by 04, the second of tickets 01/04 to land):
 * `RobocoTheme` provides `LocalReduceMotion` without the caller wrapping
 * anything in `ProvideReduceMotion` itself — ticket 35's app root relies on
 * this. The debug override flips the local without recreating the
 * composition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RobocoThemeReduceMotionTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    @Test
    fun robocoThemeProvidesLocalReduceMotion() {
        val observed = mutableListOf<Boolean>()
        compose.setContent {
            RobocoTheme {
                // No ProvideReduceMotion here: the theme wraps content itself.
                observed.add(LocalReduceMotion.current)
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(false), observed)

        // The gallery's "Force reduce motion" override (ticket 22) flips the
        // local inside the theme, without recreating the composition.
        ReduceMotion.debugOverride = true
        compose.waitForIdle()
        assertEquals(listOf(false, true), observed)

        // Back to following the system.
        ReduceMotion.debugOverride = null
        compose.waitForIdle()
        assertEquals(listOf(false, true, false), observed)
    }
}

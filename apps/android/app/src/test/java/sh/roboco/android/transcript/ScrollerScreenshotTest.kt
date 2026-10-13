package sh.roboco.android.transcript

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The transcript-scroller goldens (ticket 116), recorded by
 * `:app:recordRoborazziDebug`, verified by `:app:verifyRoborazziDebug`:
 * [ScrollerFixturePage] in light and dark at the ticket's
 * `w411dp-h891dp-xhdpi`, through the real [RowModel.draw] path.
 *
 * What the goldens must show (the ticket's side-by-side list against iOS):
 * the code card and its label fixed while only the body text moves — item
 * 1 at rest with the right edge fading, item 2 (12 dp in) with a growing
 * left fade, item 3 (the end) with the left edge faded and no right fade,
 * item 6 (short block) with no fade at all; the wide table's header,
 * border and row rules scrolling and fading with its cells, its
 * right-side hairline under the trailing fade at rest.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here. The host may lack some system fonts — the fade and
 * geometry (what these goldens pin) are font-independent, and the fixture
 * builder's `check`s keep the overflow facts stable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScrollerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun loadFonts() {
        Fonts.resetForTest()
        Fonts.load(context)
    }

    @After
    fun resetFonts() {
        Fonts.resetForTest()
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                ScrollerFixturePage()
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollersLight() {
        shot(Appearance.Light, "transcript/scrollers_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollersDark() {
        shot(Appearance.Dark, "transcript/scrollers_dark.png")
    }
}

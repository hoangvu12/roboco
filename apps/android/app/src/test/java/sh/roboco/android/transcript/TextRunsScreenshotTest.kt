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
 * The text-run goldens (recorded by `:app:recordRoborazziDebug`, verified
 * by `:app:verifyRoborazziDebug`): the [TextRunsSpecimen] fixture in light
 * and dark, through the real [RowModel.draw] path. The window is sized to
 * the specimen (360 × 200 dp) at xxhdpi — density 3, the acceptance
 * geometry's own density, so a 1-dp bar is 3 px and a mono cell 24.3 px.
 *
 * What the goldens must show (the ticket's acceptance list): bars 2 dp
 * below and 5 dp above their baselines at 1 dp thick; the strike crossing
 * the x-height of "old ideas"; the three mono lines' `|` borders in the
 * same pixel columns (the 2-cell rule and the 〃〜 exclusion); the pending
 * line grey (#62626A / #A9A9AE).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here. The host may lack CJK/emoji system fonts — tofu is
 * fine, the placement (the cell columns) is what these goldens pin; the
 * glyph forms are checked on an emulator in the ticket's manual steps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TextRunsScreenshotTest {

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

    private fun shot(appearance: Appearance, dark: Boolean, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                TextRunsSpecimen(dark = dark)
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h200dp-xxhdpi")
    fun textRunsLight() {
        shot(Appearance.Light, false, "transcript/text_runs_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h200dp-xxhdpi")
    fun textRunsDark() {
        shot(Appearance.Dark, true, "transcript/text_runs_dark.png")
    }
}

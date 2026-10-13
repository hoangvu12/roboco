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
 * The transcript-chrome goldens (recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`): the [RowChromeFixturePage]
 * fixture in light and dark, through the real [RowModel.draw] path. The
 * window is 390 × 720 dp at xxhdpi — density 3, so a 1-pt fill (a rule or
 * a table row rule) is 3 px and the trailing fade's 28 pt ramp is 84 px,
 * while a hairline stroke stays 1 physical px; 720 dp holds the 701 dp
 * page, captured without the gallery frame.
 *
 * What the goldens must show (the ticket's acceptance list): the code
 * block's rounded 12 pt corners and 1-px border; the table's rounded
 * header band, square lower half, 1-px rounded border and 1 pt row rules;
 * the 3 pt quote bar and 1 pt rule; the folded bubble's sixth line fading
 * to ≈ 8 % over the intact bubble fill; the file-pill name clipped at
 * x = 360 pt and faded over its last 28 pt; the translucent badge, well,
 * agent card and tile.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner, like
 * every golden here. The host may lack some system fonts — the box and
 * fade geometry (what these goldens pin) is font-independent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RowChromeScreenshotTest {

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
                RowChromeFixturePage()
            }
        }
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h720dp-xxhdpi")
    fun rowChromeLight() {
        shot(Appearance.Light, "transcript/row_chrome_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w390dp-h720dp-xxhdpi")
    fun rowChromeDark() {
        shot(Appearance.Dark, "transcript/row_chrome_dark.png")
    }
}

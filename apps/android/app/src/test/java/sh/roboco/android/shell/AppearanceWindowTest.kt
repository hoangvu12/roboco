package sh.roboco.android.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The root wiring of the appearance switch (android-ui-parity #96):
 *
 *  - the ticket's mounting — a `ComponentActivity` hosting
 *    `RobocoTheme(flow.collectAsState().value) { AppearanceWindowEffect(
 *    RobocoTheme.isDark); Box(palette.background) }` — under `notnight`:
 *    with Light the root pixel is `#F3F3F5` and the window background
 *    `0xFFEDEDED`; one frozen-clock frame after `flow.value = Dark` the
 *    pixel is `#060606` and the window background `0xFF000000` (the
 *    launch colour of the effective appearance, not the theme's
 *    configuration-resolved one, which `configChanges="uiMode"` would
 *    never re-resolve);
 *  - the same with the system in night and the choice Light: the
 *    composition override wins, everything stays light;
 *  - the splash resources android-app#13 wrote (verified by this
 *    ticket): `Theme.Roboco`'s `windowSplashScreenBackground` resolves to
 *    `#EDEDED` under `notnight` and `#000000` under `night`, and
 *    `windowSplashScreenAnimatedIcon` is the fully transparent blank.
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AppearanceWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ helpers

    /** The ticket's root wiring, over a controllable preference flow. */
    private fun mountRoot(flow: MutableStateFlow<Appearance>) {
        compose.setContent {
            RobocoTheme(flow.collectAsState().value) {
                AppearanceWindowEffect(RobocoTheme.isDark)
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background))
            }
        }
        compose.waitForIdle()
    }

    /** One pixel well inside the root Box, which paints `palette.background`. */
    private fun rootPixel(): Color {
        val x = with(compose.density) { 100.dp.roundToPx() }
        return compose.onRoot().captureToImage().toPixelMap()[x, x]
    }

    private fun assertRootPixel(rgb: Int, what: String) {
        val pixel = rootPixel()
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        assertTrue("$what: rgb(${pixel.red}, ${pixel.green}, ${pixel.blue}) vs #${rgb.toUInt().toString(16)}", abs(pixel.red - r) <= 0.005f)
        assertTrue("$what: green channel", abs(pixel.green - g) <= 0.005f)
        assertTrue("$what: blue channel", abs(pixel.blue - b) <= 0.005f)
    }

    /** The DecorView background, read back as the ticket specifies. */
    private fun windowBackground(): Int =
        (compose.activity.window.decorView.background as ColorDrawable).color

    private fun splashBackgroundColor(): Int {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attrs = context.obtainStyledAttributes(
            R.style.Theme_Roboco,
            intArrayOf(android.R.attr.windowSplashScreenBackground),
        )
        val color = attrs.getColor(0, 0)
        attrs.recycle()
        return color
    }

    // ------------------------------------------------------ root wiring

    @Test
    @Config(qualifiers = "notnight")
    fun lightHoldsThenSwitchesDarkInTheNextFrame() {
        compose.mainClock.autoAdvance = false
        val flow = MutableStateFlow(Appearance.Light)
        mountRoot(flow)
        compose.mainClock.advanceTimeByFrame() // the first frame under the frozen clock

        // Light: the palette background and the light launch window.
        assertRootPixel(0xFFF3F3F5.toInt(), "the root pixel with Light")
        assertEquals(0xFFEDEDED.toInt(), windowBackground())

        // The switch: exactly one frame later everything is dark.
        compose.runOnUiThread { flow.value = Appearance.Dark }
        Snapshot.sendApplyNotifications() // frozen clock: hand the write to the Recomposer
        compose.mainClock.advanceTimeByFrame()
        assertRootPixel(0xFF060606.toInt(), "the root pixel with Dark")
        assertEquals(0xFF000000.toInt(), windowBackground())
    }

    @Test
    @Config(qualifiers = "night")
    fun aLightChoiceOverridesTheNightConfiguration() {
        val flow = MutableStateFlow(Appearance.Light)
        mountRoot(flow)
        // The composition override wins over the system night mode, and
        // the window background follows the launch colour of the
        // effective (light) appearance.
        assertRootPixel(0xFFF3F3F5.toInt(), "the root pixel stays light")
        assertEquals(0xFFEDEDED.toInt(), windowBackground())
    }

    // ------------------------------------------------- splash resources

    @Test
    @Config(qualifiers = "notnight")
    fun theSplashBackgroundResolvesToTheLightLaunchColour() {
        assertEquals(0xFFEDEDED.toInt(), splashBackgroundColor())
    }

    @Test
    @Config(qualifiers = "night")
    fun theSplashBackgroundResolvesToTheDarkLaunchColour() {
        assertEquals(0xFF000000.toInt(), splashBackgroundColor())
    }

    @Test
    fun theSplashIconIsFullyTransparent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attrs = context.obtainStyledAttributes(
            R.style.Theme_Roboco,
            intArrayOf(android.R.attr.windowSplashScreenAnimatedIcon),
        )
        val icon = attrs.getDrawable(0) ?: error("windowSplashScreenAnimatedIcon must resolve")
        attrs.recycle()

        // A red field the icon is drawn over: a fully transparent icon
        // leaves every pixel exactly red.
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.RED)
        icon.setBounds(0, 0, 32, 32)
        icon.draw(canvas)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                assertEquals("the splash icon paints at ($x, $y)", android.graphics.Color.RED, bitmap.getPixel(x, y))
            }
        }
    }
}

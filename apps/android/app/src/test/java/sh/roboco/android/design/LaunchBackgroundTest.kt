package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.R

/**
 * The launch background written by android-app#13 to
 * `res/values{,-night}/colors.xml` is the same colour the palette exposes
 * (the iOS LaunchBackground colorset).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LaunchBackgroundTest {

    @Test
    @Config(qualifiers = "notnight")
    fun lightLaunchBackground() {
        val argb = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getColor(R.color.launch_background)
        assertEquals(Palette.LaunchBackgroundLight.toArgb(), argb)
    }

    @Test
    @Config(qualifiers = "night")
    fun darkLaunchBackground() {
        val argb = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getColor(R.color.launch_background)
        assertEquals(Palette.LaunchBackgroundDark.toArgb(), argb)
    }
}

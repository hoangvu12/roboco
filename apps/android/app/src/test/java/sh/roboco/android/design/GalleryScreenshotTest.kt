package sh.roboco.android.design

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every registered gallery section, in light and dark (android-ui-parity
 * #22). Later tickets register their section in `Gallery.sections` and are
 * captured here automatically — one parameter per section, two goldens each
 * (`gallery_<slug>_{light,dark}.png` under `src/test/screenshots/design/gallery/`,
 * recorded by `recordRoborazziDebug`, verified by `verifyRoborazziDebug`).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class GalleryScreenshotTest(private val name: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sections(): List<Array<Any>> = Gallery.sections.map { arrayOf<Any>(it.name) }
    }

    @After
    fun reset() = ReduceMotion.resetForTest()

    @Test
    fun lightAndDark() = GalleryCapture.capture(Gallery.section(name)!!)
}

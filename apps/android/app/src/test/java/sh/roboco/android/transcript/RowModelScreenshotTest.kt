package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.design.TranscriptPalette

/**
 * The layer-draw goldens (recorded by `:app:recordRoborazziDebug`, verified
 * by `:app:verifyRoborazziDebug`): the fixture row on a plain
 * [android.graphics.Canvas] — layer 0 in row coordinates, layer 1 clipped
 * to the scroller's viewport and translated to its content origin (what
 * ticket 116's host does) — light and dark, from **one** [RowModel]
 * instance: a theme switch must only change [TranscriptPalette], never
 * rebuild the model. 320 × 112 dp at density 2.0 → 640 × 224 px, filled
 * with the background hex (`ios:Design/Palette.swift:13`).
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner
 * (android-app#30), like every golden here. Tickets 63/64 re-record when
 * their painter bodies land (the seed fade has no ramp yet).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RowModelScreenshotTest {

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

    @Test
    fun theFixtureRowInBothAppearancesFromOneModelInstance() {
        val density = 2f
        val model = RowModel(RowFixtures.row())
        val fonts = StyleFonts().apply { update(RowFixtures.styles) }

        for (dark in listOf(false, true)) {
            val palette = TranscriptPalette(dark)
            val bitmap = Bitmap.createBitmap(640, 224, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (dark) 0xFF060606.toInt() else 0xFFF3F3F5.toInt())
            val canvas = Canvas(bitmap)

            // Layer 0: the row canvas, row coordinates × density.
            model.draw(0, canvas, density, palette, fonts)

            // Layer 1: scroller 0's content coordinates — the host clips to
            // the scroller viewport (16/40, 288×32 dp) and translates to its
            // content origin, then draws the scroller's layer.
            val save = canvas.save()
            canvas.clipRect(16f * density, 40f * density, 304f * density, 72f * density)
            canvas.translate(16f * density, 40f * density)
            model.draw(1, canvas, density, palette, fonts)
            canvas.restoreToCount(save)

            bitmap.captureRoboImage("transcript/RowModel_layers_${if (dark) "dark" else "light"}.png")
        }
    }
}

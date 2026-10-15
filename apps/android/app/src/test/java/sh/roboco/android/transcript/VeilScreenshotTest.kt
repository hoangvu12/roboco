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
import uniffi.roboco_core.RowDisplay

/**
 * The streaming veil's mid-fade goldens (recorded by `:app:recordRoborazziDebug`,
 * verified by `:app:verifyRoborazziDebug`, ticket 118): one plain run of
 * "Streaming text fades in per chunk." at x 16, baseline 28, style 0 (Sans
 * 16.5) with a chunk covering " fades in per chunk.", drawn 110 ms into its
 * 220 ms easeOut — "Streaming text" fully opaque and the fresh part at ≈ 68 %
 * (alpha 175), split at the glyph edge after "text". 320 × 56 dp at density
 * 2.0 → 640 × 112 px, filled with the background role. Both [RowDisplay]s
 * share `key = 1uL` (versions 1 and 2), so the chunk is `[14, 34)`.
 *
 * Rasterisation is host-dependent: record on the Ubuntu CI runner
 * (android-app#30), like every golden here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VeilScreenshotTest {

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
    fun theMidFadeVeilInBothAppearances() {
        val density = 2f
        val fonts = StyleFonts().apply { update(RowFixtures.styles) }
        fonts.useDensity(density)
        val paint = fonts[0u]!!.paint

        val text = "Streaming text fades in per chunk."
        val width = paint.measureText(text, 0, 34) / density

        fun display(t: String, len: UInt, version: ULong): RowDisplay =
            RowFixtures.rowOf(
                runs = listOf(RowFixtures.run(0u, len, 16f, 28f).copy(width = width)),
            ).copy(text = t, version = version, height = 56f, copyText = t)

        val old = RowModel(display("Streaming text", 14u, 1uL))
        val new = RowModel(display(text, 34u, 2uL))

        for (dark in listOf(false, true)) {
            val palette = TranscriptPalette(dark)
            val bitmap = Bitmap.createBitmap(640, 112, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (dark) 0xFF060606.toInt() else 0xFFF3F3F5.toInt())
            val canvas = Canvas(bitmap)

            // The chunk is recorded (grow), its clock started (tick at 0),
            // and the draw runs 110 ms in: alpha 175.
            val veil = Veil()
            veil.grow(old, animate = true)
            veil.grow(new, animate = true)
            veil.tick(0L)
            veil.draw(canvas, new, density, palette, fonts, now = 110_000_000L)

            bitmap.captureRoboImage("transcript/veil_mid_${if (dark) "dark" else "light"}.png")
        }
    }
}

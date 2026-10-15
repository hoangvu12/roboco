package sh.roboco.android.design.reference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The reference board (android-ui-parity #23): the mock rendered at the
 * PNGs' own 460 × 1000 px — `1-off.png` on the left, the Android render on
 * the right — plus an alignment overlay, both written under
 * `build/outputs/roborazzi/` for a human review. **No pixels are
 * asserted**; the board is never a golden, so this test never calls
 * `captureRoboImage` for it (record/verify runs must not treat it as one).
 *
 * The mock's light and dark forms are normal goldens
 * (`design/reference_mock_{light,dark}.png`, recorded by
 * `recordRoborazziDebug`, verified by `verifyRoborazziDebug`), captured at
 * the same 460 × 1000 px: the mock's 402 × 874 dp canvas laid out under
 * `LocalDensity = Density(460/402, 1)` inside a 460 × 1000 dp box at mdpi.
 *
 * Under Robolectric the glass tier resolves to the fallback fill and the
 * edge effect draws gradient-only, so the board's top band is less blurred
 * than iOS — expected (see the ticket's Roboco adaptation); the blur is
 * judged on a device via the gallery's "Reference" page. Row G's CJK and
 * 🌏 may render as tofu, also expected.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w460dp-h1000dp-mdpi")
class ReferenceBoardTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The board canvas: 460 × 1000 dp at mdpi = the PNGs' pixel size, with
     * the mock's 402 × 874 dp canvas scaled through the density (1.14428
     * px per dp). `LocalDensity` wraps `RobocoTheme` because ticket 03's
     * `TypeScale` reads the density inside the theme.
     */
    @Composable
    private fun Board(appearance: Appearance) = Box(Modifier.requiredSize(460.dp, 1000.dp)) {
        CompositionLocalProvider(
            LocalDensity provides Density(ReferenceGeometry.BOARD_SCALE, 1f),
        ) {
            RobocoTheme(appearance) { SessionsReferenceMock() }
        }
    }

    @Test
    fun mockLight() {
        compose.setContent { Board(Appearance.Light) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("design/reference_mock_light.png")
    }

    @Test
    fun mockDark() {
        compose.setContent { Board(Appearance.Dark) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("design/reference_mock_dark.png")
    }

    @Test
    fun board() {
        compose.setContent { Board(Appearance.Light) }
        compose.waitForIdle()

        val android = compose.onRoot().captureToImage().asAndroidBitmap()
        val ios = javaClass.getResourceAsStream("/ios-reference/1-off.png")!!
            .use(BitmapFactory::decodeStream)
        assertEquals(460, android.width)
        assertEquals(1000, android.height)
        assertEquals(460, ios.width)
        assertEquals(1000, ios.height)

        // 936 × 1000: iOS | 16 px grey gutter | the Android render.
        val board = Bitmap.createBitmap(936, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(ios, 0f, 0f, null)
        canvas.drawRect(460f, 0f, 476f, 1000f, Paint().apply { color = 0xFF8E8E93.toInt() })
        canvas.drawBitmap(android, 476f, 0f, null)

        // The alignment overlay: the Android render with the PNG on top at
        // half alpha — misalignments show as doubled glyphs.
        // Mutable: a Canvas refuses an immutable bitmap.
        val overlay = android.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(overlay).drawBitmap(ios, 0f, 0f, Paint().apply { alpha = 128 })

        val dir = File("build/outputs/roborazzi").apply { mkdirs() }
        val boardPng = File(dir, "reference-board.png")
        val overlayPng = File(dir, "reference-overlay.png")
        boardPng.outputStream().use { board.compress(Bitmap.CompressFormat.PNG, 100, it) }
        overlayPng.outputStream().use { overlay.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue(boardPng.isFile)
        assertTrue(boardPng.length() > 0)
        assertTrue(overlayPng.isFile)
        assertTrue(overlayPng.length() > 0)
    }
}

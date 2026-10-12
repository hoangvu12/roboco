package sh.roboco.android.core

import android.content.Context
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.roboco_core.FaceRole

/**
 * The font chain end to end against the real merged assets (Robolectric,
 * native graphics so the TTFs actually parse): `Fonts.load` returns 12
 * byte-identical faces and 12 chain typefaces, the shared paint recipe
 * round-trips features and typefaces, and a bad font fails loudly inside
 * `FontChain.build`. Must not assume a CJK system font exists on the
 * Robolectric host — `cjkSource` is only checked for shape.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FontChainTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        Fonts.resetForTest()
    }

    @After
    fun resetAfter() {
        Fonts.resetForTest()
    }

    @Test
    fun loadBuildsTwelveFacesAndTypefacesFromTheRealAssets() {
        val loaded = Fonts.load(context)

        assertEquals(12, loaded.faceData.size)
        assertEquals(FaceRole.entries, loaded.faceData.map { it.role })
        assertEquals(12, loaded.typefaces.size)
        for (role in FaceRole.entries) {
            assertNotNull("typeface for $role", loaded.typefaces[role])
        }
        for (face in Face.entries) {
            val expected = context.assets.open(face.assetPath).use { it.readBytes() }
            assertArrayEquals("bytes for ${face.name}", expected, loaded.faceData[face.ordinal].bytes)
        }
    }

    @Test
    fun secondLoadIsMemoised() {
        assertSame(Fonts.load(context), Fonts.load(context))
    }

    @Test
    fun applyToRoundTripsFeaturesAndTypefaces() {
        val loaded = Fonts.load(context)
        val paint = Paint(FontChain.TEXT_PAINT_FLAGS).apply { hinting = Paint.HINTING_OFF }

        FontChain.applyTo(paint, FaceRole.MONO, 10f, false)
        assertEquals("'liga' 0, 'clig' 0", paint.fontFeatureSettings)
        assertSame(loaded.typefaces.getValue(FaceRole.MONO), paint.typeface)
        assertEquals(10f, paint.textSize, 0f)
        assertEquals(0f, paint.letterSpacing, 0f)

        // Always assigned: a mono call must not leak its settings into the
        // next sans call.
        FontChain.applyTo(paint, FaceRole.SANS, 10f, true)
        assertEquals(null, paint.fontFeatureSettings)
        assertSame(loaded.typefaces.getValue(FaceRole.SANS), paint.typeface)
        // The default-argument chain typeface and the per-role accessor
        // are the same instance: one chain for measuring and drawing.
        assertSame(Fonts.typeface(context, FaceRole.SANS), paint.typeface)
    }

    @Test
    fun cellWidthIsSixTenthsOfTheMonoSize() {
        Fonts.load(context)
        val paint = Paint(FontChain.TEXT_PAINT_FLAGS)
        FontChain.applyTo(paint, FaceRole.MONO, 10f, false)
        // GeistMono's `0` advances 600/1000 em, so one cell is 6 dp at 10.
        assertEquals(6f, FontChain.cellWidth(paint), 0.01f)
    }

    @Test
    fun buildFailsLoudlyOnUnparseableBytes() {
        // Valid magic followed by garbage: Font.Builder must reject it and
        // FontChain must wrap the failure with the face's path.
        val garbage = byteArrayOf(0, 1, 0, 0) + ByteArray(64) { 0x55 }
        val failure = assertThrows(FontLoadException::class.java) {
            FontChain.build(listOf(Face.Sans to garbage))
        }
        assertEquals("fonts/Geist.ttf", failure.path)
        assertEquals("Unreadable font asset: fonts/Geist.ttf", failure.message)
    }

    @Test
    fun cjkSourceHasTheDocumentedShape() {
        Fonts.load(context)
        // Host-dependent (the Robolectric host may lack CJK fonts): either
        // no fallback at all, or "<file>#<ttc index>".
        val source = FontChain.cjkSource
        assertTrue(
            "cjkSource was $source",
            source == null || source.matches(Regex(".+#\\d+")),
        )
    }
}

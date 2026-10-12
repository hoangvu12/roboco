package sh.roboco.android.transcript

import android.content.Context
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import sh.roboco.android.core.FontChain
import sh.roboco.android.core.Fonts
import uniffi.roboco_core.FaceRole
import uniffi.roboco_core.StyleDesc

/**
 * The paint side of [StyleFonts] (ticket 62) over the real font chain —
 * same Robolectric setup as `FontChainTest`: the shared recipe at the
 * current density (never a font scale: Rust baked text scale into
 * `StyleDesc.size`), lazy caching, density-keyed rebuilds, and the logged
 * miss. The data half (`update`/`desc`/`count` and the per-apply sync) is
 * ticket 61's, still covered by `TranscriptStateTest`
 * (`fontsSyncFromAppliedFramesAndKeepStoredInstances`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StyleFontsTest {

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

    private fun newFonts(): StyleFonts = StyleFonts().apply { update(RowFixtures.styles) }

    @Test
    fun paintsFollowTheSharedRecipeAtTheCurrentDensity() {
        val fonts = newFonts()
        fonts.useDensity(2.625f)

        val sans = fonts[0u]!!.paint
        val mono = fonts[1u]!!.paint

        // Ticket 60's flags and no hinting: linear, unhinted advances.
        assertEquals(FontChain.TEXT_PAINT_FLAGS, sans.flags and FontChain.TEXT_PAINT_FLAGS)
        assertEquals(FontChain.TEXT_PAINT_FLAGS, mono.flags and FontChain.TEXT_PAINT_FLAGS)
        assertEquals(Paint.HINTING_OFF, sans.hinting)
        assertEquals(Paint.HINTING_OFF, mono.hinting)

        // Points × density, no font scale: 16.5 and 13.5 pt at 2.625.
        assertEquals(43.3125f, sans.textSize, 0f)
        assertEquals(35.4375f, mono.textSize, 0f)

        // The typeface is the one FontChain.applyTo sets — the same chain
        // the measurer shapes with — and the feature settings are ticket
        // 60's literals (never re-written here, so measurer and painter
        // cannot drift apart).
        val sansReference = Paint().apply { FontChain.applyTo(this, FaceRole.SANS, 1f, true) }
        val monoReference = Paint().apply { FontChain.applyTo(this, FaceRole.MONO, 1f, false) }
        assertSame(sansReference.typeface, sans.typeface)
        assertSame(monoReference.typeface, mono.typeface)
        assertNull(sans.fontFeatureSettings)
        assertEquals("'liga' 0, 'clig' 0", mono.fontFeatureSettings)
        assertEquals(FontChain.featureSettings(true), sans.fontFeatureSettings)
        assertEquals(FontChain.featureSettings(false), mono.fontFeatureSettings)
        assertEquals(0f, sans.letterSpacing, 0f)

        // The entry mirrors its StyleDesc for the painters (ticket 63's
        // mono-aware offset reads Entry.face/ligatures).
        val entry = fonts[1u]!!
        assertEquals(FaceRole.MONO, entry.face)
        assertEquals(13.5f, entry.sizePt, 0f)
        assertEquals(false, entry.ligatures)
    }

    @Test
    fun entriesAreCachedPerDensityAndRebuiltWhenTheDensityChanges() {
        val fonts = newFonts()
        fonts.useDensity(2.625f)

        val first = fonts[0u]
        assertNotNull(first)
        assertSame(first, fonts[0u])
        assertSame(first!!.paint, fonts[0u]!!.paint)

        fonts.useDensity(3f)
        val rebuilt = fonts[0u]
        assertNotNull(rebuilt)
        assertNotSame(first, rebuilt)
        assertEquals(16.5f * 3f, rebuilt!!.paint.textSize, 0f)
        assertEquals(3f, fonts.density, 0f)
    }

    @Test
    fun laterStylesJoinTheCacheWithoutReplacingKnownEntries() {
        val fonts = newFonts()
        fonts.useDensity(3f)
        val zero = fonts[0u]
        val one = fonts[1u]

        // Ticket 61's append-only update: a new id joins the table…
        fonts.update(RowFixtures.styles + StyleDesc(2u, FaceRole.SANS_BOLD, 20f, true))
        assertEquals(3, fonts.count)

        // …its paint is built lazily at the current density…
        val two = fonts[2u]
        assertNotNull(two)
        assertEquals(20f * 3f, two!!.paint.textSize, 0f)

        // …and the cached entries for the known ids are not replaced.
        assertSame(zero, fonts[0u])
        assertSame(one, fonts[1u])
    }

    @Test
    fun missingStylesReturnNullAndLogExactlyOnce() {
        val fonts = newFonts()
        fonts.useDensity(2.625f)
        ShadowLog.clear()

        assertNull(fonts[99u])
        assertNull(fonts[99u])

        val logged = ShadowLog.getLogs().filter { it.tag == "RobocoTranscript" }
        assertEquals(1, logged.size)
        assertEquals("no style 99 (count=2)", logged.single().msg)
    }

    @Test
    fun getBeforeAnyUseDensityThrows() {
        // The data half can be filled before any draw: the paint side must
        // not build at an unknown density.
        val fonts = newFonts()
        val failure = assertThrows(IllegalStateException::class.java) { fonts[0u] }
        assertTrue("message was ${failure.message}", failure.message!!.contains("useDensity"))
    }

    @Test
    fun useDensityRejectsNonPositiveValues() {
        val fonts = newFonts()
        assertThrows(IllegalArgumentException::class.java) { fonts.useDensity(0f) }
        assertThrows(IllegalArgumentException::class.java) { fonts.useDensity(-1f) }
        assertEquals(0f, fonts.density, 0f)
    }
}

package sh.roboco.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.WallpaperEffect

/**
 * The effect catalogue, character for character
 * (`ios:Design/Wallpaper.swift:39-70`): order, keys, labels, details.
 * Plain JVM (merge check): pure data over the UniFFI enum, no Android
 * runtime needed.
 */
class WallpaperEffectsTest {

    @Test
    fun allIsTheFiveEffectsInDisplayOrder() {
        assertEquals(
            listOf(
                WallpaperEffect.NONE,
                WallpaperEffect.DITHER,
                WallpaperEffect.ASCII,
                WallpaperEffect.HALFTONE,
                WallpaperEffect.SCANLINES,
            ),
            WallpaperEffects.all,
        )
        // Complete over the core enum: a new case must extend the catalogue.
        assertEquals(WallpaperEffect.entries.toSet(), WallpaperEffects.all.toSet())
    }

    @Test
    fun keysAreTheLowerCaseEffectNames() {
        assertEquals("none", WallpaperEffects.key(WallpaperEffect.NONE))
        assertEquals("dither", WallpaperEffects.key(WallpaperEffect.DITHER))
        assertEquals("ascii", WallpaperEffects.key(WallpaperEffect.ASCII))
        assertEquals("halftone", WallpaperEffects.key(WallpaperEffect.HALFTONE))
        assertEquals("scanlines", WallpaperEffects.key(WallpaperEffect.SCANLINES))
    }

    @Test
    fun keyExtensionDelegatesToTheCatalogue() {
        assertEquals("halftone", WallpaperEffect.HALFTONE.key)
        assertEquals("none", WallpaperEffect.NONE.key)
    }

    @Test
    fun fromKeyMapsTheFiveLowerCaseKeys() {
        assertEquals(WallpaperEffect.NONE, WallpaperEffects.fromKey("none"))
        assertEquals(WallpaperEffect.DITHER, WallpaperEffects.fromKey("dither"))
        assertEquals(WallpaperEffect.ASCII, WallpaperEffects.fromKey("ascii"))
        assertEquals(WallpaperEffect.HALFTONE, WallpaperEffects.fromKey("halftone"))
        assertEquals(WallpaperEffect.SCANLINES, WallpaperEffects.fromKey("scanlines"))
    }

    @Test
    fun fromKeyRejectsUnknownAndMixedCaseKeys() {
        // Case-sensitive, like iOS: "Halftone" is not "halftone".
        assertNull(WallpaperEffects.fromKey("Halftone"))
        assertNull(WallpaperEffects.fromKey("NONE"))
        assertNull(WallpaperEffects.fromKey(""))
        assertNull(WallpaperEffects.fromKey(" dither"))
        assertNull(WallpaperEffects.fromKey("bogus"))
        assertNull(WallpaperEffects.fromKey(null))
    }

    @Test
    fun labelsMatchIosVerbatim() {
        assertEquals("None", WallpaperEffects.label(WallpaperEffect.NONE))
        assertEquals("Dither", WallpaperEffects.label(WallpaperEffect.DITHER))
        // Capital "ASCII", exactly as iOS spells it.
        assertEquals("ASCII", WallpaperEffects.label(WallpaperEffect.ASCII))
        assertEquals("Halftone", WallpaperEffects.label(WallpaperEffect.HALFTONE))
        assertEquals("Scanlines", WallpaperEffects.label(WallpaperEffect.SCANLINES))
    }

    @Test
    fun detailsMatchIosVerbatim() {
        assertEquals("Shows the original artwork.", WallpaperEffects.detail(WallpaperEffect.NONE))
        // "color" spelled the US way, like iOS.
        assertEquals(
            "Rebuilds the artwork with a dithered color palette.",
            WallpaperEffects.detail(WallpaperEffect.DITHER),
        )
        assertEquals(
            "Recreates the artwork with colored characters.",
            WallpaperEffects.detail(WallpaperEffect.ASCII),
        )
        assertEquals(
            "Recreates the artwork with colored print dots.",
            WallpaperEffects.detail(WallpaperEffect.HALFTONE),
        )
        assertEquals(
            "Adds a pronounced horizontal display-line texture.",
            WallpaperEffects.detail(WallpaperEffect.SCANLINES),
        )
    }

    @Test
    fun everyEffectHasACatalogueEntry() {
        for (effect in WallpaperEffect.entries) {
            assertTrue("no key for $effect", WallpaperEffects.key(effect).isNotBlank())
            assertTrue("no label for $effect", WallpaperEffects.label(effect).isNotBlank())
            assertTrue("no detail for $effect", WallpaperEffects.detail(effect).isNotBlank())
        }
    }
}

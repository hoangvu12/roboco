package sh.roboco.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.WallpaperEffects
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.RobocoIcons
import uniffi.roboco_core.WallpaperEffect

/**
 * The pure model of the Wallpaper section (android-ui-parity #97), plain
 * JVM — no Robolectric: [wallpaperRows] and [wallpaperEffectSheet] are pure
 * functions over [WallpaperStore.State] / [WallpaperEffect], and
 * `R.drawable` fields are compile-time ints (the `RobocoIconsTest` method).
 *
 * The copy checks pin the ticket's table exactly: the U+2026 ellipsis in
 * the choose titles, the U+2014 em dash with single spaces in the effect
 * subtitles, and `None`/`-` fallbacks banned.
 */
class WallpaperRowsTest {

    private val unset = WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0)
    private val set = WallpaperStore.State(set = true, name = "IMG_0412", effect = WallpaperEffect.HALFTONE, generation = 3)

    // ------------------------------------------------------------- rows

    @Test
    fun nothingSetYieldsTheSingleChooseRow() {
        val rows = wallpaperRows(unset)
        assertEquals(1, rows.size)
        val choose = rows[0]
        assertEquals(WallpaperRowIds.Choose, choose.id)
        assertEquals("Choose Wallpaper\u2026", choose.title)
        assertEquals("Shown behind new chats and the sessions list", choose.subtitle)
        assertSame(RobocoIcons.Photo, choose.icon)
        assertEquals(listOf<GroupedAccessory>(GroupedAccessory.Disclosure), choose.accessories)
        assertFalse(choose.destructive)
    }

    @Test
    fun setYieldsChooseEffectRemoveInThatOrder() {
        val rows = wallpaperRows(set)
        assertEquals(listOf(WallpaperRowIds.Choose, WallpaperRowIds.Effect, WallpaperRowIds.Remove), rows.map { it.id })

        val choose = rows[0]
        assertEquals("Change Wallpaper\u2026", choose.title)
        assertEquals("IMG_0412", choose.subtitle)
        assertSame(RobocoIcons.Photo, choose.icon)
        assertEquals(listOf<GroupedAccessory>(GroupedAccessory.Disclosure), choose.accessories)
        assertFalse(choose.destructive)

        val effect = rows[1]
        assertEquals("Effect", effect.title)
        assertEquals("Halftone \u2014 Recreates the artwork with colored print dots.", effect.subtitle)
        assertSame(RobocoIcons.WandAndStars, effect.icon)
        assertEquals(listOf<GroupedAccessory>(GroupedAccessory.Disclosure), effect.accessories)
        assertFalse(effect.destructive)

        val remove = rows[2]
        assertEquals("Remove Wallpaper", remove.title)
        assertNull(remove.subtitle)
        assertSame(RobocoIcons.Trash, remove.icon)
        assertEquals(emptyList<GroupedAccessory>(), remove.accessories)
        assertTrue(remove.destructive)
    }

    @Test
    fun aMissingNameMeansNoSubtitleNotAPhotoFallback() {
        val noName = WallpaperStore.State(set = true, name = null, effect = WallpaperEffect.NONE, generation = 1)
        val choose = wallpaperRows(noName)[0]
        assertNull("no subtitle when name is null (iOS passes the optional through)", choose.subtitle)
        assertEquals("Change Wallpaper\u2026", choose.title)
        // Still three rows: effect and remove depend on `set`, not the name.
        assertEquals(3, wallpaperRows(noName).size)
    }

    @Test
    fun effectSubtitlesMatchTheTicketTableVerbatim() {
        val expected = mapOf(
            WallpaperEffect.NONE to "None \u2014 Shows the original artwork.",
            WallpaperEffect.DITHER to "Dither \u2014 Rebuilds the artwork with a dithered color palette.",
            WallpaperEffect.ASCII to "ASCII \u2014 Recreates the artwork with colored characters.",
            WallpaperEffect.HALFTONE to "Halftone \u2014 Recreates the artwork with colored print dots.",
            WallpaperEffect.SCANLINES to "Scanlines \u2014 Adds a pronounced horizontal display-line texture.",
        )
        for ((effect, subtitle) in expected) {
            assertEquals("subtitle for $effect", subtitle, wallpaperRows(set.copy(effect = effect))[1].subtitle)
        }
    }

    @Test
    fun theEllipsisIsU2026AndTheDashIsU2014NeverAsciiFallbacks() {
        for (state in listOf(unset, set)) {
            val choose = wallpaperRows(state).first { it.id == WallpaperRowIds.Choose }
            assertTrue("title must end with the U+2026 ellipsis: ${choose.title}", choose.title.endsWith('\u2026'))
            assertFalse("no three dots: ${choose.title}", choose.title.contains("..."))
        }
        for (effect in WallpaperEffects.all) {
            val subtitle = wallpaperRows(set.copy(effect = effect))[1].subtitle!!
            assertTrue("subtitle must use the U+2014 em dash: $subtitle", subtitle.contains(" \u2014 "))
            assertFalse("no ASCII hyphen: $subtitle", subtitle.contains(" - "))
        }
    }

    // ------------------------------------------------------------- sheet

    @Test
    fun theSheetListsTheFiveEffectsInOrderWithTheCurrentOneChecked() {
        val sheet = wallpaperEffectSheet(WallpaperEffect.DITHER) {}
        assertEquals("Wallpaper Effect", sheet.title)
        assertNull(sheet.message)
        assertEquals(
            listOf("None", "Dither", "ASCII", "Halftone", "Scanlines"),
            sheet.actions.map { it.title },
        )
        assertEquals(
            "only the current effect is checked",
            listOf(false, true, false, false, false),
            sheet.actions.map { it.checked },
        )
        assertEquals(ActionSheetStyle.Default, sheet.actions[0].style)
        assertTrue(sheet.actions.all { it.style == ActionSheetStyle.Default })
        assertEquals("Cancel", sheet.cancelTitle)
    }

    @Test
    fun theSheetChecksExactlyTheCurrentEffectForEachEffect() {
        for (effect in WallpaperEffects.all) {
            val sheet = wallpaperEffectSheet(effect) {}
            assertEquals(
                "checked row follows the current effect ($effect)",
                WallpaperEffects.all.indexOf(effect),
                sheet.actions.indexOfFirst { it.checked },
            )
        }
    }

    @Test
    fun pickingARowSetsThatEffectOnce() {
        val picks = mutableListOf<WallpaperEffect>()
        val sheet = wallpaperEffectSheet(WallpaperEffect.DITHER) { picks += it }
        sheet.actions[3].onSelect()
        assertEquals(listOf(WallpaperEffect.HALFTONE), picks)
    }

    @Test
    fun repickingTheCurrentEffectStillSetsIt() {
        val picks = mutableListOf<WallpaperEffect>()
        val sheet = wallpaperEffectSheet(WallpaperEffect.DITHER) { picks += it }
        sheet.actions[1].onSelect()
        assertEquals("iOS still calls the setter for the current effect", listOf(WallpaperEffect.DITHER), picks)
    }

    @Test
    fun cancellingTheSheetCallsNothing() {
        val picks = mutableListOf<WallpaperEffect>()
        val sheet = wallpaperEffectSheet(WallpaperEffect.NONE) { picks += it }
        sheet.onCancel()
        assertTrue("Cancel is a no-op", picks.isEmpty())
    }
}

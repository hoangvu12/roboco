package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The design-gallery section registry (android-ui-parity #22): names are
 * unique (ignore case), slugs are unique, the foundation five lead the list
 * in ticket order, and `Gallery.section` resolves a name (ignore case) or a
 * slug, or returns null.
 */
class GalleryRegistryTest {

    @Test
    fun namesAreNonBlankAndUniqueIgnoringCase() {
        val names = Gallery.sections.map { it.name }
        assertTrue("a section name is blank: $names", names.all { it.isNotBlank() })
        val lower = names.map { it.lowercase(Locale.ROOT) }
        assertEquals("duplicate section names", lower.size, lower.toSet().size)
    }

    @Test
    fun slugsAreUnique() {
        val slugs = Gallery.sections.map { it.slug }
        assertEquals("duplicate slugs: $slugs", slugs.size, slugs.toSet().size)
    }

    @Test
    fun firstFiveSectionsAreTheFoundationOnesInTicketOrder() {
        assertEquals(
            listOf("Palette", "Shapes", "Type", "Motion lab", "Haptics"),
            Gallery.sections.take(5).map { it.name },
        )
    }

    @Test
    fun slugRuleLowercasesAndRunsOfNonAlphanumericsBecomeOneUnderscore() {
        assertEquals("motion_lab", gallerySlug("Motion lab"))
        assertEquals("glass_buttons", gallerySlug("  Glass -- buttons "))
        assertEquals("status_glyph", gallerySlug("Status glyph"))
    }

    @Test
    fun sectionLookupMatchesNamesAndSlugsCaseInsensitively() {
        val lab = Gallery.section("Motion lab")
        assertEquals("Motion lab", lab?.name)
        assertSame(lab, Gallery.section("MOTION LAB"))
        assertSame(lab, Gallery.section("motion_lab"))
        assertNull(Gallery.section("nope"))
        assertNull(Gallery.section(null))
    }
}

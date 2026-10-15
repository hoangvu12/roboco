package sh.roboco.android.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem

/**
 * The attach menu's pure builder (android-ui-parity #87), plain JVM: the
 * row set, order, titles, icons, test tags and default flags of
 * `attachMenu(hasCamera, clipHasImage, onPick)` — the port of
 * `AttachmentPicker.menu(host:limit:completion:)`
 * (`ios:Composer/AttachmentPicker.swift:17-38`) — plus each action's
 * routing to [AttachSource]. No Robolectric: `attachMenu` touches no
 * Android types beyond `R.drawable` ints.
 */
class AttachMenuModelTest {

    private fun rows(hasCamera: Boolean, clipHasImage: Boolean, onPick: (AttachSource) -> Unit = {}): List<MenuItem> {
        val menu = attachMenu(hasCamera, clipHasImage, onPick)
        // One plain group: the menu has no title and every child is a row.
        assertNull("UIMenu(title: nil) — no menu title", menu.title)
        assertEquals(
            "every child is a plain MenuItem (no sections, no deferred, no separators)",
            menu.children.size,
            menu.children.count { it is MenuItem },
        )
        return menu.children.filterIsInstance<MenuItem>()
    }

    private fun assertPlainRow(item: MenuItem) {
        assertNull("no subtitle (${item.title})", item.subtitle)
        assertTrue("no checkmark (${item.title})", !item.checked)
        assertTrue("not destructive (${item.title})", !item.destructive)
        assertTrue("not disabled (${item.title})", !item.disabled)
    }

    private fun icon(item: MenuItem): Any = requireNotNull(item.icon) { "row ${item.title} has an icon" }

    @Test
    fun withCameraAndClipImageAllFourRowsInOrder() {
        val items = rows(hasCamera = true, clipHasImage = true)
        assertEquals(
            listOf("Photo Library", "Take Photo", "Choose File", "Paste Image"),
            items.map { it.title },
        )
        assertEquals(
            listOf(
                RobocoIcons.PhotoOnRectangle,
                RobocoIcons.Camera,
                RobocoIcons.Folder,
                RobocoIcons.DocOnClipboard,
            ),
            items.map { (icon(it) as MenuIcon.Symbol).icon },
        )
        assertEquals(
            listOf("attach-photos", "attach-camera", "attach-files", "attach-paste"),
            items.map { it.testTag },
        )
        items.forEach(::assertPlainRow)
    }

    @Test
    fun withoutACameraTheRowIsAbsent() {
        val items = rows(hasCamera = false, clipHasImage = true)
        assertEquals(listOf("Photo Library", "Choose File", "Paste Image"), items.map { it.title })
        assertEquals(
            listOf("attach-photos", "attach-files", "attach-paste"),
            items.map { it.testTag },
        )
        items.forEach(::assertPlainRow)
    }

    @Test
    fun withoutAClipImageTheRowIsAbsent() {
        val items = rows(hasCamera = true, clipHasImage = false)
        assertEquals(listOf("Photo Library", "Take Photo", "Choose File"), items.map { it.title })
        assertEquals(
            listOf("attach-photos", "attach-camera", "attach-files"),
            items.map { it.testTag },
        )
        items.forEach(::assertPlainRow)
    }

    @Test
    fun minimalIsPhotoLibraryAndChooseFileOnly() {
        val items = rows(hasCamera = false, clipHasImage = false)
        assertEquals(listOf("Photo Library", "Choose File"), items.map { it.title })
        assertEquals(listOf("attach-photos", "attach-files"), items.map { it.testTag })
        items.forEach(::assertPlainRow)
    }

    @Test
    fun everyActionRoutesToItsOwnSourceInRowOrder() {
        val expected = listOf(
            AttachSource.Photos,
            AttachSource.Camera,
            AttachSource.Files,
            AttachSource.Paste,
        )
        val picked = mutableListOf<AttachSource>()
        val items = rows(hasCamera = true, clipHasImage = true) { source -> picked += source }
        items.forEach { it.action() }
        assertEquals(expected, picked)
    }

    @Test
    fun theIconInkIsTheRowInkNotTheDisabledInk() {
        // The rows are plain symbol rows: the icon is the registry glyph
        // itself, and GlassMenuPanel tints it with the row ink.
        val items = rows(hasCamera = true, clipHasImage = true)
        assertSame(RobocoIcons.PhotoOnRectangle, (icon(items[0]) as MenuIcon.Symbol).icon)
        assertSame(RobocoIcons.Camera, (icon(items[1]) as MenuIcon.Symbol).icon)
        assertSame(RobocoIcons.Folder, (icon(items[2]) as MenuIcon.Symbol).icon)
        assertSame(RobocoIcons.DocOnClipboard, (icon(items[3]) as MenuIcon.Symbol).icon)
    }
}

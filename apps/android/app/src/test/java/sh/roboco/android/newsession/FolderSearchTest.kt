package sh.roboco.android.newsession

import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.Palette
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.FolderEntry

/**
 * The pure helpers of [FolderSearch.kt] (android-ui-parity #112), every
 * case of the ticket's step-2 table: the line-for-line web ports
 * (`matchRank`, `filterIndices`, `pathUnder`, `manualPathQuery`,
 * `filteredFolders`, `folderRows`), the rail model
 * (`locationChips`/`selectedLocation`'s worked cases, the longest-root
 * rule and the Home tie), the chip colour table, and [coreErrorText]'s
 * three branches. Plain JUnit, no Robolectric: the helpers are pure and
 * the uniffi `CoreException`/record classes never load the native core.
 */
class FolderSearchTest {

    // ---------------------------------------------------------- matchRank

    @Test
    fun matchRankMatchesTheWebTable() {
        assertEquals(1, matchRank("", "x"))
        assertEquals(0, matchRank("ab", "Abc"))
        assertEquals(1, matchRank("bc", "abc"))
        assertNull(matchRank("z", "abc"))
    }

    @Test
    fun matchRankTrimsAndLowercasesBothSides() {
        assertEquals(0, matchRank(" Ab ", "aBc"))
        assertEquals(1, matchRank("  ", "anything"))
    }

    // ------------------------------------------------------- filterIndices

    @Test
    fun filterIndicesRanksPrefixesFirstAndStaysStable() {
        // dev (prefix 0), Documents (prefix 0), android (substring 1);
        // the two prefixes keep their input order.
        assertEquals(
            listOf(0, 2, 4),
            filterIndices("d", listOf("dev", "notes.txt", "Documents", ".config", "android")),
        )
        // A blank query ranks everything 1: input order, everything kept.
        assertEquals(
            listOf(0, 1, 2, 3),
            filterIndices("   ", listOf("a", "b", "c", "d")),
        )
    }

    // ----------------------------------------------------------- pathUnder

    @Test
    fun pathUnderMatchesEveryWebTrueCase() {
        assertTrue(pathUnder("/media/a", "/media"))
        assertTrue(pathUnder("/media", "/media"))
        assertTrue(pathUnder("/anything", "/"))
        assertTrue(pathUnder("/anything", ""))
        assertTrue(pathUnder("D:\\", "D:\\"))
        assertTrue(pathUnder("D:\\Random", "D:\\"))
    }

    @Test
    fun pathUnderMatchesEveryWebFalseCase() {
        assertFalse(pathUnder("/media/ab", "/media/a"))
        assertFalse(pathUnder("/media/a", "/media/ab"))
        assertFalse(pathUnder("D:\\Random2", "D:\\Random"))
        assertFalse(pathUnder("C:\\Random", "D:\\"))
    }

    // ------------------------------------------------------ manualPathQuery

    @Test
    fun manualPathQueryMatchesTheWebTable() {
        for (text in listOf("/disk2", "~/x", "~", "\\\\server", "C:\\Users", "  /disk2 ", "a:")) {
            assertTrue("expected path-shaped: \"$text\"", manualPathQuery(text))
        }
        for (text in listOf("roboco", ".hidden", "", "   ", "ab:/x", "x")) {
            assertFalse("expected plain: \"$text\"", manualPathQuery(text))
        }
    }

    // ------------------------------------------------------ filteredFolders

    /** The ticket's test data class over the web fixture entries. */
    private data class E(val name: String, val isDir: Boolean)

    private val webEntries = listOf(
        E("dev", isDir = true),
        E("notes.txt", isDir = false),
        E("Documents", isDir = true),
        E(".config", isDir = true),
        E("github", isDir = true),
    )

    private fun names(entries: List<E>) = entries.map { it.name }

    @Test
    fun filteredFoldersMatchesTheWebTestPort() {
        assertEquals(listOf("dev", "Documents", "github"), names(filteredFolders(webEntries, "", E::name, E::isDir)))
        assertEquals(listOf(".config"), names(filteredFolders(webEntries, ".", E::name, E::isDir)))
        assertEquals(listOf("dev", "Documents"), names(filteredFolders(webEntries, "d", E::name, E::isDir)))
    }

    @Test
    fun filteredFoldersRanksPrefixesFirstCaseInsensitively() {
        assertEquals(listOf("github"), names(filteredFolders(webEntries, "HUB", E::name, E::isDir)))
    }

    @Test
    fun filteredFoldersTrimsTheNeedleButNotTheHiddenFlag() {
        // " doc " trims to "doc" (a prefix match), and the untrimmed query
        // does not start with ".", so dot-names stay hidden.
        assertEquals(listOf("Documents"), names(filteredFolders(webEntries, " doc ", E::name, E::isDir)))
        // The hidden flag reads the UNTRIMMED query: " .c " never turns it
        // on, .config is dropped by the entry filter, and the trimmed
        // needle ".c" matches nothing that survived — empty.
        assertEquals(emptyList<String>(), names(filteredFolders(webEntries, " .c ", E::name, E::isDir)))
    }

    @Test
    fun filteredFoldersFolderEntryOverloadMatchesTheGeneric() {
        val entries = webEntries.map { FolderEntry(name = it.name, isDir = it.isDir, isRepo = false) }
        assertEquals(
            names(filteredFolders(webEntries, "d", E::name, E::isDir)),
            filteredFolders(entries, "d").map { it.name },
        )
    }

    // ----------------------------------------------------------- folderRows

    @Test
    fun folderRowsShowEverythingWithAParentForPathShapedQueries() {
        val rows = folderRows(webEntries, "/tmp", E::name, E::isDir)
        assertTrue(rows.parentAllowed)
        assertEquals(listOf("dev", "Documents", "github"), names(rows.folders))
        assertFalse(rows.noMatch)
    }

    @Test
    fun folderRowsHideTheParentWhileFiltering() {
        val rows = folderRows(webEntries, "zzz", E::name, E::isDir)
        assertFalse(rows.parentAllowed)
        assertTrue(rows.folders.isEmpty())
        assertTrue(rows.noMatch)
    }

    @Test
    fun folderRowsAllowTheParentForABlankQuery() {
        val rows = folderRows(webEntries, "", E::name, E::isDir)
        assertTrue(rows.parentAllowed)
        assertFalse(rows.noMatch)
        assertEquals(3, rows.folders.size)
    }

    // ------------------------------------------------------- the rail model

    @Test
    fun locationChipsPutHomeFirstInEngineOrder() {
        val chips = locationChips(listOf("System" to "/", "data" to "/mnt/data"))
        assertEquals(3, chips.size)
        assertEquals(LocationChipModel("Home", null), chips[0])
        assertEquals(LocationChipModel("System", "/"), chips[1])
        assertEquals(LocationChipModel("data", "/mnt/data"), chips[2])
    }

    /** The ticket's Linux fixture: home `/home/wing`, drives `[System /, data /mnt/data]`. */
    private fun linuxChips() = locationChips(listOf("System" to "/", "data" to "/mnt/data"))

    @Test
    fun selectedLocationLinuxWorkedCases() {
        val chips = linuxChips()
        assertEquals(0, selectedLocation("/home/wing/src", "/home/wing", chips)) // Home
        assertEquals(1, selectedLocation("/etc", "/home/wing", chips)) // System
        assertEquals(2, selectedLocation("/mnt/data/x", "/home/wing", chips)) // data
        assertEquals(1, selectedLocation("/mnt/database", "/home/wing", chips)) // System
    }

    @Test
    fun selectedLocationWindowsWorkedCases() {
        val chips = locationChips(listOf("C:" to "C:\\", "D:" to "D:\\"))
        assertEquals(0, selectedLocation("C:\\Users\\wing\\src", "C:\\Users\\wing", chips)) // Home
        assertEquals(1, selectedLocation("C:\\Windows", "C:\\Users\\wing", chips)) // C:
        assertEquals(2, selectedLocation("D:\\", "C:\\Users\\wing", chips)) // D:
    }

    @Test
    fun selectedLocationWithNoPathOrNoHomeSelectsNothingOrTheRoot() {
        assertNull(selectedLocation(null, "/home/wing", linuxChips()))
        // home unknown at /home/wing: only System's root matches.
        assertEquals(1, selectedLocation("/home/wing", null, linuxChips()))
    }

    @Test
    fun selectedLocationBreaksTiesWithTheEarlierChipSoHomeWinsARootTie() {
        // home "/" and System "/" tie at trimmed length 0: Home is first.
        assertEquals(0, selectedLocation("/etc", "/", linuxChips()))
    }

    @Test
    fun selectedLocationPicksTheLongestRootWhenOneDriveShadowsAnother() {
        val chips = locationChips(listOf("System" to "/", "data" to "/mnt/data", "data-x" to "/mnt/data/x"))
        assertEquals(3, selectedLocation("/mnt/data/x/repo", null, chips))
    }

    // -------------------------------------------------------- chip colours

    @Test
    fun locationChipColorsMatchTheTicketTable() {
        fun argb(color: androidx.compose.ui.graphics.Color) = color.toArgb()

        val light = locationChipColors(Palette.Light, selected = true, enabled = true)!!
        assertEquals(0x1F5B43E8, argb(light.fill))
        assertEquals(0xFF5B43E8.toInt(), argb(light.ink))

        val dark = locationChipColors(Palette.Dark, selected = true, enabled = true)!!
        assertEquals(0x1F8B7CF6, argb(dark.fill))
        assertEquals(0xFF8B7CF6.toInt(), argb(dark.ink))

        val lightDisabled = locationChipColors(Palette.Light, selected = true, enabled = false)!!
        assertEquals(0x1F5B43E8, argb(lightDisabled.fill))
        assertEquals(0xFF97979F.toInt(), argb(lightDisabled.ink))

        val darkDisabled = locationChipColors(Palette.Dark, selected = true, enabled = false)!!
        assertEquals(0x1F8B7CF6, argb(darkDisabled.fill))
        assertEquals(0xFF6B6B72.toInt(), argb(darkDisabled.ink))

        // Unselected → null: ticket 59's controlFill/text defaults resolve.
        assertNull(locationChipColors(Palette.Light, selected = false, enabled = true))
        assertNull(locationChipColors(Palette.Light, selected = false, enabled = false))
    }

    // -------------------------------------------------------- coreErrorText

    @Test
    fun coreErrorTextSurfacesTheEngineReasonVerbatim() {
        assertEquals(
            "enter a folder path",
            coreErrorText(CoreException.HostException("enter a folder path"), "fallback"),
        )
        assertEquals(
            "enter an absolute folder path for this engine",
            coreErrorText(
                CoreException.HostException("enter an absolute folder path for this engine"),
                "fallback",
            ),
        )
    }

    @Test
    fun coreErrorTextMapsClosedToTheDisconnectCopy() {
        assertEquals("Roboco is disconnected.", coreErrorText(CoreException.Closed(), "fallback"))
    }

    @Test
    fun coreErrorTextFallsBackWhenThereIsNoMessage() {
        assertEquals(
            "Couldn't read that folder.",
            coreErrorText(IllegalStateException(null as String?), "Couldn't read that folder."),
        )
        assertEquals(
            "custom message",
            coreErrorText(IllegalStateException("custom message"), "fallback"),
        )
    }

    @Test
    fun coreErrorTextAlwaysRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            coreErrorText(CancellationException("cancelled"), "fallback")
        }
    }
}

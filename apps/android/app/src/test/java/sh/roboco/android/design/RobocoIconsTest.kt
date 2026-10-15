package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.R

/**
 * The registry tests (android-ui-parity #06), plain JVM — no Robolectric:
 * `forSfName` is pure lookup over `bySfName` plus the `"<n>.circle"`
 * pattern, and `R.drawable` fields are compile-time ints. The resource
 * *resolution* (every `res` really exists and is a `zi_*` drawable) is a
 * Robolectric test in [IconSizingTest].
 */
class RobocoIconsTest {

    /** Every SF Symbol string on the kept screens and emitted by the core. */
    private val sfNames = listOf(
        "plus",
        "ellipsis",
        "arrow.up",
        "arrow.down",
        "arrow.up.right",
        "arrow.up.arrow.down",
        "arrow.turn.down.right",
        "arrow.turn.left.up",
        "arrow.up.circle.fill",
        "xmark",
        "xmark.circle.fill",
        "chevron.down",
        "chevron.right",
        "chevron.backward",
        "stop.fill",
        "stop.circle",
        "text.line.last.and.arrowtriangle.forward",
        "pin",
        "pin.fill",
        "pin.slash",
        "pin.slash.fill",
        "archivebox",
        "archivebox.fill",
        "tray",
        "tray.and.arrow.up",
        "tray.and.arrow.up.fill",
        "folder",
        "folder.fill",
        "folder.badge.plus",
        "folder.badge.gearshape",
        "pencil",
        "trash",
        "doc",
        "doc.text",
        "doc.on.doc",
        "square.on.square",
        "doc.on.clipboard",
        "checkmark",
        "checkmark.circle",
        "checkmark.circle.fill",
        "checkmark.square.fill",
        "square",
        "desktopcomputer",
        "gauge.with.dots.needle.67percent",
        "clock",
        "photo",
        "photo.on.rectangle",
        "camera",
        "character.cursor.ibeam",
        "link",
        "safari",
        "wand.and.stars",
        "sun.max",
        "moon",
        "circle.lefthalf.filled",
        "square.stack.3d.up",
        "square.and.arrow.up",
        "gearshape",
        "rectangle.compress.vertical",
        "line.3.horizontal",
        "magnifyingglass",
        "bell",
        "questionmark.bubble",
        "exclamationmark.triangle",
        "exclamationmark.circle",
        "info.circle",
        "arrow.triangle.branch",
        "command",
    )

    @Test
    fun forSfNameResolvesEverySfSymbolOnTheKeptScreens() {
        for (name in sfNames) {
            val icon = RobocoIcons.forSfName(name)
            assertNotNull("no drawable for SF Symbol \"$name\"", icon)
            assertTrue("bySfName must contain \"$name\"", RobocoIcons.bySfName.containsKey(name))
            assertTrue(
                "SF rows resolve to a drawable, never a Number",
                RobocoIcons.bySfName[name] is RobocoIcon.Vector,
            )
        }
    }

    @Test
    fun forSfNameResolvesEveryNameTheCoreEmits() {
        // mob:layout/markdown.rs:573 (task boxes), rows.rs:414-423 (chip
        // rows), rows.rs:585-590 (slash command / skill chip, image chips).
        for (name in listOf(
            "checkmark.square.fill",
            "square",
            "questionmark.bubble",
            "exclamationmark.triangle",
            "arrow.triangle.branch",
            "command",
            "wand.and.stars",
            "photo",
        )) {
            assertNotNull("no drawable for core SF name \"$name\"", RobocoIcons.forSfName(name))
        }
    }

    @Test
    fun theAliasesShareTheirGlyph() {
        assertSame(RobocoIcons.PinSlash, RobocoIcons.forSfName("pin.slash"))
        assertSame(RobocoIcons.PinSlash, RobocoIcons.forSfName("pin.slash.fill"))
        assertSame(RobocoIcons.Doc, RobocoIcons.forSfName("doc"))
        assertSame(RobocoIcons.DocText, RobocoIcons.forSfName("doc.text"))
        assertSame(RobocoIcons.DocOnDoc, RobocoIcons.forSfName("doc.on.doc"))
        assertSame(RobocoIcons.SquareOnSquare, RobocoIcons.forSfName("square.on.square"))
    }

    @Test
    fun numberCircleNamesResolveToNumberIcons() {
        assertEquals(RobocoIcon.Number(0), RobocoIcons.forSfName("0.circle"))
        assertEquals(RobocoIcon.Number(1), RobocoIcons.forSfName("1.circle"))
        assertEquals(RobocoIcon.Number(3), RobocoIcons.forSfName("3.circle"))
        assertEquals(RobocoIcon.Number(50), RobocoIcons.forSfName("50.circle"))
    }

    @Test
    fun droppedAndUnknownAndOutOfRangeNamesReturnNullWithoutThrowing() {
        for (name in listOf(
            "51.circle", // past SF's 0.circle…50.circle
            "100.circle", // three digits: not the N.circle pattern
            "3.circle.", // trailing dot
            "3.circle.fill",
            "square.circle", // not numeric
            "-1.circle",
            "waveform", // dropped: voice
            "mic.fill",
            "person.crop.circle", // dropped: account
            "rectangle.portrait.and.arrow.right", // dropped: sign out
            "sparkle", // dropped: sign-in mark
            "tab-chat", // Roboco extras have no SF name
            "WifiOff",
            "",
        )) {
            assertNull("forSfName(\"$name\") must be null", RobocoIcons.forSfName(name))
            // Unknown names repeat (every row): still null, never a throw.
            assertNull(RobocoIcons.forSfName(name))
        }
    }

    @Test
    fun entriesAreTheSingleSourceOfTheSfNameMap() {
        val allSfNames = RobocoIcons.entries.flatMap { it.sfNames }
        assertEquals("no SF name may be mapped twice", allSfNames.size, allSfNames.toSet().size)
        assertEquals("bySfName covers exactly the entries' sfNames", allSfNames.toSet(), RobocoIcons.bySfName.keys)
        for (entry in RobocoIcons.entries) {
            for (name in entry.sfNames) {
                assertSame("bySfName[\"$name\"] must be ${entry.name}", entry.icon, RobocoIcons.bySfName[name])
            }
        }
        // 68 SF constants + 1 alias (pin.slash.fill) + 7 Roboco extras.
        // android-next#04 added Refresh (`arrow.clockwise`, the About
        // "Check for Updates" row; the approved design allows one new glyph).
        assertEquals(69, RobocoIcons.bySfName.size)
        assertEquals(75, RobocoIcons.entries.size)
        assertEquals(
            "constant names are unique",
            RobocoIcons.entries.size,
            RobocoIcons.entries.map { it.name }.toSet().size,
        )
    }

    @Test
    fun opticalScalesMatchTheMappingTable() {
        val expected = mapOf(
            RobocoIcons.Ellipsis to 1.15f,
            RobocoIcons.Xmark to 1.35f,
            RobocoIcons.ChevronDown to 1.1f,
            RobocoIcons.ChevronRight to 1.1f,
            RobocoIcons.ChevronBackward to 1.35f,
            RobocoIcons.StopFill to 0.85f,
            RobocoIcons.Checkmark to 1.05f,
        )
        for (entry in RobocoIcons.entries) {
            val icon = entry.icon as RobocoIcon.Vector
            val scale = expected[icon] ?: 1f
            assertEquals("opticalScale of ${entry.name}", scale, icon.opticalScale)
        }
        assertEquals("only the table's glyphs carry a correction", 7, expected.size)
    }

    @Test
    fun autoMirrorIsExactlyTheDirectionalGlyphs() {
        val mirrored = RobocoIcons.entries
            .filter { (it.icon as RobocoIcon.Vector).autoMirror }
            .map { it.name }
        assertEquals(
            listOf("ArrowTurnDownRight", "ArrowTurnLeftUp", "ChevronRight", "ChevronBackward", "QueueForNextTurn"),
            mirrored,
        )
    }

    @Test
    fun drawableIdsMatchTheMappingTable() {
        val expected: List<Pair<RobocoIcon.Vector, Int>> = listOf(
            RobocoIcons.Plus to R.drawable.zi_plus,
            RobocoIcons.Ellipsis to R.drawable.zi_more_horizontal,
            RobocoIcons.ArrowUp to R.drawable.zi_arrow_up,
            RobocoIcons.ArrowDown to R.drawable.zi_arrow_down,
            RobocoIcons.ArrowUpRight to R.drawable.zi_arrow_up_right,
            RobocoIcons.ArrowUpArrowDown to R.drawable.zi_sort_vertical,
            RobocoIcons.ArrowTurnDownRight to R.drawable.zi_arrow_turn_down_right,
            RobocoIcons.ArrowTurnLeftUp to R.drawable.zi_arrow_turn_left_up,
            RobocoIcons.ArrowUpCircleFill to R.drawable.zi_arrow_up_circle_fill,
            RobocoIcons.Xmark to R.drawable.zi_close,
            RobocoIcons.XmarkCircleFill to R.drawable.zi_xmark_circle_fill,
            RobocoIcons.ChevronDown to R.drawable.zi_alt_arrow_down,
            RobocoIcons.ChevronRight to R.drawable.zi_alt_arrow_right,
            RobocoIcons.ChevronBackward to R.drawable.zi_alt_arrow_left,
            RobocoIcons.StopFill to R.drawable.zi_stop,
            RobocoIcons.StopCircle to R.drawable.zi_stop_circle,
            RobocoIcons.QueueForNextTurn to R.drawable.zi_queue_send,
            RobocoIcons.Pin to R.drawable.zi_pin,
            RobocoIcons.PinFill to R.drawable.zi_pin_fill,
            RobocoIcons.PinSlash to R.drawable.zi_pin_slash,
            RobocoIcons.Archivebox to R.drawable.zi_archive_minimalistic,
            RobocoIcons.ArchiveboxFill to R.drawable.zi_archivebox_fill,
            RobocoIcons.Tray to R.drawable.zi_tray,
            RobocoIcons.TrayAndArrowUp to R.drawable.zi_tray_arrow_up,
            RobocoIcons.TrayAndArrowUpFill to R.drawable.zi_tray_arrow_up_fill,
            RobocoIcons.Folder to R.drawable.zi_folder,
            RobocoIcons.FolderFill to R.drawable.zi_folder_fill,
            RobocoIcons.FolderBadgePlus to R.drawable.zi_folder_badge_plus,
            RobocoIcons.FolderBadgeGearshape to R.drawable.zi_folder_badge_gear,
            RobocoIcons.Pencil to R.drawable.zi_pen,
            RobocoIcons.Trash to R.drawable.zi_trash_bin_minimalistic,
            RobocoIcons.Doc to R.drawable.zi_document,
            RobocoIcons.DocText to R.drawable.zi_document,
            RobocoIcons.DocOnDoc to R.drawable.zi_copy,
            RobocoIcons.SquareOnSquare to R.drawable.zi_copy,
            RobocoIcons.DocOnClipboard to R.drawable.zi_clipboard,
            RobocoIcons.Checkmark to R.drawable.zi_check,
            RobocoIcons.CheckmarkCircle to R.drawable.zi_check_circle,
            RobocoIcons.CheckmarkCircleFill to R.drawable.zi_check_circle_fill,
            RobocoIcons.CheckmarkSquareFill to R.drawable.zi_check_square_fill,
            RobocoIcons.Square to R.drawable.zi_square,
            RobocoIcons.Desktopcomputer to R.drawable.zi_monitor,
            RobocoIcons.Gauge to R.drawable.zi_speedometer,
            RobocoIcons.Clock to R.drawable.zi_clock_circle,
            RobocoIcons.Photo to R.drawable.zi_gallery,
            RobocoIcons.PhotoOnRectangle to R.drawable.zi_gallery_stack,
            RobocoIcons.Camera to R.drawable.zi_camera,
            RobocoIcons.CharacterCursorIbeam to R.drawable.zi_text_cursor,
            RobocoIcons.Link to R.drawable.zi_link,
            RobocoIcons.Safari to R.drawable.zi_global,
            RobocoIcons.WandAndStars to R.drawable.zi_magic_stick_3,
            RobocoIcons.SunMax to R.drawable.zi_sun,
            RobocoIcons.Moon to R.drawable.zi_moon,
            RobocoIcons.CircleLeftHalfFilled to R.drawable.zi_circle_half,
            RobocoIcons.SquareStack3dUp to R.drawable.zi_layers,
            RobocoIcons.SquareAndArrowUp to R.drawable.zi_share,
            RobocoIcons.Gearshape to R.drawable.zi_settings,
            RobocoIcons.RectangleCompressVertical to R.drawable.zi_fold_vertical,
            RobocoIcons.Line3Horizontal to R.drawable.zi_line_3_horizontal,
            RobocoIcons.Magnifyingglass to R.drawable.zi_magnifer,
            RobocoIcons.Bell to R.drawable.zi_bell,
            RobocoIcons.QuestionmarkBubble to R.drawable.zi_chat_round_line,
            RobocoIcons.ExclamationmarkTriangle to R.drawable.zi_danger_triangle,
            RobocoIcons.ExclamationmarkCircle to R.drawable.zi_exclamation_circle,
            RobocoIcons.InfoCircle to R.drawable.zi_info_circle,
            RobocoIcons.ArrowTriangleBranch to R.drawable.zi_git_branch,
            RobocoIcons.Command to R.drawable.zi_command,
            RobocoIcons.Refresh to R.drawable.zi_refresh, // android-next#04
            RobocoIcons.TabChat to R.drawable.zi_tab_chat,
            RobocoIcons.TabSettings to R.drawable.zi_tab_settings,
            RobocoIcons.RobocoBow to R.drawable.zi_roboco_bow, // android-next #03: replaced RobocoGlyph
            RobocoIcons.WifiOff to R.drawable.zi_wifi_off,
            RobocoIcons.Home to R.drawable.zi_home,
            RobocoIcons.HardDrive to R.drawable.zi_hard_drive,
            RobocoIcons.Laptop to R.drawable.zi_laptop,
        )
        assertEquals("every registry constant is covered", RobocoIcons.entries.size, expected.size)
        assertEquals(
            "the mapping table's drawable set",
            expected.map { it.first }.toSet(),
            RobocoIcons.entries.map { it.icon as RobocoIcon.Vector }.toSet(),
        )
        for ((icon, res) in expected) {
            assertEquals(res, icon.res)
        }
    }

    @Test
    fun symbolWeightsMatchTheMeasuredTable() {
        assertEquals(0.85f, SymbolWeight.Light.strokeScale)
        assertEquals(1.00f, SymbolWeight.Regular.strokeScale)
        assertEquals(1.12f, SymbolWeight.Medium.strokeScale)
        assertEquals(1.27f, SymbolWeight.Semibold.strokeScale)
        assertEquals(1.42f, SymbolWeight.Bold.strokeScale)
    }

    @Test
    fun numberCircleRejectsOutOfRangeIndicesInDebug() {
        assertThrows(IllegalArgumentException::class.java) { clampNumberCircle(-1) }
        assertThrows(IllegalArgumentException::class.java) { clampNumberCircle(51) }
        for (n in 0..NUMBER_CIRCLE_MAX) {
            assertEquals(n, clampNumberCircle(n))
        }
    }

    @Test
    fun numberCircleDigitFractionsMatchTheSpec() {
        assertEquals(0.50f, numberCircleDigitFraction("7"))
        assertEquals(0.50f, numberCircleDigitFraction("0"))
        assertEquals(0.42f, numberCircleDigitFraction("12"))
        assertEquals(0.42f, numberCircleDigitFraction("50"))
    }
}

package sh.roboco.android.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every ARGB value of the theme root, from `ios:Design/Palette.swift:13-43`,
 * `ios:Design/StatusGlyph.swift:4-12` and the LaunchBackground colorset.
 *
 * The expected values are explicit literals from the ticket's Spec tables —
 * never recomputed through [rgba] — so a quantisation mistake in the helpers
 * cannot make the table agree with itself.
 */
class PaletteValuesTest {

    // ---- Chrome roles (Palette.swift:13-43) --------------------------------

    private val chromeRoles = listOf(
        Triple("background", 0xFFF3F3F5.toInt(), 0xFF060606.toInt()),
        Triple("rowActive", 0xE6FFFFFF.toInt(), 0x0FFFFFFF.toInt()),
        Triple("elevated", 0xFFFFFFFF.toInt(), 0xFF111113.toInt()),
        Triple("text", 0xFF27272C.toInt(), 0xFFE8E8EA.toInt()),
        Triple("secondary", 0xFF62626A.toInt(), 0xFFA9A9AE.toInt()),
        Triple("tertiary", 0xFF97979F.toInt(), 0xFF6B6B72.toInt()),
        Triple("hairline", 0xFFE2E2E6.toInt(), 0xFF1E1E22.toInt()),
        Triple("accent", 0xFF5B43E8.toInt(), 0xFF8B7CF6.toInt()),
        Triple("controlFill", 0x1327272C.toInt(), 0x13E8E8EA.toInt()),
        Triple("accentSoft", 0x1F5B43E8.toInt(), 0x1F8B7CF6.toInt()),
        Triple("danger", 0xFFDC2626.toInt(), 0xFFF87171.toInt()),
        Triple("success", 0xFF15803D.toInt(), 0xFF34D399.toInt()),
        Triple("warning", 0xFFA16207.toInt(), 0xFFFACC15.toInt()),
        Triple("userBubble", 0xFFFFFFFF.toInt(), 0xFF19191C.toInt()),
        Triple("codeBackground", 0xFFFAFAFB.toInt(), 0xFF0B0B0D.toInt()),
        Triple("codeBorder", 0xFFE4E4E8.toInt(), 0xFF1F1F23.toInt()),
        Triple("chip", 0xFFE7E7EB.toInt(), 0xFF1C1C20.toInt()),
        Triple("subline", 0x8062626A.toInt(), 0x80A9A9AE.toInt()),
    )

    private fun Palette.role(name: String) = when (name) {
        "background" -> background
        "rowActive" -> rowActive
        "elevated" -> elevated
        "text" -> text
        "secondary" -> secondary
        "tertiary" -> tertiary
        "hairline" -> hairline
        "accent" -> accent
        "controlFill" -> controlFill
        "accentSoft" -> accentSoft
        "danger" -> danger
        "success" -> success
        "warning" -> warning
        "userBubble" -> userBubble
        "codeBackground" -> codeBackground
        "codeBorder" -> codeBorder
        "chip" -> chip
        "subline" -> subline
        else -> error("unknown chrome role: $name")
    }

    @Test
    fun chromeRolesMatchIos() {
        for ((name, light, dark) in chromeRoles) {
            assertEquals("$name (light)", light, Palette.Light.role(name).toArgb())
            assertEquals("$name (dark)", dark, Palette.Dark.role(name).toArgb())
        }
    }

    // ---- Project tones (Palette.swift:36-40; MONOGRAM_PALETTE order) -------

    private val projectTones = listOf(
        Triple("slate", 0xFF475569.toInt(), 0xFF94A3B8.toInt()),
        Triple("blue", 0xFF2563EB.toInt(), 0xFF93C5FD.toInt()),
        Triple("violet", 0xFF7C3AED.toInt(), 0xFFC4B5FD.toInt()),
        Triple("rose", 0xFFBE123C.toInt(), 0xFFFDA4AF.toInt()),
        Triple("amber", 0xFFA16207.toInt(), 0xFFFCD34D.toInt()),
        Triple("emerald", 0xFF047857.toInt(), 0xFF6EE7B7.toInt()),
        Triple("teal", 0xFF0F766E.toInt(), 0xFF5EEAD4.toInt()),
        Triple("orange", 0xFFC2410C.toInt(), 0xFFFDBA74.toInt()),
    )

    @Test
    fun projectTonesMatchIos() {
        assertEquals(Palette.PROJECT_TONE_COUNT, Palette.Light.projectTones.size)
        assertEquals(Palette.PROJECT_TONE_COUNT, Palette.Dark.projectTones.size)
        assertEquals(Palette.PROJECT_TONE_NAMES.size, Palette.PROJECT_TONE_COUNT)
        for (i in projectTones.indices) {
            val (name, light, dark) = projectTones[i]
            assertEquals("tone $i ($name) light", light, Palette.Light.projectTones[i].toArgb())
            assertEquals("tone $i ($name) dark", dark, Palette.Dark.projectTones[i].toArgb())
        }
    }

    // ---- Status tones (StatusGlyph.swift:4-12) -----------------------------

    @Test
    fun statusTonesMatchIos() {
        val expected = listOf(
            Triple("working", 0x8C5B43E8.toInt(), 0x8C8B7CF6.toInt()),
            Triple("input", 0x995B43E8.toInt(), 0x998B7CF6.toInt()),
            Triple("failed", 0xA6DC2626.toInt(), 0xA6F87171.toInt()),
            Triple("done", 0xE615803D.toInt(), 0xE634D399.toInt()),
            Triple("idle", 0x24000000.toInt(), 0x24FFFFFF.toInt()),
            Triple("time", 0x8062626A.toInt(), 0x80A9A9AE.toInt()),
        )
        fun statusColors(palette: Palette): List<Color> = with(palette.status) {
            listOf(working, input, failed, done, idle, time)
        }
        for (i in expected.indices) {
            assertEquals(
                "status ${expected[i].first} light",
                expected[i].second,
                statusColors(Palette.Light)[i].toArgb(),
            )
            assertEquals(
                "status ${expected[i].first} dark",
                expected[i].third,
                statusColors(Palette.Dark)[i].toArgb(),
            )
        }
    }

    // ---- Fixed constants and derived values --------------------------------

    @Test
    fun launchBackgroundConstantsMatchTheColorsets() {
        assertEquals(0xFFEDEDED.toInt(), Palette.LaunchBackgroundLight.toArgb())
        assertEquals(0xFF000000.toInt(), Palette.LaunchBackgroundDark.toArgb())
    }

    @Test
    fun selectionBackgroundMatchesTheDesktopAccentSelection() {
        // artifact.json accent.selection: #5b43e83d light, #8b7cf659 dark.
        assertEquals(0x3D5B43E8.toInt(), Palette.Light.selectionBackground.toArgb())
        assertEquals(0x598B7CF6.toInt(), Palette.Dark.selectionBackground.toArgb())
    }

    // ---- Indexing and appearance -------------------------------------------

    @Test
    fun projectToneWrapsNegativeAndLargeIndices() {
        assertEquals(Palette.Light.projectTone(7), Palette.Light.projectTone(-1))
        assertEquals(Palette.Dark.projectTone(7), Palette.Dark.projectTone(-1))
        assertEquals(Palette.Light.projectTone(0), Palette.Light.projectTone(8))
        assertEquals(Palette.Light.projectTone(5), Palette.Light.projectTone(13))
        assertEquals(Palette.Dark.projectTone(5), Palette.Dark.projectTone(13))
    }

    @Test
    fun projectToneAcceptsTheFfiUInt() {
        assertEquals(Palette.Light.projectTone(5), Palette.Light.projectTone(13u))
        assertEquals(Palette.Light.projectTone(7), Palette.Light.projectTone(UInt.MAX_VALUE))
        assertEquals(Palette.Dark.projectTone(0), Palette.Dark.projectTone(8u))
    }

    @Test
    fun appearanceRoundTripsTheStoredInteger() {
        assertEquals(Appearance.System, Appearance.fromStored(0))
        assertEquals(Appearance.Light, Appearance.fromStored(1))
        assertEquals(Appearance.Dark, Appearance.fromStored(2))
        // Any unknown value reads as System, like ?? .unspecified.
        assertEquals(Appearance.System, Appearance.fromStored(7))
        assertEquals(Appearance.System, Appearance.fromStored(-1))
        assertEquals(0, Appearance.System.storedValue)
        assertEquals(1, Appearance.Light.storedValue)
        assertEquals(2, Appearance.Dark.storedValue)
    }

    // ---- Instances ----------------------------------------------------------

    @Test
    fun palettesAreImmutableSingletons() {
        assertSame(Palette.Dark, Palette.of(true))
        assertSame(Palette.Light, Palette.of(false))
        assertEquals(true, Palette.Dark.isDark)
        assertEquals(false, Palette.Light.isDark)
    }
}

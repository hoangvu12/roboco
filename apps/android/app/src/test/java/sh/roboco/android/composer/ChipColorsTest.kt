package sh.roboco.android.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoIcons

/**
 * The chip paint and the leading-icon resolution (android-ui-parity #59),
 * plain JVM: [chipColors] and [resolveChipTint] are pure over the palette,
 * and [ComposerChip.leading] is a pure lookup over [RobocoIcons].
 *
 * The expected ARGBs are the ticket's Spec table as explicit literals —
 * never recomputed through `copy`/`rgba` — so a quantisation mistake
 * cannot make the table agree with itself. iOS source:
 * `ios:Composer/ComposerBar.swift:433-436` (fill/ink),
 * `ios:Design/Palette.swift:13-27` (the roles). 0.10 × 255 = 25.5 → 0x1A,
 * 0.85 × 255 = 216.75 → 0xD9, 0.075 × 255 = 19.1 → 0x13.
 */
class ChipColorsTest {

    // ------------------------------------------------------- resolveChipTint

    @Test
    fun tintsResolveToTheirPaletteRoles() {
        val light = Palette.Light
        val dark = Palette.Dark
        for (palette in listOf(light, dark)) {
            assertEquals(palette.success.toArgb(), resolveChipTint(palette, ChipTint.PrOpen).toArgb())
            assertEquals(palette.accent.toArgb(), resolveChipTint(palette, ChipTint.PrMerged).toArgb())
            assertEquals(palette.accent.toArgb(), resolveChipTint(palette, ChipTint.Accent).toArgb())
            assertEquals(palette.danger.toArgb(), resolveChipTint(palette, ChipTint.PrClosed).toArgb())
            assertEquals(palette.secondary.toArgb(), resolveChipTint(palette, ChipTint.PrDraft).toArgb())
            assertEquals(palette.warning.toArgb(), resolveChipTint(palette, ChipTint.Warning).toArgb())
        }
    }

    // --------------------------------------------------------- the colour table
    //
    // Every row of the Spec table, light and dark, through the exact
    // draw-time path: tint resolved against the palette, then chipColors.

    private fun colors(palette: Palette, tint: ChipTint?, enabled: Boolean): ChipColors =
        chipColors(palette, tint?.let { resolveChipTint(palette, it) }, enabled)

    private fun assertRow(
        tint: ChipTint?,
        enabled: Boolean,
        lightFill: Long,
        lightInk: Long,
        darkFill: Long,
        darkInk: Long,
    ) {
        val light = colors(Palette.Light, tint, enabled)
        assertEquals("light fill", lightFill.toInt(), light.fill.toArgb())
        assertEquals("light ink", lightInk.toInt(), light.ink.toArgb())
        val dark = colors(Palette.Dark, tint, enabled)
        assertEquals("dark fill", darkFill.toInt(), dark.fill.toArgb())
        assertEquals("dark ink", darkInk.toInt(), dark.ink.toArgb())
    }

    @Test
    fun untintedEnabledIsControlFillAndText() {
        assertRow(null, true, 0x1327272C, 0xFF27272C, 0x13E8E8EA, 0xFFE8E8EA)
    }

    @Test
    fun prOpenIsTheSuccessToneWash() {
        assertRow(ChipTint.PrOpen, true, 0x1A15803D, 0xD915803D, 0x1A34D399, 0xD934D399)
    }

    @Test
    fun prMergedIsTheAccentToneWash() {
        assertRow(ChipTint.PrMerged, true, 0x1A5B43E8, 0xD95B43E8, 0x1A8B7CF6, 0xD98B7CF6)
    }

    @Test
    fun warningIsTheWarningToneWash() {
        assertRow(ChipTint.Warning, true, 0x1AA16207, 0xD9A16207, 0x1AFACC15, 0xD9FACC15)
    }

    @Test
    fun disabledKeepsTheFillAndTurnsTheInkTertiary() {
        assertRow(null, false, 0x1327272C, 0xFF97979F, 0x13E8E8EA, 0xFF6B6B72)
    }

    @Test
    fun disabledTintedKeepsTheWashAndTurnsTheInkTertiary() {
        assertRow(ChipTint.PrOpen, false, 0x1A15803D, 0xFF97979F, 0x1A34D399, 0xFF6B6B72)
    }

    // ------------------------------------------------------------- leading()

    /** icon beats symbol: a Branch icon wins over a set symbol. */
    @Test
    fun iconTakesPrecedenceOverSymbol() {
        val chip = ComposerChip(
            id = "branch",
            title = "main",
            symbol = "tray",
            icon = ChipIcon.Branch,
        )
        assertEquals(ChipLeading.Branch, chip.leading())
    }

    /** An SF name that does not resolve gives no icon (UIImage(systemName:) == nil). */
    @Test
    fun anUnknownSymbolYieldsNoLeading() {
        val chip = ComposerChip(id = "model", title = "Opus 4.5", symbol = "no.such.symbol")
        assertNull(chip.leading())
    }

    /** The effort symbol resolves through the registry to the gauge glyph. */
    @Test
    fun theEffortSymbolResolvesToGauge() {
        val chip = ComposerChip(id = "effort", title = "High", symbol = "gauge.with.dots.needle.67percent")
        val leading = chip.leading()
        assertNotNull(leading)
        val symbol = leading as ChipLeading.Symbol
        assertSame(RobocoIcons.Gauge, symbol.icon)
    }

    /** Every icon kind resolves to its leading counterpart. */
    @Test
    fun everyIconKindResolves() {
        assertEquals(ChipLeading.Brand("claude-code"), ComposerChip("model", "Opus", icon = ChipIcon.Brand("claude-code")).leading())
        assertEquals(ChipLeading.Branch, ComposerChip("branch", "main", icon = ChipIcon.Branch).leading())
        assertEquals(ChipLeading.PullRequest, ComposerChip("pr", "1234", icon = ChipIcon.PullRequest).leading())
        assertEquals(
            ChipLeading.Project("roboco", 2),
            ComposerChip("project", "roboco", icon = ChipIcon.Project("roboco", 2)).leading(),
        )
        assertNull(ComposerChip("context", "62%", icon = null, symbol = null).leading())
    }

    // -------------------------------------------------------------- ChipColors

    @Test
    fun chipColorsIsAPureValueOverItsInputs() {
        val first = chipColors(Palette.Light, null, true)
        val second = chipColors(Palette.Light, null, true)
        assertEquals(first, second)
        assertEquals(Color(0x1327272C), first.fill)
        assertEquals(Color(0xFF27272C), first.ink)
    }
}

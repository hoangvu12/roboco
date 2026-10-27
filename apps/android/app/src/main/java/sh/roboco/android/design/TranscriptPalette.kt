package sh.roboco.android.design

import uniffi.roboco_core.ColorRole

/**
 * Transcript paint roles → ARGB for one appearance (iOS `Palette.color(_:)`,
 * Palette.swift:49-96). Index with `ColorRole.ordinal`. The returned array is
 * shared and precomputed: never mutate it.
 */
fun TranscriptPalette(dark: Boolean): IntArray = if (dark) TranscriptPaletteTables.DARK else TranscriptPaletteTables.LIGHT

/** Allocation-free lookup: `palette.color(run.color)`. */
@Suppress("NOTHING_TO_INLINE")
inline fun IntArray.color(role: ColorRole): Int = this[role.ordinal]

internal object TranscriptPaletteTables {
    val LIGHT: IntArray = build(dark = false)
    val DARK: IntArray = build(dark = true)

    /** iOS `dynamic(light, dark, alpha)`; rgb is 0xRRGGBB. */
    private fun dyn(dark: Boolean, light: Int, darkRgb: Int, alpha: Float = 1f): Int =
        argb(if (dark) darkRgb else light, alpha)

    /** iOS `dual(UIColor(white: lw, alpha: la), UIColor(white: dw, alpha: da))`. */
    private fun dual(dark: Boolean, lightRgb: Int, lightAlpha: Float, darkRgb: Int, darkAlpha: Float): Int =
        if (dark) argb(darkRgb, darkAlpha) else argb(lightRgb, lightAlpha)

    /** 8-bit alpha, round half up — same rule as Compose `Color.toArgb()`. */
    internal fun argb(rgb: Int, alpha: Float): Int =
        ((alpha * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)

    private fun build(dark: Boolean): IntArray {
        val out = IntArray(ColorRole.entries.size)
        for (role in ColorRole.entries) {
            out[role.ordinal] = when (role) {   // exhaustive: a new Rust role fails compilation
                ColorRole.TEXT -> dyn(dark, 0x27272C, 0xE8E8EA)                    // Palette.text
                ColorRole.TEXT_SECONDARY -> dyn(dark, 0x62626A, 0xA9A9AE)          // Palette.secondary
                ColorRole.TEXT_TERTIARY -> dyn(dark, 0x97979F, 0x6B6B72)           // Palette.tertiary
                ColorRole.LINK, ColorRole.ACCENT -> dyn(dark, 0x5B43E8, 0x8B7CF6)  // Palette.accent
                ColorRole.DANGER -> dyn(dark, 0xDC2626, 0xF87171)
                ColorRole.SUCCESS -> dyn(dark, 0x15803D, 0x34D399)
                ColorRole.WARNING -> dyn(dark, 0xA16207, 0xFACC15)
                ColorRole.INLINE_CODE_TEXT -> dyn(dark, 0x3F3F46, 0xDCDCE0)
                ColorRole.INLINE_CODE_BACKGROUND -> dyn(dark, 0xE9E9ED, 0x1A1A1E)
                ColorRole.CODE_TEXT -> dyn(dark, 0x303035, 0xE8E8EA)
                ColorRole.CODE_BACKGROUND -> dyn(dark, 0xFAFAFB, 0x0B0B0D)        // Palette.codeBackground
                ColorRole.CODE_BORDER -> dyn(dark, 0xE4E4E8, 0x1F1F23)            // Palette.codeBorder
                ColorRole.QUOTE_BAR -> dyn(dark, 0x5B43E8, 0x8B7CF6, 0.45f)
                ColorRole.RULE -> dyn(dark, 0xE2E2E6, 0x1E1E22)                   // Palette.hairline
                ColorRole.TABLE_BORDER -> dyn(dark, 0xE2E2E6, 0x232327)
                ColorRole.TABLE_HEADER_BACKGROUND -> dyn(dark, 0xF3F3F5, 0x121215)
                ColorRole.USER_BUBBLE -> dyn(dark, 0xFFFFFF, 0x19191C)            // Palette.userBubble
                ColorRole.CHIP_BACKGROUND -> dyn(dark, 0xE7E7EB, 0x1C1C20)        // Palette.chip
                ColorRole.SYNTAX_KEYWORD -> dyn(dark, 0x5B43E8, 0x8B7CF6)
                ColorRole.SYNTAX_STRING -> dyn(dark, 0x15803D, 0x34D399)
                ColorRole.SYNTAX_COMMENT -> dyn(dark, 0x6B7280, 0x92929A)
                ColorRole.SYNTAX_NUMBER, ColorRole.SYNTAX_CONSTANT -> dyn(dark, 0xA16207, 0xFACC15)
                ColorRole.SYNTAX_FUNCTION -> dyn(dark, 0x2563EB, 0x60A5FA)
                ColorRole.SYNTAX_TYPE -> dyn(dark, 0x7E22CE, 0xC084FC)
                ColorRole.SYNTAX_VARIABLE -> dyn(dark, 0x303035, 0xE8E8EA)
                ColorRole.SYNTAX_PROPERTY -> dyn(dark, 0x0E7490, 0x22D3EE)
                ColorRole.SYNTAX_OPERATOR, ColorRole.SYNTAX_PUNCTUATION -> dyn(dark, 0x52525B, 0xA1A1AA)
                ColorRole.SYNTAX_TAG -> dyn(dark, 0xBE185D, 0xF472B6)
                ColorRole.SYNTAX_ATTRIBUTE -> dyn(dark, 0xB91C1C, 0xF87171)
                ColorRole.SYNTAX_ESCAPE -> dyn(dark, 0x0E7490, 0x22D3EE)
                ColorRole.TEXT_FAINT -> dyn(dark, 0x797981, 0x85858A)
                ColorRole.TEXT_SOFT -> dyn(dark, 0x303035, 0xE8E8EA, 0.85f)
                ColorRole.TOOL_RAIL -> dual(dark, 0x000000, 0.162f, 0xFFFFFF, 0.12f)
                ColorRole.TOOL_BADGE -> dual(dark, 0x000000, 0.06f, 0xFFFFFF, 0.06f)
                ColorRole.TOOL_WELL -> dual(dark, 0xFFFFFF, 0.16f, 0x000000, 0.16f)   // inverted
                ColorRole.AGENT_CARD -> dual(dark, 0x000000, 0.03f, 0xFFFFFF, 0.03f)
                ColorRole.AGENT_CARD_BORDER -> dual(dark, 0x000000, 0.0945f, 0xFFFFFF, 0.07f)
                ColorRole.AGENT_TILE -> dual(dark, 0x000000, 0.08f, 0xFFFFFF, 0.08f)
                ColorRole.DIFF_ADD_WASH -> dyn(dark, 0x15803D, 0x34D399, 0.055f)
                ColorRole.DIFF_DEL_WASH -> dyn(dark, 0xDC2626, 0xF87171, 0.055f)
                ColorRole.DIFF_ADD_BAR -> dyn(dark, 0x15803D, 0x34D399, 0.55f)
                ColorRole.DIFF_DEL_BAR -> dyn(dark, 0xDC2626, 0xF87171, 0.55f)
                ColorRole.DIFF_HUNK -> dual(dark, 0x5B43E8, 0.07f, 0x8B7CF6, 0.08f)
                ColorRole.DANGER_WASH -> dual(dark, 0xDC2626, 0.08f, 0xF87171, 0.12f) // Palette.swift:96
                ColorRole.DANGER_SOFT -> dyn(dark, 0xB91C1C, 0xFCA5A5)                 // Palette.swift:97
            }
        }
        return out
    }
}

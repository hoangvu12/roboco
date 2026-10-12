package sh.roboco.android.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import java.lang.Math.round

/**
 * An opaque colour from a 24-bit hex literal: `0xRRGGBB`.
 *
 * Never write `Color(0x27272C)` — an Int literal without an alpha byte is
 * fully transparent — and never `Color.copy(alpha = …)`: Compose quantises
 * floats itself and can land one 8-bit step off. Alpha always goes through
 * [rgba], which rounds `a * 255` like iOS's CGFloat alpha.
 */
internal fun rgb(hex: Int): Color = Color(0xFF000000.toInt() or (hex and 0xFFFFFF))

/** A colour with alpha `a` (0..1), stored as the 8-bit byte `round(a * 255)`. */
internal fun rgba(hex: Int, alpha: Double): Color =
    Color((round(alpha * 255.0).toInt() shl 24) or (hex and 0xFFFFFF))

/**
 * Status colours, as the desktop sidebar tints them (`status_dot_color`);
 * `ios:Design/StatusGlyph.swift:3-12`.
 */
@Immutable
class StatusTone(
    val working: Color,
    val input: Color,
    val failed: Color,
    val done: Color,
    val idle: Color,
    val time: Color,
)

/**
 * App palette: the 18 chrome roles of `ios:Design/Palette.swift`, the 8
 * project tones and the [status] tone set, resolved per appearance. Light is
 * a warm paper grey, dark is true black; the accent is a single violet.
 * Colours are paint-only — nothing here affects layout.
 *
 * [Light] and [Dark] are immutable singletons; [RobocoTheme] picks between
 * them, and non-Compose code (notifications, Canvas painters, Views) uses
 * [of].
 */
@Immutable
class Palette private constructor(
    val isDark: Boolean,
    val background: Color,
    /** The open session's row in the tablet sidebar. */
    val rowActive: Color,
    val elevated: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
    val hairline: Color,
    val accent: Color,
    /** Translucent control fill that reads on glass in both modes. */
    val controlFill: Color,
    val accentSoft: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val userBubble: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val chip: Color,
    /** Desktop sidebar "subline": `text_muted` @ 0.5 (project label, branch). */
    val subline: Color,
    /**
     * Project tones — the desktop's monogram palette (`project_icon.rs`):
     * slate, blue, violet, rose, amber, emerald, teal, orange. Indexed by the
     * core's `project_color_index` (FNV-1a of the project path); exactly
     * [PROJECT_TONE_COUNT] entries, all opaque.
     */
    val projectTones: List<Color>,
    val status: StatusTone,
    /**
     * Text-selection wash: the desktop theme's `accent.selection`
     * (`#5b43e8` @ 0x3D / `#8b7cf6` @ 0x59). Handles use [accent], like
     * `window.tintColor = Palette.accent` (SceneDelegate.swift:15).
     */
    val selectionBackground: Color,
) {
    /** Project tone for the core's colour index; negative and large values wrap. */
    fun projectTone(colorIndex: Int): Color =
        projectTones[Math.floorMod(colorIndex, PROJECT_TONE_COUNT)]

    /** [projectTone] for the FFI's `u32` `color_index` (Kotlin `UInt`). */
    fun projectTone(colorIndex: UInt): Color =
        projectTones[(colorIndex % PROJECT_TONE_COUNT.toUInt()).toInt()]

    companion object {
        const val PROJECT_TONE_COUNT = 8

        val PROJECT_TONE_NAMES = listOf(
            "slate", "blue", "violet", "rose", "amber", "emerald", "teal", "orange",
        )

        /** LaunchBackground colorset: light sRGB (0.929, 0.929, 0.929). */
        val LaunchBackgroundLight = rgb(0xEDEDED)

        /** LaunchBackground colorset: dark (0, 0, 0). */
        val LaunchBackgroundDark = rgb(0x000000)

        val Light: Palette = build(false)

        val Dark: Palette = build(true)

        fun of(dark: Boolean): Palette = if (dark) Dark else Light

        /**
         * Mirrors `Palette.swift` line by line: `d(light, dark, alpha)` is
         * iOS `dynamic(light, dark, alpha:)`; the declaration order follows
         * the Swift file so the two can be diffed by eye.
         */
        private fun build(isDark: Boolean): Palette {
            fun d(light: Int, dark: Int, alpha: Double = 1.0): Color =
                rgba(if (isDark) dark else light, alpha)

            return Palette(
                isDark = isDark,
                // Roboco Light / Roboco Dark (crates/theme builtins): cool neutrals,
                // violet accent. iOS draws the builtin shell, not `background`.
                background = d(0xF3F3F5, 0x060606),
                rowActive = if (isDark) rgba(0xFFFFFF, 0.06) else rgba(0xFFFFFF, 0.9), // dual(white 0.9, white 0.06)
                elevated = d(0xFFFFFF, 0x111113),
                text = d(0x27272C, 0xE8E8EA),
                secondary = d(0x62626A, 0xA9A9AE),
                tertiary = d(0x97979F, 0x6B6B72),
                hairline = d(0xE2E2E6, 0x1E1E22),
                accent = d(0x5B43E8, 0x8B7CF6),
                controlFill = d(0x27272C, 0xE8E8EA, alpha = 0.075),
                accentSoft = d(0x5B43E8, 0x8B7CF6, alpha = 0.12),
                danger = d(0xDC2626, 0xF87171),
                success = d(0x15803D, 0x34D399),
                warning = d(0xA16207, 0xFACC15),
                userBubble = d(0xFFFFFF, 0x19191C),
                codeBackground = d(0xFAFAFB, 0x0B0B0D),
                codeBorder = d(0xE4E4E8, 0x1F1F23),
                chip = d(0xE7E7EB, 0x1C1C20),
                subline = d(0x62626A, 0xA9A9AE, alpha = 0.5),
                projectTones = listOf(
                    d(0x475569, 0x94A3B8), d(0x2563EB, 0x93C5FD), d(0x7C3AED, 0xC4B5FD),
                    d(0xBE123C, 0xFDA4AF), d(0xA16207, 0xFCD34D), d(0x047857, 0x6EE7B7),
                    d(0x0F766E, 0x5EEAD4), d(0xC2410C, 0xFDBA74),
                ),
                status = StatusTone(
                    working = d(0x5B43E8, 0x8B7CF6, alpha = 0.55),
                    input = d(0x5B43E8, 0x8B7CF6, alpha = 0.6),
                    failed = d(0xDC2626, 0xF87171, alpha = 0.65),
                    done = d(0x15803D, 0x34D399, alpha = 0.9),
                    idle = d(0x000000, 0xFFFFFF, alpha = 0.14),
                    time = d(0x62626A, 0xA9A9AE, alpha = 0.5),
                ),
                selectionBackground = d(0x5B43E8, 0x8B7CF6, alpha = (if (isDark) 0x59 else 0x3D) / 255.0),
            )
        }
    }
}

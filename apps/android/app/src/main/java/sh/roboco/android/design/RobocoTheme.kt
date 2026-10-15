package sh.roboco.android.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import sh.roboco.android.design.glass.ProvideGlass

/**
 * The appearance setting as the iOS app stores it
 * (`UserDefaults` "appearance", `UIUserInterfaceStyle.rawValue`):
 * 0 = unspecified (System), 1 = Light, 2 = Dark. Any other stored value reads
 * as System, like `?? .unspecified` (SceneDelegate.swift:14). Switching is
 * instant, with no cross-fade.
 */
enum class Appearance(val storedValue: Int) {
    System(0),
    Light(1),
    Dark(2);

    companion object {
        fun fromStored(value: Int): Appearance =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

/**
 * The resolved palette. No default on purpose: a component rendered outside
 * [RobocoTheme] crashes at once instead of silently drawing light colours.
 */
val LocalPalette = staticCompositionLocalOf<Palette> { error("No Palette: wrap content in RobocoTheme") }

/** The resolved dark/light state; pairs with [LocalPalette]. */
val LocalIsDark = staticCompositionLocalOf<Boolean> { error("No LocalIsDark: wrap content in RobocoTheme") }

/**
 * The theme root: resolves [Appearance] to a [Palette] and provides it, the
 * dark state, accent text-selection colours, [NoIndication], the Geist
 * font families (ticket 03) and the [TypeScale] for the current density.
 *
 * The families are built once per process ([RobocoFonts] caches); the type
 * scale is rebuilt whenever [LocalDensity] changes (a font-scale change is
 * a configuration change), so every `scaled`/`bodyScaled` label
 * recomposes while `fixed` text keeps its dp size.
 *
 * This is a Compose-foundation theme — there is deliberately no Material
 * theme here, no dynamic colour and no theme generation from
 * `roboco-theme-export`.
 */
@Composable
fun RobocoTheme(appearance: Appearance = Appearance.System, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val palette = Palette.of(dark)
    val density = LocalDensity.current
    val context = LocalContext.current
    val fonts = remember(context) { RobocoFonts.families(context) }
    val typeScale = remember(density) { TypeScale.from(density) }
    // window.tintColor = Palette.accent (SceneDelegate.swift:15): the handles
    // are the accent; the wash is the desktop's accent.selection.
    val selection = remember(palette) {
        TextSelectionColors(handleColor = palette.accent, backgroundColor = palette.selectionBackground)
    }
    CompositionLocalProvider(
        LocalIsDark provides dark,
        LocalPalette provides palette,
        LocalTextSelectionColors provides selection,
        // Foundation's default indication is a grey press overlay; components
        // draw their own iOS pressed states (controlFill, glass press).
        LocalIndication provides NoIndication,
        LocalFonts provides fonts,
        LocalTypeScale provides typeScale,
        // Ticket 04: LocalReduceMotion follows the system "remove animations"
        // setting inside the theme (ticket 35's app root relies on this).
        // Ticket 07: ProvideGlass resolves the blur tier inside the theme
        // too, next to ProvideReduceMotion, so GlassSurface needs no wiring.
        // Ticket 11: one shared GlyphClock, nested innermost so a nested
        // ProvideGlyphClock (a frozen test/preview clock) overrides it —
        // every animated StatusGlyph on screen pulses in phase off one
        // frame loop.
        content = { ProvideReduceMotion { ProvideGlass { ProvideGlyphClock(content = content) } } },
    )
}

/**
 * Theme accessors, the function-plus-object pattern used by Compose theme
 * APIs: call sites read `RobocoTheme.palette.text`.
 */
object RobocoTheme {
    val palette: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDark.current
}

/** No press overlay; components draw their own iOS pressed states. */
object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        object : Modifier.Node() {}

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}

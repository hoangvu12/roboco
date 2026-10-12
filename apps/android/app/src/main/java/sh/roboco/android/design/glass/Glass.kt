package sh.roboco.android.design.glass

import androidx.compose.animation.core.SpringSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Immutable
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.IosSpring
import sh.roboco.android.design.SmoothRoundedShape
import sh.roboco.android.design.rgb
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The glass material, per appearance (ticket 07). Every value is the
 * Android port of one iOS `UIGlassEffect(style: .regular)` reading
 * (`ios:Design/Glass.swift:1-36`): a translucent fill over a backdrop blur,
 * a hairline rim and a soft outer shadow, plus a flat fallback fill for when
 * blur is not used.
 *
 * Light values were measured from the iOS capture
 * (`pr-assets/group-by-project/1-off.png`); dark values follow
 * android-app#14's starred values (no dark iOS capture exists). See the
 * ticket's Comments for the light-mode fit notes.
 *
 * @param fill the fill composited over the blur (Haze `colorEffects` tint).
 * @param fallback the flat fill when blur is not used (Fallback tier, or Haze
 *   cannot blur at draw time).
 * @param rimTop the 1 px inner rim colour at the top edge.
 * @param rimBottom the 1 px inner rim colour at the bottom edge; the rim is a
 *   vertical gradient top → bottom (light mode is equal at both ends).
 * @param shadow the outer shadow colour (black with the mode's alpha).
 * @param pressHighlight the interactive press highlight colour, drawn over
 *   the material under the content, × the press factor `h`.
 */
@Immutable
data class GlassColors(
    val fill: Color,
    val fallback: Color,
    val rimTop: Color,
    val rimBottom: Color,
    val shadow: Color,
    val pressHighlight: Color,
)

/**
 * The one glass material every floating surface sits on (ticket 07): the
 * tokens, the [GlassColors] pair and the shape helper mirroring iOS
 * `Glass.surface(radius:)`. Press values are 07-owned approximations of the
 * iOS 26 system-glass touch response (the render server has no app-side
 * numbers); tickets 08, 26, 27, 107, 131 and 55 reference them by name.
 */
object Glass {

    /** Backdrop blur radius at full materialization (android-app#14 §4). */
    val BlurRadius = 24.dp

    /** Flat interior on the iOS capture: the Haze default 0.15 must be overridden. */
    const val NoiseFactor = 0f

    /** iOS `tintColor` ported with the parent ticket's blend rule: tint @0.18 over the fill. */
    const val TintMix = 0.18f

    /** Interactive press scale about the centre (the iOS 26 glass swell). */
    const val PressScale = 1.06f

    /**
     * Press-down spring (07-owned): damping 0.7 / stiffness 600 per the
     * ticket. Built through [IosSpring] — never a pasted stiffness literal
     * (decision FOUNDATION-D1) — with the duration back-solved from the
     * ticket's pair, so the settle-matched stiffness lands on 600.
     */
    val PressDownSpring: SpringSpec<Float> =
        IosSpring("glassPressDown", pressSettleSec(0.7f, 600f), 0.7f).spec()

    /** Press release/cancel spring (07-owned): damping 0.55 / stiffness 380, one small overshoot. */
    val PressReleaseSpring: SpringSpec<Float> =
        IosSpring("glassPressRelease", pressSettleSec(0.55f, 380f), 0.55f).spec()

    /** Shadow offset: the light iOS capture sits slightly low (+y). */
    val ShadowOffsetY = 2.dp

    /** Shadow blur radius; σ ≈ 12 dp fits the measured falloff (ticket Comments). */
    val ShadowRadius = 20.dp

    /**
     * Haze can blur from API 31, but this set chooses the fallback fill on
     * 31–32 (Haze's pre-draw invalidation path is costly under a streaming
     * transcript). One constant moves it.
     */
    const val BlurMinSdk = 33

    /** Light appearance: measured from `1-off.png` (the "New session" accessory, tab pill, search circle). */
    val Light = GlassColors(
        // Color.copy (float alpha), not the palette's byte-quantised rgba:
        // the ticket's tint table is defined on float alphas (GlassTokensTest).
        fill = Color.White.copy(alpha = 0.72f),
        fallback = Color.White.copy(alpha = 0.94f), // = Palette.elevated light
        rimTop = Color.White.copy(alpha = 0.65f),
        rimBottom = Color.White.copy(alpha = 0.65f),
        shadow = Color.Black.copy(alpha = 0.08f),
        pressHighlight = Color.White.copy(alpha = 0.12f),
    )

    /** Dark appearance: android-app#14's starred values (no dark iOS capture). */
    val Dark = GlassColors(
        fill = rgb(0x1C1C1F).copy(alpha = 0.66f),
        fallback = rgb(0x111113).copy(alpha = 0.94f), // = Palette.elevated dark
        rimTop = Color.White.copy(alpha = 0.14f),
        rimBottom = Color.White.copy(alpha = 0.04f),
        shadow = Color.Black.copy(alpha = 0.45f),
        pressHighlight = Color.White.copy(alpha = 0.08f),
    )

    /** The [GlassColors] for the resolved appearance ([LocalIsDark]). */
    fun colors(dark: Boolean): GlassColors = if (dark) Dark else Light

    /**
     * iOS `Glass.surface(radius:)` (`Glass.swift:14-21`): `null` radius is a
     * capsule, otherwise continuous corners of that radius (ticket 01's
     * [SmoothRoundedShape]).
     */
    fun shape(radius: Dp?): Shape = radius?.let { SmoothRoundedShape(it) } ?: CapsuleShape

    /**
     * Debug kill switch, the gallery's "Force fallback glass" toggle (ticket
     * 22). Snapshot state, so [ProvideGlass] re-resolves the tier live.
     * Production code never writes it.
     */
    var forceFallback: Boolean by mutableStateOf(false)

    /**
     * True when this composition blurs (ticket 09's scroll-edge effect and
     * others branch on this instead of re-deriving the tier).
     */
    val blurAvailable: Boolean
        @Composable @ReadOnlyComposable get() = LocalGlassTier.current == GlassTier.Blur

    /**
     * The IosSpring duration whose settle-matched stiffness equals
     * `stiffness` for `damping` < 1: T = ln(1000) / (ζ·√k), the inverse of
     * IosSpring's own ω = ln(1000) / (ζ·T).
     */
    private fun pressSettleSec(damping: Float, stiffness: Float): Double =
        ln(1000.0) / (damping * sqrt(stiffness.toDouble()))
}

/**
 * `tint` at [Glass.TintMix], composited **over** [base] with the
 * source-over rule (result alpha `a1 + a2(1 − a1)`); returns [base] when
 * [tint] is null. iOS `tintColor` (`Glass.swift:10`) has no caller today —
 * this exists for API completeness and must stay a composite, never a lerp.
 */
fun glassFill(base: Color, tint: Color?): Color =
    if (tint == null) base else tint.copy(alpha = Glass.TintMix).compositeOver(base)

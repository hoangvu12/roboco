package sh.roboco.android.design.glass

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalIsDark
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rgba

/**
 * The one Android glass material (ticket 07): the port of iOS
 * `Glass.surface` (`ios:Design/Glass.swift:14-21`). A backdrop blur (Haze,
 * blur tier), a translucent fill, a 1 px gradient rim and a soft outer
 * shadow — or a flat fallback fill when blur is not used. Every glass part
 * in the app (toast 14, glass buttons 08, scroll edge 09, status pill 73,
 * composer 55, mention card 86, queue 82, question panel 90, menus 15, tab
 * pill 26, accessory 27) sits on this.
 *
 * **Layout:** this component has no layout of its own. The caller sizes it
 * through [modifier] and puts content inside; the surface adds no padding
 * and no minimum size. [content] defaults to empty, like foundation's
 * `Box` — the ticket's fixture (the probe capsule) renders it bare. The
 * content is clipped to [shape] (iOS masks `contentView` to the corner
 * configuration).
 *
 * **Shadow extent:** the 20 dp outer shadow draws *outside* the bounds.
 * `GlassSurface` never clips it, so a parent with `clipToBounds()` /
 * `clip()` cuts it — that is the caller's concern. Leave ≈20 dp of undrawn
 * space around a surface, or accept the clip.
 *
 * **Visibility:** [visible] drives the shared materialize/dematerialize
 * animation (`Glass.setVisible`, `Glass.swift:28-36`): blur radius, fill,
 * rim, shadow and content alpha all animate together over 350 ms in /
 * 250 ms out (easeInOut), interruptibly from the current value, snapping
 * when [animateVisibility] is false or Reduce Motion is on. [visible] is
 * also the *model* value for interactivity (the UIKit hit-testing rule):
 * the moment `setVisible(false)` runs the content stops being tappable and
 * leaves the semantics tree, even while it is still fading on screen.
 * [materializeOnEnter] starts dematerialized so a first appearance grows in
 * (the toast; panels that appear after the screen's first render).
 *
 * **Interactive press** (the iOS 26 `isInteractive` touch response,
 * approximated with 07-owned tokens): the whole surface scales to
 * [Glass.PressScale] with a white highlight, over the material and under
 * the content, driven by [interactionSource] when the caller shares one
 * (tickets 08, 26, 27), else by an internal non-consuming tracker — the
 * caller's own `clickable` keeps working either way. Do **not** also wrap
 * an interactive `GlassSurface` in ticket 08's `Modifier.glassPress`: that
 * modifier is for the opaque filled/prominent buttons only, and the scale
 * would be applied twice. Under Reduce Motion the scale stays 1 and the
 * highlight switches instantly.
 *
 * **Tier:** a Haze blur when the composition resolved `Blur`
 * ([LocalGlassTier]) *and* a screen source exists ([LocalHazeState]);
 * otherwise the flat fallback fill. The look is identical whichever layer
 * decides. Callers that must fade something *outside* the surface read
 * [rememberGlassProgress] themselves (the iOS `content:` parameter is
 * dropped — no caller passes it).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = CapsuleShape,
    interactive: Boolean = false,
    tint: Color? = null,
    visible: Boolean = true,
    animateVisibility: Boolean = true,
    materializeOnEnter: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalIsDark.current
    val colors = Glass.colors(dark)
    val fill = glassFill(colors.fill, tint)
    val fallback = glassFill(colors.fallback, tint)
    val hazeState = LocalHazeState.current
    // No screen source: this surface alone falls back to the flat fill.
    val tier = if (hazeState == null) GlassTier.Fallback else LocalGlassTier.current
    val progress = rememberGlassProgress(visible, animateVisibility, materializeOnEnter)

    val press = if (interactive) rememberPress(interactionSource) else null
    val pressScale = if (press != null) {
        // Draw-phase read: the scale animates without recomposition, and
        // graphicsLayer never changes the measured size or hit area.
        Modifier.graphicsLayer {
            val k = 1f + (Glass.PressScale - 1f) * press.s.value
            scaleX = k
            scaleY = k
        }
    } else {
        Modifier
    }
    val pressTracker = if (press != null && interactionSource == null) {
        Modifier.pressTracker(press.source)
    } else {
        Modifier
    }

    Box(modifier.then(pressScale).then(pressTracker)) {
        GlassShadow(Modifier.matchParentSize(), shape, colors, progress)
        GlassMaterial(
            Modifier.matchParentSize(), shape, colors, fill, fallback, tier, hazeState,
            progress, press, visible,
        )
        Box(
            Modifier.glassContent(shape, progress, visible),
            contentAlignment = contentAlignment,
            propagateMinConstraints = propagateMinConstraints,
            content = content,
        )
        // Test-only keys (GlassProgressKey / GlassPressScaleKey), isolated
        // here so their per-frame recomposition never touches the caller's
        // content. Nothing draws; a zero-size, no-content child.
        GlassTestSemantics(progress, press)
    }
}

// ---------------------------------------------------------------------------
// Press (the iOS 26 interactive-glass response, 07-owned approximation)
// ---------------------------------------------------------------------------

/**
 * The press state: the caller's (or an internal) interaction source, the
 * scale progress `s` and the highlight progress `h`.
 */
private class GlassPressState(
    val source: MutableInteractionSource,
    val s: Animatable<Float, AnimationVector1D>,
    val h: Animatable<Float, AnimationVector1D>,
)

/** The press springs and reduce-motion rule shared with ticket 08's tokens. */
@Composable
private fun rememberPress(interactionSource: MutableInteractionSource?): GlassPressState {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val s = remember { Animatable(0f) }
    val h = remember { Animatable(0f) }
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    LaunchedEffect(pressed, reduce) {
        val target = if (pressed) 1f else 0f
        val spec = if (pressed) Glass.PressDownSpring else Glass.PressReleaseSpring
        // Reversals cancel the running animateTo and continue from the
        // current value — the same interruptible rule as the visibility
        // animation.
        launch { if (reduce) s.snapTo(0f) else s.animateTo(target, spec) }
        launch { if (reduce) h.snapTo(target) else h.animateTo(target, spec) }
    }
    return GlassPressState(source, s, h)
}

/**
 * A **non-consuming** press tracker, attached only when the caller does not
 * share a `clickable`'s interaction source: it emits `Press`/`Release` into
 * [source] on the Initial pass without consuming events, so the caller's own
 * `clickable` still works.
 */
private fun Modifier.pressTracker(source: MutableInteractionSource): Modifier =
    pointerInput(source) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = PressInteraction.Press(down.position)
            source.tryEmit(press)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            source.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
        }
    }

// ---------------------------------------------------------------------------
// The material: shadow, blur/fill, rim
// ---------------------------------------------------------------------------

/**
 * The outer shadow, drawn outside the shape only: the `Difference` clip
 * keeps it off the glass interior (measured: iOS never darkens the inside
 * of the glass). The layer alpha fades it with the materialization
 * progress, a draw-phase read.
 */
@Composable
private fun GlassShadow(base: Modifier, shape: Shape, colors: GlassColors, progress: State<Float>) {
    Spacer(
        base
            .graphicsLayer { alpha = progress.value }
            .drawWithCache {
                val interior = shape.createOutline(size, layoutDirection, this).toPath()
                onDrawWithContent {
                    // drawContent() lives on this lambda's ContentDrawScope;
                    // the clipPath lambda's DrawScope receiver hides it (K2
                    // DSL-scope rule), so the call needs the explicit label.
                    clipPath(interior, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                }
            }
            .dropShadow(
                shape,
                Shadow(
                    radius = Glass.ShadowRadius,
                    color = colors.shadow,
                    offset = DpOffset(0.dp, Glass.ShadowOffsetY),
                ),
            ),
    )
}

/**
 * The material itself. The **only** composable that reads `progress.value`
 * in composition: the Haze style is an immutable program, so its blur
 * radius and alpha must be rebuilt each frame in the Blur tier — and only
 * this private composable recomposes, never the caller's content. The
 * fallback fill and the rim are draw-phase reads and cost no recomposition.
 */
@Composable
private fun GlassMaterial(
    base: Modifier,
    shape: Shape,
    colors: GlassColors,
    fill: Color,
    fallback: Color,
    tier: GlassTier,
    hazeState: HazeState?,
    progress: State<Float>,
    press: GlassPressState?,
    visible: Boolean,
) {
    val p = if (tier == GlassTier.Blur) progress.value else 0f

    var material = base.clip(shape)
    if (tier == GlassTier.Blur && (p > 0f || visible)) {
        val state = requireNotNull(hazeState) { "The Blur tier requires a LocalHazeState" }
        // Style per frame: the materialize animation grows the blur radius
        // in (24 dp · p) instead of cross-fading a finished blur — the
        // render-server behaviour `Glass.setVisible` animates `effect` for.
        val style = remember(p, fill, fallback) {
            HazeBlurStyle {
                blurRadius(Glass.BlurRadius * p)
                noiseFactor(Glass.NoiseFactor)
                colorEffects(listOf(HazeColorEffect.tint(fill)))
                fallbackColorEffect(HazeColorEffect.tint(fallback))
                alpha(p)
            }
        }
        material = material.hazeBlur(HazeInput.Sources(state), style)
        rememberGuardConfirmation()
    } else if (tier == GlassTier.Fallback) {
        material = material.drawBehind { drawRect(fallback, alpha = progress.value) }
    }
    material = material.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this).toPath()
        val rim = Brush.verticalGradient(listOf(colors.rimTop, colors.rimBottom))
        onDrawWithContent {
            // A 2 px stroke centred on the outline; the shape clip keeps the
            // inner 1 px (one physical pixel).
            drawPath(outline, rim, alpha = progress.value, style = Stroke(width = 2f))
            if (press != null) {
                // Interactive highlight: over the material, under the
                // content, clipped to the shape, × h × p.
                drawRect(colors.pressHighlight, alpha = press.h.value * progress.value)
            }
            drawContent()
        }
    }
    Spacer(material)
}

/**
 * pr621's crash sentinel around the first blurred frames: arm before the
 * first frame (synchronously, from a `SideEffect`), confirm after two clean
 * frames on `Dispatchers.IO`. Skipped entirely when the launch cycle is
 * already confirmed.
 */
@Composable
private fun rememberGuardConfirmation() {
    val view = LocalView.current
    val guard = remember { GlassCrashGuard.get(view.context) }
    if (!guard.settled) {
        SideEffect { guard.armOnce() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            withContext(Dispatchers.IO) { guard.confirm() }
        }
    }
}

// ---------------------------------------------------------------------------
// Content: clip, fade, the UIKit model-value hit-testing rule
// ---------------------------------------------------------------------------

/**
 * The content box modifier: clipped to the shape, fading with the
 * materialization progress (draw phase), and — while `!visible` — stripped
 * from the semantics tree and swallowing touches at the Initial pass, like
 * UIKit delivering them to the effect view itself. The layout modifier
 * keeps the measured size but stops placing the content at `p == 0` and
 * `!visible` (placement-phase read): a fully hidden surface draws, hit-tests
 * and exposes nothing.
 */
private fun Modifier.glassContent(
    shape: Shape,
    progress: State<Float>,
    visible: Boolean,
): Modifier = this
    .clip(shape)
    .graphicsLayer { alpha = progress.value }
    .let {
        if (visible) {
            it
        } else {
            it
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
        }
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (visible || progress.value > 0f) placeable.place(0, 0)
        }
    }

/**
 * [Outline] as a drawn [Path]: `Rectangle`/`Rounded` are built (they have no
 * path of their own), `Generic` already is one.
 */
private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

/**
 * The test-only semantics keys, updated every frame in this isolated scope
 * (a zero-size, no-content child). Reading them in composition here keeps
 * `GlassSurface`'s own body — and the caller's content — recomposition-free
 * during the animations.
 */
@Composable
private fun GlassTestSemantics(progress: State<Float>, press: GlassPressState?) {
    val p = progress.value
    val scale = press?.let { 1f + (Glass.PressScale - 1f) * it.s.value }
    Box(
        Modifier.semantics {
            this[GlassProgressKey] = p
            if (scale != null) this[GlassPressScaleKey] = scale
        },
    )
}

// ---------------------------------------------------------------------------
// The shared screenshot fixture (previews + GlassSurfaceScreenshotTest)
// ---------------------------------------------------------------------------

/**
 * The ticket's step 9 fixture: a 400 × 300 dp page of `palette.background`,
 * a haze source of 8 × 20 dp stripes covering y 0–160 dp, and five surfaces
 * as siblings *after* the source (never inside it):
 *
 *  - a capsule with centred "Archived" over the stripes;
 *  - cards of radius 26 (over stripes), 18 (tinted, over stripes) and 20
 *    (over the plain page, `visible = [materializeVisible]` — renders
 *    nothing in the static shot);
 *  - the empty probe capsule over the plain page, around which the
 *    light-mode pixel probes are taken.
 *
 * Plain `BasicText` in monospace 13 sp so the fixture does not depend on
 * ticket 03's type scale.
 */
@Composable
internal fun GlassSurfaceFixture(materializeVisible: Boolean = false) {
    val palette = RobocoTheme.palette
    ProvideHazeState {
        Box(Modifier.size(400.dp, 300.dp).background(palette.background)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .hazeSource(LocalHazeState.current!!),
            ) {
                for (i in 0 until 8) {
                    Box(Modifier.fillMaxWidth().height(20.dp).background(fixtureStripe(palette, i)))
                }
            }
            GlassSurface(
                modifier = Modifier.offset(20.dp, 20.dp).size(200.dp, 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                FixtureLabel("Archived", palette.text)
            }
            GlassSurface(Modifier.offset(20.dp, 90.dp).size(160.dp, 64.dp), shape = Glass.shape(26.dp))
            GlassSurface(
                Modifier.offset(200.dp, 90.dp).size(160.dp, 64.dp),
                shape = Glass.shape(18.dp),
                tint = palette.accent,
            )
            GlassSurface(
                Modifier.offset(20.dp, 180.dp).size(160.dp, 64.dp),
                shape = Glass.shape(20.dp),
                visible = materializeVisible,
            )
            GlassSurface(Modifier.offset(200.dp, 220.dp).size(160.dp, 44.dp))
        }
    }
}

@Composable
private fun FixtureLabel(text: String, color: Color) {
    BasicText(
        text,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = color),
    )
}

/** `palette.accent` / `palette.text` @0.5, alternating (alpha via [rgba]). */
private fun fixtureStripe(palette: Palette, i: Int): Color = when (i % 2) {
    0 -> palette.accent
    else -> if (palette.isDark) rgba(0xE8E8EA, 0.5) else rgba(0x27272C, 0.5)
}

// ---------------------------------------------------------------------------
// Previews (step 7)
// ---------------------------------------------------------------------------

@Preview(name = "Glass light")
@Composable
private fun GlassSurfaceLightPreview() {
    RobocoTheme(Appearance.Light) { GlassSurfaceFixture() }
}

@Preview(name = "Glass dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GlassSurfaceDarkPreview() {
    RobocoTheme(Appearance.Dark) { GlassSurfaceFixture() }
}

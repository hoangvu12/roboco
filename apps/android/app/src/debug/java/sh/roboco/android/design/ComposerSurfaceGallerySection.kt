package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.composer.ComposerAttachButton
import sh.roboco.android.composer.ComposerSurface
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The "Composer surface" page of the debug design gallery
 * (android-ui-parity #55): the static half of the composer's capsule ⇄ card
 * morph over ticket 22's busy backdrop — five stages, each 200 dp tall
 * with the 366 dp composer (a 390 dp phone minus the 12 + 12 session
 * margins, `ios:Session/SessionViewController.swift:157-161`) anchored at
 * the stage's bottom centre, 12 dp above the bottom edge:
 *
 *  1. `p = 0` — the capsule: "Message" placeholder, no chips;
 *  2. `p = 0.5` — the frozen mid-morph frame: the chips half clipped by the
 *     25 dp band, half transparent;
 *  3. `p = 1` — the card: three chip stand-ins, the accent action circle;
 *  4. `p = 1` with the thumbnail stand-in (68 dp);
 *  5. the live stage: tapping the "Tap to morph" caption toggles the
 *     target, and `p` animates with `Motion.ComposerMorph` (ticket 04),
 *     snapping under Reduce Motion; `chipsInteractive` follows the target.
 *
 * The slot contents are **private stand-ins** (kept here, not in 57/58/59/
 * 88), so this golden does not churn when those tickets land: an empty box
 * of the text slot's height (50 → 46), the "Message" placeholder, three
 * `controlFill` capsules, an accent circle with the send glyph, two 56 dp
 * thumbnails. Under the Roborazzi capture (`LocalGalleryCapture`) the live
 * stage renders `p = 0` statically and Reduce Motion is forced, so the
 * golden is byte-stable.
 *
 * Registered in `Gallery.sections` as "Composer surface" (slug
 * `composer_surface`, goldens `gallery_composer_surface_{light,dark}.png`
 * via ticket 22's parameterized gallery test).
 */
@Composable
fun ComposerSurfaceGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ComposerSurfaceStage(p = 0f, chipsInteractive = false)
        ComposerSurfaceStage(p = 0.5f, chipsInteractive = true)
        ComposerSurfaceStage(p = 1f, chipsInteractive = true)
        ComposerSurfaceStage(p = 1f, chipsInteractive = true, withThumbnails = true)
        ComposerSurfaceLiveStage()
    }
}

/**
 * One static stage: the composer at a fixed morph position [p] over the
 * striped backdrop (the glass is the backdrop's sibling, never inside its
 * `hazeSource`).
 */
@Composable
private fun ComposerSurfaceStage(
    p: Float,
    chipsInteractive: Boolean,
    withThumbnails: Boolean = false,
) {
    ProvideHazeState {
        GlassStage(
            height = 200.dp,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) {
            ComposerSurface(
                progress = { p },
                chipsInteractive = chipsInteractive,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .width(366.dp),
                thumbnails = { if (withThumbnails) ThumbsStandIn() },
                text = { TextStandIn(p) },
                placeholder = { PlaceholderStandIn() },
                attach = { ComposerAttachButton(onClick = {}) },
                chips = { ChipsStandIn() },
                action = { ActionStandIn() },
            )
        }
    }
}

/** The live stage: the caption toggle and the animating composer. */
@Composable
private fun ComposerSurfaceLiveStage() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val capture = LocalGalleryCapture.current
    val reduce = LocalReduceMotion.current
    var target by remember { mutableStateOf(false) }
    val p = remember { Animatable(0f) }
    LaunchedEffect(target, reduce) {
        // Interruptible: reversing mid-flight continues from the current
        // value; Reduce Motion snaps (56 owns the real rule).
        val to = if (target) 1f else 0f
        if (reduce) p.snapTo(to) else p.animateTo(to, Motion.ComposerMorph.spec())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            "Tap to morph",
            style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.accent),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { target = !target },
            ),
        )
        ProvideHazeState {
            GlassStage(
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
            ) {
                ComposerSurface(
                    // Under capture: the resting capsule, statically.
                    progress = { if (capture) 0f else p.value },
                    chipsInteractive = target,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .width(366.dp),
                    text = { TextStandIn(p.value) },
                    placeholder = { PlaceholderStandIn() },
                    attach = { ComposerAttachButton(onClick = {}) },
                    chips = { ChipsStandIn() },
                    action = { ActionStandIn() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The private stand-in slots (the golden stays stable while 57/58/59/88 land)
// ---------------------------------------------------------------------------

/** `50 + (46 − 50)·p` dp — 57's text-slot height glide. */
private fun standInTextHeight(p: Float): Dp = (50f + (46f - 50f) * p).dp

/** The text slot: an empty box of the height 57's field will have. */
@Composable
private fun TextStandIn(p: Float) {
    Box(Modifier.fillMaxWidth().height(standInTextHeight(p)))
}

/** The placeholder: "Message" in tertiary, one line, tail-truncated. */
@Composable
private fun PlaceholderStandIn() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        "Message",
        style = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.tertiary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The chip strip: three `controlFill` capsules, 6 dp apart, 34 dp high. */
@Composable
private fun ChipsStandIn() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in listOf("Opus 4.1", "High", "main")) {
            Box(
                Modifier
                    .clip(CapsuleShape)
                    .background(palette.controlFill)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.text))
            }
        }
    }
}

/** The action button: a 34 dp accent circle with the send glyph (58 owns the real one). */
@Composable
private fun ActionStandIn() {
    val palette = RobocoTheme.palette
    Box(
        Modifier.size(34.dp).clip(CapsuleShape).background(palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
    }
}

/** The thumbnail strip: two 56 dp rounded boxes, 8 dp apart, 68 dp tall (88 owns the real one). */
@Composable
private fun ThumbsStandIn() {
    val palette = RobocoTheme.palette
    Row(
        modifier = Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(2) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.secondary),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun ComposerSurfaceGalleryLightPreview() {
    RobocoTheme(Appearance.Light) { ComposerSurfaceGallerySection() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComposerSurfaceGalleryDarkPreview() {
    RobocoTheme(Appearance.Dark) { ComposerSurfaceGallerySection() }
}

package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import java.util.Locale
import sh.roboco.android.composer.ComposerAttachButton
import sh.roboco.android.composer.ComposerMorphState
import sh.roboco.android.composer.ComposerSurface
import sh.roboco.android.composer.composerControl
import sh.roboco.android.composer.composerInput
import sh.roboco.android.composer.composerMorphHost
import sh.roboco.android.composer.previewMorphState
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The "Composer morph" page of the debug design gallery
 * (android-ui-parity #56): ticket 55's [ComposerSurface] driven by the
 * **real** [rememberComposerMorphState] over ticket 22's busy backdrop —
 * the 366 dp composer (a 390 dp phone minus the 12 + 12 session margins)
 * anchored at the stage's bottom centre, 12 dp up, with a "Half-written
 * thought" draft and the two fixture chips (`model` "Opus 4.1", `effort`
 * "High").
 *
 * Try it live:
 *  - tapping a plain glass area focuses (tap-to-focus; taps on `+`, the
 *    chips or the action are theirs — the `composerControl` marker);
 *  - the **Focus** / **Blur** chips drive [ComposerMorphState.focus] /
 *    [ComposerMorphState.blur] (the blur sequence: hide suggestions,
 *    morph, scroll the text back to the top);
 *  - **Add image** / **Clear images** flip `hasImages` — an unfocused
 *    image arrival still animates to the card (the spring, not a snap);
 *  - the **Always card** toggle is the new-session canvas's
 *    `alwaysCard = true` — it snaps (iOS's `chipsAlwaysVisible` setter);
 *  - the gallery's Light/Dark and Force-reduce-motion toggles apply: under
 *    reduce motion the morph snaps.
 *
 * The mono readout below the stage tracks `isCard`, `focused` and
 * `progress` per frame through `snapshotFlow`. Under the Roborazzi
 * capture the section renders its initial state (the resting capsule,
 * progress 0) and Reduce Motion is forced, so the golden is byte-stable.
 *
 * Registered in `Gallery.sections` as "Composer morph" (slug
 * `composer_morph`, goldens `gallery_composer_morph_{light,dark}.png` via
 * ticket 22's parameterized gallery test).
 */
@Composable
fun ComposerMorphGallerySection() {
    var alwaysCard by remember { mutableStateOf(false) }
    var hasImages by remember { mutableStateOf(false) }
    val morph = rememberComposerMorphState(alwaysCard = alwaysCard, hasImages = { hasImages })

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProvideHazeState {
            GlassStage(
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
            ) {
                MorphStageComposer(morph = morph, withThumbnails = hasImages)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MorphChip("Focus", active = morph.isFocused) { morph.focus() }
            MorphChip("Blur", active = !morph.isFocused) { morph.blur() }
            MorphChip("Add image", active = hasImages) { hasImages = true }
            MorphChip("Clear images", active = !hasImages) { hasImages = false }
            MorphChip("Always card", active = alwaysCard) { alwaysCard = !alwaysCard }
        }
        MorphReadout(morph)
    }
}

/**
 * The per-frame readout: isCard, focused, progress — snapshotFlow into a
 * label, in a scope of its own so a running morph recomposes only this
 * line, never the composer above it.
 */
@Composable
private fun MorphReadout(morph: ComposerMorphState) {
    var readout by remember { mutableStateOf("isCard=false focused=false progress=0.000") }
    LaunchedEffect(morph) {
        snapshotFlow { Triple(morph.isCard, morph.isFocused, morph.progress) }
            .collect { (card, focused, p) ->
                readout = "isCard=$card focused=$focused progress=" +
                    String.format(Locale.ROOT, "%.3f", p)
            }
    }
    BasicText(
        readout,
        style = uiStyle(Face.Mono, LocalTypeScale.current.fixed(12f), RobocoTheme.palette.secondary),
    )
}

/**
 * The composer on the stage: 55's surface wired to [morph] — the glass
 * carries `composerMorphHost` (placement tracking + tap-to-focus), the
 * one `BasicTextField` (one field for both states, the pr621 lesson)
 * carries `composerInput`, and every control carries `composerControl`.
 */
@Composable
private fun BoxScope.MorphStageComposer(morph: ComposerMorphState, withThumbnails: Boolean) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val field = remember { TextFieldState("Half-written thought") }
    ComposerSurface(
        progress = { morph.progress },
        chipsInteractive = morph.isCard,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 12.dp)
            .width(366.dp)
            .composerMorphHost(morph),
        thumbnails = { if (withThumbnails) MorphThumbs() },
        text = {
            BasicTextField(
                state = field,
                textStyle = uiStyle(Face.Sans, typeScale.fixed(16.5f), palette.text),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 8),
                scrollState = morph.textScroll,
                modifier = Modifier
                    .fillMaxWidth()
                    .composerInput(morph),
            )
        },
        placeholder = { MorphPlaceholder() },
        attach = {
            Box(Modifier.composerControl(morph)) { ComposerAttachButton(onClick = {}) }
        },
        chips = { MorphChips(morph) },
        action = { MorphAction(morph) },
    )
}

// ---------------------------------------------------------------------------
// The private stand-in slots (55's gallery keeps its own; these stay stable
// while 57/58/59/88 land)
// ---------------------------------------------------------------------------

/** The placeholder: "Message" in tertiary, one line. */
@Composable
private fun MorphPlaceholder() {
    BasicText(
        "Message",
        style = uiStyle(Face.Sans, LocalTypeScale.current.fixed(16.5f), RobocoTheme.palette.tertiary),
        maxLines = 1,
    )
}

/** The chip strip: two `controlFill` capsules with the `composerControl` marker. */
@Composable
private fun MorphChips(morph: ComposerMorphState) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in listOf("Opus 4.1", "High")) {
            Box(
                Modifier
                    .clip(CapsuleShape)
                    .background(palette.controlFill)
                    .padding(horizontal = 11.dp, vertical = 7.dp)
                    .composerControl(morph),
            ) {
                BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(13.5f), palette.text))
            }
        }
    }
}

/** The action button: a 34 dp accent circle with the send glyph (58 owns the real one). */
@Composable
private fun MorphAction(morph: ComposerMorphState) {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .size(34.dp)
            .clip(CapsuleShape)
            .background(palette.accent)
            .composerControl(morph),
        contentAlignment = Alignment.Center,
    ) {
        Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
    }
}

/** The thumbnail strip: two 56 dp rounded boxes, 8 dp apart, 68 dp tall (88 owns the real one). */
@Composable
private fun MorphThumbs() {
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

/** A plain text chip; a `clickable` BasicText, no Material controls. */
@Composable
private fun MorphChip(text: String, active: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, typeScale.fixed(14f), if (active) palette.accent else palette.secondary),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/**
 * The ticket's preview fixture: 55's [ComposerSurface] with a
 * [previewMorphState] frozen at 0.5 (the mid-morph frame, target the
 * card), the "Half-written thought" draft and the two fixture chips.
 */
@Composable
private fun ComposerMorphPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        ProvideHazeState {
            GlassStage(
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
            ) {
                val morph = previewMorphState(0.5f, card = true)
                MorphStageComposer(morph = morph, withThumbnails = false)
            }
        }
    }
}

@Preview(name = "Morph 0.5 light")
@Composable
private fun ComposerMorphHalfLightPreview() {
    ComposerMorphPreviewBody(Appearance.Light)
}

@Preview(name = "Morph 0.5 dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComposerMorphHalfDarkPreview() {
    ComposerMorphPreviewBody(Appearance.Dark)
}

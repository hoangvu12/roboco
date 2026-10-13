package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle

/**
 * The "Composer thumbnails" page of the debug design gallery
 * (android-ui-parity #88), registered in ticket 22's registry as
 * `GallerySection("Composer thumbnails", screenshotHeightDp = 760)`:
 *
 *  - a static stage with **3** fixture images — the resting card look;
 *  - a static stage with **8** fixture images — the strip scrolls and the
 *    trailing 24 dp edge fade is visible (only 5 tiles fit at 366 dp);
 *  - a **live** stage: x really removes (the 250 ms slide, the collapse
 *    of the last one), with the "Add image" (capped at 8, like 54's
 *    `addImages`) and "Reset" captions. Under
 *    [LocalGalleryCapture] (the Roborazzi pass) it renders the 3-image
 *    fixture statically, so the goldens stay byte-stable.
 *
 * Each stage is ticket 22's [GlassStage] (220 dp) holding ticket 55's
 * [ComposerSurface] at `p = 1`, the 366 dp composer anchored bottom-centre
 * 12 dp up, with this ticket's real [StagedThumbnailStrip] in the
 * `thumbnails` slot, a 46 dp text stand-in and the 34 dp accent action
 * circle (the same stand-ins 55's gallery uses; 57 and 58 own the real
 * slots). The x buttons carry 56's `composerControl` marker over a frozen
 * [previewMorphState], exactly as 78 will wire them.
 */
@Composable
fun StagedThumbnailsGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    ProvideHazeState {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption(
                "Composer thumbnails",
                uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            )
            Caption(
                "The card's staged-photo strip: 56 dp tiles with 14 dp continuous corners, " +
                    "a 1 px hairline and 8 dp gaps, 12 dp below the glass's top. x removes over " +
                    "250 ms — the survivors slide, the strip collapses with the last one. " +
                    "Up to 8 images; past 5 the strip scrolls with edge fades.",
                uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
            StaticThumbsStage("3 images", 3)
            StaticThumbsStage("8 images - trailing edge fade", 8)
            LiveThumbsStage()
        }
    }
}

/** One static stage: [count] fixture images in the card. */
@Composable
private fun StaticThumbsStage(caption: String, count: Int) {
    val images = remember { (0 until count).map { fixtureStagedImage(it) } }
    ThumbsStageFrame(caption) {
        ThumbsStageComposer(images = images, onRemove = {})
    }
}

/**
 * The live stage: x removes from a [mutableStateListOf] copy of the
 * fixture (the 250 ms slide and collapse run for real). Static under the
 * Roborazzi capture.
 */
@Composable
private fun LiveThumbsStage() {
    if (LocalGalleryCapture.current) {
        StaticThumbsStage("3 images (capture)", 3)
        return
    }
    val palette = RobocoTheme.palette
    val images = remember {
        mutableStateListOf<StagedImage>().apply { addAll((0 until 3).map { fixtureStagedImage(it) }) }
    }
    var nextSeed by remember { mutableStateOf(3) }
    ThumbsStageFrame("Live - x removes; Add image / Reset") {
        ThumbsStageComposer(images = images, onRemove = { id -> images.removeAll { it.id == id } })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GalleryChip("Add image", palette.accent) {
            if (images.size < ComposerController.MaxImages) {
                images.add(fixtureStagedImage(nextSeed))
                nextSeed++
            }
        }
        GalleryChip("Reset", palette.secondary) {
            images.clear()
            images.addAll((0 until 3).map { fixtureStagedImage(it) })
            nextSeed = 3
        }
    }
}

/** The caption + 220 dp glass stage every thumbs stage sits in. */
@Composable
private fun ThumbsStageFrame(caption: String, content: @Composable BoxScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(caption, uiStyle(Face.Sans, LocalTypeScale.current.fixed(12f), RobocoTheme.palette.secondary))
        GlassStage(
            height = 220.dp,
            backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
        ) { content() }
    }
}

/**
 * The composer on the stage: 55's [ComposerSurface] at `p = 1` with this
 * ticket's real [StagedThumbnailStrip], the 46 dp text stand-in and the
 * 34 dp accent action stand-in. The x markers ride a frozen morph state
 * (78's wiring shape; there is no field here to focus).
 */
@Composable
private fun BoxScope.ThumbsStageComposer(images: List<StagedImage>, onRemove: (String) -> Unit) {
    val morph = remember { previewMorphState(1f, card = true) }
    val palette = RobocoTheme.palette
    ComposerSurface(
        progress = { 1f },
        chipsInteractive = true,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 12.dp)
            .width(366.dp),
        thumbnails = {
            StagedThumbnailStrip(
                images = images,
                onRemove = onRemove,
                controlModifier = Modifier.composerControl(morph),
            )
        },
        text = { Box(Modifier.fillMaxWidth().height(46.dp)) },
        action = { ThumbsStageAction(morph) },
    )
}

/** The action stand-in (58 owns the real one): a 34 dp accent circle. */
@Composable
private fun ThumbsStageAction(morph: ComposerMorphState) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CapsuleShape)
            .background(RobocoTheme.palette.accent)
            .composerControl(morph),
        contentAlignment = Alignment.Center,
    ) {
        Icon(RobocoIcons.ArrowUp, tint = Color.White, sizePt = 15f, weight = SymbolWeight.Bold)
    }
}

/** A plain text chip: a `clickable` BasicText, no Material controls. */
@Composable
private fun GalleryChip(text: String, ink: Color, onClick: () -> Unit) {
    BasicText(
        text,
        style = uiStyle(Face.SansMedium, LocalTypeScale.current.fixed(14f), ink),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Caption(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews: stage 1 (3 fixture images) in light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "thumbs light")
@Composable
private fun ThumbsLightPreview() {
    ThumbsPreviewBody(Appearance.Light)
}

@Preview(name = "thumbs dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThumbsDarkPreview() {
    ThumbsPreviewBody(Appearance.Dark)
}

@Composable
private fun ThumbsPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        ProvideHazeState {
            GlassStage(
                height = 220.dp,
                backdropModifier = Modifier.hazeSource(LocalHazeState.current!!),
            ) {
                ThumbsStageComposer(
                    images = remember { (0 until 3).map { fixtureStagedImage(it) } },
                    onRemove = {},
                )
            }
        }
    }
}

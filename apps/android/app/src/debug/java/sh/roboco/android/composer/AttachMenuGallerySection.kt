package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
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
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassMenuPanel
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.flattenLevel
import sh.roboco.android.design.uiStyle

/**
 * The "Composer attach menu" page of the debug design gallery
 * (android-ui-parity #87), registered in ticket 22's registry as
 * `GallerySection("Composer attach menu", screenshotHeightDp = 700)`:
 *
 *  - two **static** [GlassMenuPanel]s — "All four" (camera + clipboard
 *    image present) and "Minimal" (neither: Photo Library and Choose File
 *    only) — the same panels the Roborazzi goldens
 *    (`attach_menu_full/minimal_{light,dark}.png`) shoot;
 *  - a **live** stage (skipped under [LocalGalleryCapture] — it needs a
 *    real [androidx.activity.result.ActivityResultRegistry], which the
 *    capture harness has no activity for): ticket 55's
 *    [ComposerSurface] driven by 56's real morph over a controller
 *    (54), with a real `BasicTextField` (57's slot), the real
 *    [ComposerAttachMenuButton] as the `+`, an `attachEnabled` toggle, a
 *    "Staged: N/8" readout and 40 dp thumbnail previews with their file
 *    names.
 *
 * The live stage wraps itself in its own [GlassMenuHost] (the registry's
 * [sh.roboco.android.design.GalleryProviders] installs no host): the
 * stage content is the haze source the menu panels blur, and the menus
 * open in-window so the IME check (open the menu while focused — the
 * keyboard must stay up) works like the GlassMenu section's composer
 * demo.
 *
 * Try it live (`adb shell am start -n sh.roboco.android/.MainActivity
 * --es route gallery --es section composer_attach_menu`): tap `+` on the
 * resting capsule — the menu covers the button and grows up from its
 * bottom-left corner, the composer neither moves nor morphs; pick three
 * photos and watch three thumbnails (with `image-xxxxxxxx.jpg` names)
 * appear in pick order, the morph to the card, and the menu's Photo
 * Library limit drop to 5 on the next open.
 */
@Composable
fun AttachMenuGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption(
            "Composer attach menu",
            uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
        )
        Caption(
            "The + pull-down: Photo Library, Take Photo (camera devices), Choose File, " +
                "Paste Image (clipboard image). Rebuilt on every open; picks are staged " +
                "(2560 px, JPEG 86, 24 MiB cap) and capped at 8.",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        StaticMenuStage("All four", attachMenu(hasCamera = true, clipHasImage = true) {})
        StaticMenuStage("Minimal (no camera, no clipboard image)", attachMenu(hasCamera = false, clipHasImage = false) {})
        if (!LocalGalleryCapture.current) {
            LiveStage()
        }
    }
}

/**
 * One static menu stage: the panel drawn straight from the flattened
 * model over the busy backdrop (no host, so no interaction — the live
 * stage below is the interactive one). 4 rows → 10 + 4×42 + 10 = 188 dp;
 * 2 rows → 104 dp; both fit the 230 dp stage with the caption.
 */
@Composable
private fun StaticMenuStage(caption: String, menu: Menu) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(caption, uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary))
        GlassStage(height = 230.dp) {
            GlassMenuPanel(
                lines = flattenLevel(
                    title = null,
                    elements = menu.children,
                    resolved = { null },
                    isRoot = true,
                    backTitle = null,
                ),
                onRow = {},
                onSubmenu = {},
                onBack = {},
            )
        }
    }
}

/**
 * The live stage: the whole block is wrapped in [GlassMenuHost] (the
 * composer must sit over a haze source, and the menu needs the host).
 */
@Composable
private fun LiveStage() {
    GlassMenuHost(hazeState = rememberHazeState()) {
        LiveBody()
    }
}

@Composable
private fun LiveBody() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val controller = rememberComposerController()
    val morph = rememberComposerMorphState(
        alwaysCard = false,
        hasImages = { controller.images.isNotEmpty() },
    )
    var attachEnabled by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassStage(height = 230.dp) {
            AttachStageComposer(controller, morph)
        }
        Caption(
            "Staged: ${controller.images.size}/${ComposerController.MaxImages}",
            uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            for (image in controller.images) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Image(
                        bitmap = image.thumb,
                        contentDescription = image.name,
                        modifier = Modifier.size(40.dp),
                    )
                    BasicText(
                        image.name,
                        style = uiStyle(Face.Sans, typeScale.fixed(11f), palette.secondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(56.dp),
                    )
                }
            }
        }
        BasicText(
            "attachEnabled: ${if (attachEnabled) "on" else "off"}",
            style = uiStyle(Face.SansMedium, typeScale.fixed(14f), palette.text),
            modifier = Modifier
                .clickable {
                    attachEnabled = !attachEnabled
                    controller.attachEnabled = !attachEnabled
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * The composer on the stage (78 has not landed, so this is the surface
 * shape the morph gallery uses): 55's [ComposerSurface] wired to [morph]
 * — the glass carries `composerMorphHost`, the one `BasicTextField`
 * carries `composerInput`, and the `+` is this ticket's real
 * [ComposerAttachMenuButton] (the marker is applied inside it).
 */
@Composable
private fun BoxScope.AttachStageComposer(controller: ComposerController, morph: ComposerMorphState) {
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
        placeholder = {
            BasicText(
                "Message",
                style = uiStyle(Face.Sans, typeScale.fixed(16.5f), RobocoTheme.palette.tertiary),
                maxLines = 1,
            )
        },
        attach = { ComposerAttachMenuButton(controller, morph) },
        action = { AttachStageAction(morph) },
    )
}

/** The action stand-in (58 owns the real one): a 34 dp accent circle. */
@Composable
private fun AttachStageAction(morph: ComposerMorphState) {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .size(34.dp)
            .clip(CapsuleShape)
            .background(palette.accent)
            .composerControl(morph),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            RobocoIcons.ArrowUp,
            tint = Color.White,
            sizePt = 15f,
            weight = SymbolWeight.Bold,
        )
    }
}

@Composable
private fun Caption(text: String, style: TextStyle) {
    BasicText(text, style = style)
}

// ---------------------------------------------------------------------------
// Previews: the two static panels, light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun AttachMenuLightPreview() {
    AttachMenuPanelsPreview(Appearance.Light)
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AttachMenuDarkPreview() {
    AttachMenuPanelsPreview(Appearance.Dark)
}

@Composable
private fun AttachMenuPanelsPreview(appearance: Appearance) {
    RobocoTheme(appearance) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StaticMenuStage("All four", attachMenu(hasCamera = true, clipHasImage = true) {})
            StaticMenuStage("Minimal", attachMenu(hasCamera = false, clipHasImage = false) {})
        }
    }
}

package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.rememberComposerController
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.wallpaperFixture

/**
 * The "New session canvas" page of the debug design gallery
 * (android-ui-parity #93), registered in ticket 22's registry as
 * `GallerySection("New session canvas", GalleryLayout.Fill)`:
 * the canvas itself — 21's wallpaper hero with the live cutout behind the
 * composer, the harness mark + headline centred in the free space, the
 * sheet nav bar — over a real [ComposerBar] (78) driven by 54's controller
 * and 56's morph.
 *
 * The whole section sits inside a [GlassMenuHost] (ticket 15), as 87's
 * gallery does, so the composer's attach menu and the chip menus can open.
 *
 * Five toggles:
 *  - **Embedded** — the tablet detail column's 44 dp nav row, no ✕;
 *  - **Keyboard** — `imeAbove = 300` with the canvas in a 300 dp bottom
 *    padding, simulating 31's `imePadding` (the wallpaper keeps the
 *    IME-less page height);
 *  - **Harness** — cycles the nine harnesses; the mark redraws with no
 *    fade (Claude keeps its orange);
 *  - **No wallpaper** — 21's `load` returns null: plain background;
 *  - **Narrow** — a 200 dp wide page, which gives the 2-line headline.
 *
 * Try it live (`adb shell am start -n sh.roboco.android/.MainActivity
 * --es route gallery --es section new_session_canvas`): tap the page to
 * dismiss the keyboard, watch the composer ride the Keyboard toggle, and
 * drag the (text-only) field.
 */
@Composable
fun NewSessionCanvasGallerySection() {
    GlassMenuHost(hazeState = rememberHazeState()) {
        NewSessionCanvasGalleryBody()
    }
}

/** The gallery body — GlassMenuHost is required, so the stage is split off. */
@Composable
private fun NewSessionCanvasGalleryBody() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    var embedded by remember { mutableStateOf(false) }
    var keyboard by remember { mutableStateOf(false) }
    var harnessIndex by remember { mutableStateOf(0) }
    var noWallpaper by remember { mutableStateOf(false) }
    var narrow by remember { mutableStateOf(false) }

    val controller = rememberComposerController()
    remember(controller) { configureCanvasGalleryComposer(controller); true }
    val morph = rememberComposerMorphState(
        alwaysCard = true,
        hasImages = { controller.images.isNotEmpty() },
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            BasicText(
                "New session canvas",
                style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
            )
            BasicText(
                "Tap the page to dismiss the keyboard; the wallpaper keeps " +
                    "its crop while the composer rides the Keyboard toggle.",
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CanvasGalleryToggle("Embedded", embedded) { embedded = !embedded }
                CanvasGalleryToggle("Keyboard", keyboard) { keyboard = !keyboard }
                CanvasGalleryToggle("Harness: ${CanvasHarnesses[harnessIndex]}", true) {
                    harnessIndex = (harnessIndex + 1) % CanvasHarnesses.size
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            ) {
                CanvasGalleryToggle("No wallpaper", noWallpaper) { noWallpaper = !noWallpaper }
                CanvasGalleryToggle("Narrow", narrow) { narrow = !narrow }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .then(if (narrow) Modifier.width(200.dp) else Modifier.fillMaxWidth()),
        ) {
            // 31's imePadding stand-in: the constraints shrink, the canvas
            // re-derives everything (wallpaper height included) per frame.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (keyboard) Modifier.padding(bottom = 300.dp) else Modifier),
            ) {
                NewSessionCanvas(
                    harness = CanvasHarnesses[harnessIndex],
                    embedded = embedded,
                    onClose = {},
                    onBackgroundTap = { morph.blur() },
                    composer = {
                        ComposerBar(controller, morph, Modifier.testTag("new-session-composer"))
                    },
                    insets = CanvasInsets(
                        top = if (embedded) 24f else 0f,
                        safeLeft = 0f,
                        safeRight = 0f,
                        bottom = if (keyboard) 0f else 24f,
                        imeAbove = if (keyboard) 300f else 0f,
                    ),
                    wallpaper = { m, c ->
                        WallpaperHero(
                            changeKey = Unit,
                            load = { _ -> if (noWallpaper) null else WallpaperRender(wallpaperFixture(), 1f) },
                            modifier = m,
                            cutout = c,
                        )
                    },
                )
            }
        }
    }
}

/** The fixture controller: the card composer with its two fake chips. */
private fun configureCanvasGalleryComposer(controller: ComposerController) {
    controller.alwaysCard = true
    controller.placeholder = "Describe the task"
    controller.canWrite = true
    controller.chips = listOf(
        ComposerChip("project", "roboco", icon = ChipIcon.Project("roboco", 0)),
        ComposerChip("model", "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
    )
}

/** One toggle row item: semibold text when on, plain when off. */
@Composable
private fun CanvasGalleryToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    BasicText(
        label,
        Modifier.clickable(role = Role.Button, onClick = onSelect),
        style = uiStyle(
            if (selected) Face.SansSemibold else Face.Sans,
            ts.fixed(14f),
            if (selected) palette.text else palette.secondary,
        ),
    )
}

/** The ticket's harness cycle order. */
private val CanvasHarnesses = listOf(
    "claude-code", "codex", "cursor", "devin", "grok", "hermes", "pi", "opencode", "antigravity",
)

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: the sheet canvas at 411 × 857, fixture wallpaper. */
@Composable
private fun NewSessionCanvasPreviewStage(appearance: Appearance) {
    RobocoTheme(appearance) {
        val controller = rememberComposerController()
        remember(controller) { configureCanvasGalleryComposer(controller); true }
        val morph = rememberComposerMorphState(
            alwaysCard = true,
            hasImages = { controller.images.isNotEmpty() },
        )
        NewSessionCanvas(
            harness = "claude-code",
            embedded = false,
            onClose = {},
            onBackgroundTap = { morph.blur() },
            composer = { ComposerBar(controller, morph, Modifier.testTag("new-session-composer")) },
            insets = CanvasInsets(0f, 0f, 0f, 24f, 0f),
            wallpaper = { m, c ->
                WallpaperHero(
                    changeKey = Unit,
                    load = { _ -> WallpaperRender(wallpaperFixture(), 1f) },
                    modifier = m,
                    cutout = c,
                )
            },
        )
    }
}

@Preview(name = "New session canvas light", widthDp = 411, heightDp = 857)
@Composable
private fun NewSessionCanvasGalleryLightPreview() {
    NewSessionCanvasPreviewStage(Appearance.Light)
}

@Preview(name = "New session canvas dark", widthDp = 411, heightDp = 857, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NewSessionCanvasGalleryDarkPreview() {
    NewSessionCanvasPreviewStage(Appearance.Dark)
}

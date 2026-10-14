package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.MentionSuggestionsHost
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.StatusPillRow

/**
 * The "Session offline" page of the debug design gallery
 * (android-ui-parity #79): the read-only state of a session whose engine
 * dropped — the **fixture, not a screen**. A 393 dp column (12 dp side
 * padding, rows 8 dp apart — iOS `bottom.spacing = 8`,
 * `ios:Session/SessionViewController.swift:120`) on ticket 22's
 * [GalleryBackdrop], top to bottom:
 *
 * 1. 73's [StatusPillRow] with the `Reconnecting` banner — the pill at
 *    the start of a full-width row (`pillRow = [pill, UIView()]`, `:123`);
 * 2. the composer exactly as 78 assembles it, with `canWrite = false`:
 *    - a running turn with a draft would derive action `Queue`, but
 *      offline every action renders the disabled **send** arrow
 *      (`ArrowUp` 15, `controlFill` fill, `tertiary` ink) — not Stop and
 *      not the accent Queue arrow;
 *    - both chips draw `tertiary` ink and open no menu;
 *    - the text `Draft kept while offline` stays editable in `text` ink
 *      (an offline engine never touches text, images or focus);
 *    - the attach `+` stays enabled — staging is local, the upload only
 *      happens inside a send (android-app#08).
 *
 * Try it live: the `canWrite` row under the fixture flips the flag, so
 * the action button snaps to the live Queue arrow and back (the
 * enabled ↔ disabled colour change snaps — no spring of its own; 58's
 * rule). The row is hidden under the Roborazzi capture, so the golden
 * shows the read-only state only.
 *
 * Reduced motion: nothing extra — 58 and 73 apply their own
 * reduced-motion rules, and nothing here animates on its own.
 *
 * Registered in `Gallery.sections` as "Session offline" (slug
 * `session_offline`, goldens `gallery_session_offline_{light,dark}.png`
 * via ticket 22's parameterized gallery test — no second screenshot test
 * class). iOS has no read-only state to compare against; the button's
 * disabled look is ticket 58's.
 */
@Composable
fun SessionOfflineGallerySection() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) {
        ComposerController(scope, NoFeedback, initialText = "Draft kept while offline").apply {
            running = true
            canWrite = false
            alwaysCard = true
            chips = listOf(
                ComposerChip("model", "Fable 5", icon = ChipIcon.Brand("mock")),
                ComposerChip("effort", "High", symbol = "gauge.with.dots.needle.67percent"),
            )
        }
    }

    // The session screen's composer stack minus the transcript (78's
    // gallery stage): the GlassMenuHost the chip strip needs, the haze
    // state with the striped backdrop as its source (the glass blurs the
    // backdrop, never itself) and the mention host ComposerBar registers
    // with. `alwaysCard = true` makes the chip row show.
    GlassMenuHost(hazeState = rememberHazeState()) {
        ProvideHazeState {
            MentionSuggestionsHost(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    GalleryBackdrop(Modifier.matchParentSize().hazeSource(LocalHazeState.current!!))
                    Column(
                        Modifier.width(393.dp).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPillRow(banner = SessionChrome.Banner.Reconnecting, onTap = {})
                        ComposerBar(
                            controller,
                            rememberComposerMorphState(
                                alwaysCard = true,
                                hasImages = { controller.images.isNotEmpty() },
                            ),
                            Modifier.fillMaxWidth(),
                        )
                        if (!LocalGalleryCapture.current) {
                            // The live toggle (never in the capture): flips
                            // the controller's canWrite so the disabled
                            // look snaps to the live Queue arrow and back.
                            val palette = RobocoTheme.palette
                            val typeScale = LocalTypeScale.current
                            BasicText(
                                "canWrite: " + if (controller.canWrite) "on" else "off",
                                Modifier.clickable { controller.canWrite = !controller.canWrite },
                                style = uiStyle(Face.Mono, typeScale.fixed(12f), palette.accent),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews: the fixture in light and dark (the BRIEF's component-ticket
// convention; the golden itself comes from ticket 22's parameterized
// GalleryScreenshotTest, which wraps the section in GalleryProviders).
// ---------------------------------------------------------------------------

@Preview(name = "Session offline light")
@Composable
private fun SessionOfflineLightPreview() {
    RobocoTheme(Appearance.Light) { SessionOfflineGallerySection() }
}

@Preview(name = "Session offline dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionOfflineDarkPreview() {
    RobocoTheme(Appearance.Dark) { SessionOfflineGallerySection() }
}

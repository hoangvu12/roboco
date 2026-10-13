package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "App root" page of the debug design gallery (android-ui-parity
 * #35), registered in ticket 22's `Gallery.sections` as
 * `GallerySection("App root", GalleryLayout.Fill) { AppRootGallerySection() }`.
 *
 * A framed [AppRoot] with the fake roots (the fills differ so the fade is
 * visible), plus the ticket's three levers:
 *
 * - **"Paired"** toggles `Ready(hasEngines)` — the Pairing ↔ Shell swap,
 *   the 350 ms cross-fade, and the instant dismissal: the fake sheet
 *   below vanishes in the same frame the swap starts (it is registered
 *   with [RegisterRootPresentation]);
 * - **"393" / "900"** re-wrap the frame with [Modifier.requiredWidth] —
 *   the Phone ↔ Split switch is instant, in place, with no fade;
 * - **"Present fake sheet"** shows the 200 dp `palette.accent` panel at
 *   the frame's bottom.
 *
 * The frame starts at the ticket's 320 × 560 dp and is centred and
 * scaled down (`min(1, maxWidth/width, maxHeight/560)`) to fit the
 * section body.
 */
@Composable
fun AppRootGallerySection() {
    val typeScale = LocalTypeScale.current
    var paired by remember { mutableStateOf(false) }
    var frameWidth by remember { mutableStateOf(320.dp) }
    var sheetUp by remember { mutableStateOf(false) }
    val presentations = remember { RootPresentations() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Toggle(if (paired) "Paired" else "Unpaired", paired) { paired = !paired }
            Toggle("393", frameWidth == 393.dp) { frameWidth = 393.dp }
            Toggle("900", frameWidth == 900.dp) { frameWidth = 900.dp }
            Toggle("Present fake sheet", sheetUp) { sheetUp = !sheetUp }
        }
        BasicText(
            "Toggling Paired with the sheet up removes it instantly, then cross-fades.",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), RobocoTheme.palette.secondary),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scale = minOf(1f, maxWidth / frameWidth, maxHeight / 560.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // The same registry the cross-fade dismisses: the fake
                // sheet registers through LocalRootPresentations, and
                // AppRoot's beforeSwap tears it down in the swap frame.
                CompositionLocalProvider(LocalRootPresentations provides presentations) {
                    FramedAppRoot(
                        frameWidth = frameWidth,
                        scale = scale,
                        paired = paired,
                        sheetUp = sheetUp,
                        presentations = presentations,
                        onSheetDismiss = { sheetUp = false },
                    )
                }
            }
        }
    }
}

/** The framed [AppRoot] with the fake roots and the fake sheet panel. */
@Composable
private fun FramedAppRoot(
    frameWidth: Dp,
    scale: Float,
    paired: Boolean,
    sheetUp: Boolean,
    presentations: RootPresentations,
    onSheetDismiss: () -> Unit,
) {
    val palette = RobocoTheme.palette
    Box(
        Modifier
            .requiredWidth(frameWidth)
            .requiredHeight(560.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        AppRoot(
            input = RootInput.Ready(hasEngines = paired),
            state = remember { AppRootState() },
            presentations = presentations,
            pairing = { FakeRoot("Pairing", palette.accent, palette.text) },
            phone = { FakeRoot("Phone", palette.background, palette.text) },
            split = { FakeRoot("Split", palette.codeBackground, palette.text) },
        )
        // The fake sheet: an in-window overlay registered with the root
        // registry — a root swap dismisses it in the same frame.
        RegisterRootPresentation(active = sheetUp, dismissInstantly = onSheetDismiss)
        if (sheetUp) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(palette.accent),
            )
        }
    }
}

/** One toggle: SemiBold `text` when selected, Regular `secondary` when not. */
@Composable
private fun Toggle(label: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicText(
        label,
        Modifier.clickable { onSelect() },
        style = uiStyle(
            if (selected) Face.SansSemibold else Face.Sans,
            typeScale.fixed(14f),
            if (selected) palette.text else palette.secondary,
        ),
    )
}

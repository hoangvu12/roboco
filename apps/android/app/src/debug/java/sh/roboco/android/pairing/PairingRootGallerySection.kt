package sh.roboco.android.pairing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Pairing root" page of the debug design gallery (android-ui-parity
 * #34), registered in ticket 22's `Gallery.sections` as
 * `GallerySection("Pairing root", layout = GalleryLayout.Fill, screenshotHeightDp = 960) { PairingRootGallerySection() }`.
 *
 * A row of three plain-`BasicText` toggles (no Material controls; the
 * selected one in `palette.text` SemiBold, the others `palette.secondary`)
 * switches the status state — `None` / `Redeeming` / an example error — and
 * an `onPair × n` counter below them proves the capsule is live. Under
 * `Redeeming` the capsule shows ticket 08's busy spinner and ignores taps.
 *
 * The root itself renders in a fixed 393 × 852 phone frame with the
 * previews' iPhone safe insets (59 top / 34 bottom), centred and scaled
 * down (`min(1, maxWidth/393, maxHeight/852)`) to fit the section body —
 * `GalleryLayout.Fill` hands it the whole window, and the gallery's own
 * Light/Dark and Force-reduce-motion toggles flow through
 * `GalleryProviders` (ticket 22), so nothing extra is wired here.
 */
@Composable
fun PairingRootGallerySection() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    var status by remember { mutableStateOf<PairingRootStatus>(PairingRootStatus.None) }
    var pairs by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatusToggle("None", status is PairingRootStatus.None) {
                status = PairingRootStatus.None
            }
            StatusToggle("Redeeming", status is PairingRootStatus.Redeeming) {
                status = PairingRootStatus.Redeeming
            }
            StatusToggle("Error", status is PairingRootStatus.Error) {
                status = PairingRootStatus.Error(PairingRootExampleError)
            }
        }
        BasicText(
            "onPair × $pairs",
            style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scale = minOf(1f, maxWidth / 393.dp, maxHeight / 852.dp)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PairingRoot(
                    status = status,
                    onPair = { pairs++ },
                    modifier = Modifier
                        .requiredSize(393.dp, 852.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    safeInsets = WindowInsets(top = 59.dp, bottom = 34.dp),
                )
            }
        }
    }
}

/** One status toggle: SemiBold `text` when selected, Regular `secondary` when not. */
@Composable
private fun StatusToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
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

package sh.roboco.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.transcript.widgets.UploadRing
import sh.roboco.android.transcript.widgets.UploadRingFixturePink
import sh.roboco.android.transcript.widgets.UploadRingFixtureTeal
import sh.roboco.android.transcript.widgets.UploadRingFixtureTile
import sh.roboco.android.transcript.widgets.imageTileRadius

/**
 * The "Upload ring" section of the debug design gallery (ticket 67):
 * the ring over pending thumbnails, in the Rust geometry of both tile
 * kinds.
 *
 *  - row 1: 76 dp thumbnails at the four percent cases (0 %, the 2 %
 *    arc floor, 42 %, 100 %) with the drawn value as caption;
 *  - row 2: the geometry cases — a 260 dp image-row tile (14 dp
 *    corners) and a 50 dp tile (d 27.5, the 9 dp label branch);
 *  - row 3: a **live** 76 dp tile (a real 200 ms fade-in / 250 ms
 *    fade-out, `startVisible = false`) with the upstream demo cadence
 *    button: 24 steps of `i / 24`, 110 ms apart, then null
 *    (`crates/client/src/demo/mod.rs:311-316`). A second tap while it
 *    runs is ignored;
 *  - row 4: a host-path reference with progress set — no ring at all.
 *
 * Everything is foundation-only (plain `BasicText` + `clickable`, no
 * Material). The static rows reuse [UploadRingFixtureTile]; the live row
 * composes the same teal/pink backing by hand because the fixture always
 * passes `startVisible = true`.
 *
 * Registered in `Gallery.sections` as "Upload ring" (slug `upload_ring`);
 * the defaults (Scroll layout, 891 dp shot height) fit the ~650 dp of
 * content.
 */
@Composable
fun UploadRingGallerySection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Row 1: the percent cases at the user-thumbnail size.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptionedFixtureTile(76f, 0.0, "0%")
            CaptionedFixtureTile(76f, 0.02, "2%")
            CaptionedFixtureTile(76f, 0.42, "42%")
            CaptionedFixtureTile(76f, 1.0, "100%")
        }
        // Row 2: the geometry cases — 260 dp (14 dp corners) and 50 dp
        // (d 27.5, the 9 dp label branch).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UploadRingFixtureTile(260f, 0.42)
            UploadRingFixtureTile(50f, 0.42)
        }
        // Row 3: the live tile and the demo cadence.
        UploadDemoRow()
        // Row 4: a host-path reference never shows a ring.
        UploadRingFixtureTile(76f, 0.42, reference = "/abs/path.png")
    }
}

/** One fixture tile with its drawn percent under it. */
@Composable
private fun CaptionedFixtureTile(sizeDp: Float, p: Double, caption: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        UploadRingFixtureTile(sizeDp, p)
        BasicText(
            caption,
            style = uiStyle(Face.Sans, typeScale.fixed(12f), palette.secondary),
        )
    }
}

/**
 * The live tile and the "Simulate upload" control: the tile holds its
 * progress in local state (null until the first tap), the button runs
 * the upstream demo cadence in a coroutine scope.
 */
@Composable
private fun UploadDemoRow() {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf<Double?>(null) }
    var running by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The live tile: the same teal/pink backing as the fixture, but a
        // real fade — `startVisible = false`, progress from local state.
        Box(
            Modifier
                .size(76.dp)
                .clip(rememberSmoothRoundedShape(imageTileRadius(76f, 76f)))
                .background(UploadRingFixtureTeal),
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight(100f / 180f)
                    .background(UploadRingFixturePink),
            )
            UploadRing(progress = { p }, modifier = Modifier.matchParentSize(), startVisible = false)
        }
        // A plain text button (not Material): the accent colour, no
        // indication, disabled while the cadence is running.
        BasicText(
            "Simulate upload",
            Modifier.clickable(
                interactionSource = null,
                indication = null,
                enabled = !running,
            ) {
                if (!running) {
                    running = true
                    scope.launch {
                        // The upstream demo cadence (demo/mod.rs:311-316):
                        // 24 steps, 110 ms apart, then null.
                        for (i in 1..24) {
                            p = i / 24.0
                            delay(110)
                        }
                        p = null
                        running = false
                    }
                }
            },
            style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent),
        )
    }
}

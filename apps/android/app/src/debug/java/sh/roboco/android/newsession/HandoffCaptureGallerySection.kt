package sh.roboco.android.newsession

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassTextCapsule
import sh.roboco.android.design.uiStyle

/**
 * The "New session: handoff capture" page of the debug design gallery
 * (android-ui-parity #94), registered in ticket 22's registry as
 * `GallerySection("New session: handoff capture", screenshotHeightDp = 891)`
 * (slug `new_session_handoff_capture`).
 *
 * A miniature of the real page's wiring, so the capture is drivable by
 * hand: a 240 × 420 dp fake sheet page (`Palette.background`, 31's 38 dp
 * top-corner shape as the stand-in `sceneShape`) whose root carries
 * [Modifier.handoffScene] and whose fixture composer (216 × 96 dp,
 * `Palette.elevated`, the 26 dp card radius) carries
 * [Modifier.handoffComposer] with a text slot that hides via
 * [HandoffCaptureState.textHidden] and reports its inset rect to
 * [HandoffCaptureState.onTextRect], plus one fixture chip.
 *
 * One capture runs automatically on composition (`LaunchedEffect`) —
 * reset right after, so the page returns to its live state — and its
 * three parts show below at 0.5 scale: the scene, the composer, and
 * the composer with a 1 dp accent outline of the text rect (mapped
 * into the composer bitmap's coordinates). Two glass capsules (ticket
 * 08) drive the rest by hand: `Capture` repeats it, `Show failure
 * alert` presents ticket 18's `StartFailedAlert` exactly as a failed
 * create does.
 *
 * If the auto-capture cannot run (GraphicsLayer bitmaps are unsupported
 * in a host), the results row shows `No capture`.
 */
@Composable
fun HandoffCaptureGallerySection() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val alerts = LocalAlerts.current
    val scope = rememberCoroutineScope()

    val capture = rememberHandoffCaptureState()
    // 31's shape stand-in: the sheet's top corners, square bottom.
    capture.sceneShape = RoundedCornerShape(topStart = 38.dp, topEnd = 38.dp)

    var result by remember { mutableStateOf<CapturedDraftHandoff?>(null) }

    // One automatic capture, so the screenshot test shows the results
    // without a tap; the reset returns the fake page to its live state.
    LaunchedEffect(capture) {
        result = capture.capture(HandoffCaptureGalleryText)
        capture.reset()
    }

    Column {
        BasicText(
            "New session: handoff capture",
            style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
        )
        BasicText(
            "The three bitmaps 95 animates: the page without its composer, " +
                "the composer without its text, and the text rect.",
            style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary),
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HandoffCaptureFakePage(capture)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassTextCapsule(
                    "Capture",
                    onClick = {
                        scope.launch {
                            result = capture.capture(HandoffCaptureGalleryText)
                            capture.reset()
                        }
                    },
                )
                GlassTextCapsule("Show failure alert", onClick = { alerts.show(StartFailedAlert) })
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            val r = result
            if (r == null) {
                BasicText(
                    "No capture",
                    style = uiStyle(Face.Sans, ts.fixed(12f), palette.secondary),
                )
            } else {
                CaptureGalleryTile("scene", r.scene, null)
                CaptureGalleryTile("composer", r.composer, null)
                CaptureGalleryTile(
                    "text rect",
                    r.composer,
                    Rect(
                        left = r.textFrame.left - r.composerFrame.left,
                        top = r.textFrame.top - r.composerFrame.top,
                        right = r.textFrame.right - r.composerFrame.left,
                        bottom = r.textFrame.bottom - r.composerFrame.top,
                    ),
                )
            }
        }
    }
}

/** The sent body the fixture stages (the iOS UI test's first message). */
private const val HandoffCaptureGalleryText = "Add a dark mode toggle to settings"

/**
 * The fake sheet page: 240 × 420 dp, the nav row and the fixture
 * composer, both roots carrying the capture modifiers exactly as 93
 * wires them.
 */
@Composable
private fun HandoffCaptureFakePage(capture: HandoffCaptureState) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Box(
        Modifier
            .size(240.dp, 420.dp)
            .handoffScene(capture)
            .background(palette.background),
    ) {
        // The nav row: 52 dp, centred title (the sheet bar's geometry).
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BasicText(
                "New Session",
                Modifier.align(Alignment.Center),
                style = uiStyle(Face.SansSemibold, ts.fixed(17f), palette.text),
            )
        }
        // The fixture composer: the card's 26 dp radius and size class.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .size(216.dp, 96.dp)
                .handoffComposer(capture)
                .clip(RoundedCornerShape(26.dp))
                .background(palette.elevated),
        ) {
            CaptureGalleryTextSlot(capture)
            // One fixture chip — plain text on the control-fill capsule
            // (no 59 dependency needed for the fixture).
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 8.dp)
                    .clip(CircleShape)
                    .background(palette.controlFill),
            ) {
                BasicText(
                    "roboco",
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = uiStyle(Face.Sans, ts.fixed(13f), palette.text),
                )
            }
        }
    }
}

/**
 * The composer's text slot (top 0, full width, 60 dp tall): the fixture
 * text, the `textHidden` alpha, and the inset rect (`onTextRect`)
 * — 14 dp top, 6 dp bottom, height at least 20 dp, like 57's field.
 */
@Composable
private fun CaptureGalleryTextSlot(capture: HandoffCaptureState) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .graphicsLayer { this.alpha = if (capture.textHidden) 0f else 1f }
            .onGloballyPositioned { coords ->
                val b = coords.boundsInRoot()
                val topPx = with(density) { 14.dp.toPx() }
                val bottomPx = with(density) { 6.dp.toPx() }
                val minPx = with(density) { 20.dp.toPx() }
                capture.onTextRect(
                    Rect(
                        left = b.left,
                        top = b.top + topPx,
                        right = b.right,
                        bottom = b.top + topPx + max(minPx, b.height - topPx - bottomPx),
                    )
                )
            },
    ) {
        BasicText(
            HandoffCaptureGalleryText,
            Modifier.padding(top = 14.dp, bottom = 6.dp),
            style = uiStyle(Face.Sans, ts.fixed(16.5f), palette.text),
        )
    }
}

/**
 * One results tile: the bitmap at 0.5 scale (captions under each), with
 * an optional 1 dp accent [outline] (the text rect, already mapped into
 * the bitmap's coordinates, scaled to match).
 */
@Composable
private fun CaptureGalleryTile(label: String, bitmap: ImageBitmap, outline: Rect?) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val density = LocalDensity.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Image(
                bitmap,
                contentDescription = label,
                modifier = Modifier.size(
                    width = with(density) { (bitmap.width / 2).toDp() },
                    height = with(density) { (bitmap.height / 2).toDp() },
                ),
            )
            if (outline != null) {
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (outline.left / 2).roundToInt(),
                                (outline.top / 2).roundToInt(),
                            )
                        }
                        .size(
                            width = with(density) { (outline.width / 2).toDp() }.coerceAtLeast(1.dp),
                            height = with(density) { (outline.height / 2).toDp() }.coerceAtLeast(1.dp),
                        )
                        .border(1.dp, palette.accent),
                )
            }
        }
        BasicText(
            label,
            Modifier.padding(top = 4.dp),
            style = uiStyle(Face.Sans, ts.fixed(12f), palette.secondary),
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview stage: the section exactly as the gallery shows it. */
@Composable
private fun HandoffCapturePreviewStage(appearance: Appearance) {
    RobocoTheme(appearance) { HandoffCaptureGallerySection() }
}

@Preview(name = "New session handoff capture light", widthDp = 411, heightDp = 891)
@Composable
private fun HandoffCaptureGalleryLightPreview() {
    HandoffCapturePreviewStage(Appearance.Light)
}

@Preview(
    name = "New session handoff capture dark",
    widthDp = 411,
    heightDp = 891,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HandoffCaptureGalleryDarkPreview() {
    HandoffCapturePreviewStage(Appearance.Dark)
}

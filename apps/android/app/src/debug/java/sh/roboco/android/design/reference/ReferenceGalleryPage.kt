package sh.roboco.android.design.reference

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle

/**
 * The "Reference" page of the debug design gallery (android-ui-parity #23),
 * registered in ticket 22's `Gallery.sections` as
 * `GallerySection("Reference", GalleryLayout.Fill, screenshotHeightDp = 1000) { ReferenceGalleryPage() }`.
 *
 * The static [SessionsReferenceMock] on its fixed 402 × 874 dp iOS canvas,
 * scaled to fit the available width (`min(1, (width − 32)/402)` — the page
 * keeps 16 dp padding), with a caption that names the board command. The
 * page owns its own scrolling (`GalleryLayout.Fill` hands it the whole
 * body), follows the gallery's Light/Dark toggle through
 * `GalleryProviders`, and has no interactive behaviour — on a device the
 * glass surfaces here show the real blur and the edge effect fades the two
 * pinned rows, which the Robolectric board cannot render (it runs the
 * gradient-only fallback).
 */
@Composable
fun ReferenceGalleryPage() {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        BasicText(
            "Static mock of iOS 1-off.png. Compare: " +
                "./gradlew :app:testDebugUnitTest --tests '*ReferenceBoardTest' " +
                "→ build/outputs/roborazzi/reference-board.png",
            style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary),
        )
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = minOf(1f, maxWidth / ReferenceGeometry.Width)
            Layout(
                content = {
                    SessionsReferenceMock(
                        Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                measurePolicy = { measurables, _ ->
                    // The child is measured at its fixed 402 × 874 dp frame;
                    // the reported size (and the scroll extent) is the
                    // scaled one, and graphicsLayer draws it scaled about
                    // its top-left.
                    val child = measurables.first().measure(
                        Constraints.fixed(
                            ReferenceGeometry.Width.roundToPx(),
                            ReferenceGeometry.Height.roundToPx(),
                        ),
                    )
                    val w = (ReferenceGeometry.Width * scale).roundToPx()
                    val h = (ReferenceGeometry.Height * scale).roundToPx()
                    layout(w, h) { child.place(0, 0) }
                },
            )
        }
    }
}

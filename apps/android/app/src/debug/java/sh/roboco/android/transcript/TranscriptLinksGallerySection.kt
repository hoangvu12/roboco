package sh.roboco.android.transcript

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalGalleryCapture
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import sh.roboco.android.image.ImageViewerHost
import sh.roboco.android.image.ImageViewerRequest
import sh.roboco.android.image.rememberImageViewerState

/**
 * The "Transcript links" section of the debug design gallery (ticket
 * 126): the real [TranscriptHost] over the real core, fed the
 * [LinkFixture] — a web link, a mailto link, the five no-op links and a
 * wide table with a link inside its scroller — with a real [LinkRouter]
 * behind `openLink`: http(s) opens a Custom Tab (light and dark follow
 * the gallery's appearance toggle), the system gets the other schemes,
 * and everything iOS no-ops (roboco-file:, file:, drive paths,
 * scheme-less paths — decision TRANSCRIPT-D4) does nothing. A one-line
 * readout at the bottom shows the last [LinkRoute], so `Ignore` taps are
 * visible as taps.
 *
 * The router's `loadFull` decodes a bundled PNG (the launcher art) for
 * any reference ending in `.png` and returns `null` otherwise, and
 * `showImage` opens the section's own [ImageViewerHost] the same way the
 * session screen does: the row's bounds as the zoom source, corner
 * radius 0, no second full read.
 *
 * Under capture (ticket 22's `LocalGalleryCapture` rule) the section
 * never feeds the engine, so the golden is the deterministic pre-frame
 * state. Build from foundation only: `BasicText`, no Material.
 *
 * Registered in `debug/…/design/Gallery.kt` as
 * `GallerySection("Transcript links", GalleryLayout.Fill)`.
 */
@Composable
fun TranscriptLinksGallerySection() {
    val capture = LocalGalleryCapture.current
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val context = LocalContext.current
    val state = remember { TranscriptState() }
    DisposableEffect(state) { onDispose { state.close() } }
    var route by remember { mutableStateOf<LinkRoute?>(null) }

    LaunchedEffect(capture) {
        if (!capture) state.engine.setDebugEntries(LinkFixture, working = false)
    }

    val viewer = rememberImageViewerState()
    val router = rememberLinkRouter(
        loadFull = { reference ->
            if (!reference.endsWith(".png")) null
            else withContext(Dispatchers.Default) {
                BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_art)
                    ?.asImageBitmap()
            }
        },
        showImage = { reference, source, bitmap ->
            viewer.show(
                ImageViewerRequest(
                    reference = reference,
                    preview = bitmap,
                    sourceBounds = source,
                    sourceCornerRadius = 0.dp,
                    label = null,
                    loadFull = null,
                ),
            )
        },
    )

    ImageViewerHost(viewer) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            TranscriptHost(
                state,
                TranscriptActions(
                    openLink = { tap ->
                        route = routeLink(tap.url)
                        router.open(tap)
                    },
                ),
                Modifier.fillMaxSize(),
            )
            BasicText(
                route?.toString() ?: "Tap a link",
                style = uiStyle(Face.Mono, typeScale.fixed(11f), palette.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

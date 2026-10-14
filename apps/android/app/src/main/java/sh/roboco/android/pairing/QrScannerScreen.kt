package sh.roboco.android.pairing

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlinx.coroutines.delay
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.ProminentGlassButton
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/**
 * The scanner's phases (android-ui-parity #138). `Requesting` covers "the
 * system dialog is up or the grant is not yet known"; the terminal
 * accepted state is not a phase — only analysis stops, the preview keeps
 * running while the close slide plays.
 */
enum class ScannerPhase { Requesting, Scanning, Denied, Unavailable }

/** What [QrScannerContent] renders: the phase plus the 2 s foreign caption. */
@Immutable
data class QrScannerUiState(val phase: ScannerPhase, val showForeign: Boolean)

internal const val QrScannerTag = "qr-scanner"
internal const val QrPreviewTag = "qr-preview"
internal const val QrCloseTag = "qr-close"
internal const val QrReticleTag = "qr-reticle"
internal const val QrCaptionTag = "qr-caption"
internal const val QrOpenSettingsTag = "qr-open-settings"

/**
 * The scanner's UI (android-ui-parity #138), stateless: a full-window
 * black stage with the aspect-filled [preview] (the activity's
 * `PreviewView`, `FakeCameraPreview` in previews/goldens), a centred 240 dp
 * reticle, the caption 24 dp under it and the 44/15 glass ✕ 16/8 inside
 * the safe area (ImageViewer's `Glass.circleButton` placement). Do **not**
 * wrap this in a theme: the activity, previews and the gallery wrap it in
 * `RobocoTheme(Appearance.Dark)` — the stage is always dark — and 07's
 * `GlassSurface` picks the fallback fill for the ✕ because no
 * `LocalHazeState` is provided (the preview is a `SurfaceView` Haze cannot
 * sample). Caption changes are instant, like 72's footers.
 *
 * [insets] is the safe area the ✕ pads against and the caption's
 * horizontal bounds come from — `WindowInsets.safeDrawing` in production;
 * tests inject a fixed one (24 dp status bar) so the placement is
 * measurable without a real window.
 */
@Composable
fun QrScannerContent(
    state: QrScannerUiState,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.safeDrawing,
    preview: @Composable () -> Unit = {},
) {
    val title = stringResource(R.string.pair_scan)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { paneTitle = title }
            .testTag(QrScannerTag),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics { hideFromAccessibility() }
                .testTag(QrPreviewTag),
        ) {
            preview()
        }
        ReticleAndCaption(state, onOpenSettings, insets)
        // The ✕: 44 dp disc, `xmark` at 15 pt in `Palette.text` — the
        // viewer's Glass.circleButton ink (SESSIONS-D1), not 08's bar
        // default. Left edge 16 dp from the safe-area start, top 8 dp
        // below it (ios:Transcript/ImageViewer.swift:35,41-42).
        GlassCircleButton(
            icon = RobocoIcons.Xmark,
            contentDescription = stringResource(R.string.qr_close),
            onClick = onClose,
            size = 44.dp,
            iconPt = 15f,
            ink = RobocoTheme.palette.text,
            modifier = Modifier
                .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(start = 16.dp, top = 8.dp)
                .testTag(QrCloseTag),
        )
    }
}

/**
 * The reticle-and-caption column, placed by a custom `Layout` so the
 * **reticle's centre is the window centre** (never the centre of the
 * reticle-plus-caption group): the caption's top is `reticle bottom + 24`,
 * the denied capsule's top `caption bottom + 16`, both centred on the
 * window. The caption is at most `window − 2 × 32` wide and stays inside
 * the safe area horizontally; unlimited lines.
 */
@Composable
private fun ReticleAndCaption(state: QrScannerUiState, onOpenSettings: () -> Unit, insets: WindowInsets) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // The safe area's horizontal total, read in composition (a snapshot
    // state read — insets changes recompose us; the measure lambda
    // itself is not composable).
    val safeHorizontal =
        insets.getLeft(density, layoutDirection) + insets.getRight(density, layoutDirection)
    val captionRes = when (state.phase) {
        ScannerPhase.Denied -> R.string.qr_denied
        ScannerPhase.Unavailable -> R.string.qr_unavailable
        ScannerPhase.Requesting, ScannerPhase.Scanning ->
            if (state.showForeign) R.string.qr_foreign else R.string.qr_hint
    }
    val caption = stringResource(captionRes)
    val openSettings = stringResource(R.string.qr_open_settings)
    val typeScale = LocalTypeScale.current
    Layout(
        content = {
            // Child 0: the reticle — its 240 dp slot stays in every state
            // so the caption never jumps; the stroke is hidden when the
            // camera is dead (a scanning frame would suggest it works).
            QrReticle(stroked = state.phase == ScannerPhase.Requesting || state.phase == ScannerPhase.Scanning)
            // Child 1: the caption — polite live region so TalkBack
            // announces the foreign/denied/unavailable change.
            BasicText(
                caption,
                modifier = Modifier
                    .testTag(QrCaptionTag)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = uiStyle(Face.SansMedium, typeScale.fixed(15f), Color.White)
                    .copy(textAlign = TextAlign.Center),
            )
            // Child 2: the capsule (Denied only).
            if (state.phase == ScannerPhase.Denied) {
                ProminentGlassButton(
                    text = openSettings,
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag(QrOpenSettingsTag),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val reticlePx = 240.dp.roundToPx()
        val captionGapPx = 24.dp.roundToPx()
        val buttonGapPx = 16.dp.roundToPx()
        val sideMarginPx = 32.dp.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        val reticle = measurables[0].measure(Constraints.fixed(reticlePx, reticlePx))
        val captionMaxWidth = min(
            constraints.maxWidth - 2 * sideMarginPx,
            constraints.maxWidth - safeHorizontal - 2 * sideMarginPx,
        ).coerceAtLeast(0)
        val captionPlaceable = measurables[1].measure(Constraints(maxWidth = captionMaxWidth))
        val button = measurables.getOrNull(2)?.measure(Constraints(maxWidth = width.coerceAtLeast(0)))

        val reticleX = (width - reticlePx) / 2
        val reticleY = (height - reticlePx) / 2
        val captionX = (width - captionPlaceable.width) / 2
        val captionY = reticleY + reticlePx + captionGapPx

        layout(width, height) {
            reticle.placeRelative(reticleX, reticleY)
            captionPlaceable.placeRelative(captionX, captionY)
            button?.placeRelative(
                (width - button.width) / 2,
                captionY + captionPlaceable.height + buttonGapPx,
            )
        }
    }
}

/**
 * The 240 × 240 reticle: a 2 dp `#E6FFFFFF` stroke of radius 26, drawn
 * inside the box like UIKit's `layer.borderWidth`, continuous corners
 * ([SmoothRoundedShape]). Hidden from accessibility — it is chrome.
 */
@Composable
internal fun QrReticle(stroked: Boolean, modifier: Modifier = Modifier) {
    val shape = rememberSmoothRoundedShape(26.dp)
    Box(
        modifier
            .size(240.dp)
            .then(
                if (stroked) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), shape)
                } else {
                    Modifier
                },
            )
            .semantics { hideFromAccessibility() }
            .testTag(QrReticleTag),
    )
}


/**
 * The fake camera for previews, goldens and the gallery: a
 * `#2A2A30 → #0E0E10` gradient with a white 160 dp square holding a black
 * 21 × 21 checker. It only suggests a code; it is not a real QR.
 */
@Composable
internal fun FakeCameraPreview(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2A30), Color(0xFF0E0E10)))),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(160.dp)
                .background(Color.White),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.width / 21f
                for (row in 0 until 21) {
                    for (col in 0 until 21) {
                        if ((row + col) % 2 == 0) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(col * cell, row * cell),
                                size = Size(cell, cell),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Composable
private fun QrScannerPreviewBody(state: QrScannerUiState) {
    // The forced-dark wrap is part of the screen (SHELL-D2): light and
    // dark previews render identically.
    RobocoTheme(Appearance.Dark) {
        QrScannerContent(
            state = state,
            onClose = {},
            onOpenSettings = {},
            preview = { FakeCameraPreview() },
        )
    }
}

@Preview(name = "QR scanner hint light", widthDp = 393, heightDp = 852, showSystemUi = false)
@Composable
private fun QrScannerHintLightPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
}

@Preview(name = "QR scanner hint dark", widthDp = 393, heightDp = 852, showSystemUi = false, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrScannerHintDarkPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
}

@Preview(name = "QR scanner foreign light", widthDp = 393, heightDp = 852, showSystemUi = false)
@Composable
private fun QrScannerForeignLightPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Scanning, showForeign = true))
}

@Preview(name = "QR scanner foreign dark", widthDp = 393, heightDp = 852, showSystemUi = false, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrScannerForeignDarkPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Scanning, showForeign = true))
}

@Preview(name = "QR scanner denied light", widthDp = 393, heightDp = 852, showSystemUi = false)
@Composable
private fun QrScannerDeniedLightPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Denied, showForeign = false))
}

@Preview(name = "QR scanner denied dark", widthDp = 393, heightDp = 852, showSystemUi = false, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrScannerDeniedDarkPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Denied, showForeign = false))
}

@Preview(name = "QR scanner unavailable light", widthDp = 393, heightDp = 852, showSystemUi = false)
@Composable
private fun QrScannerUnavailableLightPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Unavailable, showForeign = false))
}

@Preview(name = "QR scanner unavailable dark", widthDp = 393, heightDp = 852, showSystemUi = false, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QrScannerUnavailableDarkPreview() {
    QrScannerPreviewBody(QrScannerUiState(ScannerPhase.Unavailable, showForeign = false))
}

// ---------------------------------------------------------------------------
// The design gallery section (ticket 22; registered in debug Gallery.kt)
// ---------------------------------------------------------------------------

/**
 * The "QR scanner" page of the debug design gallery: [QrScannerContent]
 * over [FakeCameraPreview] in the forced-dark wrap, with plain `BasicText`
 * toggles for the four phases plus "Foreign" — which runs the real 2 s
 * caption timer (`LaunchedEffect` + `delay`, the same logic the activity
 * runs in `lifecycleScope`). "Launch real scanner" opens the real
 * [QrScanActivity] through [rememberQrScanRequest] and reports only the
 * scan's length and whether it parses as a pairing link — never the URL.
 */
@Composable
fun QrScannerGallerySection() {
    var phase by remember { mutableStateOf(ScannerPhase.Scanning) }
    var foreign by remember { mutableStateOf(false) }
    var lastScan by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val qrScan = rememberQrScanRequest()

    // The real 2 s reset: one flash per sighting of a foreign payload.
    LaunchedEffect(foreign) {
        if (foreign) {
            delay(FOREIGN_CAPTION_MS)
            foreign = false
        }
    }

    RobocoTheme(Appearance.Dark) {
        // Read inside the forced-dark wrap: the readout's ink follows the
        // stage, not the gallery's current appearance.
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QrGalleryToggle("Requesting", phase == ScannerPhase.Requesting) {
                    phase = ScannerPhase.Requesting
                    foreign = false
                }
                QrGalleryToggle("Scanning", phase == ScannerPhase.Scanning && !foreign) {
                    phase = ScannerPhase.Scanning
                    foreign = false
                }
                QrGalleryToggle("Foreign", foreign) {
                    phase = ScannerPhase.Scanning
                    foreign = true
                }
                QrGalleryToggle("Denied", phase == ScannerPhase.Denied) {
                    phase = ScannerPhase.Denied
                    foreign = false
                }
                QrGalleryToggle("Unavailable", phase == ScannerPhase.Unavailable) {
                    phase = ScannerPhase.Unavailable
                    foreign = false
                }
            }
            QrScannerContent(
                state = QrScannerUiState(phase, foreign),
                onClose = {},
                onOpenSettings = {},
                modifier = Modifier.weight(1f).fillMaxWidth(),
                preview = { FakeCameraPreview() },
            )
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QrGalleryToggle("Launch real scanner", lastScan != null) {
                    qrScan.scan { url ->
                        // Length and parse verdict only — the URL carries
                        // the token and must never reach this screen.
                        lastScan = url.length to GalleryPairingPattern.matches(url.trim())
                    }
                }
                lastScan?.let { (length, pairing) ->
                    BasicText(
                        "Last scan: $length chars, pairing=$pairing",
                        style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                    )
                }
            }
        }
    }
}

/** One toggle: SemiBold label when selected, Regular `secondary` when not. */
@Composable
private fun QrGalleryToggle(label: String, selected: Boolean, onSelect: () -> Unit) {
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

/** The gallery's pairing check: the regex stand-in, never the native library. */
private val GalleryPairingPattern =
    Regex("^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$")

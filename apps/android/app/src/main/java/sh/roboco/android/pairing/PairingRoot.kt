package sh.roboco.android.pairing

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.R
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.glass.ProminentGlassButton
import sh.roboco.android.design.glass.ProminentGlassButtonDefaults
import sh.roboco.android.design.uiStyle

/**
 * What the pairing root's status line shows. Hoisted: the caller (ticket 35,
 * fed by 72/139) owns it; this screen only renders the value and never
 * clears it, exactly like `SignInViewController`'s `status.text`, which is
 * assigned directly and replaced only by the next status
 * (`ios:Shell/SignInViewController.swift:66-110`).
 */
@Immutable
sealed interface PairingRootStatus {
    /**
     * No line. Nothing is composed — no node, no 12 dp gap — because an
     * empty `UILabel` has zero intrinsic height and the bottom stack's
     * button stays pinned to the safe area whatever the status is.
     */
    data object None : PairingRootStatus

    /** "Redeeming the pairing link…" in `secondary`, capsule busy. */
    data object Redeeming : PairingRootStatus

    /**
     * [message] is already user-facing copy (ticket 72's error mapping),
     * shown verbatim in `danger` — the port of
     * `error.localizedDescription` (`SignInViewController.swift:108`).
     */
    data class Error(val message: String) : PairingRootStatus
}

/**
 * The status line's copy and colour, or `null` when the line is absent
 * ([PairingRootStatus.None]). [PairingRootStatus.Redeeming] renders the
 * redeeming copy in `secondary` (web `pair-page.tsx:81`; iOS only ever
 * shows errors on this screen); [PairingRootStatus.Error] renders its
 * message verbatim in `danger` (`ios:Shell/SignInViewController.swift:66-67`,
 * `:108`).
 *
 * Pure over (status, copy, palette) so the colour mapping is testable
 * without pixel sampling — the goldens cover the drawn pixels.
 */
internal fun pairingRootStatusLine(
    status: PairingRootStatus,
    redeeming: String,
    palette: Palette,
): Pair<String, Color>? = when (status) {
    PairingRootStatus.None -> null
    PairingRootStatus.Redeeming -> redeeming to palette.secondary
    is PairingRootStatus.Error -> status.message to palette.danger
}

/**
 * The pairing root (android-ui-parity #34): the screen the app shows
 * whenever no engine is paired (`engines().isEmpty()` — fresh install, and
 * again after the last engine is forgotten). It keeps the iOS
 * `SignInViewController` layout exactly
 * (`ios:Shell/SignInViewController.swift:36-89`) and swaps the content:
 *
 * - a top stack centred on the **whole window** (not the safe area) and
 *   lifted 80 dp above the window centre (`:83-84`): the launcher
 *   artwork in a 64 dp rounded tile (android-next #03), the title
 *   "Roboco" and the tagline, 12 dp apart (`:71-74`);
 * - a bottom stack pinned 24 dp above the safe area's bottom edge and 24 dp
 *   inside its side edges (`:85-87`), 12 dp between the status line and the
 *   full-width "Pair an engine" capsule (ticket 08's
 *   [ProminentGlassButton] with the pairing padding, `:50-55`).
 *
 * The page fills the window edge-to-edge with `background` — **no inset
 * padding on the root**, only the bottom stack consumes [safeInsets] (the
 * top stack must not shift by half the inset difference). `safeInsets` is
 * `systemBars ∪ displayCutout` on purpose, never `safeDrawing`: the Pair
 * page (ticket 72) presents over this root, and the keyboard must not push
 * the capsule up behind the sheet.
 *
 * Stateless: [status] comes from the caller (ticket 35, derived by 139) and
 * [onPair] opens ticket 72's Pair page. Everything is fixed type (`Fonts.ui`
 * is fixed on iOS, so a font-scale-2.0 device renders the same sizes). No
 * animation — status changes are instant, like `status.text` assignments;
 * the only motion is ticket 08's busy spinner while [PairingRootStatus.Redeeming].
 *
 * @param safeInsets injects the iOS `safeAreaLayoutGuide` for previews and
 *   tests (Robolectric reports zero system-bar insets).
 */
@Composable
fun PairingRoot(
    status: PairingRootStatus,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
    safeInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val redeeming = stringResource(R.string.pairing_root_redeeming)
    Box(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("pairing-root"),
    ) {
        // The top stack: centred on the FULL window (view.centerYAnchor,
        // :83-84), then offset 80 dp up — offset moves the drawn position
        // without touching the centring maths, so the stack's centre sits
        // at exactly "window centre − 80". The horizontal padding caps the
        // stack at window width − 48; the tagline wraps to two centred
        // lines at most and is never clipped in split-screen (iOS draws
        // one unconstrained line; ≥ 334 dp wide it still is one line).
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-80).dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // android-next #03 (user decision 2026-10-15, reversing ticket
            // 34's tinted glyph): the launcher artwork itself, untinted,
            // in a 64 dp app-icon tile with continuous corners (the iOS
            // icon ratio, 0.2237 × 64 ≈ 14.3 dp), like desktop and web.
            Image(
                painter = painterResource(R.mipmap.ic_launcher_art),
                contentDescription = stringResource(R.string.pairing_root_title),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(PairingRootLogoSize)
                    .clip(rememberSmoothRoundedShape(PairingRootLogoCorner))
                    .testTag("pairing-root-logo"),
            )
            BasicText(
                stringResource(R.string.pairing_root_title),
                style = uiStyle(Face.SansSemibold, typeScale.fixed(34f), palette.text),
                maxLines = 1,
            )
            BasicText(
                stringResource(R.string.pairing_root_tagline),
                style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The bottom stack: 24 dp from the safe area's start, end and
        // bottom edges (:85-87), `.fill` alignment — status and capsule
        // are both full width, and the stack grows upward as the status
        // wraps (an empty label has zero height on iOS, so `None`
        // composes nothing, not an empty line box).
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(
                    safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pairingRootStatusLine(status, redeeming, palette)?.let { (text, color) ->
                BasicText(
                    text,
                    style = uiStyle(Face.Sans, typeScale.fixed(14f), color)
                        .copy(textAlign = TextAlign.Center),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pairing-root-status")
                        // Android-only: TalkBack announces a redeem error
                        // politely; iOS posts no announcement.
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ProminentGlassButton(
                text = stringResource(R.string.pairing_root_pair),
                onClick = onPair,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pair-engine"),
                busy = status is PairingRootStatus.Redeeming,
                contentPadding = ProminentGlassButtonDefaults.PairingPadding,
            )
        }
    }
}

/** The artwork tile's side (android-next #03: 64, within the ticket's 64–72). */
internal val PairingRootLogoSize = 64.dp

/** The tile's continuous corner: the iOS app-icon ratio, 0.2237 × 64. */
internal val PairingRootLogoCorner = 14.3.dp

// ---------------------------------------------------------------------------
// Previews — the ticket's iPhone frame: 393 × 852, safe insets 59/34
// ---------------------------------------------------------------------------

/** An iPhone-ish safe area, so the previews match the goldens' geometry. */
private val PreviewSafeInsets = WindowInsets(top = 59.dp, bottom = 34.dp)

/**
 * The ticket's example error message (ticket 72's error mapping output),
 * shared by the previews, the gallery's Error toggle and the screenshot
 * goldens so the three never drift.
 */
internal const val PairingRootExampleError =
    "That pairing link did not work — it may have expired or already have been used."

@Preview(name = "Pairing root light", widthDp = 393, heightDp = 852)
@Composable
private fun PairingRootLightPreview() {
    PairingRootPreviewBody(Appearance.Light, PairingRootStatus.None)
}

@Preview(name = "Pairing root dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PairingRootDarkPreview() {
    PairingRootPreviewBody(Appearance.Dark, PairingRootStatus.None)
}

@Preview(name = "Pairing root redeeming light", widthDp = 393, heightDp = 852)
@Composable
private fun PairingRootRedeemingLightPreview() {
    PairingRootPreviewBody(Appearance.Light, PairingRootStatus.Redeeming)
}

@Preview(
    name = "Pairing root redeeming dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PairingRootRedeemingDarkPreview() {
    PairingRootPreviewBody(Appearance.Dark, PairingRootStatus.Redeeming)
}

@Preview(name = "Pairing root error light", widthDp = 393, heightDp = 852)
@Composable
private fun PairingRootErrorLightPreview() {
    PairingRootPreviewBody(Appearance.Light, PairingRootStatus.Error(PairingRootExampleError))
}

@Preview(
    name = "Pairing root error dark",
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PairingRootErrorDarkPreview() {
    PairingRootPreviewBody(Appearance.Dark, PairingRootStatus.Error(PairingRootExampleError))
}

@Composable
private fun PairingRootPreviewBody(appearance: Appearance, status: PairingRootStatus) {
    RobocoTheme(appearance) {
        PairingRoot(status = status, onPair = {}, safeInsets = PreviewSafeInsets)
    }
}

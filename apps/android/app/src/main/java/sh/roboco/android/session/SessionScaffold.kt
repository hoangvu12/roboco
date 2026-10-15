package sh.roboco.android.session

import android.content.res.Configuration
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import java.util.Locale
import kotlinx.coroutines.delay
import sh.roboco.android.composer.MentionSuggestionsHost
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.uiStyle

/**
 * The Session screen's root scaffold (android-ui-parity #75) — the port of
 * the screen's view hierarchy (`ios:Session/SessionViewController.swift:36, 60-79, 119-150`):
 * the page background, the screen's single haze state, [body] (ticket 74's
 * `SessionBottomStack`, whose body box is the only `hazeSource`), then
 * [header] (52) over everything, as the iOS nav bar sits above the whole
 * view. It also runs the open choreography's two timers:
 *
 * - **the reveal tween**: on `Revealing`, `progress` 0 → 1 over
 *   `Motion.sessionReveal()` (180 ms ease-out; iOS
 *   `UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut,
 *   .allowUserInteraction])`, `SessionViewController.swift:273`). Both
 *   alphas derive from `progress` and are read only in `graphicsLayer`
 *   blocks, so the fade never recomposes a layer. Interrupted (the host
 *   left composition), it restarts from the current `progress` with the
 *   full spec. Under reduced motion it snaps at once
 *   (iOS's no-animation branch).
 * - **the give-up timer**: 8 s after landing while still holding
 *   (`DispatchQueue.main.asyncAfter(deadline: .now() + 8)`, `:286`). It
 *   lives here, not in `SessionScreen`, so the gallery's "Never" mode and
 *   the tests get it for free. A composition-scoped timer: a tab switch
 *   during a hold restarts it (acceptable, see the ticket's Comments).
 *
 * **The header edge settle** (the port of `settleEdgeEffect()`,
 * `SessionViewController.swift:267` / `TranscriptListView.swift:281-288`):
 * while the reveal holds or runs, the header is composed under
 * `LocalReduceMotion provides true`, so ticket 09's 250 ms engage fade
 * **snaps** and the soft edge under the title is already at full strength
 * on the reveal's first frame — the crisp-text artefact upstream #584
 * fixed. Only the header is wrapped: the transcript's new-row fade-in
 * (65) and the composer keep their normal motion. The real value is read
 * **outside** the provider (the reveal's own reduce branch must see it).
 *
 * [body] and [header] are plain slots; the screen (75's `SessionScreen`)
 * passes 74's container and 52's header. No Material anywhere — the name
 * is ours, not `androidx.compose.material3.Scaffold`.
 *
 * @param reveal the screen's [OpenReveal] (from its `SessionScreenState`)
 */
@Composable
fun SessionScaffold(
    reveal: OpenReveal,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    // Read outside the provider: the reveal's reduce-motion branch must
    // see the real value, not the settle override.
    val reduce = LocalReduceMotion.current
    Box(modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
        ProvideHazeState {
            // 86's integration contract: the mention card's host sits
            // inside ProvideHazeState and outside 74's body box (the card
            // blurs the transcript and must never be part of its source).
            MentionSuggestionsHost(Modifier.fillMaxSize()) {
                body()
                CompositionLocalProvider(
                    LocalReduceMotion provides (reduce || reveal.settlingEdge),
                ) {
                    header()
                }
            }
        }
    }

    // The reveal tween. Keyed on the phase: Holding → Revealing launches
    // it; every other transition is a no-op (an interrupted run restarts
    // from the current progress because initialValue is read at launch).
    val reduceNow by rememberUpdatedState(reduce)
    LaunchedEffect(reveal, reveal.phase) {
        if (reveal.phase != OpenReveal.Phase.Revealing) return@LaunchedEffect
        if (reduceNow) {
            reveal.finishReveal()
            return@LaunchedEffect
        }
        animate(reveal.progress, 1f, animationSpec = Motion.sessionReveal()) { v, _ ->
            reveal.progress = v
        }
        reveal.finishReveal()
    }

    // The give-up: 8 s after landing while the hold is still on. The phase
    // change to Revealing cancels the delay (awaiting flips to false).
    val awaiting = reveal.phase == OpenReveal.Phase.Holding && reveal.landed
    LaunchedEffect(reveal, awaiting) {
        if (awaiting) {
            delay(Motion.SessionGiveUpMs)
            reveal.giveUp()
        }
    }
}

/**
 * What goes inside 74's `transcript` slot (android-ui-parity #75) — the
 * port of the loader and the hold blocker of
 * `ios:Session/SessionViewController.swift:229-261`: the transcript
 * (always composed and laid out, **even while hidden** — rows must lay
 * out and 68 must place the tail during the hold), the hold blocker above
 * it, and the spinner loader above both, all inside the haze source so
 * the blurred copy under the header fades in with the content.
 *
 * - **Layer 0 — the transcript**: [transcriptAlpha] (the reveal progress ×
 *   77's arrival multiplier) applies **inside** the haze source via
 *   `graphicsLayer`, never `Modifier.alpha` on the source box. While
 *   holding, the subtree is `clearAndSetSemantics {}` (UIKit hides
 *   alpha-0 views from accessibility, and 65's `transcript` test tag is
 *   found only once the hold ends).
 * - **Layer 1 — the blocker**: a transparent full-size box consuming
 *   every pointer change at the `Initial` pass while holding (UIKit
 *   alpha-0 views get no touches). It covers only the transcript slot:
 *   74 places the stack slots above the body box, so they stay
 *   interactive.
 * - **Layer 2 — the loader**: ticket 11's spinner at `scale = 1.75`
 *   (a 21 dp box; iOS's `CGAffineTransform(scaleX: 1.75, y: 1.75)` over a
 *   12 × 12 view), at [loaderTopLeft], fading out with the reveal. It
 *   leaves composition at `Shown`, stopping 11's shared spinner clock.
 *   Unclipped, decorative: a test tag, no `contentDescription`, not
 *   focusable.
 *
 * Composed inside 74's body box, which is the only `hazeSource`; this
 * composable never adds a second one.
 *
 * @param transcriptAlpha 77's arrival multiplier; read in the draw phase
 *   only (`OpenRevealLayers` reads it inside the `graphicsLayer` lambda)
 * @param statusBarTop / [navBarBottom] the window insets in dp; parameters
 *   so tests and the fixture can pin them (Robolectric reports none)
 */
@Composable
fun OpenRevealLayers(
    reveal: OpenReveal,
    modifier: Modifier = Modifier,
    transcriptAlpha: () -> Float = { 1f },
    statusBarTop: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    navBarBottom: Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    transcript: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Layer 0: the transcript — always composed, always laid out.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = reveal.transcriptAlpha * transcriptAlpha() }
                .then(
                    if (reveal.phase == OpenReveal.Phase.Holding) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            transcript()
        }
        // Layer 1: the hold blocker. A sibling above the transcript, so it
        // is hit-tested first; consuming at the Initial pass starves every
        // consumer below (the transcript's taps and scroll).
        if (reveal.phase == OpenReveal.Phase.Holding) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
        // Layer 2: the loader, centred on the window x and the safe-area
        // centre minus 40 (see [loaderTopLeft]).
        if (reveal.loaderComposed) {
            val topLeft = loaderTopLeft(
                maxWidth.value,
                maxHeight.value,
                statusBarTop.value,
                navBarBottom.value,
            )
            StatusGlyph(
                StatusGlyphKind.Spinner,
                Modifier
                    .offset(topLeft.x.dp, topLeft.y.dp)
                    .graphicsLayer { alpha = reveal.loaderAlpha }
                    .testTag("session-loading"),
                scale = 1.75f,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The fixture (previews, the gallery and the goldens): no Rust core, no
// 74 container — a stand-in body (the haze source box with
// [OpenRevealLayers] and a text transcript) plus a glass capsule where the
// composer will sit, under ticket 52's fixture header.
// ---------------------------------------------------------------------------

/** The fixture's transcript paragraph, repeated 12 times (the ticket's text). */
private const val SessionOpenFixtureParagraph =
    "Port the streaming fade-in veil from transcript.rs into Transcript."

/** The fixture's subtitle (the reference capture's project @ engine line). */
private const val SessionOpenFixtureSubtitle = "roboco @ MacBook Pro"

/**
 * The fixture header (52's, with the ticket's values): the reference title,
 * engaged edge, both bar circles — `showBack`/`onBack` explicit, as 24's
 * locals have no default outside a `NavStackHost`.
 */
@Composable
internal fun SessionOpenFixtureHeader(statusBarTop: Dp = 24.dp) {
    SessionHeader(
        title = SessionHeaderReferenceTitle,
        subtitle = SessionOpenFixtureSubtitle,
        edgeEngaged = true,
        onMore = {},
        showBack = true,
        onBack = {},
        statusBarTop = statusBarTop,
    )
}

/**
 * The fixture transcript: 12 paragraphs of Geist Regular `fixed(17)` in
 * `Palette.text`, 20 dp side margins, 68 dp top (the fixture insets' bar
 * bottom). The column is clickable with no ripple, so the hold-blocker
 * test can land a click at (206, 300).
 */
@Composable
private fun SessionOpenTranscriptStandIn(onTap: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 68.dp, start = 20.dp, end = 20.dp)
            .testTag("transcript")
            .clickable(interactionSource = null, indication = null) { onTap() },
    ) {
        repeat(12) {
            BasicText(
                SessionOpenFixtureParagraph,
                Modifier.fillMaxWidth(),
                style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
            )
        }
    }
}

/**
 * The fixture body: the haze-source box with [OpenRevealLayers] over the
 * transcript stand-in, then (outside the source box, as 74 places it) the
 * stack stand-in — ticket 07's glass capsule, 48 dp high, 12/8 from the
 * bottom corners.
 */
@Composable
internal fun SessionOpenFixtureBody(
    reveal: OpenReveal,
    statusBarTop: Dp = 24.dp,
    navBarBottom: Dp = 0.dp,
    onTranscriptTap: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(LocalHazeState.current!!)) {
            OpenRevealLayers(
                reveal,
                statusBarTop = statusBarTop,
                navBarBottom = navBarBottom,
            ) {
                SessionOpenTranscriptStandIn(onTranscriptTap)
            }
        }
        GlassSurface(
            // fillMaxWidth: GlassSurface wraps its (empty) content, so
            // without it the capsule measures 0 dp wide and never draws.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .height(48.dp),
        )
    }
}

/**
 * The whole fixture scaffold (previews, the gallery, the goldens, the
 * scaffold tests): [SessionScaffold] over the fixture body and header, at
 * the ticket's fixture insets (24 dp status bar, no nav bar — pin them in
 * tests; Robolectric reports none).
 */
@Composable
internal fun SessionOpenFixture(
    reveal: OpenReveal,
    modifier: Modifier = Modifier,
    statusBarTop: Dp = 24.dp,
    navBarBottom: Dp = 0.dp,
    onTranscriptTap: () -> Unit = {},
) {
    SessionScaffold(
        reveal = reveal,
        header = { SessionOpenFixtureHeader(statusBarTop) },
        modifier = modifier,
    ) {
        SessionOpenFixtureBody(
            reveal,
            statusBarTop = statusBarTop,
            navBarBottom = navBarBottom,
            onTranscriptTap = onTranscriptTap,
        )
    }
}

// ---------------------------------------------------------------------------
// Previews (step 6): the fixture with a pre-`begin(true)` reveal, light
// and dark — the holding state (loader up, transcript hidden).
// ---------------------------------------------------------------------------

@Preview(name = "holding")
@Composable
private fun SessionScaffoldHoldingPreview() {
    RobocoTheme(Appearance.Light) {
        SessionOpenFixture(remember { OpenReveal().apply { begin(true) } })
    }
}

@Preview(name = "holding dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionScaffoldHoldingDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        SessionOpenFixture(remember { OpenReveal().apply { begin(true) } })
    }
}

// ---------------------------------------------------------------------------
// The gallery section (ticket 22's registry, registered in Gallery.kt):
// the fixture plus the open-choreography controls.
// ---------------------------------------------------------------------------

/** The Rows-arrive modes, in cycle order. */
private val SessionOpenRowsModes = arrayOf(
    "before landing (120 ms)",
    "after landing (1.2 s)",
    "never (8 s give-up)",
)

/**
 * The "Session open" gallery page (android-ui-parity #75): the fixture
 * scaffold with live open-choreography controls —
 *
 * - **Push**: a fresh [OpenReveal] held at once, landing at 500 ms
 *   (ticket 24's `NavMotion.Push` 0.50 s period);
 * - **Rows arrive**: before landing (120 ms) / after landing (1.2 s) /
 *   never — the last shows the 8 s give-up, driven by
 *   [SessionScaffold]'s own timer;
 * - **Replay**: a fresh [OpenReveal] (back to `Idle`);
 * - a status line with the phase, the landing, the probe flag and the
 *   progress to 2 decimals.
 *
 * It follows the gallery's Light/Dark and Force-reduce-motion toggles
 * (under reduce motion the reveal snaps and the spinner holds its static
 * pattern). Foundation `BasicText` and plain clickable rows only.
 */
@Composable
fun SessionOpenGallerySection() {
    var reveal by remember { mutableStateOf(OpenReveal()) }
    var push by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(0) }
    val currentReveal by rememberUpdatedState(reveal)

    // The push script: hold at once, land at 500 ms, rows per the mode.
    // "Never" stops after the landing — the scaffold's own give-up timer
    // (8 s) reveals, exactly as the production screen would.
    LaunchedEffect(push) {
        if (push == 0) return@LaunchedEffect
        val r = currentReveal
        r.begin(true)
        when (mode) {
            0 -> {
                delay(120)
                r.onFirstRows()
                delay(380)
                r.onLanded()
            }
            1 -> {
                delay(500)
                r.onLanded()
                delay(700)
                r.onFirstRows()
            }
            else -> {
                delay(500)
                r.onLanded()
            }
        }
    }

    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(560.dp)) {
            SessionOpenFixture(currentReveal)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SessionOpenToggleRow("Push", if (push == 0) "idle" else "run $push") {
                reveal = OpenReveal()
                push++
            }
            SessionOpenToggleRow("Rows arrive", SessionOpenRowsModes[mode]) {
                mode = (mode + 1) % SessionOpenRowsModes.size
            }
            SessionOpenToggleRow("Replay", "fresh OpenReveal") { reveal = OpenReveal() }
            BasicText(
                String.format(
                    Locale.ROOT,
                    "phase=%s landed=%b reportedOpen=%b progress=%.2f",
                    reveal.phase,
                    reveal.landed,
                    reveal.reportedOpen,
                    reveal.progress,
                ),
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
            )
        }
    }
}

/** One gallery toggle row: label left, current value in accent right. */
@Composable
private fun SessionOpenToggleRow(label: String, value: String, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text))
        Spacer(Modifier.weight(1f))
        BasicText(value, style = uiStyle(Face.SansMedium, typeScale.fixed(15f), palette.accent))
    }
}

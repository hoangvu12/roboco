package sh.roboco.android.session

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.StatusGlyph
import sh.roboco.android.design.StatusGlyphKind
import sh.roboco.android.design.formatElapsed
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.uiStyle

/**
 * The pill's tunables (android-ui-parity #73) — the UIKit constraints of
 * `ios:Session/SessionViewController.swift:604-616`: height 30, a 12×12
 * glyph 11 from the leading edge, label 8 after the glyph and 12 before
 * the trailing edge, so the pill hugs `43 + labelWidth` and caps at the
 * row width (the label ellipsizes).
 */
object StatusPillDefaults {
    /** `heightAnchor.constraint(equalToConstant: 30)` (`:609`). */
    val Height = 30.dp

    /** `grid.widthAnchor/heightAnchor` 12 (`:610-613`). */
    val GlyphSize = 12.dp

    /** `grid.leadingAnchor … constant: 11` (`:610`). */
    val StartInset = 11.dp

    /** `label.leadingAnchor = grid.trailingAnchor + 8` (`:614`). */
    val GlyphGap = 8.dp

    /** `label.trailingAnchor … constant: -12` (`:615`). */
    val EndInset = 12.dp

    /** `Fonts.ui(.sansMedium, 13)` — a fixed size (ticket 03 call-site table: "Medium 13 | F"). */
    const val LabelPt = 13f
}

/** The glyph the pill draws for a banner (mapped to a [StatusGlyphKind] inside the composable). */
enum class PillGlyph {
    /** The 3×3 animated trailer (Sending, Working). */
    Trailer,

    /** One 7 dp `Palette.danger` dot (NotDelivered, Failed, EngineParked). */
    DotDanger,

    /** One 7 dp `StatusTone.input` dot (Editing). */
    DotInput,

    /** One 7 dp `StatusTone.idle` dot (EngineOff, Reconnecting). */
    DotIdle,
}

/** One banner's pill content: the glyph and the verbatim label text. */
data class PillContent(val glyph: PillGlyph, val text: String)

/**
 * The label + glyph for a non-`None` banner (pure; the composable never
 * calls it with `None` — that case keeps the last shown content instead,
 * iOS `update()`'s `case .none: break`). Copy is verbatim, `·` = U+00B7,
 * `…` = U+2026.
 *
 * `Working` truncates toward zero, like `Int(Date().timeIntervalSince($0))`:
 * `secs = ((nowMs − sinceMs) / 1000).toInt()`, `secs > 0` →
 * `"<word> · <elapsed>"`, else `"<word>…"`.
 */
fun pillContent(banner: SessionChrome.Banner, nowMs: Long): PillContent = when (banner) {
    is SessionChrome.Banner.Working -> {
        val secs = banner.sinceMs?.let { ((nowMs - it) / 1000L).toInt() } ?: 0
        PillContent(
            glyph = PillGlyph.Trailer,
            text = if (secs > 0) "${banner.word} · ${formatElapsed(secs)}" else "${banner.word}…",
        )
    }
    SessionChrome.Banner.Sending -> PillContent(PillGlyph.Trailer, "Sending to host…")
    SessionChrome.Banner.NotDelivered -> PillContent(PillGlyph.DotDanger, "Not delivered · Tap to retry")
    is SessionChrome.Banner.Failed -> PillContent(PillGlyph.DotDanger, banner.message)
    SessionChrome.Banner.Editing -> PillContent(PillGlyph.DotInput, "Editing queued message · Tap to cancel")
    is SessionChrome.Banner.EngineParked -> when (banner.reason) {
        SessionChrome.ParkedReason.Revoked ->
            PillContent(PillGlyph.DotDanger, "Session revoked · Tap to pair again")
        SessionChrome.ParkedReason.IdentityChanged ->
            PillContent(PillGlyph.DotDanger, "Engine changed · Tap to pair again")
        // Never derived (Incompatible maps to EngineOff); defensive only.
        SessionChrome.ParkedReason.Incompatible ->
            PillContent(PillGlyph.DotIdle, "Engine off. Cached history is read-only.")
    }
    SessionChrome.Banner.EngineOff -> PillContent(PillGlyph.DotIdle, "Engine off. Cached history is read-only.")
    SessionChrome.Banner.Reconnecting -> PillContent(PillGlyph.DotIdle, "Reconnecting… Cached history is read-only.")
    SessionChrome.Banner.None -> error("None has no pill content")
}

/**
 * The Session screen's status pill (android-ui-parity #73) — the port of
 * `ios:Session/SessionViewController.swift:584-666`: a 30 dp glass
 * capsule (`GlassSurface`, capsule shape, not interactive, no press
 * feedback) with a 12×12 [StatusGlyph] and one fixed-13 Geist Medium
 * line in `Palette.secondary`, at the leading edge of its row.
 *
 * - **`banner` is the *shown* banner** — `shownBanner(chrome.banner, editing)`
 *   (ticket 83's flag). `None` means "no chrome banner": the pill keeps
 *   drawing the last non-`None` content (iOS `update()`'s `.none` case)
 *   while ticket 74 collapses the row, and stops being hit-testable.
 * - **Taps:** a live pill consumes every tap (`detectTapGestures`, like the
 *   `UIControl`) and dispatches [onTap]; it exposes a semantics click
 *   action only while [tapEnabled] is true for the banner (the screen
 *   passes `{ b -> bannerTapAction(b, true) != BannerTap.None }`). No
 *   ripple, no highlight, no scale — ever.
 * - **The 1 s elapsed ticker** runs only for a live `Working` banner
 *   ([Motion.StatusTickMs]); it stops the moment the live banner is no
 *   longer `Working` or the pill leaves composition (iOS stops the timer
 *   when the view leaves the window).
 * - **Content swaps are instant** — glyph and label re-render in the same
 *   frame, no crossfade; the row's show/hide spring belongs to ticket 74.
 */
@Composable
fun StatusPill(
    banner: SessionChrome.Banner,
    onTap: (SessionChrome.Banner) -> Unit,
    modifier: Modifier = Modifier,
    tapEnabled: (SessionChrome.Banner) -> Boolean = { true },
    nowMs: () -> Long = System::currentTimeMillis,
) {
    // `.none` keeps the old content (iOS update()): remember the last
    // non-None banner and keep rendering it while the row collapses.
    val lastShown = remember { mutableStateOf<SessionChrome.Banner?>(null) }
    SideEffect { if (banner != SessionChrome.Banner.None) lastShown.value = banner }
    val display = if (banner != SessionChrome.Banner.None) banner else lastShown.value ?: return
    val live = banner != SessionChrome.Banner.None

    // The elapsed ticker — only for the LIVE Working banner (display may
    // be a stale Working banner whose label then stays frozen, like iOS).
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(banner) {
        if (banner is SessionChrome.Banner.Working) {
            while (true) {
                now = nowMs()
                delay(Motion.StatusTickMs)
            }
        }
    }

    val content = pillContent(display, now)
    val palette = RobocoTheme.palette
    val glyph = when (content.glyph) {
        PillGlyph.Trailer -> StatusGlyphKind.Trailer
        PillGlyph.DotDanger -> StatusGlyphKind.Dot(palette.danger)
        PillGlyph.DotInput -> StatusGlyphKind.Dot(palette.status.input)
        PillGlyph.DotIdle -> StatusGlyphKind.Dot(palette.status.idle)
    }
    GlassSurface(
        modifier = modifier
            .height(StatusPillDefaults.Height)
            .testTag("status-pill")
            // A live pill consumes every tap (iOS UIControl swallowing the
            // touch), so a tap never reaches the transcript under it.
            .then(
                if (live) {
                    Modifier.pointerInput(banner) { detectTapGestures { onTap(banner) } }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                // TalkBack reads the merged label text; the click action is
                // exposed only for actionable banners.
                if (live && tapEnabled(banner)) onClick {
                    onTap(banner)
                    true
                }
            },
        shape = CapsuleShape,
        interactive = false,
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = StatusPillDefaults.StartInset, end = StatusPillDefaults.EndInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(
                glyph,
                Modifier
                    .testTag("status-pill-glyph")
                    .size(StatusPillDefaults.GlyphSize),
            )
            Spacer(Modifier.width(StatusPillDefaults.GlyphGap))
            BasicText(
                content.text,
                modifier = Modifier.testTag("status-pill-label"),
                style = uiStyle(
                    Face.SansMedium,
                    LocalTypeScale.current.fixed(StatusPillDefaults.LabelPt),
                    palette.secondary,
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The pill's row (android-ui-parity #73, ticket 74 places it as the first
 * slot of the bottom stack): full-width, the pill hugging the **start**
 * edge and an empty spacer absorbing the rest (iOS
 * `UIStackView(arrangedSubviews: [pill, UIView()])`, `:123-124`). The
 * unweighted pill is measured first, so it caps at the row width and the
 * label ellipsizes.
 *
 * [banner] = `shownBanner(chrome.banner, editing)`; ticket 74 shows/hides
 * the row (and keeps `Banner.None` content drawn while it hides).
 */
@Composable
fun StatusPillRow(
    banner: SessionChrome.Banner,
    onTap: (SessionChrome.Banner) -> Unit,
    modifier: Modifier = Modifier,
    tapEnabled: (SessionChrome.Banner) -> Boolean = { true },
    nowMs: () -> Long = System::currentTimeMillis,
) {
    Row(modifier.fillMaxWidth()) {
        StatusPill(banner, onTap, tapEnabled = tapEnabled, nowMs = nowMs)
        Spacer(Modifier.weight(1f))
    }
}

/** Whether the pill row shows: every banner but `None` (ticket 74's `pillShown` reads this). */
val SessionChrome.Banner.showsPill: Boolean
    get() = this != SessionChrome.Banner.None

// ---------------------------------------------------------------------------
// The fixture (previews, the gallery and the goldens)
// ---------------------------------------------------------------------------

/**
 * The ticket's screenshot fixture (Step 12): one column, 390 dp wide, 12 dp
 * side padding, 8 dp between rows, `Palette.background` behind, a **fixed**
 * clock (`nowMs = 1_000_000L`) so the Working row reads
 * `Working · 1m 15s` deterministically. Eleven rows: Sending, Working,
 * NotDelivered, the two Failed texts, Editing, both EngineParked reasons,
 * EngineOff, Reconnecting and the over-long Failed label (truncation).
 */
@Composable
internal fun StatusPillFixtureColumn(nowMs: () -> Long = { 1_000_000L }) {
    val palette = RobocoTheme.palette
    Column(
        Modifier
            .width(390.dp)
            .background(palette.background)
            .testTag("status-pill-fixture")
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPillRow(SessionChrome.Banner.Sending, onTap = {}, nowMs = nowMs)
        StatusPillRow(SessionChrome.Banner.Working(925_000L, "Working"), onTap = {}, nowMs = nowMs)
        StatusPillRow(SessionChrome.Banner.NotDelivered, onTap = {}, nowMs = nowMs)
        StatusPillRow(
            SessionChrome.Banner.Failed("Couldn't send: Engine is offline; reconnecting"),
            onTap = {},
            nowMs = nowMs,
        )
        StatusPillRow(
            SessionChrome.Banner.Failed("Couldn't change session settings: unknown model"),
            onTap = {},
            nowMs = nowMs,
        )
        StatusPillRow(SessionChrome.Banner.Editing, onTap = {}, nowMs = nowMs)
        StatusPillRow(SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Revoked, "e1"), onTap = {}, nowMs = nowMs)
        StatusPillRow(
            SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.IdentityChanged, "e1"),
            onTap = {},
            nowMs = nowMs,
        )
        StatusPillRow(SessionChrome.Banner.EngineOff, onTap = {}, nowMs = nowMs)
        StatusPillRow(SessionChrome.Banner.Reconnecting, onTap = {}, nowMs = nowMs)
        StatusPillRow(
            SessionChrome.Banner.Failed("Couldn't send: " + "a very long error message ".repeat(6)),
            onTap = {},
            nowMs = nowMs,
        )
    }
}

/**
 * The "Status pill" gallery section (android-ui-parity #73): the fixture
 * column of every banner, plus a **live** `Working` pill whose elapsed
 * label ticks (`Working · 5s` → `Working · 6s`…). Under the Roborazzi
 * gallery capture the live pill's clock is pinned to `sinceMs + 5s` so the
 * golden shows a constant `Working · 5s` (the caller passes
 * `liveTicker = false` there).
 *
 * Registered in `debug/…/design/Gallery.kt` (ticket 22) as
 * `GallerySection("Status pill", screenshotHeightDp = 760)`.
 */
@Composable
fun StatusPillGallerySection(liveTicker: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPillFixtureColumn()
        val sinceMs = remember { System.currentTimeMillis() - 5_000L }
        if (liveTicker) {
            StatusPillRow(SessionChrome.Banner.Working(sinceMs, "Working"), onTap = {})
        } else {
            // Capture: frozen clock → the same "Working · 5s" every run.
            StatusPillRow(
                SessionChrome.Banner.Working(sinceMs, "Working"),
                onTap = {},
                nowMs = { sinceMs + 5_000L },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 10): the fixture, light and dark, frozen glyph clock
// nested inside the theme (ticket 11).
// ---------------------------------------------------------------------------

@Preview(name = "StatusPill light")
@Composable
private fun StatusPillLightPreview() {
    RobocoTheme(Appearance.Light) {
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusPillFixtureColumn() }
    }
}

@Preview(name = "StatusPill dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StatusPillDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        ProvideGlyphClock(frozenAtNanos = 0L) { StatusPillFixtureColumn() }
    }
}

package sh.roboco.android.session

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.ScrollEdgeEffect
import sh.roboco.android.design.glass.ScrollEdgeEffectDefaults
import sh.roboco.android.design.glass.contentUnderTopEdge
import sh.roboco.android.design.uiStyle
import sh.roboco.android.shell.LocalNavStack

/**
 * The session header's tunables (android-ui-parity #52): UIKit's
 * plain `UINavigationBar` geometry — a 44 pt row under the status bar, 16 dp
 * layout margins, 44 pt bar-button circles, the 240 pt title-view cap
 * (`ios:Session/SessionViewController.swift:571`), the 1 pt stack spacing
 * (`:563`) and the 8 dp item-gap approximation shared with ticket 25.
 * The bar has no fill and no divider; the glyphs are ticket 08's default
 * bar ink (decision SESSIONS-D1).
 */
object SessionHeaderDefaults {

    /** The nav-bar row height (`ScrollEdgeEffectDefaults.BarHeight`). */
    val BarHeight = 44.dp

    /** The bar items' horizontal layout margin (screenshot: 16). */
    val SideMargin = 16.dp

    /** The glass circles' frame, square (ticket 08's `GlassCircleButton`). */
    val ButtonSize = 44.dp

    /** The glyph point size of both bar circles (ticket 08: 44/17). */
    const val IconPt = 17f

    /** The title view's width cap (`SessionViewController.swift:571`). */
    val TitleMaxWidth = 240.dp

    /** The stack spacing between the title and subtitle lines (`:563`). */
    val TitleSpacing = 1.dp

    /** The approx. UIKit bar-item-to-title gap; the same value as ticket 25. */
    val TitleItemGap = 8.dp

    /** The title line: Geist SemiBold 16, `fixed` (ignores Dynamic Type). */
    const val TitlePt = 16f

    /** The subtitle line: Geist Regular 12, `fixed`. */
    const val SubtitlePt = 12f

    /**
     * The transcript's top content inset: content rests below the bar
     * (UIKit's `adjustedContentInset.top` = safe-area top + 44,
     * `ios:Transcript/TranscriptListView.swift:183`). Ticket 75 passes this
     * to the transcript host (65); the scroller itself is never padded
     * (padding would clip rows at the bar).
     */
    fun contentTopInset(statusBarTop: Dp): Dp = statusBarTop + BarHeight
}

/**
 * The header of the Session screen (android-ui-parity #52) — the port of
 * the screen's plain `UINavigationBar` (`ios:Session/SessionViewController.swift:41-57`):
 * a transparent 44 dp row under the status bar (no fill, no divider,
 * `largeTitleDisplayMode = .never`), a round glass back circle at the start,
 * the centred two-line [SessionTitleView] (title over `"<project> @ <engine>"`),
 * and a round glass ⋯ circle at the end that opens the session menu straight
 * from a tap (the menu itself is ticket 80). The whole band is backed by
 * ticket 09's soft top scroll-edge effect: the transcript scrolls up under
 * the bar and is blurred and faded, while the header items stay crisp.
 *
 * **Layout (top of the screen, [statusBarTop] in dp):**
 *
 * ```
 * 0 ……… statusBarTop ……… navBottom = statusBarTop + 44 ……… + 16 (soft)
 * | status bar |   44 dp bar row: back ◯ … title/subtitle … ⋯ ◯   | pass |
 * |   touch-blocking, full width; only the buttons react            | thru |
 * ```
 *
 * - The circles: [GlassCircleButton] 44 × 44 at 16 (+ display cutout), the
 *   chevron Semibold (06's `ChevronBackward`, auto-mirrored in RTL), the ⋯
 *   Medium — both at 17 pt, both with 08's default bar ink
 *   ([sh.roboco.android.design.glass.GlassButtonDefaults.barGlyphInk],
 *   SESSIONS-D1; no local override).
 * - The title view is centred on the **full** width (not between the
 *   buttons), its width hugging the wider line, capped at
 *   [SessionHeaderMetrics.titleMaxWidth].
 * - The bar area `0 … navBottom` consumes every touch (UIKit bar
 *   hit-testing, the same rule as ticket 25); the 16 dp soft extension
 *   below `navBottom` belongs to the edge effect alone and passes touches
 *   through to the transcript.
 * - `showBack = false` (the tablet detail column's root,
 *   `ios:Shell/SplitRootController.swift:44`) composes no back circle; the
 *   title stays centred on the full width.
 *
 * **Haze wiring (ticket 07, provided by the screen scaffold 75):** the
 * screen wraps itself in `ProvideHazeState`; the transcript host (65) is
 * the only `Modifier.hazeSource(state)`; this header is a **sibling after**
 * the transcript, never inside the source (a blur that samples itself feeds
 * back). The glass circles and the edge effect read the same
 * [LocalHazeState] this composable receives.
 *
 * **Engagement ([edgeEngaged], derived by ticket 75):** `true` once the
 * transcript has content under the bar — iOS
 * `contentOffset.y + adjustedContentInset.top > 0`
 * (`ios:Transcript/TranscriptListView.swift:303`). Read it from the
 * transcript viewport as a derived boolean
 * (`derivedStateOf { scrollY > 0.5f }`) so this header recomposes only when
 * the value flips, never per scroll frame.
 *
 * **No animation of its own** (`render` sets the title with no animation,
 * `SessionViewController.swift:479-483`): the press response is 08's, the
 * engage fade is 09's, and text changes replace in place on the next frame.
 *
 * **[LocalNavStack] defaults:** `showBack`/`onBack` default to the stack
 * (`canPop` / animated `pop()`), and 24's local has **no default provider** —
 * previews, the gallery and every test outside a `NavStackHost` must pass
 * both explicitly (Kotlin never evaluates a default whose argument is given).
 *
 * @param onMore the ⋯ tap; [Rect] is the circle's `boundsInWindow()` (px),
 *   the anchor ticket 80's `GlassMenu` opens at
 * @param statusBarTop the status-bar inset; 0 in dialogs/sheets that already
 *   sit below it
 * @param hazeState the screen's [HazeState] (ticket 07); `null` = no blur
 *   anywhere (the edge effect's scrim-only fallback, glass fallback fills)
 * @param accessory a second 44 dp bar item left of the ⋯ (android-next
 *   #06: the side chats button, iOS `rightBarButtonItems = [menu, …,
 *   sideChats]`); `null` = none. The title's cap narrows to clear it.
 */
@Composable
fun SessionHeader(
    title: String,
    subtitle: String,
    edgeEngaged: Boolean,
    onMore: (anchorInWindow: Rect) -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = LocalNavStack.current.canPop,
    onBack: () -> Unit = LocalNavStack.current.let { stack -> { stack.pop() } },
    statusBarTop: Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
    hazeState: HazeState? = LocalHazeState.current,
    accessory: (@Composable () -> Unit)? = null,
    /** android-next #06: false drops the ⋯ (a side chat's draft has no menu). */
    showMore: Boolean = true,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    // Horizontal safe area: UIKit's layout margins include the display
    // cutout (landscape notch). The inset getters are physical left/right
    // (direction-agnostic), so start/end are mirrored by hand, as ticket 25.
    val cutout = WindowInsets.displayCutout
    val cutoutStartPx =
        if (layoutDirection == LayoutDirection.Ltr) cutout.getLeft(density, layoutDirection)
        else cutout.getRight(density, layoutDirection)
    val cutoutEndPx =
        if (layoutDirection == LayoutDirection.Ltr) cutout.getRight(density, layoutDirection)
        else cutout.getLeft(density, layoutDirection)
    val cutoutStart = with(density) { cutoutStartPx.toDp() }
    val cutoutEnd = with(density) { cutoutEndPx.toDp() }

    val navBottom = statusBarTop + SessionHeaderDefaults.BarHeight
    val edgeHeight = ScrollEdgeEffectDefaults.height(statusBarTop)

    // The ⋯ menu anchor: written during layout, read only inside the click
    // lambda — layout changes never recompose this header (step 4's rule).
    var moreBounds by remember { mutableStateOf(Rect.Zero) }

    Box(modifier.fillMaxWidth().height(edgeHeight)) {
        // 1. The soft top edge (ticket 09): over the transcript, under the
        //    bar row. It draws no input, so the 16 dp soft extension below
        //    navBottom passes touches through to the transcript.
        ScrollEdgeEffect(
            height = edgeHeight,
            engaged = edgeEngaged,
            hazeState = hazeState,
        )
        // 2. The bar row: full width, statusBarTop + 44 high, transparent,
        //    blocking touches as a UIKit bar does (only its buttons react),
        //    a traversal group read before the transcript.
        Box(
            Modifier
                .fillMaxWidth()
                .height(navBottom)
                .blockTouches()
                .semantics {
                    isTraversalGroup = true
                    traversalIndex = -1f
                }
                .testTag("session-header"),
        ) {
            BoxWithConstraints(
                Modifier
                    .padding(top = statusBarTop)
                    .fillMaxWidth()
                    .height(SessionHeaderDefaults.BarHeight),
            ) {
                val titleMaxWidth = SessionHeaderMetrics.titleMaxWidth(
                    maxWidth.value,
                    showBack,
                    extraTrailing = if (accessory != null) SessionHeaderMetrics.AccessoryExtent else 0f,
                ).dp
                if (showBack) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = SessionHeaderDefaults.SideMargin + cutoutStart),
                    ) {
                        // The system back button: a 44/17 Semibold glass
                        // circle, 08's default bar ink (SESSIONS-D1). The
                        // glyph mirrors itself in RTL (06's autoMirror).
                        GlassCircleButton(
                            icon = RobocoIcons.ChevronBackward,
                            contentDescription = "Back",
                            onClick = onBack,
                            modifier = Modifier.testTag("nav-back"),
                            size = SessionHeaderDefaults.ButtonSize,
                            iconPt = SessionHeaderDefaults.IconPt,
                            weight = SymbolWeight.Semibold,
                        )
                    }
                }
                if (showMore) Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = SessionHeaderDefaults.SideMargin + cutoutEnd),
                ) {
                    // The ⋯: a 44/17 Medium glass circle (08's default bar
                    // ink). onGloballyPositioned sits between the wrapper's
                    // padding and the button's own size, so the reported
                    // rect is the 44 circle itself, in window px.
                    GlassCircleButton(
                        icon = RobocoIcons.Ellipsis,
                        contentDescription = "More",
                        onClick = { onMore(moreBounds) },
                        modifier = Modifier
                            .onGloballyPositioned { moreBounds = it.boundsInWindow() }
                            .testTag("session-more"),
                        size = SessionHeaderDefaults.ButtonSize,
                        iconPt = SessionHeaderDefaults.IconPt,
                        weight = SymbolWeight.Medium,
                    )
                }
                if (accessory != null) {
                    // The second bar item: one 44 dp circle plus UIKit's
                    // 8 dp item gap left of the ⋯.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(
                                end = SessionHeaderDefaults.SideMargin + cutoutEnd +
                                    SessionHeaderDefaults.ButtonSize + SessionHeaderDefaults.TitleItemGap,
                            ),
                    ) {
                        accessory()
                    }
                }
                SessionTitleView(
                    title = title,
                    subtitle = subtitle,
                    maxWidth = titleMaxWidth,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * The two-line centred navigation title (android-ui-parity #52), the port
 * of `SessionTitleView` (`ios:Session/SessionViewController.swift:547-582`):
 * the session title (Geist SemiBold 16, `fixed`, `Palette.text`) over the
 * subtitle `"<project> @ <engine>"` (Geist Regular 12, `fixed`,
 * `Palette.secondary`), 1 dp apart, each line centred when it fits and
 * **fading its tail** (24 dp, ticket 10's [FadingText]) when it is wider
 * than its slot — never "…".
 *
 * The column hugs the wider of its two lines (no `fillMaxWidth`, as UIKit's
 * stack hugs), capped at [maxWidth]; the narrower line is centred inside
 * it. An empty [subtitle] composes no second line, so the title alone is
 * centred vertically in the 44 dp row (top 11.6). Exposed for the previews
 * and the tablet detail column (33/106).
 */
@Composable
fun SessionTitleView(
    title: String,
    subtitle: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier.widthIn(max = maxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SessionHeaderDefaults.TitleSpacing),
    ) {
        FadingText(
            title,
            uiStyle(
                Face.SansSemibold,
                typeScale.fixed(SessionHeaderDefaults.TitlePt),
                palette.text,
            ),
            // FadingText collapses the caller's semantics with its own
            // clearAndSetSemantics { text }: the heading and the tag land
            // on the same single node, which keeps the FULL title text.
            Modifier.testTag("session-title").semantics { heading() },
            fitsAlignment = Alignment.CenterHorizontally,
        )
        if (subtitle.isNotEmpty()) {
            FadingText(
                subtitle,
                uiStyle(
                    Face.Sans,
                    typeScale.fixed(SessionHeaderDefaults.SubtitlePt),
                    palette.secondary,
                ),
                Modifier.testTag("session-subtitle"),
                fitsAlignment = Alignment.CenterHorizontally,
            )
        }
    }
}

/**
 * The bar row's UIKit hit-testing: the bar's bounds swallow taps and drags,
 * so a row scrolled under the bar is never selected — only the buttons
 * inside react (children are hit-tested first). The loop awaits without
 * consuming; composing this `pointerInput` makes the row the hit target
 * that ends dispatch.
 */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

// ---------------------------------------------------------------------------
// The fixture (previews, the gallery and the goldens)
// ---------------------------------------------------------------------------

/** The ticket's fixture title (the reference PNG's session title). */
internal const val SessionHeaderReferenceTitle = "Streaming veil on transcript rows"

/** The fixture transcript's paragraph, repeated 20 times. */
internal const val SessionHeaderFixtureParagraph =
    "Port the streaming fade-in veil from transcript.rs into Transcript. " +
        "It must never affect layout — opacity only, split at chunk boundaries."

/**
 * The fixture's [LazyListState], pre-scrolled by [offsetDp] (the ticket's
 * 120 dp, so text sits under the bar); 0 = the transcript at its top (the
 * side-by-side reference capture, where the effect is not engaged).
 * Initial index/offset only — no runtime scroll, so goldens stay static.
 */
@Composable
internal fun rememberSessionHeaderFixtureListState(offsetDp: Float = 120f): LazyListState {
    val density = LocalDensity.current
    return rememberLazyListState(
        initialFirstVisibleItemScrollOffset = with(density) { offsetDp.dp.roundToPx() },
    )
}

/**
 * The ticket's fixture (previews, the "Session header" gallery section and
 * the goldens): the reference header over a transcript stand-in — a
 * `hazeSource`-wrapped `LazyColumn` of 20 Geist Regular `fixed(17)`
 * paragraphs with 20 dp side margins, top content padding
 * [SessionHeaderDefaults.contentTopInset] and a 120 dp pre-scroll, so text
 * sits under the bar and the soft edge engages. The real painter is ticket
 * 65; this only shows the header's layering (edge effect under the bar,
 * transcript under both).
 *
 * `showBack`/`onBack` are passed explicitly — never the [LocalNavStack]
 * defaults (the fixture is composed outside any `NavStackHost`).
 *
 * @param engaged `null` = auto: the list state's `contentUnderTopEdge`, as
 *   ticket 75 will derive it from the real viewport
 */
@Composable
internal fun SessionHeaderFixture(
    title: String = SessionHeaderReferenceTitle,
    subtitle: String = "roboco @ MacBook Pro",
    showBack: Boolean = true,
    engaged: Boolean? = null,
    statusBarTop: Dp = 24.dp,
    listState: LazyListState = rememberSessionHeaderFixtureListState(),
    onBack: () -> Unit = {},
    onMore: (anchorInWindow: Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val isEngaged = engaged ?: listState.contentUnderTopEdge
    ProvideHazeState {
        Box(modifier.fillMaxSize().background(palette.background)) {
            // 1. The haze source: everything that scrolls (the transcript
            //    host, ticket 65). Fills the screen — its content inset is
            //    the bar's bottom, never a padding on the scroller.
            Box(Modifier.fillMaxSize().hazeSource(LocalHazeState.current!!)) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        top = SessionHeaderDefaults.contentTopInset(statusBarTop),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(20) {
                        BasicText(
                            SessionHeaderFixtureParagraph,
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
                        )
                    }
                }
            }
            // 2. The header: a sibling after the source, over it.
            SessionHeader(
                title = title,
                subtitle = subtitle,
                edgeEngaged = isEngaged,
                onMore = onMore,
                showBack = showBack,
                onBack = onBack,
                statusBarTop = statusBarTop,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 7): the fixture, light and dark.
// ---------------------------------------------------------------------------

@Preview(name = "light")
@Composable
private fun SessionHeaderLightPreview() {
    RobocoTheme(Appearance.Light) { SessionHeaderFixture() }
}

@Preview(name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SessionHeaderDarkPreview() {
    RobocoTheme(Appearance.Dark) { SessionHeaderFixture() }
}

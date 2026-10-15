package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import sh.roboco.android.composer.MentionSuggestionsHost
import sh.roboco.android.core.Face
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.sheet.GlassSheetDefaults
import sh.roboco.android.design.sheet.SheetHeaderRow
import sh.roboco.android.design.sheet.SheetLeading
import sh.roboco.android.design.uiStyle

/**
 * Insets after ancestor consumption, dp floats (android-ui-parity #93).
 * `null` in production — the canvas then reads the live [WindowInsets] in
 * its measure block. Tests and previews pass fixed values.
 */
@Immutable
data class CanvasInsets(
    /** The status bar left for the embedded nav row; 0 in the sheet. */
    val top: Float,
    val safeLeft: Float,
    val safeRight: Float,
    /** `max(ime, navBars)` this page has not already been lifted by. */
    val bottom: Float,
    /** The IME height an ancestor already removed (31's `imePadding`). */
    val imeAbove: Float,
)

/**
 * The canvas's observable geometry, written from measure/placement and read
 * by the wallpaper's draw, the tap detector and the focus-on-appear wait:
 *
 * - [composerRect] is snapshot state (the wallpaper reads it **in the draw
 *   phase**; the placement block calls [Snapshot.sendApplyNotifications]
 *   after writing so the cutout follows within the same frame, not one
 *   behind during the IME animation);
 * - [freeTopPx] is a plain field (read at tap time only, never observed);
 * - the coordinates are held for 21's consumer contract and 94's capture
 *   modifiers, never kept in `mutableStateOf` (the object identity is
 *   stable across frames).
 */
@Stable
class NewSessionCanvasState {

    /** The composer's rect in page (= wallpaper-local) px. */
    var composerRect: Rect? by mutableStateOf(null)
        internal set

    /** The nav bar's bottom in page px; the tap detector's upper bound. */
    var freeTopPx: Float = 0f
        internal set

    /** The page root's coordinates; the settle wait (step 6) reads these. */
    var pageCoordinates: LayoutCoordinates? = null
        internal set

    /** The composer slot's coordinates; 94 attaches its capture modifiers. */
    var composerCoordinates: LayoutCoordinates? = null
        internal set
}

/**
 * The New Session page (android-ui-parity #93) — the port of
 * `NewSessionViewController`'s `view` (`ios:Session/
 * NewSessionViewController.swift:101-237`): wallpaper, hero, composer, in
 * that z-order, behind a transparent nav bar.
 *
 * Phone: the content of 31's page sheet (`embedded = false`). Tablet: the
 * detail column (106, `embedded = true`, no ✕). The measure is the custom
 * [Layout] below; [NewSessionScreen] owns the state and wiring, this
 * composable only places what it is given.
 *
 * Subview order (bottom → top), as on iOS: wallpaper (inside the haze
 * source backdrop), hero (a second, higher haze source so 86's glass card
 * can blur it), composer, then 86's suggestion layer (the host's last
 * child) and finally the nav bar — a sibling drawn after the host, the way
 * the UIKit bar floats over `view` and its subviews. The nav bar, composer
 * and card are chrome and sit outside every `hazeSource` of the screen's
 * state (ticket 07's rule).
 */
@Composable
internal fun NewSessionCanvas(
    harness: String,
    embedded: Boolean,
    onClose: () -> Unit,
    onBackgroundTap: () -> Unit,
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    state: NewSessionCanvasState = remember { NewSessionCanvasState() },
    insets: CanvasInsets? = null,
    wallpaper: @Composable (Modifier, () -> Rect?) -> Unit = { m, c -> WallpaperHero(m, cutout = c) },
    /** android-next #06: a side chat's draft says "Ask on the side". */
    headline: String = NewSessionHeadline,
    /** android-next #06: false when the host draws its own bar (the side-chat draft's session header). */
    navBar: Boolean = true,
) {
    ProvideHazeState {
        val haze = LocalHazeState.current!!
        val palette = RobocoTheme.palette

        // The consumed insets (what ancestors already lifted this page by)
        // and the live system insets, captured in composition and read in
        // the measure block below, so every IME animation frame re-layouts
        // without recomposing.
        var consumed by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
        val imeInsets = WindowInsets.ime
        val navInsets = WindowInsets.navigationBars
        val statusInsets = WindowInsets.statusBars
        val safeInsets = WindowInsets.safeDrawing

        // The IME height an ancestor consumed, px; written in measure,
        // before the backdrop measures, and read by the wallpaper frame's
        // own measure (a measure-phase read, never a recomposition).
        var imeAbovePx by remember { mutableStateOf(0f) }

        Box(
            modifier
                .fillMaxSize()
                .onConsumedWindowInsetsChanged { consumed = it }
                .onGloballyPositioned { state.pageCoordinates = it }
                .dismissKeyboardOnTap(state, onBackgroundTap)
                .testTag("new-session-screen")
                .semantics { paneTitle = "New Session" },
        ) {
            MentionSuggestionsHost(Modifier.fillMaxSize()) {
                val backdrop: @Composable () -> Unit = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(palette.background)
                            .hazeSource(haze, zIndex = 0f),
                    ) {
                        wallpaper(Modifier.wallpaperFrame { imeAbovePx }, { state.composerRect })
                    }
                }
                val hero: @Composable () -> Unit = {
                    NewSessionHero(
                        harness,
                        Modifier
                            .hazeSource(haze, zIndex = 1f)
                            .testTag("new-session-hero"),
                        headline = headline,
                    )
                }
                val composerSlot: @Composable () -> Unit = {
                    Box(Modifier.onGloballyPositioned { state.composerCoordinates = it }) { composer() }
                }
                Layout(
                    contents = listOf(backdrop, hero, composerSlot),
                    modifier = Modifier.fillMaxSize(),
                    measurePolicy = { measurables, constraints ->
                        // Multi-content Layout: one List<Measurable> per slot, each
                        // slot a single root.
                        val (backdropM, heroM, compM) = measurables.map { it.single() }

                        // 1. The insets, px: fixed (tests/previews) or live.
                        val topPx: Float
                        val safeLPx: Float
                        val safeRPx: Float
                        val bottomInsetPx: Float
                        val imeAbovePxVal: Float
                        if (insets != null) {
                            topPx = insets.top.dp.toPx()
                            safeLPx = insets.safeLeft.dp.toPx()
                            safeRPx = insets.safeRight.dp.toPx()
                            bottomInsetPx = insets.bottom.dp.toPx()
                            imeAbovePxVal = insets.imeAbove.dp.toPx()
                        } else {
                            val ime = imeInsets.getBottom(this)
                            val nav = navInsets.getBottom(this)
                            val cb = consumed.getBottom(this)
                            topPx = max(0, statusInsets.getTop(this) - consumed.getTop(this)).toFloat()
                            safeLPx = max(
                                0,
                                safeInsets.getLeft(this, layoutDirection) - consumed.getLeft(this, layoutDirection),
                            ).toFloat()
                            safeRPx = max(
                                0,
                                safeInsets.getRight(this, layoutDirection) - consumed.getRight(this, layoutDirection),
                            ).toFloat()
                            bottomInsetPx = max(0, max(ime, nav) - cb).toFloat()
                            imeAbovePxVal = min(ime, cb).toFloat()
                        }

                        // 2. The page: W × Hc as laid out, Hp as the IME-less
                        //    height the wallpaper is sized from.
                        val w = constraints.maxWidth
                        val hc = constraints.maxHeight
                        val scale = density
                        val dpF = { px: Float -> px / scale }
                        val pxF = { d: Float -> d * scale }

                        // Published before the backdrop measures below, so
                        // the wallpaper frame sees it in this pass already.
                        if (imeAbovePx != imeAbovePxVal) imeAbovePx = imeAbovePxVal

                        // 3. The nav band's bottom = the free space's top.
                        val freeTopPx = if (embedded) {
                            topPx + pxF(NewSessionLayout.EmbeddedNavRowHeight)
                        } else {
                            pxF(NewSessionLayout.SheetNavBarHeight)
                        }
                        state.freeTopPx = freeTopPx

                        // 4. The composer's width and x (dp math in the object).
                        val safeLDp = dpF(safeLPx)
                        val safeRDp = dpF(safeRPx)
                        val safeWDp = dpF(w - safeLPx - safeRPx)
                        val cwDp = NewSessionLayout.composerWidth(safeWDp)
                        val cwPx = cwDp.dp.roundToPx()
                        val cxDp = NewSessionLayout.composerX(safeLDp, safeWDp, cwDp)

                        // 5. The composer: width pinned, height measured,
                        //    bottom `Hc − bottomInset − 8`.
                        val compP = compM.measure(
                            Constraints(minWidth = cwPx, maxWidth = cwPx, maxHeight = hc),
                        )
                        val chPx = compP.height
                        val cBottomPx = pxF(
                            NewSessionLayout.composerBottom(dpF(hc.toFloat()), dpF(bottomInsetPx)),
                        )
                        val cTopPx = cBottomPx - chPx

                        // 6. The hero: headline width capped, height
                        //    measured (34 + 14 + lines × 28.6).
                        val heroP = heroM.measure(
                            Constraints(
                                maxWidth = NewSessionLayout
                                    .headlineMaxWidth(dpF(w.toFloat()), safeLDp, safeRDp)
                                    .dp
                                    .roundToPx(),
                            ),
                        )
                        val hyPx = pxF(
                            NewSessionLayout.heroTop(
                                dpF(freeTopPx),
                                dpF(cTopPx - pxF(NewSessionLayout.HeroComposerGap)),
                                dpF(heroP.height.toFloat()),
                            ),
                        )
                        val hxPx = (w - heroP.width) / 2f

                        // 7. The backdrop fills the page (the wallpaper may
                        //    overflow it downward under the keyboard — never
                        //    clipped).
                        val backdropP = backdropM.measure(Constraints.fixed(w, hc))

                        // 8. 86's host places the suggestion card from the
                        //    composer's own anchor: nothing to measure here.

                        // 9-10. Place in z-order, then publish the cutout.
                        layout(w, hc) {
                            backdropP.place(0, 0)
                            heroP.place(hxPx.roundToInt(), hyPx.roundToInt())
                            val cXI = pxF(cxDp).roundToInt()
                            val cTopI = cTopPx.roundToInt()
                            compP.place(cXI, cTopI)
                            val rect = Rect(
                                cXI.toFloat(),
                                cTopI.toFloat(),
                                (cXI + cwPx).toFloat(),
                                (cTopI + chPx).toFloat(),
                            )
                            if (state.composerRect != rect) {
                                state.composerRect = rect
                                // Same-frame cutout: apply the write now so
                                // the wallpaper's draw-phase read
                                // invalidates before this frame's draw.
                                Snapshot.sendApplyNotifications()
                            }
                        }
                    },
                )
            }
            if (navBar) NewSessionNavBar(embedded, onClose, insets)
        }
    }
}

/**
 * The hero column (`:119-131`): the harness's brand mark (34 dp, tinted
 * `Palette.text`, the Claude ids keep their orange in 13's resolver) over
 * the headline "What are we building?" — Geist SemiBold `fixed(22)` (iOS
 * `Fonts.ui` ignores Dynamic Type), centred, at most 2 lines with an
 * ellipsis. The label box is 1.3 × 22 = 28.6 dp per line (ticket 03), so
 * the column is 76.6 dp (1 line) or 105.2 dp (2 lines) at font scale 1.
 */
@Composable
internal fun NewSessionHero(harness: String, modifier: Modifier = Modifier, headline: String = NewSessionHeadline) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NewSessionLayout.HeroSpacing.dp),
    ) {
        HarnessMark(
            harness,
            NewSessionLayout.MarkSide.dp,
            palette.text,
            Modifier
                .testTag("new-session-mark")
                .clearAndSetSemantics { },
        )
        BasicText(
            headline,
            Modifier.testTag("new-session-headline"),
            style = uiStyle(Face.SansSemibold, typeScale.fixed(NewSessionLayout.HeadlineSize), palette.text)
                .copy(textAlign = TextAlign.Center),
            maxLines = NewSessionLayout.HeadlineMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The New Session hero's headline (`:122`). */
const val NewSessionHeadline = "What are we building?"

/** A side chat's draft headline (upstream #899's `sideOf` hero). */
const val SideChatHeadline = "Ask on the side"

/**
 * The transparent nav bar (`:113-118`, `SplitRootController.swift:46-47`).
 *
 * - **Sheet**: 17's [SheetHeaderRow] reused as is — the 52 dp row every
 *   Roboco page sheet uses, Geist SemiBold 17 title, the ✕ glass circle
 *   (tag `sheet-close`, "Close") — exactly 31's placeholder bar. It draws
 *   no fill, so the wallpaper shows through it as on iOS 26.
 * - **Embedded**: a 44 dp row (25's `NavRowHeight`) after the status-bar
 *   inset, no ✕. Same title, same `W − 136` max width (17's rule).
 *
 * The last child of the canvas's root `Box`: drawn above the canvas and
 * 86's suggestion layer, like the floating UIKit bar.
 */
@Composable
internal fun NewSessionNavBar(embedded: Boolean, onClose: () -> Unit, insets: CanvasInsets?) {
    if (!embedded) {
        SheetHeaderRow(
            title = "New Session",
            leading = SheetLeading.Close,
            onLeading = onClose,
            modifier = Modifier.testTag("new-session-nav"),
        )
        return
    }
    val topPad = if (insets != null) {
        Modifier.padding(top = insets.top.dp)
    } else {
        Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(topPad)
            .height(NewSessionLayout.EmbeddedNavRowHeight.dp)
            .testTag("new-session-nav"),
    ) {
        val palette = RobocoTheme.palette
        val typeScale = LocalTypeScale.current
        BasicText(
            "New Session",
            Modifier
                .align(Alignment.Center)
                .widthIn(max = maxWidth - GlassSheetDefaults.TitleClearance)
                .semantics { heading() }
                .testTag("new-session-title"),
            style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The wallpaper's frame modifier: the child is measured at
 * `Constraints.fixed(W, min(0.72 × Hp, 760))` with `Hp = Hc + imeAbove`
 * (the page height **without** the IME), placed at the page origin and
 * reported as `min(h, Hc)` — the artwork overflows the shrunken page
 * downward, under the keyboard, and the backdrop never clips it, so the
 * crop and the fade never re-scale when the keyboard opens.
 */
private fun Modifier.wallpaperFrame(imeAbove: () -> Float): Modifier = layout { measurable, constraints ->
    val pageHeight = constraints.maxHeight + imeAbove().roundToInt()
    val w = constraints.maxWidth
    val hPx = NewSessionLayout.wallpaperHeight(pageHeight / density).dp.roundToPx()
    val child = measurable.measure(Constraints.fixed(w, hPx))
    layout(w, min(hPx, constraints.maxHeight)) {
        child.place(0, 0)
    }
}

/**
 * Tap-to-dismiss the keyboard (`:107-110, 228-231`) — the port of UIKit's
 * `cancelsTouchesInView = false` tap recognizer, observing in the **Main**
 * pass (children saw the event first):
 *
 * - a single-pointer tap (down → up within touch slop) whose down is
 *   **not** in the nav bar (`y < freeTop`) and **not** inside the
 *   composer's rect, and whose up was **not** consumed by a child (a
 *   button, a mention row — UIKit's "controls win" rule), fires [onTap];
 * - the detector never consumes, so children still get every event;
 * - 86's card sits 8 dp above the composer, outside the rect: a tap on its
 *   rows is consumed (no dismissal), a tap on its padding dismisses.
 */
internal fun Modifier.dismissKeyboardOnTap(state: NewSessionCanvasState, onTap: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val rect = state.composerRect
            if (down.position.y < state.freeTopPx || (rect != null && rect.contains(down.position))) {
                return@awaitEachGesture
            }
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.size > 1) return@awaitEachGesture // multi-touch: not a tap
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (ch.changedToUpIgnoreConsumed()) {
                    if (!moved && !ch.isConsumed) onTap()
                    return@awaitEachGesture
                }
            }
        }
    }

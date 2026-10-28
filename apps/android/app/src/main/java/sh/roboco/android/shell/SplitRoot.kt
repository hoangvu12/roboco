package sh.roboco.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import sh.roboco.android.design.Motion
import sh.roboco.android.design.RobocoTheme

/**
 * The tablet split shell (android-ui-parity #33): the port of
 * `ios:Shell/SplitRootController.swift`'s `UISplitViewController`
 * (`.doubleColumn`, `.oneBesideSecondary`, `.tile`) as one custom
 * [Layout] — a 360 dp sidebar **beside** the detail column, nothing
 * overlaying, the detail narrower by the sidebar width:
 *
 *  - a 1-px hairline over the sidebar's **trailing** pixel column (inside
 *    the 360 dp, so the detail starts at exactly 360 dp);
 *  - flat `Palette.background` on both panes — no glass, no blur, no
 *    material (`primaryBackgroundStyle = .none`);
 *  - the show/hide slide: the sidebar and the detail **content** are
 *    measured at their target sizes, and only the pane background and the
 *    placements move per frame (`Motion.sidebarToggle()`, 300 ms
 *    easeInOut — `UIView.animate(withDuration: 0.3)`);
 *  - a hidden sidebar **stays composed** (scroll position, back stack and
 *    search text survive), placed off the leading edge with layer alpha 0,
 *    cleared from semantics and blocked from focus traversal;
 *  - inline titles only: `LocalPrefersLargeTitles provides false` around
 *    the detail slot (the detail column's own navigation stack uses
 *    inline titles — `SplitRootController.swift:46-47`).
 *
 * The slots bring the content: `sidebar` (105/107), `detail` (106 — it
 * renders the persistent detail stack for the current [DetailRoot]).
 * This frame owns no stack of its own and rebuilds nothing per detail
 * change: ticket 106 owns one persistent detail stack replaced by
 * `setRoutes`, whose unanimated replacement delivers
 * `onDidDisappear(removed = true)` + `NavEntry.close()` to the old root
 * (a rebuild keyed on the root would leave the replaced session
 * attached).
 *
 * `SplitRoot` never writes `LocalBottomChromeTop` (ticket 14): with no tab
 * chrome on screen it stays `Dp.Unspecified`, so toasts sit 12 dp above
 * the safe-area bottom, centred on the whole window, as on iPad.
 *
 * @param shell the window's [TabletShellState] (ticket 35 creates it
 *   above its tabs/split switch).
 * @param sidebar the sidebar pane's content; `shown` is the *target*
 *   visibility — 105 uses it to disable its stack's back handling
 *   while hidden.
 * @param detail the detail pane's content for the current root; the pane
 *   only decides *where* it sits.
 * @param router the shell's router; defaults to [LocalAppRouter] (the
 *   tablet implementation is ticket 106).
 */
@Composable
fun SplitRoot(
    shell: TabletShellState,
    sidebar: @Composable (shown: Boolean) -> Unit,
    detail: @Composable (root: DetailRoot) -> Unit,
    modifier: Modifier = Modifier,
    router: AppRouter = LocalAppRouter.current,
) {
    val palette = RobocoTheme.palette
    val focusManager = LocalFocusManager.current

    // The sidebar position follows `sidebarVisible`: snap on (re)composition
    // so a change made while this composable was not on screen (or a
    // restore) never plays, then animate every change — collectLatest
    // cancels an in-flight animateTo, so a reversal continues from the
    // current fraction with no snap (⌘B twice within 100 ms reverses).
    LaunchedEffect(shell) {
        shell.sidebarProgress.snapTo(if (shell.sidebarVisible) 1f else 0f)
        snapshotFlow { shell.sidebarVisible }
            .drop(1)
            .collectLatest { visible ->
                shell.sidebarProgress.animateTo(if (visible) 1f else 0f, Motion.sidebarToggle())
            }
    }

    // Hiding the sidebar while focus is inside it (the sidebar search
    // field) clears focus, which also drops the IME: an off-screen field
    // must not keep the keyboard up.
    var sidebarHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(shell.sidebarVisible) {
        if (!shell.sidebarVisible && sidebarHasFocus) {
            focusManager.clearFocus(force = true)
        }
    }

    // The settled-hidden semantics gate: TalkBack must not reach off-screen
    // rows. `sidebarFraction` itself is never read in the composable body
    // (that would recompose this whole composable every animation frame) —
    // only this derived boolean, which flips exactly at settle and at the
    // start of the reverse slide. derivedStateOf (not a collector writing a
    // second state) so the flip recomposes in the very frame the slide
    // settles, with no extra frame of lag.
    val sidebarSettledHidden by remember(shell) {
        derivedStateOf { !shell.sidebarVisible && shell.sidebarProgress.value == 0f }
    }

    // The sidebar pane: the hairline is the last modifier *before* the
    // insets/focus/semantics chain so it draws over the content
    // (it is added after the sidebar's navigation view on iOS).
    //
    // The settled-hidden clear comes first: a clearing semantics modifier
    // only wipes the semantics *inside* it (modifiers after it and the
    // descendants), so after the testTag it would leave a tagged
    // `split-sidebar` node behind. Outermost, the hidden sidebar has no
    // semantics at all (the ticket's "no semantics nodes" criterion).
    val sidebarPane = Modifier
        .then(
            if (sidebarSettledHidden) {
                Modifier.clearAndSetSemantics { }
            } else {
                Modifier
            },
        )
        .testTag("split-sidebar")
        .background(palette.background)
        .drawWithContent {
            drawContent()
            // One physical pixel on the sidebar's trailing edge, drawn over
            // the content, full height (SplitRootController.swift:270-280);
            // trailing edge in the draw scope = left in RTL.
            val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - 1f
            drawRect(palette.hairline, topLeft = Offset(x, 0f), size = Size(1f, size.height))
        }
        // The trailing cutout / nav inset belongs to the detail, not the
        // sidebar (consume it so the sidebar content doesn't pad it).
        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
        .onFocusChanged { state -> sidebarHasFocus = state.hasFocus }
        .focusProperties { onEnter = { if (!shell.sidebarVisible) cancelFocusChange() } }
        .focusGroup()

    // The detail pane: the leading inset belongs to the sidebar while it
    // is on screen; hidden, the detail consumes nothing.
    val detailPane = Modifier
        .testTag("split-detail")
        .then(
            if (shell.sidebarVisible) {
                Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
            } else {
                Modifier
            },
        )

    // Read `shell.sidebarFraction` only inside this MeasurePolicy (and in
    // the draw/layer lambdas): the animation then re-runs only this layout
    // node's measure and placement, never recomposition. The sidebar and
    // the detail content get target-only constraints — `fixed(sPx, h)` and
    // `fixed(contentWidth, h)` — so they are never re-measured mid-slide;
    // only the trivial pane-background box is.
    val measurePolicy = MeasurePolicy { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val sidebarPx = SidebarPreferredWidth.coerceIn(SidebarMinWidth, SidebarMaxWidth).roundToPx()
        val geometry =
            splitGeometry(width, sidebarPx, shell.sidebarFraction, shell.sidebarVisible)
        val sidebarChild = measurables[0].measure(Constraints.fixed(min(sidebarPx, width), height))
        val paneBackground = measurables[1].measure(Constraints.fixed(geometry.paneWidth, height))
        val detailChild = measurables[2].measure(Constraints.fixed(geometry.contentWidth, height))
        layout(width, height) {
            // Draw order is the child order: the sidebar, then the detail on
            // top. They never overlap (the sidebar's trailing edge is exactly
            // the detail pane's leading edge).
            sidebarChild.placeRelativeWithLayer(geometry.sidebarX, 0) {
                alpha = if (shell.sidebarFraction == 0f) 0f else 1f
            }
            paneBackground.placeRelative(geometry.paneX, 0)
            detailChild.placeRelative(geometry.paneX, 0)
        }
    }

    // The sidebar toolbar's bottom gap — the insets 105's placement uses.
    val toolbarBarBottom = sh.roboco.android.shell.tablet.rememberSidebarBottomInsets().barBottom
    Layout(
        modifier = modifier
            .testTag("split-root")
            .fillMaxSize()
            .background(palette.background)
            .clipToBounds()
            .then(rememberShellShortcuts(router, shell)),
        measurePolicy = measurePolicy,
        content = {
            // 1. The sidebar pane — content slot last, composed before the
            //    detail so the detail stack's BackHandler registers later and
            //    wins when both have pushed entries.
            Box(sidebarPane) {
                sidebar(shell.sidebarVisible)
            }
            // 2. The detail pane background (`Palette.background`): the only
            //    box re-measured per animation frame; it fills any strip the
            //    target-width content doesn't cover while it slides.
            Box(Modifier.background(palette.background))
            // 3. The detail content box: laid out once at the target width and
            //    slid; the root's clipToBounds trims the trailing overflow
            //    while it hides. The slot is ticket 106's — it owns the stack,
            //    this frame only places it.
            Box(detailPane) {
                CompositionLocalProvider(
                    LocalPrefersLargeTitles provides false,
                    // Wave-10 42 (#1014): the resting composer sits level
                    // with the sidebar toolbar while the sidebar shows.
                    sh.roboco.android.session.LocalSidebarToolbarMid provides if (shell.sidebarVisible) {
                        toolbarBarBottom + sh.roboco.android.shell.tablet.SidebarChromeMetrics.ToolbarHeight / 2
                    } else {
                        null
                    },
                ) {
                    detail(shell.detailRoot)
                }
            }
        },
    )
}

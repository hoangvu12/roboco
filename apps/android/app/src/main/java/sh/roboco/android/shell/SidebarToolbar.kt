package sh.roboco.android.shell

import android.content.res.Configuration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassButtonGroup
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassGroupItem
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.sessions.rememberSessionsOptionsMenu

/**
 * Test-only: the toolbar row's fade (1 = fully visible), exposed through
 * the semantics tree exactly like [sh.roboco.android.design.AlertAlphaKey]
 * — the semantics block's snapshot reads are observed by the tree, so the
 * key follows the NavStack transition frame by frame without recomposing
 * the row. Production never reads it; UI tests assert
 * `alpha == sidebarToolbarVisibility(progress)` mid-transition.
 */
val SidebarToolbarAlphaKey = SemanticsPropertyKey<Float>("SidebarToolbarAlpha")

/**
 * The sidebar toolbar's starred metrics (ticket 107). [ItemSize] is the
 * 44 dp item height — equal to 105's
 * [sh.roboco.android.shell.tablet.SidebarChromeMetrics.ToolbarHeight], the
 * slot this row fills; **retune the row height and the bottom offset there
 * (105's single source), never here** — this object adds no offset of its
 * own.
 */
object SidebarToolbarDefaults {

    /** One toolbar item (and one [GlassButtonGroup] slot): 44 dp. */
    val ItemSize = 44.dp

    /** The row's side margins inside the 360 dp sidebar pane (16 ★). */
    val SideMargin = 16.dp
}

/**
 * 1 at the sidebar stack's root, 0 on a pushed folder, interpolated by the
 * running transition (ticket 107): UIKit cross-fades `toolbarItems`
 * alongside the push/pop, and a predictive back tracks the finger, so the
 * value reads [NavTransition.progress] directly — there is no separate
 * animation. A plain snapshot-state function (no composition), callable
 * from a `graphicsLayer` block for a draw-phase read.
 *
 * `entries` is the **committed** stack, so `entries.first()` is the root
 * throughout a transition: a push from the root fades `1 → 0`
 * (`t.from` = root), a pop to it fades `0 → 1` (`t.to` = root), a push
 * between two folders stays 0, and the idle cases fall out of
 * `entries.size`.
 */
fun sidebarToolbarVisibility(stack: NavStack): Float {
    val root = stack.entries.first().id
    val t = stack.transition ?: return if (stack.entries.size == 1) 1f else 0f
    fun at(entry: NavEntry) = if (entry.id == root) 1f else 0f
    return lerp(at(t.from), at(t.to), t.progress.coerceIn(0f, 1f))
}

/**
 * The tablet sidebar's bottom toolbar (ticket 107) — the port of iOS
 * `SplitRootController`'s `nav.toolbarItems`
 * (`ios:Shell/SplitRootController.swift:239-268`): a leading `[gear ⋯]`
 * glass group, a flexible space, and one prominent accent `+`, floating
 * over the bottom of the sidebar's root page.
 *
 * Fills 105's toolbar slot: a full-width, 44-dp-high [BoxScope] whose
 * bottom sits `barBottom` above the window bottom (105 places it; this
 * adds no offset, no inset padding and no IME ride — the keyboard covers
 * it, as on iPad).
 *
 * - gear → [AppRouter.showSettings] (the tablet router's Settings dialog);
 * - ⋯ → ticket 46's options menu bound to **[sidebarStack]** (the sidebar
 *   must be inside a [NavStackHost] for the default; the ⋯ slot's own
 *   window bounds are the anchor), built at **tap time** so the
 *   Group-by-Project check is fresh, presented by ticket 15's GlassMenu —
 *   needs a `GlassMenuHost` above (35 mounts it at the root);
 * - plus → [AppRouter.presentNewSession] with a null prompt.
 *
 * @param sidebarStack the sidebar's own stack (the `Archived` row pushes
 *   here, never into the detail column)
 * @param optionsMenu the menu builder, invoked only from the ⋯ click
 */
@Composable
fun SidebarToolbar(
    sidebarStack: NavStack = LocalNavStack.current,
    modifier: Modifier = Modifier,
    router: AppRouter = LocalAppRouter.current,
    optionsMenu: () -> Menu = rememberSessionsOptionsMenu(sidebarStack),
) {
    // Throws without a GlassMenuHost above — a toolbar that cannot open
    // its menu is a wiring bug, not a silent no-op (15's rule).
    val menu = rememberGlassMenuState()
    SidebarToolbarContent(
        visibility = { sidebarToolbarVisibility(sidebarStack) },
        onSettings = { router.showSettings() },
        // The menu is built inside the click (never during composition):
        // the Group-by-Project check is read at open time, like iOS's
        // uncached deferred element.
        onOptions = { anchor -> menu.open(anchor, optionsMenu()) },
        onNewSession = { router.presentNewSession(null) },
        modifier = modifier,
    )
}

/**
 * The toolbar row itself, stateless: previews, the gallery and the
 * goldens compose it directly with a fixed [visibility]. Fills its parent
 * (the 44-dp slot). Not composed at `visibility == 0` (a pushed folder):
 * nothing is drawn, hit-tested or announced, exactly as UIKit's empty
 * toolbar.
 *
 * The fade is applied `graphicsLayer { alpha = visibility() }` — a
 * draw-phase read, so the row's children never recompose per frame; taps
 * are blocked while `visibility < 1` (the items stay at full ink — 08
 * would ink a disabled button `tertiary` — so the group keeps bar ink and
 * the `+` sits behind a touch-consuming wrapper instead).
 *
 * @param optionsSource the ellipsis slot's interaction source, shared out
 *   so the gallery and the pressed golden can hold it pressed
 */
@Composable
internal fun SidebarToolbarContent(
    visibility: () -> Float,
    onSettings: () -> Unit,
    onOptions: (anchorInWindow: Rect) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    optionsSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    // Only the 0 / partial / 1 thresholds may recompose (the gate and the
    // group's enabled flag); the continuous fade is the graphicsLayer read.
    // Keyed by the lambda so a new capture (a new stack) is never stale.
    val vis by remember(visibility) { derivedStateOf { visibility() } }
    if (vis == 0f) return
    Row(
        modifier
            .fillMaxWidth()
            .height(SidebarToolbarDefaults.ItemSize)
            // Test-only fade readout; snapshot reads here are observed by
            // the semantics tree, so it tracks the transition per frame.
            // Before the insets: the tagged node is the full-width row (a
            // semantics node takes the bounds at its place in the chain).
            .semantics { this[SidebarToolbarAlphaKey] = visibility() }
            .testTag("sidebar-toolbar")
            // The leading safe-drawing inset (a landscape display cutout)
            // inside the slot; the trailing margin is plain padding.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
            .padding(horizontal = SidebarToolbarDefaults.SideMargin)
            .graphicsLayer { alpha = visibility() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassButtonGroup(
            items = listOf(
                GlassGroupItem(
                    icon = RobocoIcons.Gearshape,
                    contentDescription = stringResource(R.string.sidebar_settings),
                    testTag = "sidebar-settings",
                ) { onSettings() },
                GlassGroupItem(
                    icon = RobocoIcons.Ellipsis,
                    contentDescription = stringResource(R.string.sidebar_options),
                    testTag = "sidebar-options",
                    interactionSource = optionsSource,
                ) { onOptions(it) },
            ),
            enabled = vis == 1f,
        )
        Spacer(Modifier.weight(1f))
        // The + keeps `enabled = true` (08 would ink a disabled glyph
        // tertiary; UIKit keeps the item's look during the fade), so taps
        // are blocked by this wrapper instead: while fading it consumes
        // downs on the Initial pass before the button's clickable can see
        // them — inside the 44 dp circle only, never in the flexible gap
        // (the Row itself has no pointer input, so gap touches reach the
        // list underneath).
        Box(
            Modifier.pointerInput(vis < 1f) {
                if (vis < 1f) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial).consume()
                    }
                }
            },
        ) {
            GlassCircleButton(
                icon = RobocoIcons.Plus,
                contentDescription = stringResource(R.string.sidebar_new_session),
                onClick = onNewSession,
                modifier = Modifier.testTag("new-session"),
                // The iOS 26 "prominent" item: the accent fill with a
                // white glyph (opaque — 08's filled variant, no blur, no
                // rim, no shadow; the ink defaults to white when filled).
                filled = RobocoTheme.palette.accent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (step 6): the 360 × 200 dp stage on ticket 22's striped busy
// backdrop — the toolbar's sibling, never inside the hazeSource it blurs —
// with 105's slot geometry (bottom 24, height 44) mimicked.
// ---------------------------------------------------------------------------

/** The preview's slot: 105's placement (full width × 44, bottom 24). */
@Composable
private fun BoxScope.SidebarToolbarPreviewSlot() {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .height(SidebarToolbarDefaults.ItemSize),
    ) {
        SidebarToolbarContent(
            visibility = { 1f },
            onSettings = {},
            onOptions = {},
            onNewSession = {},
        )
    }
}

@Preview(name = "sidebar toolbar light", widthDp = 360, heightDp = 200)
@Composable
private fun SidebarToolbarLightPreview() {
    RobocoTheme(Appearance.Light) {
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            GlassStage(
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(haze),
            ) {
                SidebarToolbarPreviewSlot()
            }
        }
    }
}

@Preview(name = "sidebar toolbar dark", widthDp = 360, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SidebarToolbarDarkPreview() {
    RobocoTheme(Appearance.Dark) {
        val haze = rememberHazeState()
        CompositionLocalProvider(LocalHazeState provides haze) {
            GlassStage(
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(haze),
            ) {
                SidebarToolbarPreviewSlot()
            }
        }
    }
}

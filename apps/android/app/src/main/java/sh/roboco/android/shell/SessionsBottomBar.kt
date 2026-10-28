package sh.roboco.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.glass.GlassTier
import sh.roboco.android.design.glass.LocalGlassTier

/**
 * The phone's one-row bottom bar (wave-10 37, upstream #967 + #971's
 * iPhone `MainTabController`): a single row of glass controls on the
 * Sessions list instead of the tab bar — Settings, Search (the field,
 * filtering the list in place) and a prominent New session. Settings
 * pushes over the list; the bar belongs to the front page alone (the
 * Sessions stack shows bottom chrome on its root only,
 * [NavStack.chromeOnRootOnly]) and steps aside for sessions, folders and
 * Settings. Upstream's voice item is dropped with voice (ADR 0009).
 *
 * The row is 48 dp tall: two 48 dp circles around the field
 * ([SearchTabField] at its settled search shape, the morph's `p = 1`).
 */
object SessionsBottomBarMetrics {
    /** The row's height and the circles' side. */
    const val Height = 48f

    /** The gap between the controls (each control its own glass shape). */
    const val Gap = 8f

    /** The row's side margins inside the safe area. */
    const val SideMargin = 16f
}

/**
 * The search state the one-row bar filters the Sessions list with, or
 * `null` outside it (the tab shell's Search tab keeps its own screen).
 * [ShellRouteContent] passes its query to the Sessions root.
 */
val LocalBottomBarSearch = staticCompositionLocalOf<SearchTabState?> { null }

/**
 * The bar itself. [onSettings] pushes Settings over the list (the
 * router's `showSettings`), [onNewSession] presents the New Session sheet.
 * The field is [SearchTabField] over [searchState]: typing filters the
 * list in place, its clear button empties the query. [onFrontPage] is
 * `false` while a page is pushed over the list: the bar stays composed
 * (hidden), and a focused field lets go of its focus and the IME.
 */
@Composable
fun SessionsBottomBar(
    searchState: SearchTabState,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    onFrontPage: Boolean = true,
) {
    val palette = RobocoTheme.palette
    BoxWithConstraints(modifier.fillMaxWidth().height(SessionsBottomBarMetrics.Height.dp)) {
        val circle = SessionsBottomBarMetrics.Height
        val gap = SessionsBottomBarMetrics.Gap
        val fieldWidth = (maxWidth.value - 2f * circle - 2f * gap).coerceAtLeast(circle)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(
                icon = RobocoIcons.Gearshape,
                contentDescription = "Settings",
                onClick = onSettings,
                modifier = Modifier.testTag("bottom-settings"),
                size = circle.dp,
                iconPt = 19f,
            )
            // The capsule draws on the non-blur glass tier: a live-blurred
            // glass capsule in this always-on bar cost the CI emulator
            // (software GPU) its device (PR #38's bisect); the circles
            // keep the blur.
            CompositionLocalProvider(LocalGlassTier provides GlassTier.Fallback) {
                SearchTabField(
                    state = searchState,
                    progress = { 1f },
                    // The field's final rect is `rowWidth − (circle + gap)`
                    // wide (the morph's return circle beside it): hand it the
                    // width that makes that rect this slot.
                    rowWidth = fieldWidth + circle + gap,
                    active = true,
                    onSearchTabTap = {},
                    modifier = Modifier
                        .weight(1f)
                        .height(circle.dp),
                    held = onFrontPage,
                )
            }
            GlassCircleButton(
                icon = RobocoIcons.Plus,
                contentDescription = "New session",
                onClick = onNewSession,
                modifier = Modifier.testTag("new-session"),
                size = circle.dp,
                iconPt = 19f,
                weight = SymbolWeight.Semibold,
                filled = palette.text,
                ink = palette.background,
            )
        }
    }
}

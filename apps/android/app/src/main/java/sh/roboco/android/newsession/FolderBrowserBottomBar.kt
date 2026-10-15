package sh.roboco.android.newsession

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.ProminentGlassButton
import sh.roboco.android.design.uiStyle

/**
 * The folder browser's bottom stack geometry (dp), the port of
 * `pr685:apps/ios/Zeron/Threads/NewProjectViewController.swift:63-93`
 * (the UIStackView metrics): 20 from the horizontal safe area, 12 above
 * the navigation-bar inset — **not** the IME; the alert is a separate
 * in-window layer (ticket 18) — 8 between children, every child the
 * full stack width.
 */
internal object FolderBottomBarTokens {

    /** `leading/trailing = safeArea ± 20`. */
    val SideInset: Dp = 20.dp

    /** `bottom = safeArea.bottom − 12`. */
    val BottomInset: Dp = 12.dp

    /** `stack.spacing = 8`. */
    val Spacing: Dp = 8.dp

    /** UIKit `.plain()` defaults (ticket 90's convention): 7 vertical, 12 horizontal. */
    val NewFolderVertical: Dp = 7.dp

    /** See [NewFolderVertical]. */
    val NewFolderHorizontal: Dp = 12.dp

    /** `imagePadding = 8` — the icon-to-title gap. */
    val NewFolderGap: Dp = 8.dp

    /** The SF Symbol point size (`folder.badge.plus` at 17). */
    const val NewFolderIconPt: Float = 17f

    /** The `.plain` highlight: content dims to this alpha while pressed, no ripple, no scale. */
    const val PressAlpha: Float = 0.5f
}

/**
 * The bottom of the New Project folder browser (android-ui-parity #111):
 * a vertical stack — the status label, the plain New Folder… button, the
 * full-width prominent Use capsule — overlaid on the folder list
 * (110's 160 dp bottom content inset keeps the last row reachable).
 *
 * The stack has **no background**: rows scroll underneath it, and the
 * column itself is not a scroll container and consumes no touches —
 * there is no `clickable`/`pointerInput` on it, so taps between the
 * children reach the list underneath, exactly like a `UIStackView`
 * outside its subviews. No motion of its own: status, title and enable
 * changes apply on the next frame (UIKit sets them instantly); the busy
 * spinner and press response are ticket 08's, the press dimming here.
 *
 * @param status 110's status string; `""` composes no row (iOS's empty
 * label is 0 tall, the rest of the stack just closes up)
 * @param useTitle `Use “<name>”` (U+201C/U+201D), `""` before the first
 * listing — an empty title still lays out one line, so the capsule
 * keeps its height
 * @param useEnabled `canAct || creatingProject`: the busy capsule keeps
 * the enabled look (ticket 08 ignores clicks while busy), the folder
 * create disables it outright
 * @param useBusy ticket 08's spinner + "In progress" state
 * @param newFolderEnabled `canAct`
 */
@Composable
fun FolderBrowserBottomBar(
    status: String,
    useTitle: String,
    useEnabled: Boolean,
    useBusy: Boolean,
    newFolderEnabled: Boolean,
    onUse: () -> Unit,
    onNewFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(
                start = FolderBottomBarTokens.SideInset,
                end = FolderBottomBarTokens.SideInset,
                bottom = FolderBottomBarTokens.BottomInset,
            )
            .testTag("folder-bottom-bar"),
        verticalArrangement = Arrangement.spacedBy(FolderBottomBarTokens.Spacing),
    ) {
        if (status.isNotEmpty()) {
            BasicText(
                status,
                Modifier
                    .fillMaxWidth()
                    .testTag("folder-status"),
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary)
                    .copy(textAlign = TextAlign.Center),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NewFolderButton(enabled = newFolderEnabled, onClick = onNewFolder)
        ProminentGlassButton(
            text = useTitle,
            onClick = onUse,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("use-folder"),
            busy = useBusy,
            enabled = useEnabled,
        )
    }
}

/**
 * The plain New Folder… button (`newFolderButton`, `:75-82`): a
 * full-width tap area whose centred content is the folder-plus glyph,
 * an 8 dp gap and the title — UIKit `.plain()`'s 7/12 padding, so
 * 7 + max(20.4 icon box, 17 pt line) + 7 ≈ 36 dp tall (iOS ≈ 35). The
 * icon and title dim to alpha 0.5 while pressed (the `.plain`
 * highlight — no ripple, no scale); disabled shows both in
 * `palette.tertiary` and takes no clicks.
 */
@Composable
private fun NewFolderButton(enabled: Boolean, onClick: () -> Unit) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    val ink = if (enabled) palette.text else palette.tertiary
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("new-folder")
            .padding(
                vertical = FolderBottomBarTokens.NewFolderVertical,
                horizontal = FolderBottomBarTokens.NewFolderHorizontal,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    alpha = if (pressed && enabled) FolderBottomBarTokens.PressAlpha else 1f
                }
                .testTag("new-folder-content"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decorative glyph: the button's own text is the label.
            Icon(
                RobocoIcons.FolderBadgePlus,
                tint = ink,
                sizePt = FolderBottomBarTokens.NewFolderIconPt,
            )
            Spacer(Modifier.width(FolderBottomBarTokens.NewFolderGap))
            BasicText(
                "New Folder…",
                style = uiStyle(Face.Sans, ts.fixed(17f), ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package sh.roboco.android.sidechats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassCircleButton
import sh.roboco.android.design.uiStyle
import sh.roboco.android.session.SessionHeaderDefaults

/** "Side chats and agents" (iOS `accessibilityLabel`), plus "N running" while anything runs. */
fun sideChatsButtonLabel(badge: SideChatsBadge): String =
    listOfNotNull("Side chats and agents", badge.a11yValue).joinToString(", ")

/**
 * The session header's side chats item (android-next #06, upstream #899's
 * `bubble.left.and.bubble.right` bar button): a 44 dp glass circle with the
 * two-bubble glyph and a badge on its top-trailing corner — `● N` on the
 * accent with white ink while subagents run, else a quiet count of
 * everything hanging off the chat on `secondary` with background ink (the
 * default red reads as unread). The session shows it only once the chat
 * has something ([SideChatsUi.badge] non-null).
 */
@Composable
fun SideChatsButton(badge: SideChatsBadge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val fill = if (badge.accent) palette.accent else palette.secondary
    val ink = if (badge.accent) Color.White else palette.background
    Box(modifier) {
        GlassCircleButton(
            icon = RobocoIcons.TabChat,
            contentDescription = sideChatsButtonLabel(badge),
            onClick = onClick,
            modifier = Modifier.testTag("side-chats"),
            size = SessionHeaderDefaults.ButtonSize,
            iconPt = SessionHeaderDefaults.IconPt,
            weight = SymbolWeight.Medium,
        )
        // The badge: decorative (the button's label carries the count), so
        // it never takes a touch or a TalkBack stop of its own.
        BasicText(
            badge.text,
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-2).dp)
                .background(fill, RoundedCornerShape(50))
                .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                .padding(horizontal = 4.5.dp, vertical = 1.dp)
                .testTag("side-chats-badge")
                .clearAndSetSemantics { },
            style = uiStyle(Face.SansSemibold, LocalTypeScale.current.fixed(11f), ink).copy(textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
        )
    }
}

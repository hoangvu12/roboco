package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.design.uiStyle

/**
 * The Limited banner card's tokens — the geometry of the desktop's
 * Limited status strip (orchestration v2) in dp.
 */
object LimitedBannerTokens {
    /** `Glass.surface(radius: 16)` — regular, non-interactive glass. */
    val CardRadius = 16.dp

    /** The card's inset from the slot's edges (the queue panel's 10 + 2). */
    val CardInset = 12.dp

    /** The column's padding inside the card. */
    val PadH = 12.dp
    val PadV = 8.dp

    /** The amber dot's size (the desktop's 6 px dot). */
    val DotSize = 6.dp

    /** The dot → label gap. */
    val DotGap = 8.dp

    /** The card's line spacing. */
    val LineGap = 6.dp

    /** One action pill's chrome. */
    val PillRadius = 8.dp
    val PillPadH = 8.dp
    val PillPadV = 3.dp
    val PillGap = 6.dp
}

/** One action pill's label. Pure. */
internal fun limitedActionLabel(action: LimitedAction): String = when (action) {
    LimitedAction.RESUME_AT_RESET -> "Resume at reset"
    LimitedAction.CANCEL_RESUME -> "Cancel"
    LimitedAction.RESUME -> "Resume"
    LimitedAction.SWITCH_ACCOUNT -> "Resume on another account"
    LimitedAction.RETRY_ACCOUNTS -> "Retry accounts"
}

/**
 * The Limited banner card (the failover port's UI) — the mobile arm of
 * the desktop's Limited status strip: one amber glass card above the
 * status pill row while the chat's status row reads Limited (errored
 * with a usage-limit record). Line one is the dot + label ("5-hour
 * limit · resets in 45m", or the armed continuation's "resumes in …");
 * line two the action pills ([limitedActions], the desktop's strip
 * order); the notice line rides last when a switch answered.
 *
 * The **Switch** pill opens the account picker as a glass menu anchored
 * to the pill ([switchMenu] builds it at tap time, never during
 * composition — the #609 rule; [onSwitchMenuOpen] re-ranks first: usage
 * meters move as turns run). The other pills dispatch [onAction]; every
 * pill consumes its tap but no-ops while a switch is in flight.
 *
 * Placement is [SessionBottomStack]'s Limited slot: this card takes the
 * slot scope's `glassVisible` / `animateGlass` flags as parameters and
 * never derives glass visibility itself. The card renders a live
 * [LimitedChrome] only — the collapse's last frame is the stack's
 * business (the pill keeps its own last-shown rule).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitedBannerCard(
    limited: LimitedChrome,
    onAction: (LimitedAction) -> Unit,
    switchMenu: () -> Menu?,
    modifier: Modifier = Modifier,
    onSwitchMenuOpen: () -> Unit = {},
    glassVisible: Boolean = true,
    animateGlass: Boolean = false,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val warning = palette.warning
    val menuState = rememberGlassMenuState()
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val now = nowMs()

    Box(modifier.fillMaxWidth().padding(horizontal = LimitedBannerTokens.CardInset)) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("limited-banner"),
            shape = Glass.shape(LimitedBannerTokens.CardRadius),
            visible = glassVisible,
            animateVisibility = animateGlass,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = LimitedBannerTokens.PadH,
                        vertical = LimitedBannerTokens.PadV,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(LimitedBannerTokens.DotSize)
                            .background(warning, CircleShape),
                    )
                    Spacer(Modifier.width(LimitedBannerTokens.DotGap))
                    BasicText(
                        text = limitedBannerLabel(limited, now),
                        style = uiStyle(Face.Sans, typeScale.fixed(13f), warning),
                        modifier = Modifier.testTag("limited-banner-label"),
                    )
                }
                Spacer(Modifier.height(LimitedBannerTokens.LineGap))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(LimitedBannerTokens.PillGap),
                ) {
                    for (action in limitedActions(limited, now)) {
                        LimitedActionPill(
                            action = action,
                            enabled = !limited.switching,
                            tint = warning,
                            onClick = {
                                if (action == LimitedAction.SWITCH_ACCOUNT) {
                                    onSwitchMenuOpen()
                                    switchMenu()?.let { menuState.open(anchor, it) }
                                } else {
                                    onAction(action)
                                }
                            },
                            modifier = if (action == LimitedAction.SWITCH_ACCOUNT) {
                                Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                limited.notice?.let { notice ->
                    Spacer(Modifier.height(LimitedBannerTokens.LineGap))
                    BasicText(
                        text = notice,
                        style = uiStyle(Face.Sans, typeScale.fixed(12.5f), palette.tertiary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("limited-banner-notice"),
                    )
                }
            }
        }
    }
}

/**
 * One action pill: a bordered capsule in the warning tone (the desktop's
 * strip pills), no ripple, no press feedback — a tap dispatches, a
 * disabled pill consumes the tap as a no-op (the #777-era rule: the tap
 * is still consumed at the hit-test level).
 */
@Composable
private fun LimitedActionPill(
    action: LimitedAction,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeScale = LocalTypeScale.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .border(
                1.dp,
                tint.copy(alpha = 0.45f),
                RoundedCornerShape(LimitedBannerTokens.PillRadius),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = true,
            ) { if (enabled) onClick() }
            .padding(
                horizontal = LimitedBannerTokens.PillPadH,
                vertical = LimitedBannerTokens.PillPadV,
            ),
    ) {
        BasicText(
            text = limitedActionLabel(action),
            style = uiStyle(
                Face.Sans,
                typeScale.fixed(12f),
                if (enabled) tint.copy(alpha = 0.9f) else tint.copy(alpha = 0.4f),
            ),
            modifier = Modifier.testTag("limited-action-${action.name.lowercase()}"),
        )
    }
}

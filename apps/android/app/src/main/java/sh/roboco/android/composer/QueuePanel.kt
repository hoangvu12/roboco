package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.FadingText
import sh.roboco.android.design.GlassStage
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.uiStyle
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.ProvideHazeState
import sh.roboco.android.design.glass.rememberGlassMenuState
import sh.roboco.android.session.SessionChrome

/**
 * The queue panel's tokens (android-ui-parity #82) — the geometry of
 * `ios:Composer/QuestionPanel.swift:217-329` in dp. The send / more
 * button widths (48 / 40) and the clock slot (13) are estimates of
 * UIKit's plain-button content insets plus the SF Symbol widths frozen
 * here per the ticket's Comments; the layout test pins them.
 */
object QueuePanelTokens {
    /** `Glass.surface(radius: 20)` — regular, non-interactive glass. */
    val CardRadius = 20.dp

    /** 10 leading / trailing from the slot's edges; top and bottom flush. */
    val CardInset = 10.dp

    /** The column's vertical padding inside the card (spacing 0). */
    val PadV = 4.dp

    val RowHeight = 40.dp
    const val MaxRows = 3
    val FooterHeight = 24.dp

    /** The clock glyph's leading edge (x = 14 in the row). */
    val ClockStart = 14.dp

    /** The 13 dp slot the 12 pt clock glyph centres in (centre x = 20.5). */
    val ClockSlot = 13.dp

    /** The gap after the clock slot — the label starts at 14 + 13 + 8 = 35. */
    val ClockGap = 8.dp

    /** The thumbnail slot, only when a ref exists (never in production). */
    val ThumbSize = 26.dp

    /** Circular 6 dp arcs, not continuous (the row thumbnail). */
    val ThumbRadius = 6.dp

    /** The gap between the thumbnail and the label. */
    val ThumbGap = 8.dp

    /** The label's trailing edge sits this far before the send button. */
    val LabelEndGap = 4.dp

    /** Glyph 24 + UIKit plain insets 12 + 12. */
    val SendWidth = 48.dp

    /** SF ellipsis 14 pt ≈ 16.5 + 12 + 12. */
    val MoreWidth = 40.dp

    /** The more button's end edge, 4 from the row's end. */
    val MoreEnd = 4.dp

    /** UIKit `.plain` dims its foreground while pressed (the ticket 14 convention). */
    const val PressedAlpha = 0.5f

    /** Roboco read-only look (79): both button glyphs at 0.4. */
    const val DisabledAlpha = 0.4f
}

/**
 * Messages waiting for the current turn to end, stacked above the
 * composer (android-ui-parity #82) — the port of `QueuePanel`
 * (`ios:Composer/QuestionPanel.swift:217-329`): one glass card, at most
 * 3 rows, then a `+N more queued` footer. Each row has a clock glyph, the
 * message text faded at its tail (never "…"), a send-now button (↑ in a
 * filled circle) and a ⋯ button whose **tap** opens ticket 15's glass
 * menu (Edit / Move Up / Move Down / Remove).
 *
 * Placement is ticket 74's bottom stack: the panel composes inside 74's
 * queue slot and takes that slot's `BottomStackSlotScope.glassVisible` /
 * `animateGlass` flags straight as parameters — it does **not** derive
 * glass visibility itself, and never passes `materializeOnEnter` (a
 * reopened session with a queue shows the card at once, and an empty
 * queue that grows in animates). 74's stack is in-tree; its gallery
 * section still draws its own `QueuePanelStandIn` until the session
 * screen (75/76) composes this panel for real. The wiring once it does:
 *
 * ```kotlin
 * // ticket 74's SessionBottomStack(…, queue = { the BottomStackSlotScope →
 * QueuePanel(chrome.queue, glassVisible = glassVisible, animateGlass = animateGlass,
 *     writable = chrome.canWrite, onAction = { id, a ->
 *         if (a == QueueAction.Edit) edit.beginEdit(id)      // ticket 83
 *         else queueActions.perform(id, a)                   // application scope
 *     })
 * // }, …)
 * ```
 *
 * `chrome.error` is not shown anywhere (decision COMPOSER-D2, strict iOS
 * parity): no toast, no inline line — a failed or refused action is
 * silent, the row moves or stays according to the engine's echo.
 *
 * - **No inner height animation** (`animateContentSize` is forbidden
 *   here): the measured height snaps — 48 / 88 / 128 / 152 dp for 1 / 2 /
 *   3 / 4-or-more items — and 74's slot-extent reflow spring does the
 *   motion.
 * - `key(items)` mirrors iOS `configure`'s full rebuild: any list change
 *   (any field of any item) recreates the rows, so a ⋯ menu open on a
 *   changed row goes away with it.
 * - No Material anywhere: `BasicText`, ticket 10's [FadingText], ticket
 *   06 [Icon], ticket 07 [GlassSurface], ticket 15's glass menu.
 *
 * @param items the mapped queue ([SessionChrome.queue], ticket 51's
 *   chrome filled by [QueueChromeFiller]); delivery order, capped at
 *   [QueuePanelTokens.MaxRows] rows by the panel
 * @param glassVisible 74's queue-slot glass target (`queueCount > 0`);
 *   an open question hides the slot but does not dematerialize the glass
 * @param animateGlass false only during the container's first
 *   composition (74), so a first render with a queue is already
 *   materialized
 * @param onAction every row action; the screen routes [QueueAction.Edit]
 *   to ticket 83 and the rest to [QueueActions]
 * @param writable `chrome.canWrite` (79): send and ⋯ draw at 0.4 alpha
 *   and are disabled; rows and footer are unchanged
 * @param thumbnail parity slot for the row thumbnail (26 × 26, 6 dp
 *   circular corners). Production passes null — `thumbnailRef` is always
 *   null for live sessions; the code path exists for parity and the
 *   gallery
 */
@Composable
fun QueuePanel(
    items: List<SessionChrome.QueuedItem>,
    glassVisible: Boolean,
    animateGlass: Boolean,
    onAction: (id: String, action: QueueAction) -> Unit,
    modifier: Modifier = Modifier,
    writable: Boolean = true,
    thumbnail: (@Composable (ref: String) -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = QueuePanelTokens.CardInset)) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("queue-panel")
                .semantics { isTraversalGroup = true },
            shape = Glass.shape(QueuePanelTokens.CardRadius),
            visible = glassVisible,
            animateVisibility = animateGlass,
        ) {
            key(items) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = QueuePanelTokens.PadV),
                ) {
                    items.take(QueuePanelTokens.MaxRows).forEachIndexed { i, item ->
                        QueueRow(
                            item = item,
                            index = i,
                            count = items.size,
                            writable = writable,
                            onAction = onAction,
                            thumbnail = thumbnail,
                        )
                    }
                    queueFooterText(items.size)?.let { QueueFooter(it) }
                }
            }
        }
    }
}

/**
 * One queued message row (`QuestionPanel.swift:270-329`): height 40,
 * everything vertically centred — clock glyph, (thumbnail), the fading
 * label, then the send and more buttons flush against the row's end.
 * The label takes the remaining width (`weight(1f)`): a long label
 * shrinks and fades instead of pushing the buttons out (the iOS
 * regression in the source comment).
 */
@Composable
private fun QueueRow(
    item: SessionChrome.QueuedItem,
    index: Int,
    count: Int,
    writable: Boolean,
    onAction: (id: String, action: QueueAction) -> Unit,
    thumbnail: (@Composable (ref: String) -> Unit)?,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val menuState = rememberGlassMenuState()
    var anchor by remember { mutableStateOf(Rect.Zero) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(QueuePanelTokens.RowHeight)
            .testTag("queue-row-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(QueuePanelTokens.ClockStart))
        Box(
            Modifier.width(QueuePanelTokens.ClockSlot),
            contentAlignment = Alignment.Center,
        ) {
            // 12 pt medium clock in a 14.4 dp box, drawn unclipped inside
            // the 13 dp slot: the glyph's centre sits at x = 20.5.
            Icon(
                RobocoIcons.Clock,
                tint = palette.tertiary,
                sizePt = 12f,
                weight = SymbolWeight.Medium,
                modifier = Modifier.wrapContentSize(align = Alignment.Center, unbounded = true),
            )
        }
        Spacer(Modifier.width(QueuePanelTokens.ClockGap))
        if (item.thumbnailRef != null && thumbnail != null) {
            Box(
                Modifier
                    .size(QueuePanelTokens.ThumbSize)
                    .clip(RoundedCornerShape(QueuePanelTokens.ThumbRadius)),
            ) {
                thumbnail(item.thumbnailRef)
            }
            Spacer(Modifier.width(QueuePanelTokens.ThumbGap))
        }
        FadingText(
            text = queueRowText(item),
            style = uiStyle(Face.Sans, typeScale.fixed(15f), palette.text),
            modifier = Modifier
                .weight(1f)
                .testTag("queue-row-text-$index"),
        )
        Spacer(Modifier.width(QueuePanelTokens.LabelEndGap))
        QueueIconButton(
            width = QueuePanelTokens.SendWidth,
            cd = "Send",
            tag = "queue-send-$index",
            enabled = writable,
            onClick = { onAction(item.id, QueueAction.SendNow) },
        ) {
            Icon(
                RobocoIcons.ArrowUpCircleFill,
                tint = palette.text,
                sizePt = 20f,
                weight = SymbolWeight.Regular,
            )
        }
        QueueIconButton(
            width = QueuePanelTokens.MoreWidth,
            cd = "More queue actions",
            tag = "queue-more-$index",
            enabled = writable,
            // A tap opens the menu (UIKit `showsMenuAsPrimaryAction`).
            // The menu is built here, at tap time, never during
            // composition (ticket 15's rule). The anchor is the button's
            // own window bounds, recorded during placement.
            onClick = {
                menuState.open(
                    anchor,
                    queueRowMenu(index, count) { a -> onAction(item.id, a) },
                )
            },
            modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        ) {
            Icon(
                RobocoIcons.Ellipsis,
                tint = palette.secondary,
                sizePt = 14f,
                weight = SymbolWeight.Semibold,
            )
        }
        Spacer(Modifier.width(QueuePanelTokens.MoreEnd))
    }
}

/**
 * The `+N more queued` footer (`QuestionPanel.swift:257-264`): 24 dp
 * tall, full card width, Geist Medium 12.5 in `tertiary`, centred. Not
 * tappable.
 */
@Composable
private fun QueueFooter(text: String) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(QueuePanelTokens.FooterHeight)
            .testTag("queue-more-count"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            style = uiStyle(Face.SansMedium, typeScale.fixed(12.5f), palette.tertiary)
                .copy(textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

/**
 * The send and more buttons (`QuestionPanel.swift:282-291`): a fixed
 * width, full row height, the glyph centred, no ripple
 * (`indication = null`), the glyph dimming to
 * [QueuePanelTokens.PressedAlpha] while pressed (UIKit `.plain`) and
 * [QueuePanelTokens.DisabledAlpha] when not writable. The `pressed` read
 * lives inside `graphicsLayer` so a press never recomposes.
 */
@Composable
private fun QueueIconButton(
    width: Dp,
    cd: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .width(width)
            .fillMaxHeight()
            .testTag(tag)
            .semantics {
                role = Role.Button
                contentDescription = cd
                if (!enabled) disabled()
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .graphicsLayer {
                alpha = when {
                    !enabled -> QueuePanelTokens.DisabledAlpha
                    pressed -> QueuePanelTokens.PressedAlpha
                    else -> 1f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// The fixtures: previews, the debug gallery and the Roborazzi goldens
// ---------------------------------------------------------------------------

/**
 * The ticket's queue fixtures (F1 / F2L / F3 / F5 / FG). Shared by the
 * previews, the debug gallery section and the screenshot tests so the
 * three never diverge — the same role `QuestionCardFixtures` plays for
 * ticket 90.
 */
internal object QueuePanelFixtures {

    /**
     * The iOS UI test's long second row
     * (`iosui:SessionFlowTests.swift:113`) — wide enough to overflow the
     * 227 dp label slot on a 378 dp stack, so the 24 dp tail fade shows.
     */
    const val LongRowText =
        "And link the docs, with a much longer follow-up line that has to wrap or fade somewhere."

    val F1 = listOf(item("q1", "Then add a TL;DR."))

    val F2L = listOf(
        item("q1", "Then add a TL;DR."),
        item("q2", LongRowText),
    )

    val F3 = F2L + item("q3", "Run the tests")

    val F5 = F3 + listOf(
        item("q4", "Bump the version"),
        item("q5", "Write release notes"),
    )

    /** Gate labels on F5's texts: three rows plus a `+1 more queued` footer. */
    val FG = listOf(
        item("q1", "Then add a TL;DR.", "Editing"),
        item("q2", LongRowText, "Being edited"),
        item("q3", "Run the tests", "Needs review"),
        item("q4", "Bump the version", "Updating"),
    )

    private fun item(id: String, text: String, gate: String? = null) =
        SessionChrome.QueuedItem(id = id, text = text, gate = gate, thumbnailRef = null)
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/** The preview harness: F5 over the striped backdrop in a 378 dp slot. */
@Composable
private fun QueuePanelPreviewBody(appearance: Appearance) {
    RobocoTheme(appearance) {
        val hazeState = rememberHazeState()
        GlassMenuHost(hazeState = hazeState) {
            GlassStage(
                modifier = Modifier.width(378.dp),
                height = 200.dp,
                backdropModifier = Modifier.hazeSource(hazeState),
            ) {
                ProvideHazeState(hazeState) {
                    QueuePanel(
                        items = QueuePanelFixtures.F5,
                        glassVisible = true,
                        animateGlass = false,
                        onAction = { _, _ -> },
                    )
                }
            }
        }
    }
}

@Preview(name = "Queue light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun QueuePanelLightPreview() {
    QueuePanelPreviewBody(Appearance.Light)
}

@Preview(name = "Queue dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun QueuePanelDarkPreview() {
    QueuePanelPreviewBody(Appearance.Dark)
}

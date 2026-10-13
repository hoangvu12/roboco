package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.ProminentGlassButton
import sh.roboco.android.design.glass.ProminentGlassButtonDefaults
import sh.roboco.android.design.rememberSmoothRoundedShape
import sh.roboco.android.design.uiStyle

/**
 * The question panel card (android-ui-parity #90): the port of iOS
 * `QuestionPanel`'s static half (`ios:Composer/QuestionPanel.swift:6-215`)
 * — one Liquid Glass card (radius 26) showing **one question per page**:
 * a one-line header, the question, large option rows, the "Other…" field
 * or a 140 dp editor, and a Back + Next/Submit row. Replaces the composer
 * while the agent asks something; ticket 74 places it, ticket 91 owns all
 * state (picks, custom text, paging, submit) and drives it through
 * [QuestionPageModel] + [QuestionPageActions].
 *
 * Everything here is stateless: no `remember` of picks or text, no page
 * state — the model is the single source, so a pick re-renders instantly
 * with no colour animation, exactly like UIKit's `render()` rebuild.
 *
 * Layout (all dp, 1:1 from the Swift): the card fills the panel width with
 * no extra inset (unlike the queue panel's 10); the column is inset
 * 16/16/16/12; gaps are **explicit spacers** — 10 after the header, 14
 * after the question, 10 after the options column, 14 after the Other
 * field but only 10 after the editor (UIStackView drops a hidden view's
 * custom spacing), 6 between option rows. The empty options column keeps
 * both its neighbouring gaps (14 + 10) — reproduced deliberately.
 *
 * No Material, no ripple (`indication = null` everywhere), no haptics
 * (ticket 91 fires them inside the callbacks).
 */

/** `"\(i + 1).circle"` exists in SF Symbols only for 0…50: the 51st option row shows no icon. */
private const val MaxNumberedOption = 50

// ---------------------------------------------------------------------------
// QuestionCardGlass + QuestionCard
// ---------------------------------------------------------------------------

/**
 * The glass card alone (the `Glass.surface(radius: 26)` of
 * `QuestionPanel.swift:10,80-83`), fading in/out as one material with its
 * content (ticket 07's shared animation — never a second fade here).
 * Ticket 91 calls this as
 * `QuestionCardGlass(visible, animate, modifier) { AnimatedContent(…) { QuestionPageContent(…) } }`,
 * so the 0.2 s page cross-fade runs inside the glass. Not interactive as a
 * surface: the option rows and buttons own their own click areas.
 */
@Composable
fun QuestionCardGlass(
    visible: Boolean = true,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
    materializeOnEnter: Boolean = false,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(QuestionCardTags.CARD),
        shape = rememberSmoothRoundedShape(26.dp),
        interactive = false,
        tint = null,
        visible = visible,
        animateVisibility = animate,
        materializeOnEnter = materializeOnEnter,
    ) {
        content()
    }
}

/**
 * Convenience: the glass card plus one page, no transition — what previews,
 * the gallery and the tests render. Production (ticket 91) goes through
 * [QuestionCardGlass] + `AnimatedContent` instead.
 */
@Composable
fun QuestionCard(
    model: QuestionPageModel,
    actions: QuestionPageActions,
    modifier: Modifier = Modifier,
    glassVisible: Boolean = true,
    animateGlass: Boolean = true,
) {
    QuestionCardGlass(visible = glassVisible, animate = animateGlass, modifier = modifier) {
        QuestionPageContent(model, actions)
    }
}

// ---------------------------------------------------------------------------
// QuestionPageContent — the padded column
// ---------------------------------------------------------------------------

/**
 * The page's column, padding 16/16/16/12 included
 * (`QuestionPanel.swift:72-87`): header, 10, question, 14, the options
 * column (kept even when empty, so both gaps stay), 10, the Other field or
 * the editor, 14 or 10, the buttons row.
 *
 * [otherFocusRequester] / [onOtherFocusChanged] let ticket 91 keep the
 * keyboard on the Other field across an IME-Done page move (91 Steps §6
 * "Refocus after Done"); both are no-ops by default and this ticket never
 * requests focus itself.
 */
@Composable
fun QuestionPageContent(
    model: QuestionPageModel,
    actions: QuestionPageActions,
    modifier: Modifier = Modifier,
    otherFocusRequester: FocusRequester? = null,
    onOtherFocusChanged: (Boolean) -> Unit = {},
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        // One line, tail-… (UILabel numberOfLines = 1).
        BasicText(
            model.headerText,
            style = uiStyle(Face.SansMedium, typeScale.fixed(12.5f), palette.secondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        // The question wraps without limit (numberOfLines = 0).
        BasicText(model.question, style = uiStyle(Face.SansSemibold, typeScale.fixed(17f), palette.text))
        Spacer(Modifier.height(14.dp))
        // The options column: 6 dp apart, kept when empty (0 height, both
        // neighbouring gaps still applied).
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            model.options.forEachIndexed { i, option ->
                QuestionOptionRow(
                    index = i,
                    option = option,
                    picked = option in model.picked,
                    multiSelect = model.multiSelect,
                    onClick = { actions.onPick(option) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (model.multiline) {
            QuestionEditor(model, actions)
        } else {
            QuestionOtherField(
                model = model,
                actions = actions,
                otherFocusRequester = otherFocusRequester,
                onOtherFocusChanged = onOtherFocusChanged,
            )
        }
        // The 14 after `other` is custom spacing, which UIStackView drops
        // together with the hidden view — the editor gets the default 10.
        Spacer(Modifier.height(if (model.multiline) 10.dp else 14.dp))
        QuestionButtonsRow(model, actions)
    }
}

// ---------------------------------------------------------------------------
// The option rows
// ---------------------------------------------------------------------------

/**
 * One option row (`QuestionPanel.swift:118-135`): full-width, continuous
 * 14 dp corners, chip @0.55 (accent @0.12 picked), 11/12/11/12 padding, a
 * number circle (or check) at 17 pt Regular, 10 dp gap, then the title in
 * Geist Medium 16 which wraps and takes the remaining width. Picked is
 * decided **by label**, switches instantly (no colour animation), and
 * while pressed the icon and title dim to alpha 0.5 with the fill
 * unchanged — UIKit's `.plain` highlight, no ripple, no scale.
 *
 * Semantics: one merged button whose label is the option title (the icon
 * is decorative) plus `selected` — Android-only; VoiceOver on iOS only
 * notices the icon change.
 */
@Composable
private fun QuestionOptionRow(
    index: Int,
    option: String,
    picked: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (picked) palette.accent else palette.text
    val icon: RobocoIcon? = when {
        picked && multiSelect -> RobocoIcons.CheckmarkSquareFill
        picked -> RobocoIcons.CheckmarkCircleFill
        index + 1 <= MaxNumberedOption -> RobocoIcon.Number(index + 1)
        else -> null // 51st+ option: no icon, no 10 dp gap (:120)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(rememberSmoothRoundedShape(14.dp))
            .background(if (picked) palette.accent.copy(alpha = 0.12f) else palette.chip.copy(alpha = 0.55f))
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { selected = picked }
            .testTag(QuestionCardTags.option(index))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dimmed half: only the icon and the title, never the fill.
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (pressed) 0.5f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    tint = ink,
                    sizePt = 17f,
                    weight = SymbolWeight.Regular,
                    contentDescription = null,
                )
                Spacer(Modifier.width(10.dp))
            }
            BasicText(
                option,
                Modifier.weight(1f),
                style = uiStyle(Face.SansMedium, typeScale.fixed(16f), ink)
                    .copy(textAlign = TextAlign.Start),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The Other field and the editor
// ---------------------------------------------------------------------------

/**
 * The "Other…" single-line field (`QuestionPanel.swift:34-45`): 44 dp tall,
 * full width, circular 12 dp corners, chip @0.6 fill, Geist Regular 16,
 * text starting 12 dp in with no trailing padding, accent caret, IME Done.
 * [QuestionPageActions.onOtherDone] fires on Done and must NOT clear focus
 * or hide the keyboard (iOS returns `true` from `textFieldShouldReturn`
 * without resigning, `:190`) — ticket 91 pages forward with the keyboard
 * still up.
 *
 * The placeholder draws while the text is empty in `tertiary` with no
 * semantics of its own; the field node carries "Other…" as its
 * `contentDescription` instead (iOS reads the placeholder).
 */
@Composable
private fun QuestionOtherField(
    model: QuestionPageModel,
    actions: QuestionPageActions,
    otherFocusRequester: FocusRequester?,
    onOtherFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicTextField(
        value = model.customText,
        onValueChange = actions.onCustomChange,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag(QuestionCardTags.OTHER)
            .then(
                if (otherFocusRequester != null) {
                    Modifier.focusRequester(otherFocusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged { onOtherFocusChanged(it.isFocused) }
            .semantics {
                if (model.customText.isEmpty()) {
                    contentDescription = QuestionCardText.OTHER_PLACEHOLDER
                }
            },
        textStyle = uiStyle(Face.Sans, typeScale.fixed(16f), palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { actions.onOtherDone() }),
        singleLine = true,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.chip.copy(alpha = 0.6f))
                    .padding(start = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (model.customText.isEmpty()) {
                    BasicText(
                        QuestionCardText.OTHER_PLACEHOLDER,
                        style = uiStyle(Face.Sans, typeScale.fixed(16f), palette.tertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                inner()
            }
        },
    )
}

/**
 * The free-text editor (`QuestionPanel.swift:47-54`): 140 dp tall, full
 * width, circular 12 dp corners, chip @0.6 fill, text inset 12 top/bottom
 * and 13 each side (the 8 pt `textContainerInset` + UITextView's 5 pt
 * line-fragment padding), accent caret, scrolls vertically on its own when
 * the text outgrows the box. No placeholder (iOS has none), no label.
 * Roboco engines never send `multiline` today; the path stays for newer
 * engines.
 */
@Composable
private fun QuestionEditor(model: QuestionPageModel, actions: QuestionPageActions, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicTextField(
        value = model.customText,
        onValueChange = actions.onCustomChange,
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .testTag(QuestionCardTags.EDITOR),
        textStyle = uiStyle(Face.Sans, typeScale.fixed(16f), palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            imeAction = ImeAction.Default,
        ),
        singleLine = false,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.chip.copy(alpha = 0.6f))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------------------
// The buttons row
// ---------------------------------------------------------------------------

/**
 * Back (leading, removed on page 0) · free space · Next/Submit (trailing)
 * (`QuestionPanel.swift:56-70,142-146`). The row is as tall as Next
 * (≈ 44.1 dp); Back fills that height with its text centred.
 */
@Composable
private fun QuestionButtonsRow(model: QuestionPageModel, actions: QuestionPageActions, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.showBack) {
            QuestionBackButton(onBack = actions.onBack, modifier = Modifier.fillMaxHeight())
        }
        Spacer(Modifier.weight(1f))
        ProminentGlassButton(
            text = model.nextTitle,
            onClick = actions.onNext,
            enabled = model.canContinue,
            contentPadding = ProminentGlassButtonDefaults.CompactPadding,
            modifier = Modifier.testTag(QuestionCardTags.NEXT),
        )
    }
}

/**
 * The plain Back button (`QuestionPanel.swift:56-59`): a borderless text
 * button — Geist Regular 17 in `secondary`, 12/7/12/7 insets (the UIKit
 * `.plain()` defaults), pressed title at alpha 0.5, no ripple, no fill.
 */
@Composable
private fun QuestionBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onBack,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            QuestionCardText.BACK,
            style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.secondary),
            maxLines = 1,
            modifier = Modifier.graphicsLayer { alpha = if (pressed) 0.5f else 1f },
        )
    }
}

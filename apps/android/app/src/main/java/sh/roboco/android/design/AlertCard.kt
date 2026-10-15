package sh.roboco.android.design

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import sh.roboco.android.core.Face
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.glass.LocalHazeState

/**
 * The stateless iOS 26 alert card (ticket 18) — the visual twin of
 * `UIAlertController(preferredStyle: .alert)` measured from the PR #685
 * capture (`ios-name-error.png`): a 320 dp glass card with continuous
 * 36 dp corners, a leading-aligned Geist SemiBold 17 title, an optional
 * Geist Regular 15 message at a 30 dp inset, an optional 48 dp capsule
 * field and grey 48 dp capsule buttons with an 8 dp gap. No hairlines
 * anywhere.
 *
 * The host ([AlertHost], Alert.kt) owns the field value, the enable and
 * press logic inputs and the animations; this card is pure layout + paint,
 * so previews, screenshots, the gallery and ticket 43's screenshot test can
 * render it standalone with `hazeState = null` (the fallback fill).
 */

/**
 * Alert-only tokens. The glass numbers (blur, fill, fallback, rim, shadow)
 * come from ticket 07's `Glass` object; `controlFill` is this ticket's one
 * addition, the capsule fill of the field and the buttons.
 */
internal object AlertTokens {

    /**
     * The capsule fill of the field and the buttons: `text` @0.16 light
     * (fitted: over the `#EAEAEB` card composite it gives the measured
     * `#CACACC`), `text` @0.12 dark (unmeasured — no dark capture; tune
     * against a dark iOS 26 capture when one exists).
     */
    private val ControlFillLight = rgba(0x27272C, 0.16)

    /** See [ControlFillLight]. */
    private val ControlFillDark = rgba(0xE8E8EA, 0.12)

    /** The capsule fill for the resolved appearance. */
    @Composable
    @ReadOnlyComposable
    internal fun controlFill(): Color =
        if (LocalIsDark.current) ControlFillDark else ControlFillLight
}

/**
 * The alert card. [onButton] receives the button's index in list order; the
 * host decides what it means. [fieldValue] drives the field and the
 * `requiresText` disabled states — the card renders them, both sides apply
 * [isEnabled] before acting. [hazeState] is provided to the card's
 * [GlassSurface] as `LocalHazeState` (it samples the host's window-level
 * source); `null` draws the fallback fill (previews, screenshots, ticket 43).
 */
@Composable
fun RobocoAlertCard(
    alert: RobocoAlert,
    fieldValue: TextFieldValue,
    onFieldValueChange: (TextFieldValue) -> Unit,
    onButton: (index: Int) -> Unit,
    onImeDone: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    fieldFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        GlassSurface(
            modifier = modifier
                .widthIn(max = AlertMetrics.CardWidthDp.dp)
                .fillMaxWidth()
                .testTag("alert")
                .semantics {
                    paneTitle = alert.title
                    isTraversalGroup = true
                },
            shape = rememberSmoothRoundedShape(AlertMetrics.CardRadiusDp.dp),
            interactive = false,
            tint = null,
            visible = true,
            animateVisibility = false,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // (a) The text + field part: scrolls when the card would
                //     exceed the placement area; the buttons stay pinned.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = AlertMetrics.TextInsetDp.dp,
                                end = AlertMetrics.TextInsetDp.dp,
                                top = AlertMetrics.TextTopDp.dp,
                            ),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        BasicText(
                            alert.title,
                            style = uiStyle(Face.SansSemibold, typeScale.scaled(17f), palette.text)
                                .copy(textAlign = TextAlign.Start),
                        )
                        alert.message?.let { message ->
                            Spacer(Modifier.height(AlertMetrics.TitleToMessageGapDp.dp))
                            BasicText(
                                message,
                                style = uiStyle(Face.Sans, typeScale.scaled(15f), palette.secondary)
                                    .copy(textAlign = TextAlign.Start),
                            )
                        }
                    }
                    alert.field?.let { field ->
                        Spacer(Modifier.height(AlertMetrics.TextToFieldGapDp.dp))
                        AlertTextField(
                            field = field,
                            value = fieldValue,
                            onValueChange = onFieldValueChange,
                            onImeDone = onImeDone,
                            focusRequester = fieldFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AlertMetrics.ControlInsetDp.dp),
                        )
                    }
                    alert.progress?.let { progress ->
                        Spacer(Modifier.height(AlertMetrics.TextToFieldGapDp.dp))
                        AlertProgressSection(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AlertMetrics.ControlInsetDp.dp),
                        )
                    }
                }
                // (b) The button area: pinned at the bottom, 20 dp below the
                //     field (or the last text line with no field), 16 from the
                //     card's sides and bottom.
                AlertButtons(
                    alert = alert,
                    fieldValue = fieldValue,
                    onButton = onButton,
                    modifier = Modifier.padding(
                        start = AlertMetrics.ControlInsetDp.dp,
                        end = AlertMetrics.ControlInsetDp.dp,
                        top = AlertMetrics.TextToButtonsGapDp.dp,
                        bottom = AlertMetrics.ControlInsetDp.dp,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The field
// ---------------------------------------------------------------------------

/**
 * The capsule text field: 48 dp min height, [AlertTokens.controlFill] fill,
 * 16 dp inner padding, Geist Regular 17, one line, accent caret
 * (`window.tintColor = Palette.accent`), IME Done routed to [onImeDone].
 * The placeholder is `tertiary`; it doubles as the field's
 * `contentDescription` when non-empty (PR #685's
 * `accessibilityIdentifier` on iOS).
 */
@Composable
private fun AlertTextField(
    field: AlertField,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onImeDone: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = uiStyle(Face.Sans, typeScale.scaled(17f), palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = field.keyboardOptions(),
        keyboardActions = KeyboardActions(onDone = { onImeDone() }),
        modifier = modifier
            .focusRequester(focusRequester)
            .testTag("alert-field")
            .semantics {
                if (field.placeholder.isNotEmpty()) contentDescription = field.placeholder
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(AlertTokens.controlFill(), CapsuleShape)
                    .heightIn(min = AlertMetrics.ControlMinHeightDp.dp)
                    .padding(horizontal = AlertMetrics.ControlInnerDp.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    BasicText(
                        field.placeholder,
                        style = uiStyle(Face.Sans, typeScale.scaled(17f), palette.tertiary),
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------------------
// The progress section (android-next#04)
// ---------------------------------------------------------------------------

/**
 * The update alert's progress (panel B of the approved mockup): a thin
 * 6 dp bar — the track is the capsule [AlertTokens.controlFill], the fill
 * `palette.accent` — with an optional Geist 13 label ("10.3 of 24.5 MB")
 * 6 dp under it. The bar tracks the fraction directly; the caller's
 * coalescing keeps the recomposition rate sane.
 */
@Composable
private fun AlertProgressSection(
    progress: AlertProgress,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    Column(modifier) {
        val fraction = progress.fraction.coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(AlertMetrics.ProgressHeightDp.dp)
                .clip(CapsuleShape)
                .background(AlertTokens.controlFill())
                .semantics { this[AlertProgressKey] = fraction },
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CapsuleShape)
                    .background(palette.accent),
            )
        }
        progress.label?.let { label ->
            Spacer(Modifier.height(AlertMetrics.ProgressLabelGapDp.dp))
            BasicText(
                label,
                style = uiStyle(Face.Sans, typeScale.fixed(13f), palette.secondary),
                modifier = Modifier.padding(horizontal = AlertMetrics.ControlInnerDp.dp / 2),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The buttons: a custom Layout measuring each title, then stacking decision
// ---------------------------------------------------------------------------

/**
 * The button area: one capsule cell per button (composed in list order),
 * measured and placed by a custom [Layout] in [displayOrder]. Two buttons
 * sit side by side, `(area − 8) / 2` each with the Cancel button leading;
 * three buttons, or two whose titles are wider than [sideBySideLabelMaxDp],
 * stack full width 8 dp apart with the Cancel button last; a single button
 * is one full-width capsule. No hairlines.
 */
@Composable
private fun AlertButtons(
    alert: RobocoAlert,
    fieldValue: TextFieldValue,
    onButton: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttons = alert.buttons
    val hasField = alert.field != null
    val typeScale = LocalTypeScale.current
    val titleStyle = uiStyle(Face.Sans, typeScale.scaled(17f))
    val measurer = rememberTextMeasurer()
    Layout(
        content = {
            buttons.forEachIndexed { index, button ->
                AlertButtonCell(
                    button = button,
                    index = index,
                    enabled = isEnabled(button, hasField, fieldValue.text),
                    onButton = onButton,
                    tag = alertButtonTag(index, buttons),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val buttonArea = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            // Unbounded (a wrap-content parent): the card's nominal width.
            AlertMetrics.CardWidthDp.dp.roundToPx() - 2 * AlertMetrics.ControlInsetDp.dp.roundToPx()
        }
        // Each title's single-line width in its own style (the disabled and
        // destructive colours share the face and size, so one style measures all).
        val cardWidthDp =
            (buttonArea + 2 * AlertMetrics.ControlInsetDp.dp.roundToPx()) / density
        val titleWidths = buttons.map { measurer.measure(it.title, titleStyle).size.width / density }
        val stacked = isStacked(buttons.size, titleWidths, cardWidthDp)
        val order = displayOrder(buttons, stacked)
        val gap = AlertMetrics.ControlGapDp.dp.roundToPx()
        val sideBySide = buttons.size == 2 && !stacked
        val cellWidth = if (sideBySide) (buttonArea - gap) / 2 else buttonArea
        val cellConstraints = Constraints(minWidth = cellWidth, maxWidth = cellWidth)

        val placeables = measurables.map { it.measure(cellConstraints) }
        val n = placeables.size
        val totalHeight = if (sideBySide) {
            placeables.maxOfOrNull { it.height } ?: 0
        } else {
            placeables.sumOf { it.height } + ((n - 1).coerceAtLeast(0)) * gap
        }
        layout(buttonArea, totalHeight) {
            if (sideBySide) {
                var x = 0
                for (i in order) {
                    placeables[i].placeRelative(x, 0)
                    x += placeables[i].width + gap
                }
            } else {
                var y = 0
                for (i in order) {
                    placeables[i].placeRelative(0, y)
                    y += placeables[i].height + gap
                }
            }
        }
    }
}

/**
 * One button capsule: 48 dp min height, [AlertTokens.controlFill] fill, the
 * pressed overlay `palette.controlFill` over it, no ripple
 * (`indication = null` — `RobocoTheme` already provides `NoIndication`,
 * this keeps it explicit). The title is Geist Regular 17, centred, one line
 * with an ellipsis: `text` for Cancel/Default (identical ink — the capture
 * shows no accent and no bold), `danger` for Destructive, `tertiary` while
 * disabled.
 *
 * A [preferred][AlertButton.preferred] button (android-next#04) is the
 * filled accent capsule with white Geist Medium text — the update alert's
 * "Update" — and darkens slightly while pressed, the accent twin of the
 * grey control's pressed overlay.
 */
@Composable
private fun AlertButtonCell(
    button: AlertButton,
    index: Int,
    enabled: Boolean,
    onButton: (Int) -> Unit,
    tag: String,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val titleColor = when {
        !enabled -> palette.tertiary
        button.role == AlertButtonRole.Destructive -> palette.danger
        button.preferred -> Color.White
        else -> palette.text
    }
    val fill = if (button.preferred) {
        // The accent capsule; pressed darkens it like the grey control's
        // heavier overlay.
        if (pressed) darken(palette.accent) else palette.accent
    } else {
        AlertTokens.controlFill()
    }
    Box(
        Modifier
            .heightIn(min = AlertMetrics.ControlMinHeightDp.dp)
            .clip(CapsuleShape)
            .background(fill)
            .then(if (!button.preferred && pressed) Modifier.background(palette.controlFill) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { onButton(index) }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            button.title,
            style = uiStyle(
                if (button.preferred) Face.SansMedium else Face.Sans,
                typeScale.scaled(17f),
                titleColor,
            )
                .copy(textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = AlertMetrics.ControlInnerDp.dp),
        )
    }
}

/** The preferred capsule's pressed fill: the accent at 88 % alpha over the card. */
private fun darken(accent: Color): Color = accent.copy(alpha = 0.88f)

// ---------------------------------------------------------------------------
// The striped backdrop of the previews and goldens (ticket 22's stand-in)
// ---------------------------------------------------------------------------

/**
 * The striped backdrop of the alert previews and goldens (ticket 22's
 * gallery backdrop does not exist yet): 44 dp rows of alternating
 * accent/text/project-tone stripes, so the 0.2 scrim and the glass read as
 * over real content.
 */
@Composable
internal fun AlertStripes(modifier: Modifier = Modifier) {
    val palette = RobocoTheme.palette
    Column(modifier.fillMaxWidth()) {
        for (i in 0 until 16) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(alertStripe(palette, i)),
            )
        }
    }
}

/** Stripes of `palette.accent` @0.35, `palette.text` @0.12 and `projectTone(i)`. */
private fun alertStripe(palette: Palette, i: Int): Color = when (i % 3) {
    0 -> if (palette.isDark) rgba(0x8B7CF6, 0.35) else rgba(0x5B43E8, 0.35)
    1 -> if (palette.isDark) rgba(0xE8E8EA, 0.12) else rgba(0x27272C, 0.12)
    else -> palette.projectTone(i)
}

// ---------------------------------------------------------------------------
// Previews (step 6): the capture's state — "New Folder" retry, light + dark
// ---------------------------------------------------------------------------

@Composable
private fun AlertCardPreviewContent() {
    val alert = RobocoAlert(
        title = "New Folder",
        message = "Couldn't create the folder. Enter a folder name without path separators.",
        field = AlertField(
            text = "../outside",
            placeholder = "Folder name",
            capitalization = KeyboardCapitalization.None,
            autocorrect = false,
        ),
        buttons = listOf(
            AlertButton("Cancel", role = AlertButtonRole.Cancel),
            AlertButton("Create", requiresText = true),
        ),
    )
    Box(Modifier.fillMaxSize()) {
        AlertStripes()
        Box(Modifier.fillMaxSize().background(rgba(0x000000, 0.2)))
        Box(
            Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RobocoAlertCard(
                alert = alert,
                fieldValue = TextFieldValue("../outside"),
                onFieldValueChange = {},
                onButton = {},
                onImeDone = {},
                hazeState = null,
            )
        }
    }
}

@Preview(name = "Alert light", widthDp = 393, heightDp = 852)
@Composable
private fun AlertLightPreview() {
    RobocoTheme(Appearance.Light) { AlertCardPreviewContent() }
}

@Preview(name = "Alert dark", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AlertDarkPreview() {
    RobocoTheme(Appearance.Dark) { AlertCardPreviewContent() }
}

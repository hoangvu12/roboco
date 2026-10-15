package sh.roboco.android.sheets

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.sheet.GlassSheet
import sh.roboco.android.design.sheet.GlassSheetState
import sh.roboco.android.design.sheet.SheetDetent
import sh.roboco.android.design.sheet.SheetHeader
import sh.roboco.android.design.sheet.SheetTrailing
import sh.roboco.android.design.sheet.rememberGlassSheetState
import sh.roboco.android.design.uiStyle

/**
 * iOS `SelectTextViewController(text:title:mono:)` (`ios:Transcript/
 * TranscriptListView.swift:658-690`) in a ticket 17 [GlassSheet] — the
 * read-only, selectable full text of a message (the transcript itself is
 * paint-only) or of a tool payload.
 *
 * - **Select Text** (`mono = false`, opened by the row menu's "Select
 *   Text", ticket 128): large detent only, no grabber, titled
 *   "Select Text", Geist Regular `bodyScaled(16.5)`, and the whole text
 *   selected on open — one frame after composition, like
 *   `DispatchQueue.main.async { tv.selectAll(nil) }` (`:689`), so the
 *   handles and the Copy toolbar appear with the sheet.
 * - **Tool detail** (`mono = true`, opened by the `Detail` widget): the
 *   `[.medium(), .large()]` detents with the grabber (`RowView.swift:244-248`),
 *   titled with the tool title, GeistMono Regular `fixed(14)`, no initial
 *   selection (the text can still be long-press selected).
 *
 * The body is a read-only foundation `BasicTextField` (state-based) inside
 * a `verticalScroll`, never a `SelectionContainer` (it cannot start with a
 * programmatic select-all) and never the field's own scrolling (17's
 * nested-scroll connection must see the scroll: medium → large expansion,
 * pull-down at the top moves the sheet). `readOnly` keeps the text
 * immutable and starts no input session, so no keyboard appears; the
 * transparent [cursorBrush] draws no caret (`isEditable = false`).
 *
 * [state] defaults to a fresh `rememberGlassSheetState` for the variant's
 * detents, exactly like [GlassSheet]; previews and screenshot tests pass
 * their own state so they can `snapTo` the detent (17's preview
 * convention). The size is read **once**, like `viewDidLoad` (`:679`) —
 * a font-scale change while the sheet is open does not restyle it — while
 * the colour is recomposed, like an iOS dynamic colour.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun SelectTextSheet(
    text: String,
    onDismiss: () -> Unit,
    title: String = SelectTextDefaults.Title,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    state: GlassSheetState = rememberGlassSheetState(
        if (mono) SelectTextDefaults.MonoDetents else SelectTextDefaults.SansDetents,
    ),
) {
    val typeScale = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val density = LocalDensity.current
    // viewDidLoad computes the font once (no adjustsFontForContentSizeCategory
    // anywhere in the iOS source), so the size survives a font-scale change
    // while the sheet is up.
    val size = remember {
        if (mono) typeScale.fixed(SelectTextDefaults.MonoSize) else typeScale.bodyScaled(SelectTextDefaults.SansSize)
    }
    // The colour is read live (a theme flip restyles the text, as iOS dynamic
    // colours do); uiStyle bakes it in, so only the size is remembered.
    val style = uiStyle(if (mono) Face.Mono else Face.Sans, size, palette.text)
    // The test observable for the ticket's ±0.01 font-size assertions: the
    // body's dp size as the current density renders it.
    val fontDp = remember(size, density) { with(density) { size.toDp().value } }
    // TextRange(0, text.length) uses UTF-16 offsets, the same as String.length.
    val field = rememberTextFieldState(
        initialText = text,
        initialSelection = if (mono) TextRange.Zero else TextRange(0, text.length),
    )
    val focus = remember { FocusRequester() }

    GlassSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = state,
        grabber = mono,
        title = title,
        header = { SheetHeader(title, trailing = SheetTrailing.Done) },
    ) { bottomInset ->
        // window.tintColor = Palette.accent (SceneDelegate.swift:15): the
        // handles; the wash is the accent at HighlightAlpha — iOS draws its
        // own and the value is the ticket's 0.4 placeholder, tuned in the
        // side-by-side.
        val selection = remember(palette) {
            TextSelectionColors(
                handleColor = palette.accent,
                backgroundColor = palette.accent.copy(alpha = SelectTextDefaults.HighlightAlpha),
            )
        }
        // The scroll container's bring-into-view rule (read by verticalScroll
        // through LocalBringIntoViewSpec). The default scrolls a focused
        // field that is taller than the viewport until its top edge meets
        // the viewport top — the select-all focus request on open scrolled
        // the 16 dp textContainerInset.top away (CI r23: first line at
        // header + 0, not header + 16). UITextView's selectAll does not
        // scroll, so a request whose rect is taller than the viewport and
        // already starts inside it scrolls nothing; small rects (the cursor,
        // the selection handles) keep the default behaviour. The field's own
        // subtree gets the outer spec back.
        val outerBringIntoView = LocalBringIntoViewSpec.current
        CompositionLocalProvider(
            LocalTextSelectionColors provides selection,
            LocalBringIntoViewSpec provides KeepVisibleTopSpec,
        ) {
            // tv.frame = view.bounds + widthTracksTextView: the scroll
            // container fills the content slot, word-wrapped in both
            // variants (no horizontal scrolling).
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides outerBringIntoView) {
                    BasicTextField(
                        state = field,
                        readOnly = true,
                        textStyle = style,
                        // isEditable = false: no caret is ever drawn.
                        cursorBrush = SolidColor(Color.Transparent),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        modifier = Modifier
                            .fillMaxWidth()
                            // textContainerInset (16/14/32/14) + the 5 dp
                            // NSTextContainer.lineFragmentPadding UIKit default,
                            // all inside the scroll so it scrolls away with the
                            // text; the bottom also clears 17's nav-bar inset so
                            // the last line scrolls above the home indicator.
                            .padding(
                                start = SelectTextDefaults.PaddingHorizontal,
                                end = SelectTextDefaults.PaddingHorizontal,
                                top = SelectTextDefaults.PaddingTop,
                                bottom = SelectTextDefaults.PaddingBottom + bottomInset,
                            )
                            .focusRequester(focus)
                            .semantics { this[SelectTextFontDpKey] = fontDp }
                            .testTag("select-text-field"),
                    )
                }
            }
        }
    }
    if (!mono) {
        val keyboard = LocalSoftwareKeyboardController.current
        // DispatchQueue.main.async { tv.selectAll(nil) } (:689): one frame
        // after composition — before the present animation ends — the field
        // takes focus with its whole-text selection, so the handles and the
        // Copy toolbar appear at once. read-only starts no input session;
        // the hide() guards against IMEs that still open for a focused
        // read-only field (recorded as this ticket's keyboard ADAPT).
        LaunchedEffect(focus) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.hide()
        }
    }
}

/**
 * The text scroll's bring-into-view rule: foundation's default
 * (`BringIntoViewSpec.defaultCalculateScrollDistance`), except that a rect
 * taller than the viewport whose leading edge is already inside it scrolls
 * nothing. The default would align that edge with the viewport top — the
 * whole-field request a focus gain sends (`FocusableNode`) — and scroll the
 * 16 dp top inset away when select-all focuses the field on open.
 */
private object KeepVisibleTopSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leadingEdge = offset
        val trailingEdge = offset + size
        return when {
            leadingEdge >= 0f && trailingEdge <= containerSize -> 0f
            leadingEdge < 0f && trailingEdge > containerSize -> 0f
            size > containerSize && leadingEdge >= 0f && leadingEdge < containerSize -> 0f
            abs(leadingEdge) < abs(trailingEdge - containerSize) -> leadingEdge
            else -> trailingEdge - containerSize
        }
    }
}

/**
 * Everything this sheet measures (ticket 127; the UIKit values of
 * `SelectTextViewController.viewDidLoad`). Starred values are tuned in the
 * side-by-side against an iOS 26 simulator and then frozen.
 */
object SelectTextDefaults {

    /** The nav-bar title (`init(title:)`'s default, verbatim). */
    const val Title = "Select Text"

    /** Geist Regular at `UIFontMetrics(.body).scaledValue(for: 16.5)`. */
    const val SansSize = 16.5f

    /** GeistMono Regular at `Fonts.ui(.mono, 14)` — fixed, no Dynamic Type. */
    const val MonoSize = 14f

    /** `textContainerInset.top`. */
    val PaddingTop = 16.dp

    /** `textContainerInset.left/right` (14) + `lineFragmentPadding` (5). */
    val PaddingHorizontal = 19.dp

    /** `textContainerInset.bottom`; GlassSheet adds its nav-bar inset. */
    val PaddingBottom = 32.dp

    /**
     * The selection wash alpha — iOS's is system-drawn and not in the
     * source. * 0.4 is Compose's own default ratio, the ticket's starting
     * point; the side-by-side tunes it (see the ticket's Comments).
     */
    const val HighlightAlpha = 0.4f

    /** The default page sheet: large only, no grabber (`:641-645`). */
    val SansDetents = listOf(SheetDetent.Large)

    /** The Detail widget's sheet: `[.medium(), .large()]`, grabber (`:244-248`). */
    val MonoDetents = listOf(SheetDetent.Medium, SheetDetent.Large)
}

/**
 * The body's font size in dp under the density the sheet rendered with —
 * the ticket's ±0.01 test observable ("the text style font size converted
 * back to dp"). Not read by TalkBack; like ticket 66's
 * `ImageTileAlphaKey`, it exists so tests assert the real composed value.
 */
val SelectTextFontDpKey = SemanticsPropertyKey<Float>("SelectTextFontDp")

// ---------------------------------------------------------------------------
// Previews (step 7): both variants, light and dark, 360 × 740 dp over a
// plain Palette.background backdrop. The sheet is snapped to its detent
// (17's preview convention); the non-mono preview does not rely on the
// first-frame focus request — if the highlight needs the focus to land,
// the screenshot test carries the selected look.
// ---------------------------------------------------------------------------

@Composable
private fun SelectTextSheetPreview(appearance: Appearance, mono: Boolean) {
    RobocoTheme(appearance) {
        val palette = RobocoTheme.palette
        Box(Modifier.fillMaxSize().background(palette.background)) {
            val state = rememberGlassSheetState(
                if (mono) SelectTextDefaults.MonoDetents else SelectTextDefaults.SansDetents,
            )
            SelectTextSheet(
                text = if (mono) ToolDetailPayload else ThreeParagraphMessage,
                onDismiss = {},
                title = if (mono) "Bash" else SelectTextDefaults.Title,
                mono = mono,
                state = state,
            )
            LaunchedEffect(state) { state.snapTo(state.detents.first()) }
        }
    }
}

@Preview(name = "Select Text light", widthDp = 360, heightDp = 740)
@Composable
private fun SelectTextPreviewLight() {
    SelectTextSheetPreview(Appearance.Light, mono = false)
}

@Preview(name = "Select Text dark", widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SelectTextPreviewDark() {
    SelectTextSheetPreview(Appearance.Dark, mono = false)
}

@Preview(name = "Tool detail light", widthDp = 360, heightDp = 740)
@Composable
private fun ToolDetailPreviewLight() {
    SelectTextSheetPreview(Appearance.Light, mono = true)
}

@Preview(name = "Tool detail dark", widthDp = 360, heightDp = 740, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToolDetailPreviewDark() {
    SelectTextSheetPreview(Appearance.Dark, mono = true)
}

/** The preview's three-paragraph message. */
private val ThreeParagraphMessage =
    "Roboco connects directly to the engines you pair with it — no account, no cloud, no sync.\n\n" +
        "The transcript is painted straight from the Rust layout core, so the text in the list itself " +
        "cannot be selected. Select Text shows the whole message here, where the system selection " +
        "handles and the Copy toolbar work.\n\n" +
        "Done dismisses the sheet; the text stays selected while it is open."

/** The preview's tool payload: 40 mono lines, the last one 160 characters. */
private val ToolDetailPayload: String = buildString {
    for (i in 1..39) appendLine("line $i: $ roboco engine start --port ${8674 + i}")
    append("k".repeat(160))
}

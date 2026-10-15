package sh.roboco.android.shell.tablet

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassSurface
import sh.roboco.android.design.uiStyle

/**
 * The stacked search bar's geometry (105 §Geometry, dp). UIKit draws the
 * navigation-bar search field itself, so the ★ values are the ticket's
 * starting points (measured once against an iPad in landscape, then
 * frozen): the 52 dp band, the 6/6 centring of the 40 dp capsule, the
 * 38 dp text start, the 6 dp text/clear gap and the 12 dp trailing inset.
 * 25 positions the band (`navBottom + max(0, 52 − c)`); this object only
 * owns what is **inside** it.
 */
object SidebarSearchMetrics {

    /** The band height — 25's `pinnedAccessoryHeight`. ★ */
    val BandHeight = 52.dp

    /** The glass capsule's height, centred in the band (6 above, 6 below). */
    val FieldHeight = 40.dp

    /** The capsule's side margins: x 16 … width − 16 (328 wide in a 360 sidebar). */
    val SideMargin = 16.dp

    /** The magnifier's leading inset inside the capsule. */
    val IconLeading = 12.dp

    /** The magnifier / clear glyph point size — box 17 × 1.2 = 20.4 dp (ticket 06). */
    const val IconPt = 17f

    /** The 17 pt glyph's layout box (ticket 06's `p × 1.2` rule). */
    val IconBox = 20.4.dp

    /** The text box's start: magnifier box end (12 + 20.4) + 6 ★, rounded down like ticket 29's 40. */
    val TextStart = 38.dp

    /** The gap between the text box's end and the clear glyph box. ★ */
    val TextGap = 6.dp

    /** The clear glyph box's end from the capsule's end (the text's end with no clear button). ★ */
    val TrailingInset = 12.dp

    /** The clear button's touch target, centred on the 20.4 dp glyph box. */
    val ClearTarget = DpSize(44.dp, 40.dp)
}

/**
 * The sidebar's stacked, never-hiding glass search field
 * (`SplitRootController.swift:216-229`) — the port of the
 * `navigationItem.searchController` with `.stacked` placement and
 * `hidesSearchBarWhenScrolling = false`: the field sits under the large
 * title, is visible at rest, rides up with it as the list scrolls and
 * pins under the nav row. 25 places the band; this composable fills it.
 *
 * - the band [modifier] blocks touches to the rows under it (25's
 *   nav-row rule), so a tap outside the capsule opens nothing;
 * - the capsule is 07's [GlassSurface] (blur through the scaffold's haze
 *   source — this must be composed inside 25's `pinnedAccessory` slot),
 *   with a `detectTapGestures` tap (not `clickable`: that would merge a
 *   button semantics node and paint `LocalIndication`'s press overlay
 *   around the text field);
 * - the magnifier (17 pt, `secondary`) leads at 12; the text (Geist
 *   Regular 17 `fixed`, one line, `accent` caret) starts at 38 and ends
 *   6 ★ before the clear glyph, else 12 ★ before the capsule's end;
 * - the clear button (17 pt `XmarkCircleFill`, `tertiary`, 44 × 40 touch
 *   target, "Clear text") shows iff the text is non-empty, swaps
 *   instantly, and focuses the field when it was unfocused (UIKit begins
 *   editing on clear);
 * - the IME "Search" action hides the IME and clears the focus, keeping
 *   the text and results; the IME hidden **while focused** (system back)
 *   drops the caret — no orphan (29's guard);
 * - `focusSearch()`/`showSessions()` requests arrive as one-shot flags on
 *   [SidebarState] and are consumed here: focus waits for the stack's
 *   transition to end plus one frame (a `requestFocus()` on a node that
 *   is not placed yet fails silently), then focuses with the caret at the
 *   end and the IME up.
 *
 * Never reads the list state — 25 owns the band's position.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SidebarSearchField(
    state: SidebarState,
    modifier: Modifier = Modifier,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val hasText by remember(state) { derivedStateOf { state.search.text.isNotEmpty() } }
    val imeVisible = WindowInsets.isImeVisible

    fun requestFocusAndShowIme() {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    // ------------------------------------------------- SidebarState requests
    // Flags, not counters: consumed when handled, so a request made just
    // before this composable was composed still runs once and a freshly
    // composed field (the 840 dp round trip) never re-runs an old request.
    LaunchedEffect(state.pendingFocus) {
        if (!state.pendingFocus) return@LaunchedEffect
        // popToRoot(false) commits on the host's next pass; wait for the
        // stack to settle…
        snapshotFlow { state.stack.transition }.first { it == null }
        // …then one more frame: the root entry is placed again before the
        // focus request, or requestFocus() fails silently.
        withFrameNanos { }
        state.pendingFocus = false
        state.search.edit { placeCursorAtEnd() }
        requestFocusAndShowIme()
    }
    LaunchedEffect(state.pendingDropFocus) {
        if (!state.pendingDropFocus) return@LaunchedEffect
        state.pendingDropFocus = false
        keyboard?.hide()
        focus.clearFocus(force = true)
    }

    // --------------------------------------------------- the orphan-caret guard
    // With the IME up and the field focused, the IME hiding (system back,
    // a swipe) must drop the caret. `sawIme` only flips once the IME has
    // actually been seen up — never right after focus (29 step 7).
    var sawIme by remember { mutableStateOf(false) }
    LaunchedEffect(focused, imeVisible) {
        if (focused && imeVisible) sawIme = true
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible && sawIme) {
            focus.clearFocus()
            sawIme = false
        }
    }

    // The band: blocks taps and drags from reaching the rows scrolled
    // under it, without consuming them (25's nav-row hit-testing rule).
    // Not a semantics node of its own beyond the test tag.
    Box(
        modifier
            .testTag("sidebar-search-band")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            },
    ) {
        // The glass capsule: 40 dp tall, 16 from each side, centred in the
        // 52 dp band. A tap anywhere on it (the magnifier included) focuses
        // the field and raises the IME; the text field inside receives its
        // own taps first as a child.
        GlassSurface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = SidebarSearchMetrics.SideMargin)
                .fillMaxWidth()
                .height(SidebarSearchMetrics.FieldHeight)
                .pointerInput(Unit) {
                    detectTapGestures { requestFocusAndShowIme() }
                },
            shape = CapsuleShape,
        ) {
            // GlassSurface's content box wraps its content (07: "no layout
            // of its own"), so the capsule's own frame is a filling box —
            // the align() children below position against it, not against
            // a wrapped content box.
            Box(Modifier.fillMaxSize()) {
                Icon(
                    RobocoIcons.Magnifyingglass,
                    tint = palette.secondary,
                    sizePt = SidebarSearchMetrics.IconPt,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = SidebarSearchMetrics.IconLeading),
                )
                // The text box ends 6 ★ before the clear glyph when it
                // shows, else 12 ★ before the capsule's end (38.4 / 12).
                val endPad = if (hasText) {
                    SidebarSearchMetrics.TrailingInset +
                        SidebarSearchMetrics.IconBox +
                        SidebarSearchMetrics.TextGap
                } else {
                    SidebarSearchMetrics.TrailingInset
                }
                BasicTextField(
                    state = state.search,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = SidebarSearchMetrics.TextStart, end = endPad)
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Search" }
                        .testTag("sidebar-search"),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = uiStyle(Face.Sans, typeScale.fixed(17f), palette.text),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    onKeyboardAction = object : KeyboardActionHandler {
                        override fun onKeyboardAction(performKeyboardAction: () -> Unit) {
                            // The IME "Search" key: hide the IME, clear the
                            // focus; the text and the results stay (the iOS
                            // UI test's typeText("\n")).
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                        }
                    },
                    decorator = TextFieldDecorator { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (!hasText) {
                                BasicText(
                                    "Search",
                                    style = uiStyle(Face.Sans, typeScale.fixed(17f), palette.tertiary),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                // The clear button: only while non-empty, no animation (UIKit
                // swaps it instantly). The 44-wide touch target is centred on
                // the 20.4 dp glyph box, so the glyph ends exactly 12 dp from
                // the capsule's end.
                if (hasText) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(
                                end = SidebarSearchMetrics.TrailingInset -
                                    (SidebarSearchMetrics.ClearTarget.width - SidebarSearchMetrics.IconBox) / 2,
                            )
                            .size(SidebarSearchMetrics.ClearTarget)
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = Role.Button,
                            ) {
                                state.search.clearText()
                                if (!focused) requestFocusAndShowIme()
                            }
                            .semantics { contentDescription = "Clear text" }
                            .testTag("sidebar-search-clear"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            RobocoIcons.XmarkCircleFill,
                            tint = palette.tertiary,
                            sizePt = SidebarSearchMetrics.IconPt,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews: the band alone (the fallback glass fill — the real band blurs
// the rows through 25's scaffold haze source, which a bare preview has
// none of), light and dark, empty and with text.
// ---------------------------------------------------------------------------

@Composable
private fun SidebarSearchFieldPreviewBody(appearance: Appearance, text: String) {
    RobocoTheme(appearance) {
        // The field's state carries the preview's text from the start —
        // never edited during composition.
        val state = remember(text) { SidebarState(search = TextFieldState(text)) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(SidebarSearchMetrics.BandHeight)
                .background(RobocoTheme.palette.background),
        ) {
            SidebarSearchField(state, Modifier.fillMaxWidth().height(SidebarSearchMetrics.BandHeight))
        }
    }
}

@Preview(name = "sidebar search light", widthDp = 360)
@Composable
private fun SidebarSearchFieldLightPreview() {
    SidebarSearchFieldPreviewBody(Appearance.Light, text = "")
}

@Preview(name = "sidebar search dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SidebarSearchFieldDarkPreview() {
    SidebarSearchFieldPreviewBody(Appearance.Dark, text = "")
}

@Preview(name = "sidebar search text light", widthDp = 360)
@Composable
private fun SidebarSearchFieldTextLightPreview() {
    SidebarSearchFieldPreviewBody(Appearance.Light, text = "wrangler")
}

@Preview(name = "sidebar search text dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SidebarSearchFieldTextDarkPreview() {
    SidebarSearchFieldPreviewBody(Appearance.Dark, text = "wrangler")
}

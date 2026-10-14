package sh.roboco.android.newsession

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.roboco.android.composer.ChipLeading
import sh.roboco.android.composer.ChipTokens
import sh.roboco.android.composer.ContextChip
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.fadingHorizontalScroll
import sh.roboco.android.design.uiStyle

/**
 * The folder browser's locations rail and search/typed-path field
 * (android-ui-parity #112) — a Roboco-only block (no iOS original) that
 * sits fixed between 110's nav bar and 110's list: a horizontally
 * scrolling, edge-faded row of **Home** + drive chips (ticket 59's
 * [ContextChip] with the selected colour override) and the rounded search
 * field (the question panel's "Other…" field, ticket 90's port of
 * `ios:Composer/QuestionPanel.swift:34-44`).
 *
 * The whole block is 90 dp: 8 + 34 (rail) + 8 + 40 (field). Nothing
 * animates — the chips change colour instantly and the rail's fade follows
 * scroll position (ticket 10) — so reduced motion changes nothing, and
 * there are no haptics. The state is [FolderNavState]'s (this composable
 * is stateless apart from the remembered [ScrollState]); `query` is
 * hoisted there and updated synchronously in `onValueChange`, or the caret
 * jumps (the ticket 90 pitfall).
 */

/** The block's geometry and copy (dp, dp-free constants); the chips are ticket 59's. */
object FolderHeaderTokens {

    /** The block's top padding, below 110's nav bar. */
    val TopPad = 8.dp

    /** The rail row's height — the strip height (`ComposerBar.swift:118`). */
    val RailHeight = 34.dp

    /** The rail's content padding, inside the scroll, so the first chip lines up with the list cards (16). */
    val RailContentPad = 16.dp

    /** Rail → field gap. */
    val Gap = 8.dp

    /** The field's height (the parent spec's 40, not the iOS template's 44). */
    val FieldHeight = 40.dp

    /** The field's horizontal margins — the list cards' inset. */
    val FieldMargin = 16.dp

    /** Circular (not continuous) 12 dp corners, as the iOS template. */
    val FieldRadius = 12.dp

    /** The text's start inset; 0 at the end (there is no right view). */
    val FieldTextStart = 12.dp

    /** Geist Regular 16 — fixed, not Dynamic-Type scaled (`Fonts.ui(.sans, 16)`). */
    const val FieldFontPt = 16f

    /** The field fill: `Palette.chip` @ 0.6 (0.6 × 255 = 0x99). */
    const val FieldFillAlpha = 0.6f

    /** The field's placeholder (web `PLACEHOLDER.folders`, U+2026). */
    const val Placeholder = "Search folders…"

    /** The filtered list's empty copy (web, U+2026-free). */
    const val NoMatch = "No folders match"

    /** The Use button's title for a missing typed path (web footer). */
    const val CreateAndAdd = "Create and add"

    /** The `No folders match` line's offset below the list's top (the card's own top spacing is 110's 35). */
    val NoMatchTop = 20.dp
}

/**
 * The rail + the field. Drawn only when an engine is selected (110/111
 * show their "No paired engines yet." status otherwise — the screen owns
 * that check).
 *
 * @param selected the selected chip's index in [chips] ([FolderNavState.selectedChip])
 * @param enabled `!submitting`: disabled chips keep their fill, turn the
 *   ink `tertiary` and no-op their taps, and the field takes no input
 * @param railState hoisted for tests; the fade follows its position
 */
@Composable
fun FolderLocationsHeader(
    chips: List<LocationChipModel>,
    selected: Int?,
    query: String,
    enabled: Boolean,
    onPickLocation: (LocationChipModel) -> Unit,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    railState: ScrollState = rememberScrollState(),
) {
    Column(modifier.fillMaxWidth().padding(top = FolderHeaderTokens.TopPad)) {
        LocationsRail(chips, selected, enabled, onPickLocation, railState)
        Spacer(Modifier.height(FolderHeaderTokens.Gap))
        FolderSearchField(query, enabled, onQueryChange, onGo)
    }
}

// ---------------------------------------------------------------------------
// The locations rail
// ---------------------------------------------------------------------------

/**
 * The full-width, 34 dp rail: ticket 59's chips 6 dp apart, centred
 * vertically, the Home chip (home glyph) then one hard-drive chip per
 * engine-reported drive, titles never truncated — the rail scrolls
 * instead. The 16 dp content padding sits **after** the scroll modifier,
 * so it scrolls with the content (ticket 10's contentInset note) and the
 * first chip lines up with the list cards at scroll 0. The 24 dp edge
 * fades sit at the sheet edges ([fadingHorizontalScroll]).
 */
@Composable
private fun LocationsRail(
    chips: List<LocationChipModel>,
    selected: Int?,
    enabled: Boolean,
    onPickLocation: (LocationChipModel) -> Unit,
    railState: ScrollState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(FolderHeaderTokens.RailHeight)
            .testTag("folder-locations")
            .fadingHorizontalScroll(railState)
            .padding(horizontal = FolderHeaderTokens.RailContentPad),
        horizontalArrangement = Arrangement.spacedBy(ChipTokens.Spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { index, chip ->
            // key(chip path): identity survives a drives refresh, and the
            // scroll position survives with it.
            key(chip.path ?: "home") {
                val isSelected = index == selected
                ContextChip(
                    title = chip.name,
                    leading = ChipLeading.Symbol(
                        if (chip.path == null) RobocoIcons.Home else RobocoIcons.HardDrive,
                    ),
                    onClick = { onPickLocation(chip) },
                    enabled = enabled,
                    colors = locationChipColors(RobocoTheme.palette, isSelected, enabled),
                    modifier = Modifier
                        .testTag(if (chip.path == null) "location-home" else "location-drive-${chip.path}")
                        // The chip merges descendants, so the flag lands on
                        // the merged node the tests read.
                        .semantics { this.selected = isSelected },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The search / typed-path field
// ---------------------------------------------------------------------------

/**
 * The field: the question panel's "Other…" field (`QuestionPanel.swift:34-
 * 44`, ticket 90's port) at the parent spec's 40 dp height — `chip` @ 0.6
 * fill, **circular** 12 dp corners, Geist Regular 16 (fixed), the text
 * 12 dp in at the start and flush at the end, the `accent` caret, IME Go.
 * The placeholder draws in `tertiary` while the text is empty and carries
 * no semantics of its own; the field node carries it as its
 * `contentDescription` instead (iOS reads the placeholder). Keyboard:
 * plain text, capitalisation and autocorrect off — a `Uri` keyboard would
 * shrink the space bar, and folder names can contain spaces.
 */
@Composable
private fun FolderSearchField(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val focus = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        singleLine = true,
        textStyle = uiStyle(Face.Sans, typeScale.fixed(FolderHeaderTokens.FieldFontPt), palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            // Go always clears focus first (hide the IME); what happens
            // next — nothing, a first-row browse or a probe — is
            // FolderNavState.onGo's business.
            onGo = {
                focus.clearFocus()
                onGo()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolderHeaderTokens.FieldMargin)
            .height(FolderHeaderTokens.FieldHeight)
            .testTag("folder-search")
            .semantics {
                if (query.isEmpty()) contentDescription = FolderHeaderTokens.Placeholder
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(FolderHeaderTokens.FieldRadius))
                    .background(palette.chip.copy(alpha = FolderHeaderTokens.FieldFillAlpha))
                    .padding(start = FolderHeaderTokens.FieldTextStart),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    BasicText(
                        FolderHeaderTokens.Placeholder,
                        style = uiStyle(
                            Face.Sans,
                            typeScale.fixed(FolderHeaderTokens.FieldFontPt),
                            palette.tertiary,
                        ),
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

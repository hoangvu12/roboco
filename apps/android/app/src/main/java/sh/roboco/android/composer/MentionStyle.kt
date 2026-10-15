package sh.roboco.android.composer

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.LocalFonts
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.FileMatch

/**
 * Every occurrence of every token in [text], as merged, non-overlapping
 * [TextRange]s — the ranges `styleMentions` accents
 * (`ios:Composer/ComposerBar.swift:381-400`). Pure function.
 *
 * - Per token, non-overlapping left-to-right matches
 *   (`text.indexOf(token, from)`, continuing at `match + token.length`);
 *   different tokens' matches may overlap (they get the same style, so the
 *   union is the visible result).
 * - Empty tokens are dropped.
 * - A match that intersects [composition] is dropped, but only while the
 *   range is non-collapsed: iOS skips the whole restyle while marked text
 *   exists (`markedTextRange == nil` guard); a transformation cannot
 *   cancel an IME composition, so only the visible effect is kept — the
 *   text being composed renders in body style, tokens elsewhere stay
 *   accent.
 * - Result sorted by start; overlapping *or touching* ranges merged.
 */
fun mentionRanges(
    text: String,
    tokens: Collection<String>,
    composition: TextRange? = null,
): List<TextRange> {
    if (tokens.isEmpty()) return emptyList()
    val comp = composition?.takeIf { !it.collapsed }
    val found = ArrayList<TextRange>()
    for (token in tokens) {
        if (token.isEmpty()) continue
        var from = 0
        while (true) {
            val at = text.indexOf(token, from)
            if (at < 0) break
            val match = TextRange(at, at + token.length)
            if (comp == null || !(match.start < comp.end && comp.start < match.end)) {
                found.add(match)
            }
            from = at + token.length
        }
    }
    if (found.isEmpty()) return emptyList()
    found.sortBy { it.start }
    val merged = ArrayList<TextRange>(found.size)
    for (range in found) {
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end) {
            merged[merged.lastIndex] = TextRange(last.start, maxOf(last.end, range.end))
        } else {
            merged.add(range)
        }
    }
    return merged
}

/**
 * Paints the live `@tokens` in the accent colour and Geist Medium at the
 * field's own size — the port of `styleMentions`
 * (`ios:Composer/ComposerBar.swift:381-400`) as a render-time
 * [VisualTransformation] instead of an `attributedText` rewrite.
 *
 * The characters are **never** changed and the offsets are
 * [OffsetMapping.Identity]: the text buffer is not rewritten, so the IME,
 * the selection and undo are never disturbed (and a token is accent
 * immediately after a pick or a programmatic set, where iOS only restyles
 * after the next keystroke — the intent kept, the lag dropped).
 * [equals]/[hashCode] cover all three fields so `remember`-ed instances
 * compare structurally.
 */
@Immutable
class MentionVisualTransformation(
    /** The live tokens; every occurrence of each is styled. */
    val tokens: Set<String>,
    /** The IME composing region; matches intersecting it are left plain. */
    val composition: TextRange?,
    /** The token style — [mentionSpanStyle]. */
    val style: SpanStyle,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val ranges = mentionRanges(text.text, tokens, composition)
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            for (range in ranges) addStyle(style, range.start, range.end)
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MentionVisualTransformation) return false
        return tokens == other.tokens && composition == other.composition && style == other.style
    }

    override fun hashCode(): Int {
        var result = tokens.hashCode()
        result = 31 * result + (composition?.hashCode() ?: 0)
        result = 31 * result + style.hashCode()
        return result
    }
}

/**
 * The live token span: accent + Geist Medium at the field's own size —
 * `Palette.accent` + `Fonts.ui(.sansMedium, font.pointSize)`
 * (`ComposerBar.swift:389,396`). Sets nothing else: no `fontSize` (the
 * token must follow the body's `bodyScaled(16.5)`), no background, no
 * underline, no letter spacing.
 */
@Composable
@ReadOnlyComposable
fun mentionSpanStyle(): SpanStyle = SpanStyle(
    color = RobocoTheme.palette.accent,
    fontFamily = LocalFonts.current.family(Face.SansMedium),
    fontWeight = FontWeight.Medium,
)

/**
 * The field's transformation: [VisualTransformation.None] while the index
 * is empty (the iOS `guard !mentions.tokens.isEmpty` fast path), otherwise
 * a remembered [MentionVisualTransformation] over the index's live token
 * set — reading `index.tokens` is the snapshot read that recomposes on
 * token changes.
 */
@Composable
fun rememberMentionTransformation(index: MentionIndex, composition: TextRange?): VisualTransformation {
    val tokens = index.tokens
    if (tokens.isEmpty()) return VisualTransformation.None
    val style = mentionSpanStyle()
    return remember(tokens.keys, composition, style) {
        MentionVisualTransformation(tokens.keys.toSet(), composition, style)
    }
}

/**
 * Ticket 53's fixture: a 360 dp composer field (foundation
 * [BasicTextField], the body style ticket 57 will install) with three live
 * tokens — `@mod.rs`, `@lib.rs` and the clash-disambiguated `@src/lib.rs` —
 * plus the debug readouts: `activeQuery` always, and `encode` only when
 * [showEncode] is set (the gallery passes it; it calls the native core, so
 * previews and Roborazzi goldens must not).
 *
 * No focus is requested, so the goldens have no caret. Editing the field is
 * live: tokens style instantly, and the `activeQuery` readout follows the
 * cursor.
 */
@Composable
fun MentionStyleDemo(showEncode: Boolean = false) {
    val index = remember {
        MentionIndex().apply {
            token(FileMatch("crates/ui/src/mod.rs", false))
            token(FileMatch("src/lib.rs", false))
            token(FileMatch("crates/core/src/lib.rs", false))
        }
    }
    val text = "Look at @mod.rs, @lib.rs and @src/lib.rs next "
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val ts = LocalTypeScale.current
    val palette = RobocoTheme.palette
    val query = MentionIndex.activeQuery(value)
    Column(
        Modifier
            .width(360.dp)
            .background(palette.background)
            .padding(16.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            // The composer body font: Geist Regular, body-scaled 16.5
            // (`ComposerBar.swift:112`). The token spans sit on top at the
            // same size through the transformation.
            textStyle = uiStyle(Face.Sans, ts.bodyScaled(16.5f), palette.text),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = rememberMentionTransformation(index, value.composition),
        )
        Spacer(Modifier.height(12.dp))
        val readout = uiStyle(Face.Mono, ts.fixed(11f), palette.secondary)
        BasicText(
            "activeQuery: " + (query?.let { "(${it.start}, ${it.end}, \"${it.query}\")" } ?: "none"),
            style = readout,
        )
        if (showEncode) {
            BasicText("encode: " + index.encode(value.text.trim()), style = readout)
        }
    }
}

/**
 * The gallery page for ticket 53's registration line in
 * `debug/design/Gallery.kt`: the fixture with the encode readout on —
 * typing `@` shows the live `activeQuery`, and the encoded `roboco-file:`
 * links are visible. Runs in the app process, where the core is loaded.
 */
@Composable
fun MentionsGallerySection() {
    MentionStyleDemo(showEncode = true)
}

@Preview(name = "Mentions light")
@Composable
private fun MentionStyleLightPreview() {
    RobocoTheme { MentionStyleDemo() }
}

@Preview(name = "Mentions dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MentionStyleDarkPreview() {
    RobocoTheme { MentionStyleDemo() }
}

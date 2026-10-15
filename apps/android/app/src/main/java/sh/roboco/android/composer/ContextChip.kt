package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.roboco.android.core.Face
import sh.roboco.android.design.BranchIcon
import sh.roboco.android.design.CapsuleShape
import sh.roboco.android.design.HarnessMark
import sh.roboco.android.design.Icon
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.Palette
import sh.roboco.android.design.PrIcon
import sh.roboco.android.design.ProjectTile
import sh.roboco.android.design.RobocoIcon
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.SymbolWeight
import sh.roboco.android.design.uiStyle

/**
 * One context chip of the composer toolbar (model, effort, branch, PR,
 * context usage — android-ui-parity #59), the port of the chip button iOS
 * builds in `rebuildChips` (`ios:Composer/ComposerBar.swift:422-458`): a
 * `controlFill` (or tone-washed) capsule with an optional leading icon and
 * a one-line, never-truncated title. [ChipStrip] lays the row out; the
 * producers (78 session, 109 new session, 112 rail) decide which chips
 * exist.
 *
 * A chip is **not** 34 dp tall: the *strip* is (34), and each chip keeps
 * its intrinsic height `7 + max(titleLine, icon) + 7` (≈ 31.55 dp sans,
 * 30.25 mono) and is centred in the row (`alignment = .center`,
 * `ComposerBar.swift:192`).
 */

/**
 * What sits before the title — [ComposerChip.icon] resolved, or
 * [ComposerChip.symbol] through [RobocoIcons.forSfName] when there is no
 * icon (the SF name that does not resolve gives **no** icon, like
 * `UIImage(systemName:) == nil`).
 */
@Immutable
sealed interface ChipLeading {

    /**
     * An SF Symbol at point 11.5, Semibold, tinted with the chip's ink —
     * ticket 06's `Icon` box is `11.5 × 1.2 = 13.8` dp.
     */
    data class Symbol(val icon: RobocoIcon) : ChipLeading

    /** The harness's brand mark at 13 dp (ticket 13; Claude keeps `#D97757`). */
    data class Brand(val harness: String) : ChipLeading

    /** The branch glyph at 13 dp, tinted with the ink (ticket 12). */
    data object Branch : ChipLeading

    /** The pull-request glyph at 12 dp, tinted with the ink (ticket 12). */
    data object PullRequest : ChipLeading

    /** A 14 dp project tile — its own tone colours, never tinted (ticket 12). */
    data class Project(val name: String, val colorIndex: Int) : ChipLeading
}

/** A chip's resolved paint: [fill] behind the capsule, [ink] for title and template icons. */
@Immutable
data class ChipColors(val fill: Color, val ink: Color)

/** Every fixed value of the chip, `ComposerBar.swift:427-458` verbatim. */
object ChipTokens {
    /** The *strip* row height (`Self.control`, `:118`, `:243`). Chips centre inside it. */
    val StripHeight = 34.dp

    /** Gap between consecutive chips (`chipStrip.spacing = 6`, `:191`). */
    val Spacing = 6.dp

    /** Chip top/bottom padding (`contentInsets`, `:438`). */
    val PadV = 7.dp

    /** Chip leading/trailing padding (`contentInsets`, `:438`). */
    val PadH = 11.dp

    /** Icon → title gap (`imagePadding = 6`). */
    val IconGap = 6.dp

    /** Icon → title gap for the `pr` chip (`imagePadding = 5`, `:432`). */
    val PrIconGap = 5.dp

    /** SF Symbol point size of a chip's leading symbol (`:429`). */
    const val SymbolPt = 11.5f

    /** The brand-mark box side (`BrandMarks.image(for:side: 13)`). */
    val BrandSide = 13.dp

    /** The branch glyph box side (`BranchIcon.sized()`, 13 pt). */
    val BranchSide = 13.dp

    /** The PR glyph box side (`PRIcon.image(side: 12)`). */
    val PrSide = 12.dp

    /** The project-tile box side (`ProjectTile.image(side: 14)`). */
    val ProjectSide = 14.dp

    /** Title size, Geist Medium (`Fonts.ui(.sansMedium, 13.5)`) — fixed, not Dynamic-Type. */
    const val TitlePt = 13.5f

    /** The `pr` chip's title size, GeistMono Medium 12.5 — fixed. */
    const val PrTitlePt = 12.5f

    /** Tinted fill: `tint.withAlphaComponent(0.1)` — the code, not the 0.08 comment (`:433`). */
    const val TintFillAlpha = 0.10f

    /** Tinted ink: `tint.withAlphaComponent(0.85)`. */
    const val TintInkAlpha = 0.85f

    /** Press overlay: `text` @0.06 inside the capsule (Roboco tuning, see the ticket's Comments). */
    const val PressOverlayAlpha = 0.06f

    /** The id whose chip takes the mono PR variant (`chip.id == "pr"`, `:432`, `:446`). */
    const val PrId = "pr"
}

/**
 * A theme-independent tint resolved against the **current** palette —
 * `PRBadgeView.tone(state)` (`ios:Design/StatusGlyph.swift:227-256`) plus
 * `Palette.warning` for the ≥ 85 % context chip.
 */
fun resolveChipTint(palette: Palette, tint: ChipTint): Color = when (tint) {
    ChipTint.PrOpen -> palette.success
    ChipTint.PrMerged, ChipTint.Accent -> palette.accent
    ChipTint.PrClosed, ChipTint.Critical -> palette.danger
    ChipTint.PrDraft -> palette.secondary
    ChipTint.Warning -> palette.warning
}

/**
 * The chip's paint, pure over the palette (`ComposerBar.swift:433-436`):
 * untinted chips are `controlFill` + `text`; tinted ones take the tone
 * wash (fill @ [ChipTokens.TintFillAlpha], ink @
 * [ChipTokens.TintInkAlpha]); a disabled chip (`canWrite == false`, the
 * Roboco offline read-only mode) keeps its fill and turns the ink
 * `tertiary`.
 */
fun chipColors(palette: Palette, tint: Color?, enabled: Boolean): ChipColors = ChipColors(
    fill = tint?.copy(alpha = ChipTokens.TintFillAlpha) ?: palette.controlFill,
    ink = if (!enabled) {
        palette.tertiary
    } else {
        tint?.copy(alpha = ChipTokens.TintInkAlpha) ?: palette.text
    },
)

/**
 * [ComposerChip]'s leading content: [ComposerChip.icon] takes precedence
 * over [ComposerChip.symbol] (iOS `config.image = icon ?? UIImage(systemName:)`);
 * an unknown SF name gives `null` (title-only chip, no icon, no gap).
 */
fun ComposerChip.leading(): ChipLeading? = when (val i = icon) {
    is ChipIcon.Brand -> ChipLeading.Brand(i.harness)
    ChipIcon.Branch -> ChipLeading.Branch
    ChipIcon.PullRequest -> ChipLeading.PullRequest
    is ChipIcon.Project -> ChipLeading.Project(i.name, i.colorIndex)
    null -> symbol?.let(RobocoIcons::forSfName)?.let(ChipLeading::Symbol)
}

// ---------------------------------------------------------------------------
// ContextChip
// ---------------------------------------------------------------------------

/**
 * The low-level chip: capsule fill, optional leading icon, one-line title
 * at a fixed size. Also the base of ticket 112's folder-browser locations
 * rail (Home + drives), which passes its own [colors] and a [ChipLeading]
 * built from `RobocoIcons` directly.
 *
 * The tap is **always consumed** (a plain `clickable` that no-ops when
 * [enabled] is false): iOS ignores touches that hit a `UIControl` in the
 * composer's focus recogniser, so a disabled chip still blocks the glass's
 * tap-to-focus instead of falling through to it.
 *
 * @param tint resolved tone colour (`resolveChipTint`); `null` = untinted
 * @param mono GeistMono Medium 12.5 instead of Geist Medium 13.5 (the PR chip)
 * @param iconGap icon → title gap (5 for the PR chip, 6 otherwise)
 * @param hasMenu only changes the a11y click label to "Show menu"
 * @param colors paint override; `null` → [chipColors] of [tint] and [enabled]
 * @param a11yLabel the merged node's content description; `null` = the title is read
 */
@Composable
fun ContextChip(
    title: String,
    leading: ChipLeading?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
    iconGap: Dp = ChipTokens.IconGap,
    hasMenu: Boolean = false,
    colors: ChipColors? = null,
    a11yLabel: String? = null,
) {
    val palette = RobocoTheme.palette
    val typeScale = LocalTypeScale.current
    val resolved = colors ?: chipColors(palette, tint, enabled)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .clip(CapsuleShape)
            .background(resolved.fill)
            .drawWithContent {
                // The press look: a text-coloured wash inside the capsule
                // (darkens in light, lightens in dark), no ripple, no
                // spring — it appears and disappears instantly.
                if (pressed && enabled) {
                    drawRect(palette.text.copy(alpha = ChipTokens.PressOverlayAlpha))
                }
                drawContent()
            }
            // clickable stays enabled = true even for a disabled chip: the
            // tap must be consumed (see the KDoc), and the guard below
            // makes it a no-op instead.
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (hasMenu) "Show menu" else null,
            ) { if (enabled) onClick() }
            .semantics(mergeDescendants = true) {
                if (!enabled) disabled()
                a11yLabel?.let { contentDescription = it }
            }
            .padding(horizontal = ChipTokens.PadH, vertical = ChipTokens.PadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            ChipLeadingIcon(leading, resolved.ink)
            Spacer(Modifier.width(iconGap))
        }
        BasicText(
            title,
            style = uiStyle(
                if (mono) Face.MonoMedium else Face.SansMedium,
                typeScale.fixed(if (mono) ChipTokens.PrTitlePt else ChipTokens.TitlePt),
                resolved.ink,
            ),
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Visible,
        )
    }
}

/**
 * A [ComposerChip]: `testTag = "composer-chip-<id>"` and the PR variant
 * (mono 12.5 title, 5 dp icon gap) for id `"pr"`. The merged semantics
 * node's text is the title, so `assertTextContains` reads it like the iOS
 * UI test's `app.buttons["composer-chip-effort"].label`.
 */
@Composable
fun ContextChip(
    chip: ComposerChip,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasMenu: Boolean = false,
) {
    val pr = chip.id == ChipTokens.PrId
    ContextChip(
        title = chip.title,
        leading = chip.leading(),
        onClick = onClick,
        modifier = modifier.testTag("composer-chip-${chip.id}"),
        tint = chip.tint?.let { resolveChipTint(RobocoTheme.palette, it) },
        enabled = enabled,
        mono = pr,
        iconGap = if (pr) ChipTokens.PrIconGap else ChipTokens.IconGap,
        hasMenu = hasMenu,
        a11yLabel = chip.contentDescription,
    )
}

/**
 * The chip's leading icon. Every kind is decorative (no semantics: the
 * chip's merged node carries the title) and carries the non-merged
 * `chip-icon` test tag, which the geometry tests read with
 * `useUnmergedTree = true`. Project tiles and the Claude mark keep their
 * own colours — they are never tinted with the ink.
 */
@Composable
private fun ChipLeadingIcon(leading: ChipLeading, ink: Color) {
    val tag = Modifier.testTag("chip-icon")
    when (leading) {
        is ChipLeading.Symbol -> Icon(
            leading.icon,
            ink,
            sizePt = ChipTokens.SymbolPt,
            weight = SymbolWeight.Semibold,
            modifier = tag,
        )
        is ChipLeading.Brand -> HarnessMark(leading.harness, ChipTokens.BrandSide, ink, tag)
        ChipLeading.Branch -> BranchIcon(ink, tag, side = ChipTokens.BranchSide)
        ChipLeading.PullRequest -> PrIcon(ChipTokens.PrSide, ink, tag)
        is ChipLeading.Project -> ProjectTile(
            leading.name,
            leading.colorIndex,
            tag,
            side = ChipTokens.ProjectSide,
        )
    }
}

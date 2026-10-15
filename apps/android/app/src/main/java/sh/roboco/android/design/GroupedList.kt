package sh.roboco.android.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.roboco.android.core.Face
import sh.roboco.android.core.GeistMetrics

/**
 * The inset-grouped list primitive (android-ui-parity #70): the Compose port
 * of `UICollectionLayoutListConfiguration(appearance: .insetGrouped)` with
 * `subtitleCell()` rows, `listGroupedCell()` backgrounds and
 * `groupedHeader()` section headers (`ios:Shell/MoreViewController.swift`).
 * Settings (103), engine detail (140), Licenses (101), the Pair page card
 * (72) and the folder browser (110) all build on it.
 *
 * Everything is Compose foundation: no `ListItem`, no Material `Card`, no
 * ripple. The one animation is the pressed-highlight fade
 * ([GroupedListTokens.HighlightFadeOut]); data changes are instant, like
 * `dataSource.apply(s, animatingDifferences: false)`
 * (`MoreViewController.swift:219`).
 */

/**
 * The measurements of `2-settings.png`, re-measured for ticket 70 (1 pt =
 * 1.1443 px, card edges at 50 % coverage). `CardInset` stays a token so
 * ticket 104/33 can widen it on regular-width tablets.
 *
 * The three `system*Label`/`systemSeparator` colours are **UIKit default
 * colours**, not `Palette` roles — the disclosure indicator and the header
 * keep UIKit's own `tertiaryLabel`/`secondaryLabel`/`separator` ink, and
 * the measurements show the difference (the chevron plateau is
 * `#C4C4C6`, not `Palette.tertiary`'s 151). They are built with ticket 01's
 * [rgba] quantisation rule: 0.6 → `0x99`, 0.3 → `0x4D`, 0.29 → `0x4A`.
 */
object GroupedListTokens {

    /** The card's inset from each screen edge. */
    val CardInset = 16.dp

    /** Continuous-corner radius of the whole card (26: the 45° curve point sits 7.6 dp from both edges). */
    val CornerRadius = 26.dp

    /** Row minimum height; the 15 dp vertical padding models 52 / 67.7 / 84.6 dp rows. */
    val RowMinHeight = 52.dp

    /** Padding above the title and below the subtitle (title-only rows centre by the minimum). */
    val RowVerticalPadding = 15.dp

    /** The icon box's start, and the text start when there is no icon. */
    val ContentStart = 16.dp

    /** The leading symbol's SF point size (`UIImage.SymbolConfiguration`); `× 1.2` = the 24 dp box. */
    const val IconSizePt = 20f

    /** Gap between the icon box and the text column. */
    val IconTextGap = 16.dp

    /** The text start when there is an icon: 16 + 24 + 16 = 56 dp inside the card. */
    val TextStartWithIcon = 56.dp

    /** The text column's end padding when no accessory is visible. */
    val TextEnd = 16.dp

    /** Gap between the text column and the accessory group. */
    val TextToAccessory = 12.dp

    /** Gap between two accessories in the trailing group (interpretation; UIKit's is unstated). */
    val AccessoryGap = 12.dp

    /**
     * The disclosure accessory's layout width: UIKit's
     * `.disclosureIndicator()` is an image view that hugs the chevron (ink
     * 7 × 12.2 pt on `2-settings.png`), so [TextToAccessory] is measured from
     * the chevron itself. The glyph still draws in its SF `13 × 1.2` = 15.6 dp
     * box, centred on this width and overhanging it on both sides (empty
     * space only). Reserving the whole 15.6 dp square (ticket 70's first cut)
     * narrowed the text column by 4.3 dp to 271.7 dp at a 402 dp window, and
     * "Shown behind new chats and the sessions list" (273.1 dp of Geist 13,
     * kerned) wrapped where iOS keeps it on one line (android-next round 46).
     */
    val DisclosureWidth = 7.dp

    /** Disclosure end padding: ink centre 22.5 from the card edge, 22.5 − 7 / 2. */
    val ChevronEnd = 19.dp

    /** Checkmark end padding: box 20.4, ink centre 28.5 from the card edge. */
    val CheckEnd = 18.3.dp

    /** End padding of the dot, value text and custom accessories (the UIKit custom-view trailing margin). */
    val TrailingMargin = 16.dp

    /** The connection dot, 8 × 8 with corner radius 4 (`MoreViewController.swift:70-71`). */
    val DotSize = 8.dp

    /** The separator's end inset from the card's trailing edge. */
    val SeparatorEndInset = 16.dp

    /** Padding above the header's Geist line box. */
    val HeaderTop = 27.dp

    /** Padding below the header's Geist line box (the `p` descender ends 11.8 dp above the card). */
    val HeaderBottom = 10.dp

    /** The header's horizontal inset, measured inside the card edges (32 from the screen). */
    val HeaderHorizontal = 16.dp

    /** Padding above the footer's line box; bottom is 0 (interpretation; no footer in the screenshots). */
    val FooterTop = 8.dp

    /**
     * The pressed highlight's fade-out: 1 → 0 over 250 ms, ease-in-out
     * (`Motion.EaseInOut`). 250 ms is a **non-source stand-in** for UIKit's
     * unstated `deselectItem(at:animated: true)` fade — this ticket owns
     * the token, independent of the 37/41 session-row fades (300/250 ms,
     * theirs to settle).
     */
    const val HighlightFadeOutMs = 250

    val HighlightFadeOut: TweenSpec<Float> = tween(HighlightFadeOutMs, easing = Motion.EaseInOut)

    /**
     * UIKit `secondaryLabel` over `background`: light `#3C3C43`@0.6 =
     * `#85858A`, dark `#EBEBF5`@0.6 = `#8F8F95`. The section header's ink —
     * the app never overrides `groupedHeader()`'s colour.
     */
    @Composable
    @ReadOnlyComposable
    fun systemSecondaryLabel(): Color =
        if (RobocoTheme.isDark) SecondaryLabelDark else SecondaryLabelLight

    /**
     * UIKit `tertiaryLabel` over `elevated`: light `#3C3C43`@0.3 =
     * `#C4C4C6`, dark `#EBEBF5`@0.3 = `#525257`. The system disclosure
     * chevron's ink.
     */
    @Composable
    @ReadOnlyComposable
    fun systemTertiaryLabel(): Color =
        if (RobocoTheme.isDark) TertiaryLabelDark else TertiaryLabelLight

    /**
     * UIKit `separator`: light `#3C3C43`@0.29 = `#C6C6C8` on white, dark
     * `#545458`@0.6 = `#39393C` on `elevated`. The row hairline's colour —
     * **not** `Palette.hairline`, which is about half as dark.
     */
    @Composable
    @ReadOnlyComposable
    fun systemSeparator(): Color =
        if (RobocoTheme.isDark) SeparatorDark else SeparatorLight
}

/** UIKit `secondaryLabel`, light / dark, alpha quantised through [rgba]. */
private val SecondaryLabelLight = rgba(0x3C3C43, 0.6)
private val SecondaryLabelDark = rgba(0xEBEBF5, 0.6)

/** UIKit `tertiaryLabel`, light / dark. */
private val TertiaryLabelLight = rgba(0x3C3C43, 0.3)
private val TertiaryLabelDark = rgba(0xEBEBF5, 0.3)

/** UIKit `separator`, light / dark. */
private val SeparatorLight = rgba(0x3C3C43, 0.29)
private val SeparatorDark = rgba(0x545458, 0.6)

// ---------------------------------------------------------------------------
// Position
// ---------------------------------------------------------------------------

/**
 * A row's place in its card — the "shape by index/count" idea, the only
 * thing #609's `SettingsScreen` got right. `Single` is the whole card,
 * `First`/`Last` are the card's top/bottom rows; only `First` and `Middle`
 * carry a separator (a hairline never follows the last row).
 */
enum class GroupedPosition {
    Single,
    First,
    Middle,
    Last;

    /** True for [First] and [Middle]: the separator is drawn at the row's bottom. */
    val hasSeparator: Boolean get() = this == First || this == Middle

    companion object {
        /**
         * The position of row [index] in a card of [count] rows.
         * `require`-fails on empty cards or out-of-range indices — callers
         * compute it from a live list, never an empty one.
         */
        fun of(index: Int, count: Int): GroupedPosition {
            require(count > 0 && index in 0 until count) {
                "GroupedPosition.of(index=$index, count=$count): index must be in 0 until count"
            }
            return when {
                count == 1 -> Single
                index == 0 -> First
                index == count - 1 -> Last
                else -> Middle
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shape
// ---------------------------------------------------------------------------

/**
 * The card clip for **one row**: the full card's continuous corners, cut
 * down to the row's share. `Single` is the whole (possibly clamped)
 * [smoothRoundedPath]; `First`/`Last` are a card two radii taller,
 * intersected with the row rect, so the corners are computed as if the card
 * were taller than 2r and **never clamp**; `Middle` is a plain rectangle.
 *
 * One instance per position is shared (file-level [groupedCardShapes]),
 * so the clip never reallocates per row.
 */
@Immutable
class GroupedCardShape(val position: GroupedPosition, val radius: Dp = GroupedListTokens.CornerRadius) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(Rect(0f, 0f, w, h))
        val r = with(density) { radius.toPx() }
        return when (position) {
            // Ticket 01's path clamps r to min(w, h)/2: a 52 dp single-row
            // card is a capsule, like iOS.
            GroupedPosition.Single -> Outline.Generic(smoothRoundedPath(w, h, r))
            GroupedPosition.Middle -> Outline.Rectangle(Rect(0f, 0f, w, h))
            GroupedPosition.First -> Outline.Generic(topHalfOfCard(w, h, r))
            GroupedPosition.Last -> Outline.Generic(bottomHalfOfCard(w, h, r))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GroupedCardShape && other.position == position && other.radius == radius

    override fun hashCode(): Int = 31 * position.hashCode() + radius.hashCode()

    override fun toString(): String = "GroupedCardShape(position=$position, radius=$radius)"
}

/** The card's top corners in the row rect: the tall card minus 2r below. */
private fun topHalfOfCard(w: Float, h: Float, r: Float): Path =
    cardClip(w, h, r, shiftUp = false)

/** The card's bottom corners in the row rect: the tall card shifted up 2r. */
private fun bottomHalfOfCard(w: Float, h: Float, r: Float): Path =
    cardClip(w, h, r, shiftUp = true)

/**
 * The card `h + 2r` tall (so its corners never clamp), optionally shifted
 * up by 2r for the bottom row, intersected with the row rect
 * `Path.op(…, PathOperation.Intersect)`.
 */
private fun cardClip(w: Float, h: Float, r: Float, shiftUp: Boolean): Path {
    val tall = smoothRoundedPath(w, h + 2f * r, r)
    if (shiftUp) tall.translate(Offset(0f, -2f * r))
    val row = Path().apply { addRect(Rect(0f, 0f, w, h)) }
    return Path().apply { op(tall, row, PathOperation.Intersect) }
}

/** One shape per position, shared by every row (the default radius). */
private val groupedCardShapes = GroupedPosition.entries.associateWith { GroupedCardShape(it) }

// ---------------------------------------------------------------------------
// Accessories
// ---------------------------------------------------------------------------

/**
 * A row's trailing accessory — `Row.accessory` in
 * `MoreViewController.swift:15-17`. Rendered in the caller's order, leading
 * → trailing, 12 dp apart. `Check(on = false)` shows nothing and reserves
 * no space, exactly like the empty `cell.accessories` array.
 */
@Immutable
sealed interface GroupedAccessory {

    /** UIKit `.disclosureIndicator()`, in [GroupedListTokens.systemTertiaryLabel]. */
    data object Disclosure : GroupedAccessory

    /** UIKit `.checkmark(tintColor: accent)`; `on = false` renders nothing. */
    data class Check(val on: Boolean) : GroupedAccessory

    /** The 8 dp connection dot: [Palette.success] when online, [Palette.tertiary] otherwise. No semantics. */
    data class Dot(val online: Boolean) : GroupedAccessory

    /** Right-aligned value text (the app's only `valueCell` precedent is `VoiceViewController.swift:59,64`). */
    data class Value(val text: String) : GroupedAccessory

    /**
     * A custom trailing accessory — ticket 98's `RobocoSwitch` slot. Not a
     * data class: lambdas have no equality. End padding 16 dp
     * ([GroupedListTokens.TrailingMargin]).
     */
    class Custom(val content: @Composable () -> Unit) : GroupedAccessory
}

/**
 * `cell.accessibilityIdentifier = "settings-\(row.id)"`
 * (`MoreViewController.swift:82`): the row's test tag.
 */
fun settingsTag(id: String): String = "settings-$id"

// ---------------------------------------------------------------------------
// Section header and footer
// ---------------------------------------------------------------------------

/**
 * The section header: UIKit `groupedHeader()`'s block — 27 dp above and
 * 10 dp below the Geist SemiBold 16 `bodyScaled` line box (57.8 dp at font
 * scale 1, the card-to-card gap), inset 16 dp inside the card edges, text
 * wrapping. The ink is UIKit `secondaryLabel`
 * ([GroupedListTokens.systemSecondaryLabel]); the font is Geist SemiBold,
 * not the OEM system font, because OEM faces would break parity.
 *
 * A blank [text] keeps the block's height but shows nothing and carries no
 * semantics — the sign-out section's identifier is `" "`
 * (`MoreViewController.swift:217`).
 */
@Composable
fun GroupedHeader(text: String, modifier: Modifier = Modifier) {
    val horizontal = GroupedListTokens.HeaderHorizontal + GroupedListTokens.CardInset
    val block = Modifier
        .fillMaxWidth()
        .padding(
            start = horizontal,
            end = horizontal,
            top = GroupedListTokens.HeaderTop,
            bottom = GroupedListTokens.HeaderBottom,
        )
    if (text.isBlank()) {
        val ts = LocalTypeScale.current
        // One header line box: the body-scaled size's natural line height,
        // so the blank block is exactly as tall as a one-line header.
        val line = with(LocalDensity.current) { ts.bodyScaled(16f).toDp() } * GeistMetrics.LINE_HEIGHT
        Spacer(
            modifier
                .then(block)
                .height(line)
                .clearAndSetSemantics { },
        )
    } else {
        val ts = LocalTypeScale.current
        Box(modifier.then(block)) {
            BasicText(
                text,
                style = uiStyle(Face.SansSemibold, ts.bodyScaled(16f), GroupedListTokens.systemSecondaryLabel()),
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/**
 * The section footer (a Roboco ADD — no footer exists in the reference
 * screenshots): Geist 13 fixed, [color] (default `Palette.secondary`;
 * 140's error footer passes `tertiary`), 8 dp above, inset 16 dp inside the
 * card edges, bottom padding 0.
 */
@Composable
fun GroupedFooter(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RobocoTheme.palette.secondary,
) {
    val horizontal = GroupedListTokens.HeaderHorizontal + GroupedListTokens.CardInset
    val ts = LocalTypeScale.current
    // The block (and the caller's modifier — the Pair page's test tags) is
    // a Box, the text its own node inside the padding, like GroupedHeader:
    // a semantics node's bounds come from its outermost semantics
    // modifier, so a tag on the BasicText itself would report the text at
    // x 0 instead of its real start, 32 dp from the screen edge.
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = horizontal, end = horizontal, top = GroupedListTokens.FooterTop),
    ) {
        BasicText(text, style = uiStyle(Face.Sans, ts.fixed(13f), color))
    }
}

// ---------------------------------------------------------------------------
// Row container
// ---------------------------------------------------------------------------

/**
 * The card row a custom cell builds in (the Pair page's link field, ticket
 * 72): the elevated fill, the per-position clip, the pressed highlight and
 * the bottom hairline, with the caller's [content] laid out as a centered
 * [Row]. [GroupedRow] is the standard [subtitleCell] port on top of it.
 *
 * The pressed highlight is an **adaptation**: iOS suppresses UIKit's grey
 * (the customised background colour survives `updated(for:)`), Android
 * draws the `controlFill` the iOS app itself uses on session rows
 * (`SessionCell.swift:81,225`). It snaps to 1 on press and fades out over
 * [GroupedListTokens.HighlightFadeOut] on release or cancel — including a
 * quick tap, so every tap flashes like iOS's select → animated deselect.
 *
 * @param separatorStart the separator's start inset from the card's leading
 *   edge (56 with an icon, 16 without, or a caller override)
 * @param selected semantics only — [GroupedAccessory.Check] on (the row is
 *   "selected" in the appearance list); no visual effect here
 * @param previewPressed holds the highlight at 1: the design gallery's
 *   "pressed" row and the screenshot fixtures. Never set it from behaviour.
 * @param onClick null = not clickable, no highlight (toggle rows, the
 *   static "account" row); otherwise `Role.Button` + the click action
 * @param onLongClick a long-press action without a click action
 *   (android-ui-parity #101: the About Version row's copy). A row with
 *   only this stays plain-tappable — the highlight still flashes, and
 *   `combinedClickable` still registers a (no-op) click action for
 *   TalkBack, but gets **no** `Role.Button` — with **no** system haptic:
 *   ticket 05 owns haptics through [Feedback], so the label must fire
 *   [Haptic.LongPress] itself
 * @param onLongClickLabel the long-press action's accessibility label
 *   ("double-tap and hold to …"), verbatim from the ticket's copy table
 */
@Composable
fun GroupedRowContainer(
    position: GroupedPosition,
    modifier: Modifier = Modifier,
    separatorStart: Dp = GroupedListTokens.TextStartWithIcon,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    selected: Boolean = false,
    previewPressed: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = RobocoTheme.palette
    val source = remember { MutableInteractionSource() }
    val highlight = remember { Animatable(if (previewPressed) 1f else 0f) }
    // Collect the interactions themselves, not collectIsPressedAsState():
    // a quick tap emits Press and Release in the same frame, and a Boolean
    // key would never see `true` (no flash). The fade job is cancelled and
    // restarted on each interaction, so reversals continue from the
    // current value.
    LaunchedEffect(source) {
        val scope = this
        var fade: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    fade?.cancel()
                    highlight.snapTo(1f)
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    fade?.cancel()
                    fade = scope.launch {
                        highlight.animateTo(0f, GroupedListTokens.HighlightFadeOut)
                    }
                }
            }
        }
    }
    LaunchedEffect(previewPressed) { if (previewPressed) highlight.snapTo(1f) }
    val separator = GroupedListTokens.systemSeparator()
    val hairline = hairlineWidth()
    Row(
        modifier
            .padding(horizontal = GroupedListTokens.CardInset)
            .fillMaxWidth()
            .heightIn(min = GroupedListTokens.RowMinHeight)
            .clip(groupedCardShapes.getValue(position))
            .drawWithContent {
                drawRect(palette.elevated)
                // Read only inside the draw phase: the fade re-draws, never
                // recomposes.
                val alpha = highlight.value
                if (alpha > 0f) drawRect(palette.controlFill, alpha = alpha)
                drawContent()
                if (position.hasSeparator) {
                    val hairPx = hairline.toPx()
                    val y = size.height - hairPx / 2f
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val start = if (rtl) size.width - separatorStart.toPx() else separatorStart.toPx()
                    val end = if (rtl) {
                        GroupedListTokens.SeparatorEndInset.toPx()
                    } else {
                        size.width - GroupedListTokens.SeparatorEndInset.toPx()
                    }
                    drawLine(separator, Offset(start, y), Offset(end, y), hairPx)
                }
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) { if (selected) this.selected = true }
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = source,
                        indication = null,
                        role = if (onClick != null) Role.Button else null,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        // Ticket 05: haptics go through LocalFeedback, never
                        // the system's own long-press haptic.
                        hapticFeedbackEnabled = false,
                        onClick = onClick ?: {},
                    )
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// The row
// ---------------------------------------------------------------------------

/**
 * The standard row — `UIListContentConfiguration.subtitleCell()`
 * (`MoreViewController.swift:49-83`): a leading 20 pt symbol, the Geist
 * Medium 16 fixed title (wrapping, `danger` when [destructive]), the Geist
 * 13 fixed subtitle below it (wrapping, `secondary`), 15 dp vertical
 * padding around the text block, 52 dp minimum height, and the trailing
 * [accessories] (default [GroupedAccessory.Disclosure], like the iOS `Row`).
 *
 * Typography is **fixed** (`ts.fixed`, not `scaled`): iOS `Fonts.ui` creates
 * the `CTFont` at exactly that size with no `UIFontMetrics`
 * (`Fonts.swift:64-66`). Only the header follows Dynamic Type.
 *
 * @param icon the leading symbol (`RobocoIcons.forSfName(row.symbol)`), or
 *   null for a text-only row (the separator then starts 16 dp inside the
 *   card)
 * @param iconTint `Row.tint`; `Palette.secondary` by default
 *   (`MoreViewController.swift:13`), `accent` for the folder browser's
 *   repos, `danger` when [destructive]
 * @param titleStyle overrides the title's style (110's ".." row, 140's
 *   offline "Rename…" row); the caller keeps the `fixed(16)` size itself
 * @param testTag the row's tag — Settings callers pass [settingsTag]
 * @param previewPressed holds the pressed highlight at 1 (gallery only)
 * @param onLongClick a long-press action without a click action
 *   (android-ui-parity #101: the About Version row's copy); see
 *   [GroupedRowContainer.onLongClick]
 * @param onLongClickLabel the long-press action's accessibility label
 */
@Composable
fun GroupedRow(
    title: String,
    position: GroupedPosition,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: RobocoIcon? = null,
    iconTint: Color = RobocoTheme.palette.secondary,
    destructive: Boolean = false,
    accessories: List<GroupedAccessory> = listOf(GroupedAccessory.Disclosure),
    titleStyle: TextStyle? = null,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    previewPressed: Boolean = false,
) {
    // The Value accessory caps at 50 % of the card's inner width, which is
    // only known from the row's incoming constraints: a BoxWithConstraints
    // wrapper for rows that carry one, so plain rows (the folder browser's
    // hundreds) pay no subcomposition. Only About/Version and the engine
    // detail use values.
    if (accessories.any { it is GroupedAccessory.Value }) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val maxValueWidth = (maxWidth - GroupedListTokens.CardInset * 2) * 0.5f
            GroupedRowBody(
                title = title,
                position = position,
                modifier = Modifier,
                maxValueWidth = maxValueWidth,
                subtitle = subtitle,
                icon = icon,
                iconTint = iconTint,
                destructive = destructive,
                accessories = accessories,
                titleStyle = titleStyle,
                testTag = testTag,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                previewPressed = previewPressed,
            )
        }
    } else {
        GroupedRowBody(
            title = title,
            position = position,
            modifier = modifier,
            maxValueWidth = Dp.Unspecified,
            subtitle = subtitle,
            icon = icon,
            iconTint = iconTint,
            destructive = destructive,
            accessories = accessories,
            titleStyle = titleStyle,
            testTag = testTag,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            previewPressed = previewPressed,
        )
    }
}

@Composable
private fun GroupedRowBody(
    title: String,
    position: GroupedPosition,
    modifier: Modifier,
    maxValueWidth: Dp,
    subtitle: String?,
    icon: RobocoIcon?,
    iconTint: Color,
    destructive: Boolean,
    accessories: List<GroupedAccessory>,
    titleStyle: TextStyle?,
    testTag: String?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    previewPressed: Boolean,
) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    GroupedRowContainer(
        position = position,
        modifier = modifier,
        separatorStart = if (icon != null) GroupedListTokens.TextStartWithIcon else GroupedListTokens.ContentStart,
        testTag = testTag,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        selected = accessories.any { it is GroupedAccessory.Check && it.on },
        previewPressed = previewPressed,
    ) {
        Spacer(Modifier.width(GroupedListTokens.ContentStart))
        if (icon != null) {
            Icon(
                icon,
                if (destructive) palette.danger else iconTint,
                GroupedListTokens.IconSizePt,
                SymbolWeight.Regular,
            )
            Spacer(Modifier.width(GroupedListTokens.IconTextGap))
        }
        Column(Modifier.weight(1f).padding(vertical = GroupedListTokens.RowVerticalPadding)) {
            BasicText(
                title,
                style = titleStyle
                    ?: uiStyle(Face.SansMedium, ts.fixed(16f), if (destructive) palette.danger else palette.text),
            )
            if (subtitle != null) {
                BasicText(subtitle, style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary))
            }
        }
        AccessoryGroup(accessories, maxValueWidth)
    }
}

/**
 * The trailing accessories: the invisible `Check(false)` removed (no space
 * reserved), a 12 dp gap after the text column, the group itself, and the
 * **last** accessory's end padding.
 */
@Composable
private fun RowScope.AccessoryGroup(accessories: List<GroupedAccessory>, maxValueWidth: Dp) {
    val shown = accessories.filterNot { it is GroupedAccessory.Check && !it.on }
    if (shown.isEmpty()) {
        Spacer(Modifier.width(GroupedListTokens.TextEnd))
        return
    }
    Spacer(Modifier.width(GroupedListTokens.TextToAccessory))
    Row(
        horizontalArrangement = Arrangement.spacedBy(GroupedListTokens.AccessoryGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (accessory in shown) {
            Accessory(accessory, maxValueWidth)
        }
    }
    Spacer(Modifier.width(endPadding(shown.last())))
}

/**
 * The trailing edge of [accessory]'s own box: the group's end padding is
 * its last accessory's — 14.7 (disclosure), 18.3 (checkmark) or 16.
 */
private fun endPadding(accessory: GroupedAccessory): Dp = when (accessory) {
    GroupedAccessory.Disclosure -> GroupedListTokens.ChevronEnd
    is GroupedAccessory.Check -> GroupedListTokens.CheckEnd
    else -> GroupedListTokens.TrailingMargin
}

@Composable
private fun Accessory(accessory: GroupedAccessory, maxValueWidth: Dp) {
    val palette = RobocoTheme.palette
    val ts = LocalTypeScale.current
    when (accessory) {
        GroupedAccessory.Disclosure ->
            // Lays out at the chevron's own width (UIKit's accessory frame),
            // the 15.6 dp symbol box centred on it and drawn unclipped.
            Icon(
                RobocoIcons.ChevronRight,
                GroupedListTokens.systemTertiaryLabel(),
                13f,
                SymbolWeight.Semibold,
                modifier = Modifier
                    .width(GroupedListTokens.DisclosureWidth)
                    .wrapContentWidth(unbounded = true),
            )

        is GroupedAccessory.Check ->
            Icon(RobocoIcons.Checkmark, palette.accent, 17f, SymbolWeight.Semibold)

        is GroupedAccessory.Dot ->
            // A plain view on iOS: no semantics — the subtitle carries the
            // state in words ("Connected").
            Box(
                Modifier
                    .size(GroupedListTokens.DotSize)
                    .background(if (accessory.online) palette.success else palette.tertiary, CircleShape),
            )

        is GroupedAccessory.Value ->
            BasicText(
                accessory.text,
                style = uiStyle(Face.Sans, ts.fixed(13f), palette.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxValueWidth),
            )

        is GroupedAccessory.Custom -> accessory.content()
    }
}

// ---------------------------------------------------------------------------
// The LazyListScope emitter
// ---------------------------------------------------------------------------

/**
 * Emits one inset-grouped section into ticket 25's
 * `LargeTitleScaffold(content: LazyListScope.() -> Unit)` (or any
 * `LazyColumn`): the optional [header] (`null` = none, blank = the
 * spacer-only header), the rows as separate items — never one lazy item per
 * section; the folder browser lists hundreds of folders — and the optional
 * [footer].
 *
 * Keys are `grouped:<key>:header`, `grouped:<key>:row:<itemKey>` and
 * `grouped:<key>:footer`; content types are `grouped-header`,
 * `grouped-row`, `grouped-footer`. No `Modifier.animateItem()`: iOS
 * reloads with `animatingDifferences: false`. An empty [items] list still
 * emits the header (UIKit shows the header of an empty section).
 */
fun <T> LazyListScope.groupedSection(
    key: String,
    header: String?,
    items: List<T>,
    itemKey: (T) -> Any,
    footer: String? = null,
    itemContent: @Composable LazyItemScope.(item: T, position: GroupedPosition) -> Unit,
) {
    if (header != null) {
        item(key = "grouped:$key:header", contentType = "grouped-header") {
            GroupedHeader(header)
        }
    }
    itemsIndexed(
        items,
        key = { _, it -> "grouped:$key:row:${itemKey(it)}" },
        contentType = { _, _ -> "grouped-row" },
    ) { index, it ->
        itemContent(it, GroupedPosition.of(index, items.size))
    }
    if (footer != null) {
        item(key = "grouped:$key:footer", contentType = "grouped-footer") {
            GroupedFooter(footer)
        }
    }
}

package sh.roboco.android.design.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import sh.roboco.android.design.RobocoIcon

/**
 * The pull-down / context menu **model** (android-ui-parity #15): the
 * Android port of what upstream's Swift *builds* — `UIMenu`, `UIAction`,
 * `UIDeferredMenuElement` — never what it *draws* (UIKit draws `UIMenu`
 * itself; the Roboco panel is [GlassMenuPanel] in `GlassMenu.kt`).
 *
 * Consumers (44, 45, 46, 58, 80, 82, 87, 89, 110, 113, 114) build their
 * menu content with this model and hand it to [GlassMenuHost] /
 * [sh.roboco.android.design.glass.GlassMenuHost]-presented surfaces
 * (`Modifier.glassMenu`, `rememberGlassMenuState`).
 */
@Immutable
data class Menu(
    /** Shown as the first line of the root level when non-blank (`UIMenu.title`). */
    val title: String? = null,
    val children: List<MenuElement>,
)

/**
 * One child of a [Menu] level: an action ([MenuItem]), a nested menu
 * ([MenuSection]) or a lazily loaded run ([Deferred]).
 */
sealed interface MenuElement {

    /**
     * `UIDeferredMenuElement`.
     *
     * - `cached = true` → the provider runs once per **instance** and the
     *   result is kept in [cache] for the instance's lifetime (UIKit's plain
     *   `UIDeferredMenuElement`). The `menu` lambda runs on every open, so a
     *   deferred created *inside* it is new each time and reloads — callers
     *   that want the cached behaviour must hoist the instance
     *   (`remember(keys…) { MenuElement.Deferred(true) { … } }`) with keys
     *   covering everything the result depends on, including the checked
     *   state.
     * - `cached = false` → the provider runs every time the menu opens
     *   (`UIDeferredMenuElement.uncached`): pin state, pasteboard, settings.
     *
     * A plain `class`, not a data class: the cache is keyed by instance
     * identity, and equality would be wrong.
     */
    class Deferred(
        val cached: Boolean,
        val load: suspend () -> List<MenuElement>,
    ) : MenuElement {
        /** The last result; only written when [cached] is true. */
        internal var cache: List<MenuElement>? = null
    }
}

/**
 * `UIAction`: one row. Two inks per row — the title ink follows the
 * [destructive] / [disabled] state, the leading icon keeps `text` even on
 * disabled rows (the iOS 26 capture: disabled icons are not dimmed and a
 * disabled destructive row's icon is not red).
 */
@Immutable
data class MenuItem(
    val title: String,
    val subtitle: String? = null,
    val icon: MenuIcon? = null,
    /** `UIMenuElement.State.on`: a checkmark replaces the icon in the leading slot. */
    val checked: Boolean = false,
    /** `.destructive`: title **and** icon use `Palette.danger` (when enabled). */
    val destructive: Boolean = false,
    /** `.disabled`: title greys to `secondary`, taps are ignored. */
    val disabled: Boolean = false,
    val testTag: String? = null,
    /**
     * A read-only line (android-next #01's context card): normal inks, no
     * tap target, no press highlight and not announced as disabled — the
     * title and subtitle read as one TalkBack node. [action] never runs.
     */
    val informational: Boolean = false,
    val action: () -> Unit,
) : MenuElement

/**
 * `UIMenu` nested.
 *
 * - `inline = true` (`.displayInline`): a **group in place**, introduced by
 *   an optional [Header][MenuLine.Header] line and separated from its
 *   neighbours by a hairline.
 * - `inline = false`: a **drill-in row** with a trailing chevron; tapping it
 *   replaces the panel's content with the section's children plus a back
 *   row (never a side flyout — the Roboco mobile rule).
 */
@Immutable
data class MenuSection(
    /** Required (non-blank) when `inline == false` (the back row needs it). */
    val title: String? = null,
    val icon: MenuIcon? = null,
    val inline: Boolean,
    val children: List<MenuElement>,
) : MenuElement

/**
 * The leading-slot glyph of a row or section header.
 *
 * - [Symbol] — a ticket 06 registry glyph, tinted with the row's icon ink;
 * - [Brand] — a ticket 13 harness mark: `claude-code` / `mock` keep their
 *   Claude orange, every other mark takes the icon ink;
 * - [Custom] — caller-drawn (ticket 12's `ProjectTile`): receives the icon
 *   ink and decides itself (the tile ignores it). Never tinted by the menu.
 */
sealed interface MenuIcon {
    data class Symbol(val icon: RobocoIcon) : MenuIcon
    data class Brand(val harness: String) : MenuIcon
    class Custom(val content: @Composable (ink: Color) -> Unit) : MenuIcon
}

// ---------------------------------------------------------------------------
// Display lines: one menu level flattened for drawing
// ---------------------------------------------------------------------------

/**
 * One drawable line of a menu level, produced by [flattenLevel] and
 * consumed by [GlassMenuPanel]. Pure data: [GlassMenuPanel] renders a list
 * of these statelessly, which is what the gallery panels and the Roborazzi
 * fixtures are.
 */
sealed interface MenuLine {
    /** The root `Menu.title`. Root level only. */
    data class Title(val text: String) : MenuLine

    /**
     * The drill-in back row: the first line of a drilled level. [text] is
     * the section's own title (the `ChevronBackward` glyph provides the
     * "‹"); [backTo] is the a11y label — the parent level's title, else the
     * menu title, else "menu" — filled in by [GlassMenuContent] where the
     * level stack is known.
     */
    data class Back(val text: String, val backTo: String? = null) : MenuLine

    /** An inline section's caption, when the section is titled or has an icon. */
    data class Header(val text: String?, val icon: MenuIcon?) : MenuLine

    /** The 8 dp block with the centred 1 px hairline between two groups. */
    data object Separator : MenuLine

    data class Row(val item: MenuItem) : MenuLine

    /** A non-inline [MenuSection] row, with the trailing chevron. */
    data class Submenu(val section: MenuSection) : MenuLine

    /** A pending [MenuElement.Deferred]'s "Loading…" placeholder row. */
    data class Loading(val key: MenuElement.Deferred) : MenuLine
}

// ---------------------------------------------------------------------------
// Flattening (pure, unit-tested in GlassMenuModelTest)
// ---------------------------------------------------------------------------

/**
 * One display group: either a **run** of consecutive non-inline lines, or
 * the lines of one inline section (with its optional header). A group is
 * empty — and draws nothing, header included — unless it holds at least
 * one [MenuLine.Row], [MenuLine.Submenu] or [MenuLine.Loading].
 */
private class MenuGroup(val header: MenuLine.Header?) {
    val lines = mutableListOf<MenuLine>()

    val nonEmpty: Boolean
        get() = lines.hasRowLines()
}

/**
 * The flattening state machine. `current` is the group that following
 * lines join: the open run group, or (while walking an inline section's
 * children) that inline group.
 */
private class MenuFlattener(private val resolved: (MenuElement.Deferred) -> List<MenuElement>?) {

    val groups = mutableListOf<MenuGroup>()

    private var current: MenuGroup? = null

    private fun target(): MenuGroup {
        current?.let { return it }
        val run = MenuGroup(null)
        groups += run
        current = run
        return run
    }

    /**
     * Walks [elements].
     *
     * @param nestedInline true while walking the children of an inline
     *   section: a directly-written nested inline section joins the same
     *   group (its header emitted in order, no separator). False at the
     *   top level of a level and for a deferred splice: there an inline
     *   section becomes its own group.
     */
    fun flatten(elements: List<MenuElement>, nestedInline: Boolean) {
        for (element in elements) {
            when (element) {
                is MenuItem -> target().lines += MenuLine.Row(element)

                is MenuSection -> if (!element.inline) {
                    target().lines += MenuLine.Submenu(element)
                } else if (nestedInline) {
                    val group = target()
                    headerOf(element)?.let { group.lines += it }
                    flatten(element.children, nestedInline = true)
                } else {
                    current = null
                    val group = MenuGroup(headerOf(element))
                    groups += group
                    current = group
                    flatten(element.children, nestedInline = true)
                    current = null
                }

                is MenuElement.Deferred -> {
                    val result = resolved(element)
                    if (result == null) {
                        // Pending: exactly one Loading line where it sits,
                        // including inside an inline section (Checkout).
                        target().lines += MenuLine.Loading(element)
                    } else {
                        // Splice: the returned items join the surrounding
                        // run; inline sections it returns become their own
                        // groups.
                        flatten(result, nestedInline = false)
                    }
                }
            }
        }
    }

    private fun headerOf(section: MenuSection): MenuLine.Header? =
        if (!section.title.isNullOrBlank() || section.icon != null) {
            MenuLine.Header(section.title, section.icon)
        } else {
            null
        }
}

/**
 * Flattens one menu level into [MenuLine]s. Pure: `resolved` reports a
 * deferred's current result (`null` = pending); nothing here reads
 * snapshot state, so the plain-JVM [GlassMenuModelTest] pins every rule.
 *
 * Rules (ticket #15 §Flattening):
 *  1. the level's children split into groups — runs of consecutive
 *     non-inline elements, and one group per inline section (a resolved
 *     deferred is spliced in place, recursively);
 *  2. a [MenuLine.Separator] goes **between** two non-empty groups only —
 *     never first, last or doubled;
 *  3. an inline group starts with its [MenuLine.Header] when titled or
 *     iconed, and only when the group is non-empty (a pending deferred
 *     counts: its Loading line makes it non-empty);
 *  4. inline sections nested directly inside inline sections join the same
 *     group, headers in order;
 *  5. a pending deferred emits exactly one [MenuLine.Loading];
 *  6. a non-inline section emits [MenuLine.Submenu]; its children flatten
 *     only when the user drills in;
 *  7. the leading slot is reserved per level — [reservesLeadingSlot].
 */
internal fun flattenLevel(
    title: String?,
    elements: List<MenuElement>,
    resolved: (MenuElement.Deferred) -> List<MenuElement>?,
    isRoot: Boolean,
    backTitle: String?,
): List<MenuLine> {
    val flattener = MenuFlattener(resolved)
    flattener.flatten(elements, nestedInline = false)

    val body = mutableListOf<MenuLine>()
    var emittedNonEmpty = false
    for (group in flattener.groups) {
        if (!group.nonEmpty) continue
        if (emittedNonEmpty) body += MenuLine.Separator
        group.header?.let { body += it }
        body += group.lines
        emittedNonEmpty = true
    }

    val lines = mutableListOf<MenuLine>()
    if (isRoot) {
        title?.takeIf { it.isNotBlank() }?.let { lines += MenuLine.Title(it) }
    } else {
        lines += MenuLine.Back(backTitle ?: "")
        if (body.isNotEmpty()) lines += MenuLine.Separator
    }
    lines += body
    return lines
}

/**
 * True when the lines hold at least one row, submenu row or pending
 * loading line — the three shapes that count as "content" for group
 * emptiness, [Menu.hasContent] and the load-emptied-root close check.
 */
internal fun List<MenuLine>.hasRowLines(): Boolean =
    any { it is MenuLine.Row || it is MenuLine.Submenu || it is MenuLine.Loading }

/**
 * Rule 7: the leading slot is reserved when any [MenuLine.Row] in the level
 * is checked or has an icon, or any [MenuLine.Submenu] / [MenuLine.Header]
 * has an icon. When reserved, every row's text starts at the 61 dp text
 * column; otherwise at 20 dp.
 */
internal fun reservesLeadingSlot(lines: List<MenuLine>): Boolean = lines.any { line ->
    when (line) {
        is MenuLine.Row -> line.item.checked || line.item.icon != null
        is MenuLine.Submenu -> line.section.icon != null
        is MenuLine.Header -> line.icon != null
        else -> false
    }
}

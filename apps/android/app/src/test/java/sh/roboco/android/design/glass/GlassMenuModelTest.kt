package sh.roboco.android.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.RobocoIcon

/**
 * The menu model's flattening rules (android-ui-parity #15, `GlassMenuModel.kt`):
 * groups, separators, headers, pending deferreds, splices, drill-in lines
 * and the leading-slot rule — pure functions, plain JVM, no Robolectric.
 *
 * Lines are asserted through [shape] strings: `MenuItem` equality would
 * compare the action lambdas by identity.
 */
class GlassMenuModelTest {

    // ------------------------------------------------------------- helpers

    private fun item(
        title: String,
        icon: MenuIcon? = null,
        checked: Boolean = false,
        disabled: Boolean = false,
        destructive: Boolean = false,
    ) = MenuItem(
        title = title,
        icon = icon,
        checked = checked,
        disabled = disabled,
        destructive = destructive,
        action = {},
    )

    private fun symbol() = MenuIcon.Symbol(RobocoIcon.Vector(1))

    private fun inline(title: String? = null, vararg children: MenuElement) =
        MenuSection(title = title, inline = true, children = children.toList())

    private fun section(title: String, vararg children: MenuElement) =
        MenuSection(title = title, icon = null, inline = false, children = children.toList())

    private fun pending() = MenuElement.Deferred(cached = false) { emptyList() }

    /**
     * Results of the deferreds built by [resolving], as the host would report
     * them once loaded. [flattenLevel] never runs `load` itself (it is pure),
     * so a "resolved" deferred has to be reported through `resolved`.
     */
    private val results = mutableMapOf<MenuElement.Deferred, List<MenuElement>>()

    private fun resolving(vararg children: MenuElement): MenuElement.Deferred {
        val deferred = MenuElement.Deferred(cached = false) { children.toList() }
        results[deferred] = children.toList()
        return deferred
    }

    private fun root(vararg children: MenuElement, title: String? = null) =
        flattenLevel(title, children.toList(), resolved = { results[it] }, isRoot = true, backTitle = null)

    private fun drilled(title: String, vararg children: MenuElement) =
        flattenLevel(title, children.toList(), resolved = { results[it] }, isRoot = false, backTitle = title)

    private fun shape(lines: List<MenuLine>): List<String> = lines.map { line ->
        when (line) {
            is MenuLine.Title -> "Title(${line.text})"
            is MenuLine.Back -> "Back(${line.text})"
            is MenuLine.Header -> "Header(${line.text})"
            MenuLine.Separator -> "Sep"
            is MenuLine.Row -> "Row(${line.item.title})"
            is MenuLine.Submenu -> "Submenu(${line.section.title})"
            is MenuLine.Loading -> "Loading"
        }
    }

    // ---------------------------------------------------------- rule 1: groups

    @Test
    fun aRunWithConsecutiveNonInlineElementsIsOneGroup() {
        val lines = root(item("Edit"), item("Move Up"), item("Move Down"))
        assertEquals(listOf("Row(Edit)", "Row(Move Up)", "Row(Move Down)"), shape(lines))
    }

    @Test
    fun anInlineSectionIsItsOwnGroupWithASeparatorOnBothSides() {
        val lines = root(
            item("New Section…"),
            inline(null, item("Bugs")),
            item("Archived"),
        )
        // Rule 3: the section is untitled → no header, but still a group.
        assertEquals(
            listOf("Row(New Section…)", "Sep", "Row(Bugs)", "Sep", "Row(Archived)"),
            shape(lines),
        )
    }

    // ------------------------------------------------------ rule 2: separators

    @Test
    fun aSeparatorIsNeverFirstOrLast() {
        assertEquals(emptyList<String>(), shape(root()))
        assertEquals(listOf("Row(a)"), shape(root(item("a"))))
        // A lone pending deferred: one group, no separator around it.
        assertEquals(listOf("Loading"), shape(root(pending())))
    }

    @Test
    fun aSeparatorIsNeverDoubledAroundAnEmptyGroup() {
        // The middle inline resolves to nothing: no header, no group, no
        // doubled separator.
        val lines = root(
            item("a"),
            inline("Empty"),
            item("b"),
        )
        assertEquals(listOf("Row(a)", "Sep", "Row(b)"), shape(lines))
    }

    // ------------------------------------------------- rule 3: inline headers

    @Test
    fun aTitledInlineSectionStartsWithItsHeader() {
        val lines = root(inline("Claude Code", item("Opus 4.5")))
        assertEquals(listOf("Header(Claude Code)", "Row(Opus 4.5)"), shape(lines))
    }

    @Test
    fun anIconedButUntitledInlineSectionAlsoStartsWithItsHeader() {
        val lines = root(inline(null, item("GPT-5")).let { it.copy(icon = symbol()) })
        assertEquals(listOf("Header(null)", "Row(GPT-5)"), shape(lines))
    }

    @Test
    fun anEmptyInlineSectionDropsItsHeaderAndSeparators() {
        val lines = root(item("a"), inline("Branch"), item("b"))
        assertEquals(listOf("Row(a)", "Sep", "Row(b)"), shape(lines))
    }

    // ---------------------------------------------- rule 4: nested inline

    @Test
    fun inlineSectionsNestedInInlineJoinTheSameGroupHeadersInOrder() {
        val inner = inline("Inner", item("i1"))
        val outer = inline("Outer", item("o1"), inner, item("o2"))
        val lines = root(outer)
        assertEquals(
            listOf("Header(Outer)", "Row(o1)", "Header(Inner)", "Row(i1)", "Row(o2)"),
            shape(lines),
        )
    }

    // ------------------------------------------------- rule 5: pending deferred

    @Test
    fun aPendingDeferredEmitsExactlyOneLoadingLineWhereItSits() {
        val lines = root(item("a"), pending(), item("b"))
        assertEquals(listOf("Row(a)", "Loading", "Row(b)"), shape(lines))
    }

    @Test
    fun aPendingDeferredInsideAnInlineSectionKeepsHeaderAndLoading() {
        // The Checkout "Branch" case.
        val lines = root(inline("Branch", pending()))
        assertEquals(listOf("Header(Branch)", "Loading"), shape(lines))
    }

    // ------------------------------------------------- rule 1c: splicing

    @Test
    fun aResolvedDeferredsItemsJoinTheSurroundingRun() {
        val lines = root(item("a"), resolving(item("b"), item("c")), item("d"))
        assertEquals(listOf("Row(a)", "Row(b)", "Row(c)", "Row(d)"), shape(lines))
    }

    @Test
    fun inlineSectionsReturnedByADeferredBecomeTheirOwnGroups() {
        val lines = root(
            item("a"),
            resolving(item("b"), inline("S", item("c"))),
            item("d"),
        )
        assertEquals(
            listOf("Row(a)", "Row(b)", "Sep", "Header(S)", "Row(c)", "Sep", "Row(d)"),
            shape(lines),
        )
    }

    @Test
    fun aDeferredResolvingToEmptyDisappearsWithItsInlineHeader() {
        val lines = root(
            item("a"),
            inline("Branch", resolving()),
            item("b"),
        )
        assertEquals(listOf("Row(a)", "Sep", "Row(b)"), shape(lines))
    }

    @Test
    fun aResolvedDeferredIsLookedUpThroughTheResolvedCallback() {
        val deferred = MenuElement.Deferred(cached = false) { listOf(item("x")) }
        val lines = flattenLevel(
            null,
            listOf(item("a"), deferred),
            resolved = { if (it === deferred) listOf(item("x")) else null },
            isRoot = true,
            backTitle = null,
        )
        assertEquals(listOf("Row(a)", "Row(x)"), shape(lines))
    }

    // ------------------------------------------------- rule 6: drill-in

    @Test
    fun aNonInlineSectionEmitsASubmenuRowAndNeverFlattensItsChildren() {
        val lines = root(item("Pin"), section("Move to Section", item("Bugs")), item("Rename…"))
        assertEquals(
            listOf("Row(Pin)", "Submenu(Move to Section)", "Row(Rename…)"),
            shape(lines),
        )
    }

    @Test
    fun aSubmenuRowKeepsItsIconForTheLeadingSlot() {
        val submenu = MenuSection(
            title = "Move to Section",
            icon = symbol(),
            inline = false,
            children = emptyList(),
        )
        val lines = root(submenu)
        assertEquals(listOf("Submenu(Move to Section)"), shape(lines))
        assertTrue(reservesLeadingSlot(lines))
    }

    // ---------------------------------------------------- the level headers

    @Test
    fun theRootTitleIsEmittedOnlyWhenNonBlank() {
        assertEquals(listOf("Title(Menu)", "Row(a)"), shape(root(item("a"), title = "Menu")))
        assertEquals(listOf("Row(a)"), shape(root(item("a"), title = "  ")))
        assertEquals(listOf("Row(a)"), shape(root(item("a"), title = null)))
    }

    @Test
    fun aDrilledLevelStartsWithBackThenSeparatorThenTheChildren() {
        val lines = drilled("Move to Section", item("Bugs"), item("No Section"))
        assertEquals(
            listOf("Back(Move to Section)", "Sep", "Row(Bugs)", "Row(No Section)"),
            shape(lines),
        )
    }

    @Test
    fun aDrilledLevelWithNoBodyIsOnlyTheBackRow() {
        val lines = drilled("Move to Section")
        assertEquals(listOf("Back(Move to Section)"), shape(lines))
    }

    @Test
    fun aDrilledLevelNeverEmitsTheTitleLine() {
        val lines = drilled("Move to Section", item("Bugs"))
        assertFalse(shape(lines).contains("Title(Move to Section)"))
    }

    @Test
    fun aDrilledLevelWithTwoGroupsSeparatesThem() {
        val lines = drilled("S", item("a"), inline("G", item("b")))
        assertEquals(listOf("Back(S)", "Sep", "Row(a)", "Sep", "Header(G)", "Row(b)"), shape(lines))
    }

    // ---------------------------------------------------- rule 7: the slot

    @Test
    fun theSlotIsReservedForIconedOrCheckedRows() {
        assertTrue(reservesLeadingSlot(root(item("a", icon = symbol()))))
        assertTrue(reservesLeadingSlot(root(item("a", checked = true))))
        assertFalse(reservesLeadingSlot(root(item("a"))))
        assertFalse(reservesLeadingSlot(root(item("a"), item("b"))))
    }

    @Test
    fun theSlotIsReservedForIconedHeadersAndSubmenusButNotLoadingRows() {
        assertTrue(reservesLeadingSlot(root(inline(null, item("a")).let { it.copy(icon = symbol()) })))
        assertTrue(reservesLeadingSlot(root(section("s", item("x")).let { it.copy(icon = symbol()) })))
        assertFalse(reservesLeadingSlot(root(pending())))
    }

    @Test
    fun disabledAndDestructiveFlagsDoNotAffectTheSlot() {
        assertFalse(reservesLeadingSlot(root(item("a", disabled = true, destructive = true))))
    }

    // ---------------------------------------------------- hasContent

    @Test
    fun anEmptyMenuHasNoContent() {
        assertFalse(Menu(null, emptyList()).hasContent())
    }

    @Test
    fun aMenuWithOnlyABlankTitleHasNoContent() {
        assertFalse(Menu("  ", emptyList()).hasContent())
    }

    @Test
    fun anyRowSubmenuOrPendingDeferredCountsAsContent() {
        assertTrue(Menu(null, listOf(item("a"))).hasContent())
        assertTrue(Menu(null, listOf(section("s", item("x")))).hasContent())
        // A pending deferred counts: nothing is resolved at open time.
        assertTrue(Menu(null, listOf(pending())).hasContent())
    }

    @Test
    fun aDeferredInsideAnInlineSectionCountsAsContent() {
        assertTrue(Menu(null, listOf(inline("Branch", pending()))).hasContent())
    }

    @Test
    fun anEmptyInlineSectionIsNotContent() {
        assertFalse(Menu(null, listOf(inline("X"))).hasContent())
    }
}

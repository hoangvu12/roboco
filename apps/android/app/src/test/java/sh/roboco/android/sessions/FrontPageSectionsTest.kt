package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage

/**
 * `FrontPageSections` (android-ui-parity #40): the port of
 * `SessionListController.buildSections()` — the section order, the Recent
 * header rule, the always-present Recent, the meta map, the collision
 * suffix, `collapsedIds` and `collapsibleIds`. Pure and plain-JVM.
 *
 * Expected strings are explicit literals from the ticket's Spec table —
 * the middle-dot assertions use `\u00B7` escapes so no editor can mangle
 * the separator along with the code under test.
 */
class FrontPageSectionsTest {

    // ------------------------------------------------------------ fixtures

    /** The 1-off.png fixture: 2 pins, "P0" (2), "Mobile" (2), 4 recent rows. */
    private fun oneOff(): FrontPage = front(
        pinned = listOf(row("p1"), row("p2")),
        sections = listOf(
            section("e1:s1", name = "P0", rows = listOf(row("a"), row("b"))),
            section("e1:s2", name = "Mobile", rows = listOf(row("c"), row("d"))),
        ),
        recent = listOf(
            row("r1", indicator = ChatIndicator.WORKING),
            row("r2"),
            row("r3"),
            row("r4"),
        ),
    )

    /** The 3-on.png fixture: the same sections, recent grouped by project/home. */
    private fun threeOn(): FrontPage = front(
        pinned = listOf(row("p1"), row("p2")),
        sections = listOf(
            section("e1:s1", name = "P0", rows = listOf(row("a"), row("b"))),
            section("e1:s2", name = "Mobile", rows = listOf(row("c"), row("d"))),
        ),
        recent = listOf(row("r1"), row("r2"), row("r3"), row("r4")),
        groups = listOf(
            group(id = "project:repo:zeron", name = "zeron", rows = listOf(row("r1"))),
            group(id = "home:mbp", name = "Home \u00B7 MacBook Pro", rows = listOf(row("r2"))),
            group(id = "project:repo:edge", name = "edge", rows = listOf(row("r3"))),
            group(id = "project:repo:blog", name = "blog", rows = listOf(row("r4"))),
        ),
    )

    // --------------------------------------------------------- the order

    @Test
    fun theOneOffFixtureDrawsPinnedSectionsRecent() {
        val built = FrontPageSections.build(
            oneOff(),
            groupByProject = false,
            engineNames = mapOf("A" to "Studio"),
        )
        assertEquals(
            listOf(FrontPageSections.PINNED, "e1:s1", "e1:s2", FrontPageSections.RECENT),
            built.sections.map { it.id },
        )
        assertEquals(listOf("Pinned", "P0", "Mobile", "Recent"), built.sections.map { it.header })
        assertEquals(listOf(2, 2, 2, 4), built.sections.map { it.rows.size })
        // iOS `buildSections` passes `[]` for folders, every section.
        assertTrue(built.sections.all { it.folders.isEmpty() })
    }

    @Test
    fun noPinsAndNoSectionsLeaveAHeaderlessRecent() {
        val recent = listOf(row("r1"), row("r2"), row("r3"))
        val built = FrontPageSections.build(front(recent = recent), false, emptyMap())
        assertEquals(listOf(FrontPageSections.RECENT), built.sections.map { it.id })
        assertNull(built.sections.single().header)
        assertEquals(recent, built.sections.single().rows)

        // A header-less Recent is never a member of collapsedIds, even
        // when "recent" is in the phone-local set.
        val collapsed = FrontPageSections.collapsedIds(built.sections) { id -> id == "recent" }
        assertFalse(FrontPageSections.RECENT in collapsed)
        assertTrue(collapsed.isEmpty())
    }

    @Test
    fun anEmptySectionStillCountsForTheRecentHeader() {
        val built = FrontPageSections.build(
            front(sections = listOf(section("e1:s1", name = "Later")), recent = emptyList()),
            false,
            emptyMap(),
        )
        assertEquals(listOf("e1:s1", FrontPageSections.RECENT), built.sections.map { it.id })
        assertEquals(listOf("Later", "Recent"), built.sections.map { it.header })
        assertEquals(listOf(0, 0), built.sections.map { it.rows.size })
    }

    @Test
    fun groupingOnReplacesRecentWithTheGroupsInCoreOrder() {
        val built = FrontPageSections.build(threeOn(), true, mapOf("A" to "Studio"))
        // The 3-on fixture has two pins, so Pinned still leads (Spec row 1:
        // present whenever pins exist, grouping or not); the groups replace
        // only Recent.
        assertEquals(
            listOf(
                FrontPageSections.PINNED, "e1:s1", "e1:s2",
                "project:repo:zeron", "home:mbp", "project:repo:edge", "project:repo:blog",
            ),
            built.sections.map { it.id },
        )
        assertEquals(
            listOf("Pinned", "P0", "Mobile", "zeron", "Home \u00B7 MacBook Pro", "edge", "blog"),
            built.sections.map { it.header },
        )
        // Groups always have headers; each keeps recency order.
        assertTrue(built.sections.drop(3).all { it.header != null })
        assertTrue(built.sections.drop(3).all { it.rows.size == 1 })
    }

    @Test
    fun groupingOnWithNoRecentRowsEmitsNothingAfterTheSections() {
        val built = FrontPageSections.build(
            front(sections = listOf(section("e1:s1", name = "P0")), groups = emptyList()),
            true,
            emptyMap(),
        )
        assertEquals(listOf("e1:s1"), built.sections.map { it.id })
    }

    // ------------------------------------------------------- the rows

    @Test
    fun everySectionCarriesAllItsRowsUnmapped() {
        val f = oneOff()
        val built = FrontPageSections.build(f, false, emptyMap())
        // The FFI records themselves, in core order — 41 maps them.
        assertSame(f.pinned[0], built.sections[0].rows[0])
        assertSame(f.sections[0].sessions[1], built.sections[1].rows[1])
        assertSame(f.recent[3], built.sections[3].rows[3])
        assertEquals("p1", built.sections[0].rows[0].id)
    }

    @Test
    fun aCollapsedSectionKeepsItsRowsAndIsNeverDeduplicated() {
        val shared = row("both")
        val f = front(
            sections = listOf(section("e1:s1", name = "S", collapsed = true, rows = listOf(shared, row("x")))),
            recent = listOf(shared, row("y")),
        )
        val built = FrontPageSections.build(f, false, emptyMap())
        val collapsedIds = FrontPageSections.collapsedIds(built.sections) { id -> id == "e1:s1" }
        assertEquals(setOf("e1:s1"), collapsedIds)
        // A collapsed section still carries all its rows…
        assertEquals(listOf("both", "x"), built.sections[0].rows.map { it.id })
        // …and a session in both a section and Recent appears in both lists.
        assertEquals(listOf("both", "y"), built.sections[1].rows.map { it.id })
    }

    // --------------------------------------------------------- the meta

    @Test
    fun metaMapsEveryKind() {
        val built = FrontPageSections.build(
            front(
                pinned = listOf(row("p1")),
                sections = listOf(section("e1:s1", name = "P0", engineId = "A")),
                recent = listOf(row("r1")),
            ),
            false,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals(FrontSectionKind.Pinned, built.meta[FrontPageSections.PINNED]!!.kind)
        assertNull(built.meta[FrontPageSections.PINNED]!!.rawName)
        assertNull(built.meta[FrontPageSections.PINNED]!!.engineId)

        val user = built.meta["e1:s1"]!!
        assertEquals(FrontSectionKind.User, user.kind)
        assertEquals("P0", user.rawName)
        assertEquals("A", user.engineId)

        assertEquals(FrontSectionKind.Recent, built.meta[FrontPageSections.RECENT]!!.kind)
        assertNull(built.meta[FrontPageSections.RECENT]!!.rawName)
        assertNull(built.meta[FrontPageSections.RECENT]!!.engineId)
    }

    @Test
    fun groupMetaCarriesTheSingleEngineOrNullWhenRowsSpanEngines() {
        val built = FrontPageSections.build(
            front(
                groups = listOf(
                    group(id = "project:x", name = "x", rows = listOf(row("r1", engineId = "A"), row("r2", engineId = "A"))),
                    group(id = "project:y", name = "y", rows = listOf(row("r3", engineId = "A"), row("r4", engineId = "B"))),
                ),
            ),
            true,
            emptyMap(),
        )
        val single = built.meta["project:x"]!!
        assertEquals(FrontSectionKind.ProjectGroup, single.kind)
        assertEquals("x", single.rawName)
        assertEquals("A", single.engineId)

        val spanning = built.meta["project:y"]!!
        assertEquals(FrontSectionKind.ProjectGroup, spanning.kind)
        assertEquals("y", spanning.rawName)
        assertNull(spanning.engineId)
    }

    // -------------------------------------------------- the collision suffix

    @Test
    fun collidingNamesAcrossEnginesGetTheEngineSuffix() {
        val built = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = "Mobile", engineId = "A"),
                    section("e2:s1", name = "mobile", engineId = "B"),
                ),
            ),
            false,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals("Mobile \u00B7 Studio", built.sections[0].header)
        assertEquals("mobile \u00B7 Laptop", built.sections[1].header)
        // The suffix never leaks into the raw name (46's Rename prefill).
        assertEquals("Mobile", built.meta["e1:s1"]!!.rawName)
        assertEquals("mobile", built.meta["e2:s1"]!!.rawName)

        // The compare trims: surrounding spaces do not tell sections apart.
        val padded = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = " Mobile ", engineId = "A"),
                    section("e2:s1", name = "mobile", engineId = "B"),
                ),
            ),
            false,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals(" Mobile  \u00B7 Studio", padded.sections[0].header)
        assertEquals("mobile \u00B7 Laptop", padded.sections[1].header)
    }

    @Test
    fun sameEngineDuplicatesStayUnchanged() {
        val built = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = "Mobile", engineId = "A"),
                    section("e1:s2", name = "Mobile", engineId = "A"),
                ),
            ),
            false,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals("Mobile", built.sections[0].header)
        assertEquals("Mobile", built.sections[1].header)
    }

    @Test
    fun onePairedEngineNeverSuffixes() {
        val built = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = "Mobile", engineId = "A"),
                    section("e2:s1", name = "mobile", engineId = "B"),
                ),
            ),
            false,
            mapOf("A" to "Studio"),
        )
        assertEquals("Mobile", built.sections[0].header)
        assertEquals("mobile", built.sections[1].header)
    }

    @Test
    fun pinnedAndRecentAreNeverSuffixed() {
        // A user section named like Pinned/Recent collides with nothing:
        // those two are never candidates.
        val built = FrontPageSections.build(
            front(
                pinned = listOf(row("p1")),
                sections = listOf(
                    section("e1:s1", name = "Recent", engineId = "A"),
                    section("e2:s1", name = "Pinned", engineId = "B"),
                ),
                recent = listOf(row("r1")),
            ),
            false,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals("Pinned", built.sections[0].header)
        assertEquals("Recent", built.sections[1].header)
        assertEquals("Pinned", built.sections[2].header)
        assertEquals("Recent", built.sections[3].header)
    }

    @Test
    fun aGroupSpanningEnginesIsNeverSuffixed() {
        val built = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = "edge", engineId = "A"),
                    section("e2:s1", name = "edge", engineId = "B"),
                ),
                groups = listOf(
                    // "edge" across A and B: no engine, never a candidate —
                    // it stays plain while the sections collide.
                    group(id = "project:edge", name = "edge", rows = listOf(row("r1", engineId = "A"), row("r2", engineId = "B"))),
                ),
            ),
            true,
            mapOf("A" to "Studio", "B" to "Laptop"),
        )
        assertEquals("edge \u00B7 Studio", built.sections[0].header)
        assertEquals("edge \u00B7 Laptop", built.sections[1].header)
        assertEquals("edge", built.sections[2].header)
    }

    @Test
    fun anUnknownEngineNameLeavesTheTitleUnchanged() {
        val built = FrontPageSections.build(
            front(
                sections = listOf(
                    section("e1:s1", name = "Mobile", engineId = "A"),
                    section("e2:s1", name = "mobile", engineId = "B"),
                ),
            ),
            false,
            mapOf("A" to "Studio", "C" to "Third"),
        )
        assertEquals("Mobile \u00B7 Studio", built.sections[0].header)
        assertEquals("mobile", built.sections[1].header)
    }

    // ------------------------------------------------------ collapsed ids

    @Test
    fun collapsedIdsCoversHeadedIdsOnly() {
        val built = FrontPageSections.build(
            front(
                pinned = listOf(row("p1")),
                sections = listOf(section("e1:s1", name = "P0")),
                recent = listOf(row("r1")),
            ),
            false,
            emptyMap(),
        )
        // Pinned and a user section precede Recent here, so Recent is headed
        // and its phone-local fold counts (Spec: `collapsedIds` = every
        // headed section whose fold is true; only a header-less Recent is
        // excluded). The unfolded Pinned stays out.
        val collapsed = FrontPageSections.collapsedIds(built.sections) { id -> id == "e1:s1" || id == "recent" }
        assertEquals(setOf("e1:s1", FrontPageSections.RECENT), collapsed)

        // The same fold set over a header-less Recent (nothing precedes it)
        // keeps "recent" out.
        val headerless = FrontPageSections.build(front(recent = listOf(row("r1"))), false, emptyMap())
        assertEquals(
            emptySet<String>(),
            FrontPageSections.collapsedIds(headerless.sections) { id -> id == "e1:s1" || id == "recent" },
        )
    }

    @Test
    fun collapsibleIdsNeverIncludeRecent() {
        val f = oneOff()
        assertEquals(
            listOf(FrontPageSections.PINNED, "e1:s1", "e1:s2"),
            FrontPageSections.collapsibleIds(f, groupByProject = false),
        )
        // No pins: no "pinned" id either.
        assertEquals(
            listOf("e1:s1", "e1:s2"),
            FrontPageSections.collapsibleIds(f.copy(pinned = emptyList()), false),
        )
        // Groups only while grouping is on.
        val grouped = threeOn()
        assertEquals(
            listOf(FrontPageSections.PINNED, "e1:s1", "e1:s2", "project:repo:zeron", "home:mbp", "project:repo:edge", "project:repo:blog"),
            FrontPageSections.collapsibleIds(grouped, groupByProject = true),
        )
        assertEquals(
            listOf(FrontPageSections.PINNED, "e1:s1", "e1:s2"),
            FrontPageSections.collapsibleIds(grouped, groupByProject = false),
        )
    }
}

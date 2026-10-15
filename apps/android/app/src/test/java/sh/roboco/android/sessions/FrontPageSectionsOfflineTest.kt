package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage

/**
 * The offline half of the front page (android-ui-parity #50):
 * [FrontPageSections.offlineOverrides] (the pure `home:*` rule) and its
 * hand-off through 41's [buildSessionListItems] — the override replaces
 * the header's live glyph only; the count and the rows are untouched.
 *
 * Plain JUnit like ticket 40's tests: the FFI records are plain Kotlin
 * data classes, and `toVm` is passed explicitly (the `HomeColorIndex`
 * default resolves through the native core).
 */
class FrontPageSectionsOfflineTest {

    // ------------------------------------------------------------ fixtures

    /**
     * Group by Project on, two engines: A owns "Home · Studio" (one
     * working row) and the "repo" project group; B owns "Home · Laptop".
     */
    private fun threeOn(): FrontPage = front(
        sections = listOf(section("e1:s1", name = "P0", engineId = "A", rows = listOf(row("a")))),
        groups = listOf(
            group(
                id = "project:repo:edge",
                name = "edge",
                rows = listOf(row("p1", engineId = "A", indicator = ChatIndicator.WORKING)),
            ),
            group(
                id = "home:studio",
                name = "Home \u00B7 Studio",
                rows = listOf(row("h1", engineId = "A", indicator = ChatIndicator.WORKING)),
            ),
            group(
                id = "home:laptop",
                name = "Home \u00B7 Laptop",
                rows = listOf(row("h2", engineId = "B")),
            ),
        ),
    )

    private fun built() = FrontPageSections.build(threeOn(), groupByProject = true, engineNames = emptyMap())

    private fun items(liveOverrides: Map<String, SectionLive>) = buildSessionListItems(
        built().sections,
        collapsible = true,
        liveOverrides = liveOverrides,
        toVm = SessionListFixtures::toVm,
    )

    // ------------------------------------------------- the pure rule

    @Test
    fun offlineEnginesMapTheirHomeGroupsToOffline() {
        val meta = built().meta
        val overrides = FrontPageSections.offlineOverrides(meta, offlineEngines = setOf("A"))
        assertEquals(mapOf("home:studio" to SectionLive.Offline), overrides)
    }

    @Test
    fun anOnlineEngineGetsNoOverride() {
        val meta = built().meta
        // Engine B is online: only A's home group is overridden even when
        // the set is exactly {A}.
        assertEquals(
            mapOf("home:studio" to SectionLive.Offline),
            FrontPageSections.offlineOverrides(meta, setOf("A")),
        )
        assertEquals(
            mapOf("home:laptop" to SectionLive.Offline),
            FrontPageSections.offlineOverrides(meta, setOf("B")),
        )
        assertEquals(
            mapOf(
                "home:studio" to SectionLive.Offline,
                "home:laptop" to SectionLive.Offline,
            ),
            FrontPageSections.offlineOverrides(meta, setOf("A", "B")),
        )
    }

    @Test
    fun anEmptyOfflineSetGivesAnEmptyMap() {
        assertTrue(FrontPageSections.offlineOverrides(built().meta, emptySet()).isEmpty())
    }

    @Test
    fun projectGroupsAndUserSectionsAreNeverOverridden() {
        val meta = built().meta
        val overrides = FrontPageSections.offlineOverrides(meta, setOf("A", "B"))
        // The project group on A and the user section keep their normal
        // live derivation; only `home:*` ids appear.
        assertTrue("project:* stays out", overrides.keys.none { it.startsWith("project:") })
        assertTrue("user sections stay out", overrides.keys.none { it.startsWith("e1:") })
        assertTrue("pinned/recent stay out", overrides.keys.none { it == "pinned" || it == "recent" })
    }

    @Test
    fun groupingOffLeavesNoHomeIdsAndThusAnEmptyMap() {
        val noGroups = FrontPageSections.build(
            front(recent = listOf(row("r1", engineId = "A"))),
            groupByProject = false,
            engineNames = emptyMap(),
        )
        assertTrue(FrontPageSections.offlineOverrides(noGroups.meta, setOf("A")).isEmpty())
    }

    // -------------------------------------------- through 41's item builder

    @Test
    fun theOverrideReplacesTheGlyphButNothingElse() {
        // Without the override: the home:studio header's rows are Working,
        // so 41 derives the spinner.
        val plain = items(emptyMap())
        val plainHeader = plain.first { it.key == "h:home:studio" } as SessionListItem.Header
        assertEquals(SectionLive.Spinner, plainHeader.state.live)
        assertEquals(1, plainHeader.state.count)

        // With A offline: the glyph becomes Offline, the count and the
        // rows are unchanged, and the other headers keep their own live.
        val offline = items(FrontPageSections.offlineOverrides(built().meta, setOf("A")))
        val offlineHeader = offline.first { it.key == "h:home:studio" } as SessionListItem.Header
        assertEquals(SectionLive.Offline, offlineHeader.state.live)
        assertEquals(plainHeader.state.count, offlineHeader.state.count)
        assertEquals(plainHeader.state.title, offlineHeader.state.title)
        assertEquals(plainHeader.state.collapsed, offlineHeader.state.collapsed)

        // The rows are byte-identical: no badge, no dimming, same order.
        assertEquals(
            plain.map { it.key },
            offline.map { it.key },
        )

        // The project group on A (Working) keeps its spinner; the home
        // group on B keeps whatever its rows derive (an idle row: none).
        val project = offline.first { it.key == "h:project:repo:edge" } as SessionListItem.Header
        assertEquals(SectionLive.Spinner, project.state.live)
        val laptop = offline.first { it.key == "h:home:laptop" } as SessionListItem.Header
        assertNull(laptop.state.live)
    }

    @Test
    fun aFoldedOfflineHomeGroupStillCarriesTheOfflineGlyph() {
        // The override is not conditional on the fold: 41 emits the header
        // either way, and the header state carries the same live value.
        val folded = buildSessionListItems(
            built().sections,
            collapsible = true,
            collapsedIds = setOf("home:studio"),
            liveOverrides = FrontPageSections.offlineOverrides(built().meta, setOf("A")),
            toVm = SessionListFixtures::toVm,
        )
        val header = folded.first { it.key == "h:home:studio" } as SessionListItem.Header
        assertTrue(header.state.collapsed)
        assertEquals(SectionLive.Offline, header.state.live)
        assertTrue("a folded section emits no rows", "s:h1" !in folded.map { it.key })
    }
}

package sh.roboco.android.sessions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.SessionRow

/**
 * The item model (android-ui-parity #41) — the pure port of
 * `SessionListController.reload()`: order, keys, content types, the fold,
 * the header's count/live derivation (pre-fold, pre-dedupe),
 * first-occurrence de-dupe (including the collapsed-first case), plain
 * headers, folders and the duplicate-section skip.
 *
 * Plain JUnit like ticket 40's tests: the FFI records are plain Kotlin
 * data classes, and `toVm` is passed explicitly (the lazy
 * `HomeColorIndex` default resolves through the native core, which the
 * plain JVM cannot load — the same property `WorkspaceFixtures` relies
 * on). `android.util.Log` is swapped out via [sessionListLogW]; the
 * status derivations live in `SessionListStatusTest`.
 */
class SessionListItemsTest {

    /** The duplicate-section skip's log lines. */
    private val logged = mutableListOf<String>()
    private var savedLogW: ((String) -> Unit)? = null

    @Before
    fun swapLog() {
        savedLogW = sessionListLogW
        sessionListLogW = { logged += it }
    }

    @After
    fun restoreLog() {
        sessionListLogW = savedLogW!!
    }

    private fun items(
        sections: List<ListSection>,
        collapsible: Boolean = true,
        collapsedIds: Set<String> = emptySet(),
        liveOverrides: Map<String, SectionLive> = emptyMap(),
    ): List<SessionListItem> = buildSessionListItems(
        sections,
        collapsible,
        collapsedIds,
        liveOverrides,
        toVm = SessionListFixtures::toVm,
    )

    private val fixture = SessionListFixtures.sections()

    // ----------------------------------------------------------- the order

    @Test
    fun theFixtureBuildsTheReferenceOrder() {
        val built = items(fixture)
        assertEquals(
            listOf(
                "h:p0", "s:chat-tool-colors", "s:chat-hibernation",
                "h:mobile", "s:chat-scroll-pinning", "s:chat-keyboard",
                "h:recent", "s:chat-i18n", "s:chat-downloads", "s:chat-deploy", "s:chat-launch-post",
            ),
            built.map { it.key },
        )
        assertEquals(
            listOf(
                "sectionHeader", "session", "session",
                "sectionHeader", "session", "session",
                "sectionHeader", "session", "session", "session", "session",
            ),
            built.map { it.contentType },
        )
    }

    @Test
    fun sessionItemsCarryBothTheRecordAndItsVm() {
        val built = items(fixture)
        val session = built.first { it is SessionListItem.Session } as SessionListItem.Session
        assertSame(fixture[0].rows[0], session.row)
        assertEquals(SessionRowVM.from(fixture[0].rows[0], homeColorIndex = 6), session.vm)
        assertEquals("chat-tool-colors", session.vm.id)
        assertEquals("p0", session.sectionId)
    }

    // ------------------------------------------------------------ the fold

    @Test
    fun aCollapsedSectionEmitsOnlyItsHeader() {
        val built = items(fixture, collapsedIds = setOf("mobile"))
        val keys = built.map { it.key }
        assertTrue("the folded header stays", "h:mobile" in keys)
        assertFalse("its first row is gone", "s:chat-scroll-pinning" in keys)
        assertFalse("its second row is gone", "s:chat-keyboard" in keys)
        // Everything around it is untouched — and still emitted.
        assertTrue("h:p0" in keys && "s:chat-tool-colors" in keys)
        assertTrue("h:recent" in keys && "s:chat-i18n" in keys)
    }

    @Test
    fun aCollapsedHeaderCountsAllItsRows() {
        val built = items(fixture, collapsedIds = setOf("mobile"))
        val header = built.first { it.key == "h:mobile" } as SessionListItem.Header
        assertEquals(
            SectionHeaderState("mobile", "Mobile", 2, collapsed = true, live = null),
            header.state,
        )
    }

    @Test
    fun aHeaderlessSectionIgnoresItsFoldFlag() {
        val sections = listOf(ListSection(id = "recent", header = null, rows = listOf(row("a"))))
        val built = items(sections, collapsedIds = setOf("recent"))
        // No header to fold: the rows are emitted anyway.
        assertEquals(listOf("s:a"), built.map { it.key })
    }

    // --------------------------------------------------- count and live

    @Test
    fun theHeaderLiveDerivationPrefersSpinnerThenInput() {
        val working = listOf(row("w", indicator = ChatIndicator.WORKING))
        val awaiting = listOf(row("a", indicator = ChatIndicator.AWAITING_INPUT))
        val both = listOf(
            row("b", indicator = ChatIndicator.AWAITING_INPUT),
            row("w", indicator = ChatIndicator.WORKING),
        )
        assertEquals(SectionLive.Spinner, sectionLive(working.map(SessionListFixtures::toVm)))
        assertEquals(SectionLive.Input, sectionLive(awaiting.map(SessionListFixtures::toVm)))
        assertEquals(SectionLive.Spinner, sectionLive(both.map(SessionListFixtures::toVm)))
        assertNull(sectionLive(listOf(row("i")).map(SessionListFixtures::toVm)))

        fun header(rows: List<SessionRow>): SectionHeaderState =
            (items(listOf(ListSection("s", "S", rows = rows))).first() as SessionListItem.Header).state
        assertEquals(SectionLive.Spinner, header(working).live)
        assertEquals(SectionLive.Input, header(awaiting).live)
        assertNull(header(listOf(row("i"))).live)
    }

    @Test
    fun theFixtureWorkingVariantDerivesTheSpinnerOnP0() {
        val built = items(SessionListFixtures.sections(workingLive = true))
        assertEquals(SectionLive.Spinner, (built.first() as SessionListItem.Header).state.live)
        // The default (errored) fixture derives nothing.
        assertNull((items(fixture).first() as SessionListItem.Header).state.live)
    }

    @Test
    fun liveOverridesReplaceTheDerivedValue() {
        val working = listOf(ListSection("s", "S", rows = listOf(row("w", indicator = ChatIndicator.WORKING))))
        val overridden = items(working, liveOverrides = mapOf("s" to SectionLive.Input))
        assertEquals(SectionLive.Input, (overridden.first() as SessionListItem.Header).state.live)
        // An override also fills a section with no derived live value.
        val quiet = listOf(ListSection("q", "Q", rows = listOf(row("i"))))
        val filled = items(quiet, liveOverrides = mapOf("q" to SectionLive.Input))
        assertEquals(SectionLive.Input, (filled.first() as SessionListItem.Header).state.live)
    }

    @Test
    fun theHeaderCountsAndLivesOverAllRowsBeforeDedupe() {
        // "dup" is already emitted by "first"; "second" still counts it.
        val sections = listOf(
            ListSection("first", "First", rows = listOf(row("dup"))),
            ListSection(
                "second",
                "Second",
                rows = listOf(
                    row("dup", indicator = ChatIndicator.WORKING),
                    row("other"),
                ),
            ),
        )
        val built = items(sections)
        val second = built.first { it.key == "h:second" } as SessionListItem.Header
        assertEquals(2, second.state.count)
        assertEquals(SectionLive.Spinner, second.state.live)
        // …and "dup" itself is emitted once, under "first".
        assertEquals(1, built.count { it.key == "s:dup" })
        assertEquals("first", (built.first { it.key == "s:dup" } as SessionListItem.Session).sectionId)
    }

    // --------------------------------------------------------- the dedupe

    @Test
    fun aRowSharedByTwoSectionsIsEmittedOnceUnderTheFirst() {
        val sections = listOf(
            ListSection("pinned", "Pinned", rows = listOf(row("shared"), row("only-pinned"))),
            ListSection(id = "recent", header = null, rows = listOf(row("shared"), row("only-recent"))),
        )
        val built = items(sections)
        assertEquals(1, built.count { it.key == "s:shared" })
        assertEquals("pinned", (built.first { it.key == "s:shared" } as SessionListItem.Session).sectionId)
        assertTrue("s:only-pinned" in built.map { it.key })
        assertTrue("s:only-recent" in built.map { it.key })
    }

    @Test
    fun aCollapsedFirstOccurrenceYieldsToTheLaterSection() {
        val sections = listOf(
            ListSection("pinned", "Pinned", rows = listOf(row("shared"))),
            ListSection(id = "recent", header = null, rows = listOf(row("shared"))),
        )
        val built = items(sections, collapsedIds = setOf("pinned"))
        assertEquals(1, built.count { it.key == "s:shared" })
        assertEquals("recent", (built.first { it.key == "s:shared" } as SessionListItem.Session).sectionId)
    }

    // -------------------------------------------- plain headers, folders

    @Test
    fun nonCollapsibleHeadersArePlainHeaders() {
        val built = items(fixture, collapsible = false)
        val plain = built.filterIsInstance<SessionListItem.PlainHeader>()
        assertEquals(listOf("h:p0", "h:mobile", "h:recent"), plain.map { it.key })
        assertEquals(listOf("p0", "mobile", "recent"), plain.map { it.sectionId })
        assertEquals(listOf("P0", "Mobile", "Recent"), plain.map { it.title })
        assertTrue(built.map { it.contentType }.contains("plainHeader"))
        // No fold in non-collapsible mode: collapsedIds is ignored.
        val folded = items(fixture, collapsible = false, collapsedIds = setOf("mobile"))
        assertTrue("s:chat-scroll-pinning" in folded.map { it.key })
    }

    @Test
    fun foldersAreEmittedAfterTheHeaderAndDeduped() {
        val folder = FolderRowVM("f1", "Folder", 3, "folder")
        val sections = listOf(
            ListSection("s", "S", folders = listOf(folder), rows = listOf(row("a"))),
            ListSection(id = "s2", header = null, folders = listOf(folder), rows = emptyList()),
        )
        val built = items(sections)
        // The second section's copy of f1 is dropped: a repeated "f:f1"
        // LazyColumn key would crash the list (ticket 41: de-dupe first-wins).
        assertEquals(
            listOf("h:s", "f:f1", "s:a"),
            built.map { it.key },
        )
        val item = built.first { it.key == "f:f1" } as SessionListItem.Folder
        assertEquals("s", item.sectionId)
        assertEquals(folder, item.vm)
    }

    @Test
    fun duplicateSectionIdsAreSkippedWithALog() {
        val sections = listOf(
            ListSection("dup", "D", rows = listOf(row("a"))),
            ListSection("dup", "D", rows = listOf(row("b"))),
            ListSection("ok", "O", rows = listOf(row("c"))),
        )
        val built = items(sections)
        assertEquals(listOf("h:dup", "s:a", "h:ok", "s:c"), built.map { it.key })
        assertEquals(listOf("duplicate section dup"), logged)
    }
}

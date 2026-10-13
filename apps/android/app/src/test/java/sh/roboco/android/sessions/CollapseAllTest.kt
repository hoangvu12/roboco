package sh.roboco.android.sessions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.NoFeedback
import uniffi.roboco_core.FrontPage

/**
 * Collapse All (android-ui-parity #40) — the port of
 * `SessionListController.swift:416-421` through the whole model: the ids
 * it toggles (`pinned` when pins exist, every user section, the groups
 * only while grouping is on, never `recent`), one engine call per open
 * user section, one `Haptic.Select` per toggled id, and the all-folded
 * no-op.
 *
 * Plain JUnit 4: [newFrontFixture] wires the fake core, the feed, the
 * prefs and the fold state over one DataStore file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollapseAllTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    /** Pinned (2 rows), s1 open, s2 engine-folded, g1 a group, r recent. */
    private fun workspace(): FrontPage = front(
        pinned = listOf(row("p1"), row("p2")),
        sections = listOf(
            section("e1:s1", name = "P0", collapsed = false, rows = listOf(row("a"), row("b"))),
            section("e1:s2", name = "Mobile", collapsed = true, rows = listOf(row("c"), row("d"))),
        ),
        recent = listOf(row("r1"), row("r2")),
        groups = listOf(group(id = "project:edge", name = "edge", rows = listOf(row("r1")))),
    )

    @Test
    fun collapseAllFoldsEveryStillOpenId() = runTest {
        val fixture = newFrontFixture(tmpFolder.root, "collapse", snapshot(1uL, workspace()))
        val feedback = RecordingFeedback()
        fixture.feed.start()
        advanceUntilIdle()

        fixture.model.collapseAll(feedback)
        advanceUntilIdle()

        // Pinned and s1 were open: two haptics, one per toggled id. s2 was
        // already folded by the engine — no toggle, no call.
        assertEquals(listOf(Haptic.Select, Haptic.Select), feedback.haptics)
        // One engine call per open user section; Pinned is phone-local.
        assertEquals(listOf("e1:s1" to true), fixture.core.setCalls.toList())
        // Never recent, and the already-collapsed s2 is untouched.
        val local = fixture.fold.state.value.local
        assertTrue(FrontPageSections.PINNED in local)
        assertFalse(FrontPageSections.RECENT in local)
        assertTrue(fixture.fold.isCollapsed("e1:s1", section("e1:s1", collapsed = false)))
    }

    @Test
    fun groupsCollapseOnlyWhileGroupingIsOn() = runTest {
        // Grouping on: the group folds too.
        val grouped = newFrontFixture(tmpFolder.root, "collapse-on", snapshot(1uL, workspace()))
        val groupedFeedback = RecordingFeedback()
        grouped.feed.start()
        // Let Prefs finish its first disk read before flipping the flag:
        // the load assigns the stored (absent → false) value and would
        // overwrite a flip made while it is still in flight.
        advanceUntilIdle()
        grouped.prefs.setGroupByProject(true)
        advanceUntilIdle()
        grouped.model.collapseAll(groupedFeedback)
        advanceUntilIdle()
        assertEquals(listOf(Haptic.Select, Haptic.Select, Haptic.Select), groupedFeedback.haptics)
        assertTrue("project:edge" in grouped.fold.state.value.local)
        assertEquals(listOf("e1:s1" to true), grouped.core.setCalls.toList())

        // Grouping off: the group never folds, even though it exists.
        val plain = newFrontFixture(tmpFolder.root, "collapse-off", snapshot(1uL, workspace()))
        val plainFeedback = RecordingFeedback()
        plain.feed.start()
        advanceUntilIdle()
        plain.model.collapseAll(plainFeedback)
        advanceUntilIdle()
        assertEquals(listOf(Haptic.Select, Haptic.Select), plainFeedback.haptics)
        assertFalse("project:edge" in plain.fold.state.value.local)
    }

    @Test
    fun noPinsMeansNoPinnedToggle() = runTest {
        val page = workspace().copy(pinned = emptyList())
        val fixture = newFrontFixture(tmpFolder.root, "collapse-nopins", snapshot(1uL, page))
        val feedback = RecordingFeedback()
        fixture.feed.start()
        advanceUntilIdle()

        fixture.model.collapseAll(feedback)
        advanceUntilIdle()
        assertEquals(listOf(Haptic.Select), feedback.haptics)
        assertEquals(listOf("e1:s1" to true), fixture.core.setCalls.toList())
    }

    @Test
    fun anAllFoldedWorkspaceIsANoOp() = runTest {
        // Pinned folded locally, s1 engine-folded, grouping on with the
        // group folded: nothing left to do.
        val page = front(
            pinned = listOf(row("p1")),
            sections = listOf(section("e1:s1", name = "P0", collapsed = true)),
            recent = listOf(row("r1")),
            groups = listOf(group(id = "project:edge", name = "edge", rows = listOf(row("r1")))),
        )
        val fixture = newFrontFixture(tmpFolder.root, "collapse-idle", snapshot(1uL, page))
        fixture.feed.start()
        advanceUntilIdle() // Prefs loaded first (see groupsCollapseOnlyWhileGroupingIsOn)
        fixture.prefs.setGroupByProject(true)
        advanceUntilIdle()
        fixture.fold.toggle(FrontPageSections.PINNED, null, NoFeedback)
        advanceUntilIdle()
        fixture.fold.toggle("project:edge", null, NoFeedback)
        advanceUntilIdle()
        val feedback = RecordingFeedback()

        fixture.model.collapseAll(feedback)
        advanceUntilIdle()

        // Nothing toggled: no haptic, no engine call.
        assertTrue(feedback.haptics.isEmpty())
        assertTrue(fixture.core.setCalls.isEmpty())
    }
}

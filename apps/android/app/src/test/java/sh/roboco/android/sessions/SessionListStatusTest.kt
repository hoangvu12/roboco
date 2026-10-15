package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.EngineState

/**
 * The status derivations (android-ui-parity #41): the
 * [sessionListStatus] first-match-wins table, the [sessionsLoading]
 * matrix over the FFI [uniffi.roboco_core.EngineView] record, and the
 * [statusAnchorY] worked example from the ticket (393 × 852, safe top
 * 155, bottom 110, lift 40 → **408.5**).
 *
 * Plain JUnit: the records are plain Kotlin data classes (`WorkspaceFixtures`
 * builds them with no JNA), and the anchors are pure math.
 */
class SessionListStatusTest {

    // -------------------------------------------------- the status table

    @Test
    fun itemsWinOverLoadingAndEveryEmptyKind() {
        val one = buildSessionListItems(
            listOf(ListSection(id = "s", header = null, rows = listOf(row("a")))),
            collapsible = true,
            toVm = SessionListFixtures::toVm,
        )
        val none = emptyList<SessionListItem>()
        assertEquals(
            SessionListStatus.Content,
            sessionListStatus(one, loading = true, empty = SessionListEmpty.Sessions),
        )
        for (kind in SessionListEmpty.entries) {
            assertEquals("loading wins over $kind", SessionListStatus.Loading, sessionListStatus(none, true, kind))
        }
    }

    @Test
    fun emptyKindsPickTheirOwnState() {
        val none = emptyList<SessionListItem>()
        assertEquals(SessionListStatus.EmptySessions, sessionListStatus(none, false, SessionListEmpty.Sessions))
        assertEquals(SessionListStatus.EmptyArchived, sessionListStatus(none, false, SessionListEmpty.Archived))
        assertEquals(SessionListStatus.Blank, sessionListStatus(none, false, SessionListEmpty.None))
    }

    // ------------------------------------------------- the loading matrix

    @Test
    fun sessionsLoadingMatrix() {
        fun eng(state: EngineState, fromCache: Boolean) = engine("e").copy(state = state, fromCache = fromCache)

        assertTrue(sessionsLoading(listOf(eng(EngineState.Connecting, true))))
        assertTrue(sessionsLoading(listOf(eng(EngineState.Reconnecting, true))))
        assertFalse(sessionsLoading(listOf(eng(EngineState.Connecting, false))))
        assertFalse(sessionsLoading(listOf(eng(EngineState.Reconnecting, false))))
        assertFalse(sessionsLoading(listOf(eng(EngineState.Connected, true))))
        assertFalse(sessionsLoading(listOf(eng(EngineState.Offline, true))))
        assertFalse(sessionsLoading(listOf(eng(EngineState.Parked(uniffi.roboco_core.ParkReason.REVOKED), true))))
        // A from-cache reconnecter makes the list loading; others never do.
        assertTrue(
            sessionsLoading(
                listOf(eng(EngineState.Connected, true), eng(EngineState.Reconnecting, true)),
            ),
        )
        assertFalse(
            sessionsLoading(
                listOf(eng(EngineState.Connected, false), eng(EngineState.Offline, true)),
            ),
        )
    }

    // ------------------------------------------------- the anchor math

    @Test
    fun theWorkedExampleAnchorIs408Point5() {
        // (155 + (852 − 110)) / 2 − 40 = 408.5
        assertEquals(408.5f, statusAnchorY(852f, 155f, 110f, 40f), 0.0001f)
    }

    @Test
    fun theAnchorScalesWithTheBounds() {
        // (100 + (400 − 50)) / 2 − 25 = 200 — pure math, no dp coupling.
        assertEquals(200f, statusAnchorY(400f, 100f, 50f, 25f), 0.0001f)
        // No insets: the plain centre minus the lift.
        assertEquals(40f, statusAnchorY(160f, 0f, 0f, 40f), 0.0001f)
    }
}

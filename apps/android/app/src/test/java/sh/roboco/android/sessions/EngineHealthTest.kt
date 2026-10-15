package sh.roboco.android.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.ConnectivityState
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason

/**
 * [EngineHealth.compute] (android-ui-parity #50) — the Spec's steps 1–6:
 * the disabled posture, the raw sources, the grace hand-off, the offline
 * engine ids, the line's entries (the label table, the caption rule, the
 * "+N more" overflow) and the next-check deadline.
 *
 * Plain JUnit like ticket 40's tests: the UniFFI records are plain Kotlin
 * data classes whose initialisation touches no JNA (the same property
 * `WorkspaceFixtures` relies on), and `compute` is pure apart from the
 * [DegradeGrace] tracker it mutates.
 *
 * Expected strings are explicit literals — the label/caption assertions
 * use `\u2026` / `\u2014` escapes so no editor can mangle the ellipsis and
 * the em dash along with the code under test.
 */
class EngineHealthTest {

    // ------------------------------------------------------------ fixtures

    // `engine(...)` / `connectivity(...)` come from WorkspaceFixtures.kt
    // (the same source set, same package); the INCOMPATIBLE cases pass
    // `lastError` through the shared builder.

    // -------------------------------------------------- the grace hand-off

    @Test
    fun oneReconnectingEngineShowsNothingAtZeroAndTheLineAt4000() {
        val g = DegradeGrace()
        val engines = listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting))

        val early = EngineHealth.compute(engines, connectivity(ConnectivityState.RECONNECTING), 0, g)
        assertNull(early.ui.line)
        assertTrue(early.ui.offlineEngineIds.isEmpty())
        assertEquals(4000L, early.nextCheckAtMs)

        val late = EngineHealth.compute(engines, connectivity(ConnectivityState.RECONNECTING), 4000, g)
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Reconnecting\u2026")),
            late.ui.line!!.entries,
        )
        assertEquals(setOf("mbp"), late.ui.offlineEngineIds)
        // Graced and still degraded: nothing more changes without an event.
        assertNull(late.nextCheckAtMs)
    }

    @Test
    fun recoveryHidesTheLineInTheSameCompute() {
        val g = DegradeGrace()
        val engines = listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting))
        EngineHealth.compute(engines, connectivity(ConnectivityState.RECONNECTING), 4000, g)
        val healed = EngineHealth.compute(
            listOf(engine("mbp", "MacBook Pro", EngineState.Connected)),
            connectivity(ConnectivityState.CONNECTED),
            4001,
            g,
        )
        assertEquals(EngineHealthUi.Healthy, healed.ui)
        assertNull(healed.nextCheckAtMs)
    }

    @Test
    fun disabledOrZeroEnginesIsHealthyAndClearsTheTracker() {
        val g = DegradeGrace()
        // A tracker that already holds a started outage.
        g.graced("os", true, 0)
        assertEquals(
            EngineHealthUi.Healthy,
            EngineHealth.compute(emptyList(), null, 5000, g).ui,
        )
        assertNull(g.nextDeadline(5000))

        g.graced("engine:a", true, 0)
        assertEquals(
            EngineHealthUi.Healthy,
            EngineHealth.compute(
                listOf(engine("a")),
                connectivity(ConnectivityState.DISABLED),
                5000,
                g,
            ).ui,
        )
        assertNull(g.nextDeadline(5000))
    }

    @Test
    fun aNullConnectivityCountsAsNotOffline() {
        val g = DegradeGrace()
        // Not read yet: the OS source is healthy, the engine's own state
        // drives the (graced) line. Warm the tracker at 0, read at 4000.
        EngineHealth.compute(listOf(engine("a", "A", EngineState.Offline)), null, 0, g)
        val r = EngineHealth.compute(listOf(engine("a", "A", EngineState.Offline)), null, 4000, g)
        assertEquals(listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline")), r.ui.line!!.entries)
        assertEquals(setOf("a"), r.ui.offlineEngineIds)
    }

    // ------------------------------------------------------- the OS offline

    @Test
    fun aGracedOsOfflineShowsOneOfflineLineAndEveryDegradedEngineId() {
        val g = DegradeGrace()
        val engines = listOf(
            engine("a", "Studio", EngineState.Offline),
            engine("b", "Laptop", EngineState.Parked(ParkReason.REVOKED)),
        )
        // A null connectivity would also work, but the OS-offline posture
        // the core reports for a dead network is OFFLINE. Warm at 0, read
        // at 4000.
        EngineHealth.compute(engines, connectivity(ConnectivityState.OFFLINE), 0, g)
        val r = EngineHealth.compute(engines, connectivity(ConnectivityState.OFFLINE), 4000, g)
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline")),
            r.ui.line!!.entries,
        )
        assertEquals(setOf("a", "b"), r.ui.offlineEngineIds)
    }

    @Test
    fun osOfflineShorterThanTheGraceShowsNothing() {
        val g = DegradeGrace()
        val engines = listOf(engine("a", "Studio", EngineState.Offline))
        EngineHealth.compute(engines, connectivity(ConnectivityState.OFFLINE), 0, g)
        val r = EngineHealth.compute(engines, connectivity(ConnectivityState.OFFLINE), 3999, g)
        assertNull(r.ui.line)
        assertTrue(r.ui.offlineEngineIds.isEmpty())
        assertEquals(4000L, r.nextCheckAtMs)
    }

    @Test
    fun connectedEnginesStayGlyphFreeWhileTheOsGraces() {
        val g = DegradeGrace()
        // The OS key graces alone: engines that are Connected are never
        // degraded sources, so no line and no offline ids until the OS
        // grace completes.
        for (t in 0L..3000L step 1000L) {
            val r = EngineHealth.compute(
                listOf(engine("a", "Studio")),
                connectivity(ConnectivityState.OFFLINE),
                t,
                g,
            )
            assertNull(r.ui.line)
            assertTrue(r.ui.offlineEngineIds.isEmpty())
        }
        // At 4000 the OS grace completes: the single Offline line appears
        // (the aggregate posture is OFFLINE), but a Connected engine
        // still never feeds the wifi-off rule.
        val graced = EngineHealth.compute(
            listOf(engine("a", "Studio")),
            connectivity(ConnectivityState.OFFLINE),
            4000,
            g,
        )
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, "Offline")),
            graced.ui.line!!.entries,
        )
        assertTrue(graced.ui.offlineEngineIds.isEmpty())
    }

    // -------------------------------------------------------- the label table

    @Test
    fun theLabelTableHoldsForEveryState() {
        fun label(state: EngineState, lastError: String? = null) =
            EngineHealth.entryFor(engine("e", "E", state, lastError))

        assertEquals(ConnectionGlyph.Spinner to "Connecting\u2026", label(EngineState.Connecting))
        assertEquals(ConnectionGlyph.Spinner to "Reconnecting\u2026", label(EngineState.Reconnecting))
        assertEquals(ConnectionGlyph.WarningDot to "Offline", label(EngineState.Offline))
        assertEquals(
            ConnectionGlyph.DangerDot to "Engine changed",
            label(EngineState.Parked(ParkReason.IDENTITY_CHANGED)),
        )
        assertEquals(
            ConnectionGlyph.DangerDot to "Session revoked",
            label(EngineState.Parked(ParkReason.REVOKED)),
        )
        // The INCOMPATIBLE sentence: 06 fills it verbatim (trimmed).
        assertEquals(
            ConnectionGlyph.DangerDot to "E needs a newer Roboco app. Update the app to connect.",
            label(EngineState.Parked(ParkReason.INCOMPATIBLE), "  E needs a newer Roboco app. Update the app to connect.  "),
        )
    }

    @Test
    fun incompatibleWithABlankOrNullLastErrorSaysUpdateRequired() {
        assertEquals(
            ConnectionGlyph.DangerDot to "Update required",
            EngineHealth.entryFor(engine("e", "E", EngineState.Parked(ParkReason.INCOMPATIBLE), null)),
        )
        assertEquals(
            ConnectionGlyph.DangerDot to "Update required",
            EngineHealth.entryFor(engine("e", "E", EngineState.Parked(ParkReason.INCOMPATIBLE), "   ")),
        )
    }

    // -------------------------------------------------------- the caption rule

    @Test
    fun oneEngineShowsTheLabelAlone() {
        assertEquals("Reconnecting\u2026", EngineHealth.caption("MacBook Pro", "Reconnecting\u2026", 1))
    }

    @Test
    fun twoEnginesPrefixTheName() {
        assertEquals(
            "Laptop \u2014 Reconnecting\u2026",
            EngineHealth.caption("Laptop", "Reconnecting\u2026", 2),
        )
    }

    @Test
    fun aLabelAlreadyStartingWithTheNameGetsNoPrefix() {
        assertEquals(
            "MacBook Pro needs a newer Roboco app. Update the app to connect.",
            EngineHealth.caption(
                "MacBook Pro",
                "MacBook Pro needs a newer Roboco app. Update the app to connect.",
                2,
            ),
        )
    }

    @Test
    fun theOverflowCopy() {
        assertEquals("+2 more", EngineHealth.more(2))
        assertEquals("+0 more", EngineHealth.more(0))
    }

    // ------------------------------------------------------- the line entries

    /** Pre-graces [engines] (all non-Connected) so the entry assertions read one compute. */
    private fun gracedCompute(engines: List<EngineView>, connectivity: Connectivity? = null): EngineHealthUi {
        val g = DegradeGrace()
        // Two passes: the first starts every timer, the second (4 s later,
        // the earliest possible) reports them all graced.
        EngineHealth.compute(engines, connectivity, 0, g)
        return EngineHealth.compute(engines, connectivity, 4000, g).ui
    }

    @Test
    fun theSingleEngineExamplesTable() {
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Reconnecting\u2026")),
            gracedCompute(listOf(engine("mbp", "MacBook Pro", EngineState.Reconnecting))).line!!.entries,
        )
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Connecting\u2026")),
            gracedCompute(listOf(engine("a", "A", EngineState.Connecting))).line!!.entries,
        )
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.DangerDot, "Session revoked")),
            gracedCompute(listOf(engine("a", "A", EngineState.Parked(ParkReason.REVOKED)))).line!!.entries,
        )
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.DangerDot, "Engine changed")),
            gracedCompute(listOf(engine("a", "A", EngineState.Parked(ParkReason.IDENTITY_CHANGED)))).line!!.entries,
        )
        assertEquals(
            listOf(
                ConnectionLineEntry(
                    ConnectionGlyph.DangerDot,
                    "MacBook Pro needs a newer Roboco app. Update the app to connect.",
                ),
            ),
            gracedCompute(
                listOf(
                    engine(
                        "mbp",
                        "MacBook Pro",
                        EngineState.Parked(ParkReason.INCOMPATIBLE),
                        lastError = "MacBook Pro needs a newer Roboco app. Update the app to connect.",
                    ),
                ),
            ).line!!.entries,
        )
    }

    @Test
    fun theMultiEngineExamplesTable() {
        // Studio Connected, Laptop Reconnecting: one line, name-prefixed.
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "Laptop \u2014 Reconnecting\u2026")),
            gracedCompute(
                listOf(
                    engine("studio", "Studio"),
                    engine("laptop", "Laptop", EngineState.Reconnecting),
                ),
            ).line!!.entries,
        )
        // Parked and degraded engines mix, pairing order preserved.
        assertEquals(
            listOf(
                ConnectionLineEntry(ConnectionGlyph.DangerDot, "Studio \u2014 Session revoked"),
                ConnectionLineEntry(ConnectionGlyph.Spinner, "Laptop \u2014 Reconnecting\u2026"),
            ),
            gracedCompute(
                listOf(
                    engine("studio", "Studio", EngineState.Parked(ParkReason.REVOKED)),
                    engine("laptop", "Laptop", EngineState.Reconnecting),
                    engine("mini", "Mini"),
                ),
            ).line!!.entries,
        )
    }

    @Test
    fun fiveDegradedEnginesShowThreeLinesAndAnOverflowEntry() {
        val engines = listOf(
            engine("a", "A", EngineState.Reconnecting),
            engine("b", "B", EngineState.Reconnecting),
            engine("c", "C", EngineState.Reconnecting),
            engine("d", "D", EngineState.Reconnecting),
            engine("e", "E", EngineState.Reconnecting),
        )
        assertEquals(
            listOf(
                ConnectionLineEntry(ConnectionGlyph.Spinner, "A \u2014 Reconnecting\u2026"),
                ConnectionLineEntry(ConnectionGlyph.Spinner, "B \u2014 Reconnecting\u2026"),
                ConnectionLineEntry(ConnectionGlyph.Spinner, "C \u2014 Reconnecting\u2026"),
                ConnectionLineEntry(null, "+2 more"),
            ),
            gracedCompute(engines).line!!.entries,
        )
        // Every degraded engine still feeds the wifi-off rule.
        assertEquals(setOf("a", "b", "c", "d", "e"), gracedCompute(engines).offlineEngineIds)
    }

    @Test
    fun threeDegradedEnginesShowExactlyThreeLinesAndNoOverflow() {
        val engines = listOf(
            engine("a", "A", EngineState.Reconnecting),
            engine("b", "B", EngineState.Reconnecting),
            engine("c", "C", EngineState.Reconnecting),
        )
        assertEquals(
            listOf(
                ConnectionLineEntry(ConnectionGlyph.Spinner, "A \u2014 Reconnecting\u2026"),
                ConnectionLineEntry(ConnectionGlyph.Spinner, "B \u2014 Reconnecting\u2026"),
                ConnectionLineEntry(ConnectionGlyph.Spinner, "C \u2014 Reconnecting\u2026"),
            ),
            gracedCompute(engines).line!!.entries,
        )
    }

    @Test
    fun theOfflineEngineIdsComeFromTheGracedEnginesWhileTheOsIsUp() {
        val g = DegradeGrace()
        val engines = listOf(
            engine("a", "A", EngineState.Reconnecting),
            engine("b", "B"),
        )
        // a is raw-degraded, b is not: only a's key ever starts. Warm at
        // 0, read at 4000.
        EngineHealth.compute(engines, connectivity(ConnectivityState.RECONNECTING), 0, g)
        val r = EngineHealth.compute(engines, connectivity(ConnectivityState.RECONNECTING), 4000, g)
        assertEquals(setOf("a"), r.ui.offlineEngineIds)
        // And the line keeps pairing order: only a's entry.
        assertEquals(
            listOf(ConnectionLineEntry(ConnectionGlyph.Spinner, "A \u2014 Reconnecting\u2026")),
            r.ui.line!!.entries,
        )
    }

    // ---------------------------------------------------------- next check

    @Test
    fun theNextCheckIsTheEarliestOpenWindowEvenWithMixedSources() {
        val g = DegradeGrace()
        // The OS went offline at 1000, engine a at 0, engine b healthy.
        EngineHealth.compute(
            listOf(engine("a", "A", EngineState.Reconnecting), engine("b", "B")),
            connectivity(ConnectivityState.OFFLINE),
            0,
            g,
        )
        val r = EngineHealth.compute(
            listOf(engine("a", "A", EngineState.Reconnecting), engine("b", "B")),
            connectivity(ConnectivityState.OFFLINE),
            1000,
            g,
        )
        // engine:a's window (0 + 4000) is the earliest still open.
        assertEquals(4000L, r.nextCheckAtMs)
    }
}

package sh.roboco.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.ParkReason

/**
 * The pure half of the Settings Devices section (android-ui-parity #102):
 * [engineConnectionLabel] and [devicesRows]. Plain JVM — no Compose, no
 * Robolectric (the section's behaviour, geometry and goldens are
 * [DevicesSectionTest] / [DevicesRowsScreenshotTest]).
 *
 * The ellipsis assertions pin the single character U+2026 (written as a
 * Kotlin escape so an editor or encoding change cannot turn it into
 * `...`), and every label is the ticket's copy table verbatim.
 */
class DevicesRowsTest {

    // ------------------------------------------------------ the labels

    @Test
    fun everyStateMapsToItsVerbatimLabel() {
        assertEquals("Starting\u2026", engineConnectionLabel(EngineState.Connecting, null))
        assertEquals("Connected", engineConnectionLabel(EngineState.Connected, null))
        assertEquals("Reconnecting\u2026", engineConnectionLabel(EngineState.Reconnecting, null))
        assertEquals("Offline", engineConnectionLabel(EngineState.Offline, null))
        assertEquals("Engine changed", engineConnectionLabel(EngineState.Parked(ParkReason.IDENTITY_CHANGED), null))
        assertEquals("Session revoked", engineConnectionLabel(EngineState.Parked(ParkReason.REVOKED), null))
        assertEquals("Update required", engineConnectionLabel(EngineState.Parked(ParkReason.INCOMPATIBLE), null))
    }

    @Test
    fun theEllipsesAreTheSingleCharacterU2026() {
        val labels = listOf(
            engineConnectionLabel(EngineState.Connecting, null),
            engineConnectionLabel(EngineState.Reconnecting, null),
        )
        for (label in labels) {
            assertTrue("U+2026 expected in '$label'", label.endsWith("\u2026"))
            assertFalse("three dots must never appear: '$label'", label.endsWith("..."))
            assertEquals("exactly one ellipsis character: '$label'", 1, label.count { it == '\u2026' })
        }
    }

    @Test
    fun parkedIncompatibleShowsTheTrimmedSentence() {
        val sentence = "  Studio needs a newer Roboco app. Update the app to connect. "
        assertEquals(
            "Studio needs a newer Roboco app. Update the app to connect.",
            engineConnectionLabel(EngineState.Parked(ParkReason.INCOMPATIBLE), sentence),
        )
        assertEquals(
            "Build box runs an older Roboco (0.9.1). Update Roboco on that device to connect.",
            engineConnectionLabel(
                EngineState.Parked(ParkReason.INCOMPATIBLE),
                "Build box runs an older Roboco (0.9.1). Update Roboco on that device to connect.",
            ),
        )
    }

    @Test
    fun parkedIncompatibleWithABlankErrorFallsBackToUpdateRequired() {
        assertEquals("Update required", engineConnectionLabel(EngineState.Parked(ParkReason.INCOMPATIBLE), null))
        assertEquals("Update required", engineConnectionLabel(EngineState.Parked(ParkReason.INCOMPATIBLE), "  "))
        assertEquals("Update required", engineConnectionLabel(EngineState.Parked(ParkReason.INCOMPATIBLE), "\t\n "))
    }

    @Test
    fun lastErrorIsShownForNoStateOtherThanIncompatible() {
        // Transport errors belong in logs and on the Engine detail page
        // (140) — never in the row.
        val error = "websocket: handshake failed"
        assertEquals("Starting\u2026", engineConnectionLabel(EngineState.Connecting, error))
        assertEquals("Connected", engineConnectionLabel(EngineState.Connected, error))
        assertEquals("Reconnecting\u2026", engineConnectionLabel(EngineState.Reconnecting, error))
        assertEquals("Offline", engineConnectionLabel(EngineState.Offline, error))
        assertEquals("Engine changed", engineConnectionLabel(EngineState.Parked(ParkReason.IDENTITY_CHANGED), error))
        assertEquals("Session revoked", engineConnectionLabel(EngineState.Parked(ParkReason.REVOKED), error))
    }

    // ------------------------------------------------------- the rows

    @Test
    fun nullAndEmptyListsGiveJustThePairRow() {
        val expected = listOf<DevicesRowSpec>(DevicesRowSpec.Pair)
        assertEquals(expected, devicesRows(null))
        assertEquals(expected, devicesRows(emptyList()))
    }

    @Test
    fun threeEnginesKeepTheCoresOrderAndIds() {
        val rows = devicesRows(
            listOf(
                engineView(id = "A", name = "MacBook Pro", state = EngineState.Connected),
                engineView(id = "B", name = "Studio", state = EngineState.Reconnecting),
                engineView(id = "C", name = "Mac mini", state = EngineState.Parked(ParkReason.REVOKED)),
            ),
        )
        assertEquals(
            listOf("device:A", "device:B", "device:C", "pair"),
            rows.map { it.id },
        )
        assertEquals(DevicesRowSpec.Pair, rows.last())
        val engine = rows[0] as DevicesRowSpec.Engine
        assertEquals("A", engine.engineId)
        assertEquals("MacBook Pro", engine.title)
        assertEquals("Connected", engine.label)
        assertTrue("Connected is connected", engine.connected)
        assertEquals("Reconnecting\u2026", (rows[1] as DevicesRowSpec.Engine).label)
        assertEquals("Session revoked", (rows[2] as DevicesRowSpec.Engine).label)
    }

    @Test
    fun parkedEnginesStayInTheListAndKeepTheirPosition() {
        // Rust dedupes re-pairs and never sorts; the section shows every
        // engine, parked included, in the core's (pairing) order.
        val rows = devicesRows(
            listOf(
                engineView(id = "parked", name = "Old box", state = EngineState.Parked(ParkReason.IDENTITY_CHANGED)),
                engineView(id = "live", name = "New box", state = EngineState.Connected),
            ),
        )
        assertEquals(listOf("device:parked", "device:live", "pair"), rows.map { it.id })
        assertFalse((rows[0] as DevicesRowSpec.Engine).connected)
    }

    @Test
    fun connectedIsTrueOnlyForTheConnectedState() {
        val states = listOf(
            EngineState.Connecting,
            EngineState.Connected,
            EngineState.Reconnecting,
            EngineState.Offline,
            EngineState.Parked(ParkReason.REVOKED),
            EngineState.Parked(ParkReason.IDENTITY_CHANGED),
            EngineState.Parked(ParkReason.INCOMPATIBLE),
        )
        for (state in states) {
            val row = devicesRows(listOf(engineView(id = "e", name = "Vu-PC", state = state)))[0] as DevicesRowSpec.Engine
            assertEquals("connected only for Connected: $state", state is EngineState.Connected, row.connected)
        }
    }

    @Test
    fun aConnectedTailcatEngineShowsItsPath() {
        val rows = devicesRows(
            listOf(
                engineView(id = "pc", name = "PC", state = EngineState.Connected, linkPath = "Via relay (Tokyo) \u00b7 160 ms"),
                engineView(id = "lan", name = "Laptop", state = EngineState.Connected),
                engineView(id = "away", name = "SG", state = EngineState.Reconnecting, linkPath = "Direct \u00b7 35 ms"),
            ),
        )
        assertEquals("Via relay (Tokyo) \u00b7 160 ms", (rows[0] as DevicesRowSpec.Engine).label)
        assertEquals("Connected", (rows[1] as DevicesRowSpec.Engine).label)
        // A stale path never hides a reconnect.
        assertEquals("Reconnecting\u2026", (rows[2] as DevicesRowSpec.Engine).label)
    }

    @Test
    fun aBlankNameFallsBackToTheHost() {
        val rows = devicesRows(listOf(engineView(id = "e1", name = "  ", state = EngineState.Connected)))
        assertEquals("studio.local:27655", (rows[0] as DevicesRowSpec.Engine).title)
    }

    @Test
    fun theIncompatibleSentenceBecomesTheLabel() {
        val rows = devicesRows(
            listOf(
                engineView(
                    id = "e4",
                    name = "Build box",
                    state = EngineState.Parked(ParkReason.INCOMPATIBLE),
                    lastError = "Build box needs a newer Roboco app. Update the app to connect.",
                ),
            ),
        )
        assertEquals(
            "Build box needs a newer Roboco app. Update the app to connect.",
            (rows[0] as DevicesRowSpec.Engine).label,
        )
    }
}

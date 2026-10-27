package sh.roboco.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.roboco_core.EngineRoomRef
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.ParkReason

/**
 * The Engine detail page's pure model (android-ui-parity #140): every
 * table of [EngineDetailModel] — `formatLastConnected`, `platformLabel`,
 * `shortId`, `engineStatusValue`, `engineFooter`, `isRepairable` and
 * [engineDetailUi]'s row rules — plus the copy's U+2026 ellipses and the
 * status-card row order. Robolectric because [engineDetailUi] takes
 * UniFFI records (android-app#10); no Compose, no clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineDetailModelTest {

    /** A fixed epoch base for the relative-time table. */
    private val now = 1_760_000_000_000L

    // ---------------------------------------------- formatLastConnected

    @Test
    fun formatLastConnectedMatchesTheWebTable() {
        val cases = listOf(
            0L to "just now",
            59_999L to "just now",
            60_000L to "1m ago",
            3_599_999L to "59m ago",
            3_600_000L to "1h ago",
            86_399_999L to "23h ago",
            86_400_000L to "1d ago",
            259_200_000L to "3d ago",
        )
        for ((delta, expected) in cases) {
            assertEquals("now − at = ${delta}ms", expected, formatLastConnected(now - delta, now))
        }
    }

    @Test
    fun aFutureTimestampIsJustNow() {
        assertEquals("just now", formatLastConnected(now + 5_000, now))
        assertEquals("just now", formatLastConnected(now + 3_600_000, now))
    }

    // ------------------------------------------------------ platformLabel

    @Test
    fun platformLabelKnowsTheSixSpellings() {
        assertEquals("macOS", platformLabel("macos"))
        assertEquals("macOS", platformLabel("darwin"))
        assertEquals("Linux", platformLabel("linux"))
        assertEquals("Windows", platformLabel("windows"))
        assertEquals("Web", platformLabel("web"))
        assertEquals("iOS", platformLabel("ios"))
        assertEquals("Android", platformLabel("android"))
    }

    @Test
    fun unknownPlatformsStayVerbatimAndCaseSensitive() {
        assertEquals("freebsd", platformLabel("freebsd"))
        assertEquals("MacOS", platformLabel("MacOS"))
        assertEquals("Windows-11", platformLabel("Windows-11"))
    }

    // ----------------------------------------------------------- shortId

    @Test
    fun shortIdKeepsTwelveCharactersAndTrimsLongerOnes() {
        assertEquals("abcdefghijkl", shortId("abcdefghijkl"))
        assertEquals("3f9c2a7e\u20261a95", shortId("3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95"))
    }

    @Test
    fun theShortIdEllipsisIsTheSingleCharacterU2026() {
        val id = shortId("3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95")
        // A middle truncation (8 + \u2026 + 4, as the test above pins): the
        // ellipsis sits inside the id, not at its end.
        assertTrue("U+2026 expected in '$id'", id.contains("\u2026"))
        assertFalse("three dots must never appear: '$id'", id.contains("..."))
        assertEquals("exactly one ellipsis character", 1, id.count { it == '\u2026' })
        assertEquals("8 + 1 + 4 characters", 13, id.length)
    }

    // -------------------------------------------------- engineStatusValue

    @Test
    fun engineStatusValueMatchesTheStatusTable() {
        assertEquals("Starting\u2026", engineStatusValue(EngineState.Connecting, null))
        assertEquals("Connected", engineStatusValue(EngineState.Connected, null))
        assertEquals("Reconnecting\u2026", engineStatusValue(EngineState.Reconnecting, null))
        assertEquals("Offline", engineStatusValue(EngineState.Offline, null))
        assertEquals("Engine changed", engineStatusValue(EngineState.Parked(ParkReason.IDENTITY_CHANGED), null))
        assertEquals("Session revoked", engineStatusValue(EngineState.Parked(ParkReason.REVOKED), null))
    }

    @Test
    fun incompatibleShowsUpdateRequiredEvenWithALastError() {
        val sentence = "Build box runs an older Roboco (0.8.0). Update Roboco on that device to connect."
        assertEquals(
            "Update required",
            engineStatusValue(EngineState.Parked(ParkReason.INCOMPATIBLE), sentence),
        )
        assertEquals(
            "Update required",
            engineStatusValue(EngineState.Parked(ParkReason.INCOMPATIBLE), null),
        )
    }

    @Test
    fun lastErrorNeverReachesTheStatusValueOutsideIncompatible() {
        // 102's rule: transport errors belong in the footer, never the value.
        val error = "websocket: handshake failed"
        assertEquals("Reconnecting\u2026", engineStatusValue(EngineState.Reconnecting, error))
        assertEquals("Offline", engineStatusValue(EngineState.Offline, error))
    }

    // -------------------------------------------------------- engineFooter

    @Test
    fun theErrorFooterWinsWhileNotConnectedAndIsTrimmed() {
        val footer = engineFooter(EngineState.Reconnecting, null, "  timed out ")
        assertEquals(EngineFooter("timed out", FooterTone.Error), footer)
        assertEquals(EngineFooter("timed out", FooterTone.Error), engineFooter(EngineState.Offline, null, "timed out"))
        assertEquals(
            EngineFooter("invalid credential", FooterTone.Error),
            engineFooter(EngineState.Parked(ParkReason.REVOKED), null, "invalid credential"),
        )
    }

    @Test
    fun theNoticeFooterShowsWhileConnected() {
        val notice = "MacBook Pro runs Roboco 0.10.0, newer than this app (0.9.2)."
        val lastError = "an old error"
        assertEquals(EngineFooter(notice, FooterTone.Notice), engineFooter(EngineState.Connected, notice, lastError))
        assertEquals(
            EngineFooter("notice text", FooterTone.Notice),
            engineFooter(EngineState.Parked(ParkReason.REVOKED), " notice text ", "   "),
        )
    }

    @Test
    fun noFooterWhenBothAreBlankOrAbsent() {
        assertNull(engineFooter(EngineState.Connected, null, null))
        assertNull(engineFooter(EngineState.Connected, "   ", null))
        assertNull(engineFooter(EngineState.Reconnecting, null, "   "))
        assertNull(engineFooter(EngineState.Parked(ParkReason.REVOKED), null, null))
    }

    // --------------------------------------------------------- isRepairable

    @Test
    fun pairAgainShowsOnlyForRevokedAndIdentityChanged() {
        assertTrue(isRepairable(EngineState.Parked(ParkReason.REVOKED)))
        assertTrue(isRepairable(EngineState.Parked(ParkReason.IDENTITY_CHANGED)))
        assertFalse(isRepairable(EngineState.Parked(ParkReason.INCOMPATIBLE)))
        assertFalse(isRepairable(EngineState.Connected))
        assertFalse(isRepairable(EngineState.Connecting))
        assertFalse(isRepairable(EngineState.Reconnecting))
        assertFalse(isRepairable(EngineState.Offline))
    }

    // -------------------------------------------------------- engineDetailUi

    @Test
    fun theTitleFallsBackToTheHostForABlankName() {
        val ui = engineDetailUi(
            engineView(id = "e1", name = "   ", state = EngineState.Connected, host = "10.0.0.7:27655"),
            now,
        )
        assertEquals("10.0.0.7:27655", ui.title)
        assertEquals("MacBook Pro", engineDetailUi(engineView(id = "e1", name = " MacBook Pro ", state = EngineState.Connected), now).title)
    }

    @Test
    fun theVersionRowIsHiddenForNullAndBlankAndPrefixedOtherwise() {
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, version = null), now).version)
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, version = "  "), now).version)
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, version = "\t"), now).version)
        assertEquals("v0.9.2", engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, version = " 0.9.2 "), now).version)
        assertEquals("v1.0.0", engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, version = "1.0.0"), now).version)
    }

    @Test
    fun thePlatformRowIsHiddenForNullAndBlankAndLabelledOtherwise() {
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, platform = null), now).platform)
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, platform = " "), now).platform)
        assertEquals("macOS", engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, platform = " macos "), now).platform)
        assertEquals("Windows", engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, platform = "windows"), now).platform)
    }

    @Test
    fun lastConnectedOnlyWhenNotConnectedAndTimestamped() {
        // Connected: never, even with a timestamp.
        assertNull(
            engineDetailUi(
                engineView(id = "e1", name = "n", state = EngineState.Connected, lastConnectedMs = now - 60_000),
                now,
            ).lastConnected,
        )
        // Not connected without a timestamp: hidden.
        assertNull(
            engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Reconnecting, lastConnectedMs = null), now).lastConnected,
        )
        // Not connected with a timestamp: the web's wording.
        assertEquals(
            "2h ago",
            engineDetailUi(
                engineView(id = "e1", name = "n", state = EngineState.Offline, lastConnectedMs = now - 7_200_000),
                now,
            ).lastConnected,
        )
        assertEquals(
            "just now",
            engineDetailUi(
                engineView(id = "e1", name = "n", state = EngineState.Parked(ParkReason.REVOKED), lastConnectedMs = now),
                now,
            ).lastConnected,
        )
    }

    @Test
    fun theDeviceIdRowIsHiddenForNullAndBlankAndRawOtherwise() {
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, deviceId = null), now).deviceId)
        assertNull(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected, deviceId = "  "), now).deviceId)
        assertEquals(
            "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95",
            engineDetailUi(
                engineView(
                    id = "e1",
                    name = "n",
                    state = EngineState.Connected,
                    deviceId = " 3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95 ",
                ),
                now,
            ).deviceId,
        )
    }

    @Test
    fun canRenameOnlyWhileConnectedAndPairAgainOnlyForRepairableParks() {
        for (state in listOf(EngineState.Connecting, EngineState.Reconnecting, EngineState.Offline, EngineState.Parked(ParkReason.REVOKED))) {
            val ui = engineDetailUi(engineView(id = "e1", name = "n", state = state), now)
            assertFalse("canRename for $state", ui.canRename)
        }
        assertTrue(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Connected), now).canRename)
        assertTrue(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Parked(ParkReason.IDENTITY_CHANGED)), now).showPairAgain)
        assertFalse(engineDetailUi(engineView(id = "e1", name = "n", state = EngineState.Parked(ParkReason.INCOMPATIBLE)), now).showPairAgain)
    }

    @Test
    fun theAddressIsTheBaseUrlVerbatim() {
        val ui = engineDetailUi(
            engineView(id = "e1", name = "n", state = EngineState.Connected, baseUrl = "https://studio.tail1234.ts.net:27655"),
            now,
        )
        assertEquals("https://studio.tail1234.ts.net:27655", ui.address)
    }

    @Test
    fun aReconnectingTailcatEngineSaysSo() {
        val view = engineView(id = "e1", name = "n", state = EngineState.Reconnecting, baseUrl = "tailcat:3f9a12bc")
        assertEquals("Reconnecting over Tailcat…", engineDetailUi(view.copy(tailcat = true), now).status)
        assertEquals("Reconnecting…", engineDetailUi(view, now).status)
    }

    @Test
    fun aTailcatEnginesAddressIsItsFingerprint() {
        val ui = engineDetailUi(
            engineView(id = "e1", name = "n", state = EngineState.Connected, host = "tailcat:3f9a12bc", baseUrl = "tailcat:3f9a12bc"),
            now,
        )
        assertEquals("Tailcat · 3f9a12bc", ui.address)
    }

    // ---------------------------------------------------------- statusRows

    @Test
    fun statusRowsShowAlwaysStatusAndAddressAndTheRestByRule() {
        val full = engineDetailUi(
            engineView(
                id = "e1",
                name = "n",
                state = EngineState.Offline,
                version = "0.9.2",
                platform = "macos",
                deviceId = "device-1",
                lastConnectedMs = now,
            ),
            now,
        )
        assertEquals(
            listOf(
                EngineStatusRow.Status,
                EngineStatusRow.Address,
                EngineStatusRow.Version("v0.9.2"),
                EngineStatusRow.Platform("macOS"),
                EngineStatusRow.LastConnected("just now"),
                EngineStatusRow.DeviceId("device-1"),
            ),
            statusRows(full),
        )
        assertEquals(
            listOf(EngineStatusRow.Status, EngineStatusRow.Address),
            // The widened fixture fills version/platform/deviceId by default;
            // the bare row clears them to leave only the always-on rows.
            statusRows(
                engineDetailUi(
                    engineView(
                        id = "e1",
                        name = "n",
                        state = EngineState.Connected,
                        deviceId = null,
                        platform = null,
                        version = null,
                    ),
                    now,
                ),
            ),
        )
    }

    @Test
    fun theRowTagsAreTheTicketTags() {
        val rows = statusRows(
            engineDetailUi(
                engineView(
                    id = "e1",
                    name = "n",
                    state = EngineState.Offline,
                    version = "0.9.2",
                    platform = "macos",
                    deviceId = "device-1",
                    lastConnectedMs = now,
                ),
                now,
            ),
        )
        assertEquals(
            listOf(
                "engine-detail-status",
                "engine-detail-address",
                "engine-detail-version",
                "engine-detail-platform",
                "engine-detail-last-connected",
                "engine-detail-device-id",
            ),
            rows.map { it.tag },
        )
    }

    // ------------------------------------------- the room row (ticket 04)

    /** A view with a room tag and the `rooms-v1` capability. */
    private fun roomEngineView(
        roomLabel: String?,
        capabilities: List<String>,
    ): uniffi.roboco_core.EngineView = engineView(
        id = "e-room",
        name = "Studio",
        state = EngineState.Connected,
    ).let { view ->
        view.copy(
            capabilities = capabilities,
            room = roomLabel?.let { EngineRoomRef(roomId = "r-1", label = it) },
        )
    }

    @Test
    fun theRoomRowShowsForARoomEngineAdvertisingRoomsV1() {
        val ui = engineDetailUi(
            roomEngineView("Home fleet", listOf("rooms-v1", "web-client")),
            now,
        )
        assertEquals("Home fleet", ui.room)
        assertEquals(
            listOf(
                "engine-detail-status",
                "engine-detail-address",
                "engine-detail-version",
                "engine-detail-platform",
                "engine-detail-room",
                "engine-detail-device-id",
            ),
            statusRows(ui).map { it.tag },
        )
    }

    @Test
    fun theRoomRowHidesWhenTheEngineLacksRoomsV1() {
        // The capability check is the hiding rule (ADR 0011): an old
        // engine shows no room row — and no error, no toast.
        val old = engineDetailUi(roomEngineView("Home fleet", listOf("web-client")), now)
        assertNull(old.room)
        assertFalse(statusRows(old).any { it.tag == "engine-detail-room" })
    }

    @Test
    fun theRoomRowHidesWhileTheCapabilitiesAreUnknown() {
        // An offline engine advertises nothing: the row hides until the
        // engine reconnects (degrade gracefully).
        val offline = roomEngineView("Home fleet", emptyList())
            .let { view ->
                view.copy(state = EngineState.Reconnecting)
            }
        val ui = engineDetailUi(offline, now)
        assertNull(ui.room)
    }

    @Test
    fun theRoomRowHidesForAnEngineWithoutARoom() {
        val plain = engineDetailUi(
            roomEngineView(null, listOf("rooms-v1")),
            now,
        )
        assertNull(plain.room)
        assertFalse(statusRows(plain).any { it.tag == "engine-detail-room" })
    }

    @Test
    fun aBlankRoomLabelHidesTheRow() {
        val blank = engineDetailUi(roomEngineView("  ", listOf("rooms-v1")), now)
        assertNull(blank.room)
    }

    // --------------------------------------------------------------- copy

    @Test
    fun theCopyEllipsesAreTheSingleCharacterU2026() {
        // "Copied" is a plain toast word (no ellipsis); only the menu-style
        // label carries U+2026.
        for (text in listOf(EngineDetailCopy.Rename)) {
            assertTrue("U+2026 expected in '$text'", text.endsWith("\u2026"))
        }
        assertEquals("Rename\u2026", EngineDetailCopy.Rename)
        assertEquals("Forget MacBook Pro?", EngineDetailCopy.forgetTitle("MacBook Pro"))
        assertEquals("Rename failed: ", EngineDetailCopy.RenameFailedPrefix)
        assertEquals("Engine not connected", EngineDetailCopy.NotConnected)
        assertEquals("Could not forget engine", EngineDetailCopy.ForgetFailed)
        assertEquals("Copy device ID", EngineDetailCopy.CopyIdAction)
    }

    @Test
    fun theTimingsMatchTheSources() {
        assertEquals(1_500L, EngineDetailTimings.CopiedHoldMs)
        assertEquals(3_000L, EngineDetailTimings.RevokeTimeoutMs)
        assertEquals(15_000L, EngineDetailTimings.NowTickMs)
    }
}

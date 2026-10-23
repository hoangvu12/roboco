package sh.roboco.android.settings

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Settings screen's pure parts (android-ui-parity #103): the
 * [SettingsSection] display order, the [settingsSectionFor] row-id →
 * section map (the order test's ordering key and the prefix table the
 * ticket documents), and [settingsBottomPadding]'s arithmetic over
 * ticket 30's worked `contentPadding` values. Plain JUnit, no
 * Robolectric; the batch/VPS run executes it.
 */
class SettingsSectionTest {

    // ------------------------------------------------------- the order

    @Test
    fun entriesAreTheEightSectionsInDisplayOrder() {
        assertEquals(
            listOf(
                SettingsSection.AgentAccounts,
                SettingsSection.Devices,
                SettingsSection.Notifications,
                SettingsSection.Background,
                SettingsSection.Appearance,
                SettingsSection.Wallpaper,
                SettingsSection.Sessions,
                SettingsSection.About,
            ),
            SettingsSection.entries,
        )
    }

    @Test
    fun sectionKeysMatchTheEmittersAndAreUnique() {
        // The keys are the groupedSection keys the section tickets emit
        // (102 devices, 99 notifications, 96 appearance, 97 wallpaper,
        // 100 sessions, 101 about) — unique, or the LazyColumn crashes on
        // a duplicate key.
        assertEquals("agent-accounts", SettingsSection.AgentAccounts.key)
        assertEquals("devices", SettingsSection.Devices.key)
        assertEquals("notifications", SettingsSection.Notifications.key)
        assertEquals("background", SettingsSection.Background.key)
        assertEquals("appearance", SettingsSection.Appearance.key)
        assertEquals("wallpaper", SettingsSection.Wallpaper.key)
        assertEquals("sessions", SettingsSection.Sessions.key)
        assertEquals("about", SettingsSection.About.key)
        assertEquals(
            SettingsSection.entries.size,
            SettingsSection.entries.map { it.key }.toSet().size,
        )
    }

    // ------------------------------------------------- the id → section map

    @Test
    fun agentAccountRowsMapToAgentAccounts() {
        // android-next#05: the harness rows and the four meta rows.
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-account:claude-code"))
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-accounts:none"))
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-accounts:loading"))
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-accounts:error"))
        assertEquals(SettingsSection.AgentAccounts, settingsSectionFor("agent-accounts:empty"))
        // The dropped iOS sections stay null, and a bare prefix is not a
        // row of this section.
        assertNull(settingsSectionFor("account"))
        assertNull(settingsSectionFor("agent-account"))
    }

    @Test
    fun engineAndPairRowsMapToDevices() {
        assertEquals(SettingsSection.Devices, settingsSectionFor("engine:abc"))
        // 102 shipped the iOS `device:` prefix (DevicesRowIds.engine keeps
        // it, and its KDoc hands it to this map) — the real rows' section.
        assertEquals(SettingsSection.Devices, settingsSectionFor("device:abc"))
        assertEquals(SettingsSection.Devices, settingsSectionFor(DevicesRowIds.Pair))
        assertEquals(SettingsSection.Devices, settingsSectionFor(DevicesRowIds.engine("e1")))
    }

    @Test
    fun notificationRowsMapToNotifications() {
        assertEquals(SettingsSection.Notifications, settingsSectionFor("notify:enabled"))
        assertEquals(SettingsSection.Notifications, settingsSectionFor("notify:done"))
        assertEquals(SettingsSection.Notifications, settingsSectionFor("notify:input"))
        assertEquals(SettingsSection.Notifications, settingsSectionFor("notify:failed"))
    }

    @Test
    fun theStayConnectedRowMapsToBackground() {
        assertEquals(SettingsSection.Background, settingsSectionFor(BackgroundSettingsCopy.StayConnectedId))
        assertEquals(SettingsSection.Background, settingsSectionFor("background:stayConnected"))
    }

    @Test
    fun appearanceAndWallpaperRowsMapToTheirSections() {
        assertEquals(SettingsSection.Appearance, settingsSectionFor("appearance:0"))
        assertEquals(SettingsSection.Appearance, settingsSectionFor("appearance:2"))
        assertEquals(SettingsSection.Wallpaper, settingsSectionFor("wallpaper:choose"))
        assertEquals(SettingsSection.Wallpaper, settingsSectionFor("wallpaper:effect"))
        assertEquals(SettingsSection.Wallpaper, settingsSectionFor("wallpaper:remove"))
    }

    @Test
    fun sessionRowsMapToSessions() {
        assertEquals(SettingsSection.Sessions, settingsSectionFor("groupByProject"))
        assertEquals(SettingsSection.Sessions, settingsSectionFor("archived"))
    }

    @Test
    fun aboutRowsMapToAbout() {
        assertEquals(SettingsSection.About, settingsSectionFor("about:version"))
        assertEquals(SettingsSection.About, settingsSectionFor("about:licenses"))
    }

    @Test
    fun droppedSectionsAndUnknownIdsMapToNull() {
        // iOS's Account, Voice and Sign Out are dropped, and their ids
        // (plus anything unknown) must not claim a section.
        assertNull(settingsSectionFor("account"))
        assertNull(settingsSectionFor("voice"))
        assertNull(settingsSectionFor("signout"))
        assertNull(settingsSectionFor(""))
        assertNull(settingsSectionFor("nope"))
    }

    // -------------------------------------------------- the bottom padding

    @Test
    fun bottomPaddingAddsTheTrailingMarginToTheChromePadding() {
        // Ticket 30's worked table + 16 (android-app#26): gesture nav with
        // a 24 dp bar → 150 + 16; a ≤ 21 bar → 147 + 16; 3-button nav →
        // 186 + 16; no chrome (the tablet dialog, gallery, previews) → 16.
        assertEquals(166f, settingsBottomPadding(150.dp).value, 1e-3f)
        assertEquals(163f, settingsBottomPadding(147.dp).value, 1e-3f)
        assertEquals(202f, settingsBottomPadding(186.dp).value, 1e-3f)
        assertEquals(16f, settingsBottomPadding(0.dp).value, 1e-3f)
    }

    @Test
    fun bottomPaddingComposesWithTheToken() {
        assertEquals(
            42f,
            settingsBottomPadding(26.dp).value,
            1e-3f,
        )
        assertEquals(16.dp, SettingsScreenTokens.BottomExtra)
    }
}

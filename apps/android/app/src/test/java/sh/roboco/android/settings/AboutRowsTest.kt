package sh.roboco.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.settingsTag

/**
 * The pure half of the Settings About section (android-ui-parity #101):
 * [versionSubtitle], [aboutRows] and [AboutSectionState]'s dispatch.
 * Plain JVM — no Compose, no org.json (the clipboard path is
 * [LicensesBehaviourTest], under Robolectric).
 */
class AboutRowsTest {

    // ------------------------------------------------------ the subtitle

    @Test
    fun versionSubtitleFormatsAllThreeVersions() {
        assertEquals(
            "Roboco 0.9.2 (9002) · core 0.9.2",
            versionSubtitle("0.9.2", 9002L, CoreStatus.Loaded("0.9.2")),
        )
        // The core may differ from the app (an engine-local build) — the
        // string still shows both, verbatim.
        assertEquals(
            "Roboco 0.9.2 (9002) · core 0.9.1",
            versionSubtitle("0.9.2", 9002L, CoreStatus.Loaded("0.9.1")),
        )
        assertEquals(
            "Roboco 1.2.0 (1002000) · core 1.2.0",
            versionSubtitle("1.2.0", 1002000L, CoreStatus.Loaded("1.2.0")),
        )
    }

    @Test
    fun versionSubtitleEndsWithCoreUnavailableWhenTheCoreFailed() {
        val subtitle = versionSubtitle("0.9.2", 9002L, CoreStatus.Failed("x"))
        assertEquals("Roboco 0.9.2 (9002) · core unavailable", subtitle)
        assertTrue("must end with core unavailable", subtitle.endsWith("core unavailable"))
    }

    // --------------------------------------------------------- the rows

    @Test
    fun aboutRowsReturnsExactlyTheTwoRows() {
        val subtitle = versionSubtitle("0.9.2", 9002L, CoreStatus.Loaded("0.9.2"))
        val rows = aboutRows(subtitle)

        assertEquals(2, rows.size)
        val version = rows[0]
        assertEquals(AboutRowIds.Version, version.id)
        assertEquals("Version", version.title)
        assertEquals(subtitle, version.subtitle)
        assertEquals(RobocoIcons.InfoCircle, version.icon)
        assertEquals(emptyList<GroupedAccessory>(), version.accessories)

        val licenses = rows[1]
        assertEquals(AboutRowIds.Licenses, licenses.id)
        assertEquals("Licenses", licenses.title)
        assertEquals("Third-party notices", licenses.subtitle)
        assertEquals(RobocoIcons.DocText, licenses.icon)
        assertEquals(listOf(GroupedAccessory.Disclosure), licenses.accessories)
    }

    @Test
    fun theRowIdsAndHeaderCopyAreTheTickets() {
        assertEquals("about:version", AboutRowIds.Version)
        assertEquals("about:licenses", AboutRowIds.Licenses)
        assertEquals("About", AboutSectionTitle)
        assertEquals("settings-about:version", settingsTag(AboutRowIds.Version))
        assertEquals("settings-about:licenses", settingsTag(AboutRowIds.Licenses))
        assertEquals("Copy", CopyActionLabel)
    }

    // ------------------------------------------------------- the dispatch

    @Test
    fun tappingLicensesOpensTheLicensesRouteAndTappingVersionDoesNothing() {
        var opened = 0
        var copied = 0
        val state = AboutSectionState(
            rows = aboutRows("Roboco 0.9.2 (9002) · core 0.9.2"),
            openLicenses = { opened++ },
            copy = { copied++ },
        )

        state.onRowClick(AboutRowIds.Licenses)
        assertEquals("the Licenses tap opens the screen", 1, opened)

        state.onRowClick(AboutRowIds.Version)
        state.onRowClick("about:unknown")
        assertEquals("no other tap opens anything", 1, opened)
        assertEquals("no tap copies", 0, copied)
    }

    @Test
    fun longPressingVersionCopiesExactlyTheSubtitle() {
        var copied = 0
        var copiedText: String? = null
        val subtitle = "Roboco 0.9.2 (9002) · core unavailable"
        val state = AboutSectionState(
            rows = aboutRows(subtitle),
            openLicenses = { },
            copy = {
                copied++
                copiedText = it
            },
        )

        state.onRowLongClick(AboutRowIds.Version)
        assertEquals(1, copied)
        assertEquals(subtitle, copiedText)

        // Long-pressing anything else (or an unknown id) does nothing.
        state.onRowLongClick(AboutRowIds.Licenses)
        state.onRowLongClick("about:unknown")
        assertEquals(1, copied)
    }

    @Test
    fun theNoticesConstantsAreVerbatim() {
        assertEquals(
            "Roboco bundles the SVG icon set and filename/folder associations from Symbols:\n" +
                "https://github.com/miguelsolorio/vscode-symbols/tree/296ef1b62287fb2315cb5651e552e09e8c8e1de8",
            SymbolsHeader,
        )
        assertTrue(SymbolsHeader.endsWith("296ef1b62287fb2315cb5651e552e09e8c8e1de8"))
        assertTrue(SolarNotice.startsWith("Solar Icons by 480 Design\n\n"))
        assertTrue(SolarNotice.contains("https://creativecommons.org/licenses/by/4.0/"))
        assertEquals("No license information", NoLicenseInformation)
        assertEquals("See license file", SeeLicenseFile)
    }
}

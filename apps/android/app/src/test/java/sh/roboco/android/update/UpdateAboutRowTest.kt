package sh.roboco.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.roboco.android.design.GroupedAccessory
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.settings.AboutRowIds
import sh.roboco.android.settings.AboutRowSpec

/**
 * The Settings → About update row (android-next#04, panels D/E/F): the
 * row spec per [UpdateUiState] — the update row while an update is known
 * (accent + disclosure + size/date subtitle), the GitHub row for
 * non-release builds, and the Check row's four subtitle states. Pure —
 * the strings are injected, so this runs on the plain JVM; the Robolectric
 * test mounts the same builder with the real resources.
 */
class UpdateAboutRowTest {

    private val strings = UpdateRowStrings(
        updateTitle = { v -> "Update to Roboco $v" },
        sizeDate = { mb, d -> "$mb MB · Released $d" },
        sizeOnly = { mb -> "$mb MB" },
        dateOnly = { d -> "Released $d" },
        checkTitle = "Check for Updates",
        checking = "Checking…",
        upToDate = "Roboco is up to date",
        upToDateAt = { rel -> "Roboco is up to date · checked $rel" },
        error = { reason -> "Couldn't check: $reason" },
        reason = { r -> r.wire },
        githubTitle = { v -> "Roboco $v on GitHub" },
        githubSubtitle = "Debug build: install the release APK manually",
        relative = { ago ->
            when (ago) {
                CheckedAgo.Now -> "just now"
                is CheckedAgo.Minutes -> "${ago.n} min ago"
                is CheckedAgo.Hours -> "${ago.n} h ago"
                CheckedAgo.Yesterday -> "yesterday"
            }
        },
    )

    private val manifest = ReleaseManifest.parse(
        """
        {"version": "0.10.0", "date": "2026-10-20T13:37:00Z",
         "files": {"roboco-0.10.0-android-arm64-v8a.apk": {"sha256": "aa", "size": 24530000}}}
        """,
    )!!

    private fun row(state: UpdateUiState, installEnabled: Boolean = true, nowMs: Long = 1_000_000L) =
        updateAboutRow(state, strings, installEnabled, nowMs, deviceAbi = "arm64-v8a")

    // --------------------------------------------- the update row (D)

    @Test
    fun anAvailableUpdateIsAnAccentDisclosureRow() {
        val r = row(UpdateUiState.Available(manifest, checkedAtMs = 999_000L))!!
        assertEquals(AboutRowIds.Update, r.id)
        assertEquals("Update to Roboco 0.10.0", r.title)
        assertEquals("24.5 MB · Released Oct 20", r.subtitle)
        assertEquals(RobocoIcons.ArrowDown, r.icon)
        assertEquals(true, r.accent)
        assertEquals(listOf(GroupedAccessory.Disclosure), r.accessories)
    }

    @Test
    fun sizeAndDateAreEachOptional() {
        val noDate = manifest.copy(date = null)
        assertEquals("24.5 MB", row(UpdateUiState.Available(noDate, 0L))!!.subtitle)
        val noSize = ReleaseManifest.parse(
            """{"version": "0.10.0", "date": "2026-10-20T13:37:00Z",
                "files": {"roboco-0.10.0-android-arm64-v8a.apk": {"sha256": "aa"}}}""",
        )!!
        assertEquals("Released Oct 20", row(UpdateUiState.Available(noSize, 0L))!!.subtitle)
        val bare = ReleaseManifest.parse("""{"version": "0.10.0"}""")!!
        assertNull(row(UpdateUiState.Available(bare, 0L))!!.subtitle)
    }

    // --------------------------------------- the GitHub row (F, non-release)

    @Test
    fun aNonReleaseBuildGetsTheGitHubRow() {
        val r = row(UpdateUiState.Available(manifest, 0L), installEnabled = false)!!
        assertEquals(AboutRowIds.Update, r.id)
        assertEquals("Roboco 0.10.0 on GitHub", r.title)
        assertEquals("Debug build: install the release APK manually", r.subtitle)
        assertEquals(RobocoIcons.ArrowUpRight, r.icon)
        assertEquals(true, r.accent)
        assertEquals(listOf(GroupedAccessory.Disclosure), r.accessories)
    }

    // ------------------------------------------- the check row (E)

    @Test
    fun idleHasNoSubtitle() {
        val r = row(UpdateUiState.Idle)!!
        assertEquals(AboutRowIds.Check, r.id)
        assertEquals("Check for Updates", r.title)
        assertNull(r.subtitle)
        assertEquals(RobocoIcons.Refresh, r.icon)
        assertEquals(false, r.accent)
        assertEquals(emptyList<GroupedAccessory>(), r.accessories)
    }

    @Test
    fun checkingShowsTheEllipsis() {
        assertEquals("Checking…", row(UpdateUiState.Checking)!!.subtitle)
    }

    @Test
    fun upToDateCarriesTheCheckedRelativeAge() {
        val now = 1_000_000L
        val r = row(UpdateUiState.UpToDate(checkedAtMs = now - 2 * 60_000), nowMs = now)!!
        assertEquals("Roboco is up to date · checked 2 min ago", r.subtitle)
    }

    @Test
    fun anUnknownCheckTimeShowsThePlainVerdict() {
        assertEquals("Roboco is up to date", row(UpdateUiState.UpToDate(checkedAtMs = 0L))!!.subtitle)
    }

    @Test
    fun anErrorCarriesTheReason() {
        val r = row(UpdateUiState.Failed(UpdateCheckReason.OFFLINE, checkedAtMs = 0L))!!
        assertEquals("Couldn't check: offline", r.subtitle)
    }
}

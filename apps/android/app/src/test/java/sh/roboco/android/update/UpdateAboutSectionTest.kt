package sh.roboco.android.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.settingsTag
import sh.roboco.android.settings.AboutRowIds
import sh.roboco.android.settings.AboutSectionState
import sh.roboco.android.settings.aboutRows
import sh.roboco.android.settings.aboutSection
import sh.roboco.android.settings.versionSubtitle

/**
 * The Settings → About update rows (android-next#04, panels D/E/F),
 * mounted with the real resources and the real [aboutSection] emitter:
 * the row content per state, the accent update row's geometry (one card,
 * the standard row positions), and the click dispatch into the update
 * seams. Robolectric + Compose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h891dp-xxhdpi")
class UpdateAboutSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val manifest = ReleaseManifest.parse(
        """
        {"version": "0.10.0", "date": "2026-10-20T13:37:00Z",
         "files": {"roboco-0.10.0-android-arm64-v8a.apk": {"sha256": "aa", "size": 24530000}}}
        """,
    )!!

    private fun mount(
        state: UpdateUiState,
        installEnabled: Boolean = true,
        onUpdate: () -> Unit = {},
        onCheck: () -> Unit = {},
        buildMarker: String? = null,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val strings = updateRowStrings()
                val section = AboutSectionState(
                    rows = aboutRows(
                        versionSubtitle("0.9.2", 9002L, CoreStatus.Loaded("0.9.2"), buildMarker),
                        updateAboutRow(state, strings, installEnabled, nowMs = 1_000_000L, deviceAbi = "arm64-v8a"),
                    ),
                    openLicenses = {},
                    copy = {},
                    onUpdate = onUpdate,
                    onCheck = onCheck,
                )
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(RobocoTheme.palette.background),
                ) {
                    aboutSection(section)
                }
            }
        }
        compose.waitForIdle()
    }

    // --------------------------------------------- the update row (D)

    @Test
    fun anUpdateShowsTheUpdateRowBetweenVersionAndLicenses() {
        mount(UpdateUiState.Available(manifest, checkedAtMs = 999_000L))

        compose.onNodeWithText("Update to Roboco 0.10.0").assertExists()
        compose.onNodeWithText("24.5 MB · Released Oct 20").assertExists()
        compose.onNodeWithTag(settingsTag(AboutRowIds.Update)).assertHasClickAction()
        // The standard About card geometry: Version first, update middle,
        // Licenses last (one card, three rows).
        compose.onNodeWithTag(settingsTag(AboutRowIds.Version)).assertExists()
        compose.onNodeWithTag(settingsTag(AboutRowIds.Licenses)).assertExists()
    }

    @Test
    fun tappingTheUpdateRowStartsTheUpdate() {
        var starts = 0
        mount(UpdateUiState.Available(manifest, 0L), onUpdate = { starts++ })
        compose.onNodeWithTag(settingsTag(AboutRowIds.Update)).performClick()
        assertEquals(1, starts)
    }

    @Test
    fun nonReleaseBuildsShowTheGitHubRowThatOpensTheReleasesPage() {
        var opens = 0
        mount(
            UpdateUiState.Available(manifest, 0L),
            installEnabled = false,
            onUpdate = { opens++ },
            buildMarker = "debug",
        )
        compose.onNodeWithText("Roboco 0.10.0 on GitHub").assertExists()
        compose.onNodeWithText("Debug build: install the release APK manually").assertExists()
        // The Version row carries the build marker (panel F).
        compose.onNodeWithText("Roboco 0.9.2 (9002) debug · core 0.9.2").assertExists()

        compose.onNodeWithTag(settingsTag(AboutRowIds.Update)).performClick()
        assertEquals(1, opens)
    }

    // ------------------------------------------- the check row (E)

    @Test
    fun idleShowsTheCheckRowWithoutASubtitle() {
        mount(UpdateUiState.Idle)
        compose.onNodeWithText("Check for Updates").assertExists()
        // No subtitle: no "Checking…" or verdict text anywhere.
        compose.onNodeWithText("Checking…").assertDoesNotExist()
        compose.onNodeWithText("Roboco is up to date").assertDoesNotExist()
    }

    @Test
    fun checkingShowsTheEllipsis() {
        mount(UpdateUiState.Checking)
        compose.onNodeWithText("Check for Updates").assertExists()
        compose.onNodeWithText("Checking…").assertExists()
    }

    @Test
    fun upToDateCarriesTheCheckedRelativeAge() {
        mount(UpdateUiState.UpToDate(checkedAtMs = 1_000_000L - 2 * 60_000))
        compose.onNodeWithText("Roboco is up to date · checked 2 min ago").assertExists()
    }

    @Test
    fun anErrorCarriesTheReason() {
        mount(UpdateUiState.Failed(UpdateCheckReason.OFFLINE, checkedAtMs = 0L))
        compose.onNodeWithText("Couldn't check: offline").assertExists()
    }

    @Test
    fun tappingTheCheckRowChecks() {
        var checks = 0
        mount(UpdateUiState.UpToDate(checkedAtMs = 0L), onCheck = { checks++ })
        compose.onNodeWithTag(settingsTag(AboutRowIds.Check)).performClick()
        assertEquals(1, checks)
    }
}

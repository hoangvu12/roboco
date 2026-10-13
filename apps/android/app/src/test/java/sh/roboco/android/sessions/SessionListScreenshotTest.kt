package sh.roboco.android.sessions

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LinearFontScaleDensity
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme

/**
 * The shared session-list goldens (android-ui-parity #41): the fixture
 * in light and dark, "Mobile" folded with the working live glyph at
 * t = 0, the current row 3 (`rowActive`), the pressed row 2
 * (controlFill, held down past the tap timeout), the font-scale-1.6
 * re-measure, the folder + plain-header demo, and the overlay states.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp) on the ticket's
 * 393 × 852 dp phone; the glyph clock is frozen at 0 ns so the spinner
 * rows are byte-stable, and the safe padding is the worked example's
 * (top 155 = 59 status + 44 nav row + 52 large title, bottom 110), so
 * the overlay anchor sits at (196.5, 408.5) exactly. Roborazzi paths are
 * relative to `roborazzi { outputDir }` (`src/test/screenshots`), so the
 * goldens land at `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.SessionListScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`, then reviewed side
 * by side with `pr-assets/group-by-project/1-off.png` (row and header
 * rhythm 62/40 dp, no gaps, no separators; row internals are 37/38/39's).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionListScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val safePadding = PaddingValues(top = 155.dp, bottom = 110.dp)

    /** Hosts the fixture list; [fontScale] goes through the linear density. */
    private fun shot(
        appearance: Appearance,
        file: String,
        sections: List<ListSection> = SessionListFixtures.sections(),
        collapsible: Boolean = true,
        collapsedIds: Set<String> = emptySet(),
        currentChatId: String? = null,
        fontScale: Float = 1f,
        loading: Boolean = false,
        empty: SessionListEmpty = SessionListEmpty.None,
        holdPressedRow: String? = null,
    ) {
        val density = LinearFontScaleDensity(3f, fontScale)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                RobocoTheme(appearance) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        SessionList(
                            model = rememberSessionListModel(
                                sections = sections,
                                collapsible = collapsible,
                                collapsedIds = collapsedIds,
                                loading = loading,
                                empty = empty,
                            ),
                            contentPadding = safePadding,
                            onOpenSession = {},
                            currentChatId = currentChatId,
                        )
                    }
                }
            }
        }
        if (holdPressedRow != null) {
            // Hold the press: the delayed Press has landed (the tap timeout
            // passes under the frozen clock) and the row draws controlFill.
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("session-$holdPressedRow").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(200)
        } else {
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("sessions/$file")
    }

    // ------------------------------------------------------ the fixture

    @Test
    fun lightGolden() {
        shot(Appearance.Light, "session-list-light.png")
    }

    @Test
    fun darkGolden() {
        shot(Appearance.Dark, "session-list-dark.png")
    }

    @Test
    fun foldedWithWorkingLiveGolden() {
        shot(
            Appearance.Light,
            "session-list-folded-light.png",
            sections = SessionListFixtures.sections(workingLive = true),
            collapsedIds = setOf("mobile"),
        )
    }

    @Test
    fun currentRowGolden() {
        shot(
            Appearance.Dark,
            "session-list-current-dark.png",
            currentChatId = SessionListFixtures.currentRow.id,
        )
    }

    @Test
    fun pressedRowGolden() {
        shot(Appearance.Light, "session-list-pressed-light.png", holdPressedRow = "chat-hibernation")
    }

    @Test
    fun fontScale160Golden() {
        shot(Appearance.Light, "session-list-scale-160-light.png", fontScale = 1.6f)
    }

    @Test
    fun folderAndPlainHeaderGolden() {
        shot(
            Appearance.Light,
            "session-list-folder-plain-light.png",
            sections = SessionListFixtures.folderPlainSections(),
            collapsible = false,
        )
    }

    // -------------------------------------------------- the overlay states

    @Test
    fun loadingGolden() {
        shot(Appearance.Light, "session-list-loading-light.png", sections = emptyList(), loading = true)
    }

    @Test
    fun emptySessionsLightGolden() {
        shot(
            Appearance.Light,
            "session-list-empty-light.png",
            sections = emptyList(),
            empty = SessionListEmpty.Sessions,
        )
    }

    @Test
    fun emptySessionsDarkGolden() {
        shot(
            Appearance.Dark,
            "session-list-empty-dark.png",
            sections = emptyList(),
            empty = SessionListEmpty.Sessions,
        )
    }

    @Test
    fun emptyArchivedGolden() {
        shot(
            Appearance.Light,
            "session-list-empty-archived-light.png",
            sections = emptyList(),
            empty = SessionListEmpty.Archived,
        )
    }
}

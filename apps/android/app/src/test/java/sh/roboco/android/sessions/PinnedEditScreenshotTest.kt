package sh.roboco.android.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.TabId
import sh.roboco.android.shell.TabRootRoute
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.ProjectRef
import uniffi.roboco_core.PullRequest
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The Pinned Edit-mode goldens (android-ui-parity #47): the Pinned folder
 * screen in Edit mode, light and dark — the trailing "Done" capsule,
 * every row narrowed by the 52 dp handle slot, the hairline separator,
 * the `line.3.horizontal` glyph — plus one row fully lifted
 * (`progress = 1`): the opaque `elevated` fill inset 8 / 1 with 16 dp
 * corners and the 8 dp shadow.
 *
 * The fixture is the ticket's: **three pins on one engine plus two on
 * another** (`eng1` / `eng2`), one with a PR badge and one Working, so
 * the merged engine-block order is visible. `initialEditing = true`
 * composes the Edit state directly (the 300 ms slot toggle never
 * animates from zero — the goldens are the settled state); the capsule
 * and the handles are asserted before the capture.
 *
 * Robolectric with NATIVE graphics at xxhdpi (3 px/dp) on the phone
 * frame the ticket names, 393 × 852 dp, with a fixed 24 dp status bar
 * (Robolectric reports no insets); the glyph clock is frozen at 0 ns so
 * the Working row's spinner renders its cycle-0 pattern. Roborazzi
 * paths are relative to `roborazzi { outputDir }`
 * (`src/test/screenshots`), so the goldens land at
 * `src/test/screenshots/sessions/`. Recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.sessions.PinnedEditScreenshotTest`,
 * verified by `:app:verifyRoborazziDebug --tests …`, then reviewed side
 * by side with an iOS 26 simulator capture (Pinned folder, Edit tapped,
 * 3 pins) — handle position, separator presence and content shift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PinnedEditScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------- Edit mode

    @Test
    fun editLight() {
        editShot(Appearance.Light, "pinned_edit_light.png")
    }

    @Test
    fun editDark() {
        editShot(Appearance.Dark, "pinned_edit_dark.png")
    }

    /**
     * Hosts the Pinned folder as a pushed screen with Edit mode on and
     * captures the whole window. The stack is pushed hostless before the
     * content is set, so the push transition is already committed.
     */
    private fun editShot(appearance: Appearance, file: String) {
        val stack = NavStack(TabRootRoute(TabId.Sessions)).apply { push(FolderRoute.pinned(), animated = false) }
        compose.setContent {
            RobocoTheme(appearance) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    GlassMenuHost {
                        NavStackHost(stack = stack, active = true) { entry ->
                            val r = entry.route
                            if (r is FolderRoute) {
                                FolderScreen(
                                    route = r,
                                    workspace = PinnedEditFixtures.workspace(),
                                    loading = false,
                                    topInset = 24.dp,
                                    initialEditing = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        // The capsule reads "Done" and the handles exist before capturing.
        compose.onNodeWithTag("pinned-edit").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithTag("reorder-${PinnedEditFixtures.firstId}").assertExists()
        compose.onRoot().captureRoboImage("sessions/$file")
    }

    // ------------------------------------------------------------ lifted row

    @Test
    fun rowLiftedLight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                        LiftedRowSample(PinnedEditFixtures.prBadgeVm(), Modifier.fillMaxWidth())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("sessions/pinned_row_lifted_light.png")
    }
}

/** The ticket's fixture: 3 pins on `eng1`, 2 on `eng2`, one PR badge, one Working. */
private object PinnedEditFixtures {

    private fun row(
        id: String,
        title: String,
        engineId: String,
        project: String? = "zeron",
        branch: String? = "main",
        indicator: ChatIndicator = ChatIndicator.IDLE,
        pullRequest: PullRequest? = null,
        unseen: Boolean = false,
    ): SessionRow = SessionRow(
        id = id,
        engineId = engineId,
        revision = 1uL,
        title = title,
        hasTitle = true,
        preview = null,
        project = project?.let { ProjectRef(id = "prj-$it", name = it, colorIndex = 7u) },
        deviceId = "device-$engineId",
        deviceName = "MacBook Pro",
        deviceOnline = true,
        engineOnline = true,
        harness = "claude-code",
        harnessLabel = null,
        model = null,
        modelLabel = null,
        reasoning = null,
        branch = branch,
        cwd = null,
        indicator = indicator,
        hostIndicator = indicator,
        workingSinceMs = if (indicator == ChatIndicator.WORKING) 90_000L else null,
        lastActivityMs = 0L,
        timeLabel = "2h",
        createdAtMs = 0L,
        unseen = unseen,
        archived = false,
        pinned = true,
        sectionId = null,
        pullRequest = pullRequest,
        sendState = null,
        parentChatId = null,
    )

    private fun pr(number: Long, title: String, branch: String): PullRequest = PullRequest(
        provider = "github",
        number = number.toULong(),
        title = title,
        url = "https://github.com/zeronsh/zeron/pull/$number",
        state = PullRequestState.OPEN,
        baseRef = "main",
        headRef = branch,
    )

    /** The PR-badge pin (engine `eng1`): golden body and the list's first row. */
    val firstId = "chat-scroll-pinning"

    private val prBadge = row(
        id = firstId,
        title = "Transcript scroll pinning",
        engineId = "eng1",
        branch = "scroll-pinning",
        pullRequest = pr(102, "Transcript scroll pinning", "scroll-pinning"),
        unseen = true,
    )

    /** The Working pin (engine `eng1`). */
    private val working = row(
        id = "chat-hibernation",
        title = "Durable Object hibernation flush",
        engineId = "eng1",
        project = "edge",
        indicator = ChatIndicator.WORKING,
    )

    private val keyboard = row(id = "chat-keyboard", title = "Keyboard avoidance for composer", engineId = "eng1")

    private val launchPost = row(id = "chat-launch-post", title = "Draft the launch post", engineId = "eng2", project = "blog")

    private val deploy = row(id = "chat-deploy", title = "Wrangler deploy hygiene", engineId = "eng2", project = "edge")

    fun workspace(): WorkspaceSnapshot = WorkspaceSnapshot(
        revision = 1uL,
        computedAtMs = 0L,
        pinsReady = true,
        synced = true,
        front = FrontPage(
            pinned = listOf(prBadge, working, keyboard, launchPost, deploy),
            sections = emptyList(),
            recent = emptyList(),
            groups = emptyList(),
        ),
        projects = emptyList(),
        projectless = emptyList(),
        pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
        archived = emptyList(),
        devices = emptyList(),
    )

    /** The lifted-row golden's view model (37's `SessionRowVM.from`). */
    fun prBadgeVm(): SessionRowVM = SessionRowVM.from(prBadge)
}

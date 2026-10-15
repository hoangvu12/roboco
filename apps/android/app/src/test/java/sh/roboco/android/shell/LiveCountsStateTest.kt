package sh.roboco.android.shell

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.sessions.SessionsScreenFixtures
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * /code-review F3 (ticket 27 step 4): the shell owner's live counts follow
 * the workspace feed. `RobocoAppContent` collects
 * `rememberLiveCounts(app.workspaceFeed.snapshot)` and passes it to
 * `MainTabShell(counts = …)`; before this the production shell never got
 * counts and the accessory's summary stayed empty. The derivation rules
 * themselves are `LiveCountsTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveCountsStateTest {

    @get:Rule
    val compose = createComposeRule()

    private val base = SessionsScreenFixtures.recentRows.first()

    private fun snapshot(revision: ULong, vararg indicators: ChatIndicator): WorkspaceSnapshot =
        WorkspaceSnapshot(
            revision = revision,
            computedAtMs = 0L,
            pinsReady = false,
            synced = true,
            front = FrontPage(
                pinned = emptyList(),
                sections = emptyList(),
                recent = indicators.mapIndexed { i, indicator ->
                    base.copy(id = "chat-$i", indicator = indicator, hostIndicator = indicator)
                },
                groups = emptyList(),
            ),
            projects = emptyList(),
            projectless = emptyList(),
            pullRequests = PullRequestGroups(emptyList(), emptyList(), emptyList()),
            archived = emptyList(),
            devices = emptyList(),
        )

    @Test
    fun theOwnersCountsFollowTheWorkspaceFeed() {
        val feed = MutableStateFlow<WorkspaceSnapshot?>(null)
        var seen: LiveCounts? = null
        compose.setContent {
            val counts by rememberLiveCounts(feed)
            seen = counts
        }
        compose.waitForIdle()
        assertEquals("no snapshot yet: the empty counts", LiveCounts(), seen)

        feed.value = snapshot(1uL, ChatIndicator.WORKING, ChatIndicator.AWAITING_INPUT, ChatIndicator.IDLE)
        // The derivation runs on Dispatchers.Default: wait for it to land.
        compose.waitUntil(5_000) { seen == LiveCounts(working = 1, awaiting = 1) }

        feed.value = snapshot(2uL, ChatIndicator.COMPLETED)
        compose.waitUntil(5_000) { seen == LiveCounts() }
        assertEquals(LiveCounts(), seen)
    }
}

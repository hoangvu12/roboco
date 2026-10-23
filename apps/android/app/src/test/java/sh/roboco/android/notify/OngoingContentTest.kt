package sh.roboco.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground service's ongoing notification content (android-app#28):
 * 1 vs n runs, the "needs/need" plural, the subText and chronometer
 * inputs, the promotion rule. Plain JUnit — the derivation is pure.
 */
class OngoingContentTest {

    private val now = 1_700_000_000_000L

    private fun run(
        chatId: String,
        title: String,
        startedAt: Long? = now - 60_000,
        fromThisDevice: Boolean = false,
        engine: String = "Mac",
    ) = WorkingRun(
        chatId = chatId,
        title = title,
        engineName = engine,
        startedAtMs = startedAt,
        fromThisDevice = fromThisDevice,
    )

    // -------------------------------------------------- one run

    @Test
    fun oneRunShowsTheTitleTheWorkingWordAndItsDeepLink() {
        val content = ongoingContent(
            OngoingInput(working = listOf(run("engine:v1:a", "Fix the login bug")), awaiting = 0, engineCount = 1, liveUpdateDismissed = false),
            now,
            apiPromotionPossible = true,
        )
        assertEquals("Fix the login bug", content.title)
        assertEquals("Working", content.text)
        assertEquals("Working", content.chip)
        assertEquals("engine:v1:a", content.tapChatId)
        assertEquals(now - 60_000, content.whenMs)
        // One engine: no subText.
        assertNull(content.subText)
        // The desktop started it: ambient, never promoted.
        assertFalse(content.promoteLiveUpdate)
    }

    @Test
    fun oneBlankTitleFallsBackToNewSessionAndTwoEnginesShowTheSubText() {
        val content = ongoingContent(
            OngoingInput(working = listOf(run("engine:v1:a", "   ")), awaiting = 0, engineCount = 2, liveUpdateDismissed = false),
            now,
            apiPromotionPossible = false,
        )
        assertEquals("New session", content.title)
        assertEquals("Mac", content.subText)
        assertEquals("Working", content.chip)
    }

    @Test
    fun oneRunWithoutAStartSkipsTheChronometer() {
        val content = ongoingContent(
            OngoingInput(working = listOf(run("engine:v1:a", "A", startedAt = null)), awaiting = 0, engineCount = 1, liveUpdateDismissed = false),
            now,
            apiPromotionPossible = false,
        )
        assertEquals(now, content.whenMs)
    }

    // -------------------------------------------------- n ≥ 2 runs

    @Test
    fun twoRunsShowTheAccessorySummaryAndJoinTheTitles() {
        val content = ongoingContent(
            OngoingInput(
                working = listOf(run("engine:v1:a", "Alpha"), run("engine:v1:b", "Beta")),
                awaiting = 0,
                engineCount = 3,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = false,
        )
        assertEquals("2 working", content.title)
        assertEquals("Alpha, Beta", content.text)
        assertEquals("2 runs", content.chip)
        // The app root (the Sessions tab), not one chat.
        assertNull(content.tapChatId)
        assertNull(content.subText)
    }

    @Test
    fun awaitingJoinsTheSummaryWithTheNeedPlural() {
        fun content(awaiting: Int) = ongoingContent(
            OngoingInput(
                working = listOf(run("engine:v1:a", "Alpha"), run("engine:v1:b", "Beta")),
                awaiting = awaiting,
                engineCount = 1,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = false,
        )
        assertEquals("2 working", content(0).title)
        assertEquals("2 working · 1 needs you", content(1).title)
        assertEquals("2 working · 3 need you", content(3).title)
    }

    @Test
    fun nRunsUseTheEarliestStartForTheChronometer() {
        val content = ongoingContent(
            OngoingInput(
                working = listOf(
                    run("engine:v1:a", "Alpha", startedAt = now - 100_000),
                    run("engine:v1:b", "Beta", startedAt = now - 50_000),
                ),
                awaiting = 0,
                engineCount = 1,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = false,
        )
        assertEquals(now - 100_000, content.whenMs)
        // A missing start counts as now, never as the minimum.
        val withUnknown = ongoingContent(
            OngoingInput(
                working = listOf(
                    run("engine:v1:a", "Alpha", startedAt = null),
                    run("engine:v1:b", "Beta", startedAt = now - 50_000),
                ),
                awaiting = 0,
                engineCount = 1,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = false,
        )
        assertEquals(now - 50_000, withUnknown.whenMs)
    }

    // -------------------------------------------------- promotion

    @Test
    fun promotionNeedsThePlatformAFromThisDeviceRunAndNoDismissal() {
        fun promote(possible: Boolean, fromDevice: Boolean, dismissed: Boolean): Boolean =
            ongoingContent(
                OngoingInput(
                    working = listOf(run("engine:v1:a", "A", fromThisDevice = fromDevice)),
                    awaiting = 0,
                    engineCount = 1,
                    liveUpdateDismissed = dismissed,
                ),
                now,
                apiPromotionPossible = possible,
            ).promoteLiveUpdate

        assertTrue(promote(possible = true, fromDevice = true, dismissed = false))
        // The platform (API < 36 or the setting off) refuses.
        assertFalse(promote(possible = false, fromDevice = true, dismissed = false))
        // A desktop-started run is ambient — "must be user-initiated".
        assertFalse(promote(possible = true, fromDevice = false, dismissed = false))
        // A dismissed Live Update is never requested again until the
        // working set changes (the controller resets the flag then).
        assertFalse(promote(possible = true, fromDevice = true, dismissed = true))
    }

    @Test
    fun nRunsPromoteWhenAnyRunStartedOnThisPhone() {
        val content = ongoingContent(
            OngoingInput(
                working = listOf(
                    run("engine:v1:a", "Alpha", fromThisDevice = false),
                    run("engine:v1:b", "Beta", fromThisDevice = true),
                ),
                awaiting = 0,
                engineCount = 1,
                liveUpdateDismissed = false,
            ),
            now,
            apiPromotionPossible = true,
        )
        assertTrue(content.promoteLiveUpdate)
    }

    // -------------------------------------------------- empty

    @Test
    fun anEmptyWorkingSetRendersTheIdleStayingConnectedContent() {
        // The linger after the last run, or Stay connected (ADR 0010).
        val content = ongoingContent(
            OngoingInput(working = emptyList(), awaiting = 0, engineCount = 2, liveUpdateDismissed = false),
            now,
            apiPromotionPossible = true,
        )
        assertEquals("Staying connected", content.title)
        assertEquals("Keeping 2 engines connected", content.text)
        assertEquals("", content.chip)
        assertNull("no chronometer", content.whenMs)
        assertNull(content.subText)
        assertNull("the tap opens the app root", content.tapChatId)
        assertFalse("never promoted", content.promoteLiveUpdate)
        assertFalse("no progress", content.working)
    }

    @Test
    fun theIdleContentCountsOneEngineAndTheChatsThatNeedYou() {
        fun idle(engines: Int, awaiting: Int) = ongoingContent(
            OngoingInput(working = emptyList(), awaiting = awaiting, engineCount = engines, liveUpdateDismissed = false),
            now,
            apiPromotionPossible = false,
        ).text
        assertEquals("Keeping 1 engine connected", idle(1, 0))
        assertEquals("Keeping 1 engine connected · 1 needs you", idle(1, 1))
        assertEquals("Keeping 3 engines connected · 2 need you", idle(3, 2))
    }
}

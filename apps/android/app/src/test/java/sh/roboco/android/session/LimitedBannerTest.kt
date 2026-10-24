package sh.roboco.android.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.ResumeCandidates
import uniffi.roboco_core.ResumeSwitchOutcome
import uniffi.roboco_core.SessionLimit
import uniffi.roboco_core.CoreException

/**
 * The Limited banner (the failover port) on the plain JVM: the pure
 * derivations — label, strip actions, picker rows, outcome notices, the
 * reset captions — plus [LimitedBanner] over a recording [LimitedCore]
 * fake: the TTL'd candidate loads, the failed-load retry, the switch
 * flow with its notice, and Resume through the send seam. `runTest` with
 * one dispatcher for the filler's scope and io, so the sequences run on
 * virtual time (the [AgentAccountsModelTest] discipline).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LimitedBannerTest {

    // ------------------------------------------------------------ the fakes

    /** Records every call in order; knobs for the answers and failures. */
    private class FakeLimitedCore : LimitedCore {
        val calls = mutableListOf<String>()
        var candidatesAnswer: ResumeCandidates? = null
        var candidatesThrows: Throwable? = null
        var switchAnswer: ResumeSwitchOutcome = ResumeSwitchOutcome.NoCandidates
        var switchThrows: Throwable? = null

        override suspend fun candidates(chatId: String): ResumeCandidates {
            calls += "candidates"
            candidatesThrows?.let { throw it }
            return candidatesAnswer ?: ResumeCandidates(
                chatId = chatId,
                harness = "claude-code",
                limitedAccountId = null,
                candidates = emptyList(),
                signedOut = emptyList(),
            )
        }

        override suspend fun switch(chatId: String, accountId: String?): ResumeSwitchOutcome {
            calls += "switch:$accountId"
            switchThrows?.let { throw it }
            return switchAnswer
        }

        override suspend fun armResumeAtReset(chatId: String) {
            calls += "arm"
        }

        override suspend fun cancelScheduledResume(chatId: String) {
            calls += "cancel"
        }
    }

    /** A clock the tests pin. */
    private class PinnedClock(var now: Long = 1_000_000L)

    // ------------------------------------------------------------ fixtures

    private fun account(
        id: String,
        email: String? = null,
        plan: String? = null,
        windows: List<AgentUsageWindow> = emptyList(),
    ) = AgentAccount(
        id = id,
        harness = "claude-code",
        email = email,
        planLabel = plan,
        active = false,
        usageWindows = windows,
        usageFetchedAtMs = null,
        usageError = null,
        displayName = null,
        organization = null,
        apiKey = false,
        switchable = true,
        provider = null,
    )

    private fun window(label: String, fraction: Float, resetsAt: Long? = null) =
        AgentUsageWindow(label = label, usedFraction = fraction, resetsAtMs = resetsAt)

    private fun candidates(
        list: List<AgentAccount> = emptyList(),
        signedOut: List<AgentAccount> = emptyList(),
    ) = ResumeCandidates(
        chatId = "chat-a",
        harness = "claude-code",
        limitedAccountId = "slot-1",
        candidates = list,
        signedOut = signedOut,
    )

    private val limit = SessionLimit(
        harness = "claude-code",
        window = "5-hour",
        resetsAtMs = 1_000_000L + 45 * 60_000L,
        accountId = "slot-1",
    )

    private fun input(
        limit: SessionLimit? = this.limit,
        fireAt: Long? = null,
    ) = ChromeInput(
        chatId = "chat-a",
        composer = composerState(chatId = "chat-a", limit = limit, scheduledResumeFireAtMs = fireAt),
        row = sessionRow(),
        engine = engineView(),
    )

    private fun banner(core: LimitedCore, clock: PinnedClock, scope: TestScope) =
        LimitedBanner(
            core = core,
            io = StandardTestDispatcher(scope.testScheduler),
            nowMs = { clock.now },
            continuePrompt = { "Continue where you left off." },
            logW = { _, _ -> },
        )

    // ------------------------------------------------------------ derivations

    @Test
    fun the_countdown_mirrors_the_proto_rounding() {
        val now = 1_000_000L
        assertEquals("resets in 45m", formatLimitReset(now + 45 * 60_000L, now))
        assertEquals("resets in 0m", formatLimitReset(now + 30_000L, now))
        assertEquals("resets in 3h 05m", formatLimitReset(now + (3 * 3600 + 5 * 60) * 1000L, now))
        assertEquals("resets in 7h", formatLimitReset(now + 7 * 3600 * 1000L, now))
        assertEquals("resets in 2d", formatLimitReset(now + (2 * 86_400 + 3 * 3600) * 1000L, now))
        // A deadline already due reads as now — never a countdown into the past.
        assertEquals("resets now", formatLimitReset(now - 600_000L, now))
        assertEquals("resumes in 45m", formatScheduledResume(now + 45 * 60_000L, now))
    }

    @Test
    fun the_label_names_the_window_and_its_reset() {
        val now = 1_000_000L
        assertEquals(
            "5-hour limit · resets in 45m",
            limitedBannerLabel(LimitedChrome(window = "5-hour", resetAtMs = now + 45 * 60_000L), now),
        )
        // The armed continuation's fire time replaces the reset.
        assertEquals(
            "5-hour limit · resumes in 45m",
            limitedBannerLabel(
                LimitedChrome(window = "5-hour", resetAtMs = now + 45 * 60_000L, fireAtMs = now + 45 * 60_000L),
                now,
            ),
        )
        // An unknown reset says so.
        assertEquals("5-hour limit · reset time unknown", limitedBannerLabel(LimitedChrome(window = "5-hour"), now))
    }

    @Test
    fun the_actions_table_matches_the_desktop_strip() {
        val now = 1_000_000L
        // Armable (a reset ahead, nothing armed): Resume at reset + Resume.
        assertEquals(
            listOf(LimitedAction.RESUME_AT_RESET, LimitedAction.RESUME),
            limitedActions(LimitedChrome(window = "5-hour", resetAtMs = now + 60_000L), now),
        )
        // Armed: Cancel + Resume (Resume at reset hides).
        assertEquals(
            listOf(LimitedAction.CANCEL_RESUME, LimitedAction.RESUME),
            limitedActions(LimitedChrome(window = "5-hour", resetAtMs = now + 60_000L, fireAtMs = now + 60_000L), now),
        )
        // A due or unknown reset leaves the honest offer as plain Resume.
        assertEquals(
            listOf(LimitedAction.RESUME),
            limitedActions(LimitedChrome(window = "5-hour", resetAtMs = now - 1_000L), now),
        )
        assertEquals(listOf(LimitedAction.RESUME), limitedActions(LimitedChrome(window = "5-hour"), now))
        // The switch pill only when the list offers rows and the harness switches.
        assertEquals(
            listOf(LimitedAction.RESUME, LimitedAction.SWITCH_ACCOUNT),
            limitedActions(LimitedChrome(window = "5-hour", switchAvailable = true), now),
        )
        // The switch pill stays while a switch runs (the card no-ops its
        // tap); a failed load offers Retry.
        assertEquals(
            listOf(LimitedAction.RESUME, LimitedAction.SWITCH_ACCOUNT),
            limitedActions(LimitedChrome(window = "5-hour", switchAvailable = true, switching = true), now),
        )
        assertEquals(
            listOf(LimitedAction.RESUME, LimitedAction.RETRY_ACCOUNTS),
            limitedActions(LimitedChrome(window = "5-hour", retry = true), now),
        )
    }

    @Test
    fun the_outcome_notices_match_the_desktop_copy() {
        assertEquals(
            "Resumed on b@example.com — continuing where the chat left off.",
            outcomeNotice(ResumeSwitchOutcome.Switched("slot-2", "b@example.com")),
        )
        assertEquals(
            "Resumed on another account — continuing where the chat left off.",
            outcomeNotice(ResumeSwitchOutcome.Switched("slot-2", null)),
        )
        assertEquals("No other account is available right now.", outcomeNotice(ResumeSwitchOutcome.NoCandidates))
        assertEquals("probe refused", outcomeNotice(ResumeSwitchOutcome.Failed("probe refused")))
    }

    @Test
    fun the_picker_rows_rank_then_list_the_signed_out() {
        val rows = failoverRows(
            candidates(
                list = listOf(account("slot-2"), account("slot-3")),
                signedOut = listOf(account("slot-4")),
            ),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0].recommended)
        assertFalse(rows[1].recommended)
        assertTrue(rows[2].signedOut)
        assertFalse(failoverListOffers(candidates()))
        assertTrue(failoverListOffers(candidates(list = listOf(account("slot-2")))))
        assertTrue(failoverListOffers(candidates(signedOut = listOf(account("slot-4")))))
    }

    @Test
    fun the_row_copy_names_the_account_its_plan_and_its_resets() {
        val now = 1_000_000L
        val row = FailoverRow(
            account = account(
                "slot-2",
                email = "b@example.com",
                plan = "Max 20x",
                windows = listOf(
                    window("Session", 0.25f, resetsAt = now + 45 * 60_000L),
                    window("Week", 0.8f, resetsAt = now + 2 * 3600 * 1000L),
                ),
            ),
            recommended = true,
        )
        assertEquals("b@example.com", accountRowTitle(row.account))
        assertEquals("Recommended · Max 20x · Week 80% · session resets in 45m · week resets in 2h", accountRowSubtitle(row, now))
        // A signed-out row never offers a switch.
        assertEquals(
            "Signed out — sign in again on the desktop",
            accountRowSubtitle(FailoverRow(account("slot-4"), signedOut = true), now),
        )
        // Unknown account copy, no windows.
        assertEquals("Unknown account", accountRowTitle(account("slot-9")))
        assertNull(candidateResetCaption(account("slot-9"), now))
    }

    @Test
    fun the_switchable_harnesses_are_claude_and_codex() {
        assertTrue(harnessOffersFailover("claude-code"))
        assertTrue(harnessOffersFailover("codex"))
        assertFalse(harnessOffersFailover("opencode"))
        assertEquals("Resume on another Claude account", failoverCardTitle("claude-code"))
        assertEquals("Resume on another Codex account", failoverCardTitle("codex"))
    }

    // ------------------------------------------------------------ the filler

    @Test
    fun fill_derives_the_banner_from_the_limit_record() = runTest {
        val core = FakeLimitedCore()
        val banner = banner(core, PinnedClock(), this)
        val chrome = banner.fill(SessionChrome(), input())
        assertEquals("5-hour", chrome.limited?.window)
        assertEquals(limit.resetsAtMs, chrome.limited?.resetAtMs)
        assertNull(chrome.limited?.fireAtMs)
        advanceUntilIdle()
        // The banner asked for its candidates once.
        assertEquals(listOf("candidates"), core.calls)
        // A record-less composer clears the banner (and its notice).
        val cleared = banner.fill(chrome.copy(limited = null), input(limit = null))
        assertNull(cleared.limited)
    }

    @Test
    fun a_limited_banner_loads_once_within_the_ttl() = runTest {
        val core = FakeLimitedCore()
        val clock = PinnedClock()
        val banner = banner(core, clock, this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        // Refresh after refresh: the TTL holds (no second load).
        repeat(3) { banner.fill(SessionChrome(), input()) }
        advanceUntilIdle()
        assertEquals(1, core.calls.size)
        // 31 s later the meters may have moved: one more load.
        clock.now += FAILOVER_LIST_TTL_MS + 1_000L
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        assertEquals(2, core.calls.size)
    }

    @Test
    fun a_failed_load_is_remembered_until_retry_or_the_picker_reopens() = runTest {
        val core = FakeLimitedCore().apply {
            candidatesThrows = CoreException.HostUnavailable("Engine is offline; reconnecting")
        }
        val banner = banner(core, PinnedClock(), this)
        var shown = banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        // The load failed: the card offers Retry and does not re-fetch.
        shown = banner.fill(SessionChrome(), input())
        assertTrue(shown.limited?.retry == true)
        repeat(3) { shown = banner.fill(shown, input()) }
        advanceUntilIdle()
        assertEquals(1, core.calls.size)
        // The picker's re-open asks again (and a healthy answer clears the flag).
        core.candidatesThrows = null
        core.candidatesAnswer = candidates(list = listOf(account("slot-2")))
        banner.refreshForPicker()
        advanceUntilIdle()
        shown = banner.fill(SessionChrome(), input())
        assertFalse(shown.limited?.retry == true)
        assertTrue(shown.limited?.switchAvailable == true)
    }

    @Test
    fun an_empty_list_hides_the_switch_pill() = runTest {
        val core = FakeLimitedCore()
        val banner = banner(core, PinnedClock(), this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        val chrome = banner.fill(SessionChrome(), input())
        assertTrue(chrome.limited?.switchAvailable == false)
        assertNull(banner.pickerMenu())
    }

    @Test
    fun a_switch_lands_the_notice_and_reloads_the_ranks() = runTest {
        val core = FakeLimitedCore().apply {
            candidatesAnswer = candidates(list = listOf(account("slot-2", email = "b@example.com")))
            switchAnswer = ResumeSwitchOutcome.Switched("slot-2", "b@example.com")
        }
        val banner = banner(core, PinnedClock(), this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        banner.switch("slot-2")
        advanceUntilIdle()
        // The switch ran with the picked id; the ranks re-load on the
        // next refresh (a switch clears the cached list).
        assertEquals(listOf("candidates", "switch:slot-2"), core.calls)
        val chrome = banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        assertEquals(listOf("candidates", "switch:slot-2", "candidates"), core.calls)
        assertEquals(
            "Resumed on b@example.com — continuing where the chat left off.",
            chrome.limited?.notice,
        )
        // The re-loaded ranks serve the next picker open (the switch
        // cleared the list; the reload re-populated it).
        assertTrue(banner.pickerMenu() != null)
    }

    @Test
    fun a_failed_switch_surfaces_the_reason_and_never_leaves_the_harness_dirty() = runTest {
        val core = FakeLimitedCore().apply {
            candidatesAnswer = candidates(list = listOf(account("slot-2")))
            switchThrows = CoreException.HostUnavailable("Engine is offline; reconnecting")
        }
        val banner = banner(core, PinnedClock(), this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        banner.switch("slot-2")
        advanceUntilIdle()
        val chrome = banner.fill(SessionChrome(), input())
        assertEquals("Could not switch account: Engine is offline; reconnecting", chrome.limited?.notice)
        assertTrue(chrome.limited?.switching == false)
    }

    @Test
    fun a_switch_in_flight_is_guarded() = runTest {
        val core = FakeLimitedCore().apply {
            candidatesAnswer = candidates(list = listOf(account("slot-2")))
        }
        val banner = banner(core, PinnedClock(), this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        banner.switch("slot-2")
        banner.switch("slot-3")
        advanceUntilIdle()
        assertEquals(listOf("candidates", "switch:slot-2"), core.calls)
    }

    @Test
    fun resume_sends_the_continue_prompt_through_the_seam() = runTest {
        val core = FakeLimitedCore()
        val sent = mutableListOf<String>()
        val banner = banner(core, PinnedClock(), this)
        banner.send = { text -> sent += text }
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        banner.resume()
        advanceUntilIdle()
        assertEquals(listOf("Continue where you left off."), sent)
    }

    @Test
    fun arm_and_cancel_reach_the_core_and_the_retry_pill_reasks() = runTest {
        val core = FakeLimitedCore()
        val banner = banner(core, PinnedClock(), this)
        banner.fill(SessionChrome(), input())
        advanceUntilIdle()
        banner.act(LimitedAction.RESUME_AT_RESET)
        advanceUntilIdle()
        banner.act(LimitedAction.CANCEL_RESUME)
        advanceUntilIdle()
        // The Retry pill re-asks for the candidates (the desktop's
        // retry_failover) — a fourth load.
        banner.act(LimitedAction.RETRY_ACCOUNTS)
        advanceUntilIdle()
        assertEquals(listOf("candidates", "arm", "cancel", "candidates"), core.calls)
    }
}

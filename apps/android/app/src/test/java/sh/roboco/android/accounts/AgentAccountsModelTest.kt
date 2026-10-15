package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.CoreException

/**
 * The Accounts & Usage page's behaviour (android-next#05) on the plain
 * JVM: [AgentAccountsModel] over a recording [AccountsCore] fake — the
 * paint-then-refresh open sequence, the offline and unsupported answers
 * (the latter terminal: no spam, a manual retry only), the optimistic
 * switch with its toast and revert, and the confirmed remove. `runTest`
 * with one dispatcher for [AgentAccountsModel]'s work scope and io, so
 * the sequences run on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentAccountsModelTest {

    // ------------------------------------------------------------ the fakes

    /** Records every call in order; knobs for the answers and failures. */
    private class FakeAccountsCore : AccountsCore {
        val calls = mutableListOf<String>()
        var cachedReads = 0
        var cached: AgentAccountsSnapshot? = null
        val listAnswers = ArrayDeque<AgentAccountsSnapshot>()
        var listThrows: Throwable? = null
        var activateAnswer: AgentAccountsSnapshot? = null
        var forgetAnswer: AgentAccountsSnapshot? = null
        var activateThrows: Throwable? = null
        var forgetThrows: Throwable? = null

        override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot {
            calls += "list:$forceUsage"
            listThrows?.let { throw it }
            listAnswers.removeFirstOrNull()?.let { return it }
            throw CoreException.HostUnavailable("Engine is offline; reconnecting")
        }

        override suspend fun activate(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot {
            calls += "activate:$harness:$accountId"
            activateThrows?.let { throw it }
            return activateAnswer!!
        }

        override suspend fun forget(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot {
            calls += "forget:$harness:$accountId"
            forgetThrows?.let { throw it }
            return forgetAnswer!!
        }

        override fun cached(deviceId: String): AgentAccountsSnapshot? {
            cachedReads++
            return cached
        }
    }

    private class FakeToaster : Toaster {
        val toasts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            toasts += text
        }
    }

    private class FakeAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }

        override fun updateCurrent(alert: RobocoAlert) {}

        override fun dismissAll() {}
    }

    private class FakeSheets : ActionSheetPresenter {
        val shown = mutableListOf<ActionSheetModel>()
        override fun show(model: ActionSheetModel) {
            shown += model
        }
    }

    // ----------------------------------------------------------- fixtures

    private fun account(
        id: String,
        harness: String = "claude-code",
        email: String = "$id@example.com",
        active: Boolean = false,
        switchable: Boolean = true,
    ): AgentAccount = AgentAccount(
        id = id,
        harness = harness,
        email = email,
        planLabel = "Max 20\u00d7",
        active = active,
        usageWindows = listOf(
            AgentUsageWindow(label = "Session", usedFraction = 0.42f, resetsAtMs = null),
        ),
        usageFetchedAtMs = null,
        usageError = null,
        displayName = null,
        organization = null,
        apiKey = false,
        switchable = switchable,
        provider = null,
    )

    private fun snapshot(vararg accounts: AgentAccount): AgentAccountsSnapshot =
        AgentAccountsSnapshot(accounts = accounts.toList(), warnings = emptyList())

    private fun model(
        core: FakeAccountsCore,
        toaster: FakeToaster = FakeToaster(),
        alerts: FakeAlerts = FakeAlerts(),
        sheets: FakeSheets = FakeSheets(),
        scope: TestScope,
    ) = AgentAccountsModel(
        core = core,
        workScope = scope,
        toaster = toaster,
        alerts = alerts,
        sheets = sheets,
        deviceName = { "Studio" },
        harnessLabel = { harness -> mapOf("claude-code" to "Claude Code")[harness] ?: harness },
        io = Dispatchers.Unconfined,
        nowMs = { 1_760_000_000_000L },
    )

    // --------------------------------------------------------- the loads

    @Test
    fun openConsultsTheCacheThenThePlainThenTheForcedList() = runTest {
        val core = FakeAccountsCore()
        val cached = snapshot(account("alice", active = true))
        core.cached = cached
        val plain = snapshot(account("alice", active = true))
        val forced = snapshot(account("bob", active = true))
        core.listAnswers.addAll(listOf(plain, forced))
        val model = model(core, scope = this)

        model.open("device-1")
        advanceUntilIdle()
        // The open sequence: the core's cache first (the paint-at-once
        // source), then the host's last probe, then the forced probe —
        // whose reply is what stays on screen.
        assertEquals(1, core.cachedReads)
        assertEquals(listOf("list:false", "list:true"), core.calls)
        assertEquals(forced, model.snapshot)
        assertEquals(AccountsLoad.Ready, model.load)
    }

    @Test
    fun anOfflineEngineWithNothingOnScreenFailsWithTheDeviceMessage() = runTest {
        val core = FakeAccountsCore()
        core.listAnswers.clear()
        val model = model(core, scope = this)
        model.open("device-1")
        advanceUntilIdle()
        assertEquals(AccountsLoad.Failed::class.java, model.load::class.java)
        assertEquals(
            "Studio is offline. Accounts show up when it's back.",
            (model.load as AccountsLoad.Failed).message,
        )
        assertNull(model.snapshot)
    }
    @Test
    fun anUnsupportedEngineIsTerminalAndTheManualRetryAsksNothing() = runTest {
        val core = FakeAccountsCore()
        core.listThrows =
            CoreException.Unsupported("The engine does not support this method: ListAgentAccounts")
        val model = model(core, scope = this)
        model.open("device-1")
        advanceUntilIdle()
        val load = model.load
        assertTrue("unsupported is terminal: $load", load is AccountsLoad.Unsupported)
        assertEquals(
            "Studio needs a newer Roboco to show accounts here.",
            (load as AccountsLoad.Unsupported).message,
        )
        val callsBefore = core.calls.size
        model.pullToRefresh()
        model.retry()
        advanceUntilIdle()
        assertEquals("no retry asks the engine again", callsBefore, core.calls.size)
    }

    @Test
    fun pullToRefreshRunsAForcedList() = runTest {
        val core = FakeAccountsCore()
        val forced = snapshot(account("alice", active = true))
        core.listAnswers.add(forced)
        val model = model(core, scope = this)
        model.open("device-1")
        advanceUntilIdle()
        core.calls.clear()
        core.listAnswers.add(forced)
        model.pullToRefresh()
        assertEquals(listOf("list:true"), core.calls)
        assertEquals(forced, model.snapshot)
    }

    // -------------------------------------------------------- the switch

    @Test
    fun aSwitchIsOptimisticConfirmsWithAToastAndRevertsOnRefusal() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        val bob = account("bob")
        core.listAnswers.add(snapshot(alice, bob))
        core.listAnswers.add(snapshot(alice, bob))
        val toaster = FakeToaster()
        val alerts = FakeAlerts()
        val model = model(core, toaster = toaster, alerts = alerts, scope = this)
        model.open("device-1")
        advanceUntilIdle()

        // Optimistic: the rows flip at once, only the chosen group.
        model.switchTo(bob)
        assertEquals(listOf(false, true), model.snapshot!!.accounts.map { it.active })
        assertEquals("bob", model.switching)

        // The engine's reply replaces them; the toast confirms.
        val answered = snapshot(bob.copy(active = true), alice.copy(active = false))
        core.activateAnswer = answered
        advanceUntilIdle()
        assertEquals(answered, model.snapshot)
        assertNull(model.switching)
        assertEquals(listOf("Claude Code now uses bob@example.com"), toaster.toasts)
        assertTrue(alerts.shown.isEmpty())
    }

    @Test
    fun aRefusedSwitchRestoresTheRowsAndAlerts() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        val bob = account("bob")
        val original = snapshot(alice, bob)
        core.listAnswers.add(original)
        core.listAnswers.add(original)
        val toaster = FakeToaster()
        val alerts = FakeAlerts()
        val model = model(core, toaster = toaster, alerts = alerts, scope = this)
        model.open("device-1")
        advanceUntilIdle()
        core.activateThrows = CoreException.HostException("no")
        model.switchTo(bob)
        assertEquals(listOf(false, true), model.snapshot!!.accounts.map { it.active })
        advanceUntilIdle()
        assertEquals(listOf(true, false), model.snapshot!!.accounts.map { it.active })
        assertNull(model.switching)
        assertEquals(1, alerts.shown.size)
        assertEquals("Couldn't switch accounts", alerts.shown[0].title)
        assertEquals(
            "Couldn't load accounts from Studio.",
            alerts.shown[0].message,
        )
        assertTrue(toaster.toasts.isEmpty())
    }

    @Test
    fun rowTapsGuardTheActiveAndTheNotSwitchableLogins() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        val locked = account("locked", switchable = false)
        core.listAnswers.add(snapshot(alice, locked))
        core.listAnswers.add(snapshot(alice, locked))
        val alerts = FakeAlerts()
        val model = model(core, alerts = alerts, scope = this)
        model.open("device-1")
        advanceUntilIdle()

        model.onRowTap(alice)
        assertTrue("the active login is a no-op", core.calls.none { it.startsWith("activate") })

        model.onRowTap(locked)
        assertTrue(core.calls.none { it.startsWith("activate") })
        assertEquals(1, alerts.shown.size)
        assertEquals("Can't switch to this login", alerts.shown[0].title)
        assertEquals(
            "Roboco couldn't read its credentials on Studio. Sign in to it again there.",
            alerts.shown[0].message,
        )
        assertEquals(AlertButtonRole.Cancel, alerts.shown[0].buttons[0].role)
    }

    // -------------------------------------------------------- the remove

    @Test
    fun removeConfirmsThroughTheSheetThenForgets() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        val bob = account("bob")
        core.listAnswers.add(snapshot(alice, bob))
        core.listAnswers.add(snapshot(alice, bob))
        val sheets = FakeSheets()
        val model = model(core, sheets = sheets, scope = this)
        model.open("device-1")
        advanceUntilIdle()

        model.onRemove(bob)
        assertEquals(1, sheets.shown.size)
        val sheet = sheets.shown[0]
        assertEquals("Remove bob@example.com?", sheet.title)
        assertEquals("Its saved login is removed from Studio.", sheet.message)
        assertEquals(ActionSheetStyle.Destructive, sheet.actions[0].style)

        val after = snapshot(alice)
        core.forgetAnswer = after
        sheet.actions[0].onSelect()
        advanceUntilIdle()
        assertEquals(listOf("forget:claude-code:bob"), core.calls.filter { it.startsWith("forget") })
        assertEquals(after, model.snapshot)
    }

    @Test
    fun removingTheActiveLoginSaysSo() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        core.listAnswers.add(snapshot(alice))
        core.listAnswers.add(snapshot(alice))
        val sheets = FakeSheets()
        val model = model(core, sheets = sheets, scope = this)
        model.open("device-1")
        advanceUntilIdle()

        model.onRemove(alice)
        assertEquals(
            "Claude Code on Studio will be signed out until you pick another login.",
            sheets.shown[0].message,
        )
    }

    @Test
    fun aFailedForgetAlerts() = runTest {
        val core = FakeAccountsCore()
        val alice = account("alice", active = true)
        val bob = account("bob")
        core.listAnswers.add(snapshot(alice, bob))
        core.listAnswers.add(snapshot(alice, bob))
        val sheets = FakeSheets()
        val alerts = FakeAlerts()
        val model = model(core, sheets = sheets, alerts = alerts, scope = this)
        model.open("device-1")
        advanceUntilIdle()
        core.forgetThrows = CoreException.HostException("no")
        model.onRemove(bob)
        sheets.shown[0].actions[0].onSelect()
        advanceUntilIdle()
        assertEquals(1, alerts.shown.size)
        assertEquals("Couldn't remove the account", alerts.shown[0].title)
    }
}

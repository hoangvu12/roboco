package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentLoginRules
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.DeviceLoginInfo
import uniffi.roboco_core.DeviceLoginPollInfo
import uniffi.roboco_core.LoginOptionInfo

/**
 * Wave-10 34's "Add account" (upstream #966's iPhone `DeviceSignIn`) on
 * the plain JVM: the pure add-row and section rules, and
 * [AgentAccountsModel]'s sign-in runs over a recording [AccountSignIn]
 * fake — the device-code flow (page, code note, clipboard, done → reload),
 * Claude's paste fallback, the interim loopback answer, a cancel mid-flow
 * and a failed poll. Virtual time drives the 1.5 s polls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSignInTest {

    // ------------------------------------------------------------ the fakes

    private class FakeCore : AccountsCore {
        val calls = mutableListOf<String>()
        var answer: AgentAccountsSnapshot = AgentAccountsSnapshot(accounts = emptyList(), warnings = emptyList())
        override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot {
            calls += "list:$forceUsage"
            return answer
        }
        override suspend fun activate(deviceId: String, harness: String, accountId: String) = answer
        override suspend fun forget(deviceId: String, harness: String, accountId: String) = answer
        override fun cached(deviceId: String): AgentAccountsSnapshot? = null
    }

    private class FakeSignIn : AccountSignIn {
        val calls = mutableListOf<String>()
        var offered = listOf("claude-code", "grok")
        var start: DeviceLoginInfo = login()
        var startThrows: Throwable? = null
        val polls = ArrayDeque<DeviceLoginPollInfo>()
        var completeAnswer: AgentAccountsSnapshot? = null

        override suspend fun offered(deviceId: String): List<String> = offered

        override suspend fun start(deviceId: String, harness: String, provider: String?): DeviceLoginInfo {
            calls += "start:$harness:${provider ?: "-"}"
            startThrows?.let { throw it }
            return start
        }

        override suspend fun poll(deviceId: String, loginId: String): DeviceLoginPollInfo {
            calls += "poll:$loginId"
            return polls.removeFirstOrNull() ?: pending()
        }

        override suspend fun complete(deviceId: String, loginId: String, code: String): AgentAccountsSnapshot {
            calls += "complete:$loginId:$code"
            return completeAnswer!!
        }

        override fun cancel(deviceId: String, loginId: String) {
            calls += "cancel:$loginId"
        }

        override fun rules(harness: String): AgentLoginRules = rulesOf(harness)
    }

    private class FakeAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        override fun show(alert: RobocoAlert) {
            shown += alert
        }
        override fun updateCurrent(alert: RobocoAlert) {}
        override fun dismissAll() {}
    }

    private object NoToaster : Toaster {
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {}
    }

    private object NoSheets : ActionSheetPresenter {
        override fun show(model: ActionSheetModel) {}
    }

    // ----------------------------------------------------------- fixtures

    private companion object {
        fun option(provider: String?, label: String, first: String, more: String) =
            LoginOptionInfo(provider = provider, label = label, firstTitle = first, moreTitle = more)

        fun rulesOf(harness: String): AgentLoginRules = when (harness) {
            "claude-code" -> AgentLoginRules(
                name = "Claude Code", signsIn = true, switches = true, keepsOneLogin = false,
                reportsUsage = true, note = null,
                options = listOf(option(null, "Claude Code", "Connect a Claude Code account", "Add account")),
            )
            "grok" -> AgentLoginRules(
                name = "Grok", signsIn = true, switches = true, keepsOneLogin = false,
                reportsUsage = true, note = null,
                options = listOf(option(null, "Grok", "Connect a Grok account", "Add account")),
            )
            "antigravity" -> AgentLoginRules(
                name = "Antigravity", signsIn = true, switches = false, keepsOneLogin = true,
                reportsUsage = false, note = null,
                options = listOf(option(null, "Antigravity", "Connect a Antigravity account", "Add account")),
            )
            else -> AgentLoginRules(
                name = "", signsIn = false, switches = false, keepsOneLogin = false,
                reportsUsage = false, note = null, options = emptyList(),
            )
        }

        fun login(
            url: String = "https://accounts.x.ai/device",
            pasteCode: Boolean = false,
            port: UShort? = null,
        ) = DeviceLoginInfo(loginId = "login-1", url = url, pasteCode = pasteCode, callbackPort = port)

        fun pending(message: String? = null, url: String? = null) =
            DeviceLoginPollInfo(status = "pending", message = message, url = url, callbackPort = null)

        fun account(id: String, harness: String, active: Boolean = true): AgentAccount = AgentAccount(
            id = id,
            harness = harness,
            email = "$id@example.com",
            planLabel = null,
            active = active,
            usageWindows = emptyList(),
            usageFetchedAtMs = null,
            usageError = null,
            displayName = null,
            organization = null,
            apiKey = false,
            switchable = true,
            provider = null,
        )
    }

    private class Rig(
        val model: AgentAccountsModel,
        val core: FakeCore,
        val signIn: FakeSignIn,
        val alerts: FakeAlerts,
        val opened: MutableList<String>,
        val copied: MutableList<String>,
    )

    private fun TestScope.rig(signIn: FakeSignIn = FakeSignIn(), core: FakeCore = FakeCore()): Rig {
        val alerts = FakeAlerts()
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val model = AgentAccountsModel(
            core = core,
            workScope = this,
            toaster = NoToaster,
            alerts = alerts,
            sheets = NoSheets,
            deviceName = { "Studio" },
            harnessLabel = { harness -> rulesOf(harness).name.ifEmpty { harness } },
            io = Dispatchers.Unconfined,
            nowMs = { 1_760_000_000_000L },
            signIn = signIn,
            openUrl = { opened += it },
            copyText = { copied += it },
        )
        model.open("device-1")
        advanceUntilIdle()
        core.calls.clear()
        return Rig(model, core, signIn, alerts, opened, copied)
    }

    private fun Rig.addRow(harness: String): AddAccountRow = model.addRows(harness).single()

    // ------------------------------------------------------- the pure rules

    @Test
    fun addRowsTitleTheFirstAccountThenAnotherAndRespectOneLogin() {
        val claude = rulesOf("claude-code")
        assertEquals(
            listOf("Connect a Claude Code account"),
            addAccountRows("claude-code", claude, emptyList()).map { it.title },
        )
        val one = addAccountRows("claude-code", claude, listOf(account("a", "claude-code")))
        assertEquals(listOf("Add account"), one.map { it.title })
        assertEquals("add:claude-code:", one.single().id)
        assertTrue(
            "one login, once connected, has nothing to add",
            addAccountRows("antigravity", rulesOf("antigravity"), listOf(account("g", "antigravity"))).isEmpty(),
        )
        assertTrue(addAccountRows("mock", rulesOf("mock"), emptyList()).isEmpty())
    }

    @Test
    fun sectionsAddOfferedAgentsThatSignInInDesktopOrder() {
        val snapshot = AgentAccountsSnapshot(accounts = listOf(account("g1", "grok")), warnings = emptyList())
        val sections = accountSections(snapshot, listOf("mock", "grok", "claude-code")) { rulesOf(it).signsIn }
        assertEquals(listOf("claude-code", "grok"), sections.map { it.harness })
        assertTrue(sections[0].accounts.isEmpty())
        assertEquals(1, sections[1].accounts.size)
    }

    @Test
    fun theDeviceCodeIsPickedOutOfThePollNote() {
        assertEquals("ABCD-1234", deviceCodeIn("Enter the code ABCD-1234 when asked."))
        assertNull(deviceCodeIn("Finish in the browser"))
        assertEquals(
            "Enter the code ABCD-1234 when asked. · Tap to cancel",
            signInDetail(SignInState("add:grok:", "grok", "Enter the code ABCD-1234 when asked.")),
        )
        assertEquals("Tap to cancel", signInDetail(SignInState("add:grok:", "grok")))
    }

    // ------------------------------------------------------- the sign-ins

    @Test
    fun aDeviceCodeSignInOpensThePageShowsTheCodeAndReloadsWhenDone() = runTest {
        val signIn = FakeSignIn()
        signIn.polls.addAll(
            listOf(
                pending(message = "Enter the code ABCD-1234 when asked."),
                DeviceLoginPollInfo(status = "done", message = null, url = null, callbackPort = null),
            ),
        )
        val r = rig(signIn)
        r.model.onAddRow(r.addRow("grok"))
        assertNotNull("the waiting row shows at once", r.model.signingIn)
        assertTrue("no second sign-in while one runs", !r.model.addEnabled)

        advanceTimeBy(1_600)
        assertEquals(listOf("https://accounts.x.ai/device"), r.opened)
        assertEquals("Enter the code ABCD-1234 when asked.", r.model.signingIn?.message)
        assertEquals("the code is ready to paste", listOf("ABCD-1234"), r.copied)

        advanceUntilIdle()
        assertEquals(listOf("start:grok:-", "poll:login-1", "poll:login-1"), signIn.calls)
        assertEquals("done re-reads the accounts", listOf("list:false"), r.core.calls)
        assertNull(r.model.signingIn)
        assertTrue("a finished sign-in alerts nothing", r.alerts.shown.isEmpty())
    }

    @Test
    fun claudesPasteFallbackTakesTheCodeBackToTheComputer() = runTest {
        val signIn = FakeSignIn()
        signIn.start = login(url = "https://claude.ai/oauth/authorize?code=true", pasteCode = true)
        val fresh = AgentAccountsSnapshot(accounts = listOf(account("c1", "claude-code")), warnings = emptyList())
        signIn.completeAnswer = fresh
        val r = rig(signIn)
        r.model.onAddRow(r.addRow("claude-code"))
        advanceUntilIdle()

        assertEquals(listOf("https://claude.ai/oauth/authorize?code=true"), r.opened)
        val paste = r.alerts.shown.single()
        assertEquals("Paste the code", paste.title)
        assertNotNull(paste.field)
        paste.buttons.last().onClick("  pasted-code \n")
        advanceUntilIdle()

        assertEquals(listOf("start:claude-code:-", "complete:login-1:pasted-code"), signIn.calls)
        assertEquals(fresh, r.model.snapshot)
        assertNull(r.model.signingIn)
    }

    @Test
    fun cancellingThePasteAlertCancelsOnTheComputer() = runTest {
        val signIn = FakeSignIn()
        signIn.start = login(pasteCode = true)
        val r = rig(signIn)
        r.model.onAddRow(r.addRow("claude-code"))
        advanceUntilIdle()
        val cancel = r.alerts.shown.single().buttons.first { it.role == AlertButtonRole.Cancel }
        cancel.onClick("")
        advanceUntilIdle()
        assertEquals(listOf("start:claude-code:-", "cancel:login-1"), signIn.calls)
        assertNull(r.model.signingIn)
    }

    @Test
    fun aLoopbackSignInSaysToFinishOnTheComputerAndCancelsIt() = runTest {
        val signIn = FakeSignIn()
        signIn.start = login(url = "https://auth.openai.com/oauth/authorize", port = 1455.toUShort())
        val r = rig(signIn)
        r.model.onAddRow(r.addRow("claude-code"))
        advanceUntilIdle()
        assertEquals(SignInCopy.LoopbackTitle, r.alerts.shown.single().title)
        assertTrue("no page the phone can't finish", r.opened.isEmpty())
        assertEquals(listOf("start:claude-code:-", "cancel:login-1"), signIn.calls)
        assertNull(r.model.signingIn)
    }

    @Test
    fun theWaitingRowsTapCancelsMidFlowHereAndOnTheComputer() = runTest {
        val r = rig()
        r.model.onAddRow(r.addRow("grok"))
        advanceTimeBy(4_000)
        r.model.cancelSignIn()
        advanceUntilIdle()
        assertNull(r.model.signingIn)
        assertEquals("cancel:login-1", r.signIn.calls.last())
        assertTrue("a cancel alerts nothing", r.alerts.shown.isEmpty())
        assertTrue(r.model.addEnabled)
    }

    @Test
    fun aFailedPollOrStartAlertsWhy() = runTest {
        val signIn = FakeSignIn()
        signIn.polls.add(
            DeviceLoginPollInfo(status = "error", message = "grok login exited", url = null, callbackPort = null),
        )
        val r = rig(signIn)
        r.model.onAddRow(r.addRow("grok"))
        advanceUntilIdle()
        val alert = r.alerts.shown.single()
        assertEquals("Couldn't sign in", alert.title)
        assertEquals("grok login exited", alert.message)
        assertTrue("the computer ended it itself", signIn.calls.none { it.startsWith("cancel") })

        signIn.startThrows = CoreException.HostUnavailable("Engine is offline; reconnecting")
        r.model.onAddRow(r.addRow("grok"))
        advanceUntilIdle()
        assertEquals("Studio is offline. Try again when it's back.", r.alerts.shown.last().message)
        assertNull(r.model.signingIn)
    }
}

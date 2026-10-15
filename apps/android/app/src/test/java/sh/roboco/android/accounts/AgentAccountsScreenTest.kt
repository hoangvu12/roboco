package sh.roboco.android.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.DeviceView
import uniffi.roboco_core.FrontPage
import uniffi.roboco_core.PullRequestGroups
import uniffi.roboco_core.WorkspaceSnapshot

/**
 * The Accounts & Usage page (android-next#05) through the real
 * [AgentAccountsScreen]: the per-harness grouping over a fake
 * [AccountsCore], the row tap's optimistic switch (and the active row's
 * no-op), the remove flow (context menu → confirm sheet → forget) and
 * the section copy. Robolectric, the pushed-route shape of
 * `EngineDetailScreenTest`; the FFI lane inlined on main so the fake
 * answers under `waitForIdle`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class AgentAccountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ the fakes

    private class FakeAccountsCore : AccountsCore {
        val calls = mutableListOf<String>()
        var answer: AgentAccountsSnapshot? = null

        override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot {
            calls += "list:$forceUsage"
            return answer!!
        }

        override suspend fun activate(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot {
            calls += "activate:$harness:$accountId"
            return answer!!
        }

        override suspend fun forget(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot {
            calls += "forget:$harness:$accountId"
            return answer!!
        }

        override fun cached(deviceId: String): AgentAccountsSnapshot? = null
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
        windows: List<AgentUsageWindow> = emptyList(),
    ): AgentAccount = AgentAccount(
        id = id,
        harness = harness,
        email = email,
        planLabel = "Max 20\u00d7",
        active = active,
        usageWindows = windows,
        usageFetchedAtMs = 1_760_000_000_000L,
        usageError = null,
        displayName = null,
        organization = null,
        apiKey = false,
        switchable = true,
        provider = null,
    )

    private fun window(label: String, fraction: Float, resetsAtMs: Long? = null) =
        AgentUsageWindow(label = label, usedFraction = fraction, resetsAtMs = resetsAtMs)

    private val snapshot = AgentAccountsSnapshot(
        accounts = listOf(
            account(
                "alice",
                active = true,
                windows = listOf(window("Session", 0.88f), window("Week", 0.2f)),
            ),
            account("bob"),
            account("dev", harness = "codex", active = true, windows = listOf(window("Session", 0.42f))),
        ),
        warnings = emptyList(),
    )

    private val device = DeviceView(
        id = "device-1",
        engineId = "e1",
        name = "Studio",
        platform = "windows",
        online = true,
        lastSeenMs = null,
        version = null,
        capabilities = emptyList(),
        isExecutionHost = true,
        isSelf = false,
        sessionCount = 3u,
    )

    private val workspace = MutableStateFlow(
        WorkspaceSnapshot(
            revision = 1uL,
            computedAtMs = 0L,
            pinsReady = true,
            synced = true,
            front = FrontPage(
                pinned = emptyList(),
                sections = emptyList(),
                recent = emptyList(),
                groups = emptyList(),
            ),
            projects = emptyList(),
            projectless = emptyList(),
            pullRequests = PullRequestGroups(
                open = emptyList(),
                merged = emptyList(),
                closed = emptyList(),
            ),
            archived = emptyList(),
            devices = listOf(device),
        ),
    )

    private val core = FakeAccountsCore()
    private val toaster = FakeToaster()
    private val alerts = FakeAlerts()
    private val sheets = FakeSheets()

    private object TestRoot : NavRoute {
        override val paneTitle: String get() = "Settings"
    }

    private lateinit var stack: NavStack

    // ------------------------------------------------------------- mounting

    private fun mount(route: AgentAccountsRoute = AgentAccountsRoute(deviceId = "device-1")) {
        core.answer = snapshot
        stack = NavStack(TestRoot)
        stack.push(route, animated = false)
        val pushed = stack.entries.last()
        // One scope for the whole test: a fresh one per recomposition
        // would re-key the page's remembered model.
        val workScope = CoroutineScope(Dispatchers.Main.immediate)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides pushed,
                    LocalAlerts provides alerts,
                    LocalToaster provides toaster,
                    LocalActionSheet provides sheets,
                ) {
                    ContextMenuHost {
                        NavStackHost(stack = stack, active = true) { entry ->
                            if (entry.route is AgentAccountsRoute) {
                                AgentAccountsScreen(
                                    route = entry.route as AgentAccountsRoute,
                                    core = core,
                                    workspace = workspace,
                                    workScope = workScope,
                                    io = Dispatchers.Main.immediate,
                                    nowMs = { 1_760_000_000_000L },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun textExists(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // --------------------------------------------------------- the grouping

    @Test
    fun groupsPerHarnessWithTitlesMetersAndTheAboutFooter() {
        mount()
        compose.onNodeWithTag("agent-accounts").assertExists()
        // The open sequence: the plain list, then the forced probe.
        assertEquals(listOf("list:false", "list:true"), core.calls)
        assertTrue("the Claude Code section header", textExists("Claude Code"))
        assertTrue("the Codex section header", textExists("Codex"))
        assertTrue("the login title", textExists("alice@example.com"))
        assertTrue("the meta line", textExists("Max 20\u00d7 \u00b7 In use"))
        assertTrue("the second login", textExists("bob@example.com"))
        // The meter: the binding window's percent.
        assertTrue("the session meter", textExists("88%"))
        assertTrue("the freshness line", textExists("Usage updated just now."))
        assertTrue(
            "where logins are added",
            textExists(
                "Switching changes the login Studio uses for new sessions. " +
                    "To add an account, sign in from Roboco on Studio.",
            ),
        )
    }

    @Test
    fun theSessionOpenerScopesThePageToItsHarness() {
        mount(AgentAccountsRoute(deviceId = "device-1", focusHarness = "codex"))
        compose.onNodeWithTag("accounts-header-codex").assertExists()
    }

    // ---------------------------------------------------------- the switch

    @Test
    fun aRowTapSwitchesTheLogin() {
        mount()
        compose.onNodeWithTag("account-bob").assertHasClickAction().performClick()
        compose.waitForIdle()
        assertEquals(listOf("activate:claude-code:bob"), core.calls.filter { it.startsWith("activate") })
        assertEquals(listOf("Claude Code now uses bob@example.com"), toaster.toasts)
    }

    @Test
    fun theActiveRowTapIsANoOp() {
        mount()
        compose.onNodeWithTag("account-alice").assertHasClickAction().performClick()
        compose.waitForIdle()
        assertTrue("the active login is a no-op", core.calls.none { it.startsWith("activate") })
    }

    // ---------------------------------------------------------- the remove

    @Test
    fun removeRunsThroughTheContextMenuAndTheConfirmSheet() {
        mount()
        // Long-press the saved login: the context menu opens.
        compose.onNodeWithTag("account-bob").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Remove Account\u2026").performClick()
        compose.waitForIdle()
        // The confirmation sheet: message + the destructive action.
        assertEquals(1, sheets.shown.size)
        assertEquals("Remove bob@example.com?", sheets.shown[0].title)
        assertEquals("Its saved login is removed from Studio.", sheets.shown[0].message)
        sheets.shown[0].actions[0].onSelect()
        compose.waitForIdle()
        assertEquals(listOf("forget:claude-code:bob"), core.calls.filter { it.startsWith("forget") })
    }
}

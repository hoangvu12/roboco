package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.SessionChrome
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.ChatIndicator
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.HostCapabilities
import uniffi.roboco_core.HostInfo
import uniffi.roboco_core.LiveStatus
import uniffi.roboco_core.RoomState
import uniffi.roboco_core.SessionRow

/**
 * The composer's usage chip (android-next#05) on the plain JVM:
 * [SessionAccountUsage] over a recording [AccountsCore] fake — the chip
 * appears (and **leads**) at ≥ 80 % with the amber/red tint and glyphs,
 * stays hidden below the threshold and for an engine too old for the
 * accounts RPCs, the after-turn re-probe respects the 2-minute throttle,
 * and the menu lists the windows, the other logins and the page row,
 * switching optimistically with its toast (or the failure toast).
 *
 * The `ChromeInput`s are the fixture records (they construct without
 * JNA); the loads run on `Dispatchers.Unconfined`, so every fill's asks
 * answer within it — `advanceUntilIdle` is only belt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionUsageChipTest {

    private val now = 1_760_000_000_000L // 2025-10-09 08:53:20 UTC

    // ------------------------------------------------------------ the fakes

    private class FakeAccountsCore : AccountsCore {
        val calls = mutableListOf<String>()
        var answer: AgentAccountsSnapshot? = null
        var throwOnList: Throwable? = null
        var throwOnActivate: Throwable? = null

        override suspend fun list(deviceId: String, forceUsage: Boolean): AgentAccountsSnapshot {
            calls += "list:$forceUsage"
            throwOnList?.let { throw it }
            return answer!!
        }

        override suspend fun activate(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot {
            calls += "activate:$accountId"
            throwOnActivate?.let { throw it }
            return answer!!
        }

        override suspend fun forget(
            deviceId: String,
            harness: String,
            accountId: String,
        ): AgentAccountsSnapshot = throw IllegalStateException("not used here")

        override fun cached(deviceId: String): AgentAccountsSnapshot? = null
    }

    private class FakeToaster : Toaster {
        val toasts = mutableListOf<String>()
        override fun show(text: String, action: String?, onAction: (() -> Unit)?) {
            toasts += text
        }
    }

    // ----------------------------------------------------------- fixtures

    private fun account(
        id: String,
        harness: String = "claude-code",
        active: Boolean = false,
        windows: List<AgentUsageWindow> = emptyList(),
        switchable: Boolean = true,
    ): AgentAccount = AgentAccount(
        id = id,
        harness = harness,
        email = "$id@example.com",
        planLabel = "Max 20\u00d7",
        active = active,
        usageWindows = windows,
        usageFetchedAtMs = null,
        usageError = null,
        displayName = null,
        organization = null,
        apiKey = false,
        switchable = switchable,
        provider = null,
    )

    private fun window(label: String, fraction: Float, resetsAtMs: Long? = null) =
        AgentUsageWindow(label = label, usedFraction = fraction, resetsAtMs = resetsAtMs)

    private fun input(
        deviceId: String = "device-1",
        harness: String? = "claude-code",
        running: Boolean = false,
    ) = sh.roboco.android.session.ChromeInput(
        chatId = "chat-a",
        composer = ComposerState(
            chatId = "chat-a",
            revision = 1uL,
            title = "Session",
            host = HostInfo(
                deviceId = deviceId,
                name = "Studio",
                online = true,
                capabilities = HostCapabilities(
                    messageQueue = true,
                    queueActions = true,
                    queueAttachments = true,
                    cleanAttachmentText = true,
                    queueEditLease = true,
                    queuedAttachments = true,
                    midTurnSteering = null,
                ),
            ),
            live = LiveStatus(
                indicator = ChatIndicator.IDLE,
                turnRunning = running,
                workingSinceMs = null,
                streaming = false,
                canInterrupt = false,
            ),
            queue = emptyList(),
            pendingSends = emptyList(),
            sendState = null,
            deliveryDegraded = false,
            room = RoomState(connected = true, retryAtMs = null, degraded = false),
            transferProgress = null,
            openInput = null,
            queueError = null,
            lastSubmittedMessageId = null,
            contextUsage = null,
        ),
        row = SessionRow(
            id = "chat-a",
            engineId = "engine:v1:alpha",
            revision = 1uL,
            title = "Session",
            hasTitle = true,
            preview = null,
            project = null,
            deviceId = deviceId,
            deviceName = null,
            deviceOnline = true,
            engineOnline = true,
            harness = harness,
            harnessLabel = harness?.let { "Claude Code" },
            model = null,
            modelLabel = null,
            reasoning = null,
            branch = null,
            cwd = null,
            indicator = ChatIndicator.IDLE,
            hostIndicator = ChatIndicator.IDLE,
            workingSinceMs = null,
            lastActivityMs = 0L,
            timeLabel = "now",
            createdAtMs = 0L,
            unseen = false,
            archived = false,
            pinned = false,
            sectionId = null,
            pullRequest = null,
            sendState = null,
            parentChatId = null,
        ),
        engine = null,
        config = null,
    )

    private fun filler(core: FakeAccountsCore) = SessionAccountUsage(
        core = core,
        io = Dispatchers.Unconfined,
        nowMs = { now },
        harnessLabel = { harness -> mapOf("claude-code" to "Claude Code")[harness] ?: harness },
    ).also { it.bind {} }

    // ------------------------------------------------------------ the chip

    @Test
    fun theChipLeadsAtTheWarningLevelAndHidesBelowIt() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(
                account("alice", active = true, windows = listOf(window("Session", 0.88f))),
            ),
            warnings = emptyList(),
        )
        val filler = filler(core)
        // The first fill asks the plain list (the load answers within it).
        var chrome = filler.fill(SessionChrome(), input(running = true))
        assertEquals(listOf("list:false"), core.calls)
        chrome = filler.fill(chrome, input(running = true))
        val chip = chrome.chips.single()
        assertEquals(SessionUsageChipIds.Usage, chip.id)
        assertEquals("Session 88%", chip.title)
        assertEquals(USAGE_SYMBOL, chip.symbol)
        assertEquals(ChipTint.Warning, chip.tint)

        // Below the threshold the chip disappears after the next probe
        // (a turn's end forces one).
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(account("alice", active = true, windows = listOf(window("Session", 0.42f)))),
            warnings = emptyList(),
        )
        chrome = filler.fill(chrome, input(running = false))
        advanceUntilIdle()
        chrome = filler.fill(chrome, input(running = false))
        assertNull("no chip under 80 %", chrome.chips.firstOrNull { it.id == SessionUsageChipIds.Usage })
    }

    @Test
    fun theChipTurnsRedWithTheWarningGlyphAtTheCriticalLevel() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(
                account("alice", active = true, windows = listOf(window("Week", 0.97f))),
            ),
            warnings = emptyList(),
        )
        val filler = filler(core)
        var chrome = filler.fill(SessionChrome(), input())
        chrome = filler.fill(chrome, input())
        val chip = chrome.chips.first { it.id == SessionUsageChipIds.Usage }
        assertEquals("Week 97%", chip.title)
        assertEquals(USAGE_CRITICAL_SYMBOL, chip.symbol)
        assertEquals(ChipTint.Critical, chip.tint)
        assertEquals(0, chrome.chips.indexOf(chip))
    }

    @Test
    fun theFirstRefreshAsksPlainAndATurnEndForcesWithinTheThrottle() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(account("alice", active = true, windows = listOf(window("Session", 0.9f)))),
            warnings = emptyList(),
        )
        val filler = filler(core)
        var chrome = filler.fill(SessionChrome(), input(running = true))
        assertEquals(listOf("list:false"), core.calls)
        // A turn still running: no second ask.
        chrome = filler.fill(chrome, input(running = true))
        assertEquals(listOf("list:false"), core.calls)
        // The turn ends: a forced probe (usage moved with it).
        chrome = filler.fill(chrome, input(running = false))
        assertEquals(listOf("list:false", "list:true"), core.calls)
        // Another end right after: still inside the 2-minute throttle.
        chrome = filler.fill(chrome, input(running = true))
        chrome = filler.fill(chrome, input(running = false))
        assertEquals(listOf("list:false", "list:true"), core.calls)
    }

    @Test
    fun anUnsupportedEngineHidesTheChipAndNeverAsksAgain() = runTest {
        val core = FakeAccountsCore()
        core.throwOnList = CoreException.Unsupported("The engine does not support this method")
        val filler = filler(core)
        var chrome = filler.fill(SessionChrome(), input())
        advanceUntilIdle()
        assertEquals(listOf("list:false"), core.calls)
        chrome = filler.fill(chrome, input())
        chrome = filler.fill(chrome, input(running = true))
        chrome = filler.fill(chrome, input(running = false))
        advanceUntilIdle()
        assertEquals("no second ask after Unsupported", listOf("list:false"), core.calls)
        assertNull("no chip for an old engine", chrome.chips.firstOrNull { it.id == SessionUsageChipIds.Usage })
    }

    // ------------------------------------------------------------ the menu

    @Test
    fun theMenuListsWindowsSwitchesAndThePageRow() = runTest {
        val core = FakeAccountsCore()
        val active = account(
            "alice",
            active = true,
            windows = listOf(
                window("Session", 0.88f, resetsAtMs = now + 2 * 3_600_000),
                window("Week", 0.2f),
            ),
        )
        val other = account("bob", windows = listOf(window("Session", 0.1f)))
        val locked = account("locked", switchable = false)
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(active, other, locked),
            warnings = emptyList(),
        )
        val filler = filler(core)
        filler.fill(SessionChrome(), input())
        advanceUntilIdle()
        filler.fill(SessionChrome(), input())

        assertNull("other ids have no usage menu", filler.menu("model"))
        val menu = filler.menu(SessionUsageChipIds.Usage)!!
        assertEquals("Claude Code \u00B7 alice@example.com", menu.title)
        val rows = menu.children.filterIsInstance<MenuItem>()
        assertEquals(4, rows.size)
        // The two windows, read-only, with the written-out reset moment.
        assertEquals("Session 88%", rows[0].title)
        assertEquals("Resets in 2h", rows[0].subtitle)
        assertEquals("Week 20%", rows[1].title)
        assertNull(rows[1].subtitle)
        assertEquals(true, rows[0].informational)
        assertEquals("usage-menu-window-Session", rows[0].testTag)
        // Only the switchable other login of the group.
        assertEquals("Switch to bob@example.com", rows[2].title)
        assertEquals("Session 10%", rows[2].subtitle)
        assertEquals("usage-menu-switch-bob", rows[2].testTag)
        // The page row.
        assertEquals("Accounts & Usage\u2026", rows[3].title)
        assertEquals("usage-menu-open", rows[3].testTag)
    }

    @Test
    fun theMenuSwitchIsOptimisticAndToasts() = runTest {
        val core = FakeAccountsCore()
        val active = account("alice", active = true, windows = listOf(window("Session", 0.9f)))
        val other = account("bob")
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(active, other),
            warnings = emptyList(),
        )
        val toaster = FakeToaster()
        val filler = filler(core)
        filler.toaster = toaster
        var opened = 0
        filler.onOpenAccounts = { opened++ }
        filler.fill(SessionChrome(), input())
        advanceUntilIdle()
        filler.fill(SessionChrome(), input())

        val menu = filler.menu(SessionUsageChipIds.Usage)!!
        // The open row runs the injected handler.
        (menu.rows().first { it.testTag == "usage-menu-open" }.action)()
        assertEquals(1, opened)
        // The switch row switches and toasts.
        (menu.rows().first { it.testTag == "usage-menu-switch-bob" }.action)()
        advanceUntilIdle()
        assertEquals(listOf("activate:bob"), core.calls.filter { it.startsWith("activate") })
        assertEquals(
            listOf("Claude Code now uses bob@example.com for new sessions"),
            toaster.toasts,
        )
    }

    @Test
    fun aFailedSwitchRestoresAndToastsTheFailure() = runTest {
        val core = FakeAccountsCore()
        val active = account("alice", active = true, windows = listOf(window("Session", 0.9f)))
        val other = account("bob")
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(active, other),
            warnings = emptyList(),
        )
        val toaster = FakeToaster()
        val filler = filler(core)
        filler.toaster = toaster
        filler.fill(SessionChrome(), input())
        advanceUntilIdle()
        filler.fill(SessionChrome(), input())

        // The engine refuses the switch: the snapshot is restored (the
        // chip stays honest) and the failure toast shows.
        core.throwOnActivate = CoreException.HostException("no")
        val menu = filler.menu(SessionUsageChipIds.Usage)!!
        (menu.rows().first { it.testTag == "usage-menu-switch-bob" }.action)()
        advanceUntilIdle()
        assertEquals(listOf(AccountsCopy.SwitchFailedToast), toaster.toasts)
        assertEquals(
            "the chip still reads the pre-switch login",
            "Session 90%",
            filler.usageChip()?.title,
        )
    }
}

private fun sh.roboco.android.design.glass.Menu.rows(): List<MenuItem> =
    children.filterIsInstance<MenuItem>()

package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.session.CONTEXT_WARNING_SYMBOL
import sh.roboco.android.session.SessionChipIds
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.contextChip
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
 * The composer's plan usage (android-next#05), folded into android-next
 * #01's context chip (user decision 2026-10-15: no separate usage chip),
 * on the plain JVM: [SessionAccountUsage] over a recording [AccountsCore]
 * fake — the worse-of tint (the context chip's own amber stays, the plan
 * adds amber/red with the warning glyph), the menu's account half
 * ([SessionAccountUsage.augmentContextCard]: the Account section, the
 * other logins to switch to, "Accounts & Usage…"), the after-turn
 * re-probe's 2-minute throttle and the unsupported-engine rule (the
 * plain context card, no error spam).
 *
 * The `ChromeInput`s and the context chips are fixture records (they
 * construct without JNA); the loads run on `Dispatchers.Unconfined`, so
 * every fill's asks answer within it.
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
            limit = null,
            scheduledResumeFireAtMs = null,
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

    /** A chrome carrying the context chip #01's filler would have built. */
    private fun contextChrome(tokens: ULong, window: ULong?): SessionChrome {
        val reading = sh.roboco.android.session.contextReading(tokens, window)!!
        return SessionChrome(chips = listOf(contextChip(reading)))
    }

    private fun filler(core: FakeAccountsCore) = SessionAccountUsage(
        core = core,
        io = Dispatchers.Unconfined,
        nowMs = { now },
        harnessLabel = { harness -> mapOf("claude-code" to "Claude Code")[harness] ?: harness },
    ).also { it.bind {} }

    // ------------------------------------------------------- the tint

    @Test
    fun aWarningPlanTintsTheCalmContextChipAmberWithTheWarningGlyph() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(
                account("alice", active = true, windows = listOf(window("Session", 0.88f))),
            ),
            warnings = emptyList(),
        )
        val filler = filler(core)
        val chrome = filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        // The fill asked the plain list (the load answers within it).
        assertEquals(listOf("list:false"), core.calls)
        // No chip was added — the context chip keeps its context reading,
        // re-tinted amber with the warning glyph.
        assertEquals(1, chrome.chips.size)
        val chip = chrome.chips.single()
        assertEquals(SessionChipIds.Context, chip.id)
        assertEquals("11% context", chip.title)
        assertEquals(ChipTint.Warning, chip.tint)
        assertEquals(CONTEXT_WARNING_SYMBOL, chip.symbol)
    }

    @Test
    fun aCriticalPlanTintsTheChipRed() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(
                account("alice", active = true, windows = listOf(window("Week", 0.97f))),
            ),
            warnings = emptyList(),
        )
        val filler = filler(core)
        val chrome = filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        assertEquals(ChipTint.Critical, chrome.chips.single().tint)
        assertEquals(CONTEXT_WARNING_SYMBOL, chrome.chips.single().symbol)
        assertEquals("11% context", chrome.chips.single().title)
    }

    @Test
    fun aCalmPlanLeavesTheChipExactlyAs01BuiltIt() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(
                account("alice", active = true, windows = listOf(window("Session", 0.42f))),
            ),
            warnings = emptyList(),
        )
        val filler = filler(core)
        val before = contextChrome(120_000uL, 1_048_576uL)
        val chrome = filler.fill(before, input())
        // The calm plan leaves #01's calm chip (and an already-amber
        // context reading keeps its own amber — the plan only worsens).
        assertEquals(before.chips.single().tint, chrome.chips.single().tint)
        assertEquals(before.chips.single().symbol, chrome.chips.single().symbol)
        val warned = contextChrome(943_713uL, 1_048_576uL) // 90 % context: #01's own amber
        val chrome2 = filler.fill(warned, input())
        assertEquals(ChipTint.Warning, chrome2.chips.single().tint)
        assertEquals(CONTEXT_WARNING_SYMBOL, chrome2.chips.single().symbol)
    }

    @Test
    fun noSnapshotOrAnOldEngineLeavesTheChipUntouched() = runTest {
        val core = FakeAccountsCore()
        core.throwOnList = CoreException.Unsupported("The engine does not support this method")
        val filler = filler(core)
        val before = contextChrome(120_000uL, 1_048_576uL)
        var chrome = filler.fill(before, input())
        advanceUntilIdle()
        chrome = filler.fill(chrome, input())
        chrome = filler.fill(chrome, input(running = true))
        chrome = filler.fill(chrome, input(running = false))
        advanceUntilIdle()
        assertEquals("no second ask after Unsupported", listOf("list:false"), core.calls)
        assertEquals(before.chips.single().tint, chrome.chips.single().tint)
        assertEquals(before.chips.single().symbol, chrome.chips.single().symbol)
    }

    // ------------------------------------------------ the loads' policy

    @Test
    fun theFirstRefreshAsksPlainAndATurnEndForcesWithinTheThrottle() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(account("alice", active = true, windows = listOf(window("Session", 0.9f)))),
            warnings = emptyList(),
        )
        val filler = filler(core)
        var chrome = filler.fill(contextChrome(120_000uL, 1_048_576uL), input(running = true))
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

    // ------------------------------------------------ the menu's account half

    @Test
    fun theCardIsAugmentedWithTheAccountSectionSwitchesAndThePageRow() = runTest {
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
        filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        advanceUntilIdle()

        val card = sh.roboco.android.session.contextUsageCard(
            sh.roboco.android.session.contextReading(120_000uL, 1_048_576uL)!!,
        )
        val augmented = filler.augmentContextCard(card)
        // #01's two context rows first, unchanged.
        assertEquals(card.children[0], augmented.children[0])
        assertEquals(card.children[1], augmented.children[1])
        // The Account section: the login in use, then every window.
        val section = augmented.children[2] as MenuSection
        assertEquals(ACCOUNT_SECTION_TITLE, section.title)
        assertTrue(section.inline)
        val sectionRows = section.children.filterIsInstance<MenuItem>()
        assertEquals("alice@example.com", sectionRows[0].title)
        assertEquals("In use", sectionRows[0].subtitle)
        assertTrue(sectionRows[0].informational)
        assertEquals("Session 88%", sectionRows[1].title)
        assertEquals("Resets in 2h", sectionRows[1].subtitle)
        assertEquals("Week 20%", sectionRows[2].title)
        assertNull(sectionRows[2].subtitle)
        // Only the switchable other login of the group, with its fullest
        // window; then the page row.
        val rows = augmented.children.filterIsInstance<MenuItem>()
        val switch = rows.first { it.title.startsWith("Switch to") }
        assertEquals("Switch to bob@example.com", switch.title)
        assertEquals("Session 10%", switch.subtitle)
        assertEquals("context-menu-switch-bob", switch.testTag)
        val open = rows.first { it.testTag == "context-menu-open" }
        assertEquals("Accounts & Usage\u2026", open.title)
    }

    @Test
    fun withoutAccountDataTheCardIsExactly01s() = runTest {
        val core = FakeAccountsCore()
        core.answer = AgentAccountsSnapshot(
            accounts = listOf(account("other-harness", harness = "codex", active = true)),
            warnings = emptyList(),
        )
        val filler = filler(core)
        filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        advanceUntilIdle()
        val card = sh.roboco.android.session.contextUsageCard(
            sh.roboco.android.session.contextReading(120_000uL, 1_048_576uL)!!,
        )
        // A harness with no accounts: the plain card.
        assertEquals(card, filler.augmentContextCard(card))
        // So does a filler that never loaded.
        assertEquals(card, filler(FakeAccountsCore()).augmentContextCard(card))
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
        filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        advanceUntilIdle()

        val card = filler.augmentContextCard(
            sh.roboco.android.session.contextUsageCard(
                sh.roboco.android.session.contextReading(120_000uL, 1_048_576uL)!!,
            ),
        )
        // The open row runs the injected handler.
        (card.rows().first { it.testTag == "context-menu-open" }.action)()
        assertEquals(1, opened)
        // The switch row switches and toasts.
        (card.rows().first { it.testTag == "context-menu-switch-bob" }.action)()
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
        filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        advanceUntilIdle()

        // The engine refuses the switch: the failure toast shows and the
        // tint still reads the pre-switch login.
        core.throwOnActivate = CoreException.HostException("no")
        val card = filler.augmentContextCard(
            sh.roboco.android.session.contextUsageCard(
                sh.roboco.android.session.contextReading(120_000uL, 1_048_576uL)!!,
            ),
        )
        (card.rows().first { it.testTag == "context-menu-switch-bob" }.action)()
        advanceUntilIdle()
        assertEquals(listOf(AccountsCopy.SwitchFailedToast), toaster.toasts)
        val afterFailure = filler.fill(contextChrome(120_000uL, 1_048_576uL), input())
        assertEquals(ChipTint.Warning, afterFailure.chips.single().tint)
    }
}

private fun sh.roboco.android.design.glass.Menu.rows(): List<MenuItem> =
    children.filterIsInstance<MenuItem>()

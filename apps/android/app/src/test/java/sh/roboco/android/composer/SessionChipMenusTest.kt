package sh.roboco.android.composer

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChrome
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ContextUsage
import uniffi.roboco_core.HarnessInfo
import uniffi.roboco_core.ModelInfo
import uniffi.roboco_core.ModelOption
import uniffi.roboco_core.SandboxLevel
import uniffi.roboco_core.SessionRow

/**
 * The chip menus' pure rules, provider and write paths
 * (android-ui-parity #89) — plain JUnit, no Robolectric: the FFI seam is
 * [FakeChipMenuCore] (the uniffi records construct without JNA) and the
 * `io` lane is a single-thread executor named `fake-io`, so every test
 * also pins the threading rule: **no core call off `fake-io`, no core
 * call from `menu()` at all**.
 */
class SessionChipMenusTest {

    private lateinit var executor: ExecutorService

    @Before
    fun startFakeIo() {
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fake-io") }
    }

    @After
    fun stopFakeIo() {
        executor.shutdownNow()
    }

    // ------------------------------------------------------------ the fake

    /** Records every call (name + calling thread) and answers from mutable fields. */
    private class FakeChipMenuCore(
        var row: SessionRow?,
        var config: ChatConfig?,
        val models: List<ModelInfo> = claudeFallbackModels(),
        val harnesses: List<HarnessInfo> = emptyList(),
    ) : ChipMenuCore {
        /** Written from the test thread (fill's fallback read) and the io lane at once. */
        val callLog = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun listHarnesses(deviceId: String): List<HarnessInfo> {
            callLog += "listHarnesses($deviceId)" to threadName()
            return harnesses
        }

        /** Makes the next `listModels` throw (the fallback path). */
        var listModelsFailure: Throwable? = null

        /** The gate a suspended `setSessionConfig` waits on (one-shot). */
        var setConfigGate: CompletableDeferred<Unit>? = null

        var setConfigFailure: Throwable? = null
        val setConfigs = mutableListOf<ChatConfig>()

        /** Ended `setSessionConfig` calls, failed or not (volatile: polled across threads). */
        @Volatile private var writeCount = 0

        /**
         * The calling thread's name without kotlinx.coroutines' debug-mode
         * suffix (`fake-io @coroutine#7`): the tests assert the lane, and the
         * CI run's debug agent appended the coroutine id (r746).
         */
        private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine#")

        override fun sessionRow(): SessionRow? {
            callLog += "sessionRow" to threadName()
            return row
        }

        override fun sessionConfig(): ChatConfig? {
            callLog += "sessionConfig" to threadName()
            return config
        }

        override suspend fun listModels(deviceId: String, harness: String): List<ModelInfo> {
            callLog += "listModels($deviceId, $harness)" to threadName()
            listModelsFailure?.let { throw it }
            return models
        }

        override fun fallbackModels(harness: String): List<ModelInfo> {
            callLog += "fallbackModels($harness)" to threadName()
            return claudeFallbackModels()
        }

        override fun reasoningLabel(level: String): String {
            callLog += "reasoningLabel($level)" to threadName()
            return kotlinReasoningLabel(level)
        }

        override suspend fun setSessionConfig(config: ChatConfig) {
            callLog += "setSessionConfig" to threadName()
            setConfigs += config
            setConfigGate?.await()
            writeCount++
            setConfigFailure?.let { throw it }
        }

        /** Polls until [count] writes ended, or fails after 5 s. */
        fun awaitWrites(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writeCount < count && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("$count writes should have ended", count, writeCount)
        }

        fun methodNames(): List<String> = callLog.map { it.first }
    }

    /**
     * Polls [done] for up to 5 s. awaitWrites returns when setSessionConfig
     * itself ended on the io thread; onSettingResult and the invalidate run
     * right after it, so assertions on them wait here first.
     */
    private fun awaitUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(what, done())
    }

    /** Records the platform side of the PR menu. */
    private class RecordingPlatform : ChipMenuPlatform {
        val openedUrls = mutableListOf<String>()
        val copiedUrls = mutableListOf<String>()

        override fun openUrl(url: String) {
            openedUrls += url
        }

        override fun copyLink(url: String) {
            copiedUrls += url
        }
    }

    private fun fakeIo() = executor.asCoroutineDispatcher()

    private fun menus(
        core: FakeChipMenuCore,
        onResult: (Throwable?) -> Unit = {},
    ): SessionChipMenus = SessionChipMenus(core, io = fakeIo()).also { it.onSettingResult = onResult }

    /** Fills the snapshot (the controller-refresh side of the contract). */
    private fun fill(
        menus: SessionChipMenus,
        core: FakeChipMenuCore,
        deviceId: String = "device-1",
        contextUsage: ContextUsage? = null,
        providerSwitch: Boolean = false,
        turnRunning: Boolean = false,
        chrome: SessionChrome = SessionChrome(),
    ): SessionChrome =
        menus.fill(
            chrome,
            ChromeInput(
                chatId = "chat-a",
                composer = chipMenuComposerState(
                    deviceId = deviceId,
                    contextUsage = contextUsage,
                    providerSwitch = providerSwitch,
                    turnRunning = turnRunning,
                ),
                row = core.row,
                engine = null,
                config = core.config,
            ),
        )

    /** The deferred of a chip's menu (single-child convenience). */
    private fun deferredOf(menus: SessionChipMenus, chipId: String): MenuElement.Deferred =
        menus.menu(chipId, RecordingPlatform())!!.children.single() as MenuElement.Deferred

    /** The menu's rows, loaded through the deferred. */
    private fun loadRows(deferred: MenuElement.Deferred): List<MenuItem> =
        runBlocking { deferred.load() }.filterIsInstance<MenuItem>()

    // ------------------------------------------- the model picker (38)

    @Test
    fun withAPickerTheModelChipOpensItInsteadOfAMenuAndAPickApplies() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(model = "claude-fable-5"))
        val menus = menus(core)
        val shown = mutableListOf<ModelPickerRequest>()
        menus.modelPicker = ModelPickerPresenter { shown += it }
        fill(menus, core)

        assertNull("no menu: the picker opens", menus.menu("model", RecordingPlatform()))
        assertTrue("tap time makes no core call", core.callLog.isEmpty())
        val request = shown.single()
        assertEquals(listOf(ModelPickerTab(harness = "claude-code", label = "Claude Code")), request.tabs)
        assertEquals("claude-code", request.harness)
        assertEquals("claude-fable-5", request.model)
        assertEquals(PICKER_HOST_FALLBACK, request.hostName)

        val rows = runBlocking { request.load("claude-code") }
        assertEquals(claudeFallbackModels().map { it.id }, rows.map { it.id })
        assertEquals("Most intelligent model for building agents", rows.first().detail)
        assertEquals("the load ran on the io lane", "fake-io", core.callLog.single { it.first.startsWith("listModels") }.second)

        request.onPick("claude-code", rows.first())
        core.awaitWrites(1)
        assertEquals("claude-fable-5-1", core.setConfigs.single().model)
        assertEquals("claude-code", core.setConfigs.single().harness)
    }

    private fun harness(id: String, label: String, offered: Boolean = true) = HarnessInfo(
        id = id,
        label = label,
        supportsSteering = null,
        steeringMode = null,
        reasoningLevels = emptyList(),
        installed = offered,
        enabled = null,
        offered = offered,
        midTurnSteering = null,
    )

    @Test
    fun aHostThatSwitchesOffersItsOtherProvidersWithTheirNotes() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(),
            config = chatConfig(model = "claude-fable-5", reasoning = "xhigh", modelOptions = mapOf("contextWindow" to "1m")),
            harnesses = listOf(
                harness("claude-code", "Claude Code"),
                harness("codex", "Codex"),
                harness("pi", "Pi", offered = false),
            ),
        )
        val menus = menus(core)
        val shown = mutableListOf<ModelPickerRequest>()
        menus.modelPicker = ModelPickerPresenter { shown += it }
        fill(menus, core, providerSwitch = true)
        awaitUntil("the providers were read ahead") { core.methodNames().contains("listHarnesses(device-1)") }
        Thread.sleep(50) // the read-ahead's answer lands on fake-io
        fill(menus, core, providerSwitch = true)

        menus.menu("model", RecordingPlatform())
        val request = shown.single()
        assertEquals(listOf("claude-code", "codex"), request.tabs.map { it.harness })
        assertEquals("Your next message continues this chat with Codex.", request.tabs[1].footer)
        assertNull("it can switch: no note", request.note)

        // A pick on Codex's page moves the chat; effort and options reset.
        request.onPick("codex", ModelPickerRow(id = "gpt-5", label = "GPT 5", detail = null))
        core.awaitWrites(1)
        assertEquals(
            chatConfig(harness = "codex", model = "gpt-5", reasoning = null, modelOptions = emptyMap()),
            core.setConfigs.single(),
        )
    }

    @Test
    fun midTurnOrOnAnOlderHostOnlyTheOwnProviderWithTheRightNote() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(model = "claude-fable-5"))
        val menus = menus(core)
        val shown = mutableListOf<ModelPickerRequest>()
        menus.modelPicker = ModelPickerPresenter { shown += it }

        fill(menus, core, providerSwitch = true, turnRunning = true)
        menus.menu("model", RecordingPlatform())
        assertEquals(1, shown.last().tabs.size)
        assertEquals("You can switch provider when this turn ends.", shown.last().note)
        // A stale cross-provider pick while it can't switch writes nothing.
        shown.last().onPick("codex", ModelPickerRow(id = "gpt-5", label = "GPT 5", detail = null))

        fill(menus, core, providerSwitch = false)
        menus.menu("model", RecordingPlatform())
        assertEquals(1, shown.last().tabs.size)
        assertEquals("This computer needs a newer Roboco to switch provider.", shown.last().note)
        assertTrue("no host read without the capability", core.methodNames().none { it.startsWith("listHarnesses") })
        Thread.sleep(50)
        assertTrue("the stale pick wrote nothing", core.setConfigs.isEmpty())
    }

    @Test
    fun aChatWithNoModelNamesAndTicksItsDefault() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(model = null, modelLabel = null),
            config = chatConfig(model = null),
        )
        val menus = menus(core)
        val shown = mutableListOf<ModelPickerRequest>()
        menus.modelPicker = ModelPickerPresenter { shown += it }
        val chrome = SessionChrome(
            chips = listOf(ComposerChip(id = "model", title = "Claude Code")),
        )
        val filled = fill(menus, core, chrome = chrome)
        assertEquals("the built-in list's first model names the chip", "Fable 5.1", filled.chips.single().title)
        menus.menu("model", RecordingPlatform())
        assertEquals("claude-fable-5-1", shown.single().model)
    }

    @Test
    fun theProviderRulesFollowUpstream() {
        assertNull(providerSwitchNote(canSwitch = true, running = false, hostSwitches = true))
        assertEquals("You can switch provider when this turn ends.", providerSwitchNote(false, running = true, hostSwitches = true))
        assertNull("a send on its way, no note", providerSwitchNote(false, running = false, hostSwitches = true))
        assertEquals("This computer needs a newer Roboco to switch provider.", providerSwitchNote(false, false, false))
        val base = chatConfig(model = "claude-fable-5", reasoning = "high", modelOptions = mapOf("contextWindow" to "1m"))
        assertEquals(chatConfig(model = "claude-fable-5-1", reasoning = "high", modelOptions = mapOf("contextWindow" to "1m")),
            applyProviderPick(base, "claude-code", "claude-fable-5-1"))
        assertEquals(chatConfig(harness = "codex", model = "gpt-5"), applyProviderPick(base, "codex", "gpt-5"))
    }

    @Test
    fun theEffortChipKeepsItsMenuWithAPicker() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig())
        val menus = menus(core)
        menus.modelPicker = ModelPickerPresenter { }
        fill(menus, core)
        assertNotNull(menus.menu("effort", RecordingPlatform()))
    }

    // ------------------------------------------------------ applyModelPick

    @Test
    fun applyModelPickFollowsThePickTableVectorByVector() {
        fun m(
            id: String,
            levels: List<String>,
            default: String?,
            options: List<ModelOption> = emptyList(),
        ): ModelInfo = chipMenuModel(id, id, null, levels, default, options)

        // 1 — the level is not offered: the model's default wins.
        assertEquals(
            chatConfig(model = "m1", reasoning = "high"),
            applyModelPick(chatConfig(reasoning = "xhigh"), m("m1", listOf("low", "medium", "high"), "high")),
        )
        // 2 — the level is offered: kept.
        assertEquals(
            chatConfig(model = "m1", reasoning = "medium"),
            applyModelPick(chatConfig(reasoning = "medium"), m("m1", listOf("low", "medium", "high"), "high")),
        )
        // 3 — no reasoning set: the model's default.
        assertEquals(
            chatConfig(model = "m1", reasoning = "high"),
            applyModelPick(chatConfig(reasoning = null), m("m1", listOf("low", "high"), "high")),
        )
        // 4 — no ladder at all: reasoning cleared (Haiku 4.5).
        assertEquals(
            chatConfig(model = "m1", reasoning = null),
            applyModelPick(chatConfig(reasoning = "high"), m("m1", emptyList(), null)),
        )
        // 5 — options survive only when the model still offers id + choice;
        //    an option key the model does not offer at all is dropped too.
        val opus5 = m(
            "m1",
            FULL_LADDER,
            "high",
            listOf(chipMenuContextWindow(), chipMenuToggle("fastMode", "Fast Mode")),
        )
        assertEquals(
            chatConfig(model = "m1", reasoning = "low", modelOptions = mapOf("contextWindow" to "1m", "fastMode" to "on")),
            applyModelPick(
                chatConfig(
                    reasoning = "low",
                    modelOptions = mapOf("contextWindow" to "1m", "fastMode" to "on", "thinking" to "on"),
                ),
                opus5,
            ),
        )
        // 6 — an unknown choice is dropped.
        val contextOnly = m("m1", FULL_LADDER, "high", listOf(chipMenuContextWindow()))
        assertEquals(
            chatConfig(model = "m1", reasoning = "low"),
            applyModelPick(chatConfig(reasoning = "low", modelOptions = mapOf("contextWindow" to "2m")), contextOnly),
        )
        // 7 — an unoffered reasoning falls back to high, never the middle index.
        val opus47 = m("m1", CLAUDE_XHIGH, "high", listOf(chipMenuToggle("fastMode", "Fast Mode")))
        assertEquals(
            chatConfig(model = "m1", reasoning = "high"),
            applyModelPick(chatConfig(reasoning = "ultracode"), opus47),
        )
    }

    @Test
    fun applyModelPickNeverChangesHarnessOrSandbox() {
        val next = applyModelPick(
            chatConfig(harness = "codex", model = "old", reasoning = null, sandbox = SandboxLevel.DANGER_FULL_ACCESS),
            claudeFallbackModels()[2], // Opus 5: FULL_LADDER, default high
        )
        assertEquals("codex", next.harness)
        assertEquals(SandboxLevel.DANGER_FULL_ACCESS, next.sandbox)
        assertEquals("claude-opus-5", next.model)
        assertEquals("high", next.reasoning)
    }

    @Test
    fun preferredReasoningIsHighThenMediumThenTheFirstLevel() {
        assertNull(preferredReasoning(emptyList()))
        assertEquals("medium", preferredReasoning(listOf("low", "medium")))
        assertEquals("high", preferredReasoning(listOf("medium", "high")))
        assertEquals("minimal", preferredReasoning(listOf("minimal", "low")))
    }

    // --------------------------------------------------- sessionSelection

    @Test
    fun sessionSelectionConfigWinsOverRowAndDefaultsTheHarness() {
        val row = chipMenuSessionRow(harness = null, model = "row-model", reasoning = "low")
        // The config wins over the row.
        assertEquals(
            SessionSelection("codex", "cfg-model", "high"),
            sessionSelection(chatConfig(harness = "codex", model = "cfg-model", reasoning = "high"), row),
        )
        // No config: the row decides; null harness → claude-code.
        assertEquals(SessionSelection("claude-code", "row-model", "low"), sessionSelection(null, row))
        // Neither: null.
        assertNull(sessionSelection(null, null))
    }

    // -------------------------------------------------------- prUrlOrNull

    @Test
    fun prUrlOrNullKeepsOnlyNonBlankUrlsWithAScheme() {
        assertEquals("https://github.com/o/r/pull/12", prUrlOrNull("https://github.com/o/r/pull/12"))
        assertNull(prUrlOrNull(""))
        assertNull(prUrlOrNull(null))
        assertNull(prUrlOrNull("not a url"))
        assertNull(prUrlOrNull("http://exa mple"))
        assertNull(prUrlOrNull("github.com/o/r/pull/12"))
    }

    // ------------------------------------------------- menu(): the guards

    @Test
    fun menuBeforeAnyFillReturnsNullForEveryChip() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig())
        val menus = menus(core)
        val platform = RecordingPlatform()
        assertNull(menus.menu("model", platform))
        assertNull(menus.menu("effort", platform))
        assertNull(menus.menu("pr", platform))
        assertTrue("menu() makes no core call", core.callLog.isEmpty())
    }

    @Test
    fun menuWithNoRowReturnsNullForEveryChip() {
        val core = FakeChipMenuCore(row = null, config = null)
        val menus = menus(core)
        fill(menus, core)
        val platform = RecordingPlatform()
        assertNull(menus.menu("model", platform))
        assertNull(menus.menu("effort", platform))
        assertNull(menus.menu("pr", platform))
        assertTrue(core.callLog.isEmpty())
    }

    // android-next #01: `context` now has a menu (the details card), but
    // only once usage is reported — this fill has none, so it stays null.
    @Test
    fun branchContextAndUnknownChipsHaveNoMenu() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(branch = "main"), config = chatConfig())
        val menus = menus(core)
        fill(menus, core)
        val platform = RecordingPlatform()
        assertNull(menus.menu("branch", platform))
        assertNull(menus.menu("context", platform))
        assertNull(menus.menu("anything-else", platform))
        assertTrue(core.callLog.isEmpty())
    }

    @Test
    fun theContextChipOpensTheDetailsCardWithoutACoreCall() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(branch = "main"), config = chatConfig())
        val menus = SessionChipMenus(core, io = fakeIo(), locale = { Locale.US })
        fill(menus, core, contextUsage = ContextUsage(tokens = 5_417uL, window = 1_048_576uL))
        val card = menus.menu("context", RecordingPlatform())
        assertNotNull(card)
        assertEquals("Context window", card!!.title)
        val rows = card.children.map { it as MenuItem }
        assertEquals(listOf("5,417 / 1,048,576 tokens", "1,043,159 tokens remaining"), rows.map { it.title })
        assertEquals("1% used", rows[0].subtitle)
        assertTrue("read-only rows", rows.all { it.informational })
        assertTrue("menu() makes no core call", core.callLog.isEmpty())
    }

    @Test
    fun theContextCardNeedsNoRow() {
        // The usage lives on the composer, not the workspace row, so the
        // card skips iOS's `guard let row` that the other chips keep.
        val core = FakeChipMenuCore(row = null, config = null)
        val menus = SessionChipMenus(core, io = fakeIo(), locale = { Locale.US })
        fill(menus, core, contextUsage = ContextUsage(tokens = 48_210uL, window = null))
        val rows = menus.menu("context", RecordingPlatform())!!.children.map { it as MenuItem }
        assertEquals(listOf("48,210 tokens used", "Context limit not reported"), rows.map { it.title })
        assertNull("the other chips still need the row", menus.menu("model", RecordingPlatform()))
    }

    @Test
    fun aPrWithoutAUrlOrWithABlankUrlYieldsNoMenu() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(pullRequest = chipMenuPullRequest(url = "")),
            config = null,
        )
        val menus = menus(core)
        fill(menus, core)
        assertNull(menus.menu("pr", RecordingPlatform()))
        assertTrue(core.callLog.isEmpty())

        core.row = chipMenuSessionRow(pullRequest = null)
        fill(menus, core)
        assertNull(menus.menu("pr", RecordingPlatform()))
        assertTrue(core.callLog.isEmpty())
    }

    @Test
    fun thePrMenuHoldsExactlyTheTwoRowsAndTheyDriveThePlatform() {
        val url = "https://github.com/hoangvu12/roboco/pull/1234"
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(pullRequest = chipMenuPullRequest(title = "Fixture PR", url = url)),
            config = null,
        )
        val menus = menus(core)
        fill(menus, core)
        val platform = RecordingPlatform()

        val menu = menus.menu("pr", platform)
        assertNotNull(menu)
        val prMenu = menu!!
        assertEquals("Fixture PR", prMenu.title)
        val rows = prMenu.children.filterIsInstance<MenuItem>()
        assertEquals(2, rows.size)
        assertEquals("Open Pull Request", rows[0].title)
        assertEquals("Copy Link", rows[1].title)
        assertEquals(sh.roboco.android.design.RobocoIcons.Safari, (rows[0].icon as MenuIcon.Symbol).icon)
        assertEquals(sh.roboco.android.design.RobocoIcons.Link, (rows[1].icon as MenuIcon.Symbol).icon)
        assertEquals("chip-menu-pr-open", rows[0].testTag)
        assertEquals("chip-menu-pr-copy", rows[1].testTag)

        rows[0].action()
        rows[1].action()
        assertEquals(listOf(url), platform.openedUrls)
        assertEquals(listOf(url), platform.copiedUrls)
        assertTrue("no core call from a PR menu", core.callLog.isEmpty())
    }

    // ------------------------------------------------- menu(): the content

    @Test
    fun theModelMenuDefersItsRowsAndTheLoadUsesTheConfigHarnessAndDevice() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(deviceId = "device-1"),
            config = chatConfig(harness = "codex", model = "claude-fable-5", reasoning = "xhigh"),
        )
        val menus = menus(core)
        fill(menus, core)

        val menu = menus.menu("model", RecordingPlatform())
        assertNotNull(menu)
        val modelMenu = menu!!
        assertEquals("Model", modelMenu.title)
        val deferred = modelMenu.children.single() as MenuElement.Deferred
        assertTrue(deferred.cached)
        assertTrue(core.callLog.isEmpty()) // the provider made no core call

        val rows = loadRows(deferred)
        assertEquals(claudeFallbackModels().map { it.label }, rows.map { it.title })
        assertEquals(claudeFallbackModels().map { it.description }, rows.map { it.subtitle })
        assertEquals(
            claudeFallbackModels().map { it.id == "claude-fable-5" },
            rows.map { it.checked },
        )
        assertEquals(claudeFallbackModels().map { "chip-menu-model-${it.id}" }, rows.map { it.testTag })
        // listModels got the snapshot's device + the config's harness.
        assertTrue(core.methodNames().contains("listModels(device-1, codex)"))
        // Every core call ran on fake-io.
        assertTrue(core.callLog.all { it.second == "fake-io" })
    }

    @Test
    fun aBlankModelDescriptionBecomesNoSubtitle() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(),
            config = chatConfig(model = "m-blank"),
            models = listOf(
                chipMenuModel("m-blank", "Blank", "  ", emptyList(), null),
                chipMenuModel("m-desc", "Desc", "A real subtitle", emptyList(), null),
            ),
        )
        val menus = menus(core)
        fill(menus, core)
        val rows = loadRows(deferredOf(menus, "model"))
        assertEquals(listOf(null, "A real subtitle"), rows.map { it.subtitle })
    }

    @Test
    fun aThrowingListModelsFallsBackAndAnEmptyListYieldsNoRows() {
        // Throws → the fallback list's rows.
        val throwing = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig())
        throwing.listModelsFailure = RuntimeException("catalog unreachable")
        val throwingMenus = menus(throwing)
        fill(throwingMenus, throwing)
        val rows = loadRows(deferredOf(throwingMenus, "model"))
        assertEquals(claudeFallbackModels().map { it.label }, rows.map { it.title })
        assertTrue(throwing.methodNames().contains("fallbackModels(claude-code)"))

        // An empty list (not a failure): no rows, and no fallback call.
        val empty = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(), models = emptyList())
        val emptyMenus = menus(empty)
        fill(emptyMenus, empty)
        assertTrue(runBlocking { deferredOf(emptyMenus, "model").load() }.isEmpty())
        assertFalse(empty.methodNames().any { it.startsWith("fallbackModels") })
    }

    @Test
    fun theEffortLoadUsesTheSelectedModelThenTheFirstModelThenNothing() {
        // The selected model's ladder…
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(),
            config = chatConfig(model = "claude-opus-4-7", reasoning = "xhigh"),
        )
        val menus = menus(core)
        fill(menus, core)
        val rows = loadRows(deferredOf(menus, "effort"))
        assertEquals(CLAUDE_XHIGH.map { kotlinReasoningLabel(it) }, rows.map { it.title })
        assertEquals(CLAUDE_XHIGH.map { it == "xhigh" }, rows.map { it.checked })
        assertEquals(CLAUDE_XHIGH.map { "chip-menu-effort-$it" }, rows.map { it.testTag })
        // The titles came from the label seam (FFI in production).
        CLAUDE_XHIGH.forEach { level ->
            assertTrue(core.methodNames().contains("reasoningLabel($level)"))
        }
        assertTrue(core.callLog.all { it.second == "fake-io" })

        // …no match → the first model's ladder.
        val noMatch = FakeChipMenuCore(
            row = chipMenuSessionRow(),
            config = chatConfig(model = "no-such-model", reasoning = "low"),
        )
        val noMatchMenus = menus(noMatch)
        fill(noMatchMenus, noMatch)
        val firstRows = loadRows(deferredOf(noMatchMenus, "effort"))
        assertEquals(FULL_LADDER.map { kotlinReasoningLabel(it) }, firstRows.map { it.title })
        assertEquals(FULL_LADDER.map { it == "low" }, firstRows.map { it.checked })

        // …no models at all: no rows.
        val none = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(), models = emptyList())
        val noneMenus = menus(none)
        fill(noneMenus, none)
        assertTrue(runBlocking { deferredOf(noneMenus, "effort").load() }.isEmpty())
    }

    // ------------------------------------------------------- the hoisting

    @Test
    fun anUnchangedSnapshotReusesTheDeferredAndAChangedSelectionDoesNot() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(),
            config = chatConfig(model = "claude-fable-5", reasoning = "xhigh"),
        )
        val menus = menus(core)
        fill(menus, core)
        val platform = RecordingPlatform()

        val first = menus.menu("model", platform)!!.children.single()
        val second = menus.menu("model", platform)!!.children.single()
        assertSame("the same snapshot reuses the hoisted deferred", first, second)

        // A different model changes the key.
        core.config = chatConfig(model = "claude-opus-5", reasoning = "xhigh")
        fill(menus, core)
        val newModel = menus.menu("model", platform)!!.children.single()
        assertFalse(newModel === first)

        // A different reasoning changes the key.
        core.config = chatConfig(model = "claude-opus-5", reasoning = "low")
        fill(menus, core)
        assertFalse(menus.menu("model", platform)!!.children.single() === newModel)

        // A different device changes the key.
        core.config = chatConfig(model = "claude-fable-5", reasoning = "xhigh")
        fill(menus, core, deviceId = "device-2")
        assertFalse(menus.menu("model", platform)!!.children.single() === first)

        // The effort chip hoists separately from the model chip.
        assertFalse(menus.menu("effort", platform)!!.children.single() === menus.menu("model", platform)!!.children.single())
    }

    // ---------------------------------------------------------- the writes

    @Test
    fun aModelPickWritesThePickedConfigOverTheCurrentBase() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(reasoning = "xhigh"))
        val results = mutableListOf<Throwable?>()
        var invalidations = 0
        val menus = menus(core, onResult = { results += it })
        menus.bind { invalidations++ }
        fill(menus, core)

        val rows = loadRows(deferredOf(menus, "model"))
        rows.first { it.title == "Opus 5" }.action()
        core.awaitWrites(1)
        awaitUntil("the result and the invalidate ran") { results.size == 1 && invalidations == 1 }

        // The base was the current config; the pick rule set the model and
        // kept xhigh (Opus 5 offers it).
        assertEquals(listOf(chatConfig(model = "claude-opus-5", reasoning = "xhigh")), core.setConfigs)
        assertEquals(listOf<Throwable?>(null), results)
        assertEquals(1, invalidations)
    }

    @Test
    fun withoutAConfigTheWriteStartsFromTheDefaultBase() {
        val core = FakeChipMenuCore(
            row = chipMenuSessionRow(harness = "codex", model = null, modelLabel = null, reasoning = null),
            config = null,
        )
        val menus = menus(core)
        fill(menus, core)

        val rows = loadRows(deferredOf(menus, "effort"))
        // The base read falls back to the row's harness; the pick sets
        // reasoning only — model stays null, sandbox workspace-write.
        rows.first { it.title == "Low" }.action()
        core.awaitWrites(1)

        val written = core.setConfigs.single()
        assertEquals(chatConfig(harness = "codex", model = null, reasoning = "low"), written)
        assertEquals(SandboxLevel.WORKSPACE_WRITE, written.sandbox)
        // The base read: sessionConfig (null) then sessionRow.
        assertTrue(core.methodNames().contains("sessionConfig"))
        assertTrue(core.methodNames().contains("sessionRow"))
    }

    @Test
    fun aFailingWriteReportsTheSameThrowableThenInvalidates() {
        val failure = IllegalStateException("Engine is offline; reconnecting")
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(model = "claude-fable-5"))
        core.setConfigFailure = failure
        val results = mutableListOf<Throwable?>()
        var invalidations = 0
        val menus = menus(core, onResult = { results += it })
        menus.bind { invalidations++ }
        fill(menus, core)

        loadRows(deferredOf(menus, "model")).first { it.title == "Fable 5.1" }.action()
        core.awaitWrites(1)
        awaitUntil("the result and the invalidate ran") { results.size == 1 && invalidations == 1 }

        assertEquals(1, results.size)
        assertSame("the exact throwable reaches the pill", failure, results[0])
        assertEquals(1, invalidations)
    }

    @Test
    fun twoRapidPicksSerialiseThroughTheMutexNeverInterleavingReads() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig(model = "claude-fable-5"))
        val gate = CompletableDeferred<Unit>()
        core.setConfigGate = gate
        val menus = menus(core)
        fill(menus, core)

        val rows = loadRows(deferredOf(menus, "model"))
        val first = rows.first { it.title == "Opus 5" }
        val second = rows.first { it.title == "Sonnet 5" }
        // The load's own calls (listModels + the load-time selection read)
        // sit below this index; only the writes' get/set pairs count.
        val callsBeforePicks = core.methodNames().size

        first.action() // acquires the lock, reads the base, suspends on the gate
        second.action() // queued behind the mutex
        Thread.sleep(100)
        // Only the first write's get+set happened: the second has not read yet.
        assertEquals(listOf("sessionConfig", "setSessionConfig"), core.methodNames().drop(callsBeforePicks))

        gate.complete(Unit)
        core.awaitWrites(2)
        assertEquals(
            "get, set, get, set — never get, get",
            listOf("sessionConfig", "setSessionConfig", "sessionConfig", "setSessionConfig"),
            core.methodNames().drop(callsBeforePicks),
        )
    }

    // ------------------------------------------------------------ dispose

    @Test
    fun afterDisposeLoadsResolveEmptyAndMakeNoCoreCall() {
        val core = FakeChipMenuCore(row = chipMenuSessionRow(), config = chatConfig())
        val menus = menus(core)
        fill(menus, core)
        val deferred = deferredOf(menus, "model")
        loadRows(deferred) // one load while open
        val callsBefore = core.callLog.size

        menus.dispose()
        assertTrue(runBlocking { deferred.load() }.isEmpty())
        assertEquals("a load after dispose calls no core method", callsBefore, core.callLog.size)
    }
}

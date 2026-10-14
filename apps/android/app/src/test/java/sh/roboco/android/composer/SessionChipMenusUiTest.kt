package sh.roboco.android.composer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric.buildActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChipsFiller
import sh.roboco.android.session.SessionController
import sh.roboco.android.session.SessionEventHub
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.SessionCore
import sh.roboco.android.session.StatusBanner
import sh.roboco.android.session.engineView
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.ClientEvent
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ModelInfo
import uniffi.roboco_core.SessionRow
import uniffi.roboco_core.TranscriptView

/**
 * The chip menus' end-to-end behaviour (android-ui-parity #89) — Robolectric
 * + compose-ui-test over a real [SessionChipMenus] (no native library: the
 * FFI seam is [FakeMenuCore], and every label comes from the Kotlin copy).
 *
 * Ports of the iOS `SessionFlowTests`:
 * - `testEffortPickerInSession` (`iosui:SessionFlowTests.swift:359-377`):
 *   the effort menu loads, a pick writes the config, the menu reopens, no
 *   crash;
 * - #664's `testModelPickerInSession`: the model chip shows the picked
 *   model **while the write is still suspended** — the optimistic
 *   override (android-app#08) updates the row and fires the hub before
 *   the RPC completes.
 *
 * Plus the loading/cache/failure/`canWrite` rows, and the platform half:
 * the Custom Tab intents and the clipboard. The clock runs
 * (`autoAdvance = true`) because the loads and writes hop to real IO
 * dispatchers, and `waitUntil` mirrors the iOS tests' 5 s windows.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionChipMenusUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun runClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = true
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------- the fake

    /**
     * One fake for both seams: the controller's [SessionCore] and the
     * menus' [ChipMenuCore] share the chat state, so a write's optimistic
     * override is visible to the next refresh exactly like android-app#08
     * (update the config + the row, fire the hub, then let the RPC
     * complete — or revert and throw).
     */
    private class FakeMenuCore(
        override val chatId: String = "chat-a",
        /** 84's draft key reads this (SessionCore.engineId); UUID-shaped like FakeSessionCore's. */
        override val engineId: String = "11111111-1111-4111-8111-111111111111",
        /** The controller's hub; [controllerSetup] wires it in, so the optimistic write's events reach the controller. */
        @Volatile var hub: SessionEventHub? = null,
        row: SessionRow? = chipMenuSessionRow(),
        config: ChatConfig? = chatConfig(model = "claude-fable-5", reasoning = "xhigh"),
    ) : SessionCore, ChipMenuCore {
        @Volatile var rowResult: SessionRow? = row
        @Volatile var configResult: ChatConfig? = config

        /** A connected engine, so the banner derivation stays clean. */
        @Volatile var engineResult: EngineView? = engineView()

        /** `listModels` waits on this before answering (the Loading row). */
        var listModelsGate: CompletableDeferred<Unit>? = null

        /** `setSessionConfig` waits on this after the optimistic override. */
        var writeGate: CompletableDeferred<Unit>? = null

        var setConfigFailure: Throwable? = null
        val setConfigs = mutableListOf<ChatConfig>()

        @Volatile var listModelsCalls = 0
        @Volatile var setConfigCalls = 0

        val callCount: Int get() = listModelsCalls + setConfigCalls + setConfigs.size

        // ---- SessionCore (the controller's pulls)

        override fun composer(): ComposerState = chipMenuComposerState(deviceId = "device-1")

        override fun sessionRow(): SessionRow? = rowResult

        override fun sessionConfig(): ChatConfig? = configResult

        override fun engine(): EngineView? = engineResult

        override fun attachTranscript(view: TranscriptView): Boolean = true

        override fun setViewAttached(attached: Boolean) {}

        override fun markSeen() {}

        // ---- ChipMenuCore (the menus' loads and writes)

        override suspend fun listModels(deviceId: String, harness: String): List<ModelInfo> {
            listModelsCalls++
            listModelsGate?.await()
            return claudeFallbackModels()
        }

        override fun fallbackModels(harness: String): List<ModelInfo> = claudeFallbackModels()

        override fun reasoningLabel(level: String): String = kotlinReasoningLabel(level)

        override suspend fun setSessionConfig(config: ChatConfig) {
            setConfigCalls++
            setConfigs += config
            // android-app#08's optimistic override: the config, the row and
            // the events land before the RPC completes; 78's chips read the
            // row (modelLabel flips at once).
            val previousConfig = configResult
            val previousRow = rowResult
            configResult = config
            val label = claudeFallbackModels().firstOrNull { it.id == config.model }?.label
            rowResult = rowResult?.copy(
                model = config.model,
                modelLabel = label ?: rowResult?.modelLabel,
                reasoning = config.reasoning,
            )
            hub?.onClientEvent(ClientEvent.ComposerChanged(chatId, 2uL))
            hub?.onClientEvent(ClientEvent.WorkspaceChanged(2uL))
            writeGate?.await()
            if (setConfigFailure != null) {
                // The revert the core performs before rethrowing.
                configResult = previousConfig
                rowResult = previousRow
                hub?.onClientEvent(ClientEvent.WorkspaceChanged(3uL))
                throw setConfigFailure!!
            }
        }
    }

    /** Records the platform calls (no real Custom Tab in these tests). */
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

    /** The strip over the menus, hosted like the session screen. */
    @Composable
    private fun StripHarness(
        chips: List<ComposerChip>,
        menus: SessionChipMenus,
        platform: ChipMenuPlatform,
        canWrite: Boolean = true,
    ) {
        RobocoTheme(Appearance.Light) {
            GlassMenuHost(hazeState = rememberHazeState()) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    ChipStrip(
                        chips = chips,
                        chipMenus = chipProviders(menus, platform),
                        canWrite = canWrite,
                        onChipTap = {},
                    )
                }
            }
        }
    }

    /** The controller-driven harness: the real chip derivation + the menus. */
    @Composable
    private fun ControllerHarness(controller: SessionController, menus: SessionChipMenus, platform: ChipMenuPlatform) {
        RobocoTheme(Appearance.Light) {
            GlassMenuHost(hazeState = rememberHazeState()) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    val chrome by controller.chrome.collectAsState()
                    ChipStrip(
                        chips = chrome.chips,
                        chipMenus = chipProviders(menus, platform),
                        canWrite = chrome.canWrite,
                        onChipTap = {},
                    )
                }
            }
        }
    }

    private fun chipProviders(menus: SessionChipMenus, platform: ChipMenuPlatform): Map<String, ChipMenuProvider> =
        mapOf(
            "model" to { menus.menu("model", platform) },
            "effort" to { menus.menu("effort", platform) },
            "pr" to { menus.menu("pr", platform) },
        )

    /** The chat-deploy demo chips (the iOS fixture's harness/model/effort row). */
    private fun chatDeployChips(): List<ComposerChip> = listOf(
        ComposerChip(id = "model", title = "Fable 5", icon = ChipIcon.Brand("claude-code")),
        ComposerChip(id = "effort", title = "X-High", symbol = "gauge.with.dots.needle.67percent"),
        ComposerChip(id = "branch", title = "main", icon = ChipIcon.Branch),
    )

    /** A controller + menus + banner over one fake core. */
    private fun controllerSetup(
        core: FakeMenuCore,
    ): Triple<SessionController, SessionChipMenus, StatusBanner> {
        val hub = SessionEventHub(post = { it.run() })
        // The fake fires the optimistic override's ComposerChanged /
        // WorkspaceChanged through this hub - the refresh that shows a pick
        // while its write is still suspended.
        core.hub = hub
        val menus = SessionChipMenus(core, io = Dispatchers.Default)
        val banner = StatusBanner(retryDelivery = {})
        menus.onSettingResult = { t ->
            if (t == null) banner.noteSettingSucceeded() else banner.noteSettingFailed(t)
        }
        val controller = SessionController.create(
            core = core,
            hub = hub,
            fillers = listOf(
                SessionChipsFiller(reasoningLabel = { kotlinReasoningLabel(it) }),
                menus,
                banner,
            ),
            io = Dispatchers.Default.limitedParallelism(1),
            isMainThread = { false },
        )
        return Triple(controller, menus, banner)
    }

    private fun rowsOf(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // ── the iOS port: testEffortPickerInSession ────────────────────────────

    @Test
    fun effortPickerLoadsPicksWritesAndReopens() {
        val core = FakeMenuCore()
        val menus = SessionChipMenus(core, io = Dispatchers.Default)
        menus.fill(
            SessionChrome(),
            ChromeInput(
                chatId = core.chatId,
                composer = chipMenuComposerState(deviceId = "device-1"),
                row = core.rowResult,
                engine = core.engineResult,
                config = core.configResult,
            ),
        )
        compose.setContent {
            StripHarness(chatDeployChips(), menus, RecordingPlatform())
        }
        compose.waitForIdle()

        // 4 — the first row appears within 5 s (the rows carry their own
        //     tags, so glass-menu-item never matches them).
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitUntil(5_000) { rowsOf("chip-menu-effort-low") > 0 }

        // The checked row is xhigh (the chat's config).
        compose.onNodeWithTag("chip-menu-effort-xhigh").assertIsSelected()
        FULL_LADDER.forEach { level ->
            assertTrue("row $level exists", rowsOf("chip-menu-effort-$level") > 0)
        }

        // 5 — tap the first row; the menu closes and the config is written.
        compose.onNodeWithTag("chip-menu-effort-low").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }
        compose.waitUntil(5_000) { core.setConfigCalls == 1 }
        assertEquals(chatConfig(model = "claude-fable-5", reasoning = "low"), core.setConfigs.single())

        // 6/7 — the chip still exists; tap it again: the rows appear again.
        compose.onNodeWithTag("composer-chip-effort").assertTextContains("X-High")
        compose.onNodeWithTag("composer-chip-effort").performClick()
        compose.waitUntil(5_000) { rowsOf("chip-menu-effort-low") > 0 }

        // 6 — the app is in the foreground: the strip is still on screen.
        compose.onNodeWithTag("composer-chip-strip").assertExists()
    }

    // ── the #664 port: testModelPickerInSession ────────────────────────────

    @Test
    fun aModelPickShowsOnTheChipWhileTheWriteIsStillSuspended() {
        val core = FakeMenuCore()
        val writeGate = CompletableDeferred<Unit>()
        core.writeGate = writeGate
        val (controller, menus, _) = controllerSetup(core)
        try {
            compose.setContent { ControllerHarness(controller, menus, RecordingPlatform()) }
            compose.waitForIdle()

            // 1 — the chip does not contain "Fable 5.1" (it shows "Fable 5").
            compose.onNodeWithTag("composer-chip-model").assertTextContains("Fable 5")
            assertFalse(
                "the chip does not contain Fable 5.1 yet",
                controller.chrome.value.chips.first { it.id == "model" }.title.contains("Fable 5.1"),
            )

            // 2 — tap the chip, wait for the rows, click Fable 5.1.
            compose.onNodeWithTag("composer-chip-model").performClick()
            compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-fable-5-1") > 0 }
            compose.onNodeWithTag("chip-menu-model-claude-fable-5-1").performClick()

            // 3 — the chip shows the picked model within 5 s, WHILE the
            //     fake's setSessionConfig is still suspended on the gate.
            compose.waitUntil(5_000) {
                controller.chrome.value.chips.firstOrNull { it.id == "model" }?.title?.contains("Fable 5.1") == true
            }
            compose.onNodeWithTag("composer-chip-model").assertTextContains("Fable 5.1")
            assertFalse("the write is still suspended", writeGate.isCompleted)

            // Let the write finish; the write result reaches the banner
            // (nothing failed: the pill stays clear).
            writeGate.complete(Unit)
            compose.waitUntil(5_000) { core.setConfigCalls == 1 }
            assertEquals(
                "applyModelPick(base, Fable 5.1): xhigh is offered, so it is kept",
                chatConfig(model = "claude-fable-5-1", reasoning = "xhigh"),
                core.setConfigs.single(),
            )
            compose.waitUntil(5_000) { controller.chrome.value.banner == SessionChrome.Banner.None }
        } finally {
            menus.dispose()
            controller.dispose()
        }
    }

    // ── loading, cache, failure, canWrite ──────────────────────────────────

    @Test
    fun aSuspendedCatalogShowsExactlyOneLoadingRowThenTheRows() {
        val core = FakeMenuCore()
        val gate = CompletableDeferred<Unit>()
        core.listModelsGate = gate
        val menus = SessionChipMenus(core, io = Dispatchers.Default)
        menus.fill(
            SessionChrome(),
            ChromeInput(
                chatId = core.chatId,
                composer = chipMenuComposerState(deviceId = "device-1"),
                row = core.rowResult,
                engine = core.engineResult,
                config = core.configResult,
            ),
        )
        compose.setContent { StripHarness(chatDeployChips(), menus, RecordingPlatform()) }
        compose.waitForIdle()

        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitUntil(5_000) { rowsOf("glass-menu-loading") > 0 }
        assertEquals("exactly one loading row", 1, rowsOf("glass-menu-loading"))
        assertEquals("no rows while the catalog is suspended", 0, rowsOf("chip-menu-model-claude-fable-5"))

        gate.complete(Unit)
        compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-fable-5") > 0 }
        compose.waitUntil(5_000) { rowsOf("glass-menu-loading") == 0 }
    }

    @Test
    fun reopeningWithoutAChromeChangeShowsTheCachedRowsAndLoadsOnce() {
        val core = FakeMenuCore()
        val menus = SessionChipMenus(core, io = Dispatchers.Default)
        menus.fill(
            SessionChrome(),
            ChromeInput(
                chatId = core.chatId,
                composer = chipMenuComposerState(deviceId = "device-1"),
                row = core.rowResult,
                engine = core.engineResult,
                config = core.configResult,
            ),
        )
        compose.setContent { StripHarness(chatDeployChips(), menus, RecordingPlatform()) }
        compose.waitForIdle()

        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-fable-5") > 0 }
        Espresso.pressBack()
        compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }
        assertEquals("listModels ran once", 1, core.listModelsCalls)

        // Reopen: the cached rows appear with no loading row and no reload.
        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-fable-5") > 0 }
        compose.waitUntil(5_000) { rowsOf("glass-menu-loading") == 0 }
        assertEquals("the cache served the second open", 1, core.listModelsCalls)
    }

    @Test
    fun aFailingWriteShowsOnThePillAndASuccessfulOneClearsIt() {
        val core = FakeMenuCore()
        core.setConfigFailure = IllegalStateException("Engine is offline; reconnecting")
        val (controller, menus, _) = controllerSetup(core)
        try {
            compose.setContent { ControllerHarness(controller, menus, RecordingPlatform()) }
            compose.waitForIdle()

            compose.onNodeWithTag("composer-chip-model").performClick()
            compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-opus-5") > 0 }
            compose.onNodeWithTag("chip-menu-model-claude-opus-5").performClick()
            compose.waitUntil(5_000) { core.setConfigCalls == 1 }
            compose.waitUntil(5_000) {
                controller.chrome.value.banner == SessionChrome.Banner.Failed(
                    "Couldn't change session settings: Engine is offline; reconnecting",
                )
            }

            // A later successful pick clears the pill.
            core.setConfigFailure = null
            compose.waitUntil(5_000) { rowsOf("glass-menu") == 0 }
            compose.onNodeWithTag("composer-chip-model").performClick()
            compose.waitUntil(5_000) { rowsOf("chip-menu-model-claude-fable-5") > 0 }
            compose.onNodeWithTag("chip-menu-model-claude-fable-5").performClick()
            compose.waitUntil(5_000) { core.setConfigCalls == 2 }
            compose.waitUntil(5_000) { controller.chrome.value.banner == SessionChrome.Banner.None }
        } finally {
            menus.dispose()
            controller.dispose()
        }
    }

    @Test
    fun canWriteFalseMakesTheChipInert() {
        val core = FakeMenuCore()
        val menus = SessionChipMenus(core, io = Dispatchers.Default)
        menus.fill(
            SessionChrome(),
            ChromeInput(
                chatId = core.chatId,
                composer = chipMenuComposerState(deviceId = "device-1"),
                row = core.rowResult,
                engine = core.engineResult,
                config = core.configResult,
            ),
        )
        var providerCalls = 0
        val counting: Map<String, ChipMenuProvider> = mapOf(
            "model" to {
                providerCalls++
                menus.menu("model", RecordingPlatform())
            },
        )
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                GlassMenuHost(hazeState = rememberHazeState()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ChipStrip(
                            chips = chatDeployChips(),
                            chipMenus = counting,
                            canWrite = false,
                            onChipTap = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("composer-chip-model").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("glass-menu").assertDoesNotExist()
        assertEquals("the provider is not invoked", 0, providerCalls)
        assertEquals("no core method runs", 0, core.callCount)
    }

    // ── the platform half: Custom Tabs + clipboard ─────────────────────────

    @Test
    fun withoutACustomTabsBrowserOpenUrlFallsBackToAPlainViewIntent() {
        val activity = buildActivity(Activity::class.java).setup().get()
        AndroidChipMenuPlatform(activity, dark = false).openUrl("https://github.com/o/r/pull/12")

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(Uri.parse("https://github.com/o/r/pull/12"), started.data)
        assertNull("no package pin without a Custom Tabs browser", started.getPackage())
    }

    @Test
    fun aCustomTabsServiceIsPinnedWithTheSchemeAndToolbarColours() {
        val activity = buildActivity(Activity::class.java).setup().get()
        val pm = shadowOf(activity.packageManager)
        // The default VIEW handler for http (androidx's getPackageName
        // resolves it first and adds its package to the candidate list)…
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.example.browser"
                    name = "com.example.browser.Browser"
                }
            },
        )
        // …and that package's Custom Tabs service (needs the <queries>
        // element on a real device).
        val serviceIntent = Intent("android.support.customtabs.action.CustomTabsService")
            .setPackage("com.example.browser")
        pm.addResolveInfoForIntent(
            serviceIntent,
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = "com.example.browser"
                    name = "com.example.browser.CustomTabsService"
                }
            },
        )

        AndroidChipMenuPlatform(activity, dark = false).openUrl("https://github.com/o/r/pull/12")
        val light = shadowOf(activity).nextStartedActivity
        assertNotNull(light)
        assertEquals("com.example.browser", light!!.getPackage())
        assertEquals(
            CustomTabsIntent.COLOR_SCHEME_LIGHT,
            light.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1),
        )
        assertEquals(
            0xFFF3F3F5.toInt(),
            light.getIntExtra(CustomTabsIntent.EXTRA_TOOLBAR_COLOR, 0),
        )
        assertEquals(Uri.parse("https://github.com/o/r/pull/12"), light.data)

        AndroidChipMenuPlatform(activity, dark = true).openUrl("https://github.com/o/r/pull/12")
        val dark = shadowOf(activity).nextStartedActivity
        assertNotNull(dark)
        assertEquals("com.example.browser", dark!!.getPackage())
        assertEquals(
            CustomTabsIntent.COLOR_SCHEME_DARK,
            dark.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1),
        )
        assertEquals(
            0xFF060606.toInt(),
            dark.getIntExtra(CustomTabsIntent.EXTRA_TOOLBAR_COLOR, 0),
        )
    }

    @Test
    fun openUrlWithoutAnyHandlerThrowsNothing() {
        val activity = buildActivity(Activity::class.java).setup().get()
        // No Custom Tabs service, no resolvable browser: neither path throws.
        AndroidChipMenuPlatform(activity, dark = true).openUrl("https://github.com/o/r/pull/12")
    }

    @Test
    fun copyLinkPutsTheUrlOnTheClipboardWithTheLinkLabel() {
        val activity = buildActivity(Activity::class.java).setup().get()
        AndroidChipMenuPlatform(activity, dark = false).copyLink("https://github.com/o/r/pull/12")

        val manager = activity.getSystemService(ClipboardManager::class.java)
        assertNotNull(manager)
        val clip = manager!!.primaryClip
        assertNotNull(clip)
        assertEquals("Link", clip!!.description.label)
        assertEquals("https://github.com/o/r/pull/12", clip.getItemAt(0).text)
    }
}

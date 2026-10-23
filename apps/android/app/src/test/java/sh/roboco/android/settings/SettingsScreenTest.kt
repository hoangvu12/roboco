package sh.roboco.android.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.CoreStatus
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.design.ActionSheetHost
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.BottomChromeInsets
import sh.roboco.android.design.LocalBottomChrome
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag
import sh.roboco.android.notify.NotificationKind
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.NotificationStatus
import sh.roboco.android.sessions.FolderRoute
import sh.roboco.android.shell.MainTabShell
import sh.roboco.android.shell.MainTabState
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.shell.ShellRouteContent
import sh.roboco.android.shell.TabId
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.WallpaperEffect

/**
 * The Settings screen's assembly (android-ui-parity #103, Robolectric +
 * compose-ui-test, 402 × 874 dp at xxhdpi): the section order and the
 * headers, the dropped iOS sections' absence, the large title at rest,
 * the row routing end to end (pushes run through [ShellRouteContent] on
 * a test [NavStack], exactly as in the shell), the reload story (each
 * source flip lands after **one** frame and nothing animates — iOS's
 * `animatingDifferences: false`), the single `ON_RESUME` re-read (99's
 * contract), and the bottom scroll inset under the chrome group (30's
 * padding + 16).
 *
 * The section states are injected through each ticket's own seam (102's
 * `engines` flow, 96's `appearance` flow, 97's fake [WallpaperSectionActions],
 * 99's public constructor or factory, 101's factory parameters), exactly
 * as [SettingsScreen]'s parameters allow — no new seams. The engine rows
 * carry 102's real ids (`device:<id>`, the iOS prefix 102 kept), so the
 * routing test taps `settings-device:e1`.
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ fixtures

    private val context: Context
        get() = compose.activity

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val alerts = AlertHostState()

    /** 102's seam faked at the source: one engine, a second on flip. */
    private val engines = MutableStateFlow<List<EngineView>?>(
        listOf(engineView(id = "e1", name = "MacBook Pro", state = EngineState.Connected)),
    )

    private val engineOpens = mutableListOf<String>()
    private var pairCalls = 0

    /** 96's seam: the stored preference flow and the writer recorder. */
    private val appearanceFlow = MutableStateFlow(Appearance.System)
    private val picks = mutableListOf<Appearance>()

    /** 97's seam: a fake store (unset) with a counting `remove`. */
    private val wallpaperFlow = MutableStateFlow(
        WallpaperStore.State(set = false, name = null, effect = WallpaperEffect.NONE, generation = 0),
    )
    private var removeCalls = 0
    private val wallpaperActions = WallpaperSectionActions(
        state = wallpaperFlow,
        set = { _ -> wallpaperFlow.value = wallpaperFlow.value.copy(set = true, name = "Picked photo") },
        setEffect = { effect -> wallpaperFlow.value = wallpaperFlow.value.copy(effect = effect) },
        remove = { removeCalls++ },
    )

    /** 100's wiring: the shared preference flow and the writer recorder. */
    private val groupFlow = MutableStateFlow(false)
    private val groupChanges = mutableListOf<Boolean>()

    /** ADR 0010's wiring: the Stay connected flow and the writer recorder. */
    private val stayFlow = MutableStateFlow(false)
    private val stayChanges = mutableListOf<Boolean>()

    /** 99's test seam: a private prefs file, a mutable fake status, a counting request. */
    private val testPrefs by lazy {
        NotificationPrefs(context.getSharedPreferences("settings-screen-test", Context.MODE_PRIVATE))
    }
    private var fakeStatus: NotificationStatus = NotificationStatus.NotDetermined
    private var permissionRequests = 0

    /** The injected hand-built state (99's public constructor), built on first use. */
    private val injectedNotifications by lazy {
        NotificationSectionState(
            prefs = testPrefs,
            readStatus = { fakeStatus },
            alerts = alerts,
            openSettings = {},
        ).also { state -> state.requestPermission = { permissionRequests++ } }
    }

    /** The real factory's state, captured for the reload test's status flips. */
    private var factoryNotifications: NotificationSectionState? = null

    /**
     * A recording registry: the composition's launchers (97's picker, 99's
     * permission request) register against it instead of the activity's,
     * so no real launch can happen and every launch is visible.
     */
    private class RecordingRegistry : ActivityResultRegistry() {
        val inputs = mutableListOf<Any?>()
        val requestCodes = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            requestCodes += requestCode
            inputs += input
        }
    }

    private val registry = RecordingRegistry()
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    /** The Settings test root route (26's `TabRootRoute(Settings)` stand-in). */
    private object SettingsTestRoot : NavRoute {
        override val paneTitle: String get() = "Settings"
    }

    private lateinit var stack: NavStack

    // ------------------------------------------------------------- mounting

    @Before
    fun resetNotificationState() {
        // The injected state's prefs may carry earlier tests' values (the
        // Robolectric application is fresh but the static singleton is
        // not); the assertions below depend on these.
        testPrefs.enabled = false
        for (kind in NotificationKind.entries) testPrefs.set(kind, true)
        fakeStatus = NotificationStatus.NotDetermined
    }

    /**
     * The ticket's host shape: [AlertHost] (18) provides `LocalAlerts` for
     * 99's denied alert, [ActionSheetHost] (19) for 97's effect sheet, a
     * frozen glyph clock and a glass menu host for the pushed screens (45's
     * folder loading spinner, 15's ⋯), and a real [NavStackHost] so pushes
     * run through [ShellRouteContent] exactly as in the shell —
     * `LocalNavStack`/`LocalNavEntry` exist for the screens that read
     * them.
     *
     * @param notifications the notification state factory — `null` uses
     *   the injected hand-built [NotificationSectionState] (99's public
     *   constructor with the fake status and the counting request)
     * @param defaultDeviceRouting keep 102's default engine/pair handlers
     *   (the Engine detail push) instead of the recording lambdas
     */
    private fun mount(
        notifications: (@Composable () -> NotificationSectionState)? = null,
        defaultDeviceRouting: Boolean = false,
    ) {
        stack = NavStack(SettingsTestRoot)
        val s = stack
        val notifFactory: @Composable () -> NotificationSectionState =
            notifications ?: { injectedNotifications }
        val openEngine: ((String) -> Unit)? = if (defaultDeviceRouting) null else ({ engineOpens += it })
        val pair: (() -> Unit)? = if (defaultDeviceRouting) null else ({ pairCalls++ })
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AlertHost(alerts) {
                        ActionSheetHost {
                            ProvideGlyphClock(frozenAtNanos = 0L) {
                                GlassMenuHost {
                                    NavStackHost(stack = s, active = true) { entry ->
                                        if (entry.route === SettingsTestRoot) {
                                            val devices = rememberDevicesSectionState(
                                                engines = engines,
                                                onOpenEngine = openEngine,
                                                onPair = pair,
                                            )
                                            val appearance = rememberAppearanceSectionState(
                                                appearance = appearanceFlow,
                                                onPick = { picks += it },
                                            )
                                            val wallpaper = rememberWallpaperSectionState(wallpaperActions)
                                            val about = rememberAboutSectionState(
                                                versionName = "0.1.0",
                                                versionCode = 1L,
                                                core = CoreStatus.Loaded("0.1.0"),
                                            )
                                            SettingsScreen(
                                                topInset = 24.dp,
                                                devices = devices,
                                                notifications = notifFactory(),
                                                appearance = appearance,
                                                wallpaper = wallpaper,
                                                groupByProject = groupFlow,
                                                onGroupByProjectChange = { groupChanges += it },
                                                about = about,
                                                stayConnected = stayFlow,
                                                onStayConnectedChange = { stayChanges += it },
                                            )
                                        } else if (entry.route is EngineDetailRoute) {
                                            // The page reads this test's engines (with e1), not
                                            // the app's coreStartup flow: under Robolectric the
                                            // core may load and publish an empty list, and the
                                            // page pops itself on a loaded flow without its engine.
                                            EngineDetailScreen(
                                                (entry.route as EngineDetailRoute).engineId,
                                                engines = engines,
                                            )
                                        } else {
                                            ShellRouteContent(entry.route)
                                        }
                                    }
                                }
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

    private fun nodeExists(tag: String): Boolean =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun row(id: String) = compose.onNodeWithTag(settingsTag(id))

    private fun switchNode(id: String) = compose.onNodeWithTag(toggleTag(id), useUnmergedTree = true)

    private fun boundsBottom(id: String): Float = row(id).getUnclippedBoundsInRoot().bottom.value

    private fun boundsTop(id: String): Float = row(id).getUnclippedBoundsInRoot().top.value

    /** Every `settings-*`-tagged node in the unmerged tree (the rows, not the screen root). */
    private fun settingsRows(): List<Pair<String, SemanticsNode>> =
        compose.onAllNodes(settingsTagMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
                if (tag == "settings-screen") return@mapNotNull null
                tag.removePrefix("settings-") to node
            }

    private val settingsTagMatcher = SemanticsMatcher("tag starts with settings-") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("settings-") == true
    }

    private val headingMatcher = SemanticsMatcher("heading") { node ->
        node.config.contains(SemanticsProperties.Heading)
    }

    private fun isHidden(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config
            .contains(SemanticsProperties.HideFromAccessibility)

    /**
     * Scrolls the screen's list until [id]'s row is in view. The
     * LazyColumn is untagged (25 owns it), so the scrollable is found by
     * its scroll semantics — the only node with `ScrollBy` before any
     * push (the pushed screens own their own lists).
     */
    private fun scrollToRow(id: String) {
        compose.onNode(scrollableMatcher).performScrollToNode(hasTestTag(settingsTag(id)))
        compose.waitForIdle()
    }

    private val scrollableMatcher = SemanticsMatcher("has a scroll action") { node ->
        node.config.contains(SemanticsActions.ScrollBy)
    }

    // ------------------------------------------------- order and headers

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h3000dp-xxhdpi")
    fun rowsAppearInSettingsSectionOrderAndAllSectionsArePresent() {
        mount()

        val rows = settingsRows()
        assertTrue("no settings rows found", rows.isNotEmpty())
        var last = -1
        for ((id, node) in rows.sortedBy { it.second.positionInRoot.y }) {
            val section = settingsSectionFor(id)
            assertNotNull("row '$id' claims no section", section)
            val ordinal = section!!.ordinal
            assertTrue(
                "row '$id' ($section) at ${node.positionInRoot.y} is out of order after section #$last",
                ordinal >= last,
            )
            last = ordinal
        }
        assertEquals(
            "every section is on screen",
            SettingsSection.entries.toSet(),
            rows.map { settingsSectionFor(it.first) }.toSet(),
        )
    }

    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h3000dp-xxhdpi")
    fun headersReadAgentAccountsDevicesNotificationsBackgroundAppearanceWallpaperSessionsAbout() {
        mount()
        val headings = compose.onAllNodes(headingMatcher).fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString(separator = "") { it.text } ?: ""
            }
        val expected = listOf(
            "Agent Accounts", "Devices", "Notifications", "Background", "Appearance", "Wallpaper", "Sessions", "About",
        )
        var searchFrom = 0
        for (header in expected) {
            val local = headings.subList(searchFrom, headings.size).indexOf(header)
            assertTrue("header '$header' not found in $headings", local >= 0)
            searchFrom += local + 1
        }
    }

    @Test
    fun noAccountVoiceOrSignOutAnywhere() {
        mount()
        compose.onNodeWithTag("settings-account").assertDoesNotExist()
        compose.onNodeWithTag("settings-voice").assertDoesNotExist()
        compose.onNodeWithTag("settings-signout").assertDoesNotExist()
        for (dropped in listOf("Sign Out", "Voice", "Account")) {
            assertFalse("text '$dropped' exists", textExists(dropped))
        }
    }

    @Test
    fun theLargeTitleIsSettingsAndVisibleAtRest() {
        mount()
        // 25's contract at rest: the large title is exposed, the inline
        // title is hidden (alpha 0 — 25's own test pins the alpha itself).
        compose.onNodeWithTag("nav-large-title").assertExists()
        assertTrue(textExists("Settings"))
        assertFalse("the large title must be visible at rest", isHidden("nav-large-title"))
        assertTrue("the inline title is hidden at rest", isHidden("nav-inline-title"))
    }

    // ------------------------------------------------------ row routing

    @Test
    fun tappingAnAppearanceRowWritesThePreferenceThroughTheSeam() {
        mount()
        // The Background section (ADR 0010) pushes Appearance below the fold.
        scrollToRow("appearance:2")
        row("appearance:2").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Appearance.Dark), picks)
    }

    @Test
    // At the class's 874 dp phone height on purpose. With a wallpaper set,
    // Remove straddles the bottom edge by a few px at rest, so
    // performScrollToNode issues a ScrollBy of a few dp: c lands inside the
    // large title's 0 < c < 52 range. The scaffold's end-of-scroll snap used
    // to undo it (back to c = 0), and performScrollToNode re-issued the same
    // ScrollBy forever: the OOM on CI afca10c5 (round 45). The snap now only
    // follows a user drag (LargeTitleScaffold), so this scroll stays put.
    fun tappingRemoveWallpaperRemovesAtOnce() {
        // The wallpaper is set (a fixture flip before mounting): the
        // effect and remove rows exist.
        wallpaperFlow.value = wallpaperFlow.value.copy(set = true, name = "IMG_0412")
        mount()
        scrollToRow(WallpaperRowIds.Remove)
        row(WallpaperRowIds.Remove).performClick()
        compose.waitForIdle()
        assertEquals(1, removeCalls)
    }

    /**
     * /code-review F7 diagnostics (ticket 103): the same tap at the class's
     * real 874 dp, where Remove starts below the fold. The old
     * scroll-then-click there never went idle (21 min into an OOM). It could
     * not be pinned statically, so this test bounds every step instead: a
     * frozen clock, fixed scroll steps and at most 240 frames, recording the
     * scroll value and Remove's bounds per frame. A frame loop fails with
     * that trace (which value oscillates names the cause) instead of hanging
     * the job. It stayed green while the loop was live (CI afca10c5): its
     * 300 dp steps jump straight past the 52 dp collapse range, where the
     * snap never fires. The loop needed a small in-range scroll (round 45;
     * see the test above). Kept as a bounded scroll-then-tap guard.
     */
    @Test
    fun removeWallpaperBelowTheFoldSettlesAndRemoves() {
        wallpaperFlow.value = wallpaperFlow.value.copy(set = true, name = "IMG_0412")
        mount()
        compose.mainClock.autoAdvance = false
        val trace = StringBuilder()
        val stepPx = with(compose.density) { 300.dp.toPx() }
        var visible = false
        for (step in 0 until 12) {
            if (removeInView()) {
                visible = true
                break
            }
            compose.onNode(scrollableMatcher).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, stepPx) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            trace.append("step $step: ").append(frameState()).append('\n')
        }
        assertTrue("Remove never came into view:\n$trace", visible)
        // Settle: done after 30 unchanged frames, failing after 240.
        var last = frameState()
        var still = 0
        var frames = 0
        while (frames < 240 && still < 30) {
            compose.mainClock.advanceTimeByFrame()
            frames++
            val now = frameState()
            if (now == last) {
                still++
            } else {
                still = 0
                trace.append("frame $frames: ").append(now).append('\n')
            }
            last = now
        }
        assertTrue(
            "the page never settled after scrolling to Remove (the frame loop):\n${trace.takeLast(4_000)}",
            still >= 30,
        )
        row(WallpaperRowIds.Remove).performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(1, removeCalls)
    }

    /** The scroll value/max and Remove's bounds: one line per frame of the trace. */
    private fun frameState(): String {
        val list = compose.onNode(scrollableMatcher).fetchSemanticsNode()
        val axis = list.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val remove = compose.onAllNodesWithTag(settingsTag(WallpaperRowIds.Remove))
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.boundsInRoot
        return "scroll=${axis?.value?.invoke()}/${axis?.maxValue?.invoke()} remove=$remove"
    }

    /** Remove is composed and clear of the bottom chrome (200 px margin). */
    private fun removeInView(): Boolean {
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        return compose.onAllNodesWithTag(settingsTag(WallpaperRowIds.Remove))
            .fetchSemanticsNodes()
            .any { it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= rootBottom - 200f }
    }

    @Test
    fun tappingArchivedPushesTheArchivedFolder() {
        mount()
        scrollToRow(SessionsSettingsRow.Archived.id)
        row(SessionsSettingsRow.Archived.id).performClick()
        compose.waitUntil(5_000) { stack.top.route == FolderRoute.archived() }
        assertEquals(2, stack.entries.size)
        // The pushed folder screen renders (through ShellRouteContent).
        compose.waitForIdle()
    }

    @Test
    fun tappingLicensesPushesTheLicensesScreen() {
        mount()
        scrollToRow(AboutRowIds.Licenses)
        row(AboutRowIds.Licenses).performClick()
        compose.waitUntil(5_000) { stack.top.route === LicensesRoute }
        // 101's hand-off: ShellRouteContent(LicensesRoute) renders the
        // Licenses screen — its large title "Licenses" (a null section
        // list shows the title alone, so the asset state does not matter).
        compose.waitUntil(5_000) { textExists("Licenses") }
        assertEquals(2, stack.entries.size)
    }

    @Test
    fun tappingAnEngineRowPushesTheEngineDetail() {
        // 102's default routing (no explicit handlers): the engine row
        // pushes EngineDetailRoute on the enclosing stack. The real tag
        // is `settings-device:e1` (102 kept iOS's `device:` prefix).
        mount(defaultDeviceRouting = true)
        row(DevicesRowIds.engine("e1")).performClick()
        compose.waitUntil(5_000) { stack.top.route == EngineDetailRoute("e1") }
        // 140's real page composes behind the push: its root is tagged
        // `engine-detail` (the pushed screen reads the app's own engines
        // flow, which has no fixture here — the scaffold alone shows,
        // and the page never crashes on the empty flow).
        compose.waitUntil(5_000) { nodeExists("engine-detail") }
        assertEquals(2, stack.entries.size)
    }

    @Test
    // Tall window: at 874 dp Group by Project straddles the fold (its centre
    // sits below the window), so the switch click landed nowhere.
    @Config(sdk = [36], qualifiers = "w402dp-h3000dp-xxhdpi")
    fun flippingGroupByProjectCallsTheWriterAndTheSwitchFollowsTheFlow() {
        mount()
        switchNode("groupByProject").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), groupChanges)

        // The Sessions ⋯ menu's flip (46) writes the same flow; the switch
        // follows it on the next frame.
        compose.runOnUiThread { groupFlow.value = true }
        compose.waitForIdle()
        switchNode("groupByProject").assertIsOn()
    }

    @Test
    // Tall window: the Background row sits below the 874 dp fold.
    @Config(sdk = [36], qualifiers = "w402dp-h3000dp-xxhdpi")
    fun flippingStayConnectedCallsTheWriterAndTheSwitchFollowsTheFlow() {
        mount()
        switchNode(BackgroundSettingsCopy.StayConnectedId).assertIsOff()
        switchNode(BackgroundSettingsCopy.StayConnectedId).performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), stayChanges)

        compose.runOnUiThread { stayFlow.value = true }
        compose.waitForIdle()
        switchNode(BackgroundSettingsCopy.StayConnectedId).assertIsOn()
    }

    @Test
    @Config(sdk = [34])
    fun masterOnWhileNotDeterminedRequestsOnceAndHoldsTheSwitchOn() {
        fakeStatus = NotificationStatus.NotDetermined
        mount()
        switchNode(NotificationCopy.MasterId).assertIsOff()

        switchNode(NotificationCopy.MasterId).performClick()
        compose.waitForIdle()

        assertEquals("exactly one permission request", 1, permissionRequests)
        assertTrue("enabled persisted before the ask", testPrefs.enabled)
        // pendingOn holds the switch on behind the (fake) system dialog.
        switchNode(NotificationCopy.MasterId).assertIsOn()
    }

    // ---------------------------------------------------------- the reload

    @Test
    // Tall window: the flips add the wallpaper Effect/Remove rows and a second
    // engine row above Group by Project, which would push it (and the
    // notification kind rows) past the 874 dp fold, out of the LazyColumn.
    @Config(sdk = [36], qualifiers = "w402dp-h3000dp-xxhdpi")
    fun sourceFlipsLandOnTheNextFrameWithNoAnimationAndFlipBack() {
        mount(
            notifications = {
                rememberNotificationSectionState(readStatus = { fakeStatus }, openSettings = {})
                    .also { factoryNotifications = it }
            },
        )
        val notif = factoryNotifications
        assertNotNull("the factory state was not captured", notif)
        val singletonPrefs = NotificationPrefs.get(context)
        singletonPrefs.enabled = true
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        // Wallpaper unset → set: the effect and remove rows appear after
        // exactly one frame, and they never animate (the bounds after a
        // long settle equal the bounds after that frame).
        compose.onNodeWithTag(settingsTag(WallpaperRowIds.Effect)).assertDoesNotExist()
        compose.runOnUiThread {
            wallpaperFlow.value = wallpaperFlow.value.copy(set = true, name = "IMG_0412")
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(settingsTag(WallpaperRowIds.Effect)).assertExists()
        val effectTopAfterFrame = boundsTop(WallpaperRowIds.Effect)
        val removeTopAfterFrame = boundsTop(WallpaperRowIds.Remove)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(effectTopAfterFrame, boundsTop(WallpaperRowIds.Effect), 0.01f)
        assertEquals(removeTopAfterFrame, boundsTop(WallpaperRowIds.Remove), 0.01f)

        // A second engine joins the registry flow: its row appears after
        // one frame (pushing the pair row down), with no animation.
        compose.onNodeWithTag(settingsTag(DevicesRowIds.engine("e2"))).assertDoesNotExist()
        compose.runOnUiThread {
            engines.value = engines.value.orEmpty() +
                engineView(id = "e2", name = "Studio", state = EngineState.Reconnecting)
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(settingsTag(DevicesRowIds.engine("e2"))).assertExists()
        val pairTopAfterFrame = boundsTop(DevicesRowIds.Pair)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(pairTopAfterFrame, boundsTop(DevicesRowIds.Pair), 0.01f)

        // Group by Project false → true: the switch turns on on the next
        // frame (the flip itself is instant; only 98's thumb moves).
        compose.runOnUiThread {
            groupFlow.value = true
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        switchNode("groupByProject").assertIsOn()

        // Notifications: prefs enabled = true with the status Granted (the
        // ON_RESUME re-read's write) — the three kind rows appear after
        // one frame, with no animation.
        compose.onNodeWithTag(settingsTag(NotificationCopy.kindId(NotificationKind.Done))).assertDoesNotExist()
        compose.runOnUiThread {
            fakeStatus = NotificationStatus.Granted
            notif!!.refreshStatus()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(settingsTag(NotificationCopy.kindId(NotificationKind.Done))).assertExists()
        val doneTopAfterFrame = boundsTop(NotificationCopy.kindId(NotificationKind.Done))
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(doneTopAfterFrame, boundsTop(NotificationCopy.kindId(NotificationKind.Done)), 0.01f)

        // Flipping back removes them the same way: one frame, no animation.
        compose.runOnUiThread {
            wallpaperFlow.value = wallpaperFlow.value.copy(set = false, name = null)
            engines.value = engines.value.orEmpty().dropLast(1)
            groupFlow.value = false
            fakeStatus = NotificationStatus.NotDetermined
            notif!!.refreshStatus()
            singletonPrefs.enabled = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(settingsTag(WallpaperRowIds.Effect)).assertDoesNotExist()
        compose.onNodeWithTag(settingsTag(WallpaperRowIds.Remove)).assertDoesNotExist()
        compose.onNodeWithTag(settingsTag(DevicesRowIds.engine("e2"))).assertDoesNotExist()
        compose.onNodeWithTag(settingsTag(NotificationCopy.kindId(NotificationKind.Done))).assertDoesNotExist()
        switchNode("groupByProject").assertIsOff()
    }

    // ---------------------------------------------------- the ON_RESUME

    @Test
    @Config(sdk = [34])
    fun onResumeRereadsTheNotificationStatusWithoutLeavingSettings() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationPrefs.get(context).enabled = true

        // The real factory: the status is the system's, the prefs are the
        // process singleton's, and the ON_RESUME effect is the screen's.
        mount(notifications = { rememberNotificationSectionState() })

        // Denied: the master reads off and the kind rows are absent.
        switchNode(NotificationCopy.MasterId).assertIsOff()
        compose.onNodeWithTag(settingsTag(NotificationCopy.kindId(NotificationKind.Done))).assertDoesNotExist()

        // Allowed in Android's system settings while the app is in the
        // background (or behind the permission dialog).
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // STARTED → RESUMED: the screen's one ON_RESUME effect re-reads
        // the status; the rows update in place.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        switchNode(NotificationCopy.MasterId).assertIsOn()
        for (kind in NotificationKind.entries) {
            compose.onNodeWithTag(settingsTag(NotificationCopy.kindId(kind))).assertExists()
        }
        // Still on the Settings screen: nothing was popped or switched.
        compose.onNodeWithTag("settings-screen").assertExists()
    }

    // ------------------------------------------------- the bottom inset

    @Test
    fun theLastRowStops166AboveTheWindowBottomUnderTheChrome() {
        val listState = LazyListState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides owner,
                    LocalBottomChrome provides BottomChromeInsets(24.dp, 150.dp),
                ) {
                    ActionSheetHost { SettingsScreenGallerySection(listState = listState) }
                }
            }
        }
        compose.waitForIdle()
        scrollToEnd(listState)
        // 874 − (150 + 16) = 708.
        assertEquals(708f, boundsBottom(AboutRowIds.Licenses), 1f)
    }

    @Test
    fun theLastRowStops16AboveTheWindowBottomWithoutChrome() {
        val listState = LazyListState()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ActionSheetHost { SettingsScreenGallerySection(listState = listState) }
                }
            }
        }
        compose.waitForIdle()
        scrollToEnd(listState)
        // LocalBottomChrome defaults to None (the tablet dialog, gallery,
        // previews): 0 + 16 → 874 − 16 = 858.
        assertEquals(858f, boundsBottom(AboutRowIds.Licenses), 1f)
    }

    private fun scrollToEnd(listState: LazyListState) {
        val px = with(compose.density) { 20_000.dp.toPx() }
        compose.runOnUiThread { runBlocking { listState.scrollBy(px) } }
        compose.waitForIdle()
    }

    // ------------------------------------------------------ the tab host

    @Test
    fun theSettingsTabShowsTheScreenAndATabSwitchBackRereadsTheStatus() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationPrefs.get(context).enabled = true
        val shellState = MainTabState(TabId.Sessions)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        GlassMenuHost { MainTabShell(state = shellState) }
                    }
                }
            }
        }
        compose.waitForIdle()

        // `-route more` / showSettings() land here: the tab is selected and
        // the tab root is the real screen under its wrapper tag.
        compose.runOnUiThread { shellState.select(TabId.Settings) }
        compose.waitUntil(5_000) { textExists("Settings") }
        compose.onNodeWithTag("tab-root-more").assertExists()
        compose.onNodeWithTag("settings-screen").assertExists()
        switchNode(NotificationCopy.MasterId).assertIsOff()

        // Allowed while the Settings tab is composed (or away — either
        // way the fresh composition reads the status and the ON_RESUME
        // catch-up re-reads it; 26 disposes the unselected tab's host).
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.runOnUiThread { shellState.select(TabId.Sessions) }
        compose.waitForIdle()
        compose.runOnUiThread { shellState.select(TabId.Settings) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(settingsTag(NotificationCopy.kindId(NotificationKind.Done)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        switchNode(NotificationCopy.MasterId).assertIsOn()
        compose.onNodeWithTag("tab-root-more").assertExists()
    }
}

package sh.roboco.android.settings

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.AlertHost
import sh.roboco.android.design.AlertHostState
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.settingsTag
import sh.roboco.android.design.toggleTag
import sh.roboco.android.notify.NotificationKind
import sh.roboco.android.notify.NotificationPermission
import sh.roboco.android.notify.NotificationPrefs
import sh.roboco.android.notify.NotificationStatus

/**
 * The Notifications section (android-ui-parity #99) through the real
 * [notificationRows] / [NotificationSectionState] /
 * [rememberNotificationSectionState] + [notificationSection]:
 *
 *  - the pure copy table of [notificationRows] (ids, titles, subtitles,
 *    icons, order, the master's `notificationsOn || pendingOn`);
 *  - the controller state machine with a Robolectric-backed
 *    [NotificationPrefs], a `readStatus` lambda over a mutable fake, a
 *    recording [Alerts] fake and a counting `requestPermission`
 *    (master off / on per status, the denied alert's handlers, the
 *    persist-before-ask order, the double-tap guard, the result paths,
 *    `refreshStatus` standing in for 103's `ON_RESUME`);
 *  - the Compose behaviour at `@Config(sdk = [34])`, wrapped in
 *    [AlertHost] with `LocalActivityResultRegistryOwner` overridden by a
 *    recording [ActivityResultRegistry] (the ticket's setup): the
 *    permission request and its results, the denied alert (buttons, back,
 *    scrim), the system-settings intent of "Open Settings", kind
 *    switches, external pref writes, and the no-write rule of
 *    `refreshStatus`;
 *  - ticket 70/98 geometry at this section's call sites on the ticket's
 *    `w402dp` phone at xxhdpi (1 dp = 3 px), font scale 1: the 84 dp
 *    master row (the subtitle wraps), the 68 dp denied-subtitle row, the
 *    52 dp kind rows, the icon box at x 32…56, the switch at x 307…370,
 *    the separators under the first three rows, and the switch track's
 *    colours in light.
 *
 * Written per the test policy; the batch/VPS run executes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h891dp-xxhdpi")
class NotificationSettingsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ------------------------------------------------------------ fixtures

    private val context: Context
        get() = compose.activity

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    /** The prefs the composition uses: the process singleton on the app context. */
    private fun prefs(): NotificationPrefs = NotificationPrefs.get(context)

    /** A prefs over a private test file, for the state-machine tests. */
    private fun newPrefs(file: String) =
        NotificationPrefs(context.getSharedPreferences(file, Context.MODE_PRIVATE))

    private val alerts = AlertHostState()

    /** The mutable fake behind the section's `readStatus` seam. */
    private var fakeStatus: NotificationStatus = NotificationStatus.NotDetermined

    private val registry = RecordingRegistry()
    private val owner = RegistryOwner(registry)

    /**
     * `onLaunch` records the input and the request code; the test
     * dispatches results back — the ticket's fake system dialog.
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

    private class RegistryOwner(private val registry: ActivityResultRegistry) : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    /** The state-machine tests' alert fake: records every shown alert. */
    private class RecordingAlerts : Alerts {
        val shown = mutableListOf<RobocoAlert>()
        var dismissAlls = 0

        override fun show(alert: RobocoAlert) {
            shown += alert
        }

        override fun updateCurrent(alert: RobocoAlert) {}

        override fun dismissAll() {
            dismissAlls++
        }
    }

    // ------------------------------------------------------------- mounting

    /**
     * Ticket 103's shape: the state at screen level (outside the
     * `LazyColumn`, so the launcher's registration survives scrolling),
     * the rows inside it, on the page background, inside a real
     * [AlertHost] so the denied alert presents. [readStatus] null = the
     * real [NotificationPermission.status] (driven by Robolectric
     * shadows); [openSettings] null = the real system-settings intent.
     * A second call in one test replaces the composition (and disposes
     * the previous launcher registration).
     */
    private fun mount(
        readStatus: (() -> NotificationStatus)? = null,
        openSettings: (() -> Unit)? = null,
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AlertHost(alerts) {
                        val state = rememberNotificationSectionState(
                            readStatus = readStatus,
                            openSettings = openSettings,
                        )
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .background(RobocoTheme.palette.background),
                        ) {
                            notificationSection(state)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A mount whose status comes from the fake. */
    private fun mountFake(status: NotificationStatus, openSettings: (() -> Unit)? = null) {
        fakeStatus = status
        mount(readStatus = { fakeStatus }, openSettings = openSettings)
    }

    private fun switchNode(id: String) = compose.onNodeWithTag(toggleTag(id), useUnmergedTree = true)

    private fun row(tag: String) = compose.onNodeWithTag(tag)

    private fun clickMaster() {
        switchNode(NotificationCopy.MasterId).performClick()
        compose.waitForIdle()
    }

    /** Dispatches a request result for the last launch, then idles. */
    private fun respond(granted: Boolean) {
        registry.dispatchResult(registry.requestCodes.last(), granted)
        compose.waitForIdle()
    }

    // ------------------------------------------------- the rows (pure table)

    private val allOn = NotificationPrefs.Snapshot(enabled = true, done = true, input = true, failed = true)

    @Test
    fun rowsWhileOffShowOnlyTheMasterWithTheFreshSubtitle() {
        val rows = notificationRows(allOn, NotificationStatus.NotDetermined, pendingOn = false)
        assertEquals(1, rows.size)
        val master = rows.single()
        assertEquals("notify:enabled", master.id)
        assertEquals("Notifications", master.title)
        assertEquals("When a session finishes, needs you or fails", master.subtitle)
        assertEquals(RobocoIcons.Bell, master.icon)
        assertFalse("the master shows enabled && Granted, never enabled alone", master.checked)
        assertEquals(null, master.kind)
    }

    @Test
    fun rowsWhileDeniedUseTheDeniedSubtitleWhateverEnabledIs() {
        for (enabled in booleanArrayOf(false, true)) {
            val snapshot = allOn.copy(enabled = enabled)
            val rows = notificationRows(snapshot, NotificationStatus.Denied, pendingOn = false)
            assertEquals(1, rows.size)
            assertEquals("Turned off in Android settings", rows.single().subtitle)
            assertFalse(rows.single().checked)
        }
    }

    @Test
    fun rowsWhileOnListMasterDoneInputFailedInOrder() {
        val snapshot = NotificationPrefs.Snapshot(enabled = true, done = true, input = false, failed = true)
        val rows = notificationRows(snapshot, NotificationStatus.Granted, pendingOn = false)
        assertEquals(4, rows.size)
        assertEquals(
            listOf("notify:enabled", "notify:done", "notify:input", "notify:failed"),
            rows.map { it.id },
        )
        assertEquals(
            listOf("Notifications", "Run finished", "Waiting on your input", "Run failed"),
            rows.map { it.title },
        )
        // The kind rows carry no subtitle, `Kind.allCases` order, their own
        // switch value, and the ticket 06 icons.
        // Three kind rows after drop(1), so three nulls.
        assertEquals(listOf(null, null, null), rows.drop(1).map { it.subtitle })
        assertEquals(listOf(true, false, true), rows.drop(1).map { it.checked })
        assertEquals(
            listOf(RobocoIcons.CheckmarkCircle, RobocoIcons.QuestionmarkBubble, RobocoIcons.ExclamationmarkTriangle),
            rows.drop(1).map { it.icon },
        )
        assertEquals(NotificationKind.entries, rows.drop(1).map { it.kind })
    }

    @Test
    fun pendingOnDrawsTheMasterOnButKeepsTheKindsHidden() {
        // NotDetermined + a request in flight: the switch is drawn on
        // behind the dialog, the rows are still master-only.
        val rows = notificationRows(allOn, NotificationStatus.NotDetermined, pendingOn = true)
        assertEquals(1, rows.size)
        assertTrue("pendingOn holds the master on", rows.single().checked)
        // Granted already on: checked stays true (trivially).
        assertTrue(notificationRows(allOn, NotificationStatus.Granted, pendingOn = true).first().checked)
    }

    // ---------------------------------------------- the state machine (fake)

    /**
     * A [NotificationSectionState] over a private prefs file, a counting
     * `readStatus` fake, a recording alert fake and a counting request.
     */
    private class Harness(prefs: NotificationPrefs, status: NotificationStatus) {
        var fakeStatus: NotificationStatus = status
        var statusReads: Int = 0
        var requests: Int = 0
        var openCalls: Int = 0
        val alerts = RecordingAlerts()
        val state = NotificationSectionState(
            prefs = prefs,
            readStatus = { statusReads++; fakeStatus },
            alerts = alerts,
            openSettings = { openCalls++ },
        )

        init {
            state.requestPermission = { requests++ }
        }
    }

    private fun harness(
        status: NotificationStatus,
        enabled: Boolean = true,
        file: String = "state-test",
    ): Harness {
        val prefs = newPrefs(file)
        prefs.enabled = enabled
        return Harness(prefs, status)
    }

    @Test
    fun masterOffDisablesThePrefAndDropsTheKindsAtOnce() {
        val h = harness(NotificationStatus.Granted)
        assertEquals(4, h.state.rows.size)
        h.state.onMasterToggled(false)
        assertFalse(h.state.prefs.enabled)
        assertEquals(1, h.state.rows.size)
        assertFalse(h.state.pendingOn)
        assertEquals("master off never asks", 0, h.alerts.shown.size)
        assertEquals(0, h.requests)
    }

    @Test
    fun masterOnWhileDeniedShowsTheAlertAndKeepsThePref() {
        val h = harness(NotificationStatus.Denied)
        h.state.onMasterToggled(true)
        assertEquals("exactly one alert", 1, h.alerts.shown.size)
        val alert = h.alerts.shown.single()
        assertEquals("Notifications are off", alert.title)
        assertEquals("Allow notifications for Roboco in Android settings.", alert.message)
        assertEquals(null, alert.field)
        val (notNow, openSettings) = alert.buttons
        assertEquals("Not Now", notNow.title)
        assertEquals(AlertButtonRole.Cancel, notNow.role)
        assertEquals("Open Settings", openSettings.title)
        assertEquals(AlertButtonRole.Default, openSettings.role)
        assertTrue("the pref is not changed while Denied", h.state.prefs.enabled)
        assertTrue("pendingOn holds the switch on behind the alert", h.state.pendingOn)
        assertTrue("the master row is drawn on", h.state.rows.single().checked)
        assertEquals("no request while Denied", 0, h.requests)
    }

    @Test
    fun alertNotNowSlidesTheSwitchBackOff() {
        val h = harness(NotificationStatus.Denied)
        h.state.onMasterToggled(true)
        h.alerts.shown.single().buttons[0].onClick("")
        assertFalse(h.state.pendingOn)
        assertFalse(h.state.rows.single().checked)
        assertTrue(h.state.prefs.enabled)
        assertTrue("the handler re-reads the status", h.statusReads >= 2)
    }

    @Test
    fun alertOpenSettingsOpensThenSlidesBack() {
        val h = harness(NotificationStatus.Denied)
        h.state.onMasterToggled(true)
        h.alerts.shown.single().buttons[1].onClick("")
        assertEquals("Open Settings runs before the slide-back", 1, h.openCalls)
        assertFalse(h.state.pendingOn)
        assertFalse(h.state.rows.single().checked)
        assertTrue(h.state.prefs.enabled)
    }

    @Test
    fun masterOnWhileGrantedEnablesAndShowsTheKindsWithoutARequest() {
        val h = harness(NotificationStatus.Granted, enabled = false)
        h.state.onMasterToggled(true)
        assertTrue(h.state.prefs.enabled)
        assertEquals(4, h.state.rows.size)
        assertEquals(0, h.requests)
        assertEquals(0, h.alerts.shown.size)
        assertFalse("nothing is pending while Granted", h.state.pendingOn)
    }

    @Test
    fun masterOnWhileNotDeterminedPersistsFirstThenRequestsOnce() {
        val h = harness(NotificationStatus.NotDetermined)
        h.state.onMasterToggled(true)
        assertTrue("enabled is persisted BEFORE the ask", h.state.prefs.enabled)
        assertTrue(h.state.pendingOn)
        assertEquals(1, h.requests)
        // The fast double tap: the guard swallows the second on-toggle.
        h.state.onMasterToggled(true)
        assertEquals(1, h.requests)
        assertTrue(h.state.pendingOn)
    }

    @Test
    fun aGrantedResultTurnsTheMasterOnAndFreesTheGuard() {
        val h = harness(NotificationStatus.NotDetermined)
        h.state.onMasterToggled(true)
        h.fakeStatus = NotificationStatus.Granted
        h.state.onPermissionResult(true)
        assertFalse(h.state.pendingOn)
        assertEquals(NotificationStatus.Granted, h.state.status)
        // Granted: the master plus the three kinds — the master is first.
        assertTrue(h.state.rows.first().checked)
        assertEquals(4, h.state.rows.size)
        assertTrue(h.state.prefs.enabled)
        // The guard is free again: off then on requests a second time. Only
        // a NotDetermined status requests (on while Granted just enables, no
        // ask), so the permission reads NotDetermined again first (e.g. it
        // was reset in system settings) — otherwise the second on-toggle
        // takes the Granted path and the count stays at 1 whatever the guard.
        h.fakeStatus = NotificationStatus.NotDetermined
        h.state.onMasterToggled(false)
        h.state.onMasterToggled(true)
        assertEquals(2, h.requests)
    }

    @Test
    fun aDeniedResultSlidesBackWithTheSubtitleAndPrefUntouched() {
        val h = harness(NotificationStatus.NotDetermined)
        h.state.onMasterToggled(true)
        // The first denial (Android 11+ never makes it final): the status
        // stays NotDetermined.
        h.state.onPermissionResult(false)
        assertFalse(h.state.pendingOn)
        assertFalse(h.state.rows.single().checked)
        assertTrue("a denial leaves enabled true", h.state.prefs.enabled)
        assertEquals(
            "the subtitle is still the fresh copy",
            NotificationCopy.MasterSubtitle,
            h.state.rows.single().subtitle,
        )
        assertEquals(1, h.state.rows.size)
    }

    @Test
    fun aSecondDeniedResultMakesTheStatusDeniedAndTheSubtitleToo() {
        val h = harness(NotificationStatus.NotDetermined)
        h.state.onMasterToggled(true)
        h.state.onPermissionResult(false)
        // The second ask: request, deny again, and the recorded final
        // denial makes the status read Denied.
        h.state.onMasterToggled(true)
        assertEquals(2, h.requests)
        h.fakeStatus = NotificationStatus.Denied
        h.state.onPermissionResult(false)
        assertEquals(NotificationStatus.Denied, h.state.status)
        assertEquals(NotificationCopy.MasterSubtitleDenied, h.state.rows.single().subtitle)
        assertFalse(h.state.rows.single().checked)
        assertTrue(h.state.prefs.enabled)
    }

    @Test
    fun kindSwitchesWriteOnlyTheirOwnPref() {
        val h = harness(NotificationStatus.Granted)
        val doneRow = h.state.rows.first { it.kind == NotificationKind.Done }
        h.state.onToggle(doneRow, false)
        assertFalse(h.state.prefs.isOn(NotificationKind.Done))
        assertTrue(h.state.prefs.isOn(NotificationKind.Input))
        assertTrue(h.state.prefs.isOn(NotificationKind.Failed))
        assertTrue(h.state.prefs.enabled)
        assertEquals("no row change: still four rows", 4, h.state.rows.size)
        assertFalse(h.state.rows.first { it.kind == NotificationKind.Done }.checked)
        assertTrue(h.state.rows.first { it.kind == NotificationKind.Input }.checked)
        assertEquals(0, h.requests)
        assertEquals(0, h.alerts.shown.size)
        assertFalse(h.state.pendingOn)
    }

    @Test
    fun onToggleDispatchesTheMasterThroughTheMachine() {
        val h = harness(NotificationStatus.NotDetermined)
        val master = h.state.rows.single()
        assertEquals(null, master.kind)
        h.state.onToggle(master, true)
        assertEquals(1, h.requests)
        h.state.onToggle(master, false)
        assertFalse(h.state.prefs.enabled)
        assertEquals(0, h.alerts.shown.size)
    }

    @Test
    fun refreshStatusStandsInForOnResumeAndWritesOnlyWhenChanged() {
        val h = harness(NotificationStatus.NotDetermined)
        assertEquals(NotificationStatus.NotDetermined, h.state.status)
        val readsAfterCreation = h.statusReads

        // Unchanged: re-read, no write.
        h.state.refreshStatus()
        assertEquals(readsAfterCreation + 1, h.statusReads)
        assertEquals(NotificationStatus.NotDetermined, h.state.status)
        assertEquals(1, h.state.rows.size)

        // Newly allowed in system settings (the ON_RESUME case): the
        // master goes on (enabled is true) and the kinds appear.
        h.fakeStatus = NotificationStatus.Granted
        h.state.refreshStatus()
        assertEquals(NotificationStatus.Granted, h.state.status)
        // Four rows now — the master is the first.
        assertTrue(h.state.rows.first().checked)
        assertEquals(4, h.state.rows.size)

        // Newly blocked: master off, kinds gone, denied subtitle.
        h.fakeStatus = NotificationStatus.Denied
        h.state.refreshStatus()
        assertEquals(1, h.state.rows.size)
        assertEquals(NotificationCopy.MasterSubtitleDenied, h.state.rows.single().subtitle)
        assertFalse(h.state.rows.single().checked)
    }

    // --------------------------------------------------- the lazy keys

    /**
     * `LazyListScope` recorder, the `GroupedListTest` method: the builder
     * only stores the item lambdas, so the key/content-type surface is
     * testable without a layout.
     */
    private class RecordingLazyListScope : LazyListScope {
        val calls = mutableListOf<String>()

        override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
            calls += "$key|$contentType"
        }

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            for (i in 0 until count) {
                calls += "${key?.invoke(i)}|${contentType?.invoke(i)}"
            }
        }
    }

    @Test
    fun theSectionEmitsTheTicketKeysAndContentTypes() {
        val h = harness(NotificationStatus.Granted, file = "keys-test")
        val scope = RecordingLazyListScope()
        scope.notificationSection(h.state)
        assertEquals(
            listOf(
                "grouped:notifications:header|grouped-header",
                "grouped:notifications:row:notify:enabled|grouped-row",
                "grouped:notifications:row:notify:done|grouped-row",
                "grouped:notifications:row:notify:input|grouped-row",
                "grouped:notifications:row:notify:failed|grouped-row",
            ),
            scope.calls,
        )
        // The stateless emitter honours its key (the gallery's fixtures).
        val fixedScope = RecordingLazyListScope()
        fixedScope.notificationRowsSection(
            rows = notificationRows(allOn, NotificationStatus.NotDetermined, pendingOn = false),
            onToggle = { _, _ -> },
            key = "n-off",
        )
        assertEquals(
            listOf(
                "grouped:n-off:header|grouped-header",
                "grouped:n-off:row:notify:enabled|grouped-row",
            ),
            fixedScope.calls,
        )
    }

    @Test
    fun theKeysNeverCarryTheCheckedValue() {
        val h = harness(NotificationStatus.NotDetermined, file = "keys-pending")
        fun recorded(): List<String> {
            val scope = RecordingLazyListScope()
            scope.notificationSection(h.state)
            return scope.calls
        }
        val before = recorded()
        h.state.onMasterToggled(true) // pendingOn flips the master's drawn value
        val after = recorded()
        assertEquals("a pending flip keeps every key (98's thumb animation always completes)", before, after)
    }

    // --------------------------------------------- Compose: the permission

    @Test
    fun aFreshInstallShowsTheMasterOffOnTheRealStatus() {
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        mount()
        switchNode(NotificationCopy.MasterId).assertIsOff()
        row(settingsTag("notify:done")).assertDoesNotExist()
        val text = rowText(settingsTag(NotificationCopy.MasterId))
        assertTrue("the fresh copy, not the denied copy: '$text'", text.contains("When a session finishes"))
        assertFalse(text.contains("Turned off in Android settings"))
    }

    @Test
    fun masterOnWhileNotDeterminedRequestsOnceAndAGrantShowsTheKinds() {
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs().enabled = false
        mount()

        switchNode(NotificationCopy.MasterId).assertIsOff()
        // Two taps in one go: the in-flight guard keeps one request. Both
        // taps must land in the same frame: two performClick()s idle (and
        // recompose) in between, so the second tap would see the switch
        // drawn on (pendingOn) and request OFF — enabled = false, the
        // round-30 failure. Invoking the switch's click action twice in one
        // main-thread turn is the real double tap: `latestChecked` is still
        // false for both, so both request ON and the guard swallows one.
        val tap = switchNode(NotificationCopy.MasterId).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            tap()
            tap()
        }
        compose.waitForIdle()

        assertEquals("exactly one POST_NOTIFICATIONS request", 1, registry.requestCodes.size)
        assertEquals(NotificationPermission.PERMISSION, registry.inputs.single())
        assertTrue("enabled persisted before the ask", prefs().enabled)
        switchNode(NotificationCopy.MasterId).assertIsOn()

        // The system dialog answered Allow: the permission grant + the
        // result callback (recordRequestResult ran inside it).
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        respond(granted = true)

        assertTrue(prefs().permissionRequested)
        assertFalse(prefs().permissionBlocked)
        switchNode(NotificationCopy.MasterId).assertIsOn()
        for (kind in NotificationKind.entries) {
            switchNode(NotificationCopy.kindId(kind)).assertIsOn()
        }
        row(settingsTag("notify:failed")).assertExists()
    }

    @Test
    fun aDeniedResultSlidesTheSwitchBackAndASecondMakesItDenied() {
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs().enabled = false
        mount()

        clickMaster()
        assertEquals(1, registry.requestCodes.size)
        respond(granted = false)
        switchNode(NotificationCopy.MasterId).assertIsOff()
        assertTrue("a denial leaves enabled true", prefs().enabled)
        assertFalse(prefs().permissionBlocked)
        var text = rowText(settingsTag(NotificationCopy.MasterId))
        assertTrue("the first denial keeps the fresh subtitle: '$text'", text.contains("When a session finishes"))

        // The second denial (requestedBefore now true, no rationale): the
        // recorded final denial makes the status Denied.
        clickMaster()
        assertEquals(2, registry.requestCodes.size)
        respond(granted = false)
        assertTrue(prefs().permissionBlocked)
        text = rowText(settingsTag(NotificationCopy.MasterId))
        assertTrue("the final denial switches the copy: '$text'", text.contains("Turned off in Android settings"))
        switchNode(NotificationCopy.MasterId).assertIsOff()
        row(settingsTag("notify:done")).assertDoesNotExist()
    }

    @Test
    fun masterOnWhileGrantedShowsTheKindsWithNoRequest() {
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs().enabled = false
        mount()
        switchNode(NotificationCopy.MasterId).assertIsOff()

        clickMaster()

        assertTrue(prefs().enabled)
        assertEquals("no request while Granted", 0, registry.requestCodes.size)
        for (kind in NotificationKind.entries) {
            switchNode(NotificationCopy.kindId(kind)).assertIsOn()
        }
    }

    // ---------------------------------------------- Compose: the alert path

    @Test
    fun masterOnWhileDeniedPresentsTheAlertAndTheSwitchStaysOn() {
        mountFake(NotificationStatus.Denied)

        clickMaster()

        compose.onNodeWithTag("alert").assertExists()
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("Allow notifications for Roboco in Android settings.").assertExists()
        // Stacked (decision, round 31: the stacking rule mirrors iOS): on the
        // 320 dp card "Open Settings" is wider than the 108 dp side-by-side
        // label limit (AlertRules.sideBySideLabelMaxDp), so the buttons stack
        // with the cancel action last — Open Settings above Not Now.
        val notNow = compose.onNodeWithText("Not Now").getUnclippedBoundsInRoot()
        val open = compose.onNodeWithText("Open Settings").getUnclippedBoundsInRoot()
        assertTrue("Open Settings stacks above Not Now: $open vs $notNow", open.bottom <= notNow.top)
        assertTrue("the pref is unchanged while Denied", prefs().enabled)
        switchNode(NotificationCopy.MasterId).assertIsOn()
        assertEquals("no request while Denied", 0, registry.requestCodes.size)
    }

    @Test
    fun alertNotNowSlidesTheSwitchBackOffOnScreen() {
        mountFake(NotificationStatus.Denied)
        clickMaster()

        compose.onNodeWithText("Not Now").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("alert").assertDoesNotExist()
        switchNode(NotificationCopy.MasterId).assertIsOff()
        assertTrue(prefs().enabled)
        assertEquals(0, registry.requestCodes.size)
    }

    @Test
    fun alertBackRunsTheCancelActionAndSlidesTheSwitchBack() {
        mountFake(NotificationStatus.Denied)
        clickMaster()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithTag("alert").assertDoesNotExist()
        switchNode(NotificationCopy.MasterId).assertIsOff()
    }

    @Test
    fun alertScrimTapRunsTheCancelActionAndSlidesTheSwitchBack() {
        mountFake(NotificationStatus.Denied)
        clickMaster()

        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.waitForIdle()

        compose.onNodeWithTag("alert").assertDoesNotExist()
        switchNode(NotificationCopy.MasterId).assertIsOff()
    }

    @Test
    fun alertOpenSettingsFiresTheAppNotificationSettingsIntentThenSlidesBack() {
        // openSettings stays null: the production intent, captured by
        // Robolectric's nextStartedActivity.
        mountFake(NotificationStatus.Denied)
        clickMaster()

        compose.onNodeWithText("Open Settings").performClick()
        compose.waitForIdle()

        val started: Intent? = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started?.action)
        assertEquals("sh.roboco.android", started?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(context.packageName, started?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        compose.onNodeWithTag("alert").assertDoesNotExist()
        switchNode(NotificationCopy.MasterId).assertIsOff()
        assertTrue(prefs().enabled)
    }

    // ------------------------------------------- Compose: the kind switches

    @Test
    fun kindSwitchesFlipOnlyTheirOwnRow() {
        mountFake(NotificationStatus.Granted)
        assertEquals(0, registry.requestCodes.size)

        switchNode("notify:done").performClick()
        compose.waitForIdle()

        assertFalse(prefs().isOn(NotificationKind.Done))
        switchNode("notify:done").assertIsOff()
        switchNode("notify:input").assertIsOn()
        switchNode("notify:failed").assertIsOn()
        switchNode(NotificationCopy.MasterId).assertIsOn()
        assertTrue(prefs().isOn(NotificationKind.Input))
        assertTrue(prefs().enabled)
        assertEquals("no request, no alert on a kind flip", 0, registry.requestCodes.size)
        compose.onNodeWithTag("alert").assertDoesNotExist()
        for (id in listOf(NotificationCopy.MasterId) + NotificationKind.entries.map { NotificationCopy.kindId(it) }) {
            row(settingsTag(id)).assertExists()
        }
    }

    @Test
    fun anExternalPrefWriteMovesTheSwitchOnTheNextFrame() {
        mountFake(NotificationStatus.Granted)
        switchNode("notify:done").assertIsOn()

        // android-app#28 writing from outside the section.
        compose.runOnUiThread { prefs().set(NotificationKind.Done, false) }
        compose.waitForIdle()

        switchNode("notify:done").assertIsOff()
        assertFalse(prefs().isOn(NotificationKind.Done))
    }

    // ------------------------------------------------ Compose: refresh

    @Test
    fun refreshStatusOnResumeWritesOnlyWhenTheStatusChanged() {
        var stateRef: NotificationSectionState? = null
        var probes = 0
        fakeStatus = NotificationStatus.NotDetermined
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AlertHost(alerts) {
                        val state = rememberNotificationSectionState(
                            readStatus = { fakeStatus },
                            openSettings = {},
                        )
                        stateRef = state
                        // The tracked read: this scope recomposes only when
                        // `status` is written.
                        val observed = state.status
                        probes++
                        check(observed == fakeStatus)
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .background(RobocoTheme.palette.background),
                        ) {
                            notificationSection(state)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, probes)
        val state = stateRef!!

        // 103's ON_RESUME with an unchanged status: a read, no write.
        compose.runOnUiThread { state.refreshStatus() }
        compose.waitForIdle()
        assertEquals("an unchanged status writes no state", 1, probes)

        // Newly allowed in system settings: exactly one recomposition.
        fakeStatus = NotificationStatus.Granted
        compose.runOnUiThread { state.refreshStatus() }
        compose.waitForIdle()
        assertEquals(2, probes)
        row(settingsTag("notify:done")).assertExists()
    }

    // ---------------------------------------------------------- geometry

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun rowImage(tag: String): PixelMap = row(tag).captureToImage().toPixelMap()

    private fun switchImage(id: String): PixelMap = switchNode(id).captureToImage().toPixelMap()

    @Test
    fun theSectionGeometryMatchesTheTicket() {
        mountFake(NotificationStatus.Granted)

        val master = row(settingsTag(NotificationCopy.MasterId)).getUnclippedBoundsInRoot()
        assertDp(master.left, 16.dp, "card left")
        assertDp(master.right, 386.dp, "card right")
        // 15 + 20.8 + 2 × 16.9 + 15: the subtitle wraps after "needs you".
        assertDp(heightOf(master), 84.dp, "master row height (title + 2 subtitle lines)")
        assertDp(
            compose.onNodeWithText("Run finished", useUnmergedTree = true).getUnclippedBoundsInRoot().left,
            72.dp,
            "kind title left (the text column at 56 inside the card)",
        )
        for (kind in NotificationKind.entries) {
            assertDp(
                heightOf(row(settingsTag(NotificationCopy.kindId(kind))).getUnclippedBoundsInRoot()),
                52.dp,
                "${kind.raw} row height (title only, the minimum)",
            )
        }
        // The header block: 27 + 20.8 + 10 = 57.8, the card's top edge.
        assertDp(master.top, 57.8.dp, "the header block above the card")

        // The switch: 63 × 28, trailing edge 16 dp inside the card,
        // vertically centred on the master row.
        val switch = switchNode(NotificationCopy.MasterId).getUnclippedBoundsInRoot()
        assertDp(widthOf(switch), 63.dp, "switch width", 0.5f)
        assertDp(heightOf(switch), 28.dp, "switch height", 0.5f)
        assertDp(switch.right, 370.dp, "switch trailing edge")
        val rowCentre = (master.top.value + master.bottom.value) / 2f
        val switchCentre = (switch.top.value + switch.bottom.value) / 2f
        assertTrue(
            "switch centre $switchCentre should sit on the row centre $rowCentre (±1 dp)",
            abs(rowCentre - switchCentre) <= 1f,
        )
    }

    @Test
    fun theDeniedSubtitleRowIsOneLineAndTheIconBoxSpansX32To56() {
        mountFake(NotificationStatus.Denied)

        val master = row(settingsTag(NotificationCopy.MasterId)).getUnclippedBoundsInRoot()
        assertDp(
            heightOf(master),
            68.dp,
            "denied master row height (one-line subtitle: 15 + 20.8 + 16.9 + 15)",
        )

        // The icon box spans 16…40 dp into the card (image x 48…120 =
        // screen x 32…56), centred on the row; the flanks stay clean.
        val px = rowImage(settingsTag(NotificationCopy.MasterId))
        val midY = px.height / 2
        var icon = 0
        for (y in (midY - 45)..(midY + 45)) {
            for (x in 48..120) {
                if (matches(px[x, y], 98, 98, 106, 8)) icon++
            }
        }
        assertTrue("the bell icon samples secondary (#62626A)", icon > 4)
        for (x in 0..45) {
            for (y in (midY - 45)..(midY + 45)) {
                assertTrue("stray ink at ($x, $y) before the icon box", px[x, y].red > 0.6f)
            }
        }
        for (x in 128..165) {
            for (y in (midY - 45)..(midY + 45)) {
                assertTrue("stray ink at ($x, $y) between the icon box and the title", px[x, y].red > 0.6f)
            }
        }
    }

    @Test
    fun separatorsRunUnderTheFirstThreeRowsOnly() {
        mountFake(NotificationStatus.Granted)

        val ids = listOf(NotificationCopy.MasterId) + NotificationKind.entries.map { NotificationCopy.kindId(it) }
        for ((index, id) in ids.withIndex()) {
            val px = rowImage(settingsTag(id))
            if (index < 3) {
                assertTrue("separator under ${ids[index]}", px.hasSeparator(px.height - 1))
            } else {
                assertFalse("no separator under the last row", px.hasSeparator(px.height - 1))
            }
        }
    }

    @Test
    fun theSwitchTracksSampleTheTicketColours() {
        mountFake(NotificationStatus.Granted)
        val on = switchImage(NotificationCopy.MasterId)
        // Accent #5B43E8 at the far (OFF) end of the track (8, 14) dp;
        // the white thumb at the trailing end, centre (42.5, 14) dp.
        assertRgb(on, 24, 42, 91, 67, 232, 3, "ON track at (8, 14) dp")
        assertRgb(on, 127, 42, 255, 255, 255, 2, "ON thumb centre")

        // One setContent per test: turn the master off through the store
        // (the section's collector follows it) instead of a second mount.
        // enabled = false reads as off exactly like NotDetermined does.
        compose.runOnUiThread { prefs().enabled = false }
        compose.waitForIdle()
        switchNode(NotificationCopy.MasterId).assertIsOff()
        val off = switchImage(NotificationCopy.MasterId)
        // The system switch grey #C6C6CA (0x787880 @ 0.42 on white).
        assertRgb(off, 165, 42, 198, 198, 202, 3, "OFF track at (55, 14) dp")
        assertRgb(off, 61, 42, 255, 255, 255, 2, "OFF thumb centre")
    }

    // ------------------------------------------------------ copy + a11y

    @Test
    fun theTagsAppearExactlyOnceEach() {
        mountFake(NotificationStatus.Granted)
        for (id in listOf(NotificationCopy.MasterId) + NotificationKind.entries.map { NotificationCopy.kindId(it) }) {
            assertEquals(
                "the row tag settings-$id appears exactly once",
                1,
                compose.onAllNodesWithTag(settingsTag(id)).fetchSemanticsNodes().size,
            )
            assertEquals(
                "the switch tag toggle-$id appears exactly once (unmerged)",
                1,
                compose.onAllNodesWithTag(toggleTag(id), useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun theMergedToggleRowCarriesTitleSubtitleAndTheSwitchState() {
        mountFake(NotificationStatus.NotDetermined)
        val node = row(settingsTag(NotificationCopy.MasterId))
        node.assertIsOff()
        val texts = node.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            ?: emptyList()
        val joined = texts.joinToString("") { it.text }
        assertTrue("merged text contains the title: '$joined'", joined.contains("Notifications"))
        assertTrue("merged text contains the subtitle: '$joined'", joined.contains("When a session finishes"))
        assertTrue(
            "title before subtitle: '$joined'",
            joined.indexOf("Notifications") < joined.indexOf("When a session"),
        )
        assertEquals(
            "Compose does not merge the switch's Role upward",
            null,
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role),
        )
        assertEquals(
            Role.Switch,
            switchNode(NotificationCopy.MasterId).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role),
        )
    }

    @Test
    fun theIconsAreDecorativeAndTheHeaderIsTheOnlyHeading() {
        mountFake(NotificationStatus.Granted)
        // The icons carry no semantics node: the unmerged subtree under the
        // master row carries exactly the two text nodes.
        val unmerged = compose
            .onNodeWithTag(settingsTag(NotificationCopy.MasterId), useUnmergedTree = true)
            .fetchSemanticsNode()
        val subtreeTexts = mutableListOf<String>()
        collectTexts(unmerged, subtreeTexts)
        assertEquals(listOf("Notifications", "When a session finishes, needs you or fails"), subtreeTexts)

        val headings = compose
            .onAllNodes(SemanticsMatcher("is a heading") { it.config.contains(SemanticsProperties.Heading) })
            .fetchSemanticsNodes()
        assertEquals("only the section header is a heading", 1, headings.size)
        assertEquals(
            "Notifications",
            headings.single().config.getOrNull(SemanticsProperties.Text)?.single()?.text,
        )
    }

    // ------------------------------------------------------------ helpers

    /** Every text in the unmerged subtree, in tree order. */
    private fun collectTexts(node: SemanticsNode, out: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.let { list -> out += list.map { it.text } }
        for (child in node.children) collectTexts(child, out)
    }

    /** The merged row's whole text (title + subtitle). */
    private fun rowText(tag: String): String =
        row(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString(separator = "") { it.text }
            ?: ""

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance &&
            abs(channel(c, 2) - b) <= tolerance

    /** Asserts the pixel is the given rgb value (± [tolerance], 8-bit steps). */
    private fun assertRgb(px: PixelMap, x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int, what: String) {
        val c = px[x, y]
        val t = tolerance / 255f
        assertTrue(
            "$what at ($x, $y): rgb(${(c.red * 255f).toInt()}, ${(c.green * 255f).toInt()}, ${(c.blue * 255f).toInt()}), " +
                "expected ($r, $g, $b) ±$tolerance",
            abs(c.red - r / 255f) <= t && abs(c.green - g / 255f) <= t && abs(c.blue - b / 255f) <= t,
        )
    }

    /**
     * Separators at [y]: present somewhere in the text-column span
     * (x 168..1062, image-local — the row image starts at the card's left
     * edge), absent either side (the GroupedListTest probe).
     */
    private fun PixelMap.hasSeparator(y: Int): Boolean {
        var ink = 0
        for (x in 168..1062) {
            if (matches(this[x, y], 198, 198, 200, 12)) ink++
        }
        if (ink < 20) return false
        assertTrue("stray ink left of the separator start", matches(this[160, y], 255, 255, 255, 2))
        assertTrue("stray ink right of the separator end", matches(this[1070, y], 255, 255, 255, 2))
        return true
    }
}

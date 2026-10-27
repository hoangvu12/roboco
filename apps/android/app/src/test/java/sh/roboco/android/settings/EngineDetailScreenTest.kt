package sh.roboco.android.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalActionSheet
import sh.roboco.android.design.LocalAlerts
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.LocalToaster
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.Toaster
import sh.roboco.android.pairing.LocalPairPage
import sh.roboco.android.pairing.PairClipboard
import sh.roboco.android.pairing.PairPageLauncher
import sh.roboco.android.pairing.PairingBackend
import sh.roboco.android.pairing.PairedEngineSink
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.SheetHostState
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomRecord
import uniffi.roboco_core.PairingTarget

/**
 * The Engine detail page's screen wiring (android-ui-parity #140,
 * Robolectric + compose-ui-test, 402 × 900 dp at xxhdpi — the width the
 * ticket's geometry table is stated against): the ticket's geometry
 * (52 dp rows, title x 32, the value rules, the 57.8 dp blank headers,
 * the chevron centre), the fixture looks (the success dot, the offline
 * greyed Rename…, the error footer), the Device ID copy (clipboard +
 * haptic + "Copied"), the Rename alert through the real
 * [EngineDetailScreen] wiring, the Forget sheet → sequence → one pop,
 * the engines-flow drop ("keep the last view"), and Pair again through
 * 72's launcher.
 *
 * The page is mounted behind real [LocalNavStack]/[LocalNavEntry] locals
 * on a hostless [NavStack] (pops commit at once, so the pop counts are
 * deterministic), with fakes for every host the page talks to. Merged
 * semantics hide the value texts, so value assertions use
 * `useUnmergedTree = true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class EngineDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ the fakes

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

    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) {}
    }

    private class FakeBackend : EngineDetailBackend {
        val calls = mutableListOf<String>()
        override suspend fun rename(engineId: String, name: String) {
            calls += "rename:$name"
        }

        override suspend fun revokeOwnSession(engineId: String) {
            calls += "revoke"
        }

        override suspend fun forget(engineId: String) {
            calls += "forget"
        }

        override suspend fun removeStored(engineId: String) {
            calls += "remove"
        }
    }

    /** 72's clipboard seam: never has text, never read here. */
    private class NoClipboard : PairClipboard {
        override fun hasText(): Boolean = false
        override fun readText(): String? = null
        override fun addListener(listener: android.content.ClipboardManager.OnPrimaryClipChangedListener) {}
        override fun removeListener(listener: android.content.ClipboardManager.OnPrimaryClipChangedListener) {}
    }

    private object StubPairingBackend : PairingBackend {
        override fun parse(input: String): PairingTarget? = null
        override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine = PairedEngine(
            engineId = "e-new",
            baseUrl = "http://127.0.0.1:1",
            credential = "credential",
            sessionId = "session",
            label = "label",
            pairedAtMs = 0L,
            deviceId = null,
            name = null,
        )

        override suspend fun forget(engineId: String) {}

        override fun parseRoom(input: String): RoomInviteTarget? = null

        override suspend fun joinRoom(invite: String): RoomJoinResult =
            throw UnsupportedOperationException("this stub joins no rooms")
    }

    private object StubSink : PairedEngineSink {
        override fun knownEngineIds(): Set<String> = emptySet()
        override suspend fun upsert(engine: PairedEngine) {}
        override suspend fun upsertRoom(room: RoomRecord) {}
        override suspend fun remove(engineId: String) {}
    }

    // --------------------------------------------------------- the fixture

    /** The ticket's fixed clock for the relative times. */
    private val now = 1_760_000_000_000L

    private val deviceId = "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95"

    private fun connectedView(id: String = "e1") = engineView(
        id = id,
        name = "MacBook Pro",
        state = EngineState.Connected,
        host = "192.168.1.20:27655",
        baseUrl = "http://192.168.1.20:27655",
        deviceId = deviceId,
        platform = "macos",
        version = "0.9.2",
    )

    private fun revokedView(id: String = "e1") = engineView(
        id = id,
        name = "Studio",
        state = EngineState.Parked(ParkReason.REVOKED),
        lastError = "invalid credential",
        host = "studio.tail1234.ts.net:27655",
        baseUrl = "https://studio.tail1234.ts.net:27655",
        deviceId = deviceId,
        platform = "linux",
        version = "0.9.1",
        lastConnectedMs = now - 7_200_000,
    )

    private val flow = MutableStateFlow<List<EngineView>?>(listOf(connectedView()))
    private val toaster = FakeToaster()
    private val alerts = FakeAlerts()
    private val sheets = FakeSheets()
    private val feedback = RecordingFeedback()
    private val backend = FakeBackend()
    private val drafted = mutableListOf<Pair<String, ForgetDraftsContext>>()
    private var contextReads = 0

    /** The Settings root the detail page is pushed on (26's shape). */
    private object TestRoot : NavRoute {
        override val paneTitle: String get() = "Settings"
    }

    private lateinit var stack: NavStack

    // ------------------------------------------------------------- mounting

    /**
     * One mount per test: the theme, the host fakes as locals, a hostless
     * [NavStack] with the detail route pushed, and the screen over the
     * fixture flow with the fake backend and the fixed clock.
     *
     * @param pairPage when non-null, also provided as [sh.roboco.android.pairing.LocalPairPage]
     */
    private fun mount(
        appearance: Appearance = Appearance.Light,
        engines: List<EngineView>? = listOf(connectedView()),
        pairPage: PairPageLauncher? = null,
    ) {
        flow.value = engines
        stack = NavStack(TestRoot)
        stack.push(EngineDetailRoute("e1"), animated = false)
        val pushed = stack.entries.last()
        compose.setContent {
            RobocoTheme(appearance) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides pushed,
                    LocalActionSheet provides sheets,
                    LocalAlerts provides alerts,
                    LocalToaster provides toaster,
                    LocalFeedback provides feedback,
                    LocalPairPage provides pairPage,
                ) {
                    EngineDetailScreen(
                        engineId = "e1",
                        engines = flow,
                        backend = backend,
                        forgetDrafts = ForgetDrafts { id, context -> drafted += id to context },
                        forgetContext = {
                            contextReads++
                            ForgetDraftsContext(snapshot = null, enginesLeft = 0)
                        },
                        nowMs = { now },
                        // The FFI lane inlined on main: the fake backend
                        // answers at once, so the sequences are synchronous
                        // under waitForIdle (no IO-thread hop to race).
                        io = Dispatchers.Main.immediate,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A test-side flow write landing after one frame (the sibling pattern). */
    private fun setEngines(engines: List<EngineView>?) {
        compose.runOnUiThread {
            flow.value = engines
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun rowImage(tag: String): PixelMap =
        compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()

    private fun assertDp(actual: Dp, expected: Dp, what: String, tolerance: Float = 1f) {
        assertTrue(
            "$what: $actual dp, expected $expected ±$tolerance dp",
            abs(actual.value - expected.value) <= tolerance,
        )
    }

    private fun heightOf(bounds: DpRect): Dp = bounds.bottom - bounds.top

    private fun channel(color: Color, ch: Int): Int = when (ch) {
        0 -> (color.red * 255f).toInt()
        1 -> (color.green * 255f).toInt()
        2 -> (color.blue * 255f).toInt()
        else -> error(ch)
    }

    private fun matches(c: Color, r: Int, g: Int, b: Int, tolerance: Int): Boolean =
        abs(channel(c, 0) - r) <= tolerance && abs(channel(c, 1) - g) <= tolerance &&
            abs(channel(c, 2) - b) <= tolerance

    /** The count of pixels matching the rgb triple anywhere in [this]. */
    private fun PixelMap.count(r: Int, g: Int, b: Int, tolerance: Int = 8): Int {
        var ink = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matches(this[x, y], r, g, b, tolerance)) ink++
            }
        }
        return ink
    }

    /** The ink centroid's x over [yRange] × [xRange], in image px. */
    private fun centroidX(
        px: PixelMap,
        yRange: IntRange,
        xRange: IntRange,
        match: (Color) -> Boolean,
    ): Float {
        var sum = 0f
        var count = 0
        for (y in yRange) {
            for (x in xRange) {
                if (match(px[x, y])) {
                    sum += x
                    count++
                }
            }
        }
        assertTrue("no matching pixels in x=$xRange, y=$yRange", count > 0)
        return sum / count
    }

    // -------------------------------------------------------------- geometry

    @Test
    fun theStatusRowsAre52TallWithTheTitleAt32() {
        mount()
        assertDp(heightOf(row("engine-detail-status").getUnclippedBoundsInRoot()), 52.dp, "status row height")
        assertDp(heightOf(row("engine-detail-address").getUnclippedBoundsInRoot()), 52.dp, "address row height")
        assertDp(heightOf(row("engine-detail-device-id").getUnclippedBoundsInRoot()), 52.dp, "device id row height")
        val title = compose.onNodeWithText("Status", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(title.left, 32.dp, "status title left")
    }

    @Test
    fun theVersionPlatformAndLastConnectedRowsFollowTheModel() {
        mount()
        val version = row("engine-detail-version").getUnclippedBoundsInRoot()
        val platform = row("engine-detail-platform").getUnclippedBoundsInRoot()
        assertDp(version.left, 16.dp, "the row node starts at the card edge")
        assertDp(platform.top - version.bottom, 0.dp, "no gap inside the card")
        compose.onNodeWithText("v0.9.2", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("macOS", useUnmergedTree = true).assertExists()
        // Connected: the row is hidden.
        compose.onNodeWithTag("engine-detail-last-connected", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * A FadingText value's frame — the capped, clipping node that carries
     * the full text in its own semantics. In the unmerged tree its inner
     * BasicText (unclipped, with GetTextLayoutResult) matches the same
     * text, so the frame is the match without that action.
     */
    private fun fadingFrame(text: String) = compose.onNode(
        hasText(text) and !SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    )

    @Test
    fun theAddressValueIsCappedAtHalfTheCardAndFadesItsTail() {
        mount()
        val address = fadingFrame("http://192.168.1.20:27655").getUnclippedBoundsInRoot()
        assertDp(address.right, 370.dp, "address value right edge")
        assertTrue("address width ${address.width} ≤ 185", address.width.value <= 185f)
        // FadingText keeps the full text in semantics and never adds "…".
        val faded = fadingFrame("http://192.168.1.20:27655")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals("http://192.168.1.20:27655", faded)
        assertFalse("no ellipsis character", faded.contains("\u2026"))
    }

    @Test
    fun theStatusDotIsSuccessGreenWhileConnected() {
        mount()
        val px = rowImage("engine-detail-status")
        // #15803D = (21, 128, 61); the dot is 8 dp = 24 px wide.
        assertTrue("no success ink in the status row", px.count(21, 128, 61) >= 20)
    }

    @Test
    fun theActionsAndDestructiveRowsMatchTheSettingsGeometry() {
        mount()
        val renameTitle = compose.onNodeWithText("Rename\u2026", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(renameTitle.left, 72.dp, "rename title left")
        assertDp(heightOf(row("engine-detail-rename").getUnclippedBoundsInRoot()), 52.dp, "rename row height")
        // The chevron's ink centroid: screen x 363.5 = node-local 347.5 dp
        // = 1042.5 px (the row image starts at the card edge, 1 dp = 3 px).
        val px = rowImage("engine-detail-rename")
        val midY = px.height / 2
        val centre = centroidX(px, midY - 12..midY + 12, 980..1080) {
            matches(it, 196, 196, 198, 12)
        }
        assertTrue("chevron centre $centre, expected 1042.5 ± 4", abs(centre - 1042.5f) <= 4f)
        // The forget row: a 52 dp capsule, the danger title.
        assertDp(heightOf(row("engine-detail-forget").getUnclippedBoundsInRoot()), 52.dp, "forget row height")
        val forgetTitle = compose.onNodeWithText("Forget", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertDp(forgetTitle.left, 72.dp, "forget title left")
        assertTrue(
            "no danger ink (#DC2626) in the forget row",
            rowImage("engine-detail-forget").count(220, 38, 38) >= 3,
        )
    }

    @Test
    fun theForgetTitleIsDangerInDarkModeToo() {
        mount(appearance = Appearance.Dark)
        assertTrue(
            "no danger ink (#F87171) in the dark forget row",
            rowImage("engine-detail-forget").count(248, 113, 113) >= 3,
        )
    }

    @Test
    fun theBlankHeadersKeepThe57Point8Gap() {
        mount()
        // The gap between two cards is exactly one blank header block.
        val statusBottom = row("engine-detail-device-id").getUnclippedBoundsInRoot().bottom
        val actionsTop = row("engine-detail-rename").getUnclippedBoundsInRoot().top
        assertDp(actionsTop - statusBottom, 57.8.dp, "status → actions header", tolerance = 0.6f)
        val actionsBottom = row("engine-detail-rename").getUnclippedBoundsInRoot().bottom
        val forgetTop = row("engine-detail-forget").getUnclippedBoundsInRoot().top
        assertDp(forgetTop - actionsBottom, 57.8.dp, "actions → destructive header", tolerance = 0.6f)
    }

    // -------------------------------------------------------- the fixtures

    @Test
    fun theRevokedFixtureGreysRenameShowsPairAgainAndFootsTheError() {
        mount(engines = listOf(revokedView()))
        // Greyed Rename…: tertiary ink in the title area.
        val px = rowImage("engine-detail-rename")
        assertTrue("no tertiary ink in the offline rename title", px.count(151, 151, 159) >= 3)
        row("engine-detail-pair-again").assertExists()
        compose.onNodeWithText("Session revoked", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Last connected", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2h ago", useUnmergedTree = true).assertExists()
        // The error footer: at x 32, 8 dp under the status card, tertiary.
        val statusBottom = row("engine-detail-device-id").getUnclippedBoundsInRoot().bottom
        val footerText = compose.onNodeWithText("invalid credential", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertDp(footerText.left, 32.dp, "footer text left")
        assertDp(footerText.top - statusBottom, 8.dp, "footer 8dp under the card")
        assertTrue(
            "no tertiary ink in the footer",
            rowImage("engine-detail-footer").count(151, 151, 159) >= 3,
        )
        // The long URL fades its tail and never ellipsizes.
        val url = fadingFrame("https://studio.tail1234.ts.net:27655")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals("https://studio.tail1234.ts.net:27655", url)
        assertFalse(url.contains("\u2026"))
    }

    @Test
    fun theIncompatibleFixtureShowsUpdateRequiredAndNoPairAgain() {
        mount(
            engines = listOf(
                engineView(
                    id = "e1",
                    name = "Build box",
                    state = EngineState.Parked(ParkReason.INCOMPATIBLE),
                    lastError = "Build box runs an older Roboco (0.8.0). Update Roboco on that device to connect.",
                    host = "10.0.0.7:27655",
                    baseUrl = "http://10.0.0.7:27655",
                    deviceId = deviceId,
                    platform = "windows",
                    version = "0.8.0",
                    lastConnectedMs = now - 259_200_000,
                ),
            ),
        )
        compose.onNodeWithText("Update required", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("3d ago", useUnmergedTree = true).assertExists()
        row("engine-detail-pair-again").assertDoesNotExist()
        compose.onNodeWithText(
            "Build box runs an older Roboco (0.8.0). Update Roboco on that device to connect.",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun theNoticeFixtureShowsTheNoticeFooterWhileConnected() {
        mount(
            engines = listOf(
                engineView(
                    id = "e1",
                    name = "MacBook Pro",
                    state = EngineState.Connected,
                    host = "192.168.1.20:27655",
                    baseUrl = "http://192.168.1.20:27655",
                    deviceId = deviceId,
                    platform = "macos",
                    version = "0.10.0",
                    notice = "MacBook Pro runs Roboco 0.10.0, newer than this app (0.9.2). " +
                        "Everything should keep working; if something looks wrong, update the app.",
                ),
            ),
        )
        compose.onNodeWithText("v0.10.0", useUnmergedTree = true).assertExists()
        assertTrue(
            "no secondary ink in the notice footer",
            rowImage("engine-detail-footer").count(98, 98, 106) >= 3,
        )
    }

    // ------------------------------------------------------ the copy flow

    @Test
    fun tappingTheDeviceIdCopiesTheRawIdFiresTheLightHapticAndShowsCopied() {
        mount()
        // Freeze the clock: with autoAdvance on, waitForIdle would run the
        // 1.5 s hold (and "Copied" would already be gone).
        compose.mainClock.autoAdvance = false
        row("engine-detail-device-id").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val clip = context().getSystemService(ClipboardManager::class.java)?.primaryClip
        assertEquals("Device ID", clip?.description?.label?.toString())
        assertEquals(deviceId, clip?.getItemAt(0)?.text?.toString())
        assertEquals(listOf(Haptic.Light), feedback.haptics)
        // The value node flips to "Copied" (in success), reading the full
        // id otherwise.
        compose.onNodeWithText("Copied", useUnmergedTree = true).assertExists()
        assertTrue(
            "no success ink in the copied value",
            rowImage("engine-detail-device-id").count(21, 128, 61) >= 3,
        )
        // After the 1.5 s hold, the value node reads the full id again.
        compose.mainClock.advanceTimeBy(2_000)
        // The hold's `copied = false` lands inside that advance; on the frozen
        // clock its apply notification is not guaranteed before the next frame.
        compose.runOnUiThread { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithText("Copied", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(deviceId, useUnmergedTree = true).assertExists()
    }

    @Test
    fun theDeviceIdRowExposesTheFullIdAndALabelledClickAction() {
        mount()
        // The value node's semantics read the full id (the drawn text is
        // the short form; TalkBack gets the whole id).
        val value = compose.onNodeWithText(deviceId, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(deviceId, value.config[SemanticsProperties.Text].single().text)
        // The merged row carries the labelled copy action.
        val merged = row("engine-detail-device-id").fetchSemanticsNode()
        assertEquals("Copy device ID", merged.config[SemanticsActions.OnClick]?.label)
    }

    // ---------------------------------------------------------- the rename

    @Test
    fun renameWhileOfflineToastsAndNeverOpensTheAlert() {
        mount(engines = listOf(revokedView()))
        compose.onNodeWithTag("engine-detail-rename").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Engine not connected"), toaster.toasts)
        assertTrue("no alert", alerts.shown.isEmpty())
        assertTrue("no FFI", backend.calls.isEmpty())
    }

    @Test
    fun theRenameAlertCarriesTheTicketStructure() {
        mount()
        compose.onNodeWithTag("engine-detail-rename").performClick()
        compose.waitForIdle()
        val alert = alerts.shown.single()
        assertEquals("Rename device", alert.title)
        assertNull(alert.message)
        val field = alert.field!!
        assertEquals("MacBook Pro", field.text)
        assertEquals("Device name", field.placeholder)
        assertEquals(KeyboardCapitalization.Sentences, field.capitalization)
        assertTrue(field.autocorrect)
        assertEquals(2, alert.buttons.size)
        assertEquals("Cancel", alert.buttons[0].title)
        assertEquals(AlertButtonRole.Cancel, alert.buttons[0].role)
        assertEquals("Rename", alert.buttons[1].title)
        assertEquals(AlertButtonRole.Default, alert.buttons[1].role)
        assertFalse(alert.buttons[1].requiresText)
    }

    @Test
    fun theRenameButtonSubmitsTheTrimmedNameThroughTheBackend() {
        mount()
        compose.onNodeWithTag("engine-detail-rename").performClick()
        compose.waitForIdle()
        alerts.shown.single().buttons[1].onClick("  New name ")
        compose.waitForIdle()
        assertEquals(listOf("rename:New name"), backend.calls)
        assertTrue("success stays silent", toaster.toasts.isEmpty())
    }

    @Test
    fun aBlankRenameAndTheCancelButtonCallNothing() {
        mount()
        compose.onNodeWithTag("engine-detail-rename").performClick()
        compose.waitForIdle()
        alerts.shown.single().buttons[1].onClick("   ")
        alerts.shown.single().buttons[0].onClick("MacBook Pro")
        compose.waitForIdle()
        assertTrue("no FFI call", backend.calls.isEmpty())
    }

    // ---------------------------------------------------------- the forget

    @Test
    fun theForgetTapShowsTheConfirmSheetAndTheRowTitleStays() {
        mount()
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        val sheet = sheets.shown.single()
        assertEquals("Forget MacBook Pro?", sheet.title)
        assertNull(sheet.message)
        val action = sheet.actions.single()
        assertEquals("Forget", action.title)
        assertEquals(ActionSheetStyle.Destructive, action.style)
        assertEquals("Cancel", sheet.cancelTitle)
        assertTrue("no backend call yet", backend.calls.isEmpty())
        assertEquals("the row title is unchanged", 2, stack.entries.size)
        compose.onNodeWithText("Forget", useUnmergedTree = true).assertExists()
    }

    @Test
    fun cancellingTheSheetRunsNoSequenceAndTheSheetShowsAgain() {
        mount()
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        sheets.shown.single().onCancel()
        compose.waitForIdle()
        assertTrue("no FFI", backend.calls.isEmpty())
        assertEquals(2, stack.entries.size)
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        assertEquals("the sheet shows again", 2, sheets.shown.size)
    }

    @Test
    fun theSheetsForgetRunsTheSequenceAndPopsOnce() {
        mount()
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        sheets.shown.single().actions.single().onSelect()
        compose.waitForIdle()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals("one pop", 1, stack.entries.size)
        assertEquals(listOf("e1" to ForgetDraftsContext(snapshot = null, enginesLeft = 0)), drafted)
        assertEquals("the context was read once", 1, contextReads)
    }

    @Test
    fun aParkedEngineForgetsWithoutTheRevoke() {
        mount(engines = listOf(revokedView()))
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        sheets.shown.single().actions.single().onSelect()
        compose.waitForIdle()
        assertEquals(listOf("forget", "remove"), backend.calls)
        assertEquals(1, stack.entries.size)
    }

    // ------------------------------------------------- the flow-driven pop

    @Test
    fun theFlowDroppingTheEnginePopsOnceAndKeepsTheLastView() {
        mount()
        assertEquals(2, stack.entries.size)
        setEngines(emptyList())
        assertEquals("one pop only", 1, stack.entries.size)
        // While popping, the cards still show the last values (no blank
        // frame): the last view survives the flow change.
        // LargeTitleScaffold composes both the large and the inline title with
        // the text; the large title's tag pins one node.
        compose.onNodeWithTag("nav-large-title").assert(hasText("MacBook Pro"))
        row("engine-detail-status").assertExists()
        compose.onNodeWithText("Connected", useUnmergedTree = true).assertExists()
    }

    @Test
    fun forgetPlusTheFlowDroppingTheEngineStillPopsExactlyOnce() {
        mount()
        compose.onNodeWithTag("engine-detail-forget").performClick()
        compose.waitForIdle()
        sheets.shown.single().actions.single().onSelect()
        setEngines(emptyList())
        compose.waitForIdle()
        assertEquals(listOf("revoke", "forget", "remove"), backend.calls)
        assertEquals("still exactly one pop", 1, stack.entries.size)
    }

    @Test
    fun anUnknownEngineOnFirstCompositionPopsTheEmptyScaffold() {
        mount(engines = emptyList())
        compose.waitForIdle()
        assertEquals("the scaffold pops itself", 1, stack.entries.size)
        compose.onNodeWithTag("engine-detail").assertExists()
        row("engine-detail-status").assertDoesNotExist()
    }

    @Test
    fun aNullFlowNeverPops() {
        mount(engines = null)
        compose.waitForIdle()
        assertEquals("the core is still starting: no pop", 2, stack.entries.size)
    }

    // -------------------------------------------------------- state changes

    @Test
    fun parkAndReconnectUpdateTheRowsOnTheNextFrame() {
        mount(engines = listOf(revokedView()))
        row("engine-detail-pair-again").assertExists()
        setEngines(listOf(connectedView()))
        // The page stays, Pair again disappears, Status reads Connected.
        assertEquals("the page stays pushed", 2, stack.entries.size)
        row("engine-detail-pair-again").assertDoesNotExist()
        compose.onNodeWithText("Connected", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("2h ago", useUnmergedTree = true).assertDoesNotExist()
    }

    // ---------------------------------------------------------- pair again

    @Test
    fun pairAgainOpensThePairPageInRePairMode() {
        val host = SheetHostState()
        val launcher = PairPageLauncher(
            host,
            StubPairingBackend,
            StubSink,
            CoroutineScope(SupervisorJob()),
            qrScan = null,
            clipboard = NoClipboard(),
        )
        mount(engines = listOf(revokedView()), pairPage = launcher)
        compose.onNodeWithTag("engine-detail-pair-again").performClick()
        compose.waitForIdle()
        assertTrue("the Pair page is presented", host.entries.isNotEmpty())
        assertEquals("pair", host.entries.last().key)
        val model = launcher.current
        assertTrue("a model was created", model != null)
        assertEquals("re-pair targets the engine", "e1", model?.replaceEngineId)
        assertEquals("no prefill", "", model?.text?.text?.toString() ?: "")
    }

    // ------------------------------------------------------------ internals

    /** The Robolectric application, for the clipboard read. */
    private fun context(): Context = ApplicationProvider.getApplicationContext()
}

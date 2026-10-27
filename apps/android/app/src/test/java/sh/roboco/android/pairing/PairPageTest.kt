package sh.roboco.android.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.BusySpinnerTag
import sh.roboco.android.shell.SheetHost
import sh.roboco.android.shell.SheetHostState
import sh.roboco.android.shell.SheetStyle
import uniffi.roboco_core.PairException
import uniffi.roboco_core.PairedEngine
import uniffi.roboco_core.RoomInviteTarget
import uniffi.roboco_core.RoomJoinResult
import uniffi.roboco_core.RoomRecord
import uniffi.roboco_core.PairingTarget

/**
 * The Pair page's Compose behaviour (android-ui-parity #72) — Robolectric
 * + compose-ui-test on the ticket's device (`w393dp-h852dp-xxhdpi`,
 * Robolectric's zero system-bar insets), with the page composed in a
 * real [SheetHost] via `presentSettledForPreview`:
 *
 * - the footers per field text (hint only / + "Engine at …" / +
 *   cleartext), the parse running through the fake backend's regex;
 * - Paste: only while the field is blank and the clip description says
 *   text; live clipboard changes through the listener; the tap fills the
 *   trimmed clip **without** submitting; opening the page never reads
 *   the clip (the fake counts `readText` calls); one direct check of the
 *   real [AndroidPairClipboard] over Robolectric's clipboard;
 * - the Scan QR row: hidden without `FEATURE_CAMERA_ANY` and without a
 *   [QrScanRequest]; a delivered URL fills the field and submits at once;
 * - the capsule: disabled while the field is blank, busy while
 *   redeeming (frozen clock: "Pairing…", 08's spinner, taps ignored);
 *   `performImeAction` on the field submits;
 * - error details: a Tailcat failure's reason behind a collapsed "Show
 *   details" toggle; no toggle for an error without a detail;
 * - the success flow: `upsert` before the Confirm haptic, the sheet
 *   dismissed once;
 * - tag bounds: the card rows at x 16, the footer at x 32, the field
 *   text at x 32, the capsule at x 24 … 369.
 *
 * The model's scope and io lane are [UnconfinedTestDispatcher]s, so a
 * redeem runs inline on the tap thread — deterministic, no looper
 * coupling; only the sheet's own dismissal animation needs the clock.
 * The frozen-clock busy test pairs `waitForIdle` (the snapshot apply
 * notifications) with a frame advance after each state write.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class PairPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = SheetHostState()
    private val ops = mutableListOf<String>()
    private val backend = FakeBackend(ops)
    private val sink = FakeSink(ops)
    private val feedback = RecordingFeedback(ops)
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val io = UnconfinedTestDispatcher()
    private var dismissals = 0
    private var model: PairPageModel? = null

    @Before
    fun setUp() {
        // Deterministic both ways: the scan row tests toggle it per test.
        shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------ the harness

    /**
     * Composes the page in a real sheet host and presents it settled
     * (no present animation). [clipboard] drives Paste; [scan] the Scan
     * QR row; [prefill] the field.
     */
    private fun hostPage(
        prefill: String = "",
        scan: QrScanRequest? = QrScanRequest { onResult -> onResult(ScanUrl) },
        clipboard: PairClipboard = FakeClipboard(),
    ) {
        val pageModel = PairPageModel(
            prefill = prefill,
            initialReplaceEngineId = null,
            backend = backend,
            store = sink,
            scope = scope,
            io = io,
        )
        model = pageModel
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(LocalFeedback provides feedback) {
                    SheetHost(state) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(RobocoTheme.palette.background)
                                .testTag("behind"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            state.presentSettledForPreview(
                key = "pair",
                style = SheetStyle.Page(grabber = true),
                onDismissed = { dismissals++ },
            ) {
                PairPage(pageModel, this, scan, clipboard)
            }
        }
        compose.waitForIdle()
    }

    /** Delivers pending writes and runs the frame that recomposes them. */
    private fun frame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Runs the clock past the sheet's dismissal spring and settles. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    private fun widthDp(tag: String): Float = bounds(tag).let { it.right.value - it.left.value }

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // -------------------------------------------------------------- footers

    @Test
    fun aBlankFieldShowsOnlyTheHint() {
        hostPage()
        compose.onNodeWithTag("pair-hint").assertExists()
        compose.onNodeWithTag("pair-target").assertDoesNotExist()
        compose.onNodeWithTag("pair-cleartext").assertDoesNotExist()
        compose.onNodeWithText("Engine at 192.168.1.10:27655").assertDoesNotExist()
    }

    @Test
    fun textThatDoesNotParseShowsOnlyTheHint() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("not a url")
        compose.waitForIdle()
        compose.onNodeWithTag("pair-target").assertDoesNotExist()
        compose.onNodeWithTag("pair-cleartext").assertDoesNotExist()
    }

    @Test
    fun anHttpsLinkShowsTheTargetWithoutTheCleartextNote() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("https://engine.example.ts.net/pair#token=abc")
        awaitTag("pair-target")
        compose.onNodeWithText("Engine at engine.example.ts.net").assertExists()
        compose.onNodeWithTag("pair-cleartext").assertDoesNotExist()
    }

    @Test
    fun anHttpLinkShowsTheTargetAndTheCleartextNote() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("http://192.168.1.10:27655/pair#token=abc")
        awaitTag("pair-target")
        compose.onNodeWithText("Engine at 192.168.1.10:27655").assertExists()
        compose.onNodeWithText("This link uses http://, so pairing and your session aren't encrypted. Use a trusted network or your own tunnel.")
            .assertExists()
        compose.onNodeWithTag("pair-cleartext").assertExists()
    }

    @Test
    fun aTailcatInviteShowsItsExpiryAndNoCleartextNote() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("roboco-tailcat:abc")
        awaitTag("pair-target")
        compose.onNodeWithText("Engine over Tailcat \u00B7 invite expires in 5 min").assertExists()
        compose.onNodeWithTag("pair-cleartext").assertDoesNotExist()
    }

    @Test
    fun theBusyCapsuleSaysConnectingThroughTailcatForAnInvite() {
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        hostPage(prefill = "roboco-tailcat:abc")
        awaitTag("pair-target")

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("pair-submit").performTouchInput { click(center) }
        frame()

        compose.onNodeWithText("Connecting through Tailcat\u2026").assertExists()
        gate.complete(newEngine())
        settle()
    }

    // --------------------------------------------------------------- paste

    @Test
    fun pasteAppearsOnlyWhileTheFieldIsBlankAndTheClipHoldsText() {
        val clipboard = FakeClipboard()
        clipboard.hasText = true
        hostPage(clipboard = clipboard)

        // The visible check read only the description — never the clip.
        assertEquals(0, clipboard.readTextCalls)
        compose.onNodeWithTag("pair-paste").assertExists()
        compose.onNodeWithTag("pair-paste").assert(hasClickAction())

        compose.onNodeWithTag("pair-url").performTextInput("https://h/pair#token=x")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pair-paste").fetchSemanticsNodes().isEmpty()
        }
        // Blank again: the empty field brings it back.
        compose.onNodeWithTag("pair-url").performTextClearance()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pair-paste").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, clipboard.readTextCalls)
    }

    @Test
    fun aClipboardChangeWhileThePageIsOpenTogglesPaste() {
        val clipboard = FakeClipboard()
        clipboard.hasText = false
        hostPage(clipboard = clipboard)
        compose.onNodeWithTag("pair-paste").assertDoesNotExist()

        compose.runOnUiThread {
            clipboard.hasText = true
            clipboard.fireClipChanged()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pair-paste").fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnUiThread {
            clipboard.hasText = false
            clipboard.fireClipChanged()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pair-paste").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun tappingPasteFillsTheTrimmedClipWithoutSubmitting() {
        val clipboard = FakeClipboard()
        clipboard.hasText = true
        clipboard.text = "http://192.168.1.10:27655/pair#token=abc"
        hostPage(clipboard = clipboard)

        compose.onNodeWithTag("pair-paste").performClick()
        compose.waitForIdle()

        assertEquals(1, clipboard.readTextCalls)
        assertTrue(backend.pairCalls.isEmpty()) // never auto-submits
        val value = compose.onNodeWithTag("pair-url")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("http://192.168.1.10:27655/pair#token=abc", value.text)
        // The pasted link parses: the target footer appears.
        awaitTag("pair-target")
    }

    @Test
    fun theFieldAndPasteStayIndependentNodesInsideTheMergingRow() {
        val clipboard = FakeClipboard()
        clipboard.hasText = true
        hostPage(clipboard = clipboard)
        // 70's row container merges descendants; the field (its own merge
        // root) must still resolve as an editable node and Paste as its
        // own button.
        compose.onNodeWithTag("pair-url").assert(hasSetTextAction())
        compose.onNodeWithText("Paste").assert(hasClickAction())
        compose.onNodeWithTag("pair-url").performTextInput("https://h/pair#token=x")
        compose.waitForIdle()
        compose.onNodeWithTag("pair-paste").assertDoesNotExist()
    }

    @Test
    fun theRealAndroidPairClipboardReadsOnlyDescriptionsForVisibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = AndroidPairClipboard(context)
        assertFalse(clipboard.hasText())

        val manager = context.getSystemService(ClipboardManager::class.java)!!
        manager.setPrimaryClip(ClipData.newPlainText("pairing", "  http://192.168.1.10:27655/pair#token=abc  "))
        assertTrue(clipboard.hasText())
        assertEquals("http://192.168.1.10:27655/pair#token=abc", clipboard.readText())

        manager.clearPrimaryClip()
        assertFalse(clipboard.hasText())
        assertNull(clipboard.readText())
    }

    // ------------------------------------------------------------ the scan row

    @Test
    fun theScanRowIsHiddenWithoutTheCameraFeature() {
        shadowOf(ApplicationProvider.getApplicationContext<Context>().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, false)
        hostPage()
        compose.onNodeWithTag("settings-pair-scan").assertDoesNotExist()
    }

    @Test
    fun theScanRowIsHiddenWithoutAQrScanRequest() {
        hostPage(scan = null)
        compose.onNodeWithTag("settings-pair-scan").assertDoesNotExist()
    }

    @Test
    fun tappingTheScanRowFillsTheFieldAndSubmitsAtOnce() {
        hostPage()
        compose.onNodeWithTag("settings-pair-scan").performClick()
        compose.waitForIdle()

        assertEquals(listOf(ScanUrl to null), backend.pairCalls)
        assertEquals(1, sink.upserts.size)
        // The auto-submit cleared the field and closed the sheet.
        compose.waitUntil(5_000) { state.entries.isEmpty() }
    }

    // ------------------------------------------------------------- the capsule

    @Test
    fun theCapsuleIsDisabledWhileTheFieldIsBlankAndEnabledOnceItHasText() {
        hostPage()
        compose.onNodeWithTag("pair-submit").assertIsNotEnabled()
        compose.onNodeWithText("Pair engine").assertExists()

        compose.onNodeWithTag("pair-url").performTextInput("https://h/pair#token=x")
        compose.waitForIdle()
        compose.onNodeWithTag("pair-submit").assertIsEnabled()
    }

    @Test
    fun theImeGoActionSubmits() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("https://h/pair#token=x")
        compose.waitForIdle()
        compose.onNodeWithTag("pair-url").performImeAction()
        compose.waitForIdle()

        assertEquals(listOf("https://h/pair#token=x" to null), backend.pairCalls)
    }

    @Test
    fun theBusyCapsuleShowsPairingAndIgnoresExtraTaps() {
        val gate = CompletableDeferred<PairedEngine>()
        backend.pairImpl = { _, _ -> gate.await() }
        hostPage(prefill = "http://192.168.1.10:27655/pair#token=abc")
        compose.waitForIdle()

        // Frozen clock: the spinner's frame loop must not spin.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("pair-submit").performTouchInput { click(center) }
        frame()

        compose.onNodeWithText("Pairing…").assertExists()
        compose.onNodeWithTag(BusySpinnerTag, useUnmergedTree = true).assertExists()
        // Raw taps: performClick would fail on the missing click action.
        compose.onNodeWithTag("pair-submit").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, backend.pairCalls.size)

        gate.complete(newEngine())
        assertEquals(PairPhase.Idle, model!!.phase)
        settle()
        assertEquals(1, sink.upserts.size)
        assertEquals(1, dismissals)
        compose.waitUntil(5_000) { state.entries.isEmpty() }
    }

    // ------------------------------------------------------------ error details

    @Test
    fun aTailcatFailureShowsItsReasonBehindADetailsToggle() {
        backend.pairImpl = { _, _ -> throw PairException.TailcatUnreachable("invalid Tailcat address") }
        hostPage(prefill = "roboco-tailcat:abc")
        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Could not reach that engine over Tailcat. Check that it is running and online.")
            .assertExists()
        compose.onNodeWithTag("pair-error-detail").assertDoesNotExist()
        compose.onNodeWithTag("pair-error-details").assert(hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("invalid Tailcat address").assertExists()
        compose.onNodeWithText("Hide details").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("pair-error-detail").assertDoesNotExist()
        compose.onNodeWithText("Show details").assertExists()
    }

    @Test
    fun anErrorWithoutADetailHasNoToggle() {
        backend.pairImpl = { _, _ -> throw PairException.Refused() }
        hostPage(prefill = "https://h/pair#token=x")
        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("pair-error").assertExists()
        compose.onNodeWithTag("pair-error-details").assertDoesNotExist()
    }

    // ---------------------------------------------------------- the success flow

    @Test
    fun successUpsertsBeforeTheConfirmHapticAndDismissesTheSheetOnce() {
        hostPage()
        compose.onNodeWithTag("pair-url").performTextInput("https://h/pair#token=x")
        compose.waitForIdle()

        compose.onNodeWithTag("pair-submit").performClick()
        compose.waitForIdle()
        settle()

        assertEquals(listOf("known", "pair", "upsert", "haptic"), ops)
        assertEquals(listOf(Haptic.Confirm), feedback.haptics)
        assertEquals(1, sink.upserts.size)
        assertEquals(1, dismissals)
        assertTrue(state.entries.isEmpty())
        compose.onNodeWithTag("pair-page").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun theCardsFootersAndCapsuleSitAtTheTicketsOffsets() {
        hostPage(prefill = "http://192.168.1.10:27655/pair#token=abc")
        awaitTag("pair-target")
        compose.waitForIdle()

        // The two card rows: x 16 … 377, one card width.
        val fieldRow = bounds("pair-link-row")
        assertEquals(16f, fieldRow.left.value, 0.5f)
        val scanRow = bounds("settings-pair-scan")
        assertEquals(16f, scanRow.left.value, 0.5f)
        assertEquals(widthDp("pair-link-row"), widthDp("settings-pair-scan"), 0.5f)
        assertEquals(361f, widthDp("pair-link-row"), 0.5f)
        // The field's text starts 16 inside the card (x 32).
        assertEquals(32f, bounds("pair-url").left.value, 0.5f)
        // The footer text: x 32 … 361 (the tag sits on 70's padded Box,
        // the text is its own node inside it, so the measured node is the
        // text itself). The rooms merge's hint names all three invite
        // kinds — the substring pins it so the footer cannot silently
        // drop one.
        val hint = compose.onNodeWithText(
            "Paste a pairing link, a roboco-tailcat: invite, or a roboco-room: room invite",
            substring = true,
        )
            .getBoundsInRoot()
        assertEquals(32f, hint.left.value, 0.5f)
        assertTrue("footer right edge: ${hint.right.value}", hint.right.value <= 361.5f)
        // The capsule: x 24 … 369, ≥ 44 high, 52.1 with the pairing padding.
        val capsule = bounds("pair-submit")
        assertEquals(24f, capsule.left.value, 0.5f)
        assertEquals(369f, capsule.right.value, 0.5f)
        assertTrue("capsule height: ${capsule.bottom.value - capsule.top.value}", abs(52.1f - (capsule.bottom.value - capsule.top.value)) < 2f)
        // The header ✕: a 44 dp circle at start 16.
        val close = bounds("sheet-close")
        assertEquals(16f, close.left.value, 0.5f)
        assertEquals(44f, close.right.value - close.left.value, 0.5f)
        // One row each: the field, the scan row, the paste-less field.
        compose.onAllNodesWithTag("pair-url").assertCountEquals(1)
        compose.onAllNodesWithTag("settings-pair-scan").assertCountEquals(1)
    }

    // ------------------------------------------------------------- internals

    private companion object {
        const val ScanUrl = "https://engine.example.ts.net/pair#token=qr-scan"
    }

    // ------------------------------------------------------------- the fakes

    /** Succeeds at once unless scripted otherwise; records the op order. */
    private class FakeBackend(private val ops: MutableList<String>) : PairingBackend {
        var pairImpl: suspend (String, String?) -> PairedEngine = { _, _ -> newEngine() }
        val pairCalls = mutableListOf<Pair<String, String?>>()

        override fun parse(input: String): PairingTarget? = StandInParse(input)

        override suspend fun pair(url: String, replaceEngineId: String?): PairedEngine {
            ops += "pair"
            pairCalls += url to replaceEngineId
            return pairImpl(url, replaceEngineId)
        }

        override suspend fun forget(engineId: String) {
            ops += "forget"
        }

        override fun parseRoom(input: String): RoomInviteTarget? = null

        override suspend fun joinRoom(invite: String): RoomJoinResult =
            throw UnsupportedOperationException("this fake joins no rooms")
    }

    private class FakeSink(private val ops: MutableList<String>) : PairedEngineSink {
        val upserts = mutableListOf<PairedEngine>()
        override suspend fun upsertRoom(room: RoomRecord) {
            ops += "upsertRoom"
        }
        override fun knownEngineIds(): Set<String> {
            ops += "known"
            return emptySet()
        }
        override suspend fun upsert(engine: PairedEngine) {
            ops += "upsert"
            upserts += engine
        }
        override suspend fun remove(engineId: String) {
            ops += "remove"
        }
    }

    private class RecordingFeedback(private val ops: MutableList<String>) : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            ops += "haptic"
            haptics += haptic
        }
        override fun cue(cue: Cue) {}
    }

    /** The clipboard fake: a mutable `hasText`, a counted `readText`, the listener list. */
    private class FakeClipboard : PairClipboard {
        var hasText = false
        var text: String? = null
        var readTextCalls = 0
        val listeners = mutableListOf<ClipboardManager.OnPrimaryClipChangedListener>()

        override fun hasText(): Boolean = hasText

        override fun readText(): String? {
            readTextCalls++
            return text
        }

        override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
            listeners += listener
        }

        override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
            listeners -= listener
        }

        fun fireClipChanged() {
            listeners.toList().forEach { it.onPrimaryClipChanged() }
        }
    }
}

/**
 * The tests' regex parse stand-in (the gallery's twin): never the native
 * library — Robolectric fixtures must not depend on it. android-app#06
 * owns the real parser's tests.
 */
internal fun StandInParse(input: String): PairingTarget? {
    // android-next#02: a Tailcat invite, valid for five more minutes.
    if (input.trim().startsWith("roboco-tailcat:")) {
        return PairingTarget(
            baseUrl = "tailcat:3f9a12bc",
            host = "Tailcat",
            tailcat = true,
            expiresAtMs = System.currentTimeMillis() + 300_000,
        )
    }
    val match = Regex("^(https?)://([^/?#@]+)(/.*)?/pair#token=[A-Za-z0-9_-]+$")
        .matchEntire(input.trim()) ?: return null
    val scheme = match.groupValues[1]
    val host = match.groupValues[2]
    val prefix = match.groupValues[3]
    return PairingTarget(baseUrl = "$scheme://$host$prefix", host = host)
}

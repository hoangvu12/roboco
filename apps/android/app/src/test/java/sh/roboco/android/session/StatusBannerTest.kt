package sh.roboco.android.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason
import uniffi.roboco_core.PendingKind
import uniffi.roboco_core.PendingSend
import uniffi.roboco_core.SendState

/**
 * The status banner's non-visual half (android-ui-parity #73):
 * [sendingBridge], [deriveBanner] (every row of the ticket's worked
 * consequences table, one test each), [shownBanner], [bannerInputs],
 * [errorText], the [StatusBanner] filler's sticky state and its tap
 * dispatch.
 *
 * Plain JUnit 4, no Robolectric: everything here is pure Kotlin over the
 * lite inputs, and the FFI records come from the test `Fixtures.kt` (their
 * construction touches no JNA). The filler's IO lane is a
 * `StandardTestDispatcher`, so "nothing ran until the scheduler advanced"
 * is observable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusBannerTest {

    // ------------------------------------------------------------- the bridge

    @Test
    fun theSendToTurnBridge() {
        assertFalse(sendingBridge(null, 5L)) // no send in flight
        assertTrue(sendingBridge(5L, null)) // no turn yet
        assertTrue(sendingBridge(5L, 5L)) // the turn at the send's start
        assertTrue(sendingBridge(5L, 4L)) // a turn before the send
        assertFalse(sendingBridge(5L, 6L)) // a turn after the send adopted it
    }

    // ------------------------------------------- deriveBanner: the table rows

    /** Row: sendFailure + engine Reconnecting → Failed (sticky beats the link). */
    @Test
    fun sendFailureBeatsTheReconnectingBanner() {
        val message = "Couldn't send: Engine is offline; reconnecting"
        assertEquals(
            SessionChrome.Banner.Failed(message),
            deriveBanner(BannerInputs(sendFailure = message, link = EngineLink.Reconnecting)),
        )
        // …and it also beats a failed send state with a connected engine.
        assertEquals(
            SessionChrome.Banner.Failed(message),
            deriveBanner(
                BannerInputs(sendFailure = message, sendState = SendLite.Failed, link = EngineLink.Connected),
            ),
        )
    }

    /** Row: FAILED + engine Connected → NotDelivered. */
    @Test
    fun failedSendStateWhileConnectedIsNotDelivered() {
        assertEquals(
            SessionChrome.Banner.NotDelivered,
            deriveBanner(BannerInputs(sendState = SendLite.Failed, link = EngineLink.Connected)),
        )
    }

    /** Row: FAILED, engine Connected, settingFailure set → NotDelivered (beats the setting failure). */
    @Test
    fun notDeliveredBeatsTheSettingFailure() {
        assertEquals(
            SessionChrome.Banner.NotDelivered,
            deriveBanner(
                BannerInputs(
                    sendState = SendLite.Failed,
                    link = EngineLink.Connected,
                    settingFailure = "Couldn't change session settings: unknown model",
                ),
            ),
        )
    }

    /** Row: FAILED, engine Reconnecting → Reconnecting (never NotDelivered while not connected). */
    @Test
    fun failedSendStateWhileReconnectingStaysReconnecting() {
        assertEquals(
            SessionChrome.Banner.Reconnecting,
            deriveBanner(BannerInputs(sendState = SendLite.Failed, link = EngineLink.Reconnecting)),
        )
    }

    /** Row: FAILED, engine Parked(REVOKED) → EngineParked(Revoked, id). */
    @Test
    fun failedSendStateWhileParkedIsEngineParked() {
        assertEquals(
            SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Revoked, "e1"),
            deriveBanner(
                BannerInputs(
                    sendState = SendLite.Failed,
                    link = EngineLink.Parked(SessionChrome.ParkedReason.Revoked, "e1"),
                ),
            ),
        )
    }

    /** Row: settingFailure + engine Parked(IDENTITY_CHANGED) → Failed. */
    @Test
    fun settingFailureBeatsTheParkedBanner() {
        val message = "Couldn't change session settings: unknown model"
        assertEquals(
            SessionChrome.Banner.Failed(message),
            deriveBanner(
                BannerInputs(
                    settingFailure = message,
                    link = EngineLink.Parked(SessionChrome.ParkedReason.IdentityChanged, "e1"),
                ),
            ),
        )
    }

    /** Row: engine null → EngineOff. */
    @Test
    fun aMissingEngineIsEngineOff() {
        assertEquals(
            SessionChrome.Banner.EngineOff,
            deriveBanner(BannerInputs(link = EngineLink.Missing)),
        )
    }

    /** Row: engine Parked(INCOMPATIBLE) → EngineOff (a version mismatch is not repairable). */
    @Test
    fun anIncompatibleEngineIsEngineOff() {
        assertEquals(
            SessionChrome.Banner.EngineOff,
            deriveBanner(
                BannerInputs(link = EngineLink.Parked(SessionChrome.ParkedReason.Incompatible, "e1")),
            ),
        )
    }

    /** Row: Offline / Connecting / Reconnecting, pending SENDING send → Reconnecting. */
    @Test
    fun notConnectedLinksAreReconnectingEvenWithASendingPending() {
        val pending = listOf(PendingLite(sentAtMs = 1000L, state = SendLite.Sending))
        for (link in listOf(EngineLink.NoNetwork, EngineLink.Connecting, EngineLink.Reconnecting)) {
            assertEquals(
                "link $link",
                SessionChrome.Banner.Reconnecting,
                deriveBanner(BannerInputs(link = link, pendingSends = pending)),
            )
        }
    }

    /** Row: Connected, SENDING@1000, workingSince null / 900 / 1000 / 1001 → Sending ×3, None. */
    @Test
    fun sendingBridgesUntilATurnStartsAfterTheSend() {
        val pending = listOf(PendingLite(sentAtMs = 1000L, state = SendLite.Sending))
        for (turn in listOf<Long?>(null, 900L, 1000L)) {
            assertEquals(
                "workingSince $turn",
                SessionChrome.Banner.Sending,
                deriveBanner(BannerInputs(link = EngineLink.Connected, pendingSends = pending, workingSinceMs = turn)),
            )
        }
        assertEquals(
            SessionChrome.Banner.None,
            deriveBanner(
                BannerInputs(link = EngineLink.Connected, pendingSends = pending, workingSinceMs = 1001L),
            ),
        )
    }

    /** Row: [QUEUED@500, SENDING@1000], workingSince 800 → Sending (the first SENDING send is 1000). */
    @Test
    fun theFirstSendingPendingSendBridgesTheBanner() {
        assertEquals(
            SessionChrome.Banner.Sending,
            deriveBanner(
                BannerInputs(
                    link = EngineLink.Connected,
                    pendingSends = listOf(
                        PendingLite(sentAtMs = 500L, state = SendLite.Queued),
                        PendingLite(sentAtMs = 1000L, state = SendLite.Sending),
                    ),
                    workingSinceMs = 800L,
                ),
            ),
        )
    }

    /** Row: [SENDING@1000, SENDING@2000], workingSince 1500 → None (the first SENDING send, 1000, is older than the turn). */
    @Test
    fun theFirstSendingSendIsTheOneThatMatters() {
        assertEquals(
            SessionChrome.Banner.None,
            deriveBanner(
                BannerInputs(
                    link = EngineLink.Connected,
                    pendingSends = listOf(
                        PendingLite(sentAtMs = 1000L, state = SendLite.Sending),
                        PendingLite(sentAtMs = 2000L, state = SendLite.Sending),
                    ),
                    workingSinceMs = 1500L,
                ),
            ),
        )
    }

    /** Row: Connected, no pending sends → None. */
    @Test
    fun connectedWithNoPendingSendsIsNone() {
        assertEquals(SessionChrome.Banner.None, deriveBanner(BannerInputs(link = EngineLink.Connected)))
    }

    /** Working is the transcript tail's and Editing is a display overlay: never derived, for any inputs. */
    @Test
    fun deriveBannerNeverReturnsEditingOrWorking() {
        val failures = listOf(null, "Couldn't send: x")
        val settings = listOf(null, "Couldn't change session settings: y")
        val sendStates = listOf<SendLite?>(null, SendLite.Sending, SendLite.Queued, SendLite.Failed)
        val links = listOf(
            EngineLink.Connected,
            EngineLink.Connecting,
            EngineLink.Reconnecting,
            EngineLink.NoNetwork,
            EngineLink.Missing,
            EngineLink.Parked(SessionChrome.ParkedReason.Revoked, "e1"),
            EngineLink.Parked(SessionChrome.ParkedReason.IdentityChanged, "e1"),
            EngineLink.Parked(SessionChrome.ParkedReason.Incompatible, "e1"),
        )
        val pendings = listOf(emptyList(), listOf(PendingLite(sentAtMs = 1000L, state = SendLite.Sending)))
        val workings = listOf<Long?>(null, 900L, 1000L, 1500L)
        var combinations = 0
        for (failure in failures) {
            for (setting in settings) {
                for (sendState in sendStates) {
                    for (link in links) {
                        for (pending in pendings) {
                            for (working in workings) {
                                val banner = deriveBanner(
                                    BannerInputs(
                                        sendFailure = failure,
                                        settingFailure = setting,
                                        sendState = sendState,
                                        link = link,
                                        pendingSends = pending,
                                        workingSinceMs = working,
                                    ),
                                )
                                assertTrue("Working is never derived: $banner", banner !is SessionChrome.Banner.Working)
                                assertTrue("Editing is never derived: $banner", banner != SessionChrome.Banner.Editing)
                                combinations++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep covered a real grid", combinations >= 1000)
    }

    // -------------------------------------------------------- shownBanner

    /** iOS :501 — the editing overlay overrides every chrome banner. */
    @Test
    fun shownBannerEditingOverridesEveryChromeBanner() {
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.Failed("x"), editing = true))
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.NotDelivered, editing = true))
        assertEquals(SessionChrome.Banner.Editing, shownBanner(SessionChrome.Banner.None, editing = true))
        assertEquals(SessionChrome.Banner.NotDelivered, shownBanner(SessionChrome.Banner.NotDelivered, editing = false))
    }

    // -------------------------------------------------------- bannerInputs

    /** Step 3: every EngineState variant, engine == null, order kept, fields passed through. */
    @Test
    fun bannerInputsMapsEveryEngineStateAndKeepsPendingOrder() {
        val engineId = "engine:v1:alpha"
        assertEquals(EngineLink.Missing, engineLinkOf(null))
        assertEquals(EngineLink.Connected, engineLinkOf(EngineState.Connected))
        assertEquals(EngineLink.Connecting, engineLinkOf(EngineState.Connecting))
        assertEquals(EngineLink.Reconnecting, engineLinkOf(EngineState.Reconnecting))
        assertEquals(EngineLink.NoNetwork, engineLinkOf(EngineState.Offline))
        assertEquals(
            EngineLink.Parked(SessionChrome.ParkedReason.Revoked, engineId),
            engineLinkOf(EngineState.Parked(ParkReason.REVOKED)),
        )
        assertEquals(
            EngineLink.Parked(SessionChrome.ParkedReason.IdentityChanged, engineId),
            engineLinkOf(EngineState.Parked(ParkReason.IDENTITY_CHANGED)),
        )
        assertEquals(
            EngineLink.Parked(SessionChrome.ParkedReason.Incompatible, engineId),
            engineLinkOf(EngineState.Parked(ParkReason.INCOMPATIBLE)),
        )

        val composer = composerState().copy(
            pendingSends = listOf(pending(SendState.SENDING, 1000L), pending(SendState.FAILED, 2000L)),
            sendState = SendState.FAILED,
            live = composerState().live.copy(workingSinceMs = 4321L),
        )
        val inputs = bannerInputs(
            ChromeInput(chatId = "chat-a", composer = composer, row = sessionRow(), engine = engineView()),
            sendFailure = "s",
            settingFailure = "t",
        )
        assertEquals(SendLite.Failed, inputs.sendState)
        assertEquals(EngineLink.Connected, inputs.link)
        assertEquals(
            listOf(PendingLite(sentAtMs = 1000L, state = SendLite.Sending), PendingLite(sentAtMs = 2000L, state = SendLite.Failed)),
            inputs.pendingSends,
        )
        assertEquals(4321L, inputs.workingSinceMs)
        assertEquals("s", inputs.sendFailure)
        assertEquals("t", inputs.settingFailure)

        // The sendState mapping, through the same seam: SENDING / QUEUED / null.
        assertEquals(SendLite.Sending, sendStateOf(SendState.SENDING))
        assertEquals(SendLite.Queued, sendStateOf(SendState.QUEUED))
        assertNull(sendStateOf(null))
    }

    // ----------------------------------------------------------- errorText

    /** The reason, never the generated message; simple names for blank/null messages. */
    @Test
    fun errorTextUsesTheReasonNeverTheGeneratedMessage() {
        assertEquals(
            "Engine is offline; reconnecting",
            errorText(CoreException.HostUnavailable("Engine is offline; reconnecting")),
        )
        assertEquals("nope", errorText(CoreException.NotFound("nope")))
        assertEquals("client is shut down", errorText(CoreException.Closed()))
        assertEquals("x", errorText(RuntimeException("x")))
        assertEquals("RuntimeException", errorText(RuntimeException("")))
        assertEquals("RuntimeException", errorText(RuntimeException()))
    }

    // ------------------------------------------- the filler: sticky failures

    @Test
    fun noteSendFailedIsStickyUntilTheNextSuccessfulSend() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }
        val input = connectedInput()
        val chrome = SessionChrome()

        banner.noteSendFailed(CoreException.HostUnavailable("Engine is offline; reconnecting"))
        assertEquals("one invalidation for the new failure", 1, invalidations)
        val failed = SessionChrome.Banner.Failed("Couldn't send: Engine is offline; reconnecting")
        assertEquals(failed, banner.fill(chrome, input).banner)
        // Three further fills keep it.
        repeat(3) { assertEquals(failed, banner.fill(chrome, input).banner) }

        banner.noteSendSucceeded()
        assertEquals("one invalidation for the clear", 2, invalidations)
        assertEquals(SessionChrome.Banner.None, banner.fill(chrome, input).banner)
        banner.noteSendSucceeded()
        assertEquals("a second no-op clear invalidates nothing", 2, invalidations)
    }

    @Test
    fun noteSettingFailedIsStickyUntilTheNextSuccessfulChange() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }
        val input = connectedInput()
        val chrome = SessionChrome()

        banner.noteSettingFailed(RuntimeException("unknown model"))
        assertEquals(1, invalidations)
        val failed = SessionChrome.Banner.Failed("Couldn't change session settings: unknown model")
        assertEquals(failed, banner.fill(chrome, input).banner)
        repeat(3) { assertEquals(failed, banner.fill(chrome, input).banner) }

        banner.noteSettingSucceeded()
        assertEquals(2, invalidations)
        assertEquals(SessionChrome.Banner.None, banner.fill(chrome, input).banner)
        banner.noteSettingSucceeded()
        assertEquals(2, invalidations)
    }

    /** A second failure with a different message replaces the text (and invalidates). */
    @Test
    fun aDifferentFailureReplacesTheText() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }
        banner.noteSendFailed(RuntimeException("one"))
        banner.noteSendFailed(RuntimeException("two"))
        assertEquals(2, invalidations)
        assertEquals(
            SessionChrome.Banner.Failed("Couldn't send: two"),
            banner.fill(SessionChrome(), connectedInput()).banner,
        )
    }

    @Test
    fun noteFailureMessageAndClearFailureMessageManageTheSendSlot() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }
        val input = connectedInput()

        banner.noteFailureMessage("m")
        assertEquals(SessionChrome.Banner.Failed("m"), banner.fill(SessionChrome(), input).banner)
        // A clearing for the wrong message leaves it.
        banner.clearFailureMessage("other")
        assertEquals(SessionChrome.Banner.Failed("m"), banner.fill(SessionChrome(), input).banner)
        // The matching message clears it (verbatim copy, no prefix).
        banner.clearFailureMessage("m")
        assertEquals(SessionChrome.Banner.None, banner.fill(SessionChrome(), input).banner)
        // And after a genuine send failure, the wrong message still leaves it.
        banner.noteSendFailed(RuntimeException("x"))
        banner.clearFailureMessage("m")
        assertEquals(
            SessionChrome.Banner.Failed("Couldn't send: x"),
            banner.fill(SessionChrome(), input).banner,
        )
    }

    // --------------------------------------------- the filler: tap dispatch

    @Test
    fun anEditingTapCancelsTheEdit() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var edits = 0
        banner.onEditingTap = { edits++ }
        banner.onTap(SessionChrome.Banner.Editing)
        assertEquals(1, edits)
    }

    @Test
    fun notDeliveredRetriesOnTheIoScopeAfterAConnectedFill() = runTest {
        var retries = 0
        val banner = StatusBanner(
            retryDelivery = { retries++ },
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }

        banner.fill(SessionChrome(), connectedInput()) // caches Connected
        banner.onTap(SessionChrome.Banner.NotDelivered)
        assertEquals("nothing ran before the dispatcher advanced", 0, retries)
        advanceUntilIdle()
        assertEquals(1, retries)
        assertEquals("the retry invalidated once", 1, invalidations)
    }

    @Test
    fun notDeliveredSkipsTheRetryWhenTheEngineIsNotConnected() = runTest {
        var retries = 0
        val banner = StatusBanner(
            retryDelivery = { retries++ },
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        var invalidations = 0
        banner.bind { invalidations++ }

        banner.fill(SessionChrome(), engineInput(EngineState.Reconnecting))
        banner.onTap(SessionChrome.Banner.NotDelivered)
        advanceUntilIdle()
        assertEquals(0, retries)
        assertEquals(0, invalidations)
    }

    @Test
    fun aThrowingRetryIsLoggedSwallowedAndStillInvalidates() = runTest {
        val logged = mutableListOf<Pair<String, Throwable>>()
        val banner = StatusBanner(
            retryDelivery = { throw RuntimeException("boom") },
            io = StandardTestDispatcher(testScheduler),
            logW = { message, error -> logged += message to error },
        )
        var invalidations = 0
        banner.bind { invalidations++ }

        banner.fill(SessionChrome(), connectedInput())
        banner.onTap(SessionChrome.Banner.NotDelivered)
        advanceUntilIdle() // must not crash the test
        assertEquals(1, logged.size)
        assertEquals("retryDelivery failed", logged.first().first)
        assertEquals("boom", logged.first().second.message)
        assertEquals(1, invalidations)
    }

    @Test
    fun anEngineParkedTapOpensThePairPage() = runTest {
        val banner = StatusBanner(
            retryDelivery = {},
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        val opened = mutableListOf<String>()
        banner.openPairPage = { replaceEngineId -> opened += replaceEngineId }
        banner.onTap(SessionChrome.Banner.EngineParked(SessionChrome.ParkedReason.Revoked, "e1"))
        assertEquals(listOf("e1"), opened)
    }

    @Test
    fun theNonActionableBannersDoNothing() = runTest {
        var retries = 0
        var edits = 0
        val opened = mutableListOf<String>()
        var invalidations = 0
        val banner = StatusBanner(
            retryDelivery = { retries++ },
            io = StandardTestDispatcher(testScheduler),
            logW = { _, _ -> },
        )
        banner.bind { invalidations++ }
        banner.onEditingTap = { edits++ }
        banner.openPairPage = { opened += it }
        banner.fill(SessionChrome(), connectedInput())

        for (shown in listOf(
            SessionChrome.Banner.Failed("x"),
            SessionChrome.Banner.EngineOff,
            SessionChrome.Banner.Reconnecting,
            SessionChrome.Banner.Sending,
            SessionChrome.Banner.Working(null, "w"),
        )) {
            banner.onTap(shown)
        }
        advanceUntilIdle()
        assertEquals(0, retries)
        assertEquals(0, edits)
        assertTrue(opened.isEmpty())
        assertEquals(0, invalidations)
    }

    // ------------------------------------------------------------- fixtures

    private fun connectedInput(): ChromeInput = engineInput(EngineState.Connected)

    private fun engineInput(state: EngineState): ChromeInput =
        ChromeInput(
            chatId = "chat-a",
            composer = composerState(),
            row = sessionRow(),
            engine = engineView().copy(state = state),
        )

    private fun engineLinkOf(state: EngineState?): EngineLink =
        bannerInputs(
            ChromeInput(chatId = "chat-a", composer = composerState(), row = sessionRow(), engine = state?.let { engineView().copy(state = it) }),
            sendFailure = null,
            settingFailure = null,
        ).link

    private fun sendStateOf(state: SendState?): SendLite? =
        bannerInputs(
            ChromeInput(
                chatId = "chat-a",
                composer = composerState().copy(sendState = state),
                row = sessionRow(),
                engine = engineView(),
            ),
            sendFailure = null,
            settingFailure = null,
        ).sendState

    private fun pending(state: SendState, sentAtMs: Long): PendingSend = PendingSend(
        messageId = "m-$sentAtMs",
        text = "t",
        visibleText = "t",
        images = emptyList(),
        kind = PendingKind.RUN,
        sentAtMs = sentAtMs,
        state = state,
    )
}

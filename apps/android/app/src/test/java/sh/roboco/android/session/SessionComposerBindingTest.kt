package sh.roboco.android.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.DeliveryMode
import sh.roboco.android.composer.SendResult
import sh.roboco.android.composer.StagedImage
import sh.roboco.android.composer.previewMorphState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import sh.roboco.android.shell.NavStackHost
import sh.roboco.android.transcript.RunwayHooks
import uniffi.roboco_core.BusyPolicy
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.ContextUsage
import uniffi.roboco_core.FileMatch
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SendOutcome
import uniffi.roboco_core.SendRequest
import uniffi.roboco_core.SessionHandle

/**
 * The session↔composer binding (android-ui-parity #78):
 *
 * - `ChipSource.from` over the FFI fixtures and the real FFI
 *   `reasoningLabel` (skipped when the host core library is missing);
 * - `busyPolicyFor` and [CoreSessionCommands] over recording fakes (the
 *   busy policy, the attachment mapping, `spaceId = null` and the
 *   host-device read at call time);
 * - [SessionComposerBinding.onSend]'s whole matrix: the queued/runway
 *   decision, the ordering (runway before the FFI, dismiss after it),
 *   the success and failure reporting and the queued-edit interception;
 * - [SessionComposerBinding.onStop] and `mentionSearch` (including
 *   cancellation propagation, ticket 86's stale-search rule);
 * - the real 73 [StatusBanner] integration: the sticky
 *   `Couldn't send: …` pill across refreshes until the next success;
 * - the composition half: the first-composition apply, later chrome
 *   changes, the placeholder override, the hide→blur rule and the
 *   nav-entry retention through a host disposal and a committed pop.
 *
 * Robolectric: `StagedImage` needs `ImageBitmap` and the chips tests use
 * the FFI records; the composition tests need the rule. `runTest` drives
 * the binding's io lane with a `StandardTestDispatcher` over the same
 * scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h900dp-xxhdpi")
class SessionComposerBindingTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackGlass() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ fakes

    /** Records the runway calls and holds the upload pin. */
    private class RecordingHooks(private val events: MutableList<String>? = null) : RunwayHooks {
        val calls = mutableListOf<String>()
        override var uploadProgress: Double? = null
        override fun beginOwnTurn() {
            calls += "beginOwnTurn"
            events?.add("beginOwnTurn")
        }
        override fun expectQueuedTurn() {
            calls += "expectQueuedTurn"
            events?.add("expectQueuedTurn")
        }
    }

    /** Records the reported send outcomes. */
    private class RecordingReporter : SendReporter {
        var successes = 0
        val failures = mutableListOf<Throwable>()
        override fun noteSendSucceeded() {
            successes++
        }
        override fun noteSendFailed(error: Throwable) {
            failures += error
        }
    }

    /** The fake [QueuedEditGate]: records every intercepted body. */
    private class FakeQueuedEditGate(
        override val placeholderOverride: String?,
        private val result: SendResult?,
    ) : QueuedEditGate {
        val intercepted = mutableListOf<String>()
        override fun interceptSend(body: String): SendResult? {
            intercepted += body
            return result
        }
    }

    /** The fake [SessionCommands] with optional gates and failures. */
    private class FakeCommands(
        private val events: MutableList<String>? = null,
        private val sendGate: CompletableDeferred<Unit>? = null,
        private val failure: Throwable? = null,
        private val interruptFailure: Throwable? = null,
        private val searchGate: CompletableDeferred<Unit>? = null,
        private val searchFailure: Throwable? = null,
        private val searchResult: List<FileMatch> = emptyList(),
    ) : SessionCommands {
        var sendCalls = 0
            private set
        var interruptCalls = 0
            private set
        val sendModes = mutableListOf<DeliveryMode>()
        val sendTexts = mutableListOf<String>()
        val sendImageCounts = mutableListOf<Int>()

        override suspend fun send(text: String, images: List<StagedImage>, mode: DeliveryMode) {
            sendCalls++
            sendModes += mode
            sendTexts += text
            sendImageCounts += images.size
            events?.add("send-start")
            sendGate?.await()
            events?.add("send-end")
            failure?.let { throw it }
        }

        override suspend fun interrupt() {
            interruptCalls++
            events?.add("interrupt")
            interruptFailure?.let { throw it }
        }

        override suspend fun searchFiles(query: String): List<FileMatch> {
            searchGate?.await()
            searchFailure?.let { throw it }
            return searchResult
        }
    }

    /** A recording [CoreClient] fake (no Rust object behind it). */
    private class RecordingClient : CoreClient(NoHandle) {
        val searches = mutableListOf<String>()
        override suspend fun searchFiles(deviceId: String, chatId: String?, spaceId: String?, query: String): List<FileMatch> {
            searches += listOf(deviceId, chatId ?: "null", spaceId ?: "null", query).joinToString("|")
            return listOf(FileMatch(path = "src/a.rs", isDir = false))
        }
    }

    /** A recording [SessionHandle] fake (no Rust object behind it). */
    private class RecordingHandle : SessionHandle(NoHandle) {
        val sent = mutableListOf<SendRequest>()
        var interrupts = 0
        override suspend fun send(request: SendRequest): SendOutcome {
            sent += request
            return SendOutcome.Started(messageId = "echo-1")
        }
        override suspend fun interrupt() {
            interrupts++
        }
    }

    private fun image(id: String): StagedImage = StagedImage(id, "$id.png", byteArrayOf(1, 2, 3), ImageBitmap(1, 1))

    /** The binding under test, off composition: a preview morph and the test's io lane. */
    private fun TestScope.binding(
        controller: ComposerController = ComposerController(this, NoFeedback),
        commands: SessionCommands,
        transcript: RunwayHooks = RecordingHooks(),
        banner: SendReporter = RecordingReporter(),
        dismiss: MutableList<String>? = null,
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        log: (String, Throwable) -> Unit = { _, _ -> },
    ): SessionComposerBinding =
        SessionComposerBinding(controller, previewMorphState(0f, card = false), commands, transcript, banner, this, io, log)
            .also { b -> if (dismiss != null) b.dismissKeyboard = { dismiss.add("dismiss") } }

    // ------------------------------------------------- ChipSource / FFI

    @Test
    fun chipSourceFromMapsTheRowAndTheComposer() {
        val row = sessionRow(
            harness = "codex-cli",
            harnessLabel = "Codex",
            modelLabel = "GPT-5.2",
            reasoning = "medium",
            branch = "main",
            pullRequest = pullRequest(77, PullRequestState.MERGED),
        )
        val composer = composerState(contextUsage = ContextUsage(tokens = 845uL, window = 1000uL))
        val source = ChipSource.from(row, composer)
        assertEquals("codex-cli", source.harness)
        assertEquals("Codex", source.harnessLabel)
        assertEquals("GPT-5.2", source.modelLabel)
        assertEquals("medium", source.reasoning)
        assertEquals("main", source.branch)
        assertEquals(77uL, source.prNumber)
        assertEquals(ChipTint.PrMerged, source.prTint)
        assertEquals(845uL, source.tokens)
        assertEquals(1000uL, source.window)

        val chips = sessionChips(source) { level -> level.replaceFirstChar(Char::uppercase) }
        assertEquals(listOf("model", "effort", "pr", "context"), chips.map { it.id })
        assertEquals("77", chips[2].title)
        assertEquals("85% context", chips[3].title)

        // A null row leaves only the context chip's inputs.
        val bare = ChipSource.from(null, composer)
        assertEquals(null, bare.harness)
        assertEquals(845uL, bare.tokens)
    }

    @Test
    fun theRealFfiLabelsXhighAsXHigh() {
        assumeTrue("host core library missing", runCatching { uniffi.roboco_core.coreVersion() }.isSuccess)
        assertEquals("X-High", uniffi.roboco_core.reasoningLabel("xhigh"))
        assertEquals("", uniffi.roboco_core.reasoningLabel(""))
        assertEquals("Custom", uniffi.roboco_core.reasoningLabel("custom"))
    }

    // ------------------------------------------------- busyPolicy / core commands

    @Test
    fun busyPolicyForMapsEveryDeliveryMode() {
        assertEquals(BusyPolicy.QUEUE, busyPolicyFor(DeliveryMode.Queue))
        assertEquals(BusyPolicy.STEER, busyPolicyFor(DeliveryMode.Steer))
        assertEquals(BusyPolicy.INTERRUPT, busyPolicyFor(DeliveryMode.Interrupt))
    }

    @Test
    fun coreCommandsSendMapsModeImagesAndWorktree() = runTest {
        val handle = RecordingHandle()
        val commands = CoreSessionCommands(RecordingClient(), handle, "chat-a") { "dev-1" }
        commands.send("hello @src", listOf(image("i1")), DeliveryMode.Interrupt)
        val sent = handle.sent.single()
        assertEquals("hello @src", sent.text)
        assertEquals(null, sent.worktree)
        assertEquals(BusyPolicy.INTERRUPT, sent.busy)
        val attachment = sent.attachments.single()
        assertEquals("i1.png", attachment.name)
        assertEquals("image/jpeg", attachment.mimeType)
        assertArrayEquals(byteArrayOf(1, 2, 3), attachment.data)
        assertEquals(0, handle.interrupts)
    }

    @Test
    fun coreCommandsSearchFilesPassesTheHostDeviceAndANullSpace() = runTest {
        val client = RecordingClient()
        val commands = CoreSessionCommands(client, RecordingHandle(), "chat-a") { "dev-1" }
        commands.searchFiles("src")
        commands.searchFiles("main")
        assertEquals(listOf("dev-1|chat-a|null|src", "dev-1|chat-a|null|main"), client.searches)
    }

    @Test
    fun coreCommandsSearchFilesReadsTheHostDeviceAtCallTime() = runTest {
        val client = RecordingClient()
        var device = "dev-1"
        val commands = CoreSessionCommands(client, RecordingHandle(), "chat-a") { device }
        commands.searchFiles("src")
        device = "dev-2"
        commands.searchFiles("src")
        assertEquals(listOf("dev-1|chat-a|null|src", "dev-2|chat-a|null|src"), client.searches)
    }

    // ------------------------------------------------- onSend

    @Test
    fun aQueueSendWhileIdleRunsItsOwnTurn() = runTest {
        val commands = FakeCommands()
        val transcript = RecordingHooks()
        val controller = ComposerController(this, NoFeedback)
        controller.running = false
        val result = binding(controller, commands, transcript).onSend("hi", emptyList(), DeliveryMode.Queue)
        assertEquals(SendResult.Sent, result)
        assertEquals(1, commands.sendCalls)
        assertEquals(DeliveryMode.Queue, commands.sendModes.single())
        assertEquals(listOf("beginOwnTurn"), transcript.calls)
    }

    @Test
    fun aQueueSendWhileRunningExpectsAQueuedTurn() = runTest {
        val commands = FakeCommands()
        val transcript = RecordingHooks()
        val controller = ComposerController(this, NoFeedback)
        controller.running = true
        val result = binding(controller, commands, transcript).onSend("hi", emptyList(), DeliveryMode.Queue)
        assertEquals(SendResult.Sent, result)
        assertEquals(listOf("expectQueuedTurn"), transcript.calls)
    }

    @Test
    fun steerAndInterruptWhileRunningEachBeginTheirOwnTurn() = runTest {
        for (mode in listOf(DeliveryMode.Steer, DeliveryMode.Interrupt)) {
            val commands = FakeCommands()
            val transcript = RecordingHooks()
            val controller = ComposerController(this, NoFeedback)
            controller.running = true
            val result = binding(controller, commands, transcript).onSend("hi", emptyList(), mode)
            assertEquals(SendResult.Sent, result)
            assertEquals("steer and stop-and-send are immediate sends ($mode)", listOf("beginOwnTurn"), transcript.calls)
            assertEquals("interrupt is never a separate call ($mode)", 0, commands.interruptCalls)
        }
    }

    @Test
    fun onSendNeverInterruptsSeparately() = runTest {
        val commands = FakeCommands()
        val controller = ComposerController(this, NoFeedback)
        controller.running = true
        binding(controller, commands).onSend("hi", emptyList(), DeliveryMode.Interrupt)
        advanceUntilIdle()
        assertEquals(0, commands.interruptCalls)
        assertEquals(1, commands.sendCalls)
    }

    @Test
    fun theRunwayRunsBeforeTheSendAndTheDismissAfterIt() = runTest {
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val commands = FakeCommands(events, sendGate = gate)
        val transcript = RecordingHooks(events)
        val dismiss = mutableListOf<String>()
        val b = binding(commands = commands, transcript = transcript, dismiss = dismiss)
        val job = launch { b.onSend("hi", emptyList(), DeliveryMode.Queue) }
        advanceUntilIdle()
        // Suspended at the gate: the runway already ran, the send started.
        assertEquals(listOf("beginOwnTurn", "send-start"), events)
        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("beginOwnTurn", "send-start", "send-end"), events)
        assertEquals(listOf("dismiss"), dismiss)
    }

    @Test
    fun aSuccessfulSendReportsClearsAndDismisses() = runTest {
        val commands = FakeCommands()
        val reporter = RecordingReporter()
        val dismiss = mutableListOf<String>()
        val controller = ComposerController(this, NoFeedback)
        controller.text = "hello"
        controller.addImages(listOf(image("i1")))
        controller.onSend = binding(controller, commands, banner = reporter, dismiss = dismiss)::onSend
        val job = controller.send(DeliveryMode.Queue)
        advanceUntilIdle()
        job?.join()
        assertEquals(1, reporter.successes)
        assertTrue(reporter.failures.isEmpty())
        assertEquals(listOf("dismiss"), dismiss)
        assertEquals(1, commands.sendCalls)
        assertEquals("hello", commands.sendTexts.single())
        assertEquals(1, commands.sendImageCounts.single())
        assertEquals("54 clears the text", "", controller.text)
        assertTrue("54 clears the sent images", controller.images.isEmpty())
    }

    @Test
    fun aFailedSendKeepsTheDraftAndReportsToTheBanner() = runTest {
        val failure = IllegalStateException("Engine is offline; reconnecting")
        val commands = FakeCommands(failure = failure)
        val reporter = RecordingReporter()
        val dismiss = mutableListOf<String>()
        val controller = ComposerController(this, NoFeedback)
        controller.text = "keep me"
        controller.addImages(listOf(image("i1")))
        val result = binding(controller, commands, banner = reporter, dismiss = dismiss)
            .onSend("keep me", listOf(image("i1")), DeliveryMode.Queue)
        assertEquals(SendResult.Kept, result)
        // Compared by type and message, not identity: rethrown out of
        // withContext, kotlinx.coroutines' stack-trace recovery (debug mode,
        // on under -ea) hands the catch a copy of the exception.
        val reported = reporter.failures.single()
        assertEquals(failure.javaClass, reported.javaClass)
        assertEquals(failure.message, reported.message)
        assertEquals(0, reporter.successes)
        assertTrue(dismiss.isEmpty())
        assertEquals("keep me", controller.text)
        assertEquals(1, controller.images.size)
    }

    @Test
    fun anOpenQueuedEditCommitsInsteadOfSending() = runTest {
        val commands = FakeCommands()
        val transcript = RecordingHooks()
        val reporter = RecordingReporter()
        val dismiss = mutableListOf<String>()
        val controller = ComposerController(this, NoFeedback)
        val gate = FakeQueuedEditGate(placeholderOverride = "Edit queued message", result = SendResult.Replaced)
        val b = binding(controller, commands, transcript, reporter, dismiss)
        b.queueEdit = gate
        val result = b.onSend("edited body", emptyList(), DeliveryMode.Queue)
        assertEquals(SendResult.Replaced, result)
        assertEquals(listOf("edited body"), gate.intercepted)
        assertEquals(0, commands.sendCalls)
        assertTrue(transcript.calls.isEmpty())
        assertTrue(dismiss.isEmpty())
        assertEquals(0, reporter.successes)
        assertTrue(reporter.failures.isEmpty())
    }

    @Test
    fun interceptSendRunsOnEverySendAndNullFallsThrough() = runTest {
        val commands = FakeCommands()
        val gate = FakeQueuedEditGate(placeholderOverride = null, result = null)
        val b = binding(commands = commands)
        b.queueEdit = gate
        assertEquals(SendResult.Sent, b.onSend("hi", emptyList(), DeliveryMode.Queue))
        assertEquals(listOf("hi"), gate.intercepted)
        assertEquals(1, commands.sendCalls)
    }

    // ------------------------------------------------- onStop / mentionSearch

    @Test
    fun onStopInterruptsOnce() = runTest {
        val commands = FakeCommands()
        val b = binding(commands = commands)
        b.onStop()
        advanceUntilIdle()
        assertEquals(1, commands.interruptCalls)
    }

    @Test
    fun aThrowingInterruptIsLoggedAndSwallowed() = runTest {
        val commands = FakeCommands(interruptFailure = IllegalStateException("boom"))
        val logged = mutableListOf<String>()
        val b = binding(
            commands = commands,
            io = StandardTestDispatcher(testScheduler),
            log = { message, _ -> logged.add(message) },
        )
        b.onStop()
        advanceUntilIdle()
        assertEquals(1, commands.interruptCalls)
        assertEquals(listOf("interrupt failed"), logged)
    }

    @Test
    fun mentionSearchReturnsTheFakeResults() = runTest {
        val matches = listOf(FileMatch(path = "src/main.rs", isDir = false))
        val commands = FakeCommands(searchResult = matches)
        assertEquals(matches, binding(commands = commands).mentionSearch("src"))
    }

    @Test
    fun aThrowingSearchYieldsAnEmptyList() = runTest {
        val commands = FakeCommands(searchFailure = IllegalStateException("offline"))
        assertEquals(emptyList<FileMatch>(), binding(commands = commands).mentionSearch("q"))
    }

    @Test
    fun aCancelledCallerSeesTheCancellationException() = runTest {
        val gate = CompletableDeferred<Unit>()
        val commands = FakeCommands(searchGate = gate)
        val b = binding(commands = commands)
        var thrown: Throwable? = null
        val job = launch {
            try {
                b.mentionSearch("q")
            } catch (cancelled: CancellationException) {
                thrown = cancelled
                throw cancelled
            }
        }
        advanceUntilIdle() // the search is suspended inside the gate
        job.cancel()
        advanceUntilIdle()
        assertTrue("cancellation propagated, got $thrown", thrown is CancellationException)
    }

    // ------------------------------------------------- the real 73 integration

    @Test
    fun theRealPillShowsTheSendFailureAcrossRefreshesUntilTheNextSuccess() = runTest {
        val core = FakeSessionCore()
        core.engineResult = engineView()
        core.composerResult = composerState(chatId = "chat-a")
        core.sessionRowResult = sessionRow(harness = "codex-cli", harnessLabel = "Codex", reasoning = "high")
        val banner = StatusBanner(retryDelivery = {}, io = StandardTestDispatcher(testScheduler))
        val controller = SessionController.create(
            core = core,
            hub = SessionEventHub(post = { it.run() }),
            fillers = listOf(
                // Named: since android-next #01 the filler's last parameter is
                // `locale: () -> Locale`, so a trailing lambda would bind there.
                SessionChipsFiller(reasoningLabel = { level -> level.replaceFirstChar(Char::uppercase) }),
                banner,
            ),
            io = StandardTestDispatcher(testScheduler),
            isMainThread = { false },
        )
        assertEquals(listOf("model", "effort"), controller.chrome.value.chips.map { it.id })

        val composer = ComposerController(this, NoFeedback)
        val failing = FakeCommands(failure = CoreException.HostUnavailable("Engine is offline; reconnecting"))
        val failingBinding = SessionComposerBinding(
            composer, previewMorphState(0f, card = false), failing, RecordingHooks(), banner, this,
            io = StandardTestDispatcher(testScheduler),
        )
        assertEquals(SendResult.Kept, failingBinding.onSend("hi", emptyList(), DeliveryMode.Queue))
        advanceUntilIdle()
        assertEquals(
            SessionChrome.Banner.Failed("Couldn't send: Engine is offline; reconnecting"),
            controller.chrome.value.banner,
        )

        // Further refreshes keep it (the sticky rule).
        controller.invalidate()
        advanceUntilIdle()
        assertEquals(
            SessionChrome.Banner.Failed("Couldn't send: Engine is offline; reconnecting"),
            controller.chrome.value.banner,
        )

        // A following successful send clears it.
        val succeeding = FakeCommands()
        val succeedingBinding = SessionComposerBinding(
            composer, previewMorphState(0f, card = false), succeeding, RecordingHooks(), banner, this,
            io = StandardTestDispatcher(testScheduler),
        )
        assertEquals(SendResult.Sent, succeedingBinding.onSend("hi", emptyList(), DeliveryMode.Queue))
        advanceUntilIdle()
        assertEquals(SessionChrome.Banner.None, controller.chrome.value.banner)
    }

    // ------------------------------------------------- composition: apply

    @Test
    fun theFirstCompositionAppliesTheChromeBeforeTheClockAdvances() {
        var controller: ComposerController? = null
        val hooks = RecordingHooks()
        val chrome = SessionChrome(
            running = true,
            canSteer = true,
            canWrite = false,
            placeholder = "Message Codex",
            chips = listOf(ComposerChip("model", "Codex")),
            uploadProgress = 0.42,
        )
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val b = rememberSessionComposer("chat-a", chrome, FakeCommands(), hooks, RecordingReporter())
                controller = b.composer
            }
        }
        compose.waitForIdle()
        val c = controller ?: error("no controller")
        assertEquals(true, c.running)
        assertEquals(true, c.canSteer)
        assertEquals(false, c.canWrite)
        assertEquals("Message Codex", c.placeholder)
        assertEquals(chrome.chips, c.chips)
        assertEquals(0.42, hooks.uploadProgress!!, 1e-9)
    }

    @Test
    fun aChromeChangeUpdatesTheControllerAndTheUploadPin() {
        var controller: ComposerController? = null
        val hooks = RecordingHooks()
        var chrome by mutableStateOf(SessionChrome(running = false, uploadProgress = 0.42))
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val b = rememberSessionComposer("chat-a", chrome, FakeCommands(), hooks, RecordingReporter())
                controller = b.composer
            }
        }
        compose.waitForIdle()
        val c = controller ?: error("no controller")
        assertEquals(false, c.running)
        assertEquals(0.42, hooks.uploadProgress!!, 1e-9)

        writeOnUi { chrome = chrome.copy(running = true, uploadProgress = 0.8) }
        compose.waitForIdle()
        assertEquals(true, c.running)
        assertEquals(0.8, hooks.uploadProgress!!, 1e-9)

        writeOnUi { chrome = chrome.copy(uploadProgress = null) }
        compose.waitForIdle()
        assertEquals(null, hooks.uploadProgress)
    }

    @Test
    fun theQueuedEditOverrideBeatsTheChromePlaceholder() {
        var controller: ComposerController? = null
        var bindingOut: SessionComposerBinding? = null
        var chrome by mutableStateOf(SessionChrome(placeholder = "Message Codex"))
        // Stable fakes: the no-entry binding is keyed on them, so fresh
        // ones per recomposition would drop the queueEdit set below.
        val commands = FakeCommands()
        val hooks = RecordingHooks()
        val reporter = RecordingReporter()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val b = rememberSessionComposer("chat-a", chrome, commands, hooks, reporter)
                bindingOut = b
                controller = b.composer
            }
        }
        compose.waitForIdle()
        val c = controller ?: error("no controller")
        assertEquals("Message Codex", c.placeholder)

        // An open edit (83) overrides the placeholder on every chrome
        // change — the iOS wart this port does not copy.
        writeOnUi {
            bindingOut?.queueEdit = FakeQueuedEditGate("Edit queued message", result = null)
            chrome = chrome.copy(placeholder = "Message Claude Code")
        }
        compose.waitForIdle()
        assertEquals("Edit queued message", c.placeholder)

        writeOnUi {
            bindingOut?.queueEdit = null
            chrome = chrome.copy(placeholder = "Message Codex")
        }
        compose.waitForIdle()
        assertEquals("Message Codex", c.placeholder)
    }

    // ------------------------------------------------- composition: hide → blur

    @Test
    fun hidingTheSlotBlursTheFocusedComposerAndKeepsTheText() {
        var bindingOut: SessionComposerBinding? = null
        var shown by mutableStateOf(true)
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val b = rememberSessionComposer("chat-a", SessionChrome(), FakeCommands(), RecordingHooks(), RecordingReporter())
                bindingOut = b
                GlassMenuHost {
                    SessionComposerSlot(b, shown)
                }
            }
        }
        compose.waitForIdle()
        val binding = bindingOut ?: error("no binding")
        writeOnUi { binding.composer.text = "draft" }

        compose.runOnUiThread { binding.morph.focus() }
        compose.mainClock.advanceTimeBy(700)
        compose.waitForIdle()
        assertTrue("the fixture focused the field", binding.morph.isFocused)

        // A question opens: the slot hides, the composer blurs, the text stays.
        writeOnUi { shown = false }
        compose.waitForIdle()
        assertFalse(binding.morph.isFocused)
        assertEquals("draft", binding.composer.text)
    }

    // ------------------------------------------------- composition: retention

    /** The plain root of the retention stack. */
    private object RootRoute : NavRoute

    /** A route whose content runs [rememberSessionComposer] and records the controller. */
    private class ProbeRoute(
        val chatId: String,
        val commands: SessionCommands,
    ) : NavRoute

    private val captured = mutableMapOf<ProbeRoute, ComposerController>()

    @Test
    fun theControllerSurvivesAHostDisposalAndDiesOnCommittedPop() {
        val stack = NavStack(RootRoute)
        // Snapshot state, so flipping it really disposes and re-adds the host.
        var hostOn by mutableStateOf(true)
        val commands = FakeCommands()
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                if (hostOn) {
                    NavStackHost(stack, active = true) { entry ->
                        val route = entry.route
                        if (route is ProbeRoute) {
                            val b = rememberSessionComposer(
                                chatId = route.chatId,
                                chrome = SessionChrome(),
                                commands = route.commands,
                                transcript = RecordingHooks(),
                                banner = RecordingReporter(),
                            )
                            captured[route] = b.composer
                        } else {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)

        val first = ProbeRoute("chat-a", commands)
        compose.runOnUiThread { stack.push(first, animated = false) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        val controller = captured[first] ?: error("no controller")
        compose.runOnUiThread { controller.text = "draft" }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()

        // A tab switch: the host is disposed and re-added; the nav entry
        // keeps the controller and its text.
        compose.runOnUiThread { hostOn = false }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { hostOn = true }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        assertSame(controller, captured[first])
        assertEquals("draft", controller.text)

        // A committed pop closes the entry; the next push of the same
        // chat gets a fresh controller with a fresh draft.
        compose.runOnUiThread { stack.pop() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil { stack.transition == null }
        compose.waitForIdle()

        val second = ProbeRoute("chat-a", commands)
        compose.runOnUiThread { stack.push(second, animated = false) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        val next = captured[second] ?: error("no fresh controller")
        assertNotSame(controller, next)
        assertEquals("", next.text)
    }

    // ------------------------------------------------------------ helpers

    /**
     * A state write from the test, delivered to the Recomposer: under
     * Robolectric `runOnUiThread` runs inline, and a write made outside
     * composition only reaches the Recomposer through
     * `Snapshot.sendApplyNotifications()` — the frozen clock never idles
     * the paused main Looper on its own.
     */
    /** A UI-thread write plus the frame that applies it (the clock is frozen). */
    private fun writeOnUi(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }
}

package sh.roboco.android.composer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import uniffi.roboco_core.FileMatch

/**
 * [ComposerController] (android-ui-parity #54): every acceptance row of the
 * ticket — the [ComposerController.deriveAction] truth table, the
 * [ComposerController.routeQueue] routing table, the image cap, the
 * [ComposerController.primaryAction] / [send] behaviour across all three
 * [SendResult]s (including the throw and the in-flight adaptations), the
 * haptic ordering and the `canWrite` gate.
 *
 * Robolectric (android-app#13): the suite constructs `uniffi.roboco_core.FileMatch`
 * records and `ImageBitmap(1, 1)`s. Nothing calls into the native core — the
 * mention index is built with the ticket's fake `link` (the real
 * `fileMentionLink` output is 53's `MentionLinkFfiTest`), so the tests need
 * only the generated bindings. `runTest` passes its `TestScope` as the
 * controller's scope; `send`'s `UNDISPATCHED` launch makes non-suspending
 * handlers complete inside the call, exactly like iOS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComposerControllerTest {

    /** Haptics and onSend calls, one shared list so the order is comparable. */
    private val events = mutableListOf<String>()

    private val feedback = RecordingFeedback(events)

    private fun image(id: String): StagedImage =
        StagedImage(id, "$id.png", byteArrayOf(1), ImageBitmap(1, 1))

    /** A controller over this test's [feedback] and scope, with the ticket's fake mention link. */
    private fun TestScope.controller(
        initialText: String = "",
        onSend: (suspend (String, List<StagedImage>, DeliveryMode) -> SendResult)? = null,
    ): ComposerController {
        val controller = ComposerController(
            scope = this,
            feedback = feedback,
            initialText = initialText,
            mentions = MentionIndex(link = { p, _ -> "[${p.substringAfterLast('/')}](roboco-file:$p)" }),
        )
        controller.onSend = onSend
        return controller
    }

    /** A handler that records the call into [events] and returns [result]. */
    private fun sendHandler(
        result: SendResult = SendResult.Sent,
        onCall: (SendCall) -> Unit = {},
    ): (suspend (String, List<StagedImage>, DeliveryMode) -> SendResult) = { body, images, mode ->
        events += "onSend"
        onCall(SendCall(body, images.map { it.id }, mode))
        result
    }

    /** One routed primaryAction in the Queue state: the mode onSend received. */
    private fun TestScope.routed(preferred: DeliveryMode, canSteer: Boolean): DeliveryMode {
        var mode: DeliveryMode? = null
        val c = controller(onSend = sendHandler { mode = it.mode })
        c.text = "hi"
        c.running = true
        c.preferredDelivery = preferred
        c.canSteer = canSteer
        c.primaryAction()
        return mode ?: error("onSend was not called")
    }

    // ── deriveAction / routeQueue (pure functions) ──────────────────────────

    @Test
    fun deriveActionMatchesEveryRowOfTheTruthTable() {
        val rows = listOf(
            // running, hasContent, canWrite, canSteer, preferred → action, enabled, steering, deliveryMenu, label
            Row(false, false, true, false, DeliveryMode.Queue, ComposerAction.Send, false, false, false, "Send message"),
            Row(false, true, true, false, DeliveryMode.Queue, ComposerAction.Send, true, false, false, "Send message"),
            Row(true, false, true, false, DeliveryMode.Queue, ComposerAction.Stop, true, false, false, "Stop response"),
            Row(true, true, true, false, DeliveryMode.Queue, ComposerAction.Queue, true, false, true, "Queue message"),
            Row(true, true, true, true, DeliveryMode.Steer, ComposerAction.Queue, true, true, true, "Steer"),
            Row(true, true, true, false, DeliveryMode.Steer, ComposerAction.Queue, true, false, true, "Queue message"),
            Row(false, true, false, false, DeliveryMode.Queue, ComposerAction.Send, false, false, false, "Send message"),
            Row(true, false, false, false, DeliveryMode.Queue, ComposerAction.Stop, false, false, false, "Stop response"),
            Row(true, true, false, false, DeliveryMode.Queue, ComposerAction.Queue, false, false, false, "Queue message"),
        )
        for (row in rows) {
            val state = ComposerController.deriveAction(
                row.running, row.hasContent, row.canWrite, row.canSteer, row.preferred,
            )
            assertEquals("action $row", row.action, state.action)
            assertEquals("enabled $row", row.enabled, state.enabled)
            assertEquals("steering $row", row.steering, state.steering)
            assertEquals("deliveryMenu $row", row.deliveryMenu, state.deliveryMenu)
            assertEquals("label $row", row.label, state.accessibilityLabel)
        }
    }

    @Test
    fun routeQueueMatchesTheRoutingTable() {
        assertEquals(DeliveryMode.Queue, ComposerController.routeQueue(DeliveryMode.Queue, canSteer = true))
        assertEquals(DeliveryMode.Queue, ComposerController.routeQueue(DeliveryMode.Queue, canSteer = false))
        assertEquals(DeliveryMode.Steer, ComposerController.routeQueue(DeliveryMode.Steer, canSteer = true))
        assertEquals(DeliveryMode.Queue, ComposerController.routeQueue(DeliveryMode.Steer, canSteer = false))
        assertEquals(DeliveryMode.Interrupt, ComposerController.routeQueue(DeliveryMode.Interrupt, canSteer = true))
        assertEquals(DeliveryMode.Interrupt, ComposerController.routeQueue(DeliveryMode.Interrupt, canSteer = false))
    }

    // ── content ─────────────────────────────────────────────────────────────

    @Test
    fun initialTextSeedsValueWithTheCaretAtTheEnd() = runTest {
        val c = controller(initialText = "draft")
        assertEquals("draft", c.text)
        assertEquals(TextFieldValue("draft", TextRange(5)), c.value)
        assertTrue(c.hasContent)
    }

    @Test
    fun hasContentFollowsTheTicketRules() = runTest {
        val c = controller()
        assertFalse(c.hasContent)
        c.text = "   \n\t"
        assertFalse(c.hasContent)
        c.text = " a "
        assertTrue(c.hasContent)
        c.text = ""
        c.addImages(listOf(image("a")))
        assertTrue(c.hasContent)
    }

    @Test
    fun addImagesCapsAtEightWhileADirectSetDoesNot() = runTest {
        val c = controller()
        val expected = (1..5).map { "old$it" } + listOf("new1", "new2", "new3")
        c.addImages((1..5).map { image("old$it") })
        c.addImages((1..6).map { image("new$it") })
        // 5 staged + 6 new: keeps 8, the first 3 of the new batch.
        assertEquals(expected, c.images.map { it.id })

        c.addImages(listOf(image("late"))) // 8 staged: adds none
        assertEquals(expected, c.images.map { it.id })

        c.removeImage("old3")
        assertEquals(expected.filterNot { it == "old3" }, c.images.map { it.id })

        c.clearImages()
        assertTrue(c.images.isEmpty())

        // A direct set has no cap — the queue-edit stash restore's path.
        c.images = (1..10).map { image("d$it") }
        assertEquals(10, c.images.size)
    }

    // ── primaryAction ───────────────────────────────────────────────────────

    @Test
    fun primaryActionInStopStateFiresStopThenOnStopWithoutSending() = runTest {
        var stops = 0
        val c = controller(onSend = { _, _, _ -> error("onSend must not run in Stop") })
        c.onStop = {
            stops++
            events += "onStop"
        }
        c.running = true // running + no content ⇒ Stop
        c.primaryAction()
        assertEquals(listOf("haptic:Stop", "onStop"), events)
        assertEquals(1, stops)
    }

    @Test
    fun primaryActionInSendStateSendsWithQueueMode() = runTest {
        val calls = mutableListOf<SendCall>()
        val c = controller(onSend = sendHandler { calls += it })
        c.text = "hi"
        c.primaryAction()
        assertEquals(listOf(SendCall("hi", emptyList(), DeliveryMode.Queue)), calls)
        assertEquals(listOf("haptic:Confirm", "onSend"), events)
        assertEquals("", c.text) // an idle send is always taken
    }

    @Test
    fun primaryActionInQueueStateRoutesDelivery() = runTest {
        assertEquals(DeliveryMode.Queue, routed(DeliveryMode.Queue, canSteer = true))
        assertEquals(DeliveryMode.Queue, routed(DeliveryMode.Queue, canSteer = false))
        assertEquals(DeliveryMode.Steer, routed(DeliveryMode.Steer, canSteer = true))
        assertEquals(DeliveryMode.Queue, routed(DeliveryMode.Steer, canSteer = false))
        assertEquals(DeliveryMode.Interrupt, routed(DeliveryMode.Interrupt, canSteer = true))
        assertEquals(DeliveryMode.Interrupt, routed(DeliveryMode.Interrupt, canSteer = false))
    }

    @Test
    fun preferredDeliverySurvivesASendThatUsedIt() = runTest {
        var mode: DeliveryMode? = null
        val c = controller(onSend = sendHandler { mode = it.mode })
        c.text = "hi"
        c.running = true
        c.canSteer = true
        c.preferredDelivery = DeliveryMode.Steer
        c.primaryAction()
        assertEquals(DeliveryMode.Steer, mode)
        assertEquals(DeliveryMode.Steer, c.preferredDelivery) // iOS never mutates it (#609 trap)
    }

    // ── send ────────────────────────────────────────────────────────────────

    @Test
    fun sendWithoutContentDoesNothing() = runTest {
        val c = controller(onSend = { _, _, _ -> error("no content, no send") })
        assertNull(c.send(DeliveryMode.Queue))
        assertTrue(events.isEmpty())
    }

    @Test
    fun sendFiresConfirmOnceBeforeTheHandlerAlsoWhenKept() = runTest {
        val kept = controller(onSend = sendHandler(SendResult.Kept))
        kept.text = "hi"
        kept.send(DeliveryMode.Queue)
        assertEquals(listOf("haptic:Confirm", "onSend"), events)

        events.clear()
        val sent = controller(onSend = sendHandler(SendResult.Sent))
        sent.text = "hi"
        sent.send(DeliveryMode.Queue)
        assertEquals(listOf("haptic:Confirm", "onSend"), events)
    }

    @Test
    fun sendTrimsThenEncodesTheBody() = runTest {
        var body: String? = null
        val c = controller(onSend = sendHandler(SendResult.Kept) { body = it.body })
        c.text = "  hi  \n"
        c.send(DeliveryMode.Queue)
        assertEquals("hi", body)
    }

    @Test
    fun sendEncodesLiveMentionTokensAfterTrimming() = runTest {
        var body: String? = null
        val c = controller(onSend = sendHandler(SendResult.Kept) { body = it.body })
        assertEquals("@a.kt", c.mentions.token(FileMatch("src/a.kt", false)))
        c.text = "  see @a.kt \n"
        c.send(DeliveryMode.Queue)
        assertEquals("see [a.kt](roboco-file:src/a.kt)", body)
    }

    @Test
    fun sentClearsTextImagesTokensAndSuggestions() = runTest {
        val c = controller(onSend = sendHandler(SendResult.Sent))
        c.mentions.token(FileMatch("src/a.kt", false))
        c.text = "hi @a.kt"
        c.addImages(listOf(image("a"), image("b")))
        c.suggestionsVisible = true
        c.send(DeliveryMode.Queue)
        assertEquals(TextFieldValue("", TextRange(0)), c.value)
        assertEquals("", c.text)
        assertEquals(0, c.images.size)
        assertEquals(0, c.mentions.tokens.size)
        assertFalse(c.suggestionsVisible)
    }

    @Test
    fun sentWithoutAHandlerClearsTheSame() = runTest {
        val c = controller() // onSend == null ⇒ Sent (iOS `?? .sent`)
        c.mentions.token(FileMatch("src/a.kt", false))
        c.text = "hi"
        c.addImages(listOf(image("a")))
        c.suggestionsVisible = true
        c.send(DeliveryMode.Queue)
        assertEquals("", c.text)
        assertEquals(0, c.images.size)
        assertEquals(0, c.mentions.tokens.size)
        assertFalse(c.suggestionsVisible)
        assertEquals(listOf("haptic:Confirm"), events)
    }

    @Test
    fun keptKeepsEverything() = runTest {
        val c = controller(onSend = sendHandler(SendResult.Kept))
        c.mentions.token(FileMatch("src/a.kt", false))
        c.value = TextFieldValue("hi there", TextRange(1, 3))
        c.addImages(listOf(image("a"), image("b")))
        c.suggestionsVisible = true
        c.send(DeliveryMode.Queue)
        assertEquals(TextFieldValue("hi there", TextRange(1, 3)), c.value)
        assertEquals("hi there", c.text)
        assertEquals(listOf("a", "b"), c.images.map { it.id })
        assertEquals(1, c.mentions.tokens.size)
        assertTrue(c.suggestionsVisible)
    }

    @Test
    fun replacedKeepsTheHandlersContentsAndHidesSuggestions() = runTest {
        val c = controller()
        c.onSend = sendHandler(SendResult.Replaced) {
            // Committing a queued edit: the stash is swapped in from inside onSend.
            c.text = "stash"
            c.images = listOf(image("b"))
        }
        c.mentions.token(FileMatch("src/a.kt", false))
        c.text = "edit me @a.kt"
        c.addImages(listOf(image("a")))
        c.suggestionsVisible = true
        c.send(DeliveryMode.Queue)
        assertEquals("stash", c.text)
        assertEquals(listOf("b"), c.images.map { it.id })
        assertEquals(1, c.mentions.tokens.size) // the restored draft's tokens still resolve
        assertFalse(c.suggestionsVisible)
    }

    @Test
    fun aThrowingHandlerBehavesLikeKept() = runTest {
        val c = controller(onSend = { _, _, _ -> throw IllegalStateException("ffi send failed") })
        c.mentions.token(FileMatch("src/a.kt", false))
        c.text = "hi"
        c.addImages(listOf(image("a")))
        c.suggestionsVisible = true
        c.send(DeliveryMode.Queue)
        assertEquals("hi", c.text)
        assertEquals(listOf("a"), c.images.map { it.id })
        assertEquals(1, c.mentions.tokens.size)
        assertTrue(c.suggestionsVisible)
        assertEquals(listOf("haptic:Confirm"), events)
    }

    // ── async sends (the suspend adaptation) ────────────────────────────────

    @Test
    fun aSecondSendWhileOneIsInFlightIsIgnored() = runTest {
        val gate = CompletableDeferred<SendResult>()
        var calls = 0
        val c = controller(onSend = { _, _, _ ->
            calls++
            events += "onSend"
            gate.await()
        })
        c.text = "hi"
        val job = c.send(DeliveryMode.Queue)!!
        assertNull(c.send(DeliveryMode.Queue)) // in flight: ignored, no second haptic
        assertEquals(1, calls)
        assertEquals(listOf("haptic:Confirm", "onSend"), events)
        gate.complete(SendResult.Sent)
        job.join()
        assertEquals("", c.text)
    }

    @Test
    fun typingDuringAnInFlightSendIsNotLost() = runTest {
        val gate = CompletableDeferred<SendResult>()
        val c = controller(onSend = { _, _, _ -> gate.await() })
        c.addImages(listOf(image("sent1"))) // content without text
        val job = c.send(DeliveryMode.Queue)!!
        c.text = "more" // the user types while the FFI send runs
        c.addImages(listOf(image("added")))
        gate.complete(SendResult.Sent)
        job.join()
        assertEquals("more", c.text)
        assertEquals(listOf("added"), c.images.map { it.id }) // only the sent image is removed
    }

    // ── canWrite / keyboardSend ─────────────────────────────────────────────

    @Test
    fun canWriteFalseDisablesEveryEntryPoint() = runTest {
        val c = controller(onSend = { _, _, _ -> error("offline engines never send") })
        var stops = 0
        c.onStop = { stops++ }
        c.running = true
        c.text = "hi"
        c.canWrite = false

        assertNull(c.send(DeliveryMode.Queue))
        c.primaryAction()
        c.keyboardSend()
        assertTrue(events.isEmpty())
        assertEquals(0, stops)

        val state = c.actionState
        assertEquals(ComposerAction.Queue, state.action) // still derived, just disabled
        assertFalse(state.enabled)
        assertFalse(state.deliveryMenu)

        c.mentionSearch = { _ -> emptyList() }
        assertFalse(c.mentionsEnabled)
        c.canWrite = true
        assertTrue(c.mentionsEnabled)
        assertTrue(c.actionState.enabled)
    }

    @Test
    fun keyboardSendWithAnEmptyComposerNeverStops() = runTest {
        val c = controller(onSend = { _, _, _ -> error("an empty composer never sends") })
        var stops = 0
        c.onStop = { stops++ }
        c.running = true
        c.keyboardSend()
        assertEquals(0, stops)
        assertTrue(events.isEmpty())
    }

    @Test
    fun keyboardSendWithContentSends() = runTest {
        var mode: DeliveryMode? = null
        val c = controller(onSend = sendHandler { mode = it.mode })
        c.text = "hi"
        c.running = true
        c.keyboardSend()
        assertEquals(DeliveryMode.Queue, mode) // preferred == Queue
    }

    @Test
    fun hideSuggestionsOnlyHides() = runTest {
        val c = controller()
        c.suggestionsVisible = true
        c.hideSuggestions()
        assertFalse(c.suggestionsVisible)
    }
}

/** One row of the ticket's Action truth table. */
private data class Row(
    val running: Boolean,
    val hasContent: Boolean,
    val canWrite: Boolean,
    val canSteer: Boolean,
    val preferred: DeliveryMode,
    val action: ComposerAction,
    val enabled: Boolean,
    val steering: Boolean,
    val deliveryMenu: Boolean,
    val label: String,
)

/** One recorded onSend call. */
private data class SendCall(val body: String, val imageIds: List<String>, val mode: DeliveryMode)

/** The ticket's recording fake: haptics land in [events], shared with the onSend recorders so the order is comparable; cues are a no-op. */
private class RecordingFeedback(private val events: MutableList<String>) : Feedback {
    override fun haptic(haptic: Haptic) {
        events += "haptic:${haptic.name}"
    }

    override fun cue(cue: Cue) = Unit
}

package sh.roboco.android.transcript

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalTypeScale
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.uiStyle
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * [DetailWidget]'s behaviour (ticket 127) — Robolectric + compose-ui-test
 * at mdpi (1 dp = 1 px) in a 360 × 300 dp window, per the ticket's list:
 * the synthetic `WidgetKind.Detail` widget sits in a 360 × 100 dp area
 * inside a scrollable parent that carries 65's Final-pass tap observer, so
 * the tests assert the semantics ("Bash details", button role), the click
 * callback ([DetailWidget]'s `onOpen` fires once with title and payload),
 * that the up event a widget click consumes keeps 65's observer from
 * firing `onTap`, that a drag starting on the widget scrolls the parent
 * instead, and that the widget draws nothing over a striped stage (pixel
 * equality with and without it, the golden rule's programmatic form).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h300dp-mdpi")
class DetailWidgetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The widget's onOpen calls, (title, payload) pairs. */
    private val opened = mutableListOf<Pair<String, String>>()

    /** 65's onTap-style observer: only an unconsumed tap counts. */
    private var taps = 0

    /** Whether the up change of the last gesture was consumed (raw read). */
    private var upConsumed: Boolean? = null

    /** The scrollable parent's state, captured at composition. */
    private var scrollState: ScrollState? = null

    /** [stripedPixels]' widget toggle (one setContent per test). */
    private var stripeWidget by mutableStateOf(false)
    private var stripeMounted = false

    // ----------------------------------------------------------- mounting

    /**
     * The widget in a scrollable column, exactly as ticket 65's [WidgetBox]
     * places it: `(w.x, w.y, max(w.w, 0), max(w.h, 0))`. The parent carries
     * both observers: 65's tap observer (`waitForUpOrCancellation` on the
     * Final pass — a consumed drag or a consumed up yields no tap) and the
     * raw recorder of the final up's consumption.
     */
    private fun mountWidget(payload: String?) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val typeScale = LocalTypeScale.current
                val palette = RobocoTheme.palette
                val scroll = rememberScrollState()
                scrollState = scroll
                val w = remember { widget(payload) }
                Box(
                    Modifier
                        .fillMaxSize()
                        // 65's tap observer (TranscriptHost.transcriptTaps).
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                val up = waitForUpOrCancellation(PointerEventPass.Final)
                                    ?: return@awaitEachGesture
                                taps++
                            }
                        }
                        // The raw consumption recorder for the ticket's
                        // "the up event is consumed" assertion.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                var up: PointerInputChange? = null
                                while (up == null) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    up = event.changes.firstOrNull { !it.pressed }
                                }
                                upConsumed = up.isConsumed
                            }
                        },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .testTag("stage"),
                    ) {
                        Box(Modifier.absoluteOffset(w.x.dp, w.y.dp).size(w.w.dp, w.h.dp)) {
                            when (val k = w.kind) {
                                is WidgetKind.Detail -> DetailWidget(k.title, w.payload ?: "") { t, s ->
                                    opened += t to s
                                }
                                else -> {}
                            }
                        }
                        BasicText(
                            "scrollable content below the widget",
                            Modifier.fillMaxWidth().height(1500.dp),
                            style = uiStyle(Face.Mono, typeScale.fixed(13f), palette.text),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The synthetic widget from the generated bindings (`display.rs:198-207`):
     * Rust never constructs the kind today, so this is the only producer.
     */
    private fun widget(payload: String?): Widget = Widget(
        id = 1u,
        kind = WidgetKind.Detail("Bash"),
        x = 16f,
        y = 12f,
        w = 200f,
        h = 20f,
        payload = payload,
        scroller = null,
    )

    // ------------------------------------------------------------ semantics

    @Test
    fun announcesTheTitleDetailsButtonWithTheWidgetTag() {
        mountWidget(Payload)
        val node = compose.onNodeWithTag("transcript-detail").fetchSemanticsNode()
        assertEquals(
            listOf("Bash details"),
            node.config[SemanticsProperties.ContentDescription],
        )
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        // The iOS view is a bare UIControl with no visuals: no text, no image.
        assertNull(node.config.getOrNull(SemanticsProperties.Text))
        compose.onNodeWithContentDescription("Bash details").assertExists()
    }

    // --------------------------------------------------------------- click

    @Test
    fun aClickOpensTheDetailOnceAndKeepsTheTapFromOnTap() {
        mountWidget(Payload)
        // A real touch click (performClick would use the semantics action
        // and skip the pointer pipeline the observers watch).
        compose.onNodeWithTag("transcript-detail").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf("Bash" to Payload), opened)
        // The clickable consumed the up: 65's observer sees no tap.
        assertEquals(0, taps)
        assertEquals("the widget's up must be consumed", true, upConsumed)
    }

    @Test
    fun aMissingPayloadOpensAnEmptyText() {
        mountWidget(null)
        compose.onNodeWithTag("transcript-detail").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf("Bash" to ""), opened)
        assertEquals(0, taps)
    }

    // ---------------------------------------------------------------- drag

    @Test
    fun aDragStartingOnTheWidgetScrollsTheParentAndOpensNothing() {
        mountWidget(Payload)
        // A 240 px drag starting inside the widget's rect (16..216, 12..32):
        // the down stays unconsumed, the scrollable takes it after the slop.
        // Upward: at offset 0 only a finger moving up can scroll the content.
        compose.onNodeWithTag("stage").performTouchInput {
            swipe(Offset(100f, 20f), Offset(100f, -220f), durationMillis = 500)
        }
        compose.waitForIdle()
        assertTrue("the drag must scroll the parent", scrollOffset() > 0)
        assertEquals("a drag is not a tap", 0, taps)
        assertTrue("a drag opens no sheet", opened.isEmpty())
    }

    private fun scrollOffset(): Int {
        var value = 0
        compose.runOnIdle { value = scrollState?.value ?: 0 }
        return value
    }

    // ------------------------------------------------------------- drawing

    @Test
    fun drawsNothingOverAStripe() {
        val bare = stripedPixels(withWidget = false)
        val with = stripedPixels(withWidget = true)
        assertEquals(bare.width, with.width)
        assertEquals(bare.height, with.height)
        assertTrue(
            "the widget must not draw anything over the stripe",
            bare.buffer.contentEquals(with.buffer),
        )
    }

    /** A 360 × 100 dp striped stage, with or without the widget on top. */
    private fun stripedPixels(withWidget: Boolean): PixelMap {
        // setContent may run only once per test with an Android compose rule:
        // the second capture toggles the widget through state.
        if (stripeMounted) {
            compose.runOnIdle { stripeWidget = withWidget }
            compose.waitForIdle()
            return compose.onNodeWithTag("stripe").captureToImage().toPixelMap()
        }
        stripeMounted = true
        stripeWidget = withWidget
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val palette = RobocoTheme.palette
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .drawBehind {
                                val bar = 12.dp.toPx()
                                var x = 0f
                                var i = 0
                                while (x < size.width) {
                                    drawRect(
                                        if (i % 2 == 0) palette.elevated else palette.chip,
                                        topLeft = Offset(x, 0f),
                                        size = Size(minOf(bar, size.width - x), size.height),
                                    )
                                    x += bar
                                    i++
                                }
                            }
                            .testTag("stripe"),
                    ) {
                        if (stripeWidget) {
                            Box(Modifier.absoluteOffset(16.dp, 12.dp).size(200.dp, 20.dp)) {
                                DetailWidget("Bash", Payload) { _, _ -> }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag("stripe").captureToImage().toPixelMap()
    }

    private companion object {
        const val Payload = "\$ roboco engine start\nlistening on ws://0.0.0.0:8675"
    }
}

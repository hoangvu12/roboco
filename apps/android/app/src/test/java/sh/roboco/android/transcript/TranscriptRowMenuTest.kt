package sh.roboco.android.transcript

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.Cue
import sh.roboco.android.design.Feedback
import sh.roboco.android.design.Haptic
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToasterState
import sh.roboco.android.design.ToastHost
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.WidgetKind

/**
 * The row long-press menu against the real core (android-ui-parity #128;
 * the same JNA setup as `TranscriptHostTest`, `w411dp-h891dp-xhdpi` so
 * d = 2): the item set per row kind, the order, the clipboard writes (the
 * `ClipData` label `Roboco`), the API 31–32 `Copied` toast against the
 * API 33+ silence, the Select Text hand-off, the "no menu for a Working
 * row or a control" rule, and that a lift's release never fires `onTap`.
 *
 * The host is the ticket's: `ToastHost { ContextMenuHost { TranscriptHost } }`
 * with a recording [Feedback] and a test-owned [ToasterState] (the
 * clipboard and toast rules read both). `GlassMenuHost` is not needed —
 * 16 embeds 15's `GlassMenuContent`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class TranscriptRowMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Records every haptic; provided via [LocalFeedback]. */
    private class RecordingFeedback : Feedback {
        val haptics = mutableListOf<Haptic>()
        override fun haptic(haptic: Haptic) {
            haptics += haptic
        }

        override fun cue(cue: Cue) = Unit
    }

    private val feedback = RecordingFeedback()
    private val toaster = ToasterState()
    private val taps = AtomicInteger()
    private val selectTextCalls = mutableListOf<String>()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(context)
        runBlocking { TextEngine.await() }
    }

    // ------------------------------------------------------------- helpers

    /** A fed state with the 5-row frame already applied (see [fedRowMenuState]). */
    private fun fedState(): TranscriptState = fedRowMenuState()

    /** The ticket's host: `ToastHost { ContextMenuHost { TranscriptHost } }`. */
    private fun host(state: TranscriptState, actions: TranscriptActions) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                // Frozen glyph clock: the fixture's Working row subscribes the
                // trailer glyph to the theme's endless withFrameNanos loop, and
                // an auto-advancing test clock never sees Compose idle.
                ProvideGlyphClock(frozenAtNanos = 0L) {
                    CompositionLocalProvider(LocalFeedback provides feedback) {
                        ToastHost(state = toaster) {
                            ContextMenuHost {
                                TranscriptHost(state, actions, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun actions() = TranscriptActions(
        selectText = { text -> selectTextCalls += text },
        onTap = { taps.incrementAndGet() },
    )

    /** Long-presses [node] with the clock auto-advancing (the gesture needs time). */
    private fun longPress(node: SemanticsNodeInteraction) {
        compose.mainClock.autoAdvance = true
        node.performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Long-presses [tag] (must match exactly one node). */
    private fun longPress(tag: String) = longPress(compose.onNodeWithTag(tag))

    /** Settles the lift (0.38 s) and 15's open spring (0.35 s). */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    /** Picks [tag] from an open menu and runs the close fade + next-frame action out. */
    private fun pick(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    /** The first `row-markdown` (a1 block 0), asserted to be the topmost. */
    private fun firstMarkdownRow(): SemanticsNodeInteraction {
        val tops = compose.onAllNodesWithTag("row-markdown").fetchSemanticsNodes().map { it.boundsInRoot.top }
        assertEquals("the fixture lays out three markdown rows", 3, tops.size)
        val first = compose.onAllNodesWithTag("row-markdown")[0]
        assertEquals("tree order is top-down", tops.minOf { it }, first.fetchSemanticsNode().boundsInRoot.top, 0f)
        return first
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(ClipboardManager::class.java) ?: error("no ClipboardManager")

    private fun clipText(): String = clipboard().primaryClip!!.getItemAt(0).text.toString()

    private fun clipLabel(): String = clipboard().primaryClip!!.description.label.toString()

    // --------------------------------------------------------------- tests

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aUserRowShowsExactlyCopyAndSelectTextWithOneLongPressHaptic() {
        val state = fedState()
        try {
            host(state, actions())
            longPress("row-user")
            settle()
            compose.onNodeWithTag("row-menu-copy").assertExists()
            compose.onNodeWithTag("row-menu-select-text").assertExists()
            compose.onNodeWithTag("row-menu-copy-message").assertDoesNotExist()
            compose.onNodeWithTag("context-menu-overlay").assertExists()
            assertEquals(listOf(Haptic.LongPress), feedback.haptics)
            // The release after the lift was swallowed: no tap.
            assertEquals(0, taps.get())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun copyPutsTheRowsOwnTextOnTheClipboardWithTheRobocoLabelAndNoToastOnApi33Plus() {
        val state = fedState()
        try {
            host(state, actions())
            longPress("row-user")
            settle()
            pick("row-menu-copy")
            assertEquals("Ship it.", clipText())
            assertEquals("Roboco", clipLabel())
            assertEquals("choosing an item added no haptic", listOf(Haptic.LongPress), feedback.haptics)
            assertNull("no app toast on API 33+ (the system's own overlay)", toaster.current)
            compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [32], qualifiers = "w411dp-h891dp-xhdpi")
    fun copyShowsTheCopiedToastOnApi32() {
        val state = fedState()
        try {
            host(state, actions())
            longPress("row-user")
            settle()
            pick("row-menu-copy")
            assertEquals("Copied", toaster.current?.text)
            assertEquals("Ship it.", clipText())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aBlockOfAMultiBlockReplyShowsAllThreeItemsInOrderAndCopyMessageCopiesTheWholeMessage() {
        val state = fedState()
        try {
            host(state, actions())
            longPress(firstMarkdownRow())
            settle()
            val tops = listOf(
                "row-menu-copy",
                "row-menu-copy-message",
                "row-menu-select-text",
            ).map { tag -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
            assertEquals("three rows for a multi-block reply", 3, tops.size)
            assertTrue("Copy must sit above Copy Message", tops[0] < tops[1])
            assertTrue("Copy Message must sit above Select Text", tops[1] < tops[2])

            pick("row-menu-copy-message")
            assertEquals("First paragraph.\n\nSecond paragraph.", clipText())
            assertEquals("Roboco", clipLabel())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun selectTextHandsTheWholeMessageToTranscriptActionsOnce() {
        val state = fedState()
        try {
            host(state, actions())
            longPress(firstMarkdownRow())
            settle()
            pick("row-menu-select-text")
            assertEquals(listOf("First paragraph.\n\nSecond paragraph."), selectTextCalls)
            assertEquals("choosing Select Text added no haptic", listOf(Haptic.LongPress), feedback.haptics)
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aWorkingRowShowsNoMenuNoHapticAndItsReleaseIsANormalTap() {
        val state = fedState()
        try {
            host(state, actions())
            longPress("row-working")
            settle()
            compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
            assertTrue("no haptic for a row with no items", feedback.haptics.isEmpty())
            // The gesture was left alone, so the release is an ordinary tap.
            assertEquals(1, taps.get())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun aLongPressOnTheCopyCodeControlOpensNothingWhileTheSameRowAwayFromItOpensTheMenu() {
        val state = fedState()
        try {
            host(state, actions())
            // a2 = the third markdown row; its CopyCode widget rect comes
            // from the frame's display list (row dp, or scroller-content dp
            // translated by the viewport — offset 0, nothing was scrolled).
            val frame = state.frame!!
            val a2Key = frame.rowsIn(0f, Float.MAX_VALUE).last { it.kind == RowKind.MARKDOWN }.key
            val disp = frame.display(frame.indexOf(a2Key)!!)!!
            val w = disp.widgets.first { it.kind is WidgetKind.CopyCode }
            val d = compose.density.density
            val sc = w.scroller?.toInt()
            val center = if (sc != null && sc < disp.scrollers.size) {
                val s = disp.scrollers[sc]
                Offset((w.x + s.x + w.w / 2f) * d, (w.y + s.y + w.h / 2f) * d)
            } else {
                Offset((w.x + w.w / 2f) * d, (w.y + w.h / 2f) * d)
            }
            val a2 = compose.onAllNodesWithTag("row-markdown")[2]
            assertEquals(
                "the third markdown node is a2",
                state.realized[a2Key]!!.topPx().toFloat(),
                a2.fetchSemanticsNode().boundsInRoot.top,
                1f,
            )

            compose.mainClock.autoAdvance = true
            a2.performTouchInput { longClick(center) }
            compose.waitForIdle()
            compose.onNodeWithTag("context-menu-overlay").assertDoesNotExist()
            // The menu stays shut (no LongPress haptic), and the gesture is
            // left alone, so the release is the control's own normal tap:
            // `clickable` has no duration limit (foundation 1.12's
            // ClickableNode.handleUpEvent → performClick on any up, like
            // UIButton's touchUpInside after a hold), so the copy fires with
            // its Light haptic and the "Code" clip. The old "no haptic at
            // all" expectation counted that Light as a menu haptic.
            assertTrue(
                "no menu haptic when the press starts on a control: ${feedback.haptics}",
                Haptic.LongPress !in feedback.haptics,
            )
            assertEquals("the release copied the code", listOf(Haptic.Light), feedback.haptics)
            assertEquals("Code", clipLabel())
            // The copy button consumed the up (ticket 65: controls keep their
            // taps), so the row's tap observer saw none.
            assertEquals(0, taps.get())

            // 20 dp from the row's left edge, mid-height: the row, not the
            // control — a2 is a single-block message, so Copy and Select
            // Text only (no Copy Message).
            a2.performTouchInput { longClick(Offset(20f * d, height / 2f)) }
            compose.waitForIdle()
            settle()
            compose.onNodeWithTag("row-menu-copy").assertExists()
            compose.onNodeWithTag("row-menu-select-text").assertExists()
            compose.onNodeWithTag("row-menu-copy-message").assertDoesNotExist()
            assertEquals(listOf(Haptic.Light, Haptic.LongPress), feedback.haptics)
            // The control press's release was the control's click (consumed,
            // no row tap); the menu press's release was swallowed.
            assertEquals(0, taps.get())
        } finally {
            state.close()
        }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun theStateExtensionReturnsTheSameItemsAsTheMenu() {
        val state = fedState()
        try {
            host(state, actions())
            val u1Key = state.frame!!.rowsIn(0f, Float.MAX_VALUE).first { it.kind == RowKind.USER }.key
            assertEquals(
                rowMenuActions("Ship it.", "Ship it."),
                state.rowMenuActions(u1Key),
            )
        } finally {
            state.close()
        }
    }
}

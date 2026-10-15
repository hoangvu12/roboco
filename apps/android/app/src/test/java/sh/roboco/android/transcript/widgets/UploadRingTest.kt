package sh.roboco.android.transcript.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalReduceMotion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.ManualPoster
import sh.roboco.android.transcript.RealizedRow
import sh.roboco.android.transcript.RowContext
import sh.roboco.android.transcript.RowModel
import sh.roboco.android.transcript.RowWidget
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.LayoutFrame
import uniffi.roboco_core.NoHandle
import uniffi.roboco_core.RowDisplay
import uniffi.roboco_core.RowKind
import uniffi.roboco_core.RowPlacement
import uniffi.roboco_core.TextSystem
import uniffi.roboco_core.TranscriptView
import uniffi.roboco_core.Widget
import uniffi.roboco_core.WidgetKind

/**
 * The ring's behaviour (ticket 67, Compose test rule at 393 dp × xxhdpi →
 * d = 3), through the test clock where timing matters:
 *
 * - nothing is emitted while progress is null;
 * - the fade-in: 200 ms easeInOut after null → 0.1, strictly mid-fade at
 *   ~100 ms, settled at 200 ms in whole frames + the recomposition and
 *   effect-start frames;
 * - the fade-out: 250 ms, the label and arc **keep the last non-null
 *   value** while fading (no "0%" flash, no arc collapse), the node is
 *   gone once the alpha reaches 0;
 * - progress returning mid-fade-out fades back in from the current alpha;
 * - a change 0.1 → 0.6 while visible: the label updates instantly, the
 *   arc sweeps 36° → 216° over 250 ms, the alpha never re-fades;
 * - `startVisible` (the fixture/golden path) skips the fade and snaps the
 *   arc at the first value;
 * - the fades are not gated by Reduce Motion (iOS does not gate them);
 * - semantics: one merged node — "Uploading", stateDescription "42%", no
 *   click action, no progressBarRangeInfo (TalkBack would read the
 *   percent twice);
 * - through ticket 65's real [RowWidget] dispatch: a pending tile's tap
 *   still opens the image with the pending reference (the ring consumes
 *   no touches), a host-path reference composes no ring whatever the
 *   progress, and one `uploadProgress` write updates every pending tile
 *   of every row with the same percent.
 *
 * The [TranscriptState] runs on a no-op fake engine — no Rust core is
 * involved; only `uploadProgress` is used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class UploadRingTest {

    @get:Rule
    val compose = createComposeRule()

    private val states = mutableListOf<TranscriptState>()

    @After
    fun reset() {
        ReduceMotion.resetForTest()
        states.forEach { it.close() }
        states.clear()
    }

    // ------------------------------------------------------------- fixtures

    /** The no-op engine: no frame ever arrives, nothing touches the core. */
    private object NoEngine : TranscriptView(NoHandle) {
        override fun setViewport(width: Float, textScale: Float) {}
        override fun frame(): LayoutFrame = error("no frame staged")
        override fun shutdown() {}
        override fun close() {}
    }

    /** A [TranscriptState] over the fake engine; `uploadProgress` is all we use. */
    private fun newState(): TranscriptState =
        TranscriptState(TextSystem(NoHandle), ManualPoster()) { _, _ -> NoEngine }
            .also { states += it }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        RobocoTheme(Appearance.Light) {
            Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) { content() }
        }
    }

    /** The bare ring in a 76 dp slot, reading the state's upload progress. */
    @Composable
    private fun RingTile(state: TranscriptState) {
        Box(Modifier.size(76.dp)) {
            UploadRing(progress = { state.uploadProgress }, modifier = Modifier.matchParentSize())
        }
    }

    /**
     * One 76 dp image widget through the real [RowWidget] dispatch — the
     * wiring ticket 67 owns: the `overlay` argument is only built for a
     * pending reference, and the ring reads `ctx.state.uploadProgress`.
     */
    @Composable
    private fun WidgetTile(state: TranscriptState, actions: TranscriptActions, reference: String, id: UInt) {
        Box(Modifier.size(76.dp)) {
            val key = (id + 1u).toULong()
            val placement = remember(key) { RowPlacement(id, key, 1uL, 0f, 76f, RowKind.USER) }
            val row = remember(key) { RealizedRow(key, state, placement) }
            val model = remember(key) {
                RowModel(
                    RowDisplay(
                        key, 1uL, 393f, 76f, "",
                        emptyList(), emptyList(), emptyList(), emptyList(),
                        emptyList(), emptyList(), "",
                    )
                )
            }
            RowWidget(
                RowContext(state, row, RowKind.USER, model, IntArray(0), { actions }, false),
                Widget(id, WidgetKind.Image(reference), 0f, 0f, 76f, 76f, null, null),
            )
        }
    }

    // -------------------------------------------------------------- helpers

    /** All ring nodes — empty before the first composition, safe to poll. */
    private fun ringNodes() = compose.onAllNodesWithTag("upload-progress").fetchSemanticsNodes()

    private fun ringAlpha(): Float = ringNodes().firstOrNull()?.config?.get(UploadRingAlphaKey) ?: -1f

    private fun ringSweep(): Float = ringNodes().firstOrNull()?.config?.get(UploadRingSweepKey) ?: -1f

    private fun ringLabel(): String? =
        ringNodes().firstOrNull()?.config?.get(SemanticsProperties.StateDescription)

    /**
     * Writes [p] on the UI thread and sends the apply notification at
     * once. Under Robolectric the test body is the main thread, so the
     * write runs inline, and Compose's GlobalSnapshotManager would only
     * forward it to the Recomposer from a main-looper post — which a
     * frozen `mainClock.advanceTimeByFrame()` never idles. Without this
     * the change lands one or more frames late (or not at all before the
     * next semantics read idles the looper), skewing every timing below.
     */
    private fun setProgress(state: TranscriptState, p: Double?) {
        compose.runOnUiThread {
            state.uploadProgress = p
            Snapshot.sendApplyNotifications()
        }
    }

    /** Advances frozen frames until [condition] holds (5 s of frames). */
    private fun driveFrames(condition: () -> Boolean) {
        repeat(300) {
            if (condition()) return
            compose.mainClock.advanceTimeByFrame()
        }
        error("condition not met within 300 frames")
    }

    // ------------------------------------------------- nothing before progress

    @Test
    fun withNoProgressEverSetNothingIsEmitted() {
        val state = newState()
        compose.setContent { Host { RingTile(state) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        assertTrue("no draw and no semantics node while p is null", ringNodes().isEmpty())
    }

    // -------------------------------------------------------- the 200 ms fade-in

    @Test
    fun theOverlayFadesInOver200MsAfterProgressStarts() {
        compose.mainClock.autoAdvance = false
        val state = newState()
        compose.setContent { Host { RingTile(state) } }

        // First frame at p = null: nothing is emitted at all.
        compose.mainClock.advanceTimeByFrame()
        assertTrue(ringNodes().isEmpty())

        setProgress(state, 0.1)
        val start = compose.mainClock.currentTime
        // One frame for the LaunchedEffect to start (alpha still 0) …
        compose.mainClock.advanceTimeByFrame()
        // … roughly half-faded at the ~100 ms midpoint (frame-stepped:
        // `advanceTimeBy` rounds up to whole frames) …
        compose.mainClock.advanceTimeBy(100)
        val mid = ringAlpha()
        assertTrue("alpha at ~100 ms should be mid-fade, was $mid", mid in 0.2f..0.8f)
        assertEquals("the label shows p as soon as it arrives", "10%", ringLabel())
        // … and settled at 1 after the full 200 ms tween plus two frames of
        // latency: frame 1 recomposes on the new value and launches the
        // effect, frame 2 is the animation's play-time-0 frame, and the
        // 200 ms tween ends on the first whole 16 ms frame at or past it
        // (13 frames = 208 ms): 16 + 16 + 208 = 240 ms.
        driveFrames { ringAlpha() >= 0.999f }
        assertEquals(1f, ringAlpha(), 0.001f)
        val settle = compose.mainClock.currentTime - start
        assertTrue("fade-in takes 240 ms (200 tween in whole frames + two start frames), was $settle ms", settle in 200..240)
    }

    // ------------------------------------------------------- the 250 ms fade-out

    @Test
    fun theOverlayFadesOutOver250MsKeepingTheLastValues() {
        compose.mainClock.autoAdvance = false
        val state = newState()
        compose.setContent { Host { RingTile(state) } }
        setProgress(state, 0.1)
        driveFrames { ringAlpha() >= 0.999f }

        setProgress(state, null)
        val start = compose.mainClock.currentTime
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(125)
        // The label and the arc keep the last non-null value while fading …
        assertEquals("the label keeps 10% during the fade-out", "10%", ringLabel())
        assertEquals("the arc keeps 36° during the fade-out", 36f, ringSweep(), 0.1f)
        val mid = ringAlpha()
        assertTrue("alpha at ~125 ms should be mid-fade-out, was $mid", mid in 0.02f..0.98f)
        // … alpha reaches 0 by 288 ms: the recomposition frame, the
        // animation's play-time-0 frame, then 250 ms in whole 16 ms frames
        // (16 frames = 256 ms): 16 + 16 + 256 …
        driveFrames { ringAlpha() <= 0.001f }
        val settle = compose.mainClock.currentTime - start
        assertTrue("fade-out takes 288 ms (250 tween in whole frames + two start frames), was $settle ms", settle in 250..288)
        // … and by 300 ms nothing is left: no draw, no semantics node.
        compose.mainClock.advanceTimeBy(300)
        assertTrue("the node is gone once the alpha reaches 0", ringNodes().isEmpty())
    }

    @Test
    fun progressReturningMidFadeOutAnimatesBackIn() {
        compose.mainClock.autoAdvance = false
        val state = newState()
        compose.setContent { Host { RingTile(state) } }
        setProgress(state, 0.1)
        driveFrames { ringAlpha() >= 0.999f }

        // Fade out halfway …
        setProgress(state, null)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(125)
        val fading = ringAlpha()
        assertTrue("mid fade-out before the return, was $fading", fading in 0.02f..0.98f)

        // … then progress returns: the alpha climbs back from its current
        // value and the label shows the new value.
        setProgress(state, 0.5)
        driveFrames { ringAlpha() >= 0.999f }
        assertEquals(1f, ringAlpha(), 0.001f)
        assertEquals("50%", ringLabel())
    }

    // ---------------------------------------------------- the 250 ms arc sweep

    @Test
    fun aProgressChangeWhileVisibleAnimatesTheArcWithoutRefading() {
        compose.mainClock.autoAdvance = false
        val state = newState()
        compose.setContent { Host { RingTile(state) } }
        setProgress(state, 0.1)
        driveFrames { ringAlpha() >= 0.999f && ringSweep() >= 36f }

        setProgress(state, 0.6)
        // The label updates on the very next frame …
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the label updates instantly", "60%", ringLabel())
        // … the arc sweeps from 36° towards 216° over 250 ms …
        compose.mainClock.advanceTimeBy(125)
        val mid = ringSweep()
        assertTrue("sweep at ~125 ms should be between 36° and 216°, was $mid", mid in 37f..215f)
        // … and the alpha never re-fades.
        assertEquals(1f, ringAlpha(), 0.001f)
        driveFrames { ringSweep() >= 215.9f }
        assertEquals(216f, ringSweep(), 0.1f)
        assertEquals(1f, ringAlpha(), 0.001f)
    }

    // --------------------------------------------------------- the fixture path

    @Test
    fun startVisibleSkipsTheFadeInAndSnapsTheArc() {
        compose.mainClock.autoAdvance = false
        val p: Double? = 0.42
        compose.setContent {
            Host {
                Box(Modifier.size(76.dp)) {
                    UploadRing(progress = { p }, modifier = Modifier.matchParentSize(), startVisible = true)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("no fade-in for startVisible", 1f, ringAlpha(), 0.001f)
        assertEquals("the arc snaps at the first value (no sweep from 0)", 151.2f, ringSweep(), 0.01f)
        assertEquals("42%", ringLabel())
        compose.mainClock.advanceTimeBy(100)
        assertEquals("still no fade-in", 1f, ringAlpha(), 0.001f)
    }

    // ------------------------------------------------------------ reduce motion

    @Test
    fun underReduceMotionTheFadesStillRun() {
        compose.mainClock.autoAdvance = false
        val state = newState()
        compose.setContent {
            Host {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    RingTile(state)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        setProgress(state, 0.1)
        driveFrames { ringAlpha() >= 0.999f }
        assertEquals("the fades are not gated by reduce motion (a ticket 04 keep)", 1f, ringAlpha(), 0.001f)
    }

    // ---------------------------------------------------------------- semantics

    @Test
    fun theRingNodeIsOneMergedElementWithNoClickAction() {
        val state = newState()
        compose.setContent { Host { RingTile(state) } }
        compose.runOnIdle { state.uploadProgress = 0.42 }
        compose.waitForIdle()

        val node = compose.onNodeWithTag("upload-progress").fetchSemanticsNode()
        assertEquals("Uploading", node.config[SemanticsProperties.ContentDescription]?.firstOrNull())
        assertEquals("42%", node.config[SemanticsProperties.StateDescription])
        assertEquals("upload-progress", node.config[SemanticsProperties.TestTag])
        assertNull("no click action on the ring", node.config.getOrNull(SemanticsActions.OnClick))
        assertNull(
            "no progressBarRangeInfo: TalkBack would announce the percent twice",
            node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo),
        )
    }

    // ------------------------------------------- the RowWidget wiring (ticket 65)

    @Test
    fun aTapThroughTheVisibleRingStillOpensTheImage() {
        val state = newState()
        val bitmap = ImageBitmap(100, 100)
        val opened = mutableListOf<String>()
        val actions = TranscriptActions(
            cachedImage = { _, _ -> bitmap },
            loadImage = { _, _ -> bitmap },
            openImage = { reference, _, _, _ -> opened.add(reference) },
        )
        compose.setContent { Host { WidgetTile(state, actions, "pending://9f1c/a.png", 1u) } }
        compose.runOnIdle { state.uploadProgress = 0.42 }
        compose.waitForIdle()

        // The ring is up over the loaded tile; the tap goes through it.
        compose.onNodeWithTag("transcript-image").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf("pending://9f1c/a.png"), opened)
    }

    @Test
    fun aHostPathReferenceNeverComposesARing() {
        val state = newState()
        compose.setContent { Host { WidgetTile(state, TranscriptActions(), "/abs/path.png", 1u) } }
        compose.runOnIdle { state.uploadProgress = 0.42 }
        compose.waitForIdle()

        assertTrue("no ring node for a host-path reference", ringNodes().isEmpty())
        compose.onNodeWithTag("transcript-image").assertExists()
    }

    @Test
    fun oneProgressWriteUpdatesEveryPendingTileOfEveryRow() {
        val state = newState()
        compose.setContent {
            Host {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTile(state, TranscriptActions(), "pending://u/1.png", 1u)
                        WidgetTile(state, TranscriptActions(), "pending://u/2.png", 2u)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetTile(state, TranscriptActions(), "pending://u/3.png", 3u)
                        WidgetTile(state, TranscriptActions(), "/abs/host.png", 4u)
                    }
                }
            }
        }
        compose.runOnIdle { state.uploadProgress = 0.42 }
        compose.waitForIdle()

        val nodes = ringNodes()
        assertEquals("three pending tiles across two rows, one ring each", 3, nodes.size)
        nodes.forEach { assertEquals("42%", it.config[SemanticsProperties.StateDescription]) }
    }
}

package sh.roboco.android.newsession

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Motion
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.sheet.SmoothTopRoundedShape
import sh.roboco.android.shell.DraftHandoff
import sh.roboco.android.shell.HandoffAnimator
import sh.roboco.android.shell.RouterHooks
import sh.roboco.android.shell.SessionRoute

/**
 * [DraftHandoffAnimator] (android-ui-parity #95) — the handoff's
 * timeline over a fake arrival target, Robolectric +
 * `createComposeRule` at `w393dp-h852dp-xxhdpi` (1 dp = 3 px, root =
 * 1179 × 2556) with `mainClock.autoAdvance = false`: the frame clock
 * drives everything (`withFrameNanos`, `animate`, the 16 ms poll), so
 * the timeline is deterministic. The mount is one `setContent` per test;
 * state writes go through `runOnIdle` + [Snapshot.sendApplyNotifications]
 * where the frozen looper needs them.
 *
 * The fixture handoff: a 1179 × 1500 scene picture (blue-grey, clipped
 * to 31's 38 dp top-corner shape — the corner pixel proves the clip), a
 * 200 × 80 composer picture (olive) at (48, 2000) and the text slot at
 * (100, 1600)–(700, 1650); the fake chat's composer lives at
 * (48, 2420)–(828, 2556) and its first bubble at (700, 300)–(1140, 390).
 * [FakeArrivalTarget] logs every call with `mainClock.currentTime`,
 * records the specs, moves its composer bounds 2 px per read (the IME
 * ride) and counts its reads (the poll).
 *
 * Timing model (pinned by the self-calibrating checkpoints): `run` at
 * t = 0 adds the overlays synchronously and suspends `play` on the
 * 50 ms start delay; at t = 50 the children launch and the first poll
 * read happens; every `animate`/`withFrameNanos` awaiter registered at
 * 50 fires at 66 (16 ms frame delay), so the animations' zero is the
 * first frame and the AC's times are checked against the **observed**
 * start frame, with ±1 frame of slack where the ticket's windows are
 * tight.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class DraftHandoffAnimatorTest {

    @get:Rule
    val compose = createComposeRule()

    // ── the fakes (one instance per test) ─────────────────────────────

    /** Every arrival-hook call, logged with the clock at call time. */
    private data class HookCall(val name: String, val atMs: Long)

    /**
     * The fake arrival target: logs the calls and specs, counts the
     * reads, moves the composer bounds 2 px per read (the keyboard
     * ride), and runs the reveals for real so their durations and
     * completion times are observable.
     */
    private inner class FakeArrivalTarget : HandoffArrivalTarget {
        val calls = mutableListOf<HookCall>()
        val composerSpecs = mutableListOf<AnimationSpec<Float>>()
        val transcriptSpecs = mutableListOf<AnimationSpec<Float>>()
        var composerReads = 0
        var bubbleReads = 0
        var lastComposerBounds: Rect? = null
        var lastBubbleBounds: Rect? = null
        var composerBounds: () -> Rect? = { ChatComposerRect }
        var bubbleBounds: () -> Rect? = { BubbleDestRect }
        var finishArrivalCalls = 0
        var transcriptRevealCompleted = false
        var transcriptRevealCancelled = false
        var transcriptRevealDoneAt: Long? = null

        override fun arrivalComposerBounds(): Rect? {
            composerReads++
            val bounds = composerBounds()
            lastComposerBounds = bounds
            return bounds
        }

        override fun arrivalBubbleBounds(): Rect? {
            bubbleReads++
            val bounds = bubbleBounds()
            lastBubbleBounds = bounds
            return bounds
        }

        override suspend fun revealArrivalComposer(spec: AnimationSpec<Float>) {
            composerSpecs += spec
            calls += HookCall("composer", now())
            animate(0f, 1f, animationSpec = spec) { _, _ -> }
        }

        override suspend fun revealArrivalTranscript(spec: AnimationSpec<Float>) {
            transcriptSpecs += spec
            calls += HookCall("transcript", now())
            try {
                animate(0f, 1f, animationSpec = spec) { _, _ -> }
                transcriptRevealCompleted = true
            } catch (cancelled: CancellationException) {
                transcriptRevealCancelled = true
                throw cancelled
            } finally {
                transcriptRevealDoneAt = now()
            }
        }

        override fun finishArrival() {
            finishArrivalCalls++
            calls += HookCall("finish", now())
        }

        fun callAt(name: String): List<Long> = calls.filter { it.name == name }.map { it.atMs }
    }

    /** The fake route: chatId-keyed so `targetOf` can resolve or refuse it. */
    private class FakeRoute(
        override val chatId: String,
        private val onFinish: () -> Unit = {},
    ) : SessionRoute {
        @Composable
        override fun Content() {}

        override fun prepareArrival() {}

        override fun finishArrival() = onFinish()

        override fun discard() {}
    }

    // ── the fixture ───────────────────────────────────────────────────

    private val fake = FakeArrivalTarget()
    private val knownRoute = FakeRoute("known") { knownRouteFinishes++ }
    private val unknownRoute = FakeRoute("unknown") { unknownRouteFinishes++ }

    private var knownRouteFinishes = 0
    private var unknownRouteFinishes = 0
    private var hooks: RouterHooks? = null
    private var finishedCount = 0
    private var finishedAt = -1L
    private val hostShown = mutableStateOf(true)

    private val fixture = fixtureHandoff()

    @Before
    fun freezeClockAndPinReduceMotion() {
        compose.mainClock.autoAdvance = false
        // Never the Robolectric system shadow: the normal tests pin
        // full motion, the reduced test pins reduced.
        ReduceMotion.debugOverride = false
        finishedCount = 0
        finishedAt = -1L
        knownRouteFinishes = 0
        unknownRouteFinishes = 0
        hostShown.value = true
    }

    @After
    fun unpinReduceMotion() {
        ReduceMotion.debugOverride = null
    }

    // ── the mount and the driver helpers ─────────────────────────────

    private fun mount(content: @Composable () -> Unit = { DefaultBackdrop() }) {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val h = remember { RouterHooks() }
                hooks = h
                if (hostShown.value) {
                    HandoffOverlayHost(
                        hooks = h,
                        targetOf = { route -> if (route.chatId == "known") fake else null },
                    ) {
                        content()
                    }
                } else {
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    /** The default underlay: the light theme's page background. */
    @Composable
    private fun DefaultBackdrop() {
        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background))
    }

    /** Runs the handoff and returns the animator the host registered. */
    private fun runHandoff(
        handoff: DraftHandoff = fixture,
        route: SessionRoute = knownRoute,
    ): DraftHandoffAnimator {
        // Most tests drive the default mount; only the custom-content ones
        // mount themselves first (one setContent per test).
        if (hooks == null) mount()
        val h = checkNotNull(hooks) { "mount first" }
        var animator: DraftHandoffAnimator? = null
        compose.runOnIdle {
            val a = h.handoffAnimator as DraftHandoffAnimator
            animator = a
            a.run(handoff, route) {
                finishedCount++
                finishedAt = compose.mainClock.currentTime
            }
        }
        return checkNotNull(animator) { "the host did not register the animator" }
    }

    /** Advances the clock to [ms] (absolute), firing the tasks on the way. */
    private fun advanceTo(ms: Long) {
        val current = compose.mainClock.currentTime
        if (ms > current) compose.mainClock.advanceTimeBy(ms - current)
    }

    /** Advances frame by frame until [until] (bounded by [maxMs]). */
    private fun advanceFrames(until: () -> Boolean, maxMs: Long = 2_000): Long {
        var frames = 0
        while (!until()) {
            if (compose.mainClock.currentTime >= maxMs || frames > 400) break
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        return compose.mainClock.currentTime
    }

    private fun now(): Long = compose.mainClock.currentTime

    /**
     * Runs the handoff and settles frame 0 (one frame, the overlays draw)
     * plus the 50 ms start delay (the children launch, the first poll
     * read happens). Returns the playing set.
     */
    private fun startPlaying(handoff: DraftHandoff = fixture): HandoffOverlaySet {
        val animator = runHandoff(handoff = handoff)
        compose.mainClock.advanceTimeByFrame() // t = 16: frame 0 draws
        val set = animator.overlays.sets.single()
        advanceTo(50) // the motion starts
        return set
    }

    /**
     * The first frame at which [changed] observes a value the animation
     * wrote — calibrates the animation's start frame (its zero).
     */
    private fun firstFrameWhere(changed: () -> Boolean): Long {
        while (!changed() && compose.mainClock.currentTime < 300) {
            compose.mainClock.advanceTimeByFrame()
        }
        return compose.mainClock.currentTime
    }

    // ── 1. frame 0 ────────────────────────────────────────────────────

    /**
     * Right after `run`: the overlays are added synchronously — the set
     * exists, the scene sits at `sceneFrame` (clipped to its shape), the
     * composer at `composerFrame`, the bare bubble at
     * `bubbleStartRect(textFrame)` with `bubbleFill == 0`, and no hook
     * is called before 50 ms.
     */
    @Test
    fun frameZeroDrawsTheThreePicturesAndCallsNoHookBeforeFiftyMs() {
        val animator = runHandoff()

        // The set is already there — same frame as the router's push.
        val set = animator.overlays.sets.single()
        assertEquals(0f, set.sceneProgress, 1e-4f)
        assertEquals(fixture.composerFrame, set.composerRect)
        assertEquals(1f, set.composerAlpha, 1e-4f)
        assertEquals(bubbleStartRect(fixture.textFrame, 3f), set.bubbleRect)
        assertEquals(0f, set.bubbleFill, 1e-4f)
        assertEquals(1f, set.bubbleAlpha, 1e-4f)
        assertTrue("no hook before 50 ms", fake.calls.isEmpty())

        // One frame: the canvas composes and draws the three pictures.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        // The scene fills its frame…
        assertTrue("the scene pixel at its centre", close(pixels[589, 750], SceneColor))
        // …clipped to 31's shape (the top-left corner shows the page)…
        assertTrue("the corner is clipped by the scene shape", close(pixels[2, 2], PageBackground))
        // …and the composer picture sits at its frame.
        assertTrue("the composer pixel at its centre", close(pixels[148, 2040], ComposerColor))
    }

    // ── 2. the specs ──────────────────────────────────────────────────

    /** The reveals receive the ticket's specs and run at 50 ms (±1 frame). */
    @Test
    fun theRevealsReceiveTheTicketsSpecsAtFiftyMs() {
        startPlaying()
        assertEquals(
            tween<Float>(durationMillis = 180, delayMillis = 360, easing = Motion.EaseInOut),
            fake.composerSpecs.single(),
        )
        assertEquals(
            tween<Float>(durationMillis = 350, delayMillis = 120, easing = Motion.EaseOut),
            fake.transcriptSpecs.single(),
        )
        val composerAt = fake.callAt("composer").single()
        val transcriptAt = fake.callAt("transcript").single()
        assertTrue("composer reveal at 50 ms ±1 frame, was $composerAt", abs(composerAt - 50) <= 16)
        assertTrue("transcript reveal at 50 ms ±1 frame, was $transcriptAt", abs(transcriptAt - 50) <= 16)
    }

    // ── 3. the dissolve ───────────────────────────────────────────────

    /**
     * The scene dissolves over 320 ms with `Motion.EaseOut`: 0 at 50 ms,
     * the eased half-way value at 210 ms (self-calibrated against the
     * animation's first frame) and 1 by 370 ms; the settle scale is
     * 0.985 (`Motion.handoff(false).dissolveScale`, drawn as
     * `lerp(1, 0.985, p)` around the frame's centre).
     */
    @Test
    fun theDissolveRunsTheThreeTwentyEaseOutTween() {
        val set = startPlaying()
        assertEquals("still 0 at 50 ms", 0f, set.sceneProgress, 1e-4f)

        // Calibrate: the first non-zero value is written one frame after
        // the animation's start frame (which emitted the initial 0). The
        // window allows one frame of launch slack.
        val t0 = firstFrameWhere { set.sceneProgress > 0f } - 16
        assertTrue("the dissolve starts on the first frame after 50 ms", t0 in 50..82)

        advanceTo(210)
        // advanceTimeBy rounds up to whole frames, so the clock stands on
        // the frame at/after 210 ms (224): the expected value is taken at
        // the clock's real time, not the nominal 210.
        val at = now()
        val expected = Motion.EaseOut.transform((((at - t0) / 320f)).coerceIn(0f, 1f))
        assertEquals(
            "the eased value at 210 ms (Motion.EaseOut of the half-way progress)",
            expected,
            set.sceneProgress,
            0.02f,
        )

        val tEnd = advanceFrames({ set.sceneProgress >= 1f })
        assertEquals("1 by 370 ms (+ frame slack)", 1f, set.sceneProgress, 1e-4f)
        assertTrue("the dissolve ends within 320 ms + slack of its start, was ${tEnd - t0}", tEnd - t0 in 320..352)
        assertEquals("the settle scale is 0.985", 0.985f, Motion.handoff(false).dissolveScale, 1e-4f)
    }

    // ── 4. the glide ──────────────────────────────────────────────────

    /**
     * The glide tracks a moving target: with the fake composer bounds
     * moving down 2 px per read, every frame's `composerRect` keeps
     * `from`'s size, interpolates bottom (and left) from `from` toward
     * that frame's live rect with the quartic progress of the elapsed
     * frame time, and lands bottom-aligned by 600 ms.
     */
    @Test
    fun theGlideTracksTheMovingComposerTarget() {
        // The fake composer rides the keyboard: 2 px down per read.
        fake.composerBounds = { ChatComposerRect.translate(0f, ((fake.composerReads - 1) * 2).toFloat()) }
        val set = startPlaying()
        val from = fixture.composerFrame

        // Calibrate the glide's start frame from the first moved rect.
        val t0 = firstFrameWhere { set.composerRect.top > from.top } - 16
        assertTrue("the glide starts on the first frame after 50 ms", t0 in 50..82)

        var maxFill = 0f
        while (compose.mainClock.currentTime < 660) {
            compose.mainClock.advanceTimeByFrame()
            val rect = set.composerRect
            val live = checkNotNull(fake.lastComposerBounds) { "the glide reads the target every frame" }
            // The picture is never squashed: always `from`'s size.
            assertEquals("width kept at ${compose.mainClock.currentTime}", from.width, rect.width, 0.5f)
            assertEquals("height kept at ${compose.mainClock.currentTime}", from.height, rect.height, 0.5f)
            // It is the eased lerp onto the live target, never past it.
            val expected = glideProgress((compose.mainClock.currentTime - t0) * 1_000_000L, 550)
            val easedBottom = from.bottom + (live.bottom - from.bottom) * expected
            assertEquals(
                "bottom follows the quartic lerp at ${compose.mainClock.currentTime}",
                easedBottom,
                rect.bottom,
                0.75f,
            )
            assertTrue("never past the live bottom", rect.bottom <= live.bottom + 0.5f)
            maxFill = max(maxFill, set.bubbleFill)
        }
        // Settled: bottom-aligned on the (last-read) live rect.
        assertEquals(
            "bottom-aligned at the end",
            checkNotNull(fake.lastComposerBounds).bottom,
            set.composerRect.bottom,
            0.5f,
        )
        assertTrue("the glide moved down", set.composerRect.bottom > from.bottom)
        assertTrue(maxFill <= 1f)
    }

    // ── 5. the poll ───────────────────────────────────────────────────

    /** Null for the first 3 reads: 4 reads, the flight goes to the 4th result. */
    @Test
    fun thePollRetriesUntilTheBubbleAppears() {
        val target = Rect(700f, 300f, 1140f, 390f)
        fake.bubbleBounds = { if (fake.bubbleReads <= 3) null else target }
        val set = startPlaying()

        val tFinish = advanceFrames({ fake.finishArrivalCalls > 0 })
        assertEquals("exactly 4 reads", 4, fake.bubbleReads)
        // The flight went to the 4th result: the rect lands on it (within
        // the spring's ~0.5 % overshoot past the target — the rect is
        // never clamped, only the fill is).
        assertLandsOn(set.bubbleRect, target, bubbleStartRect(fixture.textFrame, 3f))
        assertTrue("finishArrival after the flight: $tFinish", tFinish > 50)
    }

    /** Bounds always null: exactly 21 reads, the target is the fallback. */
    @Test
    fun thePollGivesUpAfterTwentyOneReadsAndFliesToTheFallback() {
        fake.bubbleBounds = { null }
        val animator = runHandoff()
        compose.mainClock.advanceTimeByFrame()
        val set = animator.overlays.sets.single()
        advanceTo(50)

        advanceFrames({ fake.finishArrivalCalls > 0 }, maxMs = 2_500)
        assertEquals("exactly 21 reads", 21, fake.bubbleReads)

        val expected = fallbackBubbleTarget(
            start = bubbleStartRect(fixture.textFrame, 3f),
            rootWidth = animator.overlays.hostWidth,
            safeTop = animator.overlays.safeTop,
            d = 3f,
        )
        assertLandsOn(set.bubbleRect, expected, bubbleStartRect(fixture.textFrame, 3f))
    }

    // ── 6. the flight and the end ─────────────────────────────────────

    /**
     * The flight (the 0.5 s ζ 0.86 spring on a 0–1 progress): the fill is
     * clamped to [0, 1] even while the rect overshoots the target,
     * `finishArrival()` runs once 345–480 ms after the poll hit (the
     * settle-matched spring's 1 % cut-off ≈ 382 ms + frame alignment),
     * the bubble fades over 140 ms, and the overlays are removed with
     * `onFinished` exactly once.
     */
    @Test
    fun theFlightFinishesAndTheHandoffEnds() {
        val set = startPlaying()

        var maxFill = 0f
        var overshoots = false
        while (fake.finishArrivalCalls == 0 && compose.mainClock.currentTime < 1_500) {
            compose.mainClock.advanceTimeByFrame()
            maxFill = max(maxFill, set.bubbleFill)
            if (set.bubbleRect.right > BubbleDestRect.right + 0.5f) overshoots = true
        }
        val tFinish = fake.callAt("finish").single()
        assertEquals("finishArrival exactly once", 1, fake.finishArrivalCalls)
        assertTrue(
            "finishArrival 345–440 ms after the poll hit (382 + alignment), was ${tFinish - 50}",
            tFinish - 50 in 345..440,
        )
        assertTrue("the flight overshoots the target rect (ζ 0.86)", overshoots)
        assertTrue("the fill is clamped to at most 1", maxFill <= 1f)
        assertEquals("the fill settles at 1", 1f, maxFill, 1e-4f)
        // The flight lands on the target (within the overshoot).
        assertLandsOn(set.bubbleRect, BubbleDestRect, bubbleStartRect(fixture.textFrame, 3f))

        // The final fade: 140 ms (±2 frames of alignment) after finish.
        assertTrue("the bubble is still opaque at finishArrival", set.bubbleAlpha > 0f)
        val tZero = advanceFrames({ set.bubbleAlpha <= 0f })
        assertTrue(
            "the bubble fades over 140 ms (+ alignment), was ${tZero - tFinish}",
            tZero - tFinish in 140..200,
        )

        advanceFrames({ finishedCount >= 1 })
        assertEquals("onFinished exactly once", 1, finishedCount)
        val animator = (hooks!!.handoffAnimator as DraftHandoffAnimator)
        assertTrue("the overlays are removed", animator.overlays.sets.isEmpty())
    }

    // ── 7. reduced motion ─────────────────────────────────────────────

    /**
     * Reduced motion (read once at `run`): the 200 ms dissolve with no
     * scale, the 200 ms glide, the reveal specs with delay 0,
     * `finishArrival` 125–190 ms after the poll hit (the 0.2 s spring's
     * 1 % cut-off ≈ 153 ms + alignment), and the transcript reveal is
     * **not cancelled** by the end of the flight — it returns normally
     * at 400 ms (±2 frames).
     */
    @Test
    fun reducedMotionShortensEveryStage() {
        ReduceMotion.debugOverride = true
        val set = startPlaying()

        // The reveal specs: the same curves, no delays.
        assertEquals(
            tween<Float>(durationMillis = 180, delayMillis = 0, easing = Motion.EaseInOut),
            fake.composerSpecs.single(),
        )
        assertEquals(
            tween<Float>(durationMillis = 350, delayMillis = 0, easing = Motion.EaseOut),
            fake.transcriptSpecs.single(),
        )

        // The dissolve: 200 ms, no scale.
        assertEquals(1f, Motion.handoff(true).dissolveScale, 1e-4f)
        val t0 = firstFrameWhere { set.sceneProgress > 0f } - 16
        val tDissolveEnd = advanceFrames({ set.sceneProgress >= 1f })
        assertEquals(1f, set.sceneProgress, 1e-4f)
        assertTrue("the reduced dissolve ends by 250 ms (+2 frames), was $tDissolveEnd", tDissolveEnd <= t0 + 200 + 32)

        // The glide: 200 ms, bottom-aligned at the end.
        val tGlideEnd = advanceFrames({ set.composerRect.bottom >= ChatComposerRect.bottom - 0.5f })
        assertEquals(ChatComposerRect.bottom, set.composerRect.bottom, 0.5f)
        assertTrue("the reduced glide ends by 250 ms (+2 frames), was $tGlideEnd", tGlideEnd <= t0 + 200 + 32)

        // The flight: finishArrival 125–190 ms after the poll hit.
        advanceFrames({ fake.finishArrivalCalls > 0 })
        val tFinish = fake.callAt("finish").single()
        assertEquals(1, fake.finishArrivalCalls)
        assertTrue(
            "finishArrival 125–201 ms after the poll hit (153 + up to 3 frames), was ${tFinish - 50}",
            tFinish - 50 in 125..201,
        )

        // The transcript reveal outlives the flight and the overlays:
        // never cancelled, returning normally at 400 ms (±2 frames).
        advanceFrames({ fake.transcriptRevealDoneAt != null })
        val tRevealDone = checkNotNull(fake.transcriptRevealDoneAt) { "the transcript reveal ran" }
        assertTrue("the transcript reveal completed normally", fake.transcriptRevealCompleted)
        assertTrue("the transcript reveal was never cancelled", !fake.transcriptRevealCancelled)
        assertTrue(
            "the transcript reveal returns at 400 ms (±3 frames), was $tRevealDone",
            abs(tRevealDone - 400) <= 48,
        )
    }

    // ── 8. blank text ─────────────────────────────────────────────────

    /**
     * Blank text (`"  \n"`): the bubble draws nothing — no `userBubble`
     * (#FFFFFF) pixel in a captured frame at the landing — but the
     * flight still runs, so `finishArrival` and `onFinished` come at the
     * same times as the full-text case.
     */
    @Test
    fun blankTextFliesNothingButKeepsTheTiming() {
        val blank = fixtureHandoff(text = BlankText)
        val set = startPlaying(handoff = blank)
        assertFalse(set.textVisible)

        advanceFrames({ fake.finishArrivalCalls > 0 })
        val tFinish = fake.callAt("finish").single()
        compose.mainClock.advanceTimeByFrame() // the landing frame
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val white = firstUserBubblePixel(pixels)
        assertTrue("a userBubble pixel $white: the blank bubble must draw nothing", white == null)
        val tZero = advanceFrames({ set.bubbleAlpha <= 0f })
        advanceFrames({ finishedCount >= 1 })
        assertEquals(1, fake.finishArrivalCalls)
        assertEquals(1, finishedCount)
        assertTrue("the same timing as the full-text case, was ${tFinish - 50}", tFinish - 50 in 345..440)
        assertTrue("the fade keeps its window, was ${tZero - tFinish}", tZero - tFinish in 140..200)
    }

    // ── 9. unknown route / foreign handoff ────────────────────────────

    /** The Instant path: synchronous finish + onFinished, no overlay. */
    @Test
    fun anUnknownRouteOrForeignHandoffTakesTheInstantPath() {
        mount()
        val animator = runHandoff(handoff = fixture, route = unknownRoute)
        assertEquals("the unknown route finishes at once", 1, unknownRouteFinishes)
        assertEquals("onFinished synchronously", 1, finishedCount)
        assertTrue("no overlay added", animator.overlays.sets.isEmpty())

        val foreign: DraftHandoff = object : DraftHandoff {}
        runHandoff(handoff = foreign, route = knownRoute)
        assertEquals("the foreign handoff finishes the route at once", 1, knownRouteFinishes)
        assertEquals("onFinished synchronously, again", 2, finishedCount)
        assertTrue("still no overlay", animator.overlays.sets.isEmpty())
        assertTrue("the target is never touched", fake.calls.isEmpty())
    }

    // ── 10. teardown ──────────────────────────────────────────────────

    /**
     * Removing the host mid-flight: the `finally` runs —
     * `finishArrival()` once, `onFinished()` once, no overlay left — and
     * the hooks are back to [HandoffAnimator.Instant].
     */
    @Test
    fun teardownMidFlightFinishesOnceAndRemovesTheOverlay() {
        val animator = runHandoff()
        compose.mainClock.advanceTimeByFrame()
        val set = animator.overlays.sets.single()
        advanceTo(50)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame() // mid-glide

        compose.runOnIdle {
            hostShown.value = false
            Snapshot.sendApplyNotifications()
        }
        advanceFrames({ finishedCount >= 1 })

        assertEquals("finishArrival exactly once", 1, fake.finishArrivalCalls)
        assertEquals("onFinished exactly once", 1, finishedCount)
        assertTrue("the overlays are removed", animator.overlays.sets.isEmpty())
        assertSame("the hooks are back to Instant", HandoffAnimator.Instant, hooks!!.handoffAnimator)
        assertTrue("the overlay state is torn down", finishedAt >= 0)
    }

    // ── 11. pass-through ──────────────────────────────────────────────

    /**
     * The overlay is non-interactive: a `clickable` box under it
     * receives a click at the composer overlay's centre during the
     * glide, and the semantics tree contains no node from the overlay
     * (no click action, no text — the canvas is
     * `clearAndSetSemantics { }`).
     */
    @Test
    fun tapsPassThroughTheOverlayToTheChatUnderneath() {
        var clicks = 0
        mount(
            content = {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    // Covers the composer picture's start frame
                    // (48..248, 2000..2080 px): 16..276 dp × 640..852 dp.
                    Box(
                        Modifier
                            .offset(x = 16.dp, y = 640.dp)
                            .size(260.dp, 212.dp)
                            .testTag("under")
                            .clickable(interactionSource = null, indication = null) { clicks++ },
                    )
                }
            },
        )
        startPlaying()
        // Early glide: the composer picture still covers its start frame.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // A click at the composer picture's centre (node-local
        // (100, 120) px = root (148, 2040) px).
        compose.onNodeWithTag("under").performTouchInput { click(Offset(100f, 120f)) }
        compose.waitForIdle()
        assertEquals("the box under the overlay received the click", 1, clicks)

        assertEquals(
            "the overlay exposes no click action",
            1,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
        assertTrue(
            "the flying text is not in the semantics tree",
            compose.onAllNodes(hasText("Hello from the draft")).fetchSemanticsNodes().isEmpty(),
        )
    }

    // ── 12. registration ──────────────────────────────────────────────

    /** With the host composed, `hooks.handoffAnimator` is the animator. */
    @Test
    fun theHostRegistersTheAnimatorWithTheRouterHooks() {
        mount()
        val animator = hooks!!.handoffAnimator
        assertTrue(
            "the host registers DraftHandoffAnimator (was $animator)",
            animator is DraftHandoffAnimator,
        )
    }

    // ── the fixture's geometry and colours ────────────────────────────

    /**
     * The fixture handoff: solid-colour software bitmaps at the sizes
     * the frames name. No colour collides with `Palette.userBubble`
     * (#FFFFFF), so a white pixel can only be the bubble fill.
     */
    private fun fixtureHandoff(text: String = "Hello from the draft"): CapturedDraftHandoff {
        val scene = solidBitmap(1179, 1500, SceneColor)
        val composer = solidBitmap(200, 80, ComposerColor)
        return CapturedDraftHandoff(
            scene = scene,
            sceneFrame = Rect(0f, 0f, 1179f, 1500f),
            sceneShape = SmoothTopRoundedShape(38.dp),
            composer = composer,
            composerFrame = Rect(48f, 2000f, 248f, 2080f),
            text = text,
            textFrame = Rect(100f, 1600f, 700f, 1650f),
        )
    }

    private fun solidBitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
        return bitmap
    }

    // A companion, not instance fields: `fixture` (declared above) reads
    // SceneColor/ComposerColor during construction, before instance
    // fields declared later are initialised (they'd still be Color(0));
    // and `const` is only legal at top level / in objects.
    private companion object {
        /** The fake chat's composer capsule: bottom of the window. */
        val ChatComposerRect = Rect(48f, 2420f, 828f, 2556f)

        /** The fake first user bubble: the flight's target. */
        val BubbleDestRect = Rect(700f, 300f, 1140f, 390f)

        val SceneColor = Color(0xFF99AABB)
        val ComposerColor = Color(0xFFBBCC99)

        /** The light theme's `Palette.background` (the underlay's fill). */
        val PageBackground = Color(0xFFF3F3F5)

        const val BlankText = "  \n"
    }

    private fun close(actual: Color, expected: Color): Boolean =
        abs(actual.red - expected.red) <= 2f / 255f &&
            abs(actual.green - expected.green) <= 2f / 255f &&
            abs(actual.blue - expected.blue) <= 2f / 255f

    /**
     * The flight's landing: within the ζ 0.86 spring's ~0.5 % overshoot
     * past [dest] (the rect is never clamped — only the fill is).
     */
    private fun assertLandsOn(actual: Rect, dest: Rect, start: Rect) {
        fun near(a: Float, b: Float, from: Float, what: String) {
            assertTrue(
                "$what lands on the target (± the overshoot): $a vs $b",
                abs(a - b) <= 0.007f * abs(b - from) + 0.5f,
            )
        }
        near(actual.left, dest.left, start.left, "left")
        near(actual.top, dest.top, start.top, "top")
        near(actual.width, dest.width, start.width, "width")
        near(actual.height, dest.height, start.height, "height")
    }

    /** The first near-white (#FFFFFF, `Palette.userBubble`) pixel, or null. */
    private fun firstUserBubblePixel(pixels: PixelMap): String? {
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.red > 0.98f && c.green > 0.98f && c.blue > 0.98f) return "($x, $y)"
            }
        }
        return null
    }
}

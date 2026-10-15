package sh.roboco.android.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Macrobenchmark ports of upstream's three scroll metrics tests
 * (`apps/ios/ZeronUITests/ScrollPerformanceTests.swift:21-85`), run against
 * :app's `benchmark` build type. The ADAPT: iOS launches the lab once and
 * measures iterations on that instance; Macrobenchmark relaunches in
 * `setupBlock`, so each iteration starts fresh at the tail — numbers
 * compare Android runs with each other only, never with iOS.
 *
 * Gesture mapping (`.fast`/`.slow` are opaque XCTest constants): a fling
 * is UiAutomator's default `fling`, a `.slow` swipe is
 * `swipe(percent = 0.6, speed = 1500)`.
 */
@RunWith(AndroidJUnit4::class)
class ScrollPerformanceTest {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** The transcript host box, with Macrobenchmark's gesture margin. */
    private fun MacrobenchmarkScope.transcriptList() =
        checkNotNull(device.findObject(By.res("transcript"))) { "transcript host not found" }
            .also { it.setGestureMargin(device.displayWidth / 5) }

    /**
     * `testFlingLongTranscript` (`:21-33`): FrameTimingMetric over the
     * default 5 iterations; each iterates iOS's
     * `swipeDown(.fast)` ×2, `swipeUp(.fast)` ×2.
     */
    @Test
    fun flingLongTranscript() = rule.measureRepeated(
        packageName = TARGET,
        iterations = 5,
        metrics = listOf(FrameTimingMetric()),
        setupBlock = {
            startActivityAndWait(labIntent())
            awaitTranscript()
        },
    ) {
        val list = transcriptList()
        list.fling(Direction.UP)
        list.fling(Direction.UP)
        list.fling(Direction.DOWN)
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }

    /**
     * `testScrollWhileStreaming` (`:36-47`): the same metric over 3
     * iterations while the 45 ms fixture stream runs. The 1.5 s sleep
     * covers 135's 1 s autostream delay plus the first streamed frames.
     */
    @Test
    fun scrollWhileStreaming() = rule.measureRepeated(
        packageName = TARGET,
        iterations = 3,
        metrics = listOf(FrameTimingMetric()),
        setupBlock = {
            startActivityAndWait(labIntent(300, "autostream"))
            awaitTranscript()
            SystemClock.sleep(1_500)
        },
    ) {
        val list = transcriptList()
        // iOS `swipeDown(.slow)` then `swipeUp(.fast)`.
        list.swipe(Direction.DOWN, 0.6f, 1500)
        list.fling(Direction.DOWN)
    }

    /**
     * `testLaunchIntoLongTranscript` (`:79-85`): cold startup of the lab
     * with 300 fixture turns, 5 iterations. `StartupTimingMetric` reads
     * time-to-initial-display and time-to-fully-drawn — the lab reports
     * fully drawn once its first frame has rows (136's `ReportDrawnWhen`),
     * the `waitUntilResponsive` analogue.
     */
    @Test
    fun launchIntoLongTranscript() = rule.measureRepeated(
        packageName = TARGET,
        iterations = 5,
        metrics = listOf(StartupTimingMetric()),
        startupMode = StartupMode.COLD,
        setupBlock = {
            device.pressHome()
        },
    ) {
        startActivityAndWait(labIntent())
        awaitTranscript()
    }
}

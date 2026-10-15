package sh.roboco.android.screenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import android.os.Looper
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.transcript.TranscriptActions
import sh.roboco.android.transcript.TranscriptHost
import sh.roboco.android.transcript.TranscriptPerf
import sh.roboco.android.transcript.TranscriptState
import uniffi.roboco_core.DebugEntry
import uniffi.roboco_core.layoutFixtureMarkdown

/**
 * The transcript goldens of the parity suite (android-app#30 §3): the
 * **real core** on the host — `layoutFixtureMarkdown()` (the iOS lab's
 * fixture, `mob:layout/mod.rs:282-286`) fed through
 * `TranscriptView.setDebugEntries`, preceded by the fixture's user turn
 * "How does the layout engine avoid measuring text on the main thread?"
 * — rendered by the transcript painter at the ticket's device config
 * (393 × 852 dp at 3×, the iPhone the #874 references were taken on),
 * plus the 320 dp and 768 dp width re-measures.
 *
 * The streaming variant (`session-streaming`) appends a last entry with
 * `streaming = true` under `working = true`: the veil + the working
 * trailer, the clock frozen mid-run so the golden is byte-stable.
 *
 * Recorded by `./gradlew :app:recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug` (G1), gated by the manifest (G2) and the
 * metrics (G3: `MARGIN_X` 18); the tone reference is
 * `mobile-polish/tool-activity.png` (tone-only, pre-rewrite).
 *
 * Robolectric with NATIVE graphics; the frame wait mirrors
 * `transcript/TranscriptHostScreenshotTest` (viewport first, then
 * entries; the looper idles between polls).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class SessionTranscriptFixtureScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { TextEngine.await() }
        TranscriptPerf.reset()
    }

    // ── the fixture ─────────────────────────────────────────────────────

    /** The settled fixture: the user turn + the core's fixture markdown. */
    private fun fixtureEntries(): List<DebugEntry> = listOf(
        DebugEntry(
            id = "u1",
            user = true,
            text = "How does the layout engine avoid measuring text on the main thread?",
            streaming = false,
        ),
        DebugEntry(
            id = "a1",
            user = false,
            text = layoutFixtureMarkdown(),
            streaming = false,
        ),
    )

    /** The streaming variant: a half-arrived reply under `working`. */
    private fun streamingEntries(): List<DebugEntry> =
        fixtureEntries() + DebugEntry(
            id = "a2",
            user = false,
            text = "The layout worker holds the measurement lock only while",
            streaming = true,
        )

    // ── the goldens ─────────────────────────────────────────────────────

    @Test
    fun light393() {
        shot(Appearance.Light, "transcript/session_transcript_fixture_light_393.png", 393f, fixtureEntries(), working = false)
    }

    @Test
    fun dark393() {
        shot(Appearance.Dark, "transcript/session_transcript_fixture_dark_393.png", 393f, fixtureEntries(), working = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w320dp-h852dp-xxhdpi")
    fun light320() {
        shot(Appearance.Light, "transcript/session_transcript_fixture_light_320.png", 320f, fixtureEntries(), working = false)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w768dp-h852dp-xxhdpi")
    fun light768() {
        shot(Appearance.Light, "transcript/session_transcript_fixture_light_768.png", 768f, fixtureEntries(), working = false)
    }

    @Test
    fun streamingLight() {
        shot(Appearance.Light, "transcript/session_streaming_light.png", 393f, streamingEntries(), working = true)
    }

    // ── the host ────────────────────────────────────────────────────────

    /**
     * Feeds the entries through the real core and captures. The frame
     * wait: viewport first, then entries; a width-0 frame never builds.
     */
    private fun shot(
        appearance: Appearance,
        file: String,
        widthDp: Float,
        entries: List<DebugEntry>,
        working: Boolean,
    ) {
        val state = TranscriptState()
        try {
            state.setViewport(widthDp, 1f)
            state.engine.setDebugEntries(entries, working)
            val end = System.nanoTime() + 5_000_000_000L
            while (state.frame?.let { it.width() == widthDp && it.rowCount() > 0u } != true) {
                check(System.nanoTime() < end) { "no frame at width $widthDp within 5 s" }
                shadowOf(Looper.getMainLooper()).idleFor(ShadowChoreographer.getFrameDelay())
            }
            TranscriptPerf.reset()
            compose.setContent {
                RobocoTheme(appearance) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(RobocoTheme.palette.background),
                        ) {
                            TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (working) {
                // Freeze the veil mid-veiling (a stable, reviewable state)
                // instead of letting it settle under the idling clock.
                compose.mainClock.autoAdvance = false
                compose.mainClock.advanceTimeBy(600)
            }
            compose.onRoot().captureRoboImage(file)
        } finally {
            state.close()
        }
    }
}

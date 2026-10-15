package sh.roboco.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.hazeSource
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.MentionSuggestionsHost
import sh.roboco.android.composer.previewMorphState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.glass.LocalHazeState
import sh.roboco.android.design.glass.ProvideHazeState

/**
 * The composer goldens (android-ui-parity #78), recorded by
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.session.SessionComposerScreenshotTest`
 * and verified by `verifyRoborazziDebug`: the ticket's fixture block —
 * three [ComposerBar]s stacked in a 400 × 282 dp window (12 dp padding,
 * 376 dp composers, 8 dp gaps; heights 50 + 96 + 96), on the iOS lab
 * row's chips:
 *
 * - **A** — the resting capsule, idle, placeholder `Message Claude Code`;
 * - **B** — the card: `model "Opus 4.5"` (the Claude mark), `effort "High"`,
 *   `branch "ios-rewrite"`, `context "62% context"`;
 * - **C** — the card, running with content (the queue arrow), `pr "1234"`
 *   merged (the accent wash) and `context "91% context"` (the warning
 *   wash, `exclamationmark.circle`).
 *
 * The morphs are frozen ([previewMorphState]), so nothing animates while
 * capturing; `Glass.forceFallback = true` is the golden convention. Paths
 * are relative to `src/test/screenshots`, landing under `session/`.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h282dp-xxhdpi")
class SessionComposerScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freezeClockAndForceFallbackTier() {
        compose.mainClock.autoAdvance = false
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    // ------------------------------------------------------------ goldens

    @Test
    fun sessionComposerLight() {
        shot(Appearance.Light, "session_composer_light.png")
    }

    @Test
    fun sessionComposerDark() {
        shot(Appearance.Dark, "session_composer_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        compose.setContent {
            RobocoTheme(appearance) {
                GoldenComposerHost()
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("session/$file")
    }

    // ------------------------------------------------------------ fixture

    /** One static fixture: the morph position and the controller's state. */
    private class GoldenFixture(
        val progress: Float,
        val card: Boolean,
        val text: String,
        val running: Boolean,
        val chips: List<ComposerChip>,
        val placeholder: String,
    )

    /** The chips' shared source: the iOS lab row plus the C-only PR/context. */
    private fun goldenChips(
        pr: Boolean = false,
        tokens: ULong? = null,
        window: ULong? = null,
    ): List<ComposerChip> = sessionChips(
        ChipSource(
            harness = "claude-code",
            harnessLabel = "Claude Code",
            modelLabel = "Opus 4.5",
            reasoning = "high",
            branch = "ios-rewrite",
            prNumber = if (pr) 1234uL else null,
            prTint = if (pr) ChipTint.PrMerged else null,
            tokens = tokens,
            window = window,
        ),
    ) { level -> level.replaceFirstChar(Char::uppercase) }

    private val fixtureA = GoldenFixture(
        progress = 0f,
        card = false,
        text = "",
        running = false,
        chips = emptyList(),
        placeholder = "Message Claude Code",
    )

    private val fixtureB = GoldenFixture(
        progress = 1f,
        card = true,
        text = "",
        running = false,
        chips = goldenChips(tokens = 620uL, window = 1000uL),
        placeholder = "Message Claude Code",
    )

    private val fixtureC = GoldenFixture(
        progress = 1f,
        card = true,
        text = "Ship the release",
        running = true,
        chips = goldenChips(pr = true, tokens = 910uL, window = 1000uL),
        placeholder = "Message Claude Code",
    )

    /**
     * The host: the menu host (chips + attach), the haze state with a
     * plain backdrop plane as its source, and the mention host — the
     * session screen's own stack, minus the transcript.
     */
    @Composable
    private fun GoldenComposerHost() {
        GlassMenuHost {
            ProvideHazeState {
                MentionSuggestionsHost(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background).hazeSource(LocalHazeState.current!!))
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GoldenComposer(fixtureA)
                            GoldenComposer(fixtureB)
                            GoldenComposer(fixtureC)
                        }
                    }
                }
            }
        }
    }

    /** One fixture's composer: a controller over a frozen morph. */
    @Composable
    private fun GoldenComposer(fixture: GoldenFixture) {
        val scope = rememberCoroutineScope()
        val controller = remember(scope, fixture) {
            ComposerController(scope, NoFeedback).apply {
                text = fixture.text
                running = fixture.running
                chips = fixture.chips
                placeholder = fixture.placeholder
            }
        }
        ComposerBar(controller, previewMorphState(fixture.progress, card = fixture.card))
    }
}

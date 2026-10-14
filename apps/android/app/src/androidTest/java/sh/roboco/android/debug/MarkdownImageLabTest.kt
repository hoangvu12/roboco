package sh.roboco.android.debug

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.transcript.widgets.ImageTileReferenceKey

/**
 * The markdown-image lab flows (ticket 137; the ports of pr817's
 * `testMarkdownImageLoadsAndOpensPreview` and
 * `testMarkdownImageFailureShowsRetry`) — CI-only: no emulator in the
 * worker slots. Launches the Transcript Lab with `--ez images true
 * --ei turns 1`, so the lab's [LabImageReader] serves
 * `/workspace/calculator/5-plus-3.png` after 600 ms and answers
 * `/tmp/calculator-result/missing.png` with NotFound after 300 ms.
 *
 *  - the served tile reaches "Image loaded" with the alt text as its
 *    label; tapping it opens the viewer (the `image-preview` node with
 *    the same label, and its close button); Share fires a chooser intent;
 *    closing returns to the transcript with the tile still there;
 *  - the missing tile shows the full Failed text with the label "Missing
 *    screenshot"; tapping it retries (Loading, then Failed again) and
 *    never opens the viewer.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownImageLabTest {

    /** The lab's served image, verbatim from pr817's demo fixture. */
    private val served = "/workspace/calculator/5-plus-3.png"

    /** The missing image (outside the engine's path jail). */
    private val missing = "/tmp/calculator-result/missing.png"

    private val scenario: ActivityScenarioRule<TranscriptLabActivity> =
        ActivityScenarioRule.launch(labIntent())

    @Suppress("DEPRECATION") // the public AndroidComposeTestRule constructor is the one way to hand it a custom rule
    @get:Rule
    val compose: AndroidComposeTestRule<ActivityScenarioRule<TranscriptLabActivity>, TranscriptLabActivity> =
        AndroidComposeTestRule(scenario) { rule -> rule.mountedLabActivity() }

    @Test
    fun markdownImageLoadsAndOpensPreview() {
        Intents.init()
        try {
            compose.waitUntil(10_000) { tileState(served) == "Image loaded" }
            assertEquals("the alt text is the tile's label", "Calculation result: 8", tileLabel(served))

            tile(served).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("image-preview").assertExists()
            assertEquals(
                "the preview carries the alt text",
                "Calculation result: 8",
                compose.onNodeWithTag("image-preview").fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]?.firstOrNull(),
            )
            compose.onNodeWithTag("image-preview-close").assertExists()

            // Share: the system chooser (125's ImageShare) fires.
            compose.onNodeWithTag("image-preview-share").performClick()
            compose.waitUntil(10_000) {
                runCatching { Intents.intended(hasAction(Intent.ACTION_CHOOSER)) }.isSuccess
            }

            // Close: back to the transcript, the tile still there.
            compose.onNodeWithTag("image-preview-close").performClick()
            compose.waitForIdle()
            tile(served).assertExists()
        } finally {
            Intents.release()
        }
    }

    @Test
    fun markdownImageFailureShowsRetry() {
        compose.waitUntil(10_000) {
            val state = tileState(missing)
            state != null && state.startsWith("Image unavailable") && state.contains("Tap to retry")
        }
        assertEquals("Missing screenshot", tileLabel(missing))

        // The tap retries: a new read (Loading), then Failed again.
        tile(missing).performClick()
        compose.waitUntil(10_000) {
            val state = tileState(missing)
            state != null && state.startsWith("Image unavailable")
        }
        // A failed tile never opens the viewer.
        assertTrue(
            "no viewer for a failed tile",
            compose.onAllNodesWithTag("image-preview").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** The lab launch intent: `--ez images true --ei turns 1`. */
    private fun labIntent(): Intent =
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, TranscriptLabActivity::class.java)
            .putExtra("turns", 1)
            .putExtra("images", true)

    /** The tile's matcher, by its `ImageTileReferenceKey`. */
    private fun tileOf(reference: String) = SemanticsMatcher("transcript-image for $reference") {
        it.config.get(ImageTileReferenceKey) == reference
    }

    /** The tile's interaction. */
    private fun tile(reference: String) = compose.onNode(tileOf(reference))
    /** The tile's a11y value (`stateDescription`), or null before it exists. */
    private fun tileState(reference: String): String? =
        compose.onAllNodes(tileOf(reference)).fetchSemanticsNodes().firstOrNull()
            ?.config?.get(SemanticsProperties.StateDescription)

    /** The tile's a11y label (`contentDescription`). */
    private fun tileLabel(reference: String): String? =
        compose.onAllNodes(tileOf(reference)).fetchSemanticsNodes().firstOrNull()
            ?.config?.get(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** The lab's activity, via the scenario (the FixtureRule pattern). */
    private fun ActivityScenarioRule<TranscriptLabActivity>.mountedLabActivity(): TranscriptLabActivity {
        var activity: TranscriptLabActivity? = null
        scenario.onActivity { activity = it }
        return checkNotNull(activity) { "the lab activity never launched" }
    }
}

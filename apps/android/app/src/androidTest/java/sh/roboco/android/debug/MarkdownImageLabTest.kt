package sh.roboco.android.debug

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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

    /**
     * The served tile's alt text. The lab's extras fixture (`a0`,
     * `LAB_IMAGES_EXTRAS_FIXTURE`) reuses the same path for its First /
     * Quoted / Linked / Logo / Cell tiles, so the reference alone matched
     * whichever of those composed first ("Linked" on CI, run
     * 38531061823) — the served tile is the reference **and** this label.
     */
    private val servedAlt = "Calculation result: 8"

    /** The missing image (no file at that path on the engine). */
    private val missing = "/tmp/calculator-result/missing.png"

    private val scenario: ActivityScenarioRule<TranscriptLabActivity> =
        ActivityScenarioRule<TranscriptLabActivity>(labIntent())

    @Suppress("DEPRECATION") // the public AndroidComposeTestRule constructor is the one way to hand it a custom rule
    @get:Rule
    val compose: AndroidComposeTestRule<ActivityScenarioRule<TranscriptLabActivity>, TranscriptLabActivity> =
        AndroidComposeTestRule(scenario) { rule -> rule.mountedLabActivity() }

    @Test
    fun markdownImageLoadsAndOpensPreview() {
        Intents.init()
        try {
            compose.waitUntil(10_000) { tileState(served, servedAlt) == "Image loaded" }
            assertEquals("the alt text is the tile's label", servedAlt, tileLabel(served, servedAlt))

            tile(served, servedAlt).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("image-preview").assertExists()
            assertEquals(
                "the preview carries the alt text",
                "Calculation result: 8",
                compose.onNodeWithTag("image-preview").fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]?.firstOrNull(),
            )
            compose.onNodeWithTag("image-preview-close").assertExists()

            // Share: the system chooser (125's ImageShare) fires. Stubbed:
            // Intents.init only records, so the real chooser covered the lab
            // (paused, stopped, destroyed) and the close below found no
            // compose hierarchy (run 38552405016). A stubbed intent is still
            // recorded, so `intended` sees it.
            Intents.intending(hasAction(Intent.ACTION_CHOOSER))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithTag("image-preview-share").performClick()
            compose.waitUntil(10_000) {
                runCatching { Intents.intended(hasAction(Intent.ACTION_CHOOSER)) }.isSuccess
            }

            // Close: back to the transcript, the tile still there.
            compose.onNodeWithTag("image-preview-close").performClick()
            compose.waitForIdle()
            tile(served, servedAlt).assertExists()
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

    /**
     * The tile's matcher, by its `ImageTileReferenceKey` and, when given,
     * its alt-text label (one path can back several tiles).
     */
    private fun tileOf(reference: String, label: String? = null) =
        SemanticsMatcher("transcript-image for $reference${label?.let { " ($it)" } ?: ""}") {
            it.config.getOrNull(ImageTileReferenceKey) == reference &&
                (label == null || it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == label)
        }

    /** The tile's interaction. */
    private fun tile(reference: String, label: String? = null) = compose.onNode(tileOf(reference, label))
    /** The tile's a11y value (`stateDescription`), or null before it exists. */
    private fun tileState(reference: String, label: String? = null): String? =
        compose.onAllNodes(tileOf(reference, label)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** The tile's a11y label (`contentDescription`). */
    private fun tileLabel(reference: String, label: String? = null): String? =
        compose.onAllNodes(tileOf(reference, label)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** The lab's activity, via the scenario (the FixtureRule pattern). */
    private fun ActivityScenarioRule<TranscriptLabActivity>.mountedLabActivity(): TranscriptLabActivity {
        var activity: TranscriptLabActivity? = null
        scenario.onActivity { activity = it }
        return checkNotNull(activity) { "the lab activity never launched" }
    }
}

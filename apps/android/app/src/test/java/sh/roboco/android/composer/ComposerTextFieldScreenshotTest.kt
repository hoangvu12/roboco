package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.height
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme

/**
 * The composer text field's goldens (android-ui-parity #57): gallery
 * stages (a)–(e) in light and dark — the empty capsule with its
 * placeholder, `Ship it`, the iOS UI-test draft resting as the two-line
 * `…` preview, the same draft full in the card, and the 12-line card
 * capped at `20 + 8 × L` — each in its hairline slot frame (268 dp
 * capsule / 338 dp card) on `Palette.background`.
 *
 * The captures use roborazzi-compose's top-level [captureRoboImage] (it
 * launches its own headless activity — no compose rule), the
 * MentionStyleScreenshotTest convention. Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.ComposerTextFieldScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative
 * to the `roborazzi { outputDir }` (`src/test/screenshots`), landing
 * under `composer/`.
 *
 * The last test pins the bounds on the rule: the **field** node is
 * measured at the target height (the animated value is the
 * `composer-text-slot` clip box — ComposerTextFieldTest reads that one).
 * At xhdpi `L` ≈ 21.5 dp, so the stage heights are 50 / 50 / 71 / 106 /
 * 192 (dp), each asserted with slack for the one-pixel line rounding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class ComposerTextFieldScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composerTextFieldLight() {
        captureRoboImage("composer/composer_text_field_light.png") {
            RobocoTheme(Appearance.Light) { ComposerTextFieldGalleryStages() }
        }
    }

    @Test
    fun composerTextFieldDark() {
        captureRoboImage("composer/composer_text_field_dark.png") {
            RobocoTheme(Appearance.Dark) { ComposerTextFieldGalleryStages() }
        }
    }

    @Test
    fun theStagesMeasureTheFieldAtTheTargetHeight() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                Box(Modifier.fillMaxSize().background(Palette.Light.background)) {
                    ComposerTextFieldGalleryStages()
                }
            }
        }
        compose.waitForIdle()
        // Five stages, one field each — never two fields for one mode.
        val fields = compose.onAllNodesWithTag("composer-input")
        fields.assertCountEquals(5)
        compose.onAllNodesWithTag("composer-text-slot").assertCountEquals(5)

        fun heightDp(fields: SemanticsNodeInteractionCollection, index: Int): Float =
            fields[index].getBoundsInRoot().height.value

        // (a)+(b): the capsule's 50 dp minimum (empty or one line).
        assertEquals(50f, heightDp(fields, 0), 0.5f)
        assertEquals(50f, heightDp(fields, 1), 0.5f)
        // (c): the draft resting as the two-line preview: 28 + 2L
        // (L ≈ 21.5 dp at xhdpi) ≈ 71 — never a third sliced line.
        val preview = heightDp(fields, 2)
        assertTrue("the preview rests at two lines + insets: $preview", preview in 69.5f..72.5f)
        // (d): the full four-line draft in the card: 20 + 4L ≈ 106.
        val full = heightDp(fields, 3)
        assertTrue("the card shows the whole draft: $full", full in 104f..108f)
        // (e): the 12-line card capped at 20 + 8L ≈ 192.
        val capped = heightDp(fields, 4)
        assertTrue("the card caps at eight lines: $capped", capped in 189f..194f)
        // The preview nodes — (b) and (c), the two capsules with text — are
        // at most two lines tall. They live inside the field's decoration
        // box, so the field's merged node absorbs them: read the unmerged
        // tree.
        val previews = compose.onAllNodesWithTag("composer-input-preview", useUnmergedTree = true)
        previews.assertCountEquals(2)
        for (i in 0 until 2) {
            val previewNodeHeight = previews[i].getBoundsInRoot().height.value
            assertTrue("preview $i is ≤ 2 lines: $previewNodeHeight", previewNodeHeight <= 43.5f)
        }
    }
}

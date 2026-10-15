package sh.roboco.android.composer

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Face
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.LocalFonts
import sh.roboco.android.design.Palette
import sh.roboco.android.design.RobocoTheme
import uniffi.roboco_core.FileMatch

/**
 * The mention goldens (android-ui-parity #53): [MentionStyleDemo] with the
 * three seeded tokens, in light and dark, on the ticket's
 * `w400dp-h800dp-xxhdpi` phone. `@mod.rs`, `@lib.rs` and `@src/lib.rs` must
 * read visibly accent (`#5B43E8` light / `#8B7CF6` dark) and heavier
 * (Medium) than the surrounding Regular text, all at one size (the span
 * sets no fontSize). `showEncode = false`: the encode readout calls the
 * native core, and goldens never may.
 *
 * The captures use roborazzi-compose's top-level [captureRoboImage] (it
 * launches its own headless activity — no compose rule), as 22's
 * `GalleryCapture` does. Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.MentionStyleScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative to
 * the `roborazzi { outputDir }` (`src/test/screenshots`), landing under
 * `composer/`.
 *
 * The last three tests host the [rememberMentionTransformation] acceptance
 * checks that need a composition (the rule is per-test; the golden tests
 * do not use it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-xxhdpi")
class MentionStyleScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mentionStyleLight() {
        captureRoboImage("composer/mention_style_light.png") {
            RobocoTheme(Appearance.Light) { MentionStyleDemo(showEncode = false) }
        }
    }

    @Test
    fun mentionStyleDark() {
        captureRoboImage("composer/mention_style_dark.png") {
            RobocoTheme(Appearance.Dark) { MentionStyleDemo(showEncode = false) }
        }
    }

    private var first: VisualTransformation? = null
    private var second: VisualTransformation? = null

    @Test
    fun rememberTransformationReturnsNoneForAnEmptyIndex() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val index = remember { MentionIndex() }
                first = rememberMentionTransformation(index, null)
            }
        }
        compose.waitForIdle()
        assertSame(VisualTransformation.None, first)
    }

    @Test
    fun rememberTransformationReturnsEqualInstancesForEqualIndexes() {
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                val a = remember { MentionIndex().apply { token(FileMatch("src/lib.rs", false)) } }
                val b = remember { MentionIndex().apply { token(FileMatch("src/lib.rs", false)) } }
                first = rememberMentionTransformation(a, TextRange(4, 8))
                second = rememberMentionTransformation(b, TextRange(4, 8))
            }
        }
        compose.waitForIdle()
        assertEquals(first, second)
    }

    @Test
    fun mentionSpanStyleIsAccentMediumAtTheBodiesOwnSize() {
        var style: SpanStyle? = null
        var mediumFamily: FontFamily? = null
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                style = mentionSpanStyle()
                mediumFamily = LocalFonts.current.family(Face.SansMedium)
            }
        }
        compose.waitForIdle()
        val s = style!!
        assertEquals(Palette.Light.accent, s.color)
        assertEquals(FontWeight.Medium, s.fontWeight)
        // The span must not fix a size: it follows the field's body style.
        assertEquals(TextUnit.Unspecified, s.fontSize)
        assertEquals(mediumFamily, s.fontFamily)
    }
}

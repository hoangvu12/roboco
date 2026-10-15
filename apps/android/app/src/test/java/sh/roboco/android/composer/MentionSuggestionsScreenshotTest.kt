package sh.roboco.android.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.Glass

/**
 * The mention-card goldens (android-ui-parity #86): [MentionFixture.files]
 * on the ticket's `w400dp-h320dp-xxhdpi` phone — the 376 dp card
 * bottom-centred in a 12 dp-padded page of `palette.background`
 * (`#F3F3F5` / `#060606`), five rows: `mod.rs` / `crates/ui/src/layout`,
 * `layout` / `crates/ui/src` (folder glyph), `layout.md` /
 * `docs/reference`, `layout` / `apps/android/app/src/main/res` (folder),
 * `LAYOUT.md` with no dir line.
 *
 * `Glass.forceFallback` is set for the captures (ticket 07: Robolectric
 * blur is not byte-stable), so the card draws the fallback fill
 * `#FFFFFF`@0.94 / `#111113`@0.94 over the plain page. Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.composer.MentionSuggestionsScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`; paths land under
 * `src/test/screenshots/composer/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h320dp-xxhdpi")
class MentionSuggestionsScreenshotTest {

    @Before
    fun forceFallbackGlass() {
        Glass.forceFallback = true
    }

    @After
    fun restoreGlass() {
        Glass.forceFallback = false
    }

    @Test
    fun mentionSuggestionsLight() {
        captureRoboImage("composer/mention_suggestions_light.png") {
            RobocoTheme(Appearance.Light) { CardFixture() }
        }
    }

    @Test
    fun mentionSuggestionsDark() {
        captureRoboImage("composer/mention_suggestions_dark.png") {
            RobocoTheme(Appearance.Dark) { CardFixture() }
        }
    }

    /** The ticket's capture body: the card, stateless, no host needed. */
    @Composable
    private fun CardFixture() {
        Box(
            Modifier
                .fillMaxSize()
                .background(RobocoTheme.palette.background)
                .padding(12.dp),
        ) {
            MentionSuggestionsCard(
                MentionFixture.files,
                enabled = true,
                onPick = {},
                modifier = Modifier.width(376.dp).align(Alignment.BottomCenter),
            )
        }
    }
}

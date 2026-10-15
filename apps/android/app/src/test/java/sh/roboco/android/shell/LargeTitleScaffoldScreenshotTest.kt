package sh.roboco.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The large-title scaffold's goldens (android-ui-parity #25): the ticket's
 * fixture — "Sessions" over the fixture rows of `1-off.png` (ticket 09's
 * ten titles, repeated once for scroll range), a ⋯ trailing circle — at a
 * **fixed 24 dp status bar** (Robolectric has no real insets), captured at
 * `w412dp-h700dp-xxhdpi`, in light and dark:
 *
 * - `LargeTitle_expanded` — at rest: the large title at `navBottom + 5`, the
 *   inline title invisible, the edge effect not engaged;
 * - `LargeTitle_mid` — `c = 35`: the large title clipped from its top, the
 *   inline title at ~50 % over the blurred extension;
 * - `LargeTitle_collapsed` — the 124 dp pre-scroll (the large title + two
 *   rows − 10, ticket 09's fixture position): `c = 52`, the inline title
 *   centred, rows dissolving under the bar — the side-by-side match of
 *   `1-off.png`;
 * - `LargeTitle_inlineOnly_back` — `InlineOnly` with the back circle.
 *
 * Static initial positions (no runtime scroll), so the snap never fires
 * while capturing. Recorded by `recordRoborazziDebug`, verified by
 * `verifyRoborazziDebug`; paths are relative to `src/test/screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h700dp-xxhdpi")
class LargeTitleScaffoldScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(appearance: Appearance, file: String, content: @Composable () -> Unit) {
        compose.setContent {
            RobocoTheme(appearance) { content() }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun expandedLight() {
        shot(Appearance.Light, "shell/LargeTitle_expanded_light.png") {
            LargeTitleFixtureScaffold(showBack = false, onBack = {})
        }
    }

    @Test
    fun expandedDark() {
        shot(Appearance.Dark, "shell/LargeTitle_expanded_dark.png") {
            LargeTitleFixtureScaffold(showBack = false, onBack = {})
        }
    }

    @Test
    fun midLight() {
        shot(Appearance.Light, "shell/LargeTitle_mid_light.png") {
            LargeTitleFixtureScaffold(
                showBack = false,
                onBack = {},
                listState = rememberLargeTitleFixtureState(offsetDp = 35f),
            )
        }
    }

    @Test
    fun midDark() {
        shot(Appearance.Dark, "shell/LargeTitle_mid_dark.png") {
            LargeTitleFixtureScaffold(
                showBack = false,
                onBack = {},
                listState = rememberLargeTitleFixtureState(offsetDp = 35f),
            )
        }
    }

    @Test
    fun collapsedLight() {
        shot(Appearance.Light, "shell/LargeTitle_collapsed_light.png") {
            LargeTitleFixtureScaffold(
                showBack = false,
                onBack = {},
                listState = rememberLargeTitleFixtureState(offsetDp = 124f),
            )
        }
    }

    @Test
    fun collapsedDark() {
        shot(Appearance.Dark, "shell/LargeTitle_collapsed_dark.png") {
            LargeTitleFixtureScaffold(
                showBack = false,
                onBack = {},
                listState = rememberLargeTitleFixtureState(offsetDp = 124f),
            )
        }
    }

    @Test
    fun inlineOnlyBackLight() {
        shot(Appearance.Light, "shell/LargeTitle_inlineOnly_back_light.png") {
            LargeTitleFixtureScaffold(mode = TitleMode.InlineOnly, showBack = true, onBack = {})
        }
    }

    @Test
    fun inlineOnlyBackDark() {
        shot(Appearance.Dark, "shell/LargeTitle_inlineOnly_back_dark.png") {
            LargeTitleFixtureScaffold(mode = TitleMode.InlineOnly, showBack = true, onBack = {})
        }
    }
}

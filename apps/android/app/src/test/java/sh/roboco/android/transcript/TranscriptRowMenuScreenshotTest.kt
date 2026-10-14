package sh.roboco.android.transcript

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.core.Fonts
import sh.roboco.android.core.TextEngine
import sh.roboco.android.core.UniffiSandbox
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ContextMenuHost
import sh.roboco.android.design.LocalFeedback
import sh.roboco.android.design.NoFeedback
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.ToastHost
import sh.roboco.android.design.glass.Glass

/**
 * The row-menu goldens (android-ui-parity #128), recorded by
 * `:app:recordRoborazziDebug` and verified by `:app:verifyRoborazziDebug`:
 * the first block of the two-block reply long-pressed open, light and
 * dark, on the ticket's `w411dp-h891dp-xhdpi` device — 2 images under
 * `src/test/screenshots/transcript/`.
 *
 * The host is `TranscriptRowMenuTest`'s (real core, `RowMenuFixture`, a
 * trailing Working row, `ToastHost { ContextMenuHost { TranscriptHost } }`,
 * `NoFeedback` so the long press announces nothing) and the lift and the
 * panel are settled (`advanceTimeBy` past the 0.38 s lift and 15's 0.35 s
 * open spring), so the goldens are byte-stable. Side by side with an iOS
 * long-press on a multi-block reply: the item order Copy, Copy Message,
 * Select Text, the title-case labels, the three symbols, and 16's lifted
 * preview (inset 8 dp left/right, radius 16, `background`) under 15's
 * panel. The glass panels draw the fallback fill
 * (`Glass.forceFallback`, the golden convention); Robolectric may not
 * render the backdrop's `RenderEffect` blur — the goldens then show the
 * dim without it, which the ticket accepts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TranscriptRowMenuScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun init() {
        // The layout worker's frameReady must reach this sandbox's relay.
        UniffiSandbox.claimCallbacks()
        TextEngine.init(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { TextEngine.await() }
        // Load the fonts here too: another class's @After Fonts.resetForTest()
        // (same Robolectric sandbox) clears FontChain while TextEngine keeps
        // its built system, so await() never re-runs Fonts.load and the row
        // paints throw "FontChain not built" (verify run 38599396791). The
        // other transcript screenshot tests load it the same way.
        Fonts.load(ApplicationProvider.getApplicationContext<Context>())
        Glass.forceFallback = true
    }

    @After
    fun restoreTier() {
        Glass.forceFallback = false
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun rowMenuLight() {
        shot(Appearance.Light, "transcript/row_menu_light.png")
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
    fun rowMenuDark() {
        shot(Appearance.Dark, "transcript/row_menu_dark.png")
    }

    private fun shot(appearance: Appearance, file: String) {
        val state = fedRowMenuState()
        try {
            compose.setContent {
                RobocoTheme(appearance) {
                    // Frozen glyph clock: the Working row's trailer would
                    // otherwise run the theme's endless withFrameNanos loop,
                    // so the auto-advancing clock never idles (and the golden
                    // stays byte-stable at cycle 0).
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        CompositionLocalProvider(LocalFeedback provides NoFeedback) {
                            ToastHost {
                                ContextMenuHost {
                                    TranscriptHost(state, TranscriptActions(), Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            // The long press needs virtual time for the 400 ms timeout; the
            // advance settles the lift (0.38 s) and 15's open spring (0.35 s)
            // before the capture.
            compose.mainClock.autoAdvance = true
            compose.onAllNodesWithTag("row-markdown")[0].performTouchInput { longClick() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(file)
        } finally {
            state.close()
        }
    }
}

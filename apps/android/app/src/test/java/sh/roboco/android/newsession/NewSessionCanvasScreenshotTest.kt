package sh.roboco.android.newsession

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.composer.ComposerBar
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.rememberComposerController
import sh.roboco.android.composer.rememberComposerMorphState
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.ReduceMotion
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.WallpaperHero
import sh.roboco.android.design.WallpaperRender
import sh.roboco.android.design.glass.Glass
import sh.roboco.android.design.glass.GlassMenuHost
import sh.roboco.android.design.wallpaperFixture

/**
 * The New Session canvas's goldens (android-ui-parity #93): the ticket's
 * seven shots under `src/test/screenshots/newsession/` — the phone sheet
 * (`w411dp-h857dp-xxhdpi`) in light and dark with the cutout visible
 * behind the composer, the IME frame (`canvas_ime`), the Codex mark in
 * dark, the no-wallpaper page and the 200 dp two-line column, plus the
 * embedded tablet column (`w920dp-h800dp-xhdpi`, `canvas_embedded`).
 *
 * Robolectric with NATIVE graphics; [Glass.forceFallback] and
 * [ReduceMotion.debugOverride] follow the gallery golden convention
 * (tickets 07/22): the fallback fill and the snapped wallpaper arrival
 * keep the bytes stable. The composer is 78's real [ComposerBar] over a
 * fixture controller (the card with its two chips); the wallpaper is 21's
 * testable core over [wallpaperFixture].
 *
 * Record with `./gradlew :app:recordRoborazziDebug --tests
 * sh.roboco.android.newsession.NewSessionCanvasScreenshotTest --tests
 * sh.roboco.android.newsession.NewSessionCanvasEmbeddedScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`, then review side by
 * side with an iOS 26 simulator run of `testNewSessionFromAccessory` and
 * `testNewSessionHeadlineStaysAboveComposer`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h857dp-xxhdpi")
class NewSessionCanvasScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun stableGoldens() {
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restoreTiers() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    @Test
    fun canvasLight() {
        goldenShot(compose, Appearance.Light, "canvas_light.png")
    }

    @Test
    fun canvasDark() {
        goldenShot(compose, Appearance.Dark, "canvas_dark.png")
    }

    @Test
    fun canvasImeLight() {
        goldenShot(
            compose,
            Appearance.Light,
            "canvas_ime_light.png",
            insets = CanvasInsets(0f, 0f, 0f, 0f, 336f),
            imePad = 336.dp,
        )
    }

    @Test
    fun canvasCodexDark() {
        goldenShot(compose, Appearance.Dark, "canvas_codex_dark.png", harness = "codex")
    }

    @Test
    fun canvasNoWallpaperLight() {
        goldenShot(compose, Appearance.Light, "canvas_no_wallpaper_light.png", wallpaper = false)
    }

    @Test
    fun canvasTwoLineLight() {
        goldenShot(compose, Appearance.Light, "canvas_two_line_light.png", width = 200.dp)
    }
}

/** The embedded tablet column: `w920dp-h800dp-xhdpi`, no ✕, 44 dp row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w920dp-h800dp-xhdpi")
class NewSessionCanvasEmbeddedScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun stableGoldens() {
        Glass.forceFallback = true
        ReduceMotion.debugOverride = true
    }

    @After
    fun restoreTiers() {
        Glass.forceFallback = false
        ReduceMotion.debugOverride = null
    }

    @Test
    fun canvasEmbeddedLight() {
        goldenShot(
            compose,
            Appearance.Light,
            "canvas_embedded_light.png",
            embedded = true,
            insets = CanvasInsets(top = 24f, safeLeft = 0f, safeRight = 0f, bottom = 0f, imeAbove = 0f),
        )
    }
}

// ---------------------------------------------------------------------------
// The shared fixture (unique top-level names in this package's tests)
// ---------------------------------------------------------------------------

/**
 * One golden: the sheet (or embedded) canvas over the real [ComposerBar]
 * and 21's wallpaper core. [imePad] simulates 31's consumed IME (the
 * constraints shrink, the wallpaper keeps the IME-less height);
 * [width] narrows the page (the two-line headline); `wallpaper = false`
 * draws the plain background page. One `setContent` per test.
 */
private fun goldenShot(
    compose: ComposeContentTestRule,
    appearance: Appearance,
    file: String,
    harness: String = "claude-code",
    embedded: Boolean = false,
    insets: CanvasInsets = CanvasInsets(0f, 0f, 0f, 24f, 0f),
    imePad: Dp = 0.dp,
    wallpaper: Boolean = true,
    width: Dp? = null,
) {
    compose.setContent {
        RobocoTheme(appearance) {
            // The real ComposerBar's attach button opens its menu through
            // rememberGlassMenuState, which throws without a GlassMenuHost
            // above (production: RootHosts mounts it above every sheet).
            GlassMenuHost {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    Box(
                        Modifier
                            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxSize())
                            .padding(bottom = imePad),
                    ) {
                        val controller = rememberComposerController()
                        remember(controller) { goldenComposerConfig(controller); true }
                        val morph = rememberComposerMorphState(
                            alwaysCard = true,
                            hasImages = { controller.images.isNotEmpty() },
                        )
                        NewSessionCanvas(
                            harness = harness,
                            embedded = embedded,
                            onClose = {},
                            onBackgroundTap = { morph.blur() },
                            composer = {
                                ComposerBar(controller, morph, Modifier.testTag("new-session-composer"))
                            },
                            insets = insets,
                            wallpaper = { m, c ->
                                WallpaperHero(
                                    changeKey = Unit,
                                    load = { _ -> if (wallpaper) WallpaperRender(wallpaperFixture(), 1f) else null },
                                    modifier = m,
                                    cutout = c,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    compose.waitForIdle()
    compose.onRoot().captureRoboImage("newsession/$file")
}

/** The fixture controller: the card composer with its two fake chips. */
private fun goldenComposerConfig(controller: ComposerController) {
    controller.alwaysCard = true
    controller.placeholder = "Describe the task"
    controller.canWrite = true
    controller.chips = listOf(
        ComposerChip("project", "roboco", icon = ChipIcon.Project("roboco", 0)),
        ComposerChip("model", "Opus 4.5", icon = ChipIcon.Brand("claude-code")),
    )
}

package sh.roboco.android.settings

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import sh.roboco.android.MainActivity
import sh.roboco.android.core.WallpaperStore
import sh.roboco.android.flows.FixtureRule
import sh.roboco.android.flows.mountedActivity

/**
 * The instrumented port of `testWallpaperSettings`
 * (`iosui:SessionFlowTests.swift:591-609`, android-ui-parity #97): launch
 * with the `wallpaper` / `wallpaper-effect` / `route` extras, wait for the
 * Settings screen, check the Effect row and its Halftone subtitle, remove
 * the wallpaper, and check the section collapsed back to "Choose
 * Wallpaper…".
 *
 * Needs tickets 20 (WallpaperStore), 35 (app root), 36 (the launch
 * extras: `wallpaper` → `WallpaperStore.set(File)`, `wallpaper-effect`
 * → `setEffect("halftone")`, `route = more` → the Settings tab) and 103
 * (`settings-screen`) merged, and a debuggable build (the extras are
 * debug-only, ticket 36). CI-only: no emulator runs in the worker slots
 * or locally.
 *
 * android-app#30: the launch goes through the flow rig's [FixtureRule]
 * (the `main` engine paired via the debug `pair` extra — the Settings
 * tab only exists behind a shell, and the shell only with a paired
 * engine), carrying the wallpaper extras through [FixtureRule]'s
 * `extrasFor`.
 *
 * The compose rule wraps the [FixtureRule] (the public,
 * deprecated-but-supported [AndroidComposeTestRule] constructor — the
 * only way to hand the rule a launch built from a generated file); only
 * the compose rule is a JUnit rule, so the launch is applied exactly
 * once.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperSettingsFlowTest {

    /** A 64 × 48 solid green PNG, like the iOS test's /tmp/wall-test.png. */
    private val wallpaperFile: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF30A46C.toInt())
        val file = File(context.filesDir, "wall-test.png")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        file
    }

    /** Launched at rule application, after the field initializers ran (so the PNG exists). */
    private val scenario = FixtureRule(
        routeFor = { "more" },
        extrasFor = {
            mapOf(
                "wallpaper" to wallpaperFile.absolutePath,
                "wallpaper-effect" to "halftone",
            )
        },
    )

    init {
        // A store left over from an earlier instrumented test in the same
        // process must not win over the launch extra (applied after this).
        WallpaperStore.resetForTest()
    }

    @Suppress("DEPRECATION")
    @get:Rule
    val compose: AndroidComposeTestRule<FixtureRule, MainActivity> =
        AndroidComposeTestRule(scenario) { rule -> rule.mountedActivity() }

    @Test
    fun wallpaperSettingsFlow() {
        // iOS waits 10 s for the "Settings" nav bar; we wait for ticket
        // 103's root tag.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag("settings-screen")).fetchSemanticsNodes().isNotEmpty()
        }

        // Ticket 36 applies the `wallpaper` extra through
        // WallpaperStore.set(File), which is asynchronous — the rows may
        // still read "Choose Wallpaper…" right now. Scroll the settings
        // list until the Effect row shows up (5 s, like iOS).
        val settingsList = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("settings-screen")))
        compose.waitUntil(timeoutMillis = 5_000) {
            settingsList.performScrollToNode(hasTestTag("settings-wallpaper:effect"))
            compose.onAllNodesWithText("Effect").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Effect").assertIsDisplayed()
        compose.onNodeWithText("Halftone", substring = true).assertExists() // iOS: label BEGINSWITH "Halftone"

        // iOS scrolls the row above 70 % of the window, clear of the
        // bottom chrome, then taps it.
        settingsList.performScrollToNode(hasTestTag("settings-wallpaper:remove"))
        // The semantics action, not a touch: performScrollToNode only brings
        // the row inside the list, whose bounds run under the glass bottom
        // chrome — the injected tap landed on a field there (the IME showed,
        // run 38552405016). Removal itself is instant (no confirmation).
        compose.onNodeWithText("Remove Wallpaper").performSemanticsAction(SemanticsActions.OnClick)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Choose Wallpaper\u2026").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Effect is gone after the removal",
            compose.onAllNodesWithText("Effect").fetchSemanticsNodes().isEmpty(),
        )
    }
}

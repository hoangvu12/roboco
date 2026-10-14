package sh.roboco.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.shell.LocalNavEntry
import sh.roboco.android.shell.LocalNavStack
import sh.roboco.android.shell.NavRoute
import sh.roboco.android.shell.NavStack
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason

/**
 * The Engine detail goldens (android-ui-parity #140): the five
 * [EngineDetailCards] fixtures — connected, copied, revoked,
 * incompatible, notice — in light and dark on `Palette.background`, at
 * the ticket's `w402dp-h900dp` phone at xxhdpi (the width ticket 70's
 * geometry table is stated against), built through [engineDetailUi] over
 * 102's [EngineViewFixtures] with the same fixed clock as the design
 * gallery, plus the full [EngineDetailScreen] for fixture 1 pushed on a
 * test [NavStack] (large title expanded, `w402dp-h874dp` — the method
 * overrides the class qualifier).
 *
 * Nothing animates and the clock is fixed, so the goldens are
 * byte-stable without freezing anything.
 *
 * Record with
 * `./gradlew :app:recordRoborazziDebug --tests sh.roboco.android.settings.EngineDetailScreenshotTest`,
 * verify with `:app:verifyRoborazziDebug --tests …`. Paths are relative
 * to the `roborazzi { outputDir }` (`src/test/screenshots`), landing
 * under `settings/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h900dp-xxhdpi")
class EngineDetailScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** The ticket's fixed clock (the gallery's `EngineDetailGallery`). */
    private val now = 1_760_000_000_000L

    private val deviceId = "3f9c2a7e-5b1d-4c8e-9a6f-0d2e4b7c1a95"

    private fun connectedView(version: String = "0.9.2", notice: String? = null): EngineView = engineView(
        id = "e1",
        name = "MacBook Pro",
        state = EngineState.Connected,
        host = "192.168.1.20:27655",
        baseUrl = "http://192.168.1.20:27655",
        deviceId = deviceId,
        platform = "macos",
        version = version,
        notice = notice,
    )

    /** Fixture 1: connected, every row shown, no footer. */
    private val connectedUi = engineDetailUi(connectedView(), now)

    /** Fixture 3: parked revoked — greyed Rename…, Pair again, error footer. */
    private val revokedUi = engineDetailUi(
        engineView(
            id = "e2",
            name = "Studio",
            state = EngineState.Parked(ParkReason.REVOKED),
            lastError = "invalid credential",
            host = "studio.tail1234.ts.net:27655",
            baseUrl = "https://studio.tail1234.ts.net:27655",
            deviceId = deviceId,
            platform = "linux",
            version = "0.9.1",
            lastConnectedMs = now - 7_200_000,
        ),
        now,
    )

    /** Fixture 4: incompatible — "Update required", no Pair again. */
    private val incompatibleUi = engineDetailUi(
        engineView(
            id = "e3",
            name = "Build box",
            state = EngineState.Parked(ParkReason.INCOMPATIBLE),
            lastError = "Build box runs an older Roboco (0.8.0). Update Roboco on that device to connect.",
            host = "10.0.0.7:27655",
            baseUrl = "http://10.0.0.7:27655",
            deviceId = deviceId,
            platform = "windows",
            version = "0.8.0",
            lastConnectedMs = now - 259_200_000,
        ),
        now,
    )

    /** Fixture 5: the newer-engine notice footer. */
    private val noticeUi = engineDetailUi(
        connectedView(
            version = "0.10.0",
            notice = "MacBook Pro runs Roboco 0.10.0, newer than this app (0.9.2). " +
                "Everything should keep working; if something looks wrong, update the app.",
        ),
        now,
    )

    private fun shotCards(appearance: Appearance, file: String, ui: EngineDetailUi, copied: Boolean = false) {
        compose.setContent {
            RobocoTheme(appearance) {
                Box(Modifier.fillMaxSize().background(RobocoTheme.palette.background)) {
                    EngineDetailCards(
                        ui = ui,
                        copied = copied,
                        forgetPhase = ForgetPhase.Idle,
                        onCopyId = {},
                        onRename = {},
                        onPairAgain = {},
                        onForget = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(file)
    }

    @Test
    fun connectedLight() {
        shotCards(Appearance.Light, "settings/engine_detail_connected_light.png", connectedUi)
    }

    @Test
    fun connectedDark() {
        shotCards(Appearance.Dark, "settings/engine_detail_connected_dark.png", connectedUi)
    }

    @Test
    fun copiedLight() {
        shotCards(Appearance.Light, "settings/engine_detail_copied_light.png", connectedUi, copied = true)
    }

    @Test
    fun copiedDark() {
        shotCards(Appearance.Dark, "settings/engine_detail_copied_dark.png", connectedUi, copied = true)
    }

    @Test
    fun revokedLight() {
        shotCards(Appearance.Light, "settings/engine_detail_revoked_light.png", revokedUi)
    }

    @Test
    fun revokedDark() {
        shotCards(Appearance.Dark, "settings/engine_detail_revoked_dark.png", revokedUi)
    }

    @Test
    fun incompatibleLight() {
        shotCards(Appearance.Light, "settings/engine_detail_incompatible_light.png", incompatibleUi)
    }

    @Test
    fun incompatibleDark() {
        shotCards(Appearance.Dark, "settings/engine_detail_incompatible_dark.png", incompatibleUi)
    }

    @Test
    fun noticeLight() {
        shotCards(Appearance.Light, "settings/engine_detail_notice_light.png", noticeUi)
    }

    @Test
    fun noticeDark() {
        shotCards(Appearance.Dark, "settings/engine_detail_notice_dark.png", noticeUi)
    }

    /** The full screen for fixture 1, pushed, large title expanded (h874). */
    @Test
    @Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
    fun screenLight() {
        val stack = NavStack(ScreenRoot)
        stack.push(EngineDetailRoute("e1"), animated = false)
        val pushed = stack.entries.last()
        val flow = MutableStateFlow<List<EngineView>?>(listOf(connectedView()))
        compose.setContent {
            RobocoTheme(Appearance.Light) {
                CompositionLocalProvider(
                    LocalNavStack provides stack,
                    LocalNavEntry provides pushed,
                ) {
                    EngineDetailScreen(
                        engineId = "e1",
                        engines = flow,
                        backend = ScreenshotBackend,
                        forgetDrafts = ForgetDrafts { _, _ -> },
                        forgetContext = { ForgetDraftsContext(snapshot = null, enginesLeft = 0) },
                        nowMs = { now },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("settings/engine_detail_screen_light.png")
    }

    /** The Settings root the golden's stack starts from. */
    private object ScreenRoot : NavRoute {
        override val paneTitle: String get() = "Settings"
    }

    /** Never called: the golden never taps. */
    private object ScreenshotBackend : EngineDetailBackend {
        override suspend fun rename(engineId: String, name: String) {}
        override suspend fun revokeOwnSession(engineId: String) {}
        override suspend fun forget(engineId: String) {}
        override suspend fun removeStored(engineId: String) {}
    }
}

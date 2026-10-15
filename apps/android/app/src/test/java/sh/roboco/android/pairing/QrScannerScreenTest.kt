package sh.roboco.android.pairing

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Robolectric.buildActivity
import sh.roboco.android.R
import sh.roboco.android.design.Appearance
import sh.roboco.android.design.RobocoTheme

/**
 * The scanner screen's geometry, caption states, foreign timer and
 * settings intent (android-ui-parity #138) — Robolectric + compose-ui-test
 * on the ticket's device (`w393dp-h852dp-xxhdpi`, density 3, Robolectric's
 * zero system-bar insets): the safe area is faked through
 * [QrScannerContent]'s `insets` parameter, a 24 dp status bar (72 px), so
 * the ✕ sits at top = 24 + 8 = 32 dp exactly as the acceptance table
 * measures it.
 *
 * - ✕: 44 × 44 dp, 16 dp from the safe-area start, 8 dp below the bar;
 * - reticle: 240 × 240 dp centred in the 393 × 852 window (± 0.5 dp),
 *   caption top = reticle bottom + 24, capsule top = caption bottom + 16,
 *   both centred on the window (never on the reticle-plus-caption group);
 * - the caption text per state, and the polite live region;
 * - the foreign caption's real 2 s reset (the gallery's
 *   `LaunchedEffect` + `delay` timer) at the frozen clock: 1999 ms → still
 *   foreign, +2 ms → hint;
 * - `qr-open-settings` click → `ACTION_APPLICATION_DETAILS_SETTINGS`
 *   with `package:sh.roboco.android` data, through the activity's shared
 *   [openAppDetailsSettings] on a Robolectric activity.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi")
class QrScannerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun freezeClock() {
        compose.mainClock.autoAdvance = false
    }

    /** The ticket's faked safe area: a 24 dp status bar (72 px at xxhdpi). */
    private fun barInsets(): WindowInsets = with(compose.density) {
        WindowInsets(0, 24.dp.roundToPx(), 0, 0)
    }

    private fun host(
        state: QrScannerUiState,
        insets: WindowInsets = barInsets(),
        onOpenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                QrScannerContent(
                    state = state,
                    onClose = {},
                    onOpenSettings = onOpenSettings,
                    insets = insets,
                ) { FakeCameraPreview() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = with(compose.density) {
        compose.onNodeWithTag(tag).getBoundsInRoot().toRect()
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun theCloseButtonFollowsTheSafeAreaPlacement() {
        host(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        val d = compose.density.density
        val close = bounds(QrCloseTag)
        // 44 × 44 dp; left edge 16 dp from the safe-area start (0 here),
        // top 8 dp below the 24 dp bar → 32 dp.
        assertEquals(44f * d, close.width, 1f)
        assertEquals(44f * d, close.height, 1f)
        assertEquals(16f * d, close.left, 1f)
        assertEquals((24f + 8f) * d, close.top, 1f)
    }

    @Test
    fun theReticleIsCentredInTheWindow() {
        host(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        val d = compose.density.density
        val reticle = bounds(QrReticleTag)
        assertEquals(240f * d, reticle.width, 1f)
        assertEquals(240f * d, reticle.height, 1f)
        // (393 − 240) / 2 = 76.5 dp, (852 − 240) / 2 = 306 dp — Int
        // placement rounds, so 0.5 px of tolerance (0.17 dp) is allowed.
        assertEquals((393f - 240f) / 2f * d, reticle.left, 0.6f)
        assertEquals((852f - 240f) / 2f * d, reticle.top, 1f)
    }

    @Test
    fun theReticleStrokeHidesWhenTheCameraIsDead() {
        // The 240 dp slot stays (the caption does not jump) — only the
        // stroke is not drawn. Same slot, so same bounds in both phases.
        val state = mutableStateOf(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                QrScannerContent(
                    state = state.value,
                    onClose = {},
                    onOpenSettings = {},
                    insets = barInsets(),
                ) { FakeCameraPreview() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val scanning = bounds(QrReticleTag)

        state.value = QrScannerUiState(ScannerPhase.Denied, showForeign = false)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // the frozen clock: one frame applies the write
        compose.waitForIdle()
        val denied = bounds(QrReticleTag)
        assertEquals(scanning.left, denied.left, 0.5f)
        assertEquals(scanning.top, denied.top, 0.5f)
        assertEquals(scanning.width, denied.width, 0.5f)
    }

    @Test
    fun theCaptionSitsTwentyFourDpBelowTheReticleAndIsCentred() {
        host(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        val d = compose.density.density
        val reticle = bounds(QrReticleTag)
        val caption = bounds(QrCaptionTag)
        assertEquals(reticle.bottom + 24f * d, caption.top, 1f)
        // Centred on the window — not on the reticle-plus-caption group.
        assertEquals(393f * d / 2f, (caption.left + caption.right) / 2f, 2f)
        // At most window − 2 × 32 dp wide.
        assertTrue(caption.width <= 329f * d + 1f)
    }

    @Test
    fun theOpenSettingsCapsuleSitsSixteenDpBelowTheCaption() {
        host(QrScannerUiState(ScannerPhase.Denied, showForeign = false))
        val d = compose.density.density
        val caption = bounds(QrCaptionTag)
        val button = bounds(QrOpenSettingsTag)
        assertEquals(caption.bottom + 16f * d, button.top, 1f)
        assertEquals(393f * d / 2f, (button.left + button.right) / 2f, 2f)
        assertTrue("min height 44 dp, was ${button.height}", button.height >= 44f * d - 1f)
    }

    @Test
    fun theCapsuleExistsOnlyInTheDeniedState() {
        host(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        compose.onNodeWithTag(QrOpenSettingsTag).assertDoesNotExist()
    }

    // -------------------------------------------------------------- captions

    @Test
    fun theCaptionTextFollowsTheState() {
        val state = mutableStateOf(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                QrScannerContent(
                    state = state.value,
                    onClose = {},
                    onOpenSettings = {},
                    insets = barInsets(),
                ) { FakeCameraPreview() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(context.getString(R.string.qr_hint)).assertExists()

        state.value = QrScannerUiState(ScannerPhase.Scanning, showForeign = true)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // the frozen clock: one frame applies the write
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.qr_foreign)).assertExists()

        state.value = QrScannerUiState(ScannerPhase.Denied, showForeign = false)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // the frozen clock: one frame applies the write
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.qr_denied)).assertExists()
        compose.onNodeWithTag(QrOpenSettingsTag).assertExists()

        state.value = QrScannerUiState(ScannerPhase.Unavailable, showForeign = false)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame() // the frozen clock: one frame applies the write
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.qr_unavailable)).assertExists()
    }

    @Test
    fun theCaptionIsAPoliteLiveRegionAndThePaneHasATitle() {
        host(QrScannerUiState(ScannerPhase.Scanning, showForeign = false))
        assertEquals(
            LiveRegionMode.Polite,
            compose.onNodeWithTag(QrCaptionTag).fetchSemanticsNode().config[SemanticsProperties.LiveRegion],
        )
        assertEquals(
            context.getString(R.string.pair_scan),
            compose.onNodeWithTag(QrScannerTag).fetchSemanticsNode().config[SemanticsProperties.PaneTitle],
        )
        // The preview and the reticle are pure chrome: hidden from
        // accessibility, nothing to announce.
        assertNotNull(
            compose.onNodeWithTag(QrPreviewTag).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.HideFromAccessibility),
        )
        assertNotNull(
            compose.onNodeWithTag(QrReticleTag).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.HideFromAccessibility),
        )
    }

    // -------------------------------------------------------- foreign timer

    @Test
    fun theForeignCaptionTimesOutAfterTwoSeconds() {
        val state = mutableStateOf(QrScannerUiState(ScannerPhase.Scanning, showForeign = true))
        compose.setContent {
            RobocoTheme(Appearance.Dark) {
                // The gallery's timer — the same 2 s reset the activity
                // runs in lifecycleScope.
                val showForeign = state.value.showForeign
                LaunchedEffect(showForeign) {
                    if (showForeign) {
                        delay(2000)
                        state.value = state.value.copy(showForeign = false)
                    }
                }
                QrScannerContent(
                    state = state.value,
                    onClose = {},
                    onOpenSettings = {},
                    insets = barInsets(),
                ) { FakeCameraPreview() }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(context.getString(R.string.qr_foreign)).assertExists()

        // t = 16 (the frame) + 1999 = 2015 < 2016: still foreign.
        compose.mainClock.advanceTimeBy(1999, ignoreFrameDuration = true)
        compose.onNodeWithText(context.getString(R.string.qr_foreign)).assertExists()

        // +2 ms → the 2000 ms delay fires and the hint comes back.
        compose.mainClock.advanceTimeBy(2, ignoreFrameDuration = true)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(context.getString(R.string.qr_hint)).assertExists()
    }

    // ------------------------------------------------------- Open Settings

    @Test
    fun theOpenSettingsCapsuleLaunchesTheAppSettingsPage() {
        val activity = buildActivity(Activity::class.java).setup().get()
        host(
            QrScannerUiState(ScannerPhase.Denied, showForeign = false),
            onOpenSettings = { openAppDetailsSettings(activity) },
        )
        compose.onNodeWithTag(QrOpenSettingsTag).performClick()
        compose.waitForIdle()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started!!.action)
        assertEquals(Uri.fromParts("package", "sh.roboco.android", null), started.data)
    }
}

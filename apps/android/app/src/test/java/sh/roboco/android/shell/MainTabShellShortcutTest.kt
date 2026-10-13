package sh.roboco.android.shell

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.roboco.android.design.ProvideGlyphClock
import sh.roboco.android.design.RobocoTheme
import sh.roboco.android.design.glass.GlassMenuHost

/**
 * The phone shell's one shortcut line (android-ui-parity #33, the
 * `MainTabShell` edit): with a [ShortcutSink] provided, the shell's root
 * registers on it — so the helper lists New Session + Search (no Toggle
 * Sidebar), Ctrl+N / Ctrl+Shift+F are consumed through the sink, and
 * Ctrl+B falls through unconsumed (no sidebar to toggle on the phone;
 * iPad keeps a silent no-op while collapsed, Android phones never show
 * the split).
 *
 * Robolectric + Compose: [MainTabShell] composes its own phone router
 * (#32), so the dispatch return is the consumption observable — the
 * router's own behaviour is 32's tested territory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w402dp-h874dp-xxhdpi")
class MainTabShellShortcutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sink = ShortcutSink()

    private fun ctrlKey(code: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)

    @Test
    fun theShellRegistersItsShortcutsOnTheSink() {
        compose.setContent {
            RobocoTheme {
                CompositionLocalProvider(LocalShortcutSink provides sink) {
                    // Ticket 46: the real Sessions root needs the menu host
                    // and the frozen glyph clock (the loading spinner).
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        GlassMenuHost { MainTabShell(state = MainTabState(TabId.Sessions)) }
                    }
                }
            }
        }
        compose.waitForIdle()

        // The shell root mounted: New Session and Search are listed, but
        // never Toggle Sidebar (the split isn't up).
        assertTrue(sink.shellMounted)
        assertFalse(sink.splitMounted)
    }

    @Test
    fun ctrlNAndCtrlShiftFAreConsumedCtrlBIsNot() {
        compose.setContent {
            RobocoTheme {
                CompositionLocalProvider(LocalShortcutSink provides sink) {
                    ProvideGlyphClock(frozenAtNanos = 0L) {
                        GlassMenuHost { MainTabShell(state = MainTabState(TabId.Sessions)) }
                    }
                }
            }
        }
        compose.waitForIdle()

        // The shell's handler consumed them (the router's
        // presentNewSession(null) / showSearch() ran — dispatchShellShortcut
        // returns true exactly when it called the router).
        assertTrue(sink.dispatch(ctrlKey(KeyEvent.KEYCODE_N)))
        assertTrue(
            sink.dispatch(
                KeyEvent(
                    0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or
                        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                ),
            ),
        )
        // No tablet shell: Ctrl+B is not consumed.
        assertFalse(sink.dispatch(ctrlKey(KeyEvent.KEYCODE_B)))
    }
}

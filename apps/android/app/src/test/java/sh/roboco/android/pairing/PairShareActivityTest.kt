package sh.roboco.android.pairing

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.roboco.android.MainActivity

/**
 * The share trampoline (android-ui-parity #139): `PairShareActivity`
 * builds everything in `onCreate` (a `Theme.NoDisplay` activity must
 * finish before `onResume`) and forwards into the app's own task —
 * a candidate-bearing text becomes an explicit `ACTION_SEND` to
 * [MainActivity] with `NEW_TASK | CLEAR_TOP | SINGLE_TOP` and only the
 * surviving candidates as the extra (trimmed of the trailing `.`); any
 * other text becomes the launcher-icon `ACTION_MAIN`/`LAUNCHER` intent,
 * and a missing extra is the same, without a crash.
 *
 * Robolectric records the forwarded intent on the application's shadow
 * (`ShadowContextWrapper.getNextStartedActivity` — the single
 * process-wide instrumentation list). The candidates come from the pure
 * [PairingLinks.shareCandidates]; no FFI runs here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PairShareActivityTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aSharedPairingLinkIsForwardedToMainActivityAsASend() {
        val activity = trampoline("see http://h/pair#token=abc.")

        val forward = shadowOf(app).nextStartedActivity
        assertNotNull(forward)
        assertEquals(Intent.ACTION_SEND, forward!!.action)
        assertEquals(MainActivity::class.java.name, forward.component?.className)
        assertEquals("text/plain", forward.type)
        // Only the surviving candidate, punctuation trimmed.
        assertEquals("http://h/pair#token=abc", forward.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun textWithoutAPairingLinkOpensTheAppNormally() {
        val activity = trampoline("hello")

        val forward = shadowOf(app).nextStartedActivity
        assertNotNull(forward)
        assertEquals(Intent.ACTION_MAIN, forward!!.action)
        assertTrue(forward.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertFalse(forward.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertFalse(forward.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertFalse(forward.hasExtra(Intent.EXTRA_TEXT))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun missingExtrasOpenTheAppNormallyWithoutCrashing() {
        val activity = trampoline(null)

        val forward = shadowOf(app).nextStartedActivity
        assertNotNull(forward)
        assertEquals(Intent.ACTION_MAIN, forward!!.action)
        assertTrue(forward.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(forward.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(activity.isFinishing)
    }

    // ------------------------------------------------------------- rig

    /**
     * Builds the trampoline with a SEND intent (with or without
     * `EXTRA_TEXT`) and runs it through `create()` only — never
     * `setup()`: the real activity finishes inside `onCreate`.
     */
    private fun trampoline(text: String?): PairShareActivity {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
        return Robolectric.buildActivity(PairShareActivity::class.java, intent).create().get()
    }
}

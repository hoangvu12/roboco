package sh.roboco.android.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production entry point against the real merged assets (Robolectric;
 * the test task depends on `syncRobocoFonts`, so all 12 faces are there):
 * the APK the gate checks at startup must pass it in every build type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FontGateAssetsTest {

    @Test
    fun theMergedAssetsPassTheGate() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // debug = true: a bad pack would throw here, exactly as it does in
        // RobocoApplication.onCreate.
        val status = FontGate.check(context.assets, debug = true)
        assertSame(FontStatus.Ok, status)
        assertSame(FontStatus.Ok, FontGate.status)
    }
}

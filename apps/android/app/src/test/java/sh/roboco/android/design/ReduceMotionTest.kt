package sh.roboco.android.design

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reduce Motion (ticket 04): the system "remove animations" state read live
 * through `ANIMATOR_DURATION_SCALE` / `TRANSITION_ANIMATION_SCALE`, the debug
 * override, and the Compose local's live flip.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReduceMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ReduceMotion.resetForTest()
    }

    /** Writes a scale, dispatches the change to the registered observer, runs the main looper. */
    private fun putGlobalScale(name: String, value: Float) {
        Settings.Global.putFloat(context.contentResolver, name, value)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(name), null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun defaultsToFullMotionWhenNoScalesAreSet() {
        ReduceMotion.install(context)
        assertFalse(ReduceMotion.isReduced)
    }

    @Test
    fun animatorScaleZeroMeansReducedAndBack() {
        ReduceMotion.install(context)

        putGlobalScale(Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(ReduceMotion.isReduced)

        putGlobalScale(Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(ReduceMotion.isReduced)
    }

    @Test
    fun transitionScaleZeroAloneMeansReduced() {
        ReduceMotion.install(context)

        putGlobalScale(Settings.Global.TRANSITION_ANIMATION_SCALE, 0f)
        assertTrue(ReduceMotion.isReduced)
    }

    @Test
    fun debugOverrideWinsOverTheSystemState() {
        ReduceMotion.install(context)

        // Forced reduced while the system is at full motion.
        ReduceMotion.debugOverride = true
        assertTrue(ReduceMotion.isReduced)

        // Forced full motion while the system asks for reduced.
        putGlobalScale(Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        ReduceMotion.debugOverride = false
        assertFalse(ReduceMotion.isReduced)

        // null falls back to the system state.
        ReduceMotion.debugOverride = null
        assertTrue(ReduceMotion.isReduced)
    }

    @Test
    fun localReduceMotionFlipsLiveWithoutRecreatingTheComposition() {
        ReduceMotion.install(context)
        val observed = mutableListOf<Boolean>()
        var rememberedAnchor: Any? = null

        compose.setContent {
            ProvideReduceMotion {
                // The remembered object survives recomposition: if the
                // composition were recreated it would be a new instance.
                rememberedAnchor = remember { Any() }
                observed.add(LocalReduceMotion.current)
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(false), observed)
        val firstAnchor = rememberedAnchor

        putGlobalScale(Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.waitForIdle()

        assertEquals(listOf(false, true), observed)
        assertSame("the composition was recreated, not recomposed", firstAnchor, rememberedAnchor)
    }
}

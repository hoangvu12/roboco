package sh.roboco.android.design

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The core's `project_color_count` (android-app#07) must size the same tone
 * table the palette indexes: `Palette.PROJECT_TONE_COUNT`.
 *
 * Robolectric like android-app#13's `CoreLoadTest` (the generated bindings
 * reference `android.os.Build`).
 *
 * android-app#07 is not merged yet, so the export is absent from the
 * generated bindings; the call is made reflectively to keep this file
 * compiling until then. When 07 merges: drop the @Ignore, call
 * `uniffi.roboco_core.projectColorCount()` directly and delete this comment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectColorCountTest {

    @Test
    @Ignore("needs android-app#07 project_color_count")
    fun projectColorCountMatchesThePalette() {
        val bindings = Class.forName("uniffi.roboco_core.Roboco_coreKt")
        val count = bindings.getMethod("projectColorCount").invoke(null) as UInt
        assertEquals(Palette.PROJECT_TONE_COUNT.toUInt(), count)
    }
}

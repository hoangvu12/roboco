package sh.roboco.android.design

import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the subset of the palette the desktop theme artifact also defines
 * (`web/packages/theme/src/generated/artifact.json`, generated from
 * `crates/theme/src/builtins.rs`). iOS is the visual reference, so only the
 * roles the two *share* must agree; every intentional divergence
 * (light background = builtin `shell`, light text `#27272C`, `tertiary`,
 * `controlFill`, …) is simply not pinned here.
 *
 * Robolectric: `org.json` is real under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeArtifactPinTest {

    private fun diverged(role: String): String =
        "$role diverged from the desktop theme (`crates/theme/src/builtins.rs`); " +
            "decide deliberately whether iOS/Android should follow"

    private fun artifactVariant(id: String): JSONObject {
        val path = File(TestRepoRoot.root(), "web/packages/theme/src/generated/artifact.json")
        val families = JSONObject(path.readText()).getJSONArray("families")
        for (i in 0 until families.length()) {
            val family = families.getJSONObject(i)
            if (family.getString("id") != "roboco") continue
            val variants = family.getJSONArray("variants")
            for (j in 0 until variants.length()) {
                val variant = variants.getJSONObject(j)
                if (variant.getString("id") == id) return variant
            }
        }
        throw AssertionError("variant $id not found in $path")
    }

    /** `#RRGGBB` → 0xFFRRGGBB, or `#RRGGBBAA` → 0xAARRGGBB. */
    private fun argbOf(hex: String): Int {
        val body = hex.removePrefix("#")
        return when (body.length) {
            6 -> (0xFF shl 24) or body.toInt(16)
            8 -> (body.substring(6).toInt(16) shl 24) or body.substring(0, 6).toInt(16)
            else -> error("unexpected hex length in artifact: $hex")
        }
    }

    /** `#RRGGBB(AA)` → RGB only, for the alpha-carrying palette roles. */
    private fun rgbOf(hex: String): Int = argbOf(hex) and 0xFFFFFF

    private fun assertEqualsArgb(message: String, expectedHex: String, actual: Int) =
        assertEquals(message, argbOf(expectedHex), actual)

    private fun assertEqualsRgb(message: String, expectedHex: String, actual: Int) =
        assertEquals(message, rgbOf(expectedHex), actual and 0xFFFFFF)

    /**
     * The shared subset, parameterised by appearance. Light pins its
     * background to the builtin `shell` (iOS's choice), dark to `background`;
     * only dark's `text` matches a builtin.
     */
    private fun assertSharedSubset(variantId: String, palette: Palette, isDark: Boolean) {
        val v = artifactVariant(variantId)
        val colors = v.getJSONObject("colors")
        val accent = v.getJSONObject("accent")
        assertEqualsArgb(diverged("accent"), accent.getString("primary"), palette.accent.toArgb())
        assertEqualsArgb(diverged("danger"), colors.getString("danger"), palette.danger.toArgb())
        assertEqualsArgb(diverged("success"), colors.getString("success"), palette.success.toArgb())
        assertEqualsArgb(diverged("warning"), colors.getString("warning"), palette.warning.toArgb())
        assertEqualsRgb(diverged("secondary"), colors.getString("textMuted"), palette.secondary.toArgb())
        assertEqualsRgb(diverged("subline"), colors.getString("textMuted"), palette.subline.toArgb())
        assertEqualsRgb(diverged("status.time"), colors.getString("textMuted"), palette.status.time.toArgb())
        assertEqualsRgb(diverged("accentSoft"), accent.getString("primary"), palette.accentSoft.toArgb())
        assertEqualsRgb(diverged("status.working"), accent.getString("primary"), palette.status.working.toArgb())
        assertEqualsRgb(diverged("status.input"), accent.getString("primary"), palette.status.input.toArgb())
        assertEqualsRgb(diverged("status.failed"), colors.getString("danger"), palette.status.failed.toArgb())
        assertEqualsRgb(diverged("status.done"), colors.getString("success"), palette.status.done.toArgb())
        assertEqualsArgb(diverged("selectionBackground"), accent.getString("selection"), palette.selectionBackground.toArgb())
        assertEqualsRgb(
            diverged("background"),
            colors.getString(if (isDark) "background" else "shell"),
            palette.background.toArgb(),
        )
        if (isDark) {
            assertEqualsRgb(diverged("text"), colors.getString("text"), palette.text.toArgb())
        }
    }

    @Test
    fun lightVariantMatchesTheSharedSubset() {
        assertSharedSubset("roboco-light", Palette.Light, isDark = false)
    }

    @Test
    fun darkVariantMatchesTheSharedSubset() {
        assertSharedSubset("roboco-dark", Palette.Dark, isDark = true)
    }
}

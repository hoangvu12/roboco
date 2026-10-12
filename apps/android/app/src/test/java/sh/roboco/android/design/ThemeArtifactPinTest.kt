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
            8 -> (body.substring(2).toInt(16) shl 24) or body.substring(0, 2).toInt(16)
            else -> error("unexpected hex length in artifact: $hex")
        }
    }

    private fun JSONObject.colors(): JSONObject = getJSONObject("colors")

    private fun JSONObject.accent(): JSONObject = getJSONObject("accent")

    @Test
    fun lightVariantMatchesTheSharedSubset() {
        val v = artifactVariant("roboco-light")
        assertEquals(diverged("accent"), argbOf(v.accent().getString("primary")), Palette.Light.accent.toArgb())
        assertEquals(diverged("danger"), argbOf(v.colors().getString("danger")), Palette.Light.danger.toArgb())
        assertEquals(diverged("success"), argbOf(v.colors().getString("success")), Palette.Light.success.toArgb())
        assertEquals(diverged("warning"), argbOf(v.colors().getString("warning")), Palette.Light.warning.toArgb())
        // RGB-only comparisons for colours the artifact stores opaque:
        assertEquals(diverged("secondary"), v.colors().getString("textMuted").toIntRgb(), Palette.Light.secondary.toArgb() and 0xFFFFFF)
        assertEquals(diverged("subline"), v.colors().getString("textMuted").toIntRgb(), Palette.Light.subline.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.time"), v.colors().getString("textMuted").toIntRgb(), Palette.Light.status.time.toArgb() and 0xFFFFFF)
        // iOS draws the builtin shell (#f3f3f5), not colors.background (#ffffff).
        assertEquals(diverged("background"), v.colors().getString("shell").toIntRgb(), Palette.Light.background.toArgb() and 0xFFFFFF)
        assertEquals(diverged("accentSoft"), v.accent().getString("primary").toIntRgb(), Palette.Light.accentSoft.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.working"), v.accent().getString("primary").toIntRgb(), Palette.Light.status.working.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.input"), v.accent().getString("primary").toIntRgb(), Palette.Light.status.input.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.failed"), v.colors().getString("danger").toIntRgb(), Palette.Light.status.failed.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.done"), v.colors().getString("success").toIntRgb(), Palette.Light.status.done.toArgb() and 0xFFFFFF)
        assertEquals(diverged("selectionBackground"), argbOf(v.accent().getString("selection")), Palette.Light.selectionBackground.toArgb())
    }

    @Test
    fun darkVariantMatchesTheSharedSubset() {
        val v = artifactVariant("roboco-dark")
        assertEquals(diverged("accent"), argbOf(v.accent().getString("primary")), Palette.Dark.accent.toArgb())
        assertEquals(diverged("danger"), argbOf(v.colors().getString("danger")), Palette.Dark.danger.toArgb())
        assertEquals(diverged("success"), argbOf(v.colors().getString("success")), Palette.Dark.success.toArgb())
        assertEquals(diverged("warning"), argbOf(v.colors().getString("warning")), Palette.Dark.warning.toArgb())
        assertEquals(diverged("secondary"), v.colors().getString("textMuted").toIntRgb(), Palette.Dark.secondary.toArgb() and 0xFFFFFF)
        assertEquals(diverged("subline"), v.colors().getString("textMuted").toIntRgb(), Palette.Dark.subline.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.time"), v.colors().getString("textMuted").toIntRgb(), Palette.Dark.status.time.toArgb() and 0xFFFFFF)
        // Dark draws the builtin background (#060606).
        assertEquals(diverged("background"), v.colors().getString("background").toIntRgb(), Palette.Dark.background.toArgb() and 0xFFFFFF)
        assertEquals(diverged("text"), v.colors().getString("text").toIntRgb(), Palette.Dark.text.toArgb() and 0xFFFFFF)
        assertEquals(diverged("accentSoft"), v.accent().getString("primary").toIntRgb(), Palette.Dark.accentSoft.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.working"), v.accent().getString("primary").toIntRgb(), Palette.Dark.status.working.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.input"), v.accent().getString("primary").toIntRgb(), Palette.Dark.status.input.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.failed"), v.colors().getString("danger").toIntRgb(), Palette.Dark.status.failed.toArgb() and 0xFFFFFF)
        assertEquals(diverged("status.done"), v.colors().getString("success").toIntRgb(), Palette.Dark.status.done.toArgb() and 0xFFFFFF)
        assertEquals(diverged("selectionBackground"), argbOf(v.accent().getString("selection")), Palette.Dark.selectionBackground.toArgb())
    }

    /** `#RRGGBB(AA)` → RGB only, for the alpha-carrying palette roles. */
    private fun String.toIntRgb(): Int = argbOf(this) and 0xFFFFFF
}

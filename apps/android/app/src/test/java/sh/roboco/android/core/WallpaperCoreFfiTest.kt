package sh.roboco.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.roboco_core.WallpaperEffect

/**
 * The `WallpaperCore.Ffi` seam against the real host library
 * (android-app#10, `jna.library.path` set by Gradle): the Rust test
 * anchors of `crates/mobile/src/wallpaper.rs:258-282`, re-run from Kotlin.
 *
 * Robolectric: the generated Kotlin references `android.os.Build`
 * (`android = true` in uniffi.toml), as `CoreLoadTest` documents.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WallpaperCoreFfiTest {

    /** Straight RGBA8, row-major: `rgb` repeated over `w * h` pixels. */
    private fun solid(w: Int, h: Int, rgb: Int): ByteArray {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return ByteArray(w * h * 4).also { bytes ->
            var i = 0
            while (i < bytes.size) {
                bytes[i] = r.toByte()
                bytes[i + 1] = g.toByte()
                bytes[i + 2] = b.toByte()
                bytes[i + 3] = 0xFF.toByte()
                i += 4
            }
        }
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xFF

    @Test
    fun noneEffectReturnsTheInputPixelsUnchanged() {
        val rgba = solid(8, 6, 0x3366CC)
        val out = WallpaperCore.Ffi.render(rgba, 8, 6, WallpaperEffect.NONE, light = true)
        // A copy, not the same array — but identical bytes.
        assertTrue(out.contentEquals(rgba))
    }

    @Test
    fun scanlinesMatchTheRustGainAnchor() {
        // wallpaper.rs:268-273: solid [200, 100, 50], dark mode; row 0 is
        // multiplied by the 0.52 gain, row 1 untouched. This pins the enum
        // lowering through the real host library.
        val out = WallpaperCore.Ffi.render(solid(3, 3, 0xC86432), 3, 3, WallpaperEffect.SCANLINES, light = false)
        assertEquals(104, u8(out[0]))
        assertEquals(52, u8(out[1]))
        assertEquals(26, u8(out[2]))
        assertEquals(255, u8(out[3]))
        assertEquals(200, u8(out[12]))
        assertEquals(100, u8(out[13]))
        assertEquals(50, u8(out[14]))
        assertEquals(255, u8(out[15]))
    }

    @Test
    fun aWhiteWallpaperInDarkModeFadesBelowHalfOpacity() {
        // Dark theme (light text 0xE8E8EA on near-black 0x060606): the
        // artwork's brightest pixels are the threat, so a white image must
        // be faded hard (wallpaper.rs:276-282).
        val opacity = WallpaperCore.Ffi.safeOpacity(
            solid(64, 64, 0xFFFFFF), 64, 64,
            textRgb = 0xE8E8EA, backgroundRgb = 0x060606,
            region = 0.6f, minContrast = 4.5f, maxOpacity = 1f,
        )
        assertTrue("expected opacity < 0.5, got $opacity", opacity < 0.5f)
    }

    @Test
    fun aBlackWallpaperInDarkModeShowsFully() {
        // A black image never threatens light text: full opacity.
        val opacity = WallpaperCore.Ffi.safeOpacity(
            solid(64, 64, 0x000000), 64, 64,
            textRgb = 0xE8E8EA, backgroundRgb = 0x060606,
            region = 0.6f, minContrast = 4.5f, maxOpacity = 1f,
        )
        assertEquals(1f, opacity, 1e-6f)
    }

    @Test
    fun aDarkWallpaperInLightModeIsTheThreat() {
        // Light theme (dark text on 0xF3F3F5): a dark image is what gets
        // faded, and the result is between the two extremes (wallpaper.rs:283-287).
        val opacity = WallpaperCore.Ffi.safeOpacity(
            solid(64, 64, 0x141428), 64, 64,
            textRgb = 0x27272C, backgroundRgb = 0xF3F3F5,
            region = 0.6f, minContrast = 4.5f, maxOpacity = 1f,
        )
        assertTrue("expected 0.2 < opacity < 0.75, got $opacity", opacity in 0.2f..0.75f)
    }

    @Test
    fun shortInputToSafeOpacityReturnsZero() {
        // Degenerate buffers return 0.0 instead of throwing.
        assertEquals(
            0f,
            WallpaperCore.Ffi.safeOpacity(
                ByteArray(3), 8, 8,
                textRgb = 0xE8E8EA, backgroundRgb = 0x060606,
                region = 0.6f, minContrast = 4.5f, maxOpacity = 1f,
            ),
            1e-6f,
        )
    }
}

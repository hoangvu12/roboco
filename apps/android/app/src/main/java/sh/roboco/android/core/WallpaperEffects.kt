package sh.roboco.android.core

import uniffi.roboco_core.WallpaperEffect

/**
 * The wallpaper effect catalogue, verbatim from
 * `ios:Design/Wallpaper.swift:39-70`: display order, lower-case pref keys,
 * settings-row labels and details. The keys are what the `wallpaperEffect`
 * pref stores (iOS stores the same strings through `UserDefaults`).
 *
 * Every `when` is exhaustive over the core enum, so a new `WallpaperEffect`
 * case fails compilation instead of silently falling through.
 */
object WallpaperEffects {

    /** Display order (`Wallpaper.swift:49`). */
    val all: List<WallpaperEffect> = listOf(
        WallpaperEffect.NONE,
        WallpaperEffect.DITHER,
        WallpaperEffect.ASCII,
        WallpaperEffect.HALFTONE,
        WallpaperEffect.SCANLINES,
    )

    /** The stored key for `e` (`Wallpaper.swift:39-47`). */
    fun key(e: WallpaperEffect): String = when (e) {
        WallpaperEffect.NONE -> "none"
        WallpaperEffect.DITHER -> "dither"
        WallpaperEffect.ASCII -> "ascii"
        WallpaperEffect.HALFTONE -> "halftone"
        WallpaperEffect.SCANLINES -> "scanlines"
    }

    /**
     * The effect for a stored key, or `null`. Case-sensitive, like iOS:
     * `"Halftone"` and `"NONE"` are unknown, and a missing value is not a
     * key (readers map `null` to [WallpaperEffect.NONE]).
     */
    fun fromKey(key: String?): WallpaperEffect? = when (key) {
        "none" -> WallpaperEffect.NONE
        "dither" -> WallpaperEffect.DITHER
        "ascii" -> WallpaperEffect.ASCII
        "halftone" -> WallpaperEffect.HALFTONE
        "scanlines" -> WallpaperEffect.SCANLINES
        else -> null
    }

    /** The settings row title (`Wallpaper.swift:51-60`). */
    fun label(e: WallpaperEffect): String = when (e) {
        WallpaperEffect.NONE -> "None"
        WallpaperEffect.DITHER -> "Dither"
        WallpaperEffect.ASCII -> "ASCII"
        WallpaperEffect.HALFTONE -> "Halftone"
        WallpaperEffect.SCANLINES -> "Scanlines"
    }

    /** The settings row subtitle (`Wallpaper.swift:61-70`). */
    fun detail(e: WallpaperEffect): String = when (e) {
        WallpaperEffect.NONE -> "Shows the original artwork."
        WallpaperEffect.DITHER -> "Rebuilds the artwork with a dithered color palette."
        WallpaperEffect.ASCII -> "Recreates the artwork with colored characters."
        WallpaperEffect.HALFTONE -> "Recreates the artwork with colored print dots."
        WallpaperEffect.SCANLINES -> "Adds a pronounced horizontal display-line texture."
    }
}

/** [WallpaperEffects.key] as a property, mirroring `WallpaperEffect.key` on iOS. */
val WallpaperEffect.key: String
    get() = WallpaperEffects.key(this)

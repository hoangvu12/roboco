package sh.roboco.android.shell

import java.io.File

/**
 * Test seam over `android.util.Log.w` — the plain-JVM tests replace it
 * because `android.util.Log` throws "not mocked" there. `android.util.Log`
 * is fully qualified on purpose: this file keeps no `android.*` **import**
 * (the parse path stays plain JVM; only the `Bundle` extension below
 * touches the framework, also fully qualified).
 */
internal var launchOptionsLog: (String) -> Unit = { message ->
    android.util.Log.w("LaunchOptions", message)
}

/**
 * The launch intent extras (android-ui-parity #36) — the Android form of
 * iOS's launch arguments (`SceneDelegate.swift:34-47,64-72`,
 * `AppModel.swift:62-66`, `-measureopen`), read once per `onCreate` from
 * `intent.extras.toExtrasMap()`:
 *
 * - `route` is **always** parsed (iOS's `-route` is ungated — it does
 *   nothing a deep link can't already do) and again in `onNewIntent`. It
 *   is not a field here: [route] turns it into a [DeepLink] for the hub.
 * - `wallpaper` (`path` | `none`), `wallpaper-effect` (a raw key,
 *   validated by ticket 20's store), `harness` (verbatim, never
 *   validated — ticket 92 reads it) and `measureopen` (ticket 75 reads
 *   it) are honoured **only in debuggable builds** and only in
 *   `onCreate`: an exported activity's extras can be sent by any app in
 *   release.
 * - `pair` (android-app#30's flow rig) is a comma-separated list of
 *   pairing URLs the flow tests mint from `android_fixture`'s `/mint`
 *   endpoint. Debug-only like the rest: [MainActivity] hands them to
 *   `RobocoApplication.redeemLaunchPairs` before the first frame is
 *   released, so the shell never shows the pairing root mid-rig.
 *
 * Wrong types (an `Int` where a `String` is expected) read as absent;
 * blank strings read as null.
 */
data class LaunchOptions(
    val wallpaper: String? = null,
    val wallpaperEffect: String? = null,
    val harness: String? = null,
    val measureOpen: Boolean = false,
    val pair: List<String> = emptyList(),
) {

    /**
     * Wallpaper first, effect second (iOS order,
     * `SceneDelegate.swift:34-47`): `"none"` is an exact, case-sensitive
     * match that clears the stored image (any other value is a file path
     * — `"None"` is a path, like iOS); an effect is applied even without
     * a wallpaper, and an unknown key only warns. A failed file import
     * warns from the adapter (`MainActivity`), not here.
     */
    fun applyWallpaper(target: WallpaperTarget) {
        when {
            wallpaper == "none" -> target.remove()
            wallpaper != null -> target.set(File(wallpaper))
        }
        wallpaperEffect?.let { key ->
            if (!target.setEffect(key)) {
                launchOptionsLog("wallpaper-effect: unknown key $key")
            }
        }
    }

    companion object {

        /**
         * Set once per `onCreate` (`MainActivity`), so it survives
         * recreation; after process death it is re-parsed from the
         * restored intent. Read by ticket 92 (`harness`) and ticket 75
         * (`measureOpen`). `@Volatile`: process-wide, written from the
         * activity, read anywhere.
         */
        @Volatile
        var current: LaunchOptions = LaunchOptions()
            internal set

        /**
         * The debug-gated extras. In a non-debuggable build the result is
         * [LaunchOptions] with every field empty whatever the extras say
         * — the release-flag behaviour is exactly this. Debuggable means
         * `ApplicationInfo.FLAG_DEBUGGABLE` (the installed APK's
         * property), not `BuildConfig.DEBUG` (a per-variant compile-time
         * constant).
         */
        fun parse(extras: Map<String, Any?>, debuggable: Boolean): LaunchOptions {
            if (!debuggable) return LaunchOptions()
            fun string(key: String): String? =
                (extras[key] as? String)?.takeIf { it.isNotBlank() }
            val measureOpen = when (val value = extras["measureopen"]) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> false
            }
            return LaunchOptions(
                wallpaper = string("wallpaper"),
                wallpaperEffect = string("wallpaper-effect"),
                harness = string("harness"),
                measureOpen = measureOpen,
                pair = string("pair")
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?: emptyList(),
            )
        }

        /**
         * The `route` extra as a [DeepLink] — always parsed, in both
         * `onCreate` and `onNewIntent`, regardless of the debug gate.
         * Non-string, absent or unknown values read as nothing.
         */
        fun route(extras: Map<String, Any?>): DeepLink? =
            DeepLinks.parseRoute(extras["route"] as? String)
    }
}

/**
 * Seam over ticket 20's `WallpaperStore` so `LaunchOptions.applyWallpaper`
 * call order stays unit-testable. The real adapter (in `MainActivity`)
 * runs the import on the store's own lane and logs a read failure.
 */
interface WallpaperTarget {

    /** `wallpaper none` — clears the stored image (the effect pref survives). */
    fun remove()

    /** Imports the file (name = file name **with** extension); async, fire-and-forget. */
    fun set(path: File)

    /**
     * Sets the effect by key; `false` when the key is not one of the five
     * (the caller then logs the warning). Main-safe.
     */
    fun setEffect(key: String): Boolean
}

/**
 * `Bundle` → plain map. Never throws: a hostile caller's unknown
 * Parcelable reads as no extras (`BadParcelableException` → empty map).
 * Wrong-typed values survive here; `LaunchOptions.parse`'s `as?` casts
 * are what turn them into "absent".
 */
@Suppress("DEPRECATION") // BaseBundle.get(String): the one untyped read the ticket prescribes
fun android.os.Bundle?.toExtrasMap(): Map<String, Any?> {
    if (this == null) return emptyMap()
    return runCatching {
        keySet().associateWith { key -> get(key) }
    }.getOrElse { emptyMap() }
}

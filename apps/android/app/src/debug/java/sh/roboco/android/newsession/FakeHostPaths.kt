package sh.roboco.android.newsession

/**
 * Debug-only Kotlin copy of PR #621's `HostPaths` rules (ticket 110's
 * file table; `pr621:apps/android/app/src/main/java/sh/zeron/android/ui/FolderBrowser.kt:57-130`),
 * used by the design gallery, the previews and the JVM/Robolectric tests
 * — the same rules android-app#09 owns in Rust
 * (`crates/mobile-client/src/host_paths.rs`) and [FfiHostPaths] delegates
 * to, so no JVM code needs the native core. Main code must never
 * reference it (the debug source set is absent from release builds; it is
 * visible to `testDebugUnitTest`, which is why it lives here).
 */
object FakeHostPaths : HostPathOps {
    /** `^[A-Za-z]:([\\/].*)?$` — `C:`, `C:\`, `C:/x` — or a UNC `\\server\share`. */
    private val drive = Regex("^[A-Za-z]:([\\\\/].*)?$")

    fun isWindows(path: String): Boolean = drive.matches(path) || path.startsWith("\\\\")

    private fun isSep(c: Char, windows: Boolean) = c == '/' || (windows && c == '\\')

    /** `C:\` or `C:` (a drive root), `\\server\share`, or `/`. */
    fun isRoot(path: String): Boolean {
        if (isWindows(path)) {
            val t = path.trimEnd('\\', '/')
            if (t.length == 2 && t[1] == ':') return true
            if (path.startsWith("\\\\")) return t.drop(2).count { it == '\\' || it == '/' } <= 1
            return false
        }
        return path.trimEnd('/').isEmpty()
    }

    override fun join(base: String, name: String): String {
        val windows = isWindows(base)
        if (base.isEmpty()) return name
        return if (isSep(base.last(), windows)) base + name else base + (if (windows) '\\' else '/') + name
    }

    /** One level up, or null at a root. `C:\Users` → `C:\`, `/home` → `/`, `\\srv\share\x` → `\\srv\share`. */
    override fun parent(path: String): String? {
        if (path.isEmpty() || isRoot(path)) return null
        val windows = isWindows(path)
        var end = path.length
        while (end > 1 && isSep(path[end - 1], windows)) end--
        val trimmed = path.substring(0, end)
        val cut = trimmed.indexOfLast { isSep(it, windows) }
        if (cut < 0) return null
        val up = trimmed.substring(0, cut)
        return when {
            windows && up.length == 2 && up[1] == ':' -> "$up\\"
            windows && up.isEmpty() -> null
            !windows && up.isEmpty() -> "/"
            else -> up
        }
    }

    /** Last component, or the path itself for a root (`C:\`, `/`). */
    override fun name(path: String): String {
        if (isRoot(path)) return path
        val windows = isWindows(path)
        val t = path.trimEnd { isSep(it, windows) }
        return t.substring(t.indexOfLast { isSep(it, windows) } + 1)
    }
}

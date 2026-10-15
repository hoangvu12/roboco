package sh.roboco.android.session

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.roboco.android.shell.LaunchOptions

/**
 * The open-latency probe (android-ui-parity #75) — the port of
 * `applyFrame`'s probe block (`ios:Session/SessionViewController.swift:454-469`):
 * the first applied frame with rows emits one line
 * `OPEN {"openMs":…,"rows":…,"layoutPassMs":…}` to logcat (tag
 * [TAG]), and, with the debug launch extra `measureopen`, writes the same
 * JSON to `open.json` in the external files dir, atomically, off main.
 * Pull path: `adb pull /sdcard/Android/data/sh.roboco.android/files/open.json`.
 *
 * This object keeps no `android.util.Log` **import** on the pure path: the
 * default [log] lambda refers to it fully qualified, and the plain-JVM
 * tests swap [log] out (the stub `android.util.Log` throws "not mocked"
 * outside Robolectric) — the same seam as `LaunchOptions.launchOptionsLog`.
 */
object OpenProbe {

    /** The logcat tag (a fixed string; not a translation). */
    const val TAG = "RobocoOpen"

    /** `Log.INFO`, spelled locally so the pure path stays import-free. */
    internal const val INFO = 4

    /** `Log.WARN`, spelled locally so the pure path stays import-free. */
    internal const val WARN = 5

    /**
     * The JSON iOS writes (`String(format:)` with `%.1f` / `%d` / `%.2f`).
     * Always `Locale.ROOT`: a `vi`/`de` default locale would otherwise
     * write `412,3`.
     */
    fun json(openMs: Double, rows: Long, buildMicros: Long): String =
        String.format(
            Locale.ROOT,
            "{\"openMs\":%.1f,\"rows\":%d,\"layoutPassMs\":%.2f}",
            openMs,
            rows,
            buildMicros / 1000.0,
        )

    /**
     * Emits the probe: the logcat line always, `open.json` when
     * [writeFile] (the launch extra, debuggable builds only — ticket 36
     * gates the parsing). Main thread; the file write itself hops to
     * `Dispatchers.IO` on a **process-lifetime** scope (a fresh
     * `SupervisorJob` each call, never cancelled), so a quick pop of the
     * screen cannot cancel a write in flight.
     */
    fun emit(context: Context, json: String, writeFile: Boolean = LaunchOptions.current.measureOpen) {
        log(INFO, "OPEN $json")
        if (!writeFile) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // getExternalFilesDir creates directories: disk I/O, never main.
            writeAtomically(app.getExternalFilesDir(null), json)
        }
    }

    /**
     * Writes [json] to `open.json` in [dir] atomically: write
     * `open.json.tmp`, then rename; if the rename fails (a stale target),
     * delete the target and rename again. iOS's `try?`: a `null` dir or an
     * `IOException`/`SecurityException` logs one warning and returns
     * `false`, nothing else. Any thread; overwrites on each open.
     */
    internal fun writeAtomically(dir: File?, json: String): Boolean {
        if (dir == null) {
            log(WARN, "open.json: no external files dir")
            return false
        }
        return try {
            val tmp = File(dir, "open.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            val target = File(dir, "open.json")
            if (tmp.renameTo(target)) {
                true
            } else {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (error: IOException) {
            log(WARN, "open.json: ${error.message}")
            false
        } catch (error: SecurityException) {
            log(WARN, "open.json: ${error.message}")
            false
        }
    }

    /**
     * The log seam: `android.util.Log.i/w` in production, a capturing
     * lambda in the plain-JVM tests. Levels are [INFO]/[WARN].
     */
    internal var log: (level: Int, msg: String) -> Unit = { level, msg ->
        if (level == INFO) {
            android.util.Log.i(TAG, msg)
        } else {
            android.util.Log.w(TAG, msg)
        }
    }
}

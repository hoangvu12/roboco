package roboco.gradle

/**
 * Whether a Gradle invocation needs the host core (ci-speed#07): the
 * `host/` library that JVM (Robolectric) tests load through JNA, and
 * nothing else loads. An invocation that only packages or installs APKs,
 * or runs instrumented tests on a device, skips it; the xtask then reads
 * the Kotlin bindings out of the first requested ABI's library instead
 * (`--skip-host`).
 *
 * Conservative: the host core is skipped only when every requested task is
 * a known APK/device task. No tasks (an IDE sync's defaults), an
 * abbreviation, `test`, `check`, `build`, a task option such as `--tests`,
 * or anything else unrecognised keeps it.
 */
object HostCore {

    fun needed(taskNames: List<String>): Boolean =
        taskNames.isEmpty() ||
            taskNames.any { !apkOrDeviceTask(it.substringAfterLast(':').lowercase()) }

    private fun apkOrDeviceTask(name: String): Boolean = when {
        // assembleDebugUnitTest and friends belong to the JVM tests.
        name.endsWith("unittest") -> false
        name == "clean" -> true
        name.startsWith("assemble") || name.startsWith("bundle") -> true
        name.startsWith("install") || name.startsWith("uninstall") -> true
        // connectedDebugAndroidTest, connectedCheck: on the device.
        name.startsWith("connected") -> true
        else -> false
    }
}

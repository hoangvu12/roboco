package sh.roboco.android.pairing

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The redeem label this phone shows the engine (`CoreConfig.deviceName`,
 * the port of iOS's `UIDevice.current.name`): the user's own device name
 * if set, else the model, else `"Android"` — never blank.
 *
 * Read once per process at startup; existing pairings keep the label the
 * engine stored at redeem time. Label *sanitising* (control characters,
 * the 256-byte cap) is the Rust core's job — Kotlin only picks the name.
 */
object DeviceLabel {

    fun read(context: Context): String {
        val deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return deviceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Android"
    }
}

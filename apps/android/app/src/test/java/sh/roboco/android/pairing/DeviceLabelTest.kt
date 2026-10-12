package sh.roboco.android.pairing

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * The redeem label chain (ticket 71): the user's own device name, else the
 * model, else "Android" — never blank, never a crash when the platform
 * restricts `Settings.Global.DEVICE_NAME`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceLabelTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theDeviceNameWinsAfterTrimming() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "  Vu's Pixel ")

        assertEquals("Vu's Pixel", DeviceLabel.read(context))
    }

    @Test
    fun aBlankDeviceNameFallsBackToTheModel() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "   ")
        ShadowBuild.setModel("Pixel 9")

        assertEquals("Pixel 9", DeviceLabel.read(context))
    }

    @Test
    fun aMissingDeviceNameFallsBackToTheModel() {
        ShadowBuild.setModel("Pixel 9")

        assertEquals("Pixel 9", DeviceLabel.read(context))
    }

    @Test
    fun aBlankModelFallsBackToAndroid() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVICE_NAME, "")
        ShadowBuild.setModel("   ")

        assertEquals("Android", DeviceLabel.read(context))
    }
}

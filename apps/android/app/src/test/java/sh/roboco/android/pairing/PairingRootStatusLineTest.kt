package sh.roboco.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.roboco.android.design.Palette

/**
 * The pairing root's status → copy/colour mapping (android-ui-parity #34),
 * plain JVM (the PaletteValuesTest convention — [Palette] and [Color] are
 * pure Kotlin): `None` maps to no line, `Redeeming` to the redeeming copy
 * in `secondary`, `Error(message)` to the message verbatim in `danger`, in
 * both appearances. The drawn pixels are the Roborazzi goldens' job.
 */
class PairingRootStatusLineTest {

    private val redeeming = "Redeeming the pairing link…"

    @Test
    fun noneMapsToNoLineInBothAppearances() {
        assertNull(pairingRootStatusLine(PairingRootStatus.None, redeeming, Palette.of(false)))
        assertNull(pairingRootStatusLine(PairingRootStatus.None, redeeming, Palette.of(true)))
    }

    @Test
    fun redeemingMapsToTheCopyInSecondary() {
        for (dark in listOf(false, true)) {
            val palette = Palette.of(dark)
            assertEquals(
                redeeming to palette.secondary,
                pairingRootStatusLine(PairingRootStatus.Redeeming, redeeming, palette),
            )
        }
    }

    @Test
    fun errorMapsToItsMessageVerbatimInDanger() {
        val message = "That pairing link did not work — it may have expired or already have been used."
        for (dark in listOf(false, true)) {
            val palette = Palette.of(dark)
            assertEquals(
                message to palette.danger,
                pairingRootStatusLine(PairingRootStatus.Error(message), redeeming, palette),
            )
        }
    }
}

package sh.roboco.android.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The root choice (android-ui-parity #35, plain JVM for the merge check):
 * `!hasEngines` wins over any width, the 744 dp breakpoint is inclusive,
 * and the fade groups are Pairing alone vs Phone + Split together.
 */
class RootKindTest {

    @Test
    fun noEnginesPairsWhateverTheWidth() {
        assertEquals(RootKind.Pairing, chooseRoot(hasEngines = false, width = 1200.dp))
        assertEquals(RootKind.Pairing, chooseRoot(hasEngines = false, width = 320.dp))
    }

    @Test
    fun belowTheBreakpointIsThePhoneShell() {
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 743.dp))
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 393.dp))
    }

    @Test
    fun atAndAboveTheBreakpointIsTheSplit() {
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 744.dp))
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 1280.dp))
    }

    @Test
    fun theBreakpointIsTheIpadMinisPortraitWidth() {
        assertEquals(744.dp, SplitBreakpoint)
        assertEquals(480.dp, CompactHeight)
    }

    /** Wave-10 42: I1 and the user's portrait-tablet decision. */
    @Test
    fun aLandscapePhoneStaysPhoneAndAnUprightTabletSplits() {
        // Pixel 7 sideways: wide, but compact height.
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 915.dp, height = 400.dp))
        // Pixel Tablet upright (800 × 1280) and sideways.
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 800.dp, height = 1280.dp))
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 1280.dp, height = 800.dp))
        // A small 600 dp tablet upright is still the phone layout.
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 600.dp, height = 960.dp))
        // Exactly the compact height is regular.
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 900.dp, height = 480.dp))
        assertEquals(RootKind.Pairing, chooseRoot(hasEngines = false, width = 915.dp, height = 400.dp))
    }

    @Test
    fun onlyPairingFadesPhoneAndSplitShareAGroup() {
        assertEquals(RootGroup.Pairing, RootKind.Pairing.group)
        assertEquals(RootGroup.Shell, RootKind.Phone.group)
        assertEquals(RootGroup.Shell, RootKind.Split.group)
    }
}

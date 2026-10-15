package sh.roboco.android.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The root choice (android-ui-parity #35, plain JVM for the merge check):
 * `!hasEngines` wins over any width, the 840 dp breakpoint is inclusive,
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
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 839.dp))
        assertEquals(RootKind.Phone, chooseRoot(hasEngines = true, width = 393.dp))
    }

    @Test
    fun atAndAboveTheBreakpointIsTheSplit() {
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 840.dp))
        assertEquals(RootKind.Split, chooseRoot(hasEngines = true, width = 1280.dp))
    }

    @Test
    fun theBreakpointConstantIs840Dp() {
        assertEquals(840.dp, SplitBreakpoint)
    }

    @Test
    fun onlyPairingFadesPhoneAndSplitShareAGroup() {
        assertEquals(RootGroup.Pairing, RootKind.Pairing.group)
        assertEquals(RootGroup.Shell, RootKind.Phone.group)
        assertEquals(RootGroup.Shell, RootKind.Split.group)
    }
}

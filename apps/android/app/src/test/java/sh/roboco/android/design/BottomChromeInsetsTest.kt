package sh.roboco.android.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.roboco.android.shell.bottomChromeContentPadding
import sh.roboco.android.shell.tabBarBottomGap

/**
 * Ticket 30's bottom insets math, plain JUnit:
 * [bottomChromeContentPadding] = `barBottom + 62 + 8 + 48 + 8` over 26's
 * [tabBarBottomGap], with the worked table —
 *
 * | nav bottom | tappable | barBottom | contentPadding |
 * |---|---|---|---|
 * | 24 dp (gesture) | 0 | 24 | 150 |
 * | 0 / 21 dp | 0 | 21 | 147 |
 * | 48 dp (3-button, 48 dp tall) | 48 | 60 | 186 |
 */
class BottomChromeInsetsTest {

    @Test
    fun contentPaddingIs150OnGestureNav() {
        assertEquals(150.dp, bottomChromeContentPadding(tabBarBottomGap(24.dp, 0.dp)))
    }

    @Test
    fun contentPaddingIs147WithNoInsetsOrAtTheMinimumGap() {
        assertEquals(147.dp, bottomChromeContentPadding(tabBarBottomGap(0.dp, 0.dp)))
        assertEquals(147.dp, bottomChromeContentPadding(tabBarBottomGap(21.dp, 0.dp)))
    }

    @Test
    fun contentPaddingIs186OverThreeButtonNav() {
        assertEquals(186.dp, bottomChromeContentPadding(tabBarBottomGap(48.dp, 48.dp)))
    }

    @Test
    fun barBottomAlwaysClearsATappableBarByAtLeast12() {
        for (tappable in listOf(24.dp, 36.dp, 48.dp)) {
            val bar = tabBarBottomGap(24.dp, tappable)
            assertTrue("barBottom $bar must sit ≥ 12 above a $tappable bar", bar >= tappable + 12.dp)
        }
    }

    @Test
    fun gestureNavBarBottomIsMax21NavBottom() {
        assertEquals(21.dp, tabBarBottomGap(0.dp, 0.dp))
        assertEquals(21.dp, tabBarBottomGap(21.dp, 0.dp))
        assertEquals(24.dp, tabBarBottomGap(24.dp, 0.dp))
    }
}

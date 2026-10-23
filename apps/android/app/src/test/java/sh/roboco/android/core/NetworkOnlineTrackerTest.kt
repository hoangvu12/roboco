package sh.roboco.android.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default-network dedupe logic (android-ui-parity #35, plain JVM for
 * the merge check): calls are deduplicated on
 * `(defaultNetworkHandle, online)` — the ticket's exact case list, the
 * iOS `NWPathMonitor` behaviour (an interface change re-sends `true`, a
 * signal-strength-only change sends nothing).
 */
class NetworkOnlineTrackerTest {

    @Test
    fun initialOfflineThenAvailableSendsTrue() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertEquals(true, tracker.available(1))
    }

    @Test
    fun theSameNetworkAgainSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(1)
        assertNull(tracker.available(1))
    }

    @Test
    fun anInterfaceHandoverSendsTrueAgain() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(1)
        assertEquals(true, tracker.available(2))
    }

    @Test
    fun anUnchangedCapabilitySendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertNull(tracker.capabilities(2, hasInternet = true))
    }

    @Test
    fun anInternetCapabilityFlipSendsFalse() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertEquals(false, tracker.capabilities(2, hasInternet = false))
    }

    @Test
    fun losingANonCurrentNetworkSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        assertNull(tracker.lost(1))
    }

    @Test
    fun losingTheCurrentNetworkWhileAlreadyOfflineSendsNothing() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        tracker.available(2)
        tracker.capabilities(2, hasInternet = false)
        assertNull(tracker.lost(2))
    }

    @Test
    fun losingTheCurrentNetworkWhileOnlineSendsFalse() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertEquals(false, tracker.lost(1))
    }

    @Test
    fun aSignalStrengthOnlyChangeSendsNothing() {
        // Bandwidth/signal changes arrive as onCapabilitiesChanged with
        // the same INTERNET state: no call, as on iOS.
        val tracker = NetworkOnlineTracker(initialNetwork = 2, initialOnline = true)
        assertNull(tracker.capabilities(2, hasInternet = true))
        assertNull(tracker.available(2))
    }

    @Test
    fun theOnlineFlagTracksTheLastState() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertEquals(false, tracker.online)
        tracker.available(1)
        assertEquals(true, tracker.online)
        tracker.capabilities(1, hasInternet = false)
        assertEquals(false, tracker.online)
    }

    // android-next#02: the Tailcat rebuild signal.

    @Test
    fun aHandoverToAnotherNetworkIsASwitch() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertFalse(tracker.switched(1))
        assertTrue(tracker.switched(2))
    }

    @Test
    fun theFirstNetworkAfterNoneIsNotASwitch() {
        val tracker = NetworkOnlineTracker(initialNetwork = null, initialOnline = false)
        assertFalse(tracker.switched(1))
        tracker.available(1)
        tracker.lost(1)
        assertFalse(tracker.switched(2))
    }

    @Test
    fun aLostAddressOnTheCurrentNetworkIsAChangeAfterTheFirstReading() {
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        assertFalse(tracker.linkChanged(1, setOf("192.168.1.5")))
        assertFalse(tracker.linkChanged(1, setOf("192.168.1.5")))
        // The old IP went away (DHCP renumbered): rebuild.
        assertTrue(tracker.linkChanged(1, setOf("192.168.1.9")))
        // Another network's link properties are ignored.
        assertFalse(tracker.linkChanged(2, setOf("10.0.0.2")))
        // A handover resets the reading: the new network's first one is no change.
        tracker.available(2)
        assertFalse(tracker.linkChanged(2, setOf("10.0.0.2")))
    }

    @Test
    fun addedAddressesAloneAreNotAChange() {
        // A new IPv6 privacy address or a second prefix on the same
        // network: the existing sockets still work, no rebuild.
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        tracker.linkChanged(1, setOf("192.168.1.5"))
        assertFalse(tracker.linkChanged(1, setOf("192.168.1.5", "2001:db8::a")))
        assertFalse(tracker.linkChanged(1, setOf("192.168.1.5", "2001:db8::a", "2001:db8::b")))
        // The privacy address rotates out: one went away, rebuild.
        assertTrue(tracker.linkChanged(1, setOf("192.168.1.5", "2001:db8::b")))
    }

    @Test
    fun onlyRoutableAddressesCount() {
        fun routable(literal: String) = InetAddress.getByName(literal).isRoutableAddress()
        assertTrue(routable("192.168.1.5"))
        assertTrue(routable("10.0.0.2"))
        assertTrue(routable("100.64.1.2"))
        assertTrue(routable("2001:db8::1"))
        assertTrue("IPv6 ULAs are LAN-routable", routable("fd12:3456::1"))
        assertFalse(routable("fe80::1"))
        assertFalse(routable("169.254.10.1"))
        assertFalse(routable("127.0.0.1"))
        assertFalse(routable("::1"))
        assertFalse(routable("0.0.0.0"))
        assertFalse(routable("ff02::1"))
        // So a link-local-only churn never reaches the tracker as a change.
        val tracker = NetworkOnlineTracker(initialNetwork = 1, initialOnline = true)
        val before = listOf("192.168.1.5", "fe80::1").filter(::routable).toSet()
        val after = listOf("192.168.1.5", "fe80::2").filter(::routable).toSet()
        tracker.linkChanged(1, before)
        assertFalse(tracker.linkChanged(1, after))
    }
}

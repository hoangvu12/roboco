package sh.roboco.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

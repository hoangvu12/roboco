package sh.roboco.android.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import uniffi.roboco_core.CoreClient

/**
 * The three OS lifecycle calls the core needs (android-ui-parity #35;
 * upstream `mob:client_ffi/mod.rs:488-503`), behind a seam so tests can
 * drive them with a fake. The real sink holds the live [CoreClient].
 *
 * - [onForeground] — app returned to the foreground: resume, redial/probe;
 * - [onBackground] — app backgrounding: persist now (flushes pending cache
 *   writes synchronously, bounded at 2 s — **never on the main thread**);
 * - [setNetworkOnline] — the OS network path; `true` resets backoff and
 *   redials (android-app#06), and re-sends are deduplicated by
 *   [NetworkOnlineTracker].
 */
interface LifecycleSink {
    fun onForeground()

    fun onBackground()

    fun setNetworkOnline(online: Boolean)

    /**
     * The default network became a different `Network`, or its addresses
     * changed (android-next#02): Tailcat engines rebuild their tunnels on
     * the new interface. Tailscale's netmon only polls every 10 minutes
     * on Android, so it would not notice in time by itself.
     */
    fun setNetworkChanged() {}
}

/** The production sink: the live [CoreClient]. Logs each call (`adb logcat -s Roboco`). */
class CoreLifecycleSink(private val client: CoreClient) : LifecycleSink {
    override fun onForeground() {
        Log.d(TAG, "onForeground")
        client.onForeground()
    }

    override fun onBackground() {
        Log.d(TAG, "onBackground")
        client.onBackground()
    }

    override fun setNetworkOnline(online: Boolean) {
        Log.d(TAG, "setNetworkOnline($online)")
        client.setNetworkOnline(online)
    }

    override fun setNetworkChanged() {
        Log.d(TAG, "setNetworkChanged")
        client.setNetworkChanged()
    }

    private companion object {
        const val TAG = "Roboco"
    }
}

/**
 * Pure dedupe logic for the default-network callback
 * (android-ui-parity #35, `NWPathMonitor`'s Kotlin stand-in). Calls are
 * deduplicated on the pair `(defaultNetworkHandle, online)`: a
 * capability callback that changes only bandwidth or signal strength
 * produces no call, and an interface change (Wi-Fi → cellular) sends
 * `true` again, as on iOS — it resets the redial backoff.
 *
 * Each method returns the value to send, or `null` when nothing changed.
 * Touched from the `ConnectivityManager` callback thread (after install)
 * and read by [AppLifecycleHooks.clientReady] on another thread.
 */
class NetworkOnlineTracker(initialNetwork: Long?, initialOnline: Boolean) {

    /** The last online value; volatile for the cross-thread `clientReady()` read. */
    @Volatile
    var online: Boolean = initialOnline
        private set

    private var current: Long? = initialNetwork

    /** The current default network's addresses, once seen (link changes). */
    private var addresses: Set<String>? = null

    /**
     * `onAvailable`, asked BEFORE [available]: true when the default
     * moved from one network to another (Wi-Fi → cellular). The first
     * network after none is not a switch.
     */
    fun switched(net: Long): Boolean {
        val previous = current ?: return false
        return previous != net
    }

    /**
     * `onLinkPropertiesChanged`, fed only the **routable** addresses
     * ([isRoutableAddress]): true when an address the current network had
     * went away (the old IP on the same Wi-Fi is gone, so sockets bound to
     * it are dead). Additions alone (a new IPv6 privacy address, a second
     * prefix) and link-local churn are not a change: the existing sockets
     * still work, and `setNetworkChanged` would tear down healthy links.
     * The first reading only records them; a default-network switch is
     * reported by [switched], not here.
     */
    fun linkChanged(net: Long, linkAddresses: Set<String>): Boolean {
        if (net != current) return false
        val previous = addresses
        addresses = linkAddresses
        return previous != null && !linkAddresses.containsAll(previous)
    }

    /** `onAvailable`: a new (or handover) default network. */
    fun available(net: Long): Boolean? {
        if (net != current) addresses = null
        val changed = net != current || !online
        current = net
        online = true
        return if (changed) true else null
    }

    /** `onCapabilitiesChanged`: only an INTERNET capability flip matters. */
    fun capabilities(net: Long, hasInternet: Boolean): Boolean? {
        if (net != current || hasInternet == online) return null
        online = hasInternet
        return hasInternet
    }

    /** `onLost`: only the current default going away with no replacement. */
    fun lost(net: Long): Boolean? {
        if (net != current) return null
        current = null
        if (!online) return null
        online = false
        return false
    }
}

/**
 * An address a socket to an engine can be bound to: not link-local
 * (fe80::/10, 169.254/16), loopback, wildcard or multicast. Private LAN
 * ranges and IPv6 ULAs count — LAN engines are reached over them. The
 * filter [NetworkOnlineTracker.linkChanged] is fed through.
 */
fun java.net.InetAddress.isRoutableAddress(): Boolean =
    !isLinkLocalAddress && !isLoopbackAddress && !isAnyLocalAddress && !isMulticastAddress

/**
 * The process lifecycle hooks (android-ui-parity #35), registered **once
 * in `RobocoApplication.onCreate`** — never per `onCreate` of an activity
 * (#609's observer leak) and never from an activity's `onStart`/`onStop`
 * (#621's redial per rotation):
 *
 * - `ProcessLifecycleOwner` `ON_START` → [onStart]: `onForeground()` then
 *   [afterForeground][] (iOS `refreshWorkspace()`, `AppModel.swift:289`;
 *   Roboco's workspace feed re-pulls through `ClientEvent`, so the app
 *   passes `{}`);
 * - `ProcessLifecycleOwner` `ON_STOP` → [onStop]: `onBackground()` (the
 *   androidx owner dispatches it ~700 ms after the last activity stops,
 *   so a rotation never triggers it);
 * - one `registerDefaultNetworkCallback`, never unregistered (iOS's
 *   `NWPathMonitor` also lives as long as the app model), feeding
 *   [NetworkOnlineTracker].
 *
 * Every core call goes through the **single-thread** executor, so the
 * order is preserved (background then foreground can never be reordered)
 * and the up-to-2 s `onBackground` flush never runs on the main thread.
 * With no client yet (startup loading, core failed) calls are dropped,
 * like iOS's `client?`; the network state is still tracked, and
 * [clientReady] re-sends it once when 71's startup creates the client.
 */
class AppLifecycleHooks internal constructor(
    private val sink: () -> LifecycleSink?,
    private val executor: Executor,
    private val afterForeground: () -> Unit,
) : DefaultLifecycleObserver {

    /**
     * The network state; seeded by [install] **before** the callback is
     * registered, then touched only from the `ConnectivityManager`
     * callback thread. `@Volatile` for the cross-thread [clientReady].
     */
    @Volatile
    private var tracker: NetworkOnlineTracker? = null

    override fun onStart(owner: LifecycleOwner) {
        executor.execute {
            sink()?.onForeground()
            afterForeground()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        executor.execute { sink()?.onBackground() }
    }

    /** Forwards a network change (already deduplicated; `null` = silent). */
    internal fun network(send: Boolean?) {
        if (send != null) executor.execute { sink()?.setNetworkOnline(send) }
    }

    /** Forwards a switch to another network or new addresses (Tailcat rebuilds). */
    internal fun networkChanged() {
        executor.execute { sink()?.setNetworkChanged() }
    }

    /**
     * Called by the startup code (71) right after `CoreClient.new`
     * succeeds: re-sends the current online value once, so the fresh
     * client starts with the real network state even if it was captured
     * before the client existed.
     */
    fun clientReady() {
        val online = tracker?.online ?: return
        executor.execute { sink()?.setNetworkOnline(online) }
    }

    companion object {
        private const val TAG = "Roboco"
        private const val THREAD_NAME = "roboco-lifecycle"

        /** Process-wide: exactly one install per process (the #609 leak guard). */
        private val installed = AtomicBoolean(false)

        @Volatile
        private var instance: AppLifecycleHooks? = null

        /**
         * Call exactly once, from `RobocoApplication.onCreate` (main
         * thread). A second call logs a warning and returns the first
         * instance, adding no second observer.
         */
        @Synchronized
        fun install(
            app: Application,
            lifecycle: Lifecycle,
            sink: () -> LifecycleSink?,
            afterForeground: () -> Unit = {},
        ): AppLifecycleHooks {
            if (!installed.compareAndSet(false, true)) {
                Log.w(TAG, "AppLifecycleHooks already installed")
                return checkNotNull(instance) { "AppLifecycleHooks install raced" }
            }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
            val hooks = AppLifecycleHooks(sink, executor, afterForeground)
            instance = hooks
            lifecycle.addObserver(hooks)
            seedNetwork(app, hooks)
            return hooks
        }

        /**
         * Robolectric reuses one JVM across test classes: forget the
         * installed instance so each test installs its own.
         */
        @VisibleForTesting
        @Synchronized
        internal fun resetForTest() {
            installed.set(false)
            instance = null
        }

        /**
         * The initial reading (the iOS monitor's first callback) plus the
         * one callback for the life of the process. Registration is
         * wrapped in `runCatching` (`pr621:NetworkWatcher.kt:49`) — a
         * device without a `ConnectivityManager` service still gets the
         * lifecycle hooks.
         */
        private fun seedNetwork(app: Application, hooks: AppLifecycleHooks) {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val active = cm.activeNetwork
            val online = active?.let { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } == true
            val tracker = NetworkOnlineTracker(active?.networkHandle, online)
            hooks.tracker = tracker
            // Dropped when there is no client yet; clientReady() re-sends.
            hooks.network(online)
            runCatching {
                cm.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            val switched = tracker.switched(network.networkHandle)
                            hooks.network(tracker.available(network.networkHandle))
                            if (switched) hooks.networkChanged()
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            // Only routable addresses count: link-local
                            // (fe80::/10, 169.254/16) churn never warrants
                            // a rebuild (see NetworkOnlineTracker.linkChanged).
                            val addresses = linkProperties.linkAddresses
                                .filter { it.address.isRoutableAddress() }
                                .map { it.address.hostAddress.orEmpty() }
                                .toSet()
                            if (tracker.linkChanged(network.networkHandle, addresses)) {
                                hooks.networkChanged()
                            }
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            hooks.network(
                                tracker.capabilities(
                                    network.networkHandle,
                                    networkCapabilities.hasCapability(
                                        NetworkCapabilities.NET_CAPABILITY_INTERNET,
                                    ),
                                ),
                            )
                        }

                        override fun onLost(network: Network) {
                            hooks.network(tracker.lost(network.networkHandle))
                        }
                    },
                )
            }
        }
    }
}

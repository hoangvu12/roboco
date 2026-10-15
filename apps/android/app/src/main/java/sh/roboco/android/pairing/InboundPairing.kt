package sh.roboco.android.pairing

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.RobocoApplication
import sh.roboco.android.shell.DeepLinkHub
import sh.roboco.android.shell.LocalRootPresentations

/**
 * What [InboundPairing] needs from ticket 72's [PairPageLauncher] and
 * ticket 35's [RootPresentations] (a narrow, fakeable seam: the
 * production implementation lives in [InboundPairingLinks], the tests
 * record the calls).
 */
interface PairPageControl {

    /** Whether the Pair page's sheet is currently presented. */
    val isShowing: Boolean

    /** Whether a redeem is running — page showing or not. */
    val isRedeeming: Boolean

    /** The showing page's `replaceEngineId`, or null (kept on refill). */
    val showingReplaceEngineId: String?

    /** 35's `dismissAllInstantly()` — every root presentation, same frame. */
    fun dismissOtherPresentations()

    /** One frame — the hosts recompose before the page is presented. */
    suspend fun awaitFrame()

    /** 72's `openPairPage(prefill, replaceEngineId)`. */
    fun open(prefill: String, replaceEngineId: String?)
}

/**
 * The hub's pairing handler (android-ui-parity #139): a
 * `roboco://pair…` link (tapped, or re-wrapped from a share) decodes,
 * is validated by the core's `parse_pairing_url` **off the main
 * thread**, and then only **pre-fills** ticket 72's Pair page — never
 * auto-redeems (android-app#18: an installed app receives links from
 * any app, and pairing with a hostile engine would route the user's
 * prompts to it; QR scans still auto-submit, 138).
 *
 * The handler runs on the main thread (the hub's contract) in the
 * composition's [scope] ([rememberCoroutineScope] is fine: only the
 * parse and the open run here — the redeem itself runs in 72's
 * launcher scope, so a root swap mid-redeem never cancels it).
 *
 * Fixed-string logs only — never the link, URL, shared text, a
 * candidate or a `PairingTarget` (they all carry the token).
 */
class InboundPairing(
    private val control: PairPageControl,
    private val scope: CoroutineScope,
    private val accepts: (String) -> Boolean = ::ffiAcceptsPairingUrl,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = { Log.i("PairingLinks", it) },
) {

    /**
     * The [DeepLinkHub] pairing handler. Main thread. `url=` absent →
     * fixed-string log, nothing visible; accepted → [open]; rejected by
     * the core (including Tailcat invites) → fixed-string log.
     */
    fun onPairLink(link: String) {
        val url = PairingLinks.urlFromDeepLink(link)
        if (url == null) {
            log("pair link ignored: no url")
            return
        }
        scope.launch {
            if (withContext(worker) { accepts(url) }) {
                open(url)
            } else {
                log("pair link ignored: not a pairing URL")
            }
        }
    }

    /**
     * Behaviour D. A redeem in flight wins over any link (a tapped link
     * must never replace a URL being redeemed). The page showing →
     * refill in place (72: text replaced, phase reset, re-pair mode
     * kept). Otherwise dismiss every root presentation instantly (31:
     * its `present` refuses while anything is up, and a link the user
     * explicitly tapped must not vanish — this also closes the tablet
     * Settings card, 104), wait one frame for the hosts to recompose,
     * then present the page over the pairing root or the current shell
     * screen.
     */
    internal suspend fun open(url: String) {
        if (control.isRedeeming) {
            log("pair link ignored: redeem in progress")
            return
        }
        if (control.isShowing) {
            control.open(url, control.showingReplaceEngineId)
            return
        }
        control.dismissOtherPresentations()
        control.awaitFrame()
        // The user may have started a redeem while the hosts recomposed.
        if (control.isRedeeming) {
            log("pair link ignored: redeem in progress")
            return
        }
        control.open(url, null)
    }
}

/**
 * `parse_pairing_url` accepts [url] — the gate both the deep link and
 * the share path go through before any page opens, so the page always
 * starts with a parseable link and its "Engine at …" line. Never
 * throws (`runCatching` also catches an `UnsatisfiedLinkError` from a
 * missing core). **Never call on the main thread.**
 */
fun ffiAcceptsPairingUrl(url: String): Boolean =
    runCatching { uniffi.roboco_core.parsePairingUrl(url) }.isSuccess

/**
 * Composed once at the app root, inside 72's `ProvidePairPage`:
 * registers [InboundPairing.onPairLink] as the hub's pairing handler.
 *
 * Only while [ready] is true (`rootInput is RootInput.Ready` — the
 * pairing root or a shell is on screen); one frame after the root is
 * drawn (36's cold-start timing — never during composition). While
 * `Loading`/`CoreFailed` the hub holds the link; on `ready` turning
 * false or the composition leaving (activity destroyed/recreated) the
 * handler unregisters and the hub holds new links until the next
 * registration.
 */
@Composable
fun InboundPairingLinks(ready: Boolean) {
    val launcher = LocalPairPage.current ?: return
    val presentations = LocalRootPresentations.current
    val hub = (LocalContext.current.applicationContext as RobocoApplication).deepLinks
    val scope = rememberCoroutineScope()
    val inbound = remember(launcher, presentations, scope) {
        InboundPairing(
            control = object : PairPageControl {
                override val isShowing: Boolean get() = launcher.isShowing
                override val isRedeeming: Boolean get() = launcher.isRedeeming
                override val showingReplaceEngineId: String?
                    get() = if (launcher.isShowing) launcher.current?.replaceEngineId else null

                override fun dismissOtherPresentations() {
                    presentations.dismissAllInstantly()
                }

                override suspend fun awaitFrame() {
                    withFrameNanos { }
                }

                override fun open(prefill: String, replaceEngineId: String?) {
                    launcher.openPairPage(prefill = prefill, replaceEngineId = replaceEngineId)
                }
            },
            scope = scope,
        )
    }
    LaunchedEffect(hub, inbound, ready) {
        if (!ready) return@LaunchedEffect
        withFrameNanos { } // the root's first frame is out
        val unregister = hub.registerPairingHandler(inbound::onPairLink)
        try {
            awaitCancellation()
        } finally {
            unregister()
        }
    }
}

/**
 * The pairing root's status line (table E) from the page's state:
 * `Redeeming` whether the page shows or not (a redeem that outlived its
 * sheet — busy capsule, "Redeeming the pairing link…"), an error only
 * once the page is gone (the sheet renders its own error line), and
 * nothing while idle or with no model. [resolve] turns 72's messages
 * into user-facing copy; the line then sticks until the next status,
 * exactly like the iOS SignIn label (`SignInViewController.swift:66-110`).
 */
fun pairingRootStatusOf(
    phase: PairPhase?,
    pageShowing: Boolean,
    resolve: (PairMessage) -> String,
): PairingRootStatus = when (phase) {
    null, PairPhase.Idle -> PairingRootStatus.None
    PairPhase.Redeeming -> PairingRootStatus.Redeeming
    is PairPhase.Error -> if (pageShowing) {
        PairingRootStatus.None
    } else {
        PairingRootStatus.Error(resolve(phase.message))
    }
}

/**
 * [pairingRootStatusOf] over the live launcher: reads its (now
 * snapshot-backed) `current`/`isShowing`, so the root recomposes when
 * the phase changes — the follow-up 72's Comments left open for a
 * redeem that outlived its page.
 */
@Composable
fun pairingRootStatus(launcher: PairPageLauncher?): PairingRootStatus {
    if (launcher == null) return PairingRootStatus.None
    val context = LocalContext.current
    return pairingRootStatusOf(
        phase = launcher.current?.phase,
        pageShowing = launcher.isShowing,
    ) { message ->
        when (message) {
            is PairMessage.Res -> context.getString(message.id)
            is PairMessage.Verbatim -> message.text
        }
    }
}

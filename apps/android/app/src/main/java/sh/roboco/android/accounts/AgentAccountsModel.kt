package sh.roboco.android.accounts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.design.ActionSheetAction
import sh.roboco.android.design.ActionSheetModel
import sh.roboco.android.design.ActionSheetPresenter
import sh.roboco.android.design.ActionSheetStyle
import sh.roboco.android.design.AlertButton
import sh.roboco.android.design.AlertButtonRole
import sh.roboco.android.design.Alerts
import sh.roboco.android.design.RobocoAlert
import sh.roboco.android.design.Toaster
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot

/**
 * The Accounts & Usage page's copy (android-next#05, PR #578's wording
 * adapted to Roboco: "Zeron" → "Roboco", "provider accounts" → "agent
 * accounts"). The ellipses are U+2026.
 */
object AccountsCopy {
    /** The page's nav title. */
    const val PageTitle = "Accounts & Usage"

    /** The row that opens the page (the chip menu's last row). */
    const val OpenPage = "Accounts & Usage\u2026"

    /** The Settings section's header. */
    const val SectionHeader = "Agent Accounts"

    /** The ⋯ menu's row on the session screen. */
    const val SessionMenuRow = "Account & Usage"

    /** The page's pending row. */
    const val Loading = "Loading accounts\u2026"

    /** No paired engine can be asked (the page is unreachable in practice). */
    const val NoComputer = "No computer to ask yet. Open Roboco on a computer to add one."

    /** The page's empty state. */
    fun noAccounts(deviceName: String) = "No agent accounts on $deviceName yet."

    /**
     * The page's about footer: switching semantics plus where logins are
     * added (their OAuth redirects land on the computer's loopback, so
     * the phone cannot sign in).
     */
    fun signInSection(deviceName: String) =
        "Switching changes the login $deviceName uses for new sessions. To add an account, sign in from Roboco on $deviceName."

    /** The switch confirmation toast on the page. */
    fun switchedToast(harness: String, login: String) = "$harness now uses $login"

    /** The switch toast from the composer's chip menu. */
    fun switchedToastForNewSessions(harness: String, login: String) =
        "$harness now uses $login for new sessions"

    /** The chip-menu switch failure toast. */
    const val SwitchFailedToast = "Couldn't switch accounts"

    /** The page's switch-failure alert title. */
    const val SwitchFailedTitle = "Couldn't switch accounts"

    /** The page's remove-failure alert title. */
    const val RemoveFailedTitle = "Couldn't remove the account"

    /** The remove confirmation's title. */
    fun removeTitle(login: String) = "Remove $login?"

    /** The active login's remove message. */
    fun removeActiveMessage(harness: String, deviceName: String) =
        "$harness on $deviceName will be signed out until you pick another login."

    /** A saved, inactive login's remove message. */
    fun removeMessage(deviceName: String) = "Its saved login is removed from $deviceName."

    /** The remove confirmation's destructive action. */
    const val RemoveAction = "Remove Account"

    /** The not-switchable guard alert. */
    const val CantSwitchTitle = "Can't switch to this login"

    /** The not-switchable guard alert's message. */
    fun cantSwitchMessage(deviceName: String) =
        "Roboco couldn't read its credentials on $deviceName. Sign in to it again there."

    /** The context menu's switch row. */
    const val UseThisAccount = "Use This Account"

    /** The context menu's copy row. */
    const val CopyEmail = "Copy Email"

    /** The context menu's remove row. */
    const val RemoveAccountDots = "Remove Account\u2026"

    /** The row meta's marker. */
    const val InUse = "In use"

    /** The row meta's not-switchable marker. */
    const val CredentialsUnavailable = "Credentials unavailable"

    /** The settings section's footer lead. */
    fun planUsageOn(deviceName: String) = "Plan usage on $deviceName."

    /** The section's pending footer. */
    const val UpdatingUsage = "Updating usage\u2026"
}

/**
 * The page's load state. `Unsupported` (an engine too old for the
 * accounts RPCs) is terminal for auto-refresh: the page keeps saying so
 * until a manual retry, never error-spamming; the Settings section and
 * the usage chip hide for that engine entirely.
 */
sealed interface AccountsLoad {
    /** No answer yet (the cached snapshot may already paint). */
    data object Loading : AccountsLoad

    /** A snapshot is on screen. */
    data object Ready : AccountsLoad

    /** The engine could not answer; the message is on screen. */
    data class Failed(val message: String) : AccountsLoad

    /** The engine predates the accounts RPCs. */
    data class Unsupported(val message: String) : AccountsLoad
}

/**
 * The Accounts & Usage page's behaviour (android-next#05) — the port of
 * PR #578's `ProviderAccountsViewController` data half: load
 * (cache → plain → forced), the optimistic switch with revert, the
 * confirmed remove, the pull-to-refresh re-probe and the per-device
 * state. Pure drawing lives in [AgentAccountsScreen]; this class owns
 * nothing composable but the snapshot states.
 *
 * Scopes (140's rule): [workScope] — the application scope — carries
 * every FFI call, so a pop mid-switch or mid-forget never cancels it
 * half-way; [uiScope] carries nothing here (no timers). The FFI hops go
 * through [io].
 */
@Stable
class AgentAccountsModel(
    private val core: AccountsCore,
    private val workScope: CoroutineScope,
    private val toaster: Toaster,
    private val alerts: Alerts,
    private val sheets: ActionSheetPresenter,
    /** The selected device's display name (never its id). */
    private val deviceName: (deviceId: String) -> String,
    private val harnessLabel: (harness: String) -> String = { uniffi.roboco_core.harnessLabel(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The device whose accounts the page shows (the picker's choice). */
    var deviceId: String? by mutableStateOf(null)
        private set

    /** The snapshot on screen (the cached one paints at once). */
    var snapshot: AgentAccountsSnapshot? by mutableStateOf(null)
        private set

    var load: AccountsLoad by mutableStateOf(AccountsLoad.Loading)
        private set

    /** The account being switched to (its row shows the spinner). */
    var switching: String? by mutableStateOf(null)
        private set

    /** Whether a probe is in flight (the footer reads "Updating usage…"). */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    /** The newest probe's wording, for the page's about footer. */
    val updatedText: String?
        get() = snapshot?.let { updatedText(it, nowMs()) }

    /**
     * Opens the page on `device`: the core's cached snapshot paints at
     * once, then the plain list (the host's last probe) and the forced
     * probe land behind it. `null` (no computer) shows the no-computer
     * message.
     */
    fun open(deviceId: String?) {
        this.deviceId = deviceId
        if (deviceId == null) {
            snapshot = null
            load = AccountsLoad.Loading
            return
        }
        val cached = core.cached(deviceId)
        if (cached != null) {
            snapshot = cached
            load = AccountsLoad.Ready
        } else {
            snapshot = null
            load = AccountsLoad.Loading
        }
        workScope.launch { loadOnce(deviceId, force = false, thenForce = true) }
    }

    /** Pull-to-refresh: re-probe usage on the host. Suspends until the
     * probe answers (the pull state's own 3 s backstop cuts it; the
     * spinner then closes and the data lands whenever it lands). */
    suspend fun pullToRefresh() {
        val deviceId = deviceId ?: return
        if (load is AccountsLoad.Unsupported) return
        loadOnce(deviceId, force = true, thenForce = false)
    }

    /** The failed state's retry. An unsupported engine never retries —
     * the page keeps its message until a fresh open. */
    fun retry() {
        val deviceId = deviceId ?: return
        if (load is AccountsLoad.Unsupported) return
        load = AccountsLoad.Loading
        workScope.launch { loadOnce(deviceId, force = true, thenForce = false) }
    }

    /**
     * One load: `force` re-probes; `thenForce` chains a plain list with a
     * forced probe (the page's open sequence — the host's last probe
     * paints, then the fresh usage replaces it). Failures keep what is on
     * screen and say why only when nothing is (`Failed`); an unsupported
     * engine is terminal (no auto retry, no spam). Runs on [workScope]
     * (main-immediate); every FFI hop is inside [withContext]`[io]`, so
     * the state writes land back on main.
     */
    private suspend fun loadOnce(deviceId: String, force: Boolean, thenForce: Boolean) {
        if (load is AccountsLoad.Unsupported) return
        refreshing = true
        try {
            try {
                val answer = withContext(io) { core.list(deviceId, force) }
                if (this.deviceId != deviceId) return
                snapshot = answer
                load = AccountsLoad.Ready
                if (thenForce) {
                    val forced = withContext(io) { core.list(deviceId, true) }
                    if (this.deviceId == deviceId) {
                        snapshot = forced
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (this.deviceId != deviceId) return
                if (snapshot == null) {
                    load = if (isUnsupportedEngine(t)) {
                        AccountsLoad.Unsupported(describeAccountsError(t, nameOf(deviceId)))
                    } else {
                        AccountsLoad.Failed(describeAccountsError(t, nameOf(deviceId)))
                    }
                }
            }
        } finally {
            if (this.deviceId == deviceId) refreshing = false
        }
    }

    /**
     * A row tap. The active login is a no-op; a not-switchable one gets
     * the guard alert; anything else switches optimistically.
     */
    fun onRowTap(account: AgentAccount) {
        if (account.active) return
        if (!account.switchable) {
            alerts.show(
                RobocoAlert(
                    title = AccountsCopy.CantSwitchTitle,
                    message = accountDeviceCantSwitch(),
                    buttons = listOf(AlertButton("OK", role = AlertButtonRole.Cancel)),
                ),
            )
            return
        }
        switchTo(account)
    }

    /**
     * Switch optimistically, like the desktop and the web: the rows flip
     * at once (only the switched-to row's group changes), the host's
     * reply replaces them, a refusal restores the previous list and
     * alerts.
     */
    fun switchTo(account: AgentAccount) {
        val deviceId = deviceId ?: return
        if (switching != null) return
        val previous = snapshot
        // Bind first: `snapshot` is a delegated property and never
        // smart-casts after the null check.
        val current = snapshot
        if (current != null) {
            snapshot = markSwitched(current, account)
        }
        switching = account.id
        workScope.launch {
            try {
                val answer = withContext(io) { core.activate(deviceId, account.harness, account.id) }
                if (this@AgentAccountsModel.deviceId == deviceId) {
                    snapshot = answer
                    load = AccountsLoad.Ready
                }
                switching = null
                toaster.show(
                    AccountsCopy.switchedToast(harnessLabel(account.harness), accountTitle(account)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                switching = null
                if (this@AgentAccountsModel.deviceId == deviceId) {
                    snapshot = previous
                }
                alerts.show(
                    RobocoAlert(
                        title = AccountsCopy.SwitchFailedTitle,
                        message = describeAccountsError(t, nameOf(deviceId)),
                        buttons = listOf(AlertButton("OK", role = AlertButtonRole.Cancel)),
                    ),
                )
            }
        }
    }

    /**
     * The remove confirmation (19's action sheet, like 140's Forget):
     * "Remove <login>?" with the active/inactive message, then the
     * destructive `Remove Account` and Cancel. The forget itself runs on
     * [workScope], so a pop mid-flight never cancels it.
     */
    fun onRemove(account: AgentAccount) {
        val deviceId = deviceId ?: return
        val device = nameOf(deviceId)
        val message = if (account.active) {
            AccountsCopy.removeActiveMessage(harnessLabel(account.harness), device)
        } else {
            AccountsCopy.removeMessage(device)
        }
        sheets.show(
            ActionSheetModel(
                title = AccountsCopy.removeTitle(accountTitle(account)),
                message = message,
                actions = listOf(
                    ActionSheetAction(
                        title = AccountsCopy.RemoveAction,
                        style = ActionSheetStyle.Destructive,
                    ) { forget(account) },
                ),
            ),
        )
    }

    private fun forget(account: AgentAccount) {
        val deviceId = deviceId ?: return
        workScope.launch {
            try {
                val answer = withContext(io) { core.forget(deviceId, account.harness, account.id) }
                if (this@AgentAccountsModel.deviceId == deviceId) {
                    snapshot = answer
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                alerts.show(
                    RobocoAlert(
                        title = AccountsCopy.RemoveFailedTitle,
                        message = describeAccountsError(t, nameOf(deviceId)),
                        buttons = listOf(AlertButton("OK", role = AlertButtonRole.Cancel)),
                    ),
                )
            }
        }
    }

    private fun nameOf(deviceId: String): String = deviceName(deviceId)

    /** The selected device's display name ("your computer" until one is
     * picked), for the empty state and the footers. */
    fun nameOfSelected(): String = deviceId?.let(deviceName) ?: "your computer"

    private fun accountDeviceCantSwitch(): String =
        AccountsCopy.cantSwitchMessage(nameOfSelected())
}

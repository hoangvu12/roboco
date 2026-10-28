package sh.roboco.android.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentLoginRules
import uniffi.roboco_core.CoreClient
import uniffi.roboco_core.CoreException
import uniffi.roboco_core.DeviceLoginInfo
import uniffi.roboco_core.DeviceLoginPollInfo
import uniffi.roboco_core.HarnessInfo

/**
 * "Add account" (wave-10 34, upstream #966's iPhone `DeviceSignIn`): the
 * phone signs an agent in on a paired computer. The computer runs the
 * sign-in (`StartAgentLogin`); the phone opens its page in a Custom Tab
 * and polls; a device-code login shows its code, Claude's paste fallback
 * asks for the code back. A seam of its own (not [AccountsCore]) so the
 * accounts surfaces that never sign in keep their fakes as they are.
 * Every member but [rules] is FFI: call from an IO lane, never on main.
 */
interface AccountSignIn {

    /** The agents on `deviceId` (`CoreClient.listHarnesses`; never throws). */
    suspend fun harnesses(deviceId: String): List<HarnessInfo>

    /** `CoreClient.setHarnessEnabled` — wave-10 40; the device's fresh catalog. */
    suspend fun setEnabled(deviceId: String, harness: String, enabled: Boolean): List<HarnessInfo>

    /** `CoreClient.deviceLoginStart`. */
    suspend fun start(deviceId: String, harness: String, provider: String?): DeviceLoginInfo

    /** `CoreClient.deviceLoginPoll`. */
    suspend fun poll(deviceId: String, loginId: String): DeviceLoginPollInfo

    /** `CoreClient.deviceLoginComplete` — the pasted code; the fresh accounts. */
    suspend fun complete(deviceId: String, loginId: String, code: String): AgentAccountsSnapshot

    /** `CoreClient.deviceLoginCancel` — best effort, fire-and-forget. */
    fun cancel(deviceId: String, loginId: String)

    /** The static `agentLoginRules(harness)` — pure, safe on main. */
    fun rules(harness: String): AgentLoginRules
}

/** The live [AccountSignIn] over the process's single [CoreClient]. */
class CoreAccountSignIn(
    private val client: () -> CoreClient?,
) : AccountSignIn {
    override suspend fun harnesses(deviceId: String): List<HarnessInfo> =
        withContext(Dispatchers.IO) { client()?.listHarnesses(deviceId).orEmpty() }

    override suspend fun setEnabled(deviceId: String, harness: String, enabled: Boolean): List<HarnessInfo> =
        withContext(Dispatchers.IO) { live().setHarnessEnabled(deviceId, harness, enabled) }

    override suspend fun start(deviceId: String, harness: String, provider: String?): DeviceLoginInfo =
        withContext(Dispatchers.IO) { live().deviceLoginStart(deviceId, harness, provider) }

    override suspend fun poll(deviceId: String, loginId: String): DeviceLoginPollInfo =
        withContext(Dispatchers.IO) { live().deviceLoginPoll(deviceId, loginId) }

    override suspend fun complete(deviceId: String, loginId: String, code: String): AgentAccountsSnapshot =
        withContext(Dispatchers.IO) { live().deviceLoginComplete(deviceId, loginId, code) }

    override fun cancel(deviceId: String, loginId: String) {
        client()?.deviceLoginCancel(deviceId, loginId)
    }

    override fun rules(harness: String): AgentLoginRules = uniffi.roboco_core.agentLoginRules(harness)

    private fun live(): CoreClient = client() ?: throw CoreException.Closed()
}

/** The sign-in's copy (upstream #966's iPhone strings, "Zeron" → "Roboco"). */
object SignInCopy {
    /** The waiting row (`ios:Cloud/AgentAccountsViewController.swift:300`). */
    const val Waiting = "Finish signing in in the browser…"

    /** The waiting row's cancel hint. */
    const val TapToCancel = "Tap to cancel"

    /** The paste-code alert. */
    const val PasteTitle = "Paste the code"
    const val PasteMessage = "Copy the code the sign-in page shows, then paste it here."
    const val PastePlaceholder = "Authorization code"
    const val Continue = "Continue"
    const val Cancel = "Cancel"

    /** The failure alert's title. */
    const val FailedTitle = "Couldn't sign in"

    /** A poll that reported an error without a message. */
    const val Failed = "The sign-in failed."

    /** The ten-minute deadline. */
    const val TimedOut = "The sign-in timed out."

    /**
     * The page's about footer once the phone can sign in (replacing
     * [AccountsCopy.signInSection]'s "sign in from Roboco on …").
     */
    fun about(deviceName: String) =
        "Switching changes the login $deviceName uses for new sessions. Signing in runs on $deviceName; you finish in the browser here."
}

/** One "Add account" row: the option it starts and its title. */
data class AddAccountRow(
    /** `add:<harness>:<provider or "">` — the row's key and test tag. */
    val id: String,
    val harness: String,
    val provider: String?,
    val title: String,
)

/**
 * The add rows under one agent's accounts (iOS `addRows`): one per login
 * option — the first account's title when the agent has none, else the
 * "add another" one — and none for an agent that keeps one login and has
 * it. An agent Roboco doesn't sign in to gets none. Pure.
 */
fun addAccountRows(harness: String, rules: AgentLoginRules, accounts: List<AgentAccount>): List<AddAccountRow> {
    if (!rules.signsIn) return emptyList()
    val empty = accounts.none { it.harness == harness }
    if (!empty && rules.keepsOneLogin) return emptyList()
    return rules.options.map { option ->
        AddAccountRow(
            id = "add:$harness:${option.provider ?: ""}",
            harness = harness,
            provider = option.provider,
            title = if (empty) option.firstTitle else option.moreTitle,
        )
    }
}

/**
 * The page's sections once it can add accounts: [providerGroups] plus an
 * empty section for every agent the computer offers that signs in but has
 * no login yet (where "Connect a … account" lives), in desktop order.
 * Pure.
 */
fun accountSections(
    snapshot: AgentAccountsSnapshot,
    offered: List<String>,
    signsIn: (String) -> Boolean,
): List<ProviderGroup> {
    val groups = providerGroups(snapshot)
    val present = groups.map { it.harness }.toSet()
    val extra = offered.distinct()
        .filter { it !in present && signsIn(it) }
        .map { ProviderGroup(harness = it, accounts = emptyList(), warnings = emptyList()) }
    return (groups + extra).sortedWith(compareBy({ providerRank(it.harness) }, { it.harness }))
}

/** A sign-in under way: which add row, and what to show while it waits. */
data class SignInState(
    val rowId: String,
    val harness: String,
    val message: String? = null,
)

/** The waiting row's detail line: the device code note, then the cancel hint. */
fun signInDetail(state: SignInState): String =
    listOfNotNull(state.message, SignInCopy.TapToCancel).joinToString(" · ")

/**
 * The device code inside a poll's note ("Enter the code ABCD-1234 when
 * asked."), to have it ready on the clipboard — iOS's rule: the first
 * word with a dash, at least 7 characters. Pure.
 */
fun deviceCodeIn(note: String): String? =
    note.split(' ').map { it.trimEnd('.', ',') }.firstOrNull { it.contains('-') && it.length >= 7 }

/** The Agents section's copy (upstream #966 `ProvidersViewController`, verbatim). */
object AgentsCopy {
    fun header(deviceName: String) = "Agents on $deviceName"

    const val Installed = "Installed"
    const val NotInstalled = "Not installed"
    const val Offline = "Offline"

    fun failedTitle(on: Boolean) = if (on) "Couldn't turn it on" else "Couldn't turn it off"

    const val Footer = "New sessions on this computer offer the agents that are on. The last one stays on."
}

/** One agent's row: what it says and whether its switch takes a flip. */
data class AgentToggleRow(
    val harness: String,
    val label: String,
    val subtitle: String,
    /** The switch's state (an agent that isn't installed reads off). */
    val on: Boolean,
    /** The last agent standing stays on; an offline computer or a missing CLI can't change. */
    val toggleEnabled: Boolean,
    /** Not installed: dimmed. */
    val dimmed: Boolean,
)

/**
 * The computer's agents (iOS `computerRows`): installed first, then by
 * name; "Installed" / "Not installed" / "Offline"; the switch on for an
 * offered agent, and locked for the last one on, a missing CLI or an
 * offline computer. Pure.
 */
fun agentToggleRows(list: List<HarnessInfo>, online: Boolean): List<AgentToggleRow> {
    val enabledCount = list.count { it.offered }
    return list
        .sortedWith(compareBy({ if (it.installed) 0 else 1 }, { it.label }))
        .map { h ->
            val on = h.installed && h.offered
            AgentToggleRow(
                harness = h.id,
                label = h.label,
                subtitle = when {
                    !h.installed -> AgentsCopy.NotInstalled
                    online -> AgentsCopy.Installed
                    else -> AgentsCopy.Offline
                },
                on = on,
                toggleEnabled = online && h.installed && !(on && enabledCount <= 1),
                dimmed = !h.installed,
            )
        }
}

/** Why a switch didn't take, for its alert. Pure. */
fun agentToggleFailure(error: Throwable, deviceName: String): String = when (error) {
    is CoreException.HostUnavailable -> "$deviceName is offline. Try again when it's back."
    is CoreException.HostException -> error.reason.ifBlank { "The change could not be applied." }
    else -> "The change could not be applied."
}

/** Why a sign-in failed, for the alert. Pure. */
fun signInFailure(error: Throwable, deviceName: String): String = when (error) {
    is CoreException.HostUnavailable -> "$deviceName is offline. Try again when it's back."
    is CoreException.Unsupported -> "$deviceName needs a newer Roboco to sign in from this phone."
    is CoreException.HostException -> error.reason.ifBlank { SignInCopy.Failed }
    else -> SignInCopy.Failed
}

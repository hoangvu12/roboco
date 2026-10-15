package sh.roboco.android.accounts

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.ChromeFiller
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.SessionChrome
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot

/**
 * The composer's usage chip (android-next#05) — the port of PR #578's
 * plan-usage half of `CoreSessionSource`: when the session agent's login
 * is at ≥ 80 % of any window, the chips **lead** with an amber (≥ 95 %:
 * red) `<Window> NN%` chip. Its menu lists the windows with their reset
 * times, the other logins of that group (with their usage) to switch to,
 * and "Accounts & Usage…".
 *
 * Refresh policy (iOS's `refreshUsage`): the host's last probe when a
 * session opens (the core's snapshot cache may already answer), a fresh
 * probe after turns end (usage moves with them), at most every 2 minutes.
 * An engine too old for the accounts RPCs hides the chip for the session
 * — no error spam: the filler stops asking after the first `Unsupported`.
 *
 * Threading (the #609 rules): [fill] runs on the controller's IO lane and
 * touches no FFI — the chip derives from the filler's own snapshot; the
 * loads and the switch run on the filler's own IO scope; [menu] runs on
 * main at tap time and makes zero core calls.
 */
class SessionAccountUsage(
    private val core: AccountsCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val harnessLabel: (harness: String) -> String = { uniffi.roboco_core.harnessLabel(it) },
) : ChromeFiller {

    /**
     * Set by the session screen's wiring: opens the Accounts & Usage page
     * scoped to this session's harness and computer. Default no-op.
     */
    @Volatile
    var onOpenAccounts: () -> Unit = {}

    /** Set by the session screen's wiring (the app-scoped toaster). */
    @Volatile
    var toaster: Toaster? = null

    /** The load lane: never cancelled — an in-flight load always lands. */
    private val loadScope = CoroutineScope(SupervisorJob() + io)

    private val closed = AtomicBoolean(false)

    /** The host's accounts as of the last load (the chip's source). */
    @Volatile
    private var snapshot: AgentAccountsSnapshot? = null

    /** The session's host device (empty until the first refresh). */
    @Volatile
    private var hostDevice: String = ""

    /** The session agent's wire harness id. */
    @Volatile
    private var harness: String? = null

    /** `true` once the engine answered `Unsupported` — never ask again. */
    @Volatile
    private var unsupported = false

    @Volatile
    private var lastRunning: Boolean = false

    /** The last forced probe (the 2-minute throttle). */
    @Volatile
    private var forcedAtMs: Long = 0L

    /** The last plain list (the 5-minute staleness gate). */
    @Volatile
    private var plainAtMs: Long = 0L

    /** The invalidate handle [bind] stores (the controller's trigger). */
    @Volatile
    private var invalidate: () -> Unit = {}

    override fun bind(invalidate: () -> Unit) {
        this.invalidate = invalidate
    }

    /**
     * Stores the refresh inputs, prepends the usage chip when the active
     * login is at ≥ 80 % of any window, and asks for the loads the policy
     * allows. No FFI: the snapshot is the filler's own.
     */
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome {
        val deviceId = input.composer.host.deviceId
        if (deviceId.isNotEmpty() && deviceId != hostDevice) {
            hostDevice = deviceId
            snapshot = null
            forcedAtMs = 0L
            plainAtMs = 0L
        }
        harness = input.row?.harness
        val running = input.composer.live.turnRunning
        val turnEnded = lastRunning && !running
        lastRunning = running
        maybeLoad(turnEnded)
        val chip = usageChip()
        return if (chip == null) {
            chrome
        } else {
            chrome.copy(chips = listOf(chip) + chrome.chips)
        }
    }

    /**
     * The near-limit chip: `<Window> NN%` (PR #578's wording — "Session
     * 88%"), amber from 80 %, red from 95 % with the warning glyph; leads
     * the chips (inserted at 0, where the next message is written).
     * `null` below the threshold, with no snapshot, or for a harness the
     * host did not name an active login for.
     */
    internal fun usageChip(): ComposerChip? {
        val snapshot = snapshot ?: return null
        val harness = harness ?: return null
        val active = activeAccountOf(snapshot, harness) ?: return null
        val top = topWindowOf(active) ?: return null
        val fraction = clampedFraction(top.usedFraction)
        if (usageLevelOf(fraction) == UsageLevel.Normal) return null
        val critical = usageLevelOf(fraction) == UsageLevel.Critical
        return ComposerChip(
            id = SessionUsageChipIds.Usage,
            title = "${top.label} ${usagePercent(fraction)}",
            symbol = if (critical) USAGE_CRITICAL_SYMBOL else USAGE_SYMBOL,
            tint = if (critical) ChipTint.Critical else ChipTint.Warning,
        )
    }

    /**
     * Ticket 59's `ChipMenuProvider` body for the usage chip, called on
     * main at tap time. The windows (read-only, with the written-out
     * reset times), the other logins of the group to switch to (with
     * their own usage), and "Accounts & Usage…". `null` without a
     * snapshot (the strip falls back to `onChipTap`, a no-op in a
     * session).
     */
    fun menu(chipId: String): Menu? {
        if (chipId != SessionUsageChipIds.Usage) return null
        val snapshot = snapshot ?: return null
        val harness = harness ?: return null
        val active = activeAccountOf(snapshot, harness) ?: return null
        val now = nowMs()
        val windows = active.usageWindows.map { window ->
            MenuItem(
                title = "${window.label} ${usagePercent(window.usedFraction)}",
                subtitle = resetText(window.resetsAtMs, now),
                icon = MenuIcon.Symbol(RobocoIcons.Gauge),
                informational = true,
                testTag = "usage-menu-window-${window.label}",
            ) {},
        }
        val switches = otherAccountsOf(snapshot, active)
            .filter { it.switchable }
            .map { account ->
                MenuItem(
                    title = "Switch to ${accountTitle(account)}",
                    subtitle = topWindowOf(account)?.let { "${it.label} ${usagePercent(it.usedFraction)}" }
                        ?: account.planLabel,
                    icon = MenuIcon.Symbol(RobocoIcons.ArrowUpArrowDown),
                    testTag = "usage-menu-switch-${account.id}",
                ) { switchTo(account) }
            }
        val open = MenuItem(
            title = AccountsCopy.OpenPage,
            icon = MenuIcon.Symbol(RobocoIcons.Desktopcomputer),
            testTag = "usage-menu-open",
        ) { onOpenAccounts() }
        return Menu(
            title = "${harnessLabel(active.harness)} \u00B7 ${accountTitle(active)}",
            children = buildList {
                if (windows.isNotEmpty()) addAll(windows)
                addAll(switches)
                add(open)
            },
        )
    }

    /**
     * The route is discarded / closed: later loads resolve empty — never
     * cancels a load already in flight (the answer still refreshes the
     * core's cache, which the next session paints from).
     */
    fun dispose() {
        closed.set(true)
    }

    // ── the loads and the switch ───────────────────────────────────────

    /**
     * The load policy: after a turn ends, a forced probe at most every
     * 2 minutes; with no snapshot yet, the plain list (the host's last
     * probe) at most every 5 minutes. An `Unsupported` engine never asks
     * again.
     */
    private fun maybeLoad(turnEnded: Boolean) {
        val host = hostDevice
        if (host.isEmpty() || closed.get() || unsupported) return
        val now = nowMs()
        if (turnEnded && now - forcedAtMs >= FORCED_MIN_INTERVAL_MS) {
            forcedAtMs = now
            load(force = true)
        } else if (snapshot == null && now - plainAtMs >= PLAIN_STALE_MS) {
            plainAtMs = now
            load(force = false)
        }
    }

    private fun load(force: Boolean) {
        val host = hostDevice
        loadScope.launch {
            try {
                val answer = withContext(io) { core.list(host, force) }
                if (closed.get()) return@launch
                snapshot = answer
                invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (isUnsupportedEngine(t)) {
                    // Hide the chip for this engine, and never ask again.
                    unsupported = true
                }
                // Any other failure stays silent: the chip is hidden until
                // the next policy window, like iOS's `try?`.
            }
        }
    }

    /**
     * One switch from the chip menu: optimistic rows, the engine's reply
     * replaces them, a failure restores and toasts (PR #578's chip-menu
     * actions — "Couldn't switch accounts", no alert).
     */
    private fun switchTo(account: AgentAccount) {
        val host = hostDevice
        if (host.isEmpty()) return
        val current = snapshot
        if (current != null) {
            snapshot = markSwitched(current, account)
            invalidate()
        }
        loadScope.launch {
            try {
                val answer = withContext(io) { core.activate(host, account.harness, account.id) }
                if (closed.get()) return@launch
                snapshot = answer
                invalidate()
                toaster?.show(
                    AccountsCopy.switchedToastForNewSessions(
                        harnessLabel(account.harness),
                        accountTitle(account),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (!closed.get()) {
                    snapshot = current
                    invalidate()
                    toaster?.show(AccountsCopy.SwitchFailedToast)
                }
            }
        }
    }
}

/**
 * The chip id — the contract with ticket 59's strip, ticket 89's provider
 * map and the test tags `composer-chip-usage`.
 */
object SessionUsageChipIds {
    /** The plan-usage chip (`Session 88%`). */
    const val Usage = "usage"
}

/** The usage chip's calm glyph (iOS's `gauge.with.dots.needle.67percent`). */
const val USAGE_SYMBOL = "gauge.with.dots.needle.67percent"

/** The usage chip's glyph at the critical level (iOS's `exclamationmark.circle`). */
const val USAGE_CRITICAL_SYMBOL = "exclamationmark.circle"

/** A forced probe at most this often (iOS `refreshUsage`'s 120 s). */
const val FORCED_MIN_INTERVAL_MS = 120_000L

/** A plain list at most this stale (iOS `refreshUsage`'s 300 s). */
const val PLAIN_STALE_MS = 300_000L

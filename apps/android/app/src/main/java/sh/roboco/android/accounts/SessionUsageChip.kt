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
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import sh.roboco.android.composer.ContextCardAccounts
import sh.roboco.android.session.ChromeFiller
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.CONTEXT_WARNING_SYMBOL
import sh.roboco.android.session.SessionChipIds
import sh.roboco.android.session.SessionChrome
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot

/**
 * The composer's plan usage (android-next#05) — the port of PR #578's
 * `refreshUsage` half of `CoreSessionSource`, folded into android-next
 * #01's context chip (user decision, 2026-10-15: no separate usage
 * chip). This filler owns the account side only:
 *
 * - the **tint**: the context chip keeps its context reading ("12%
 *   context" / tokens); its tint becomes the **worse** of the context
 *   reading (#01's ≥ 85 % amber) and the plan usage of the session
 *   harness's live login (amber ≥ 80 %, red ≥ 95 % — the desktop
 *   thresholds), with the warning glyph when either warns;
 * - the **menu's account half**: [augmentContextCard] appends the
 *   Account section (the login in use and every window with percent and
 *   written-out reset), the harness's other logins to switch to, and
 *   "Accounts & Usage…" to #01's context-window details card.
 *
 * Refresh policy (iOS's `refreshUsage`): the host's last probe when a
 * session opens (the core's snapshot cache may already answer), a fresh
 * probe after turns end (usage moves with them), at most every 2 minutes.
 * An engine too old for the accounts RPCs stops asking after the first
 * `Unsupported` (no error spam): the chip keeps #01's behaviour, the
 * menu stays the plain context card.
 *
 * Threading (the #609 rules): [fill] runs on the controller's IO lane
 * and touches no FFI — the tint derives from the filler's own snapshot;
 * the loads and the switch run on the filler's own IO scope; the menu
 * augmentation runs on main at tap time and makes zero core calls.
 */
class SessionAccountUsage(
    private val core: AccountsCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val harnessLabel: (harness: String) -> String = { uniffi.roboco_core.harnessLabel(it) },
) : ChromeFiller, ContextCardAccounts {

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

    /** The host's accounts as of the last load (the tint's and menu's source). */
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
     * Stores the refresh inputs, asks for the loads the policy allows,
     * and re-tints the context chip when the plan usage is the worse
     * reading. No FFI: the snapshot is the filler's own; the context chip
     * itself is rebuilt by #01's filler on every refresh, so the override
     * never goes stale.
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
        return tintContextChip(chrome)
    }

    /**
     * The plan-usage level of the session harness's live login's binding
     * window (the desktop thresholds), or `null` without data (no
     * snapshot, no harness, no active login, no windows).
     */
    internal fun planLevel(): UsageLevel? {
        val snapshot = snapshot ?: return null
        val harness = harness ?: return null
        val active = activeAccountOf(snapshot, harness) ?: return null
        val top = topWindowOf(active) ?: return null
        return usageLevelOf(clampedFraction(top.usedFraction))
    }

    /**
     * The worse-of tint: the plan usage's level over the context chip
     * #01 built this refresh. Amber at the plan's warning (the context's
     * own ≥ 85 % amber is already on the chip — the plan only ever
     * **worsens** it), red at the plan's critical, the warning glyph with
     * either. The chip untouched below the threshold (a calm plan leaves
     * whatever #01 decided).
     */
    private fun tintContextChip(chrome: SessionChrome): SessionChrome {
        val plan = planLevel() ?: return chrome
        if (plan == UsageLevel.Normal) return chrome
        val tint = if (plan == UsageLevel.Critical) ChipTint.Critical else ChipTint.Warning
        return chrome.copy(
            chips = chrome.chips.map { chip ->
                if (chip.id == SessionChipIds.Context) {
                    chip.copy(tint = tint, symbol = CONTEXT_WARNING_SYMBOL)
                } else {
                    chip
                }
            },
        )
    }

    /**
     * The context chip's menu, account half (ticket 89's `menu("context")`
     * calls this on main at tap time): the **Account** section — the login
     * in use and every usage window with its percent and written-out
     * reset moment (read-only rows, #01's informational style) — then the
     * harness's other logins, each with its fullest window, tap to switch
     * (optimistic, toast), then "Accounts & Usage…" opening the page
     * scoped to this harness and computer. Without account data (an old
     * engine, offline, or a harness with no accounts) the card is
     * returned unchanged: #01's context-window details card alone.
     */
    override fun augmentContextCard(card: Menu): Menu {
        val snapshot = snapshot ?: return card
        val harness = harness ?: return card
        val active = activeAccountOf(snapshot, harness) ?: return card
        val now = nowMs()
        return card.copy(
            children = buildList {
                addAll(card.children)
                add(
                    MenuSection(
                        title = ACCOUNT_SECTION_TITLE,
                        inline = true,
                        children = buildList {
                            add(
                                MenuItem(
                                    title = accountTitle(active),
                                    subtitle = AccountsCopy.InUse,
                                    informational = true,
                                    testTag = "context-menu-account",
                                ) {},
                            )
                            for (window in active.usageWindows) {
                                add(
                                    MenuItem(
                                        title = "${window.label} ${usagePercent(window.usedFraction)}",
                                        subtitle = resetText(window.resetsAtMs, now),
                                        informational = true,
                                        testTag = "context-menu-window-${window.label}",
                                    ) {},
                                )
                            }
                        },
                    ),
                )
                // The other logins of the active account's single-choice
                // group, with their fullest window; tap to switch.
                for (account in otherAccountsOf(snapshot, active).filter { it.switchable }) {
                    add(
                        MenuItem(
                            title = "Switch to ${accountTitle(account)}",
                            subtitle = topWindowOf(account)
                                ?.let { "${it.label} ${usagePercent(it.usedFraction)}" }
                                ?: account.planLabel,
                            icon = MenuIcon.Symbol(RobocoIcons.ArrowUpArrowDown),
                            testTag = "context-menu-switch-${account.id}",
                        ) { switchTo(account) },
                    )
                }
                add(
                    MenuItem(
                        title = AccountsCopy.OpenPage,
                        icon = MenuIcon.Symbol(RobocoIcons.Desktopcomputer),
                        testTag = "context-menu-open",
                    ) { onOpenAccounts() },
                )
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
                    // Stop asking: the chip keeps #01's behaviour and the
                    // menu stays the plain context card.
                    unsupported = true
                }
                // Any other failure stays silent: nothing is tinted or
                // appended until the next policy window, like iOS's `try?`.
            }
        }
    }

    /**
     * One switch from the context chip's menu: optimistic rows, the
     * engine's reply replaces them, a failure restores and toasts (PR
     * #578's chip-menu actions — "Couldn't switch accounts", no alert).
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

/** The context chip menu's Account section header. */
const val ACCOUNT_SECTION_TITLE = "Account"

/** A forced probe at most this often (iOS `refreshUsage`'s 120 s). */
const val FORCED_MIN_INTERVAL_MS = 120_000L

/** A plain list at most this stale (iOS `refreshUsage`'s 300 s). */
const val PLAIN_STALE_MS = 300_000L

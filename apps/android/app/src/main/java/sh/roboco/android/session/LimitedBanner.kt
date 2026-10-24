package sh.roboco.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import sh.roboco.android.design.Toaster
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.ResumeCandidates
import uniffi.roboco_core.ResumeSwitchOutcome

/**
 * The Limited chat's banner state on [SessionChrome] — the Android arm of
 * the desktop's Limited status strip (orchestration v2, tickets 07/09).
 * Value-equality drives recomposition; every field is a value type.
 *
 * The card renders while this is non-null (the chat's status row reads
 * Limited — errored with a `limit` record); [LimitedBanner] derives it
 * from `ComposerState.limit` and owns everything the actions need.
 */
@androidx.compose.runtime.Immutable
data class LimitedChrome(
    /** The harness's own window label ("5-hour", "weekly", …). */
    val window: String,
    /** Epoch millis when the window resets, `null` when unknown. */
    val resetAtMs: Long? = null,
    /** The armed Resume-at-reset continuation's fire time, `null` when none is armed. */
    val fireAtMs: Long? = null,
    /** "Resume on another account" offers rows (the list loaded and has candidates or signed-out accounts). */
    val switchAvailable: Boolean = false,
    /** The candidate load failed — the card offers Retry instead. */
    val retry: Boolean = false,
    /** A switch is in flight — the card's actions disable. */
    val switching: Boolean = false,
    /** The last switch outcome's notice line (chat-scoped, lives until the next switch or the banner's own state supersedes it). */
    val notice: String? = null,
)

/** One action pill of the Limited banner card, in strip order (the
 *  desktop's `LimitedAction`). Pure derivation input for [limitedActions]. */
enum class LimitedAction {
    /** Arm the durable continuation (a reset still ahead, nothing armed). */
    RESUME_AT_RESET,

    /** Cancel the armed continuation. */
    CANCEL_RESUME,

    /** Send the continue prompt now. */
    RESUME,

    /** Open the "Resume on another account" picker. */
    SWITCH_ACCOUNT,

    /** Re-ask for the candidate list after a failed load. */
    RETRY_ACCOUNTS,
}

/** How long a loaded candidate list stays fresh while the banner shows
 *  (the desktop's `FAILOVER_LIST_TTL`, the web's `FAILOVER_LIST_TTL_MS`). */
const val FAILOVER_LIST_TTL_MS: Long = 30_000L

/** The harnesses whose logins switch — "Resume on another account" is
 *  offered for these only (ticket 09). Pure. */
fun harnessOffersFailover(harness: String): Boolean = harness == "claude-code" || harness == "codex"

/** The picker's title for `harness`. Pure. */
fun failoverCardTitle(harness: String): String =
    if (harness == "codex") "Resume on another Codex account" else "Resume on another Claude account"

/** The shared relative countdown behind both Limited banner texts (the
 *  proto's `relative_reset_text`, mirrored exactly: the minutes bucket
 *  truncates — 30 s reads "0m" — hours floor-divide with a zero-padded
 *  rest, days floor-divide the hours, and a deadline already due reads
 *  "now", never a countdown into the past). Pure. */
internal fun relativeResetText(verb: String, at: Long, now: Long): String {
    val untilSecs = (at - now) / 1000L
    if (untilSecs <= 0L) return "$verb now"
    val minutes = untilSecs / 60L
    if (minutes < 60L) return "$verb in ${minutes}m"
    val hours = minutes / 60L
    val rest = minutes % 60L
    if (hours < 24L) {
        return if (rest == 0L) "$verb in ${hours}h" else "$verb in ${hours}h ${"%02d".format(rest)}m"
    }
    return "$verb in ${hours / 24L}d"
}

/** Banner text for a Limited chat's reset time: "resets in 45m". Pure. */
fun formatLimitReset(resetsAt: Long, now: Long): String = relativeResetText("resets", resetsAt, now)

/** Banner text for a chat's armed Resume-at-reset continuation: "resumes
 *  in 45m". Pure. */
fun formatScheduledResume(fireAt: Long, now: Long): String = relativeResetText("resumes", fireAt, now)

/**
 * The Limited banner's label — the window and its reset ("5-hour limit ·
 * resets in 45m"), or the armed continuation's fire time instead
 * ("5-hour limit · resumes in 45m"). An unknown reset says so. Pure.
 */
fun limitedBannerLabel(limited: LimitedChrome, nowMs: Long): String {
    val fireAt = limited.fireAtMs
    if (fireAt != null) {
        return "${limited.window} limit · ${formatScheduledResume(fireAt, nowMs)}"
    }
    val reset = limited.resetAtMs?.let { formatLimitReset(it, nowMs) } ?: "reset time unknown"
    return "${limited.window} limit · $reset"
}

/**
 * The card's action pills, in strip order (the desktop's
 * `limited_strip_actions`): Resume at reset while a reset is still ahead
 * and nothing is armed; Cancel once one is armed; Resume always; Resume
 * on another account only for the switchable harnesses when the list
 * offers rows; Retry after a failed load. Pure.
 */
fun limitedActions(limited: LimitedChrome, nowMs: Long): List<LimitedAction> {
    val actions = mutableListOf<LimitedAction>()
    if (limited.fireAtMs == null && limited.resetAtMs != null && limited.resetAtMs > nowMs) {
        actions += LimitedAction.RESUME_AT_RESET
    }
    if (limited.fireAtMs != null) {
        actions += LimitedAction.CANCEL_RESUME
    }
    actions += LimitedAction.RESUME
    if (limited.switchAvailable) {
        // The pill stays while a switch runs (the desktop keeps it and
        // greys the rows); the card no-ops its tap until the switch lands.
        actions += LimitedAction.SWITCH_ACCOUNT
    }
    if (limited.retry) {
        actions += LimitedAction.RETRY_ACCOUNTS
    }
    return actions
}

/**
 * The strip line for a failover outcome (the desktop's
 * `failover_outcome_notice`): `Switched` names the account the chat
 * resumed on, `NoCandidates` says why nothing else was offered, `Failed`
 * surfaces the reason. Pure.
 */
fun outcomeNotice(outcome: ResumeSwitchOutcome): String = when (outcome) {
    is ResumeSwitchOutcome.Switched ->
        "Resumed on ${outcome.accountEmail ?: "another account"} — continuing where the chat left off."
    ResumeSwitchOutcome.NoCandidates -> "No other account is available right now."
    is ResumeSwitchOutcome.Failed -> outcome.reason
}

/**
 * The reset caption under one candidate row (the desktop's
 * `candidate_reset_caption`): "session resets in 45m · week resets in
 * 2h 05m" — one entry per shown window (the first two) that knows its
 * reset. Pure.
 */
fun candidateResetCaption(account: AgentAccount, nowMs: Long): String? {
    val resets = mutableListOf<String>()
    for (window in account.usageWindows.take(2)) {
        val at = window.resetsAtMs ?: continue
        resets += "${window.label.lowercase()} ${formatLimitReset(at, nowMs)}"
    }
    return resets.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** One row of the failover picker (the desktop's `FailoverRow`). Pure. */
data class FailoverRow(
    val account: AgentAccount,
    /** The first candidate — the engine's recommendation. */
    val recommended: Boolean = false,
    /** A signed-out account: listed, never a switch target. */
    val signedOut: Boolean = false,
)

/** The picker's rows: the ranked candidates (best first, the first
 *  recommended) followed by the signed-out accounts. Pure. */
fun failoverRows(list: ResumeCandidates): List<FailoverRow> {
    val rows = list.candidates.mapIndexed { ix, account ->
        FailoverRow(account = account, recommended = ix == 0)
    }.toMutableList()
    rows += list.signedOut.map { FailoverRow(account = it, signedOut = true) }
    return rows
}

/** Whether the loaded candidate list offers anything — healthy candidates,
 *  signed-out rows, or both. `false` hides the Switch pill (the desktop's
 *  `failover_list`). Pure. */
fun failoverListOffers(list: ResumeCandidates): Boolean =
    list.candidates.isNotEmpty() || list.signedOut.isNotEmpty()

/** The account's row title: email, else the display name, else the honest
 *  unknown. Pure. */
fun accountRowTitle(account: AgentAccount): String =
    account.email ?: account.displayName ?: "Unknown account"

/** One candidate row's subtitle: "Recommended · Max 20x · Session 25% ·
 *  session resets in 45m" (the recommended marker only on the first, the
 *  plan when known, the fullest window's usage, the reset caption). Pure. */
fun accountRowSubtitle(row: FailoverRow, nowMs: Long): String {
    if (row.signedOut) return "Signed out — sign in again on the desktop"
    val parts = mutableListOf<String>()
    if (row.recommended) parts += "Recommended"
    row.account.planLabel?.let { parts += it }
    row.account.usageWindows.maxByOrNull { it.usedFraction }?.let { top ->
        parts += "${top.label} ${(top.usedFraction * 100).toInt()}%"
    }
    candidateResetCaption(row.account, nowMs)?.let { parts += it }
    return parts.joinToString(" · ")
}

/**
 * The Limited banner's [ChromeFiller] (the failover port, ticket 09 +
 * 07's pills): derives [SessionChrome.limited] from the composer's limit
 * record, loads the failover candidates behind the banner (TTL 30 s, a
 * failed load remembered until Retry or the picker's re-open), and runs
 * the strip's actions — Resume (the fixed continue prompt through the
 * screen-wired send seam, so failures ride the composer's own failure
 * pill), arm/cancel the durable Resume-at-reset, and the account switch
 * with its outcome notice.
 *
 * Threading (the #609 rules): [fill] runs on the controller's IO lane
 * and touches no FFI (the snapshot is the filler's own); the loads and
 * the actions run on the filler's own IO scope; the picker menu builds
 * on main at tap time from the `@Volatile` snapshot, zero core calls.
 * The send seam ([send]) is set by the screen (like 73's
 * `openPairPage`); a `null` seam (tests, previews) leaves Resume a
 * logged no-op.
 */
class LimitedBanner(
    private val core: LimitedCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The continue prompt's one shared spelling (the FFI constant; a
     *  seam so JVM tests inject it instead of touching JNA). */
    private val continuePrompt: () -> String = { uniffi.roboco_core.resumeContinuePrompt() },
    private val logW: (String, Throwable) -> Unit = { message, error ->
        android.util.Log.w("RobocoSession", message, error)
    },
) : ChromeFiller {

    /** The screen wiring: sends the continue prompt through the composer's
     *  normal send (failures ride its failure pill; the draft survives). */
    @Volatile
    var send: (suspend (String) -> Unit)? = null

    /** The screen wiring (the app toaster): arm/cancel failures toast,
     *  like the desktop's Mutate errors — never the banner's notice line
     *  (that one belongs to the failover outcomes alone). */
    @Volatile
    var toaster: Toaster? = null

    /** The loaded candidate list (the picker's source). */
    @Volatile
    private var loaded: ResumeCandidates? = null

    @Volatile
    private var loadedAtMs: Long = 0L

    @Volatile
    private var loading: Boolean = false

    /** The last candidate load failed — the card offers Retry; fill does
     *  not re-fetch (that would spin the RPC every refresh). */
    @Volatile
    private var failed: Boolean = false

    @Volatile
    private var switching: Boolean = false

    @Volatile
    private var notice: String? = null

    @Volatile
    private var chatId: String = ""

    /** The limited chat's harness as of the last fill (the load gate). */
    @Volatile
    private var currentHarness: String = ""

    /** The invalidate handle [bind] stores (the controller's trigger). */
    @Volatile
    private var invalidate: () -> Unit = {}

    /** The action lane: never cancelled — an in-flight switch always lands. */
    private val scope = CoroutineScope(SupervisorJob() + io)

    override fun bind(invalidate: () -> Unit) {
        this.invalidate = invalidate
    }

    /**
     * Derives the banner from the composer's limit record and asks for
     * the candidate loads the TTL allows. No FFI: everything the chrome
     * needs is in [input] plus the filler's own snapshot.
     */
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome {
        // A route's filler serves exactly one chat; a chat id change (a
        // reused controller) drops the other chat's list and notice —
        // the desktop's chat-scoped line "never shows on another chat".
        if (input.chatId != chatId) {
            chatId = input.chatId
            loaded = null
            loadedAtMs = 0L
            failed = false
            notice = null
        }
        val limit = input.composer.limit
        if (limit == null) {
            // The banner is gone (the chat resumed, or the record was
            // cleared): its notice goes with it, like the desktop's
            // chat-scoped line the chat's own state supersedes.
            notice = null
            currentHarness = ""
            return chrome.copy(limited = null)
        }
        currentHarness = limit.harness
        maybeLoad(limit.harness)
        val list = loaded
        return chrome.copy(
            limited = LimitedChrome(
                window = limit.window,
                resetAtMs = limit.resetsAtMs,
                fireAtMs = input.composer.scheduledResumeFireAtMs,
                switchAvailable = list != null && failoverListOffers(list) &&
                    harnessOffersFailover(limit.harness),
                retry = failed,
                switching = switching,
                notice = notice,
            ),
        )
    }

    /**
     * The load policy: a limited banner on a switchable harness with no
     * list, or one older than the TTL (usage meters move as turns run);
     * never while a load is in flight or after a failure (the Retry pill
     * or the picker's re-open asks again). `@Synchronized` closes the
     * check-then-act between the controller's IO lane (`fill`) and main
     * (`refreshForPicker`) — one launch, never two.
     */
    @Synchronized
    private fun maybeLoad(harness: String) {
        val chat = chatId
        if (chat.isEmpty() || loading || failed) return
        // The switchable-harness gate up front: an unsupported harness
        // (the desktop's `switchable`) never asks at all.
        if (!harnessOffersFailover(harness)) return
        val now = nowMs()
        val list = loaded
        if (list != null && now - loadedAtMs < FAILOVER_LIST_TTL_MS) return
        loading = true
        scope.launch {
            try {
                val answer = core.candidates(chat)
                loaded = answer
                loadedAtMs = nowMs()
                failed = false
            } catch (cancelled: CancellationException) {
                loading = false
                throw cancelled
            } catch (t: Throwable) {
                // A failed load is remembered and the stale list drops
                // (the desktop clears `failover.loaded` on Err): the card
                // offers Retry and the plain Resume stays; no error spam
                // (like iOS's `try?`).
                loaded = null
                loadedAtMs = 0L
                failed = true
                logW("failover candidates failed to load", t)
            } finally {
                loading = false
            }
            invalidate()
        }
    }

    /**
     * The picker just opened or the Retry pill was tapped: forget a
     * failure and re-rank — usage meters move as turns run, so every
     * open asks once more (the desktop's `toggle_failover` /
     * `retry_failover`, one body). Main thread; the load itself runs on
     * [io].
     */
    fun refreshForPicker() {
        if (failed) failed = false
        loadedAtMs = 0L
        maybeLoad(currentHarness)
        invalidate()
    }

    /**
     * The card's Switch pill's menu (main thread, tap time): the ranked
     * candidates (tap to switch; the first recommended), then the
     * signed-out rows as informational lines, and the harness-wide
     * warning footer. `null` without a list (still loading or failed) —
     * the pill does not offer the menu then. Zero core calls.
     */
    fun pickerMenu(): Menu? {
        val list = loaded ?: return null
        if (!failoverListOffers(list)) return null
        val now = nowMs()
        val rows = failoverRows(list)
        return Menu(
            title = failoverCardTitle(list.harness),
            children = buildList {
                add(
                    MenuSection(
                        title = null,
                        inline = true,
                        children = rows.map { row ->
                            MenuItem(
                                title = accountRowTitle(row.account),
                                subtitle = accountRowSubtitle(row, now),
                                informational = row.signedOut,
                                disabled = row.signedOut || switching,
                                testTag = if (row.signedOut) {
                                    "limited-picker-signed-out-${row.account.id}"
                                } else {
                                    "limited-picker-account-${row.account.id}"
                                },
                            ) { if (!row.signedOut) switch(row.account.id) }
                        },
                    ),
                )
                add(
                    MenuItem(
                        title = SWITCHES_EVERY_CHAT_WARNING,
                        informational = true,
                        testTag = "limited-picker-warning",
                    ) {},
                )
            },
        )
    }

    /** The strip's actions (the card's pill taps). Main thread. */
    fun act(action: LimitedAction) {
        when (action) {
            LimitedAction.RESUME_AT_RESET -> arm()
            LimitedAction.CANCEL_RESUME -> cancel()
            LimitedAction.RESUME -> resume()
            // Retry re-asks for the candidate list (the desktop's
            // `retry_failover`); the Switch pill opens the picker in the
            // card itself.
            LimitedAction.RETRY_ACCOUNTS -> refreshForPicker()
            LimitedAction.SWITCH_ACCOUNT -> Unit
        }
    }

    /** **Resume at reset**: arm the durable continuation; the row's
     *  `scheduledResume` flips the card's Cancel pill in on the next
     *  refresh. */
    private fun arm() {
        val chat = chatId
        if (chat.isEmpty()) return
        scope.launch {
            try {
                core.armResumeAtReset(chat)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // The desktop toasts Mutate errors; the banner's notice
                // line belongs to the failover outcomes alone.
                toaster?.show("Couldn't schedule the resume: ${errorText(t)}")
                logW("scheduleResumeAtReset failed", t)
            }
            invalidate()
        }
    }

    /** Cancel the armed continuation (the row drops it on the next refresh). */
    private fun cancel() {
        val chat = chatId
        if (chat.isEmpty()) return
        scope.launch {
            try {
                core.cancelScheduledResume(chat)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                toaster?.show("Couldn't cancel the resume: ${errorText(t)}")
                logW("cancelScheduledResume failed", t)
            }
            invalidate()
        }
    }

    /** **Resume now**: the fixed continue prompt through the screen's send
     *  seam (the composer's normal send — the failure pill and the draft
     *  survive); the prompt's one shared spelling comes from the core. */
    fun resume() {
        val chat = chatId
        val seam = send
        if (chat.isEmpty() || seam == null) return
        scope.launch {
            try {
                seam(continuePrompt())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // The composer's own reporting already owns send failures;
                // this is the seam itself refusing (a closed route).
                logW("resume send failed", t)
            }
        }
    }

    /**
     * **Resume on another account**: the switch runs engine-side (probe,
     * activate, retire the warm runtime, queue the continuation); the
     * outcome lands as the notice line. A `null` id takes the engine's
     * recommendation. Guarded by [switching].
     */
    fun switch(accountId: String?) {
        val chat = chatId
        if (chat.isEmpty() || switching) return
        switching = true
        failed = false
        notice = null
        invalidate()
        scope.launch {
            try {
                val outcome = core.switch(chat, accountId)
                notice = outcomeNotice(outcome)
                // The ranks change with a switch: the next open re-loads.
                loaded = null
                loadedAtMs = 0L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                notice = "Could not switch account: ${errorText(t)}"
                logW("resumeOnAnotherAccount failed", t)
            } finally {
                switching = false
            }
            invalidate()
        }
    }
}

/** The picker's footer warning (the web's spec story 53 copy). */
const val SWITCHES_EVERY_CHAT_WARNING =
    "Switching applies to every chat on this harness, not just this one."

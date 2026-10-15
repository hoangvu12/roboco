package sh.roboco.android.accounts

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import uniffi.roboco_core.AgentAccount
import uniffi.roboco_core.AgentAccountsSnapshot
import uniffi.roboco_core.AgentUsageWindow
import uniffi.roboco_core.CoreException

/**
 * How agent accounts read everywhere they appear (the composer's usage
 * chip, the Settings section, the Accounts & Usage page) — the Android
 * port of PR #578's `ProviderUsage.swift` with the web's copy of the
 * same rules (`web/…/lib/accounts.ts`): order, names, the most-used
 * window, reset wording, thresholds. Pure Kotlin, plain-JVM tested; the
 * thresholds mirror the core's `usage_level` (desktop thresholds, amber
 * ≥ 80 %, red ≥ 95 %) so every surface classifies identically.
 */

// ── usage levels (the desktop thresholds) ──────────────────────────────────

/** Amber at 80 % of a window (`crates/ui/src/settings/accounts.rs`). */
const val USAGE_WARN_FRACTION = 0.80f

/** Red at 95 % of a window. */
const val USAGE_CRITICAL_FRACTION = 0.95f

/** How full a usage window reads. Pure classification, like the core's. */
enum class UsageLevel { Normal, Warning, Critical }

/**
 * Threshold classification of a usage fraction — the Kotlin mirror of
 * the core's `usage_level` (the one rule every surface shares; the FFI
 * export exists for the record, the pure Kotlin copy keeps the
 * derivations JVM-testable).
 */
fun usageLevelOf(usedFraction: Float): UsageLevel = when {
    usedFraction >= USAGE_CRITICAL_FRACTION -> UsageLevel.Critical
    usedFraction >= USAGE_WARN_FRACTION -> UsageLevel.Warning
    else -> UsageLevel.Normal
}

/** The window's fraction clamped to 0…1 (the FFI already clamps; belt). */
fun clampedFraction(usedFraction: Float): Float = usedFraction.coerceIn(0f, 1f)

/** `87%` — rounded half away from zero, like iOS's `(x*100).rounded()`. */
fun usagePercent(usedFraction: Float): String = "${(clampedFraction(usedFraction) * 100).roundToInt()}%"

// ── provider order (the desktop's) ─────────────────────────────────────────

/** The provider cards' order (web `PROVIDERS`, PR #578's `order`). */
val PROVIDER_ORDER = listOf(
    "claude-code",
    "codex",
    "cursor",
    "antigravity",
    "grok",
    "devin",
    "opencode",
    "pi",
    "hermes",
)

/** Unknown harnesses sort last, alphabetically (`rank == order.size`). */
fun providerRank(harness: String): Int = PROVIDER_ORDER.indexOf(harness).let {
    if (it < 0) PROVIDER_ORDER.size else it
}

// ── the grouping ───────────────────────────────────────────────────────────

/** One harness's rows plus its warnings, in display order. */
data class ProviderGroup(
    val harness: String,
    val accounts: List<AgentAccount>,
    val warnings: List<String>,
)

/**
 * Accounts grouped per provider in desktop order (unknown harnesses last,
 * then alphabetical); within a group the active login first, the rest by
 * title. Harnesses that carry only warnings still show (their footer).
 */
fun providerGroups(snapshot: AgentAccountsSnapshot): List<ProviderGroup> {
    val byHarness = snapshot.accounts.groupBy { it.harness }
    val harnesses = (byHarness.keys + snapshot.warnings.map { it.harness }).distinct()
    return harnesses
        .sortedWith(compareBy({ providerRank(it) }, { it }))
        .map { harness ->
            val accounts = (byHarness[harness] ?: emptyList()).sortedWith(
                compareByDescending<AgentAccount> { it.active }.thenBy { accountTitle(it).lowercase(Locale.ROOT) },
            )
            ProviderGroup(
                harness = harness,
                accounts = accounts,
                warnings = snapshot.warnings.filter { it.harness == harness }.map { it.message },
            )
        }
}

/** Accounts of one harness, engine order (no active-first re-sort). */
fun providerAccountsOf(snapshot: AgentAccountsSnapshot, harness: String): List<AgentAccount> =
    snapshot.accounts.filter { it.harness == harness }

/** The harness's live login, when the host said. */
fun activeAccountOf(snapshot: AgentAccountsSnapshot, harness: String): AgentAccount? =
    snapshot.accounts.firstOrNull { it.harness == harness && it.active }

/**
 * The other logins of the active account's single-choice group (same
 * harness, same `provider`), for the chip menu's switch rows.
 */
fun otherAccountsOf(snapshot: AgentAccountsSnapshot, active: AgentAccount): List<AgentAccount> =
    snapshot.accounts.filter {
        it.harness == active.harness && it.provider == active.provider && !it.active
    }

// ── the row copy ───────────────────────────────────────────────────────────

/** The row's primary label: email, else display name, else the fallback. */
fun accountTitle(a: AgentAccount): String = a.email ?: a.displayName ?: "Unknown account"

/**
 * `Plan · organization` — the organization dropped when it just repeats
 * the email (PR #578's rule), `null` when neither is set.
 */
fun accountDetail(a: AgentAccount): String? {
    val parts = buildList {
        a.planLabel?.let { add(it) }
        a.organization?.takeIf { org -> a.email?.let { !org.startsWith(it) } ?: true }?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The window closest to its limit (the binding one). */
fun topWindowOf(a: AgentAccount): AgentUsageWindow? =
    a.usageWindows.maxByOrNull { clampedFraction(it.usedFraction) }

/**
 * Why an account shows no meters (`ProviderUsage.missingUsage`): the
 * probe's own error, else the API-key note, else "Usage unavailable".
 */
fun missingUsage(a: AgentAccount): String? = when {
    a.usageError != null -> a.usageError
    a.usageWindows.isEmpty() -> if (a.apiKey) "API keys have no plan usage" else "Usage unavailable"
    else -> null
}

// ── the reset wording (PR #578's `resetText`) ──────────────────────────────

/**
 * The written-out reset moment:
 * - ≤ 1 min left (past included): `Resets now`;
 * - under a day: `Resets in 2h 13m` (minutes only under an hour, at most
 *   two units);
 * - under a week: `Resets Mon 9:00 AM`;
 * - else: `Resets Sep 14` (a weekday is noise when a Codex free-tier
 *   MONTHLY reset lands weeks out).
 *
 * `null` when the provider does not say.
 */
fun resetText(
    resetsAtMs: Long?,
    nowMs: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val at = resetsAtMs ?: return null
    val left = at - nowMs
    if (left <= 60_000) return "Resets now"
    if (left < 24 * 3_600_000) {
        val hours = left / 3_600_000
        val minutes = (left % 3_600_000) / 60_000
        val inText = if (hours == 0L) {
            "${minutes}m"
        } else {
            val hourText = "${hours}h"
            if (minutes == 0L) hourText else "$hourText ${minutes}m"
        }
        return "Resets in $inText"
    }
    val zoned = Instant.ofEpochMilli(at).atZone(zone)
    return if (left < 7 * 24 * 3_600_000) {
        "Resets " + zoned.format(DateTimeFormatter.ofPattern("EEE h:mm a", locale))
    } else {
        "Resets " + zoned.format(DateTimeFormatter.ofPattern("MMM d", locale))
    }
}

/**
 * `"Usage updated just now"` / `"Usage updated 4 minutes ago"` for the
 * newest probe behind the snapshot's windows (`null` when no window was
 * ever probed).
 */
fun updatedText(snapshot: AgentAccountsSnapshot, nowMs: Long): String? {
    val at = snapshot.accounts.mapNotNull { it.usageFetchedAtMs }.maxOrNull() ?: return null
    val age = nowMs - at
    return when {
        age < 60_000 -> "Usage updated just now"
        age < 60 * 60_000 -> "Usage updated ${age / 60_000} minutes ago"
        age < 24 * 60 * 60_000 -> "Usage updated ${age / 3_600_000} hours ago"
        else -> "Usage updated ${age / 86_400_000} days ago"
    }
}

// ── the optimistic switch (web `markSwitched`) ─────────────────────────────

/**
 * The optimistic half of a switch: `account` becomes the live login of
 * its group — its agent, or for agents that keep a login per model
 * provider, that provider — and every other group keeps its own. The
 * engine's reply replaces the list; a refusal restores the previous one.
 */
fun markSwitched(snapshot: AgentAccountsSnapshot, account: AgentAccount): AgentAccountsSnapshot =
    snapshot.copy(
        accounts = snapshot.accounts.map { row ->
            if (row.harness == account.harness && row.provider == account.provider) {
                row.copy(active = row.id == account.id)
            } else {
                row
            }
        },
    )

// ── the error table (PR #578's `describe`) ─────────────────────────────────

/**
 * Why accounts could not load from `deviceName` — the page's failed state
 * and the alert copy. An **unsupported** engine (too old for the
 * accounts RPCs) says so; the section and the chip hide entirely for it.
 */
fun describeAccountsError(error: Throwable, deviceName: String): String = when (error) {
    is CoreException.HostUnavailable -> "$deviceName is offline. Accounts show up when it's back."
    is CoreException.Unsupported -> "$deviceName needs a newer Roboco to show accounts here."
    else -> "Couldn't load accounts from $deviceName."
}

/** Whether `error` means "this engine predates the accounts RPCs". */
fun isUnsupportedEngine(error: Throwable): Boolean = error is CoreException.Unsupported

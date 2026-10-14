package sh.roboco.android.sessions

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import sh.roboco.android.core.WorkspaceFeed
import uniffi.roboco_core.Connectivity
import uniffi.roboco_core.ConnectivityState
import uniffi.roboco_core.EngineState
import uniffi.roboco_core.EngineView
import uniffi.roboco_core.ParkReason

/**
 * The 4 s show-slow / hide-fast degradation grace (android-ui-parity #50)
 * — the Kotlin port of upstream's `ConnectivityTracker`
 * (`upstream/main:crates/client/src/connectivity.rs:5-14,70-84,110`):
 * a source must be RAW-degraded for [graceMs] continuously before it
 * reports degraded (sub-second blips — a cold-start dial, a
 * pull-to-refresh redial, a Doze wake — never flash the UI), while
 * recovery reports instantly (hide-fast, show-slow). Sources keep
 * independent timers, exactly like the Rust keys.
 *
 * Pure and not thread-safe: [EngineHealthModel] is its only mutator, and
 * only from one `transformLatest` block at a time.
 */
class DegradeGrace(val graceMs: Long = GRACE_MS) {

    /** `degraded_since`: key → the epoch of the first raw-degraded sample. */
    private val since = HashMap<String, Long>()

    /**
     * `graced()` (`connectivity.rs:70-84`): true once [key] has been
     * raw-degraded continuously for ≥ [graceMs]. Recovery (the first
     * non-degraded sample) removes the key and returns false at once.
     */
    fun graced(key: String, rawDegraded: Boolean, nowMs: Long): Boolean {
        if (!rawDegraded) {
            since.remove(key)
            return false
        }
        val s = since.getOrPut(key) { nowMs }
        return nowMs - s >= graceMs
    }

    /** Drops keys not in [live] — `compute`'s `retain` (`connectivity.rs:110`). */
    fun retain(live: Set<String>) {
        since.keys.retainAll(live)
    }

    /**
     * The earliest `since + graceMs` among keys still inside their grace
     * window at [nowMs], or null — [EngineHealthModel] re-runs `compute`
     * at that time even if no event arrives (the line appears 4 s after
     * the last event with no new event).
     */
    fun nextDeadline(nowMs: Long): Long? =
        since.values.map { it + graceMs }.filter { it > nowMs }.minOrNull()

    /** Forgets every timer (the `Disabled` posture). */
    fun clear() = since.clear()

    companion object {
        /** `DEGRADE_GRACE_MS` (`connectivity.rs:5`). */
        const val GRACE_MS = 4_000L
    }
}

/** The glyph of one connection-line entry: the ticket-11 kinds, by role. */
enum class ConnectionGlyph { Spinner, DangerDot, WarningDot }

/** One line: the 12 dp glyph (null = the overflow "+N more" row) and its caption. */
@Immutable
data class ConnectionLineEntry(val glyph: ConnectionGlyph?, val caption: String)

/** The whole item: one line per degraded source, never empty when shown. */
@Immutable
data class ConnectionLineUi(val entries: List<ConnectionLineEntry>)

/**
 * What ticket 50's consumers read: the connection line (null = healthy,
 * emit nothing) and the **graced** offline engine ids (the `home:*`
 * headers' wifi-off rule — never the raw `engineOnline`, which blinks).
 */
@Immutable
data class EngineHealthUi(val line: ConnectionLineUi?, val offlineEngineIds: Set<String>) {
    companion object {
        /** No line, no offline engines: every engine `Connected` (or none paired). */
        val Healthy = EngineHealthUi(null, emptySet())
    }
}

/** One `compute` pass: the UI to draw and when to look again. */
data class EngineHealthResult(val ui: EngineHealthUi, val nextCheckAtMs: Long?)

/**
 * The multi-engine offline indication's pure model (android-ui-parity #50)
 * — the Roboco stand-in for upstream's never-shipped "offline banner on
 * the session list" (upstream #570), because Roboco pairs N engines and
 * one of them can be down while the others work.
 *
 * The labels are the web's, verbatim
 * (`web/packages/app/src/components/connection-state.tsx:13-43`); the
 * nothing-while-healthy rule, the bare glyph + quiet caption with no
 * surface and the "no Attempt N / no transport error" rules are the web
 * pill's and the desktop `render_connection_pill`'s
 * (`crates/ui/src/shell.rs:7110-7163`).
 */
object EngineHealth {

    /** Lines per item before the "+N more" overflow entry. */
    const val MAX_LINES = 3

    /** Space, U+2014 EM DASH, space — the multi-engine name prefix. */
    const val SEPARATOR = " — "

    /** The single device-offline line (`connection-state.tsx` / spec W9; Android refuses offline writes, so no "sends are saved"). */
    const val OFFLINE = "Offline"

    /** `connection-state.tsx:15,19`. */
    const val CONNECTING = "Connecting…"

    /** `connection-state.tsx:23-30`. */
    const val RECONNECTING = "Reconnecting…"

    /** `connection-state.tsx:35-36`. */
    const val ENGINE_CHANGED = "Engine changed"

    /** `connection-state.tsx:35-36`. */
    const val SESSION_REVOKED = "Session revoked"

    /** Fallback for `Parked(INCOMPATIBLE)` with a blank `lastError` (new copy; 06 always fills it). */
    const val UPDATE_REQUIRED = "Update required"

    /** The overflow entry, e.g. `+2 more`. */
    fun more(n: Int) = "+$n more"

    /**
     * Steps 1–6 of the Spec. Mutates [grace] (the tracker is the only
     * state); the inputs are otherwise pure, so the label table, the
     * overflow and the offline-engine rules are testable without a
     * device.
     */
    fun compute(
        engines: List<EngineView>,
        connectivity: Connectivity?,
        nowMs: Long,
        grace: DegradeGrace,
    ): EngineHealthResult {
        // 1. Disabled: no engines paired (or the aggregate says so) —
        // no line, no offline engines, and the tracker forgets everything
        // so a later pairing starts its grace from zero.
        if (engines.isEmpty() || connectivity?.state == ConnectivityState.DISABLED) {
            grace.clear()
            return EngineHealthResult(EngineHealthUi.Healthy, null)
        }

        // 2. Raw state per source: `"os"` iff the OS reports no network
        // (a not-yet-read `null` counts as not offline), `"engine:<id>"`
        // iff the engine is anything but `Connected`.
        val osRaw = connectivity?.state == ConnectivityState.OFFLINE
        val engineKeys = engines.map { engineKey(it.engineId) }

        // 3. The grace pass, then the retain that drops sources that no
        // longer exist (`connectivity.rs:110`).
        val osDegraded = grace.graced(OS_KEY, osRaw, nowMs)
        val engineDegraded = engines.map { grace.graced(engineKey(it.engineId), it.state !is EngineState.Connected, nowMs) }
        grace.retain((setOf(OS_KEY) + engineKeys).toSet())

        // 4. Offline engine ids (the wifi-off glyph): while the device is
        // offline every non-Connected engine reads offline; otherwise
        // only the graced-degraded ones.
        val offlineEngineIds: Set<String> = if (osDegraded) {
            engines.filter { it.state !is EngineState.Connected }.mapTo(HashSet()) { it.engineId }
        } else {
            engines.filterIndexed { i, _ -> engineDegraded[i] }.mapTo(HashSet()) { it.engineId }
        }

        // 5. The line: one `Offline` entry while the device is offline
        // (parked engines fold into it — their reason stays on their
        // Settings row); one entry per graced-degraded engine in pairing
        // order otherwise, capped at MAX_LINES with a "+N more" overflow.
        val entries: List<ConnectionLineEntry> = when {
            osDegraded -> listOf(ConnectionLineEntry(ConnectionGlyph.WarningDot, OFFLINE))
            engineDegraded.none { it } -> emptyList()
            else -> {
                val degraded = engines.filterIndexed { i, _ -> engineDegraded[i] }
                val lines = degraded.take(MAX_LINES).map { e ->
                    val (glyph, label) = entryFor(e)
                    ConnectionLineEntry(glyph, caption(e.name, label, engines.size))
                }
                if (degraded.size > MAX_LINES) {
                    lines + ConnectionLineEntry(null, more(degraded.size - MAX_LINES))
                } else {
                    lines
                }
            }
        }

        // 6. The next look: the earliest deadline still inside its grace
        // window, or null (nothing more will change without an event).
        val nextCheckAtMs = grace.nextDeadline(nowMs)
        val ui = if (entries.isEmpty()) EngineHealthUi(null, offlineEngineIds) else EngineHealthUi(ConnectionLineUi(entries), offlineEngineIds)
        return EngineHealthResult(ui, nextCheckAtMs)
    }

    /**
     * The glyph + label for one non-Connected engine (the Spec's table):
     * the web's labels verbatim, except `Offline` (android-app#20 W9)
     * and the `INCOMPATIBLE` sentence, which 06 fills into `lastError`
     * beginning with the device's own name.
     */
    fun entryFor(e: EngineView): Pair<ConnectionGlyph, String> = when (val s = e.state) {
        is EngineState.Connecting -> ConnectionGlyph.Spinner to CONNECTING
        is EngineState.Reconnecting -> ConnectionGlyph.Spinner to RECONNECTING
        is EngineState.Offline -> ConnectionGlyph.WarningDot to OFFLINE
        is EngineState.Parked -> ConnectionGlyph.DangerDot to when (s.reason) {
            ParkReason.IDENTITY_CHANGED -> ENGINE_CHANGED
            ParkReason.REVOKED -> SESSION_REVOKED
            ParkReason.INCOMPATIBLE ->
                e.lastError?.trim()?.takeIf { it.isNotEmpty() } ?: UPDATE_REQUIRED
        }
        is EngineState.Connected -> error("Connected engines have no entry")
    }

    /**
     * The caption rule: the label alone for one paired engine (the web
     * pill's shape); `"<name> — <label>"` with ≥2 — except when the label
     * already begins with the name (the `INCOMPATIBLE` sentences), so the
     * name never repeats.
     */
    fun caption(name: String, label: String, pairedCount: Int): String =
        if (pairedCount <= 1 || label.startsWith(name)) label else name + SEPARATOR + label

    /** The raw sources' key for the OS posture. */
    private const val OS_KEY = "os"

    /** The raw sources' key for one engine. */
    private fun engineKey(engineId: String) = "engine:$engineId"
}

/**
 * The live owner of [EngineHealthUi] (android-ui-parity #50): combines
 * the engines list and the connectivity posture, re-runs
 * [EngineHealth.compute] on every change **and** at the grace deadline,
 * and publishes the distinct results. No FFI of its own —
 * [WorkspaceFeed] owns every call.
 *
 * Taking plain flows (plus the [WorkspaceFeed] convenience constructor)
 * lets the debug gallery and the tests drive it with
 * `MutableStateFlow`s; ticket 40's `FakeWorkspaceCore` lives in test
 * sources and is not visible from the debug source set.
 */
class EngineHealthModel(
    engines: Flow<List<EngineView>>,
    connectivity: Flow<Connectivity?>,
    scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    /** Production wiring: ticket 40's feed (the engines + the new connectivity flow). */
    constructor(feed: WorkspaceFeed, scope: CoroutineScope) : this(feed.engines, feed.connectivity, scope)

    private val grace = DegradeGrace()

    /**
     * `transformLatest` cancels **and joins** the previous block before
     * running the next, so [grace] is never touched concurrently — and
     * only the block itself touches it, always before the `delay`. The
     * re-check after the `delay` re-reads the **same** inputs: that is
     * how the line appears 4 s after the last event with no new event.
     * `SystemClock.elapsedRealtime()` is monotonic and keeps counting
     * through Doze, so a grace that started before Doze completes on
     * wake.
     */
    val ui: StateFlow<EngineHealthUi> =
        combine(engines, connectivity) { e, c -> e to c }
            .transformLatest { (engines, conn) ->
                while (true) {
                    val now = clock()
                    val r = EngineHealth.compute(engines, conn, now, grace)
                    emit(r.ui)
                    val next = r.nextCheckAtMs ?: break
                    delay((next - now).coerceAtLeast(1L))
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, EngineHealthUi.Healthy)
}

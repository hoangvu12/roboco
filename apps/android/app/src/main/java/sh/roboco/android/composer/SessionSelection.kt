package sh.roboco.android.composer

import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.SessionRow

/**
 * The model / reasoning selection a chip menu resolves against
 * (android-ui-parity #89) — the port of upstream PR #664's
 * `selection(for:)` (`pr664:Session/CoreSessionSource.swift`):
 * a local config write reaches the core before AppModel's workspace
 * event, so the chat's **`sessionConfig` wins over the row**; with no
 * config, the row decides, and `row.harness ?? "claude-code"`.
 *
 * Ticket 78's chip titles do **not** use this (they read the row's
 * `modelLabel` / `reasoning`, which android-app#08's optimistic override
 * flips at once). This file is the one config-first rule; a later ticket
 * that needs it imports it here, never a second copy.
 */
data class SessionSelection(
    val harness: String,
    val model: String?,
    val reasoning: String?,
)

/** iOS `row.harness ?? "claude-code"` — the fallback harness everywhere in this ticket. */
const val DEFAULT_HARNESS = "claude-code"

/**
 * The #664 selection precedence. `null` only when both inputs are null
 * (no row, no config — no menu either).
 */
fun sessionSelection(config: ChatConfig?, row: SessionRow?): SessionSelection? = when {
    config != null -> SessionSelection(config.harness, config.model, config.reasoning)
    row != null -> SessionSelection(row.harness ?: DEFAULT_HARNESS, row.model, row.reasoning)
    else -> null
}

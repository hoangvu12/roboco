package sh.roboco.android.session

import sh.roboco.android.composer.ComposerChip
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.SessionRow

/**
 * Composer-facing state of one open session (android-ui-parity #51) —
 * the port of `ios:Session/SessionSource.swift:5-55`. The transcript
 * itself flows Rust→Rust into the layout engine and never crosses FFI
 * as rows; this is everything the header (52), composer (54/78),
 * status pill (73), queue panel (82) and question panel (90/91)
 * render.
 *
 * Value equality drives recomposition: the controller publishes through
 * a `StateFlow`, which drops values equal to the current one, so **the
 * data-class `==` is what decides whether anything re-renders**. Every
 * field is therefore a value type (strings, booleans, lists of data
 * classes) — no `ImageBitmap`, `Painter`, `Drawable`, lambdas or arrays
 * (identity equality), and no theme-resolved colours (the chrome must
 * not depend on light/dark).
 *
 * Fields this ticket (51) derives: [title], [subtitle], [running],
 * [canSteer], [placeholder], [uploadProgress], [error]. The rest are
 * declared here but filled by their owners through a `ChromeFiller`
 * (see [SessionController]): 78 chips, 82 queue, 91 questions, 73
 * banner, 79 canWrite.
 */
data class SessionChrome(
    /** `composer.title` verbatim (the core already falls back to "New session"). */
    val title: String = "",
    /** `"<project> @ <engine device>"`, or just the project (`CoreSessionSource.swift:45-46`). */
    val subtitle: String = "",
    /** `composer.live.turnRunning`. */
    val running: Boolean = false,
    /** `composer.host.capabilities.midTurnSteering ?: false`. */
    val canSteer: Boolean = false,
    /** Roboco ADD (79): `false` while the owning engine is offline — read-only mode. `true` until 79 lands. */
    val canWrite: Boolean = true,
    /** `"Message <harness label>"` (`CoreSessionSource.swift:49`). */
    val placeholder: String = "Message",
    /** Ticket 78 (type owned by 54, reconciled by 59). */
    val chips: List<ComposerChip> = emptyList(),
    /** Ticket 73. */
    val banner: Banner = Banner.None,
    /** Ticket 91 (`composer.openInput`). */
    val questions: Questions? = null,
    /** Ticket 82 (`composer.queue`). */
    val queue: List<QueuedItem> = emptyList(),
    /** `composer.transferProgress` (0…1), drawn as a ring on pending thumbnails (67). */
    val uploadProgress: Double? = null,
    /** `composer.queueError`. Derived for iOS parity but never rendered (decision COMPOSER-D2). */
    val error: String? = null,
    /** The Limited banner (the failover port): non-null while the chat's
     *  status row reads Limited — errored with a usage-limit record. The
     *  card rides the bottom stack above the pill row. */
    val limited: LimitedChrome? = null,
) {
    /**
     * Roboco's banner set (android-app#22 §Status pill) instead of iOS's
     * `offline` / `reconnecting(in:)`. Ticket 73 owns precedence, copy
     * and taps and may refine the payloads — nothing in this ticket
     * depends on the variants beyond value equality.
     */
    sealed interface Banner {
        data object None : Banner

        /**
         * Ported for completeness; never driven (working shows at the
         * transcript tail, `CoreSessionSource.swift:89`).
         */
        data class Working(val sinceMs: Long?, val word: String) : Banner

        data object Sending : Banner

        data object NotDelivered : Banner

        data class Failed(val message: String) : Banner

        data object Editing : Banner

        /** Shape used by ticket 73's status pill. */
        data class EngineParked(val reason: ParkedReason, val engineId: String) : Banner

        data object EngineOff : Banner

        data object Reconnecting : Banner
    }

    /** Mirrors android-app#06's `ParkReason` (SCREAMING_SNAKE over FFI, CamelCase here). */
    enum class ParkedReason { Revoked, IdentityChanged, Incompatible }

    /** iOS `SessionChrome.Question` (`SessionSource.swift:17-26`), field for field. */
    data class Question(
        val id: String,
        val header: String,
        val text: String,
        val options: List<String>,
        val multiSelect: Boolean,
        val prefill: String? = null,
        val multiline: Boolean = false,
    )

    /**
     * iOS carries `questions` as a tuple `(requestId, items)`, so Swift
     * cannot synthesise `==`; the hand-written `==` compares it by
     * `requestId` **and** `items` (`SessionSource.swift:52`). A data
     * class over exactly those two fields is that rule.
     */
    data class Questions(val requestId: String, val items: List<Question>)

    /**
     * iOS `QueuedItem` (`SessionSource.swift:38-43`). iOS's
     * `thumbnail: UIImage?` is always `nil` for live sessions
     * (`CoreSessionSource.swift:101`), so the Kotlin type carries a
     * reference instead of an image (82 fills it if it ever needs to).
     */
    data class QueuedItem(
        val id: String,
        val text: String,
        val gate: String?,
        val thumbnailRef: String? = null,
    )
}

/**
 * This ticket's fill — `CoreSessionSource.refresh` lines 42-49, 88 and
 * 103, verbatim:
 * - `project` = `row?.project?.name ?: "No project"`;
 * - `subtitle` = `"<project> @ <device>"` when the host has a name
 *   (one space, `@`, one space; an empty but non-null name still yields
 *   `"<project> @ "`), else just the project;
 * - `placeholder` = `"Message " + (row?.harnessLabel ?: "the agent")`;
 * - `running` / `canSteer` / `uploadProgress` / `error` straight from
 *   the composer state.
 *
 * Pure; unit-tested against the ticket's worked-examples table.
 */
internal fun baseChrome(c: ComposerState, row: SessionRow?): SessionChrome {
    val project = row?.project?.name ?: "No project"
    return SessionChrome(
        title = c.title,
        subtitle = c.host.name?.let { "$project @ $it" } ?: project,
        running = c.live.turnRunning,
        canSteer = c.host.capabilities.midTurnSteering ?: false,
        placeholder = "Message ${row?.harnessLabel ?: "the agent"}",
        uploadProgress = c.transferProgress,
        error = c.queueError,
    )
}

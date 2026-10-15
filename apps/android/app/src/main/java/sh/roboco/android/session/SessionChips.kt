package sh.roboco.android.session

import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import uniffi.roboco_core.ComposerState
import uniffi.roboco_core.PullRequestState
import uniffi.roboco_core.SessionRow
import java.util.Locale

/**
 * The composer's context chips (android-ui-parity #78) — the port of
 * `ios:Session/CoreSessionSource.swift:50-74`: a fixed row of `model`,
 * `effort`, `pr` **or** `branch`, then `context`, built from the cached
 * workspace row and the composer state on every chrome refresh.
 *
 * The chip ids (see [SessionChipIds]) are the contract with ticket 59's
 * strip, ticket 89's menus and the test tags `composer-chip-<id>`.
 */
object SessionChipIds {
    /** `row.modelLabel ?? row.harnessLabel` — the model / harness chip. */
    const val Model = "model"

    /** `row.reasoning` — the reasoning-effort chip. */
    const val Effort = "effort"

    /** `row.pullRequest` — replaces the branch chip while a PR is open. */
    const val Pr = "pr"

    /** `row.branch` — shown only when there is no PR. */
    const val Branch = "branch"

    /** `composer.contextUsage` — shown whenever usage is reported (android-next #01). */
    const val Context = "context"
}

/**
 * Plain-Kotlin view of what the chips need (keeps [sessionChips]
 * JVM-testable): the row fields the iOS `refresh()` reads plus the
 * composer's context usage. Built from the live records by [from].
 */
data class ChipSource(
    /** `SessionRow.harness` — the wire harness id, or null. */
    val harness: String?,
    /** `SessionRow.harnessLabel`. */
    val harnessLabel: String?,
    /** `SessionRow.modelLabel`. */
    val modelLabel: String?,
    /** `SessionRow.reasoning` — the wire effort, or null. */
    val reasoning: String?,
    /** `SessionRow.branch`. */
    val branch: String?,
    /** `SessionRow.pullRequest?.number`. */
    val prNumber: ULong?,
    /** `OPEN`→[ChipTint.PrOpen], `MERGED`→[ChipTint.PrMerged], `CLOSED`→[ChipTint.PrClosed]. */
    val prTint: ChipTint?,
    /** `ComposerState.contextUsage?.tokens`. */
    val tokens: ULong?,
    /** `ComposerState.contextUsage?.window`. */
    val window: ULong?,
) {
    companion object {
        /**
         * Maps the FFI records into the plain view. `prTint` is null
         * exactly when `prNumber` is; the `when` is exhaustive over the
         * generated `PullRequestState`.
         */
        fun from(row: SessionRow?, composer: ComposerState): ChipSource {
            val pr = row?.pullRequest
            return ChipSource(
                harness = row?.harness,
                harnessLabel = row?.harnessLabel,
                modelLabel = row?.modelLabel,
                reasoning = row?.reasoning,
                branch = row?.branch,
                prNumber = pr?.number,
                prTint = pr?.let { p ->
                    when (p.state) {
                        PullRequestState.OPEN -> ChipTint.PrOpen
                        PullRequestState.MERGED -> ChipTint.PrMerged
                        PullRequestState.CLOSED -> ChipTint.PrClosed
                    }
                },
                tokens = composer.contextUsage?.tokens,
                window = composer.contextUsage?.window,
            )
        }
    }
}

/**
 * The chip derivation, verbatim from `CoreSessionSource.swift:50-74`
 * (see the ticket's spec table). Pure; [reasoningLabel] is injected so
 * plain-JVM tests pass a Kotlin lambda (the FFI free function is the
 * production default, injected by [SessionChipsFiller]).
 *
 * Rules kept exactly:
 * - **model** — `modelLabel` else `harnessLabel`; the brand mark of
 *   `harness ?: "claude-code"`, and **no icon when the harness string is
 *   empty** (`BrandMarks.image(for:)` returns nil, `BrandMarks.swift:380-381`);
 * - **effort** — only when `reasoning` is non-null and non-empty;
 * - **pr** — the bare number (no `#`), the PR-state tint, the 12 dp PR
 *   glyph; a PR **replaces** the branch chip;
 * - **branch** — only without a PR, and only when non-empty;
 * - **context** — android-next #01 supersedes iOS's `fraction >= 0.5`
 *   gate: [contextReading] / [contextChip] show it at any fraction (0 %
 *   included), `N tokens` without a window, and hide it only with no
 *   token count. The percentage rounds **half away from zero**, the
 *   warning symbol and tint come from the **unrounded** `fraction >= 0.85`
 *   (`845 / 1000` shows `85% context` calm), and fractions above 1 are not
 *   clamped (`120% context`). [locale] groups the a11y label's numbers.
 */
fun sessionChips(
    s: ChipSource,
    locale: Locale = Locale.getDefault(),
    reasoningLabel: (String) -> String,
): List<ComposerChip> = buildList {
    (s.modelLabel ?: s.harnessLabel)?.let { title ->
        val h = s.harness ?: "claude-code"
        add(
            ComposerChip(
                id = SessionChipIds.Model,
                title = title,
                icon = if (h.isEmpty()) null else ChipIcon.Brand(h),
            ),
        )
    }
    s.reasoning?.takeIf { it.isNotEmpty() }?.let { level ->
        add(
            ComposerChip(
                id = SessionChipIds.Effort,
                title = reasoningLabel(level),
                symbol = "gauge.with.dots.needle.67percent",
            ),
        )
    }
    if (s.prNumber != null) {
        add(
            ComposerChip(
                id = SessionChipIds.Pr,
                title = s.prNumber.toString(),
                tint = s.prTint,
                icon = ChipIcon.PullRequest,
            ),
        )
    } else {
        s.branch?.takeIf { it.isNotEmpty() }?.let { branch ->
            add(
                ComposerChip(
                    id = SessionChipIds.Branch,
                    title = branch,
                    icon = ChipIcon.Branch,
                ),
            )
        }
    }
    // android-next #01: always shown once usage is reported (no 50 % gate).
    contextReading(s.tokens, s.window)?.let { add(contextChip(it, locale)) }
}

/**
 * Ticket 51's [ChromeFiller] for [SessionChrome.chips]: runs on the
 * controller's IO lane during every refresh, right after 79's `canWrite`
 * (slot 20 in [ChromeFillerRegistry]). The FFI free function
 * `reasoningLabel` is pure and stateless — the one accepted exception to
 * 51's "fillers make no FFI" rule — and injected so tests pass a Kotlin
 * lambda.
 *
 * No state, no [bind]: two refreshes with the same inputs produce equal
 * chromes, so the controller's `StateFlow` drops the second (equality is
 * what drives recomposition).
 */
class SessionChipsFiller(
    private val reasoningLabel: (String) -> String = { uniffi.roboco_core.reasoningLabel(it) },
    /** Read per refresh, so a locale change regroups the context label on the next chrome. */
    private val locale: () -> Locale = { Locale.getDefault() },
) : ChromeFiller {
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome =
        chrome.copy(chips = sessionChips(ChipSource.from(input.row, input.composer), locale(), reasoningLabel))
}

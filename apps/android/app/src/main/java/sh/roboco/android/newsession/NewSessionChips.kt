package sh.roboco.android.newsession

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import sh.roboco.android.composer.ChipIcon
import sh.roboco.android.composer.ChipMenuProvider
import sh.roboco.android.composer.ChipTint
import sh.roboco.android.composer.ComposerChip
import sh.roboco.android.composer.ComposerController

/**
 * The New Session page's chip ids (android-ui-parity #109) — the contract
 * with ticket 59's strip, tickets 113/114's menus and the
 * `composer-chip-<id>` test tags the UI tests read. [All] is the strip
 * order, and the key order of [newSessionChipMenus]'s result.
 */
object NewSessionChipIds {

    /** The picked checkout's repository group name (the [ChipIcon.Project] tile). */
    const val Project = "project"

    /** The engine the session runs on — always present, project or not. */
    const val Host = "host"

    /** The branch / worktree pick — only with a git project. */
    const val Branch = "branch"

    /** The model pick (a model label, never the harness name). */
    const val Model = "model"

    /** The reasoning effort — only for a model with a non-empty ladder. */
    const val Effort = "effort"

    /** The five ids, in strip order. */
    val All = listOf(Project, Host, Branch, Model, Effort)
}

/**
 * The three core label helpers the model chip's title chain calls
 * (`mob:client_ffi/mod.rs:572-603`, the free functions android-app#07
 * exports and #10 binds). A seam so the plain-JVM tests fake them: the
 * generated free functions need the native library, the records do not.
 *
 * All three are static, in-memory and fast (`modelLabel` takes a read
 * lock on the learned-label map), so [CoreChipLabels] may run on the
 * main thread — the same rule as ticket 92's `builtIn()`.
 */
interface ChipLabels {

    /** `catalog::model_label` — the static catalog label, else a label learned from a live `ListModels`, else the raw id. */
    fun modelLabel(harness: String, model: String): String

    /** `fallbackModels(harness).firstOrNull()?.label` — the built-in default row of the harness. */
    fun firstFallbackModelLabel(harness: String): String?

    /** `catalog::harness_label` — the harness catalog label (the title chain's last resort). */
    fun harnessLabel(harness: String): String
}

/** The production [ChipLabels] over the core's static catalog functions. */
object CoreChipLabels : ChipLabels {
    override fun modelLabel(harness: String, model: String): String =
        uniffi.roboco_core.modelLabel(harness, model)

    override fun firstFallbackModelLabel(harness: String): String? =
        uniffi.roboco_core.fallbackModels(harness).firstOrNull()?.label

    override fun harnessLabel(harness: String): String =
        uniffi.roboco_core.harnessLabel(harness)
}

/**
 * iOS `refreshChips()` (`ios:Session/NewSessionViewController.swift:247-278`),
 * as a pure function over ticket 92's page state (the ticket's Spec table):
 * `project` (group name + tile) · `host` (device name, warning-tinted while
 * its engine is offline) · `branch` (only for a git project: "New worktree"
 * / the branch / "Current branch") · `model` (always) · `effort` (only for
 * a model with a non-empty ladder).
 *
 * Rules kept exactly from the Swift:
 * - the project title and the tile letter are the **group** name, never the
 *   checkout's `name`;
 * - the host chip is never dropped when a project is picked (upstream PR
 *   #609's trap), and its title is the checkout's device display name;
 * - without a project there is never a branch chip, the project chip reads
 *   `No project` with the `tray` symbol, and the host chip reads the host
 *   matching `draft.hostId`, else `Choose host`;
 * - the model title is the lazy chain `selected.label` → (only with a
 *   `draft.model`) `modelLabel` → `firstFallbackModelLabel` →
 *   `harnessLabel`, so a harness that lists models names the **first**
 *   listed model even when the draft holds an unlisted id;
 * - the brand icon is dropped when `draft.harness` is empty
 *   (`BrandMarks.image(for:)` returns nil, ticket 78's rule);
 * - `draft.effort` shows as-is even outside the ladder, and the default is
 *   `efforts[count / 2]` — integer division, no `defaultReasoning` step;
 * - the effort title goes through [swiftCapitalized] (Foundation's
 *   `capitalized`: `Xhigh`, not the core's `X-High`).
 *
 * The Roboco addition: the `host` chip's [ChipTint.Warning] while the
 * resolved engine is offline (`!project.online`, or the hostless pick's
 * `!host.online`) — Roboco web's `DeviceChip` rule, carried over by
 * android-app#25. Only the host chip is tinted, and it stays tappable (94
 * keeps the strip live while it gates Send).
 */
fun newSessionChips(
    draft: NewSessionDraft,
    project: ProjectOption?,
    hosts: List<HostOption>,
    models: List<ModelChoice>,
    labels: ChipLabels,
): List<ComposerChip> {
    val chips = ArrayList<ComposerChip>(5)
    if (project != null) {
        // The project, then the host it runs on.
        chips += ComposerChip(
            id = NewSessionChipIds.Project,
            title = project.groupName,
            icon = ChipIcon.Project(project.groupName, project.colorIndex),
        )
        chips += ComposerChip(
            id = NewSessionChipIds.Host,
            title = project.deviceName,
            symbol = "desktopcomputer",
            tint = if (project.online) null else ChipTint.Warning,
        )
        if (project.git) {
            chips += ComposerChip(
                id = NewSessionChipIds.Branch,
                title = if (draft.worktree) "New worktree" else draft.branch ?: "Current branch",
                icon = ChipIcon.Branch,
            )
        }
    } else {
        // No project: no branch chip, ever.
        val host = hosts.firstOrNull { it.id == draft.hostId }
        chips += ComposerChip(id = NewSessionChipIds.Project, title = "No project", symbol = "tray")
        chips += ComposerChip(
            id = NewSessionChipIds.Host,
            title = host?.name ?: "Choose host",
            symbol = "desktopcomputer",
            tint = if (host != null && !host.online) ChipTint.Warning else null,
        )
    }
    // The model: the draft's pick within its harness, else the harness's
    // first listed model — the same lookup as ticket 92's selectedModel.
    val selected = models.firstOrNull { it.harness == draft.harness && it.id == draft.model }
        ?: models.firstOrNull { it.harness == draft.harness }
    // Never the harness name in place of a model: a model this host hasn't
    // listed still gets its catalog label.
    val modelTitle = selected?.label
        ?: draft.model?.let { labels.modelLabel(draft.harness, it) }
        ?: labels.firstFallbackModelLabel(draft.harness)
        ?: labels.harnessLabel(draft.harness)
    chips += ComposerChip(
        id = NewSessionChipIds.Model,
        title = modelTitle,
        icon = draft.harness.takeIf { it.isNotEmpty() }?.let { ChipIcon.Brand(it) },
    )
    val efforts = selected?.efforts.orEmpty()
    if (efforts.isNotEmpty()) {
        chips += ComposerChip(
            id = NewSessionChipIds.Effort,
            title = (draft.effort ?: efforts[efforts.size / 2]).swiftCapitalized(),
            symbol = "gauge.with.dots.needle.67percent",
        )
    }
    return chips
}

/** The chips a side chat's draft keeps (iOS `refreshChips` with `sideOf`). */
private val SideChatChipIds = setOf(NewSessionChipIds.Model, NewSessionChipIds.Effort)

/**
 * Foundation's `String.capitalized` (`CFStringCapitalize`), which the effort
 * titles (here and in ticket 114's effort rows) use: a letter is
 * title-cased when the last non-case-ignorable character before it was
 * **not** a cased letter (string start, whitespace, digit, `-`, …), and
 * lower-cased otherwise; case-ignorable characters (`'`, `.`, `:`, `·`,
 * `’`, marks, formats, modifier symbols) leave that state unchanged.
 * Locale-independent — `titlecaseChar`/`lowercaseChar`, never
 * `String.capitalize(Locale)` or an `uppercase(Locale)`.
 *
 * `low` → `Low`, `HIGH` → `High`, `xhigh` → `Xhigh`, `x-high` → `X-High`,
 * `1st` → `1St`, `don't` → `Don't`.
 */
fun String.swiftCapitalized(): String {
    val out = StringBuilder(length)
    var lastCased = false
    for (ch in this) {
        when {
            ch.isLetter() -> {
                out.append(if (lastCased) ch.lowercaseChar() else ch.titlecaseChar())
                lastCased = true
            }
            ch.isCaseIgnorable() -> out.append(ch) // the state survives
            else -> {
                out.append(ch)
                lastCased = false
            }
        }
    }
    return out.toString()
}

/**
 * The provider for an id no part supplies: no menu, so the tap falls
 * through to [ComposerController.onChipTap] (93's no-op).
 */
private val NoChipMenu: ChipMenuProvider = { null }

/**
 * The composer's `chipMenus` map for the New Session page — the union of
 * the menu maps tickets 113 (`ProjectRunOnMenus.providers()`: `project`,
 * `host`) and 114 (`branch`, `model`, `effort`) build, merged into the
 * fixed five-key map 59's strip looks ids up in.
 *
 * Always exactly the five ids of [NewSessionChipIds.All], in that order;
 * keys outside the five are dropped, and a later part overrides an
 * earlier one for the same id. With no parts every id maps to `{ null }`,
 * and taps fall through to 93's no-op `onChipTap`.
 *
 * 93 calls this once, `remember`ed (its provider lambdas read the live
 * model at tap time, so the map itself is stable); 113 step 8 and 114
 * step 4 each add their provider map as one more argument of that same
 * call, giving `newSessionChipMenus(projectMenus.providers(), checkoutMenus.providers())`.
 */
fun newSessionChipMenus(vararg parts: Map<String, ChipMenuProvider>): Map<String, ChipMenuProvider> {
    val merged = HashMap<String, ChipMenuProvider>()
    for (part in parts) merged.putAll(part)
    return NewSessionChipIds.All.associateWith { id -> merged[id] ?: NoChipMenu }
}

/**
 * Keeps [ComposerController.chips] and [ComposerController.chipMenus] in
 * sync with [model] — the port of iOS's `refreshChips()` *call sites*
 * (`:182,194,197,205,306,315,337,356,365,374,390,401`: after the
 * defaults, after every catalog load, after every pick). Here the chips
 * recompute whenever the snapshot inputs change: the page's [chips]
 * derive from `draft` / `currentOptions` / `models`, the strip is
 * re-rendered only when the list actually differs (`List` equality of the
 * [ComposerChip] data classes), and nothing animates.
 *
 * The seed `remember` writes the chips **during composition**, so the
 * very first frame already has them (iOS sets them in `viewDidLoad`) —
 * ticket 93 calls this **before** its composer composable, so the write
 * happens before anything reads `composer.chips` in the same composition
 * (no backwards write). `composer.onChipTap` and `composer.canWrite` are
 * never touched: 93 and 94 own them.
 *
 * The [labels] default runs the core's static catalog functions on the
 * main thread — allowed, like ticket 92's `builtIn()` — and only on the
 * fallback path of the title chain, inside `derivedStateOf`, which
 * re-evaluates only when an input changes.
 */
@Composable
fun NewSessionChipsEffect(
    model: NewSessionModel,
    composer: ComposerController,
    menus: Map<String, ChipMenuProvider>,
    labels: ChipLabels = CoreChipLabels,
) {
    val chips by remember(model, labels) {
        derivedStateOf {
            val all = newSessionChips(
                model.draft,
                model.project,
                model.currentOptions?.hosts.orEmpty(),
                model.models,
                labels,
            )
            // android-next #06: a side chat runs in its parent's checkout
            // on its parent's host — only the model and effort are picked.
            if (model.side == null) all else all.filter { it.id in SideChatChipIds }
        }
    }
    // Seed synchronously so the first composition already has the chips.
    remember(model, composer) {
        composer.chips = chips
        Unit
    }
    LaunchedEffect(composer, menus) {
        composer.chipMenus = menus
    }
    LaunchedEffect(model, composer, labels) {
        snapshotFlow { chips }.collect { if (it != composer.chips) composer.chips = it }
    }
}

/**
 * CF's case-ignorable set: the punctuation effort ids actually contain
 * (`'`, `.`, `:`, `·`, `’`, `^`, backtick) plus the Unicode categories
 * `CFStringCapitalize` consults (non-spacing and enclosing marks,
 * formats, modifier symbols). These characters copy through and leave
 * the last-cased state unchanged.
 */
private fun Char.isCaseIgnorable(): Boolean =
    this == '\'' || this == '.' || this == ':' || this == '·' || this == '’' ||
        this == '^' || this == '`' ||
        Character.getType(this) in CaseIgnorableTypes

private val CaseIgnorableTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.FORMAT.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
)

package sh.roboco.android.newsession

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.roboco.android.composer.ChipMenuProvider
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.design.glass.MenuSection
import uniffi.roboco_core.CoreClientInterface
import uniffi.roboco_core.RepoRef

/**
 * Ref names for a project, the current branch first (android-ui-parity
 * #114) — the seam behind the Checkout menu's `Branch` section, so the
 * plain-JVM tests fake the engine and the gallery delays its answer.
 * Call off the main thread; never throws except cancellation.
 */
fun interface RefSource {
    /** The project's ref names, current first, engine order otherwise. */
    suspend fun refs(project: ProjectOption): List<String>
}

/**
 * The live [RefSource] — the port of iOS `AppModel.refs(projectId:)`
 * (`ios:App/AppModel.swift:612-616`): the call goes to the **project's
 * own engine** (`CoreClient.listRefs(deviceId, repoPath)`, android-app#09)
 * with the project folder as the repo path, any error reads as an empty
 * list (the Swift `try?`) and only the names are kept, the current branch
 * sorted first (Swift's stable `sorted { $0.current && !$1.current }`,
 * which [CoreRefSource.refs] mirrors with the equally stable
 * [sortedByDescending][List.sortedByDescending] — android-app#09 already
 * returns the current ref first, so the sort is idempotent).
 *
 * The FFI call is a **lambda seam**, not a `CoreClient` reference: the
 * generated `CoreClientInterface` grows with every mobile ticket, and a
 * plain-JVM fake of it would break on each new member. The production
 * constructor wraps [client] into the seam; the tests (and only they)
 * use the primary constructor directly.
 */
class CoreRefSource(
    private val listRefs: suspend (String, String) -> List<RepoRef>,
) : RefSource {

    /**
     * The live client's refs: `client() ?: emptyList()` — no client (the
     * engine is not ready yet) is exactly iOS's `guard let client`, and
     * the elvis means the engine is never asked.
     */
    constructor(client: () -> CoreClientInterface?) : this(
        listRefs = { deviceId, repoPath ->
            client()?.listRefs(deviceId, repoPath) ?: emptyList()
        },
    )

    override suspend fun refs(project: ProjectOption): List<String> {
        val refs = try {
            listRefs(project.device, project.path)
        } catch (cancelled: CancellationException) {
            throw cancelled // ticket 15 cancels the load when the menu closes
        } catch (failure: Throwable) {
            return emptyList() // iOS `try?` → no rows, no "No branches"
        }
        return refs.sortedByDescending { it.current }.map { it.name }
    }
}

/**
 * Menu content for the New Session chips `branch`, `model` and `effort`
 * (android-ui-parity #114) — the port of `NewSessionViewController`'s
 * `branchMenu()`, `modelMenu()` and `effortMenu()`
 * (`ios:Session/NewSessionViewController.swift:361-404`). This class owns
 * **the content and the actions only**: ticket 15 draws the panel, ticket
 * 59 anchors it to the chip at tap time, ticket 109 decides which chips
 * exist, ticket 92 owns the draft.
 *
 * Main thread only. Every provider reads the live page state at **tap
 * time** through the constructor lambdas; every pick goes through
 * [update], which applies the transform to the draft **at pick time**
 * (never a captured copy — this is what makes the worktree toggle
 * correct) and persists it (ticket 92's adaptation). Because the draft
 * is Compose state, the chips (109) and the hero mark (93) redraw in the
 * same frame without any explicit refresh — the port of iOS's
 * `refreshChips()` *call sites*, not its body.
 *
 * The branch list's cache lifetime matches iOS ("cached until the next
 * chip refresh"): the `UIDeferredMenuElement` instance is hoisted under
 * [BranchKey] — the draft plus the project's device and path — and kept
 * while the key is unchanged, so reopening Checkout without any pick in
 * between shows the branches at once, while any pick (in any of the five
 * menus) or a device change makes the next open load again.
 */
class CheckoutModelEffortMenus(
    /** `NewSessionModel.draft` — read at build time and at pick time. */
    private val draft: () -> NewSessionDraft,
    /** `NewSessionModel.project` — no project, no branch rows. */
    private val project: () -> ProjectOption?,
    /** `NewSessionModel.models` — the page's current catalog. */
    private val models: () -> List<ModelChoice>,
    /** `NewSessionModel.selectedModel` — the effort ladder's owner. */
    private val selectedModel: () -> ModelChoice?,
    /** `NewSessionModel.update` — the only mutation path (ticket 92). */
    private val update: ((NewSessionDraft) -> NewSessionDraft) -> Unit,
    private val refSource: RefSource,
    /** The FFI hop of the branch load; never touched by the other two menus. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** The hoisted branch deferred plus its key. Read and written on main only. */
    private var branchHoist: Pair<BranchKey, MenuElement.Deferred>? = null

    /**
     * The `branch` chip's menu (iOS `branchMenu()`, `:361-380`): title
     * `Checkout`, the "New worktree" toggle row (checked with
     * `draft.worktree`, no icon, no subtitle — upstream #593), then the
     * inline `Branch` section with the **cached** deferred. Synchronous —
     * only the deferred load suspends, and ticket 15 runs it.
     */
    fun checkout(): Menu {
        val d = draft()
        val p = project()
        val key = BranchKey(d, p?.id, p?.device, p?.path)
        val deferred = branchHoist?.takeIf { it.first == key }?.second
            ?: MenuElement.Deferred(cached = true) { loadBranches() }.also { branchHoist = key to it }
        return checkoutMenu(d.worktree, deferred) { update { it.copy(worktree = !it.worktree) } }
    }

    /**
     * The branch rows (iOS `AppModel.refs` + the deferred element's body):
     * reads `project()` on main (`guard let p = self.project` — no
     * project, no rows, and the ref source is never called), hops to
     * [io] **only** for the FFI call, and builds the rows back on main
     * with the draft **as it is when the refs arrive** — the checkmark is
     * `draft.branch ?? refs.first`, read after the load, exactly the
     * Swift.
     */
    private suspend fun loadBranches(): List<MenuElement> {
        val p = project() ?: return emptyList()
        val names = withContext(io) { refSource.refs(p) }
        return branchRows(names, draft().branch) { ref -> update { it.copy(branch = ref) } }
    }

    /**
     * The `model` chip's menu (iOS `modelMenu()`, `:382-394`): title
     * `Model`, one inline section per harness, sections sorted by harness
     * id, rows in catalog order, the check on `draft.harness` +
     * `draft.model` only. A pick sets the harness and the model and
     * clears the effort, so the effort chip falls back to the new model's
     * middle level (109).
     */
    fun model(): Menu =
        modelMenu(models(), draft()) { m -> update { it.copy(harness = m.harness, model = m.id, effort = null) } }

    /**
     * The `effort` chip's menu (iOS `effortMenu()`, `:396-404`): title
     * `Reasoning effort`, the resolved model's ladder through
     * [effortTitle], the check on `draft.effort` only — nothing checked
     * while nothing is picked (the chip still shows the middle level).
     */
    fun effort(): Menu =
        effortMenu(selectedModel(), draft().effort) { e -> update { it.copy(effort = e) } }

    /**
     * This ticket's share of the `composer.chipMenus` map — the same
     * shape as ticket 113's `ProjectRunOnMenus.providers()`, handed to
     * [newSessionChipMenus] as one more `vararg` part (keys `branch`,
     * `model`, `effort`; the two other ids stay 113's).
     */
    fun providers(): Map<String, ChipMenuProvider> = mapOf(
        NewSessionChipIds.Branch to { checkout() },
        NewSessionChipIds.Model to { model() },
        NewSessionChipIds.Effort to { effort() },
    )
}

/**
 * The branch deferred's cache key: the whole draft (any pick, in any of
 * the five menus, changes it) plus the project's device and path (the
 * refs' source). The provider map is rebuilt on every chip refresh, so a
 * key equal to the hoisted one means no pick and no project change
 * happened since — the instance survives, exactly a cached
 * `UIDeferredMenuElement`'s lifetime.
 */
private data class BranchKey(
    val draft: NewSessionDraft,
    val projectId: String?,
    val device: String?,
    val path: String?,
)

/**
 * The Checkout menu (ticket 15's model): `Menu("Checkout")` with the
 * "New worktree" toggle row (no image, no subtitle — upstream #593
 * removed both) and the inline `Branch` section whose only child is the
 * [branchSection] deferred. Pure; the pure test drives [onToggleWorktree]
 * directly.
 */
internal fun checkoutMenu(
    worktree: Boolean,
    branchSection: MenuElement.Deferred,
    onToggleWorktree: () -> Unit,
): Menu = Menu(
    title = "Checkout",
    children = listOf(
        MenuItem(
            title = "New worktree",
            checked = worktree,
            testTag = "new-session-menu-worktree",
            action = onToggleWorktree,
        ),
        MenuSection(title = "Branch", inline = true, children = listOf(branchSection)),
    ),
)

/**
 * The branch rows: one `MenuItem` per ref, verbatim titles (no
 * "Checked out" subtitle, no "No branches" row), checked when the ref is
 * `branch` — or the first ref when nothing is picked — **as evaluated by
 * the caller** (after the refs arrive; the pure test pins both rows).
 */
internal fun branchRows(refs: List<String>, branch: String?, onPick: (String) -> Unit): List<MenuElement> {
    val picked = branch ?: refs.firstOrNull()
    return refs.map { ref ->
        MenuItem(
            title = ref,
            checked = ref == picked,
            testTag = "new-session-menu-branch-$ref",
        ) { onPick(ref) }
    }
}

/**
 * The Model menu (ticket 15's model): one inline section per harness,
 * sections sorted by harness id in plain string order (Swift's
 * `keys.sorted()`), each headed by the **first** model's `harnessLabel`
 * and a 16 dp brand mark, rows in the catalog's order within the
 * harness. The check is the draft's `(harness, id)` pair only — never
 * the resolved model (`pr609`'s trap) — so with no model picked no row
 * is checked, even though the chip already names one.
 */
internal fun modelMenu(
    models: List<ModelChoice>,
    draft: NewSessionDraft,
    onPick: (ModelChoice) -> Unit,
): Menu {
    val byHarness = models.groupBy { it.harness } // keeps the list order inside each group
    return Menu(
        title = "Model",
        children = byHarness.keys.sorted().map { h ->
            val list = byHarness.getValue(h)
            MenuSection(
                title = list.first().harnessLabel,
                icon = MenuIcon.Brand(h),
                inline = true,
                children = list.map { m ->
                    MenuItem(
                        title = m.label,
                        checked = m.harness == draft.harness && m.id == draft.model,
                        testTag = "new-session-menu-model-${m.harness}-${m.id}",
                    ) { onPick(m) }
                },
            )
        },
    )
}

/**
 * The Reasoning effort menu (ticket 15's model): one row per level of
 * [model]'s ladder (no rows with no model), titled through [effortTitle]
 * — **not** the core's `reasoningLabel`: the wire level `xhigh` reads
 * `Xhigh` here, while the session screen's menu (ticket 89) reads
 * `X-High`. The check is `draft.effort` only: with nothing picked, no
 * row is checked even though the chip shows the middle level.
 */
internal fun effortMenu(model: ModelChoice?, effort: String?, onPick: (String) -> Unit): Menu = Menu(
    title = "Reasoning effort",
    children = (model?.efforts ?: emptyList()).map { level ->
        MenuItem(
            title = effortTitle(level),
            checked = level == effort,
            testTag = "new-session-menu-effort-$level",
        ) { onPick(level) }
    },
)

/**
 * Effort row title = the effort chip's spelling (iOS `e.capitalized`,
 * `:399`): a one-line delegate to ticket 109's
 * [String.swiftCapitalized], so the chip and the menu can never spell a
 * level differently. `low` → `Low`, `xhigh` → `Xhigh`, `HIGH` → `High`.
 */
internal fun effortTitle(level: String): String = level.swiftCapitalized()

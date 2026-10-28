package sh.roboco.android.composer

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.roboco.android.design.RobocoIcons
import sh.roboco.android.design.glass.Menu
import sh.roboco.android.design.glass.MenuElement
import sh.roboco.android.design.glass.MenuIcon
import sh.roboco.android.design.glass.MenuItem
import sh.roboco.android.session.ChromeFiller
import sh.roboco.android.session.ChromeInput
import sh.roboco.android.session.ContextReading
import sh.roboco.android.session.CoreSessionCore
import sh.roboco.android.session.SessionChrome
import sh.roboco.android.session.SessionChipIds
import sh.roboco.android.session.SessionCore
import sh.roboco.android.session.contextReading
import sh.roboco.android.session.contextUsageCard
import uniffi.roboco_core.ChatConfig
import uniffi.roboco_core.HarnessInfo
import uniffi.roboco_core.ModelInfo
import uniffi.roboco_core.SandboxLevel
import uniffi.roboco_core.SendState
import uniffi.roboco_core.SessionRow

/**
 * The FFI seam of the chip menus (android-ui-parity #89) — the five core
 * calls `chipMenu`/`setConfig` make (`ios:Session/CoreSessionSource.swift:
 * 201-245` plus #664). Every member is FFI: call only from IO. A seam so
 * the plain-JVM tests fake it (the generated records construct without
 * JNA, the calls do not).
 */
interface ChipMenuCore {
    /** `CoreClient.sessionRow(chatId)` — the cached workspace row. */
    fun sessionRow(): SessionRow?

    /** `CoreClient.sessionConfig(chatId)` — the chat's local config (#664's selection source). */
    fun sessionConfig(): ChatConfig?

    /** `CoreClient.listModels(deviceId, harness)` — the engine's catalog. */
    suspend fun listModels(deviceId: String, harness: String): List<ModelInfo>

    /**
     * `CoreClient.listHarnesses(deviceId)` — the providers the host
     * offers (wave-10 39's other picker pages). Never throws; the
     * default (fakes that predate 39) offers none.
     */
    suspend fun listHarnesses(deviceId: String): List<HarnessInfo> = emptyList()

    /** The core's static fallback when [listModels] fails outright. */
    fun fallbackModels(harness: String): List<ModelInfo>

    /** `reasoning_label(level)` — the wire level's display label. */
    fun reasoningLabel(level: String): String

    /**
     * `CoreClient.setSessionConfig(chatId, config)` — a **full replace**
     * (android-app#08: optimistic override, `Mutate setChatConfig`,
     * revert on error). Throws the generated `CoreException` on failure.
     */
    suspend fun setSessionConfig(config: ChatConfig)
}

/**
 * The live [ChipMenuCore] over one chat: the route's [CoreSessionCore]
 * holds the client and the chat id, so a sibling adapter cast (the
 * registry hands the filler the same `SessionCore` instance).
 */
class CoreChipMenuCore(
    private val client: uniffi.roboco_core.CoreClient,
    private val chatId: String,
) : ChipMenuCore {
    override fun sessionRow(): SessionRow? = client.sessionRow(chatId)

    override fun sessionConfig(): ChatConfig? = client.sessionConfig(chatId)

    override suspend fun listModels(deviceId: String, harness: String): List<ModelInfo> =
        client.listModels(deviceId, harness)

    override suspend fun listHarnesses(deviceId: String): List<HarnessInfo> =
        client.listHarnesses(deviceId)

    override fun fallbackModels(harness: String): List<ModelInfo> =
        uniffi.roboco_core.fallbackModels(harness)

    override fun reasoningLabel(level: String): String = uniffi.roboco_core.reasoningLabel(level)

    override suspend fun setSessionConfig(config: ChatConfig) {
        client.setSessionConfig(chatId, config)
    }

    companion object {
        /** Production cores are ticket 51's [CoreSessionCore] (public `client`). */
        fun of(core: SessionCore): ChipMenuCore =
            CoreChipMenuCore((core as CoreSessionCore).client, core.chatId)
    }
}

/** What the PR menu's two rows need from the platform: open the URL, copy it. */
interface ChipMenuPlatform {
    /** A Custom Tab (ticket 126's recipe), falling back to `ACTION_VIEW`. */
    fun openUrl(url: String)

    /** `ClipboardManager` plain text, clip label "Link", no confirmation UI. */
    fun copyLink(url: String)
}

/**
 * android-next #05: the account half of the context chip's menu —
 * [augmentContextCard] appends the Account section (the login in use
 * and every usage window with percent and written-out reset moment),
 * the session harness's other logins to switch to, and "Accounts &
 * Usage…" to #01's context-window details card, or returns the card
 * unchanged without account data. Implemented by the accounts
 * package's `SessionAccountUsage`; held by [SessionChipMenus.accounts]
 * so the plain context card stays the default (tests, previews).
 */
interface ContextCardAccounts {
    /** [card] with the account rows appended, or [card] unchanged. */
    fun augmentContextCard(card: Menu): Menu
}

/** The deferred-cache key: the selection (tap time) plus the engine device. */
private data class MenuKey(
    val chipId: String,
    val harness: String,
    val model: String?,
    val reasoning: String?,
    val deviceId: String,
)

/**
 * The chip menus' content and actions (android-ui-parity #89) — the port
 * of `CoreSessionSource.chipMenu` + `setConfig` (`ios:Session/
 * CoreSessionSource.swift:201-245`, with #664's selection, pick rule,
 * failure pill and immediate refresh). This ticket owns **the content and
 * the actions only**: ticket 15 draws the menu, ticket 59 anchors it to
 * the chip at tap time, ticket 73 shows the failure pill.
 *
 * Threading (the #609 traps this ticket exists to avoid):
 * - [fill] stores a **snapshot** (row, config, host device) on the
 *   controller's IO lane and touches no FFI — everything comes from
 *   [ChromeInput];
 * - [menu] runs on **main** at tap time and makes **zero** core calls: it
 *   reads the snapshot only;
 * - the deferred loads and the writes make every core call inside
 *   `withContext(io)` / the write scope;
 * - iOS's `chipMenu` guard — no row, no menu — holds for every chip id.
 *
 * #664's cache lifetime: ticket 15 caches a `Deferred(cached = true)` per
 * **instance**, and this class keeps one instance per chip id together
 * with its [MenuKey]. Reopening with an unchanged key shows the cached
 * rows at once; a pick changes the selection, so the key changes and the
 * next open loads again — iOS's "until the chrome changes".
 */
class SessionChipMenus(
    private val core: ChipMenuCore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The context card's number grouping, read at tap time. */
    private val locale: () -> Locale = { Locale.getDefault() },
) : ChromeFiller {

    /**
     * Set by ticket 78's wiring (the session screen): `null` = the write
     * succeeded, else the failure. Ticket 73's `StatusBanner` turns it
     * into the `Couldn't change session settings: …` pill. Default no-op.
     */
    @Volatile
    var onSettingResult: (Throwable?) -> Unit = {}

    /**
     * android-next #05: the context chip's account half, set by the
     * session screen's wiring (this route's `SessionAccountUsage`).
     * `null` (tests, previews) keeps the plain context card of #01.
     */
    @Volatile
    var accounts: ContextCardAccounts? = null

    /**
     * Wave-10 38: the tabbed model picker (upstream #966). Set by the
     * session screen; with one, the model chip opens the picker instead
     * of its flat menu. `null` (tests, previews) keeps the menu.
     */
    @Volatile
    var modelPicker: ModelPickerPresenter? = null

    /** The models each picker page loaded, by harness — a pick applies its [ModelInfo]. */
    private val pickerModels = java.util.concurrent.ConcurrentHashMap<String, List<ModelInfo>>()

    /**
     * Wave-10 39 (I2): what a chat with no model of its own runs — its
     * host's default (the catalog's first model) by `device|harness`,
     * seeded from the built-in list so the chip never shows a bare agent
     * name, then replaced by the host's own list (upstream
     * `CoreSessionSource.defaultModel`).
     */
    private val defaultModels = java.util.concurrent.ConcurrentHashMap<String, ModelPickerRow>()
    private val defaultModelsLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The host's providers, read ahead once it can switch (a page must not wait on the host). */
    @Volatile
    private var hostHarnesses: Pair<String, List<HarnessInfo>>? = null

    /** The write lane: never cancelled — an in-flight write always finishes, like iOS's synchronous call. */
    private val writeScope = CoroutineScope(SupervisorJob() + io)

    /** Serialises "read base → write" so two quick picks can never interleave replaces. */
    private val writeLock = Mutex()

    private val closed = AtomicBoolean(false)

    /** The controller's refresh snapshot (row, config, deviceId) — written on IO, read on main. */
    @Volatile
    private var snapshot: Snapshot? = null

    /** The hoisted deferred instances, keyed by chip id. Main thread only. */
    private val hoisted = mutableMapOf<String, Pair<MenuKey, MenuElement.Deferred>>()

    /** The invalidate handle [bind] stores (the controller's coalescing trigger). */
    @Volatile
    private var invalidate: () -> Unit = {}

    private data class Snapshot(
        val row: SessionRow?,
        val config: ChatConfig?,
        val deviceId: String,
        /** android-next #01: the context card's numbers (`ComposerState.contextUsage`). */
        val context: ContextReading?,
        /** Wave-10 39: the host advertises `provider-switch-v1`. */
        val hostSwitches: Boolean = false,
        /** A turn is running on the host. */
        val running: Boolean = false,
        /** A send is still on its way (its turn starts on the current provider). */
        val sendPending: Boolean = false,
    ) {
        /** Not mid-turn, and no send on its way (upstream's `canSwitchProvider`). */
        val canSwitch: Boolean get() = hostSwitches && !running && !sendPending
    }

    override fun bind(invalidate: () -> Unit) {
        this.invalidate = invalidate
    }

    /**
     * Stores the snapshot and returns the chrome **unchanged** — this
     * filler fills no chrome field; the menus only decorate the chips
     * ticket 78 already filled — except a model-less chat's model chip
     * (wave-10 39), named from [defaultModel]: the built-in list's
     * first (a static FFI read), then the host's, loaded on [writeScope].
     */
    override fun fill(chrome: SessionChrome, input: ChromeInput): SessionChrome {
        val usage = input.composer.contextUsage
        val sendState = input.composer.sendState
        val next = Snapshot(
            row = input.row,
            config = input.config,
            deviceId = input.composer.host.deviceId,
            context = contextReading(usage?.tokens, usage?.window),
            hostSwitches = input.composer.host.capabilities.providerSwitch,
            running = input.composer.live.turnRunning,
            sendPending = sendState == SendState.SENDING || sendState == SendState.QUEUED,
        )
        snapshot = next
        if (next.canSwitch) readAheadHarnesses(next.deviceId)
        // Wave-10 39 (I2): a chat with no model of its own names the
        // model it runs (the host's default), never the bare agent name.
        val row = input.row ?: return chrome
        if (!row.model.isNullOrEmpty() || row.modelLabel != null) return chrome
        val byDefault = defaultModel(next.deviceId, row.harness ?: DEFAULT_HARNESS) ?: return chrome
        val chips = chrome.chips.map { chip ->
            if (chip.id == SessionChipIds.Model) chip.copy(title = byDefault.label) else chip
        }
        return if (chips == chrome.chips) chrome else chrome.copy(chips = chips)
    }

    /**
     * The default model for `device|harness`: the built-in list's first at
     * once, the host's first once its catalog answers (one load per key;
     * a changed answer refreshes the chrome).
     */
    private fun defaultModel(deviceId: String, harness: String): ModelPickerRow? {
        val key = "$deviceId|$harness"
        if (defaultModels[key] == null) {
            core.fallbackModels(harness).firstOrNull()?.let { defaultModels.putIfAbsent(key, it.pickerRow()) }
        }
        if (deviceId.isNotEmpty() && defaultModelsLoading.add(key)) {
            writeScope.launch {
                val first = try {
                    core.listModels(deviceId, harness).firstOrNull()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    null
                } ?: return@launch
                val row = first.pickerRow()
                if (defaultModels.put(key, row) != row) invalidate()
            }
        }
        return defaultModels[key]
    }

    /** Reads the host's providers once per device (again after an empty answer). */
    private fun readAheadHarnesses(deviceId: String) {
        if (hostHarnesses?.first == deviceId) return
        hostHarnesses = deviceId to emptyList()
        writeScope.launch {
            val list = try {
                core.listHarnesses(deviceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                emptyList()
            }
            // Nothing back (offline, slow): ask again on the next refresh.
            hostHarnesses = if (list.isEmpty()) null else deviceId to list
        }
    }

    /**
     * Ticket 54's `ChipMenuProvider` body (`() -> Menu?`), called on main
     * at tap time by ticket 59's strip. No FFI, no snapshot state writes:
     * it reads [snapshot] and builds the [Menu] per the ticket's table
     * (`model` / `effort` get one hoisted deferred; `pr` two rows;
     * `branch` / anything else `null`). android-next #01: `context` opens
     * the read-only details card from the composer's usage — it needs no
     * row, so it is answered before iOS's row guard — and android-next
     * #05 folds the account rows into it through [accounts]: the plain
     * card without account data.
     */
    fun menu(chipId: String, platform: ChipMenuPlatform): Menu? {
        val s = snapshot ?: return null
        if (chipId == SessionChipIds.Context) {
            val card = s.context?.let { contextUsageCard(it, locale()) } ?: return null
            return accounts?.augmentContextCard(card) ?: card
        }
        val row = s.row ?: return null // iOS: guard let row
        val sel = sessionSelection(s.config, row)
        val harness = sel?.harness ?: DEFAULT_HARNESS
        if (chipId == SessionChipIds.Model) {
            val picker = modelPicker
            if (picker != null) {
                // The picker opens instead of a menu; the strip gets no
                // menu back (its fallback tap is inert in a session).
                picker.present(modelPickerRequest(s, row, harness, sel?.model))
                return null
            }
        }
        return when (chipId) {
            SessionChipIds.Model -> Menu(
                title = MODEL_MENU_TITLE,
                children = listOf(
                    deferred(MenuKey(SessionChipIds.Model, harness, sel?.model, sel?.reasoning, s.deviceId)) {
                        modelRows(s.deviceId, harness)
                    },
                ),
            )
            SessionChipIds.Effort -> Menu(
                title = EFFORT_MENU_TITLE,
                children = listOf(
                    deferred(MenuKey(SessionChipIds.Effort, harness, sel?.model, sel?.reasoning, s.deviceId)) {
                        effortRows(s.deviceId, harness)
                    },
                ),
            )
            SessionChipIds.Pr -> {
                val pr = row.pullRequest ?: return null
                val url = prUrlOrNull(pr.url) ?: return null
                Menu(
                    title = pr.title,
                    children = listOf(
                        MenuItem(
                            title = PR_OPEN_ROW,
                            icon = MenuIcon.Symbol(RobocoIcons.Safari),
                            testTag = "chip-menu-pr-open",
                        ) { platform.openUrl(url) },
                        MenuItem(
                            title = PR_COPY_ROW,
                            icon = MenuIcon.Symbol(RobocoIcons.Link),
                            testTag = "chip-menu-pr-copy",
                        ) { platform.copyLink(url) },
                    ),
                )
            }
            else -> null
        }
    }

    /**
     * The picker for this chat (wave-10 38): the chat's provider as the
     * one page, its models from the engine (the fallback when the call
     * fails), the current one ticked; a pick of this provider's model
     * applies it with #664's rule. Main thread, tap time; no core call —
     * the page loads on IO when the sheet opens.
     */
    private fun modelPickerRequest(
        s: Snapshot,
        row: SessionRow,
        harness: String,
        model: String?,
    ): ModelPickerRequest {
        val deviceId = s.deviceId
        val own = ModelPickerTab(harness = harness, label = row.harnessLabel ?: harness)
        // Wave-10 39: the other providers the host offers, only when it can
        // switch now; until its list is in, only the chat's own.
        val others = if (s.canSwitch) {
            hostHarnesses?.takeIf { it.first == deviceId }?.second.orEmpty()
                .filter { it.offered && it.id != harness }
                .map { h -> ModelPickerTab(h.id, h.label, footer = providerSwitchFooter(h.label)) }
        } else {
            emptyList()
        }
        return ModelPickerRequest(
            tabs = listOf(own) + others,
            harness = harness,
            // No model of its own: it runs the default, so that's the one ticked.
            model = model ?: defaultModels["$deviceId|$harness"]?.id,
            hostName = row.deviceName ?: PICKER_HOST_FALLBACK,
            note = providerSwitchNote(s.canSwitch, s.running, s.hostSwitches),
            load = { h -> pickerRows(deviceId, h) },
            onPick = { h, picked ->
                if (h == harness) {
                    val info = pickerModels[h]?.firstOrNull { it.id == picked.id }
                    if (info != null) write { applyModelPick(it, info) }
                } else if (snapshot?.canSwitch == true) {
                    // Re-checked at the pick: a turn may have started since
                    // the sheet opened with other providers' pages.
                    write { applyProviderPick(it, h, picked.id) }
                }
            },
        )
    }

    /** One picker page's rows (IO), remembering the [ModelInfo]s for the pick. */
    private suspend fun pickerRows(deviceId: String, harness: String): List<ModelPickerRow> = withContext(io) {
        if (closed.get()) return@withContext emptyList()
        val models = loadModels(deviceId, harness)
        pickerModels[harness] = models
        models.map { m -> ModelPickerRow(id = m.id, label = m.label, detail = m.description?.takeIf { it.isNotBlank() }) }
    }

    /**
     * The route is discarded / closed (ticket 76's `discard()`, 85's
     * `release()`): later loads resolve empty — iOS's `guard let self else
     * { return done([]) }`. **Never cancels writes**: a write that is in
     * flight when the user pops the screen still completes.
     */
    fun dispose() {
        closed.set(true)
    }

    // ── the deferred loads (ticket 15 calls them from main) ─────────────

    /**
     * The model rows: the engine's `listModels` in its order, label +
     * non-blank description, the checkmark on the load-time selection's
     * model (#664 re-reads the config + row when the load runs). The
     * `MenuItem` action only captures values; ticket 15 runs it on main
     * after the menu closed.
     */
    private suspend fun modelRows(deviceId: String, harness: String): List<MenuElement> = withContext(io) {
        if (closed.get()) return@withContext emptyList()
        val models = loadModels(deviceId, harness)
        val selected = sessionSelection(core.sessionConfig(), core.sessionRow())?.model
        models.map { m ->
            MenuItem(
                title = m.label,
                subtitle = m.description?.takeIf { it.isNotBlank() },
                checked = m.id == selected,
                testTag = "chip-menu-model-${m.id}",
            ) { write { applyModelPick(it, m) } }
        }
    }

    /**
     * The effort rows: the levels of the model the load-time selection
     * names, else the **first** model's, else none (iOS `??` never fires
     * on an empty list). `reasoningLabel` is FFI, so the rows are built
     * here on IO.
     */
    private suspend fun effortRows(deviceId: String, harness: String): List<MenuElement> = withContext(io) {
        if (closed.get()) return@withContext emptyList()
        val models = loadModels(deviceId, harness)
        val sel = sessionSelection(core.sessionConfig(), core.sessionRow())
        val levels = models.firstOrNull { it.id == sel?.model }?.reasoningLevels
            ?: models.firstOrNull()?.reasoningLevels
            ?: emptyList()
        levels.map { level ->
            MenuItem(
                title = core.reasoningLabel(level),
                checked = level == sel?.reasoning,
                testTag = "chip-menu-effort-$level",
            ) { write { it.copy(reasoning = level) } }
        }
    }

    /**
     * `listModels` with the fallback: the core itself falls back live →
     * disk cache → static, so the Kotlin `??` only matters if the call
     * fails outright (a cancelled load rethrows — ticket 15 cancels its
     * scope when the menu closes).
     */
    private suspend fun loadModels(deviceId: String, harness: String): List<ModelInfo> =
        try {
            core.listModels(deviceId, harness)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            core.fallbackModels(harness)
        }

    // ── the writes (iOS `setConfig`, adapted: suspend + a mutex) ───────

    /**
     * One config write: read the base **at pick time** (inside the lock,
     * so a second pick reads the config the first one produced), apply
     * the change, write the **full** config (a replace — harness, sandbox
     * and untouched options survive), report the outcome and invalidate
     * (iOS `refresh()`, success or failure alike). The optimistic refresh
     * itself is android-app#08's, in the core.
     */
    private fun write(change: (ChatConfig) -> ChatConfig) {
        writeScope.launch {
            writeLock.withLock {
                val base = core.sessionConfig()
                    ?: ChatConfig(
                        harness = core.sessionRow()?.harness ?: DEFAULT_HARNESS,
                        model = null,
                        reasoning = null,
                        modelOptions = emptyMap(),
                        sandbox = SandboxLevel.WORKSPACE_WRITE,
                    )
                val result = try {
                    core.setSessionConfig(change(base))
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    t
                }
                onSettingResult(result)
                invalidate()
            }
        }
    }

    // ── the deferred hoisting (iOS's cache lifetime) ───────────────────

    /**
     * Returns the stored deferred while [key] is unchanged, else a new
     * one (and stores it). Two `menu("model")` calls with one snapshot
     * return the same instance (one load); after a pick changes the
     * selection — or the engine device changes — a new instance loads
     * again.
     */
    private fun deferred(key: MenuKey, load: suspend () -> List<MenuElement>): MenuElement.Deferred {
        val stored = hoisted[key.chipId]
        if (stored != null && stored.first == key) return stored.second
        val created = MenuElement.Deferred(cached = true, load = load)
        hoisted[key.chipId] = key to created
        return created
    }
}

// ── copy (verbatim from the ticket / iOS) ──────────────────────────────────

/** The picker's host line when the row names no computer. */
const val PICKER_HOST_FALLBACK = "This computer"

/** A model row of the picker from the catalog record. */
private fun ModelInfo.pickerRow(): ModelPickerRow =
    ModelPickerRow(id = id, label = label, detail = description?.takeIf { it.isNotBlank() })

/** Over another provider's page (upstream's footer, verbatim). */
fun providerSwitchFooter(label: String): String = "Your next message continues this chat with $label."

/**
 * The note over the chat's own page (upstream `CoreSessionSource`): none
 * when it can switch; mid-turn, when it can switch again; nothing on a
 * host that switches but is busy sending; an older host says so. Pure.
 */
fun providerSwitchNote(canSwitch: Boolean, running: Boolean, hostSwitches: Boolean): String? = when {
    canSwitch -> null
    running -> "You can switch provider when this turn ends."
    hostSwitches -> null
    else -> "This computer needs a newer Roboco to switch provider."
}

/**
 * A pick on another provider's page (upstream #966): the chat moves to
 * that provider with the picked model; the old provider's effort and
 * options don't carry over. A same-provider pick only sets the model.
 * Sandbox is never changed. Pure.
 */
fun applyProviderPick(config: ChatConfig, harness: String, modelId: String): ChatConfig =
    if (harness != config.harness) {
        config.copy(harness = harness, model = modelId, reasoning = null, modelOptions = emptyMap())
    } else {
        config.copy(model = modelId)
    }

/** The model menu's pane title. */
const val MODEL_MENU_TITLE = "Model"

/** The effort menu's pane title. */
const val EFFORT_MENU_TITLE = "Reasoning effort"

/** The PR menu's first row. */
const val PR_OPEN_ROW = "Open Pull Request"

/** The PR menu's second row. */
const val PR_COPY_ROW = "Copy Link"

/** The ⋯ menu's Account & Usage row (android-next#05). */
const val ACCOUNT_USAGE_ROW = "Account & Usage"

// ── the pure rules (unit-tested on the plain JVM) ──────────────────────────

/**
 * The #664 model-pick rule, a copy of android-app#08's `config_for_model`
 * so the menu logic is JVM-testable without the native lib (both are
 * pinned by the same 7 vectors; if one changes, change the other):
 *
 * - `model = m.id`;
 * - keep `reasoning` only when it is one of `m.reasoningLevels` (a nil
 *   counts as "not in"), else `m.defaultReasoning`, else
 *   [preferredReasoning] (high → medium → first — `catalog::
 *   default_reasoning`, never the middle index);
 * - keep only the `modelOptions` pairs whose key is one of `m.options`
 *   **and** whose value is one of that option's choices;
 * - `harness` and `sandbox` are never changed (the caller starts from the
 *   base config).
 */
fun applyModelPick(config: ChatConfig, m: ModelInfo): ChatConfig {
    // Bind first: the uniffi record fields are `var`, so `config.reasoning`
    // never smart-casts after a null check.
    val currentReasoning = config.reasoning
    val keep = currentReasoning != null && currentReasoning in m.reasoningLevels
    val reasoning = if (keep) currentReasoning else (m.defaultReasoning ?: preferredReasoning(m.reasoningLevels))
    val options = config.modelOptions.filter { (key, value) ->
        m.options.any { o -> o.id == key && o.choices.any { it.id == value } }
    }
    return config.copy(model = m.id, reasoning = reasoning, modelOptions = options)
}

/**
 * `catalog::default_reasoning`: `high` if offered, else `medium`, else
 * the first level, else `null`. Only used when the FFI's
 * `ModelInfo.defaultReasoning` is null.
 */
internal fun preferredReasoning(levels: List<String>): String? =
    listOf("high", "medium").firstOrNull { it in levels } ?: levels.firstOrNull()

/**
 * iOS `URL(string:)` narrowed to the case that matters: a PR url must be
 * non-blank **and** parse with a scheme, else the chip gets no menu (a
 * menu whose "Open Pull Request" cannot open is worse than no menu).
 */
internal fun prUrlOrNull(url: String?): String? =
    url?.takeIf { it.isNotBlank() && runCatching { java.net.URI(it) }.getOrNull()?.scheme != null }

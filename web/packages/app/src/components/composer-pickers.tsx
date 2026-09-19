import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type CSSProperties } from "react";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import type { ChatConfig, HarnessDescriptor, HarnessId, Model, ReasoningLevel } from "@roboco/proto";
import type { DraftConfig, DraftConfigUpdate } from "../lib/composer-actions";
import { catalogLoading, modelsLoading, openForceRefire, shouldReload } from "../lib/catalog-loading";
import {
  applyDraftUpdate,
  composerDefaults,
  isModelFavorite,
  rememberedLabelFor,
  rememberedModelFor,
  rememberHarness,
  rememberModel,
  rememberModelOption,
  rememberReasoning,
  toggleModelFavorite,
} from "../lib/composer-draft";
import { defaultReasoning, reasoningLabel, traitsCustomized, traitsSummary } from "../lib/traits-summary";
import { offeredHarnesses, scopedModelRows, type ModelRail } from "../lib/model-rows";
import type { PickerCatalog, LoadableList } from "../state/picker-catalog";
import { isMacPlatform } from "../state/shortcuts";
import { openChipClass } from "./ui/Chip";
import { useCursorList } from "./ui/CursorList";
import { KbdHint } from "./ui/KeyHint";
import { MenuHeading, MenuRowNav, MenuSeparator } from "./ui/MenuRows";
import { PickerCard } from "./ui/PickerCard";
import { MenuScrollbar } from "./ui/Scrollbar";
import { ErrorRow, SkeletonBar, SkeletonMenuRows } from "./ui/Skeleton";
import { GlyphSpinner } from "./glyph-spinner";

/**
 * The composer's run identity — the desktop's `Pickers::render` cluster.
 *
 * ONE chip carries the whole run identity: the harness's brand mark, the
 * model's display label, and then the joined traits summary ("High · 1M ·
 * Fast") as the chip's muted second tone. Opening the chip reveals ONE card
 * (`render_harness_model_popover`, pickers.rs:3143-3464): a tab strip
 * (favorites + one tab per offered harness), a search row, a VIRTUALIZED
 * model list with star favorites and ⌘1-9 jump chips, and a pinned traits
 * tray below. The card stays open after a model pick — model and traits
 * share one popover; Esc, click-out, or the chip close it.
 *
 * There is no sandbox facet: `SandboxLevel::WorkspaceWrite` is written when a
 * chat is created and preserved thereafter — the desktop never offers it as
 * a choice, so neither does this.
 */

/** `LIST_HEIGHT` (pickers.rs:3143) — the model list band. */
const LIST_HEIGHT = 216;
/** The virtualizer's fixed row heights (compact harness tab / two-line favorites tab). */
const ROW_HEIGHT_COMPACT = 29;
const ROW_HEIGHT_FAVORITE = 48;
/** Rows rendered beyond the viewport on either side. */
const OVERSCAN = 6;
/** The traits tray's cap (`pickers.rs:3432`). */
const TRAYS_MAX_HEIGHT = 236;
/** `MAX_REF_ROWS`-style cap is not needed here; lists are windowed. */

export interface ComposerPickersProps {
  readonly catalog: PickerCatalog;
  readonly draft: DraftConfig;
  /** The chat's persisted config — locks the harness facet once non-null. */
  readonly chatConfig: ChatConfig | null;
  /** Apply the next draft (clamped + offered-filtered by `applyDraftUpdate`). */
  readonly onDraft: (next: DraftConfig) => void;
  /** Persist the next config on an existing chat (`Mutate setChatConfig`). */
  readonly onPersist: (next: DraftConfig) => void;
  /**
   * Escape's focus return — the composer's textarea (`animate_close`,
   * pickers.rs:866): Base UI's escape-key dismissal routes it through
   * `RbPopover`'s finalFocus.
   */
  readonly escapeFocusTarget: () => HTMLElement | null;
  /**
   * Reports the card's open state to the host — the composer's pill
   * mouse-down focus defers to open menus (`pickers.is_open()`,
   * composer.rs:7701-7709).
   */
  readonly onOpenChange?: (open: boolean) => void;
}

export function ComposerPickers(props: ComposerPickersProps) {
  const { catalog, draft, chatConfig, onDraft, onPersist, escapeFocusTarget, onOpenChange } = props;
  const [open, setOpen] = useState(false);
  const setOpenAndNotify = useCallback(
    (next: boolean) => {
      setOpen(next);
      onOpenChange?.(next);
    },
    [onOpenChange],
  );

  const harnesses = useSyncExternalStore(
    useCallback((listener: () => void) => catalog.subscribe(listener), [catalog]),
    useCallback(() => catalog.getHarnesses(), [catalog]),
    useCallback(() => catalog.getHarnesses(), [catalog]),
  );
  const defaults = useSyncExternalStore(
    useCallback((listener: () => void) => composerDefaults.subscribe(listener), []),
    useCallback(() => composerDefaults.getSnapshot(), []),
    useCallback(() => composerDefaults.getSnapshot(), []),
  );

  const locked = chatConfig !== null;
  const effectiveHarness = draft.harness;
  const offered = useMemo(() => offeredHarnesses(harnesses.rows), [harnesses.rows]);
  // `rail_descriptors`: offered harnesses, the committed one force-inserted
  // at index 0 when it sits outside the offered set (pickers.rs:1567).
  const railDescriptors = useMemo(() => {
    const committed = chatConfig?.harness;
    if (committed === undefined || offered.some((descriptor) => descriptor.id === committed)) {
      return offered;
    }
    const row = harnesses.rows.find((descriptor) => descriptor.id === committed);
    return row === undefined ? offered : [row, ...offered];
  }, [chatConfig?.harness, offered, harnesses.rows]);

  const noAgents = harnesses.loaded && harnesses.error === null && offered.length === 0;

  // The catalog error shows only once the slot has actually errored — not
  // while an initial load is in flight.
  const harnessError = harnesses.error;

  const modelsLists = useCatalogModels(catalog, railDescriptors, effectiveHarness);
  const modelsList = catalog.getModels(effectiveHarness);
  const descriptor = railDescriptors.find((row) => row.id === effectiveHarness) ?? null;
  const models: readonly Model[] = modelsList.rows;

  // `selected_model`: the effective id's row if the loaded list still offers
  // it, else the first row — never null with a non-empty catalog.
  const selectedModel =
    (modelsList.loaded ? models.find((model) => model.id === draft.model) : undefined) ??
    (models.length > 0 ? models[0] : undefined);
  // `trait_ladder`: the model's ladder, falling back to the descriptor's own.
  const ladder: readonly ReasoningLevel[] = selectedModel?.reasoningLevels ?? descriptor?.reasoningLevels ?? [];

  const favorites = defaults.favorites;

  const resolveModel = useCallback(
    (harness: HarnessId, modelId: string | null): Model | null => {
      if (modelId === null) {
        return null;
      }
      return catalog.getModels(harness).rows.find((model) => model.id === modelId) ?? null;
    },
    [catalog],
  );

  const commit = useCallback(
    (update: DraftConfigUpdate) => {
      const next = applyDraftUpdate(draft, update, resolveModel);
      onDraft(next);
      if (chatConfig !== null) {
        onPersist(next);
      }
    },
    [draft, resolveModel, onDraft, onPersist, chatConfig],
  );

  function pickHarness(harness: HarnessId): void {
    if (locked) {
      // Harness is locked once the chat exists (feature-inventory A1.7).
      return;
    }
    if (draft.harness === harness) {
      return;
    }
    rememberHarness(harness);
    // The remembered model for this harness takes over via the defaults
    // fallback; a foreign pick must not linger (pickers.rs:1380-1394).
    commit({ harness, model: null, reasoning: null });
  }

  function pickModel(harness: HarnessId, model: Model): void {
    if (harness !== effectiveHarness) {
      if (locked) {
        return;
      }
      pickHarness(harness);
    }
    rememberModel(harness, model.id, model.label);
    commit({ model: model.id });
  }

  function pickReasoning(level: ReasoningLevel | null): void {
    rememberReasoning(level);
    commit({ reasoning: level });
  }

  function pickOption(model: Model, optionId: string, choiceId: string, isDefault: boolean): void {
    rememberModelOption(draft.harness, model.id, optionId, choiceId, isDefault);
    const picks: Record<string, unknown> = { ...draft.modelOptions };
    if (isDefault) {
      delete picks[optionId];
    } else {
      picks[optionId] = choiceId;
    }
    commit({ modelOptions: picks });
  }

  // — The chip ——————————————————————————————————————————————

  const brand = harnessBrandIcon(effectiveHarness);
  const modelLabel = resolveChipLabel(draft.model, selectedModel, effectiveHarness, modelsList);
  const rememberedLabel = draft.model === null ? null : rememberedLabelFor(draft.model);
  // `chip_label_loading` (pickers.rs:4220-4221): nothing names the pick yet
  // AND the catalog is Idle/Loading. An errored harness or model slot is
  // settled, not loading — the real label (remembered label → configured/
  // raw id, `model_label` at pickers.rs:4186-4206) renders and the failure
  // surfaces through the card's ErrorRow, never an eternal skeleton.
  const labelLoading =
    draft.model !== null &&
    modelLabel === draft.model &&
    rememberedLabel === null &&
    harnesses.error === null &&
    (catalogLoading(harnesses) || modelsLoading(modelsList));
  // `chip_icon_loading` (pickers.rs:4216-4217): catalog Idle/Loading only —
  // an errored catalog shows the brand mark with the resolved label.
  const iconLoading =
    catalogLoading(harnesses) && chatConfig === null && defaults.harness === null && !noAgents;
  const suffix = traitsSummary(selectedModel, draft.reasoning, draft.modelOptions);
  const suffixActive = traitsCustomized(selectedModel, draft.reasoning, ladder, draft.modelOptions);

  // Force: the enabled set moves under us (Settings → Agents, possibly from
  // another viewer) — every open revalidates, keeping current rows visible
  // until the fresh catalog lands (pickers.rs:1003-1019). The force also
  // RE-FIRES when the slot lands Error while the card stays open (ticket
  // 61, hole 1): the desktop's per-render `ensure_harnesses` cadence
  // (pickers.rs:4164-4168) never waits for an event to re-kick, and the
  // web's stand-in for that cadence is this effect re-running on a state
  // change — the slot's error arm is the one that must re-kick (an open
  // card's ErrorRow has no scheduled retry otherwise). `openForceRefire`
  // keys on that arm alone, so a warm Ready slot never re-fires (each
  // landed reload produces a fresh slot object — keying on identity would
  // loop) and a failed re-arm does not re-fire again until a new error
  // lands. The in-flight guard inside `loadHarnesses` bounds a wedged
  // load's lifetime, so the re-force can supersede it.
  // The overlaySource prop below registers the `composer-pickers` overlay
  // keyboard source while the card is open (shell.rs:3681-3683).
  const opened = open;
  const openRefire = openForceRefire(harnesses);
  useEffect(() => {
    if (opened) {
      void catalog.loadHarnesses({ force: true });
      catalog.prefetchModels(true);
    }
  }, [catalog, opened, openRefire]);

  // The desktop's per-render `ensure_harnesses(false, cx)` kick
  // (pickers.rs:4164-4168) has no per-frame web peer — port the discipline
  // instead: React re-renders on every relevant state change, and this
  // effect re-runs whenever the slot identity moves, which is every Idle
  // transition (reset, invalidate, a fresh session). A non-forced kick is
  // a no-op unless the slot is Idle (`shouldReload`'s Idle-only rule), so
  // Ready/Loading/Error slots never re-fire — and the card's skeleton
  // takeover can never sit on an Idle slot with nothing scheduled.
  useEffect(() => {
    if (shouldReload(harnesses, false)) {
      void catalog.loadHarnesses();
    }
  }, [catalog, harnesses]);

  // A failure that lands while the connection is up (the unary call
  // timeout, a mid-call teardown) has no status event to heal it — the
  // offline re-arm only covers pre-dial errors. Window focus re-arms an
  // errored, row-less slot: reset to Idle, then the non-forced kick above
  // (the Idle row of `shouldReload`) reloads it. Ready and Loading slots
  // are never touched; the in-flight guard inside `loadHarnesses` stands.
  useEffect(() => {
    const onWindowFocus = (): void => {
      const slot = catalog.getHarnesses();
      if (slot.error !== null && !slot.loaded && !slot.loading) {
        catalog.resetHarnesses();
        void catalog.loadHarnesses();
      }
    };
    window.addEventListener("focus", onWindowFocus);
    return () => {
      window.removeEventListener("focus", onWindowFocus);
    };
  }, [catalog]);

  return (
    <div className="composer-pickers">
      <PickerCard
        open={open}
        onOpenChange={setOpenAndNotify}
        placement="anchorAboveEnd"
        cardClassName="popover-card popover-card-flush identity-card"
        role="dialog"
        ariaLabel="Run identity"
        width={304}
        style={{ maxHeight: 640 }}
        overlaySource="composer-pickers"
        escapeFocusTarget={escapeFocusTarget}
        trigger={
          <button
            type="button"
            id="picker-model"
            className={openChipClass("identity-chip", open)}
          >
            {noAgents ? (
              <Icon name="terminal" size={16} className="identity-chip-brand identity-chip-brand-muted" />
            ) : iconLoading ? (
              <GlyphSpinner size={16} mono className="identity-chip-brand" />
            ) : (
              <Icon
                name={brand.name}
                size={16}
                className="identity-chip-brand"
                style={brand.tint === null ? undefined : { color: brand.tint }}
              />
            )}
            {labelLoading ? (
              <SkeletonBar width={56} />
            ) : (
              <span className="identity-chip-model">{noAgents ? "No agents available" : modelLabel}</span>
            )}
            {suffix !== null && !noAgents && (
              <span className={`identity-chip-suffix ${suffixActive ? "identity-chip-suffix-active" : ""}`}>
                {suffix}
              </span>
            )}
          </button>
        }
      >
        {/* `anchored_menu_above_end` — the card's RIGHT edge flush with the
            chip's, opening upward with a 6px gap, clamped 8px inside. */}
        <IdentityCard
          open={open}
          harnesses={harnesses}
          harnessError={harnessError}
          noAgents={noAgents}
          locked={locked}
          railDescriptors={railDescriptors}
          modelsLists={modelsLists}
          effectiveHarness={effectiveHarness}
          draft={draft}
          favorites={favorites}
          selectedModel={selectedModel}
          ladder={ladder}
          onRetryHarnesses={() => catalog.retryHarnessCatalog()}
          onRetryModels={() => {
            catalog.resetModels(effectiveHarness);
            void catalog.loadModels(effectiveHarness, { force: true });
          }}
          onPickHarness={pickHarness}
          onPickModel={pickModel}
          onPickReasoning={pickReasoning}
          onPickOption={pickOption}
          onToggleFavorite={(harness, model) => {
            toggleModelFavorite(harness, model.id, model.label);
          }}
        />
      </PickerCard>
    </div>
  );
}

/**
 * The chip's label resolution (§3.5): the catalog label when the list is
 * loaded, else the remembered label for the effective id, else the raw id —
 * never a placeholder word.
 */
function resolveChipLabel(
  modelId: string | null,
  selectedModel: Model | undefined,
  harness: HarnessId,
  modelsList: LoadableList<Model>,
): string {
  if (selectedModel !== undefined && modelsList.loaded) {
    return selectedModel.label.length > 0 ? selectedModel.label : selectedModel.id;
  }
  if (modelId !== null) {
    const remembered = rememberedModelFor(harness);
    if (remembered !== null && remembered.id === modelId && remembered.label.length > 0) {
      return remembered.label;
    }
    return rememberedLabelFor(modelId) ?? modelId;
  }
  return harness;
}

/** Subscribe to several harness model slots at once; re-reads on notify. */
function useCatalogModels(
  catalog: PickerCatalog,
  descriptors: readonly HarnessDescriptor[],
  effective: HarnessId,
): Map<HarnessId, LoadableList<Model>> {
  const ids = useMemo(() => {
    const set = new Set<HarnessId>(descriptors.map((descriptor) => descriptor.id));
    set.add(effective);
    return [...set];
  }, [descriptors, effective]);
  const key = ids.join("|");
  const [, setRevision] = useState(0);
  useEffect(() => {
    const bump = (): void => setRevision((current) => current + 1);
    const offs = ids.map((harness) => catalog.subscribeModels(harness, bump));
    bump();
    return () => {
      for (const off of offs) {
        off();
      }
    };
    // The key pins the subscription set; ids itself is rebuilt per render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalog, key]);
  const lists = new Map<HarnessId, LoadableList<Model>>();
  for (const harness of ids) {
    lists.set(harness, catalog.getModels(harness));
  }
  return lists;
}

// ---------------------------------------------------------------------------
// The one identity card
// ---------------------------------------------------------------------------

interface IdentityCardProps {
  readonly open: boolean;
  readonly harnesses: LoadableList<HarnessDescriptor>;
  readonly harnessError: string | null;
  readonly noAgents: boolean;
  readonly locked: boolean;
  readonly railDescriptors: readonly HarnessDescriptor[];
  readonly modelsLists: Map<HarnessId, LoadableList<Model>>;
  readonly effectiveHarness: HarnessId;
  readonly draft: DraftConfig;
  readonly favorites: readonly { harness: HarnessId; model: string }[];
  readonly selectedModel: Model | undefined;
  readonly ladder: readonly ReasoningLevel[];
  readonly onRetryHarnesses: () => void;
  readonly onRetryModels: () => void;
  readonly onPickHarness: (harness: HarnessId) => void;
  readonly onPickModel: (harness: HarnessId, model: Model) => void;
  readonly onPickReasoning: (level: ReasoningLevel | null) => void;
  readonly onPickOption: (model: Model, optionId: string, choiceId: string, isDefault: boolean) => void;
  readonly onToggleFavorite: (harness: HarnessId, model: Model) => void;
}

function IdentityCard(props: IdentityCardProps) {
  const {
    open,
    harnesses,
    harnessError,
    noAgents,
    locked,
    railDescriptors,
    modelsLists,
    effectiveHarness,
    draft,
    favorites,
    selectedModel,
    ladder,
    onRetryHarnesses,
    onRetryModels,
    onPickHarness,
    onPickModel,
    onPickReasoning,
    onPickOption,
    onToggleFavorite,
  } = props;

  const [rail, setRail] = useState<ModelRail>(() =>
    !locked && favorites.length > 0 ? "favorites" : "harness",
  );
  const [query, setQuery] = useState("");
  const [scrollTop, setScrollTop] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);

  const isFavorite = useCallback(
    (harness: HarnessId, model: string): boolean =>
      favorites.some((favorite) => favorite.harness === harness && favorite.model === model),
    [favorites],
  );

  const modelsFor = useCallback(
    (harness: HarnessId): readonly Model[] | null => {
      const slot = modelsLists.get(harness);
      return slot === undefined || !slot.loaded ? null : slot.rows;
    },
    [modelsLists],
  );

  const rows = useMemo(
    () => scopedModelRows(query, rail, effectiveHarness, railDescriptors, modelsFor, isFavorite),
    [query, rail, effectiveHarness, railDescriptors, modelsFor, isFavorite],
  );

  // `selected_model_index`: the resolved model's index in the VISIBLE rows;
  // 0 when the favorites/search view doesn't contain it.
  const selectedModelIndex = useMemo(() => {
    if (selectedModel === undefined || (rail === "favorites" && !isFavorite(effectiveHarness, selectedModel.id))) {
      return 0;
    }
    const index = rows.findIndex((row) => row.harness === effectiveHarness && row.model.id === selectedModel.id);
    return index < 0 ? 0 : index;
  }, [rows, selectedModel, effectiveHarness, rail, isFavorite]);

  const activateRow = (index: number): void => {
    const row = rows[index];
    if (row === undefined) {
      return;
    }
    onPickModel(row.harness, row.model);
  };

  // The cursor keyboard model (`useCursorList`) — the walk, Enter, and the
  // scrollIntoView-on-cursor, all composed. Key handling rides a
  // capture-phase window listener while the card is open — the desktop
  // mounts it on the card so keys bubble from the focused search input,
  // but the takeover states have no input to focus, so a card-scoped
  // handler would never see the keys there. "any" context, per §2.5's
  // table. Escape is NOT handled here: Base UI's dismiss pipeline owns
  // it, which is what records the `escape-key` reason `PickerCard`'s
  // finalFocus decision reads (the composer focus return).
  const { cursor, setCursor, onKeyDown: walkKeys } = useCursorList({
    enabled: open,
    count: rows.length,
    onActivate: activateRow,
    listRef,
    rowAttribute: "model-index",
  });

  // Anchoring: on open (and after a star reorder), the cursor sits on the
  // selected row so exactly one row reads highlighted (pickers.rs toggle 5).
  // setCursor is the hook's setter — stable like useState's own.
  const anchorCursor = useCallback(
    (index: number): void => {
      setCursor(index);
      setScrollTop(0);
      if (listRef.current !== null) {
        listRef.current.scrollTop = 0;
      }
      requestAnimationFrame(() => {
        const row = listRef.current?.querySelector<HTMLElement>(`[data-model-index="${index}"]`);
        row?.scrollIntoView({ block: "nearest" });
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  // Fresh search box + focus on open; forced reload (toggle steps 3-8).
  const opened = open;
  useEffect(() => {
    if (!opened) {
      return;
    }
    setQuery("");
    // Prime the rail BEFORE anchoring: Favorites when the chat is not
    // harness-locked and there are saved favorites, else Harness (§2.5.4).
    setRail(!locked && favorites.length > 0 ? "favorites" : "harness");
    anchorCursor(selectedModelIndex);
    // Focus the search input; the takeover states have none, so the card
    // itself takes focus (toggle step 7). The card IS the popup element.
    if (inputRef.current === null) {
      (document.querySelector(".rb-popover-popup[data-open]") as HTMLElement | null)?.focus();
    } else {
      inputRef.current.focus();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened]);

  // Typing resets the highlight AND the list's scroll offset (gap row 32).
  const onQueryChange = (event: React.ChangeEvent<HTMLInputElement>): void => {
    setQuery(event.target.value);
    setCursor(0);
    setScrollTop(0);
    if (listRef.current !== null) {
      listRef.current.scrollTop = 0;
    }
  };

  useEffect(() => {
    if (!opened) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (!open) {
        // A closing card ignores keys (it keeps painting through the exit).
        return;
      }
      // The platform modifier — `modifiers.platform` (pickers.rs on_key_down):
      // Cmd on macOS, Ctrl elsewhere, the same modifier the shell's Mod+1..9
      // jump binding spells. The global dispatcher (ticket 12) suppresses the
      // session jump while this overlay owns the keyboard (`jump_model_slot`'s
      // first refusal); this listener is the picker's half of that handoff.
      if ((isMacPlatform() ? event.metaKey : event.ctrlKey) && /^[1-9]$/.test(event.key)) {
        // Cmd+1…9 activates the Nth visible row (gap row 16).
        event.preventDefault();
        activateRow(Number(event.key) - 1);
        return;
      }
      walkKeys(event);
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => {
      window.removeEventListener("keydown", onKeyDown, true);
    };
    // walkKeys reads the render's cursor/rows and is re-created every
    // render: re-arm on the same triggers as before the hook (open, the
    // list length, the cursor) so the captured walkKeys is always the one
    // from the render those last changed — the closure would otherwise go
    // stale. walkKeys itself is deliberately NOT a dep (it is a new
    // reference per render; listing it would re-arm on every render).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, rows.length, cursor, open]);

  const rowHeight = rail === "favorites" ? ROW_HEIGHT_FAVORITE : ROW_HEIGHT_COMPACT;
  const viewport = LIST_HEIGHT - 12; // the band's 6px padding-block, both sides
  const first = Math.max(0, Math.floor(scrollTop / rowHeight) - OVERSCAN);
  const last = Math.min(rows.length, Math.ceil((scrollTop + viewport) / rowHeight) + OVERSCAN);
  const slice = rows.slice(first, last);

  // The empty-list note precedence (§2.3.3).
  const modelsList = modelsLists.get(effectiveHarness);
  const modelSlotError = modelsList?.error ?? null;
  const emptyNote =
    query.trim().length > 0
      ? "No models found"
      : rail === "favorites"
        ? "No starred models yet — hit a row's star"
        : null;

  const showTraits = ladder.length > 0 || (selectedModel?.options ?? []).length > 0;

  return (
    <>
      {(() => {
        // Card-level takeover states render instead of the whole stack.
        if (!harnesses.loaded && harnessError === null) {
          return (
            <div className="model-list-loading" id="model-skeleton">
              <SkeletonMenuRows count={5} />
            </div>
          );
        }
        if (harnessError !== null && !harnesses.loaded) {
          return (
            <div className="model-list-loading">
              <ErrorRow message={harnessError} onRetry={onRetryHarnesses} />
            </div>
          );
        }
        if (noAgents) {
          return (
            <div className="model-no-agents">
              <Icon name="terminal" size={20} className="model-no-agents-icon" />
              <span className="model-no-agents-title">No agents available</span>
              <span className="model-no-agents-body">
                Enable an installed agent in Settings → Agents, or install an agent CLI.
              </span>
            </div>
          );
        }
        return (
          <>
            <div className="model-tab-strip" role="tablist">
              <button
                type="button"
                id="model-tab-favorites"
                role="tab"
                aria-selected={rail === "favorites"}
                className={`model-tab ${rail === "favorites" ? "model-tab-viewed" : ""}`}
                onClick={() => {
                  setRail("favorites");
                  anchorCursor(selectedModelIndex);
                }}
              >
                <Icon name="starBold" size={15} className={`model-tab-star ${rail === "favorites" ? "model-tab-star-viewed" : ""}`} />
                {rail === "favorites" && <span className="model-tab-marker" />}
              </button>
              {railDescriptors.map((descriptor) => {
                const isViewed = rail === "harness" && effectiveHarness === descriptor.id;
                const isDisabled = locked && effectiveHarness !== descriptor.id;
                const brand = harnessBrandIcon(descriptor.id);
                return (
                  <button
                    key={descriptor.id}
                    type="button"
                    role="tab"
                    aria-selected={isViewed}
                    className={`model-tab ${isViewed ? "model-tab-viewed" : ""} ${isDisabled ? "model-tab-locked" : ""}`}
                    onClick={() => {
                      setRail("harness");
                      // The handler stays attached while locked: the rail
                      // switches back, `pickHarness` no-ops (pickers.rs:3282).
                      onPickHarness(descriptor.id);
                    }}
                  >
                    <Icon
                      name={brand.name}
                      size={16}
                      style={brand.tint === null ? undefined : { color: brand.tint }}
                      className={`model-tab-brand ${isViewed ? "model-tab-brand-viewed" : ""}`}
                    />
                    {isViewed && <span className="model-tab-marker" />}
                  </button>
                );
              })}
            </div>
            <div className="model-search-row">
              <Icon name="magnifer" size={14} className="model-search-icon" />
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={onQueryChange}
                placeholder="Search models…"
                spellCheck={false}
                autoComplete="off"
                aria-label="Search models"
              />
            </div>
            <div className="model-list-scroll-host" id="model-list-scroll-host">
              <div
                ref={listRef}
                className="model-list-scroll"
                onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
              >
                {rows.length === 0 ? (
                  <div className="menu-scroll-fallback">
                    {modelSlotError !== null ? (
                      <ErrorRow message={modelSlotError} onRetry={onRetryModels} />
                    ) : emptyNote !== null ? (
                      <div className="model-list-note">{emptyNote}</div>
                    ) : (
                      <div id="model-skeleton">
                        <SkeletonMenuRows count={5} />
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="model-list-sizer" style={{ height: rows.length * rowHeight }}>
                    {slice.map((row, ixInSlice) => {
                      const ix = first + ixInSlice;
                      return (
                        <ModelRow
                          key={`${row.harness}/${row.model.id}`}
                          ix={ix}
                          row={row}
                          style={{ top: ix * rowHeight, height: rowHeight }}
                          twoLine={rail === "favorites"}
                          selected={
                            row.harness === effectiveHarness &&
                            selectedModel !== undefined &&
                            row.model.id === selectedModel.id
                          }
                          highlighted={ix === cursor && !(
                            row.harness === effectiveHarness &&
                            selectedModel !== undefined &&
                            row.model.id === selectedModel.id
                          )}
                          starred={isFavorite(row.harness, row.model.id)}
                          onActivate={() => activateRow(ix)}
                          onHover={() => setCursor(ix)}
                          onToggleFavorite={() => {
                            onToggleFavorite(row.harness, row.model);
                            // Starring reorders the list; re-home the cursor
                            // onto the SELECTED row so exactly one row reads
                            // highlighted (pickers.rs:3600-3624).
                            requestAnimationFrame(() => anchorCursor(selectedModelIndex));
                          }}
                        />
                      );
                    })}
                  </div>
                )}
              </div>
              <MenuScrollbar scrollRef={listRef} />
            </div>
            {showTraits && (
              <TraitsTray
                model={selectedModel}
                ladder={ladder}
                draft={draft}
                onPickReasoning={onPickReasoning}
                onPickOption={onPickOption}
              />
            )}
           </>
         );
      })()}
    </>
  );
}

/**
 * One row of the virtualized list — `render_model_row` (pickers.rs:3470).
 * The `ix` is the row's GLOBAL index: ⌘N chips, hover cursor, and activation
 * all key on it. The 2px inter-row gap bakes into each item's own box so
 * every item measures the same height for the virtualizer.
 */
function ModelRow({
  ix,
  row,
  style,
  twoLine,
  selected,
  highlighted,
  starred,
  onActivate,
  onHover,
  onToggleFavorite,
}: {
  ix: number;
  row: { harness: HarnessId; harnessName: string; model: Model };
  style: CSSProperties;
  twoLine: boolean;
  selected: boolean;
  highlighted: boolean;
  starred: boolean;
  onActivate: () => void;
  onHover: () => void;
  onToggleFavorite: () => void;
}) {
  const brand = harnessBrandIcon(row.harness);
  const attribution =
    row.model.description !== null &&
    row.model.description !== undefined &&
    row.model.description.trim().length > 0 &&
    row.model.description.toLowerCase() !== row.harnessName.toLowerCase()
      ? row.model.description
      : null;
  return (
    <div className="model-row-item" style={style} data-model-index={ix}>
      {/* The row is a div, not a button: the star button nests inside it
          (HTML forbids nested buttons) and keyboard activation lives on the
          card, exactly like the desktop's card-level key handler. */}
      <div
        className={`model-row ${twoLine ? "model-row-two-line" : ""} ${selected ? "model-row-selected" : ""} ${
          highlighted && !selected ? "model-row-highlighted" : ""
        }`}
        role="option"
        aria-selected={selected}
        onMouseEnter={onHover}
        onClick={onActivate}
      >
        {twoLine ? (
          <span className="model-row-body model-row-body-column">
            <span className="model-row-label">{row.model.label}</span>
            <span className="model-row-subline">
              <Icon
                name={brand.name}
                size={11}
                style={brand.tint === null ? undefined : { color: brand.tint }}
                className="model-row-subline-brand"
              />
              <span className="model-row-subline-harness">{row.harnessName}</span>
              {attribution !== null && (
                <>
                  <span className="model-row-subline-dot">·</span>
                  <span className="model-row-subline-attribution">{attribution}</span>
                </>
              )}
            </span>
          </span>
        ) : (
          <span className="model-row-body">
            <span className="model-row-label">{row.model.label}</span>
            {attribution !== null && <span className="model-row-attribution">{attribution}</span>}
          </span>
        )}
        {ix < 9 && <KbdHint>{`⌘${ix + 1}`}</KbdHint>}
        <button
          type="button"
          className={`model-row-star ${starred ? "model-row-star-on" : ""}`}
          aria-label={starred ? "Unstar model" : "Star model"}
          onClick={(event) => {
            event.stopPropagation();
            onToggleFavorite();
          }}
        >
          <Icon name={starred ? "starBold" : "star"} size={13} />
        </button>
      </div>
    </div>
  );
}

/**
 * The pinned traits tray — `render_traits_sections` (pickers.rs:3666-3774).
 * Keyboard nav never enters it: ↑/↓ walk the MODEL list only; the tray's
 * rows are mouse-only. There are no check marks — selection is the row's
 * active wash.
 */
function TraitsTray({
  model,
  ladder,
  draft,
  onPickReasoning,
  onPickOption,
}: {
  model: Model | undefined;
  ladder: readonly ReasoningLevel[];
  draft: DraftConfig;
  onPickReasoning: (level: ReasoningLevel | null) => void;
  onPickOption: (model: Model, optionId: string, choiceId: string, isDefault: boolean) => void;
}) {
  if (model === undefined) {
    return (
      <div className="model-traits" id="traits-skeleton" style={{ maxHeight: TRAYS_MAX_HEIGHT }}>
        <div className="model-traits-body">
          <SkeletonMenuRows count={3} />
        </div>
      </div>
    );
  }
  const options = model.options;
  if (ladder.length === 0 && options.length === 0) {
    return null;
  }
  const fallbackLevel = defaultReasoning(ladder);
  return (
    <div className="model-traits" style={{ maxHeight: TRAYS_MAX_HEIGHT }}>
      <div className="model-traits-body">
        {ladder.length > 0 && (
          <div className="model-traits-section">
            <MenuHeading>Reasoning</MenuHeading>
            {ladder.map((level) => (
              <MenuRowNav
                key={level}
                fadeKey={level}
                className="model-trait-row"
                selected={draft.reasoning === level}
                onClick={() => onPickReasoning(level)}
              >
                <span className="menu-row-label">{reasoningLabel(level)}</span>
                <span className="model-trait-spring" />
                {fallbackLevel === level && <DefaultBadge />}
              </MenuRowNav>
            ))}
          </div>
        )}
        {options.map((option, index) => {
          const saved = draft.modelOptions[option.id];
          const selectedChoice =
            typeof saved === "string" && option.choices.some((choice) => choice.id === saved)
              ? saved
              : option.defaultChoice;
          return (
            <div className="model-traits-section" key={option.id}>
              {index > 0 || ladder.length > 0 ? <MenuSeparator /> : null}
              <MenuHeading>{option.label}</MenuHeading>
              {option.choices.map((choice) => (
                <MenuRowNav
                  key={choice.id}
                  fadeKey={`${option.id}/${choice.id}`}
                  className="model-trait-row"
                  selected={selectedChoice === choice.id}
                  onClick={() =>
                    onPickOption(model, option.id, choice.id, choice.id === option.defaultChoice)
                  }
                >
                  <span className="menu-row-label">{choice.label}</span>
                  <span className="model-trait-spring" />
                  {choice.id === option.defaultChoice && <DefaultBadge />}
                </MenuRowNav>
              ))}
            </div>
          );
        })}
      </div>
    </div>
  );
}

/** `default_badge` (pickers.rs:3780) — bare muted "Default" text, no border. */
function DefaultBadge() {
  return <span className="model-default-badge">Default</span>;
}

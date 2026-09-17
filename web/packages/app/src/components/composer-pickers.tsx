import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import type { HarnessDescriptor, Model, ReasoningLevel } from "@roboco/proto";
import type { DraftConfig, DraftConfigUpdate } from "../lib/composer-actions";
import { classifyKey, filterAndSort, menuStep } from "../lib/picker-search";
import { anchorAbove } from "../lib/popover-anchor";
import { traitsActive, traitsSummary } from "../lib/traits-summary";
import { overlayKeyboard } from "../state/keymap";
import { PopoverCard, SearchInputFrame } from "./popover/menu";
import { MenuRowNav } from "./popover/menu-row";
import { MenuScrollbar } from "./popover/scrollbar";
import { ErrorRow, SkeletonMenuRows } from "./popover/skeleton";
import { POPUP_TRIGGER_ATTR, Popup, usePopup } from "./popover/popup";

/**
 * The composer's run identity — the desktop's `Pickers::render` cluster.
 *
 * ONE chip carries the whole run identity: the harness's brand mark, the model
 * name, and then the joined traits summary ("Medium", "High · 1M · Fast",
 * "Agent · Balance") as the chip's muted second tone. The run's configuration
 * reads without opening anything, and the suffix brightens only when something
 * departs from its default. No suffix when the model has neither a ladder nor
 * options.
 *
 * Opening the chip reveals the harness, model, and reasoning lists in one
 * card (the desktop's tabbed `render_harness_model_popover`): the facet tabs
 * and the searchable list sit in one `PopoverCard` on the shared popover
 * primitive, so the whole unit opens with `menu-in`, exits with `menu-out`,
 * and dismisses on an outside pointerdown. Escape returns focus to the chip
 * (the desktop's `animate_close` routes it to the composer; ticket 10 moves
 * the refocus target there). The harness list dims once the chat has a
 * persisted `ChatConfig` — the desktop locks it (`pickers.rs
 * HarnessModelPicker`).
 *
 * There is no sandbox facet: `SandboxLevel::WorkspaceWrite` is written when a
 * chat is created and preserved thereafter — the desktop never offers it as a
 * choice, so neither does this.
 */

const DEFAULT_REASONING: readonly ReasoningLevel[] = ["minimal", "low", "medium", "high", "xhigh", "max", "ultra", "ultracode", "ultrathink"];

interface ComposerPickersProps {
  readonly draft: DraftConfig;
  readonly harnesses: readonly HarnessDescriptor[];
  readonly models: readonly Model[];
  readonly harnessError: string | null;
  readonly modelsError: string | null;
  readonly harnessLocked: boolean;
  readonly onChange: (update: DraftConfigUpdate) => void;
  readonly onRetryHarnesses: () => void;
  readonly onRetryModels: () => void;
}

type PickerFacet = "harness" | "model" | "reasoning";

export interface PickerItem {
  readonly id: string;
  readonly label: string;
  readonly secondary?: string | null;
  readonly disabled?: boolean;
}

export function ComposerPickers(props: ComposerPickersProps) {
  const { draft, harnesses, models, harnessError, modelsError, harnessLocked, onChange, onRetryHarnesses, onRetryModels } = props;
  const chipRef = useRef<HTMLButtonElement | null>(null);
  // The popup's value is which facet's list is mounted.
  const popup = usePopup<PickerFacet>({
    onClosedByEscape: () => chipRef.current?.focus(),
  });

  const open = popup.asOpen();

  // An open composer picker owns the keyboard (`overlay_owns_keyboard`,
  // shell.rs:3681-3683): session-nav shortcuts go quiet underneath it, and
  // the sidebar's jump chips drop. The add-space palette (ticket 11)
  // registers itself the same way.
  useEffect(() => {
    overlayKeyboard.set("composer-pickers", open !== null);
    return () => overlayKeyboard.set("composer-pickers", false);
  }, [open]);

  const harnessItems = harnessPickerItems(harnesses);
  const modelItems = modelPickerItems(models);
  const reasoningItems = stringPickerItems(modelItems.length === 0 ? DEFAULT_REASONING : (models.find((m) => m.id === draft.model)?.reasoningLevels ?? DEFAULT_REASONING));

  const harnessLabel = harnesses.find((h) => h.id === draft.harness)?.name ?? draft.harness;
  const pickedModel = models.find((m) => m.id === draft.model);
  const modelLabel = pickedModel?.label ?? draft.model ?? "Model";
  const reasoningLabel = draft.reasoning ?? "Reasoning";
  const brand = harnessBrandIcon(draft.harness);
  const suffix = traitsSummary(pickedModel, draft.reasoning, draft.modelOptions);
  const suffixActive = traitsActive(pickedModel, draft.reasoning, draft.modelOptions);

  function pick(update: DraftConfigUpdate, next: PickerFacet | null = null): void {
    onChange(update);
    if (next === null) {
      popup.dismiss();
    } else {
      popup.open(next);
    }
  }

  // The card opens above the chip, bottom-left anchored, 6px gap
  // (`anchored_menu_above`, popover.rs:523) — clamped into the viewport.
  const placeAboveChip = (size: { width: number; height: number }): CSSProperties => {
    const rect = chipRef.current?.getBoundingClientRect();
    if (rect === undefined) {
      return { left: 8, top: 8 };
    }
    return anchorAbove(rect, size);
  };

  return (
    <div className="composer-pickers">
      <button
        type="button"
        ref={chipRef}
        {...{ [POPUP_TRIGGER_ATTR]: "" }}
        className={`identity-chip ${open !== null ? "identity-chip-open" : ""}`}
        onPointerDown={() => popup.noteTriggerPress()}
        onClick={() => {
          // The outside-press guard already dismissed on the pointerdown of
          // this same gesture; the note says "was open" — stay closed instead
          // of close-then-reopen (popover.rs:152-169).
          if (popup.takePressWasOpen()) {
            return;
          }
          popup.open("model");
        }}
        aria-haspopup="menu"
        aria-expanded={popup.get() !== null}
        title={`${harnessLabel} · ${modelLabel}${suffix === null ? "" : ` · ${suffix}`}`}
      >
        <Icon
          name={brand.name}
          size={16}
          className="identity-chip-brand"
          style={brand.tint === null ? undefined : { color: brand.tint }}
        />
        <span className="identity-chip-model">{modelLabel}</span>
        {suffix !== null && (
          <span className={`identity-chip-suffix ${suffixActive ? "identity-chip-suffix-active" : ""}`}>
            {suffix}
          </span>
        )}
      </button>
      <Popup popup={popup} placement={placeAboveChip}>
        {(facet) => (
          <PopoverCard
            role="dialog"
            aria-label="Run identity"
            style={{ minWidth: 220, maxWidth: 320 }}
          >
            <div className="identity-tabs" role="tablist">
              <IdentityTab id="harness" facet={facet} onFacet={(next) => popup.open(next)} label="Harness" value={harnessLabel} dim={harnessLocked} />
              <IdentityTab id="model" facet={facet} onFacet={(next) => popup.open(next)} label="Model" value={modelLabel} />
              <IdentityTab id="reasoning" facet={facet} onFacet={(next) => popup.open(next)} label="Effort" value={reasoningLabel} />
            </div>
            {facet === "harness" && (
              <PickerList
                key={facet}
                items={harnessItems}
                selectedId={draft.harness}
                placeholder="Search harnesses…"
                emptyHint="No harnesses installed."
                errorMessage={harnessError}
                onRetry={onRetryHarnesses}
                onPick={(item) => pick({ harness: item.id as DraftConfig["harness"] }, null)}
                onEscape={() => popup.closeByEscape()}
              />
            )}
            {facet === "model" && (
              <PickerList
                key={facet}
                items={modelItems}
                selectedId={draft.model}
                placeholder="Search models…"
                emptyHint="No models for this harness."
                errorMessage={modelsError}
                onRetry={onRetryModels}
                onPick={(item) => pick({ model: item.id })}
                onEscape={() => popup.closeByEscape()}
              />
            )}
            {facet === "reasoning" && (
              <PickerList
                key={facet}
                items={reasoningItems}
                selectedId={draft.reasoning}
                placeholder="Search reasoning…"
                emptyHint="No reasoning levels."
                onPick={(item) => pick({ reasoning: item.id as ReasoningLevel })}
                onEscape={() => popup.closeByEscape()}
              />
            )}
          </PopoverCard>
        )}
      </Popup>
    </div>
  );
}

/**
 * One tab of the opened identity card. The desktop's popover is tabbed across
 * harness and model with the traits ladder beside them; the same three facets
 * live here as one row of quiet tabs at the top of the card.
 */
function IdentityTab({
  id,
  facet,
  onFacet,
  label,
  value,
  dim,
}: {
  id: PickerFacet;
  facet: PickerFacet;
  onFacet: (facet: PickerFacet) => void;
  label: string;
  value: string;
  dim?: boolean;
}) {
  const selected = facet === id;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      className={`identity-tab ${selected ? "identity-tab-selected" : ""} ${dim === true ? "identity-tab-locked" : ""}`}
      onClick={() => onFacet(id)}
    >
      <span className="identity-tab-label">{label}</span>
      <span className="identity-tab-value">{value}</span>
    </button>
  );
}

interface PickerListProps<T extends PickerItem> {
  readonly items: readonly T[];
  readonly selectedId: string | null;
  readonly placeholder: string;
  readonly emptyHint: string;
  readonly errorMessage?: string | null;
  readonly loading?: boolean;
  readonly onRetry?: () => void;
  readonly onPick: (item: T) => void;
  /** Escape path — the caller routes it to `popup.closeByEscape()`. */
  readonly onEscape: () => void;
}

/**
 * The searchable list inside the card — the interim shell over the shared
 * primitives (`SearchInputFrame` + `MenuRowNav` + `MenuScrollbar` +
 * `SkeletonMenuRows`/`ErrorRow`). Ticket 10 replaces it with the per-facet
 * cards.
 *
 * Keyboard is the desktop's `PALETTE_SEARCH_CONTEXT` policy (composer.rs:
 * 1532-1590): the search input binds NOTHING — text editing is native, and
 * the keys deliberately left unbound there (bare ↑↓←→, Enter, Tab, and
 * their shifted/cmd variants) bubble to THIS frame, which owns row
 * navigation and activation. The frame handles ↑↓ (Ctrl+N/Ctrl+P mirror
 * them) with wrap via `menu_step`, Enter picks, Escape closes; Tab and the
 * arrows the palette does not use pass through to the browser untouched.
 */
function PickerList<T extends PickerItem>(props: PickerListProps<T>) {
  const { items, selectedId, placeholder, emptyHint, errorMessage, loading, onRetry, onPick, onEscape } = props;
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const onEscapeRef = useRef(onEscape);
  onEscapeRef.current = onEscape;

  const filtered = useMemo(() => filterAndSort(items, (item) => item.label, query), [items, query]);

  // Reset highlight when the filter set changes so the cursor always points
  // at something visible.
  useEffect(() => {
    setHighlight(0);
  }, [query, items.length]);

  // Focus the filter on open.
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // The frame's key handler — everything the search input lets through
  // lands here (see the component doc above for the let-through list).
  const onFrameKeyDown = (event: React.KeyboardEvent<HTMLDivElement>): void => {
    const key = classifyKey(event.key, event.metaKey, event.ctrlKey);
    switch (key) {
      case "down":
      case "up":
        event.preventDefault();
        setHighlight((current) => menuStep(current, filtered.length, key === "down" ? 1 : -1) ?? 0);
        return;
      case "enter":
        event.preventDefault();
        {
          const item = filtered[highlight];
          if (item !== undefined && item.disabled !== true) {
            onPickRef.current(item);
          }
        }
        return;
      case "escape":
        event.preventDefault();
        onEscapeRef.current();
        return;
      default:
        return;
    }
  };

  // Scroll the highlighted row into view as the user navigates.
  useEffect(() => {
    const list = listRef.current;
    if (list === null) {
      return;
    }
    const row = list.querySelector<HTMLElement>(`[data-pick-index="${highlight}"]`);
    row?.scrollIntoView({ block: "nearest" });
  }, [highlight, filtered.length]);

  return (
    <div
      style={{ display: "contents" }}
      onKeyDown={onFrameKeyDown}
    >
      <SearchInputFrame>
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={placeholder}
          spellCheck={false}
          autoComplete="off"
          aria-label="Filter"
        />
      </SearchInputFrame>
      <div className="menu-scroll-wrap">
        <div ref={listRef} className="menu-scroll-area" role="listbox" aria-label={placeholder}>
          {errorMessage !== null && errorMessage !== undefined ? (
            <ErrorRow message={errorMessage} onRetry={onRetry} />
          ) : loading === true ? (
            <SkeletonMenuRows count={5} />
          ) : filtered.length === 0 ? (
            <div className="menu-empty">{emptyHint}</div>
          ) : (
            filtered.map((item, ix) => {
              const isHighlight = ix === highlight;
              const isSelected = item.id === selectedId;
              return (
                <MenuRowNav
                  key={item.id}
                  fadeKey={item.id}
                  data-pick-index={ix}
                  role="option"
                  highlighted={isHighlight}
                  selected={isSelected}
                  disabled={item.disabled === true}
                  onMouseEnter={() => setHighlight(ix)}
                  onClick={() => {
                    if (item.disabled !== true) {
                      onPick(item);
                    }
                  }}
                >
                  <span className="menu-row-label">{item.label}</span>
                  {item.secondary !== null && item.secondary !== undefined && item.secondary.length > 0 && (
                    <span className="menu-row-secondary">{item.secondary}</span>
                  )}
                </MenuRowNav>
              );
            })
          )}
        </div>
        <MenuScrollbar scrollRef={listRef} />
      </div>
    </div>
  );
}

/** Convert a list of strings to picker items (id = label). */
export function stringPickerItems(items: readonly string[]): PickerItem[] {
  return items.map((label) => ({ id: label, label }));
}

/** Pick a model label for the picker (id = model.id). */
export function modelPickerItems(models: readonly Model[]): PickerItem[] {
  return models.map((model) => ({
    id: model.id,
    label: model.label.length > 0 ? model.label : model.id,
    secondary: model.description ?? null,
  }));
}

/**
 * Pick a harness item (id = harness.id).
 *
 * Uninstalled harnesses are FILTERED OUT, not offered disabled: the desktop's
 * `offered_harnesses` never puts one in the list, so there is no "CLI not
 * detected" row to grey. With none installed the picker falls through to its
 * own empty state ("No harnesses installed.").
 */
export function harnessPickerItems(harnesses: readonly HarnessDescriptor[]): PickerItem[] {
  return harnesses
    .filter((harness) => harness.installed !== false && harness.enabled !== false)
    .map((harness) => ({
      id: harness.id,
      label: harness.name.length > 0 ? harness.name : harness.id,
      secondary: null,
    }));
}

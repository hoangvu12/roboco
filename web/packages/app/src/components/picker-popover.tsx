import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { HarnessDescriptor, Model } from "@roboco/proto";
import { filterAndSort } from "../lib/picker-search";

/**
 * The picker popover — the desktop's shared `popover::searchable_list`
 * (`crates/ui/src/popover.rs`) translated to React. A filter input on top of
 * a virtual-free scrollable list of items, with ↑↓/⏎/Esc keyboard navigation
 * and wrap-around highlight. Escape closes the popover and restores focus
 * to the composer; click-outside does NOT close (the desktop's choice —
 * accidental dismissals are the more common UX bug).
 */
export interface PickerItem {
  readonly id: string;
  readonly label: string;
  readonly secondary?: string | null;
  readonly disabled?: boolean;
}

interface PickerPopoverProps<T extends PickerItem> {
  readonly items: readonly T[];
  readonly selectedId: string | null;
  readonly placeholder?: string;
  readonly emptyHint?: string;
  readonly errorMessage?: string | null;
  readonly loading?: boolean;
  readonly onPick: (item: T) => void;
  readonly onClose: () => void;
  /** A custom row renderer (e.g., the harness chip shows a dot). */
  readonly renderItem?: (item: T, highlighted: boolean) => ReactNode;
}

/**
 * The popover renders its own backdrop (transparent) so click-outside is a
 * no-op. Focus the filter input on open so keyboard navigation works
 * immediately. Wrap-around highlight means ↓ from the last row jumps to the
 * first, ↑ from the first jumps to the last — matching the desktop.
 */
export function PickerPopover<T extends PickerItem>(props: PickerPopoverProps<T>) {
  const { items, selectedId, placeholder = "Filter…", emptyHint = "No matches.", errorMessage, loading, onPick, onClose, renderItem } = props;
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const onPickRef = useRef(onPick);
  onPickRef.current = onPick;
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

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

  // Keyboard nav: ↑/↓ wrap; Enter picks the highlighted; Esc closes.
  const move = useCallback(
    (delta: number) => {
      if (filtered.length === 0) {
        return;
      }
      setHighlight((current) => {
        const next = current + delta;
        if (next < 0) {
          return filtered.length - 1;
        }
        if (next >= filtered.length) {
          return 0;
        }
        return next;
      });
    },
    [filtered.length],
  );

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>): void => {
    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        move(1);
        return;
      case "ArrowUp":
        event.preventDefault();
        move(-1);
        return;
      case "Enter": {
        event.preventDefault();
        const pick = filtered[highlight];
        if (pick !== undefined && !pick.disabled) {
          onPickRef.current(pick as T);
        }
        return;
      }
      case "Escape":
        event.preventDefault();
        onCloseRef.current();
        return;
      // No Tab handling: `popover.rs`/`pickers.rs` have none, so Tab keeps the
      // browser's native focus move.
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
      className="picker-popover panel"
      role="listbox"
      aria-busy={loading === true ? "true" : undefined}
      onMouseDown={(event) => event.stopPropagation()}
    >
      <div className="picker-popover-search">
        <input
          ref={inputRef}
          className="input picker-popover-input"
          type="text"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={placeholder}
          spellCheck={false}
          autoComplete="off"
          aria-label="Filter"
        />
      </div>
      <div ref={listRef} className="picker-popover-list" role="presentation">
        {errorMessage !== null && errorMessage !== undefined ? (
          <div className="picker-popover-error">
            <span>{errorMessage}</span>
          </div>
        ) : filtered.length === 0 ? (
          <div className="picker-popover-empty">
            <span>{emptyHint}</span>
          </div>
        ) : (
          filtered.map((item, ix) => {
            const isHighlight = ix === highlight;
            const isSelected = item.id === selectedId;
            return (
              <button
                type="button"
                key={item.id}
                data-pick-index={ix}
                role="option"
                aria-selected={isSelected}
                className={`picker-row ${isHighlight ? "picker-row-highlight" : ""} ${isSelected ? "picker-row-selected" : ""}`}
                onMouseEnter={() => setHighlight(ix)}
                onClick={() => {
                  if (!item.disabled) {
                    onPick(item);
                  }
                }}
                disabled={item.disabled === true}
              >
                {renderItem !== undefined ? (
                  renderItem(item, isHighlight)
                ) : (
                  <>
                    <span className="picker-row-label">{item.label}</span>
                    {item.secondary !== null && item.secondary !== undefined && item.secondary.length > 0 && (
                      <span className="picker-row-secondary">{item.secondary}</span>
                    )}
                  </>
                )}
              </button>
            );
          })
        )}
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

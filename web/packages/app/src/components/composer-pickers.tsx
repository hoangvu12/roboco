import { useEffect, useRef, useState } from "react";
import { Icon, harnessBrandIcon } from "@roboco/icons";
import type { HarnessDescriptor, Model, ReasoningLevel, SandboxLevel } from "@roboco/proto";
import type { DraftConfig, DraftConfigUpdate } from "../lib/composer-actions";
import { traitsActive, traitsSummary } from "../lib/traits-summary";
import {
  PickerPopover,
  harnessPickerItems,
  modelPickerItems,
  stringPickerItems,
  type PickerItem,
} from "./picker-popover";

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
 * Opening the chip reveals the harness, model, reasoning, and sandbox lists in
 * one stack (the desktop's tabbed `render_harness_model_popover`). The harness
 * list dims once the chat has a persisted `ChatConfig` — the desktop locks it
 * (`pickers.rs HarnessModelPicker`).
 */

const SANDBOX_LEVELS: readonly SandboxLevel[] = ["read-only", "workspace-write", "danger-full-access"];
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

type OpenPicker = "harness" | "model" | "reasoning" | "sandbox" | null;

export function ComposerPickers(props: ComposerPickersProps) {
  const { draft, harnesses, models, harnessError, modelsError, harnessLocked, onChange, onRetryHarnesses, onRetryModels } = props;
  const [open, setOpen] = useState<OpenPicker>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Close any open popover on outside click (the desktop keeps the popover
  // open; the web's small-screen layouts make stray clicks more likely, so
  // we close on pointerdown outside the popover container. Esc still closes.)
  useEffect(() => {
    if (open === null) {
      return;
    }
    const onPointer = (event: PointerEvent): void => {
      const root = containerRef.current;
      if (root === null) {
        return;
      }
      if (!root.contains(event.target as Node)) {
        setOpen(null);
      }
    };
    window.addEventListener("pointerdown", onPointer);
    return () => window.removeEventListener("pointerdown", onPointer);
  }, [open]);

  const harnessItems = harnessPickerItems(harnesses);
  const modelItems = modelPickerItems(models);
  const reasoningItems = stringPickerItems(modelItems.length === 0 ? DEFAULT_REASONING : (models.find((m) => m.id === draft.model)?.reasoningLevels ?? DEFAULT_REASONING));
  const sandboxItems = stringPickerItems(SANDBOX_LEVELS);

  const harnessLabel = harnesses.find((h) => h.id === draft.harness)?.name ?? draft.harness;
  const pickedModel = models.find((m) => m.id === draft.model);
  const modelLabel = pickedModel?.label ?? draft.model ?? "Model";
  const reasoningLabel = draft.reasoning ?? "Reasoning";
  const sandboxLabel = draft.sandbox;
  const brand = harnessBrandIcon(draft.harness);
  const suffix = traitsSummary(pickedModel, draft.reasoning, draft.modelOptions);
  const suffixActive = traitsActive(pickedModel, draft.reasoning, draft.modelOptions);

  function pick(update: DraftConfigUpdate, next: OpenPicker = null): void {
    onChange(update);
    setOpen(next);
  }

  return (
    <div className="composer-pickers" ref={containerRef}>
      <button
        type="button"
        className={`identity-chip ${open !== null ? "identity-chip-open" : ""}`}
        onClick={() => setOpen((current) => (current === null ? "model" : null))}
        aria-haspopup="menu"
        aria-expanded={open !== null}
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
      {open !== null && (
        <div className="identity-tabs" role="tablist">
          <IdentityTab id="harness" open={open} setOpen={setOpen} label="Harness" value={harnessLabel} dim={harnessLocked} />
          <IdentityTab id="model" open={open} setOpen={setOpen} label="Model" value={modelLabel} />
          <IdentityTab id="reasoning" open={open} setOpen={setOpen} label="Effort" value={reasoningLabel} />
          <IdentityTab id="sandbox" open={open} setOpen={setOpen} label="Sandbox" value={sandboxLabel} />
        </div>
      )}
      {open === "harness" && (
        <PickerPopover
          items={harnessItems}
          selectedId={draft.harness}
          placeholder="Search harnesses…"
          emptyHint="No harnesses installed."
          errorMessage={harnessError}
          onPick={(item) => pick({ harness: item.id as DraftConfig["harness"] }, null)}
          onClose={() => setOpen(null)}
          renderItem={(item, _) => (
            <>
              <span className="picker-row-label">{item.label}</span>
              {item.secondary !== null && item.secondary !== undefined && item.secondary.length > 0 && (
                <span className="picker-row-secondary">{item.secondary}</span>
              )}
            </>
          )}
        />
      )}
      {open === "harness" && harnessError !== null && (
        <div className="composer-pickers-retry">
          <button type="button" className="btn btn-ghost" onClick={onRetryHarnesses}>
            Retry
          </button>
        </div>
      )}
      {open === "model" && (
        <PickerPopover
          items={modelItems}
          selectedId={draft.model}
          placeholder="Search models…"
          emptyHint="No models for this harness."
          errorMessage={modelsError}
          onPick={(item) => pick({ model: item.id })}
          onClose={() => setOpen(null)}
        />
      )}
      {open === "model" && modelsError !== null && (
        <div className="composer-pickers-retry">
          <button type="button" className="btn btn-ghost" onClick={onRetryModels}>
            Retry
          </button>
        </div>
      )}
      {open === "reasoning" && (
        <PickerPopover
          items={reasoningItems}
          selectedId={draft.reasoning}
          placeholder="Search reasoning…"
          emptyHint="No reasoning levels."
          onPick={(item: PickerItem) => pick({ reasoning: item.id as ReasoningLevel })}
          onClose={() => setOpen(null)}
        />
      )}
      {open === "sandbox" && (
        <PickerPopover
          items={sandboxItems}
          selectedId={draft.sandbox}
          placeholder="Search sandbox…"
          emptyHint="No sandbox levels."
          onPick={(item: PickerItem) => pick({ sandbox: item.id as SandboxLevel })}
          onClose={() => setOpen(null)}
        />
      )}
    </div>
  );
}

/**
 * One tab of the opened identity popover. The desktop's popover is tabbed
 * across harness and model with the traits ladder beside them; the same four
 * facets live here as a single row of quiet tabs above whichever list is open.
 */
function IdentityTab({
  id,
  open,
  setOpen,
  label,
  value,
  dim,
}: {
  id: Exclude<OpenPicker, null>;
  open: OpenPicker;
  setOpen: (next: OpenPicker) => void;
  label: string;
  value: string;
  dim?: boolean;
}) {
  const selected = open === id;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      className={`identity-tab ${selected ? "identity-tab-selected" : ""} ${dim === true ? "identity-tab-locked" : ""}`}
      onClick={() => setOpen(id)}
    >
      <span className="identity-tab-label">{label}</span>
      <span className="identity-tab-value">{value}</span>
    </button>
  );
}

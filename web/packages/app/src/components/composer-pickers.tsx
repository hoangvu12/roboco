import { useEffect, useRef, useState } from "react";
import type { HarnessDescriptor, Model, ReasoningLevel, SandboxLevel } from "@roboco/proto";
import type { DraftConfig, DraftConfigUpdate } from "../lib/composer-actions";
import {
  PickerPopover,
  harnessPickerItems,
  modelPickerItems,
  stringPickerItems,
  type PickerItem,
} from "./picker-popover";

/**
 * The chip row at the foot of the composer: harness, model, reasoning, and
 * sandbox pickers. Each chip opens a `PickerPopover` anchored above. The
 * harness chip dims once the chat has a persisted `ChatConfig` (the desktop
 * locks it: `crates/ui/src/pickers.rs HarnessModelPicker`). Reasoning and
 * sandbox options are gated on the picked harness/model/reasoning so an
 * unrelated harness's ladders don't appear.
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
  const modelLabel = models.find((m) => m.id === draft.model)?.label ?? draft.model ?? "Model";
  const reasoningLabel = draft.reasoning ?? "Reasoning";
  const sandboxLabel = draft.sandbox;

  function pick(update: DraftConfigUpdate, next: OpenPicker = null): void {
    onChange(update);
    setOpen(next);
  }

  return (
    <div className="composer-pickers" ref={containerRef}>
      <div className="composer-pickers-row">
        <PickerChip
          label="Harness"
          value={harnessLabel}
          dim={harnessLocked}
          open={open === "harness"}
          onToggle={() => setOpen((current) => (current === "harness" ? null : "harness"))}
        />
        <PickerChip
          label="Model"
          value={modelLabel}
          open={open === "model"}
          onToggle={() => setOpen((current) => (current === "model" ? null : "model"))}
        />
        <PickerChip
          label="Reasoning"
          value={reasoningLabel}
          open={open === "reasoning"}
          onToggle={() => setOpen((current) => (current === "reasoning" ? null : "reasoning"))}
        />
        <PickerChip
          label="Sandbox"
          value={sandboxLabel}
          open={open === "sandbox"}
          onToggle={() => setOpen((current) => (current === "sandbox" ? null : "sandbox"))}
        />
      </div>
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

function PickerChip({ label, value, dim, open, onToggle }: { label: string; value: string; dim?: boolean; open: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className={`picker-chip ${open ? "picker-chip-open" : ""} ${dim === true ? "picker-chip-locked" : ""}`}
      onClick={onToggle}
      aria-haspopup="listbox"
      aria-expanded={open}
    >
      <span className="picker-chip-label">{label}</span>
      <span className="picker-chip-value">{value}</span>
    </button>
  );
}

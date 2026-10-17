import { harnessBrandIcon, Icon } from "@roboco/icons";
import type { HarnessDescriptor, Model } from "@roboco/proto";
import { dialogError, dialogLoading, type DialogLoad } from "../../lib/harness-choice";

/**
 * The shared harness/model choice rows (t3-orchestration-v2 tickets 21/22/
 * 25) — the `ui/` composites both choice surfaces render: the fork dialog's
 * modal body and the Limited banner's "Continue on another harness" card.
 * Data + callbacks in, DOM out; no engine state (the components/README
 * `ui/` rule). The desktop keeps the same row shapes in both dialogs
 * (`shell/fork_dialog.rs` / `shell/continue_on_harness.rs`).
 */

/** One offered harness's chip — the desktop dialogs' chip treatment. */
export function HarnessChip({
  descriptor,
  selected,
  onPick,
}: {
  readonly descriptor: HarnessDescriptor;
  readonly selected: boolean;
  readonly onPick: () => void;
}) {
  const brand = harnessBrandIcon(descriptor.id);
  return (
    <button
      type="button"
      className="harness-choice-chip"
      data-selected={selected ? "1" : undefined}
      onClick={onPick}
    >
      <Icon
        name={brand.name}
        size={13}
        style={{ color: brand.tint ?? (selected ? "var(--rb-text)" : "var(--rb-text-muted)") }}
      />
      <span className="harness-choice-chip-label">{descriptor.name}</span>
    </button>
  );
}

/**
 * The selected harness's model list — the highlighted row is the pick, or
 * the harness's default row (first) when nothing is picked (a null model
 * means "the default at run time").
 */
export function ModelList({
  models,
  error,
  loading,
  selectedModel,
  onPick,
}: {
  readonly models: readonly Model[];
  readonly error: string | null;
  readonly loading: boolean;
  readonly selectedModel: string | null;
  readonly onPick: (modelId: string) => void;
}) {
  if (models.length === 0) {
    return (
      <div className="harness-choice-empty harness-choice-models-empty">
        {modelListEmptyText(error, loading)}
      </div>
    );
  }
  return (
    <div className="harness-choice-models">
      {models.map((model, ix) => {
        const selected = selectedModel === model.id || (selectedModel === null && ix === 0);
        const defaultRow = selectedModel === null && ix === 0;
        const description = model.description?.trim() ?? "";
        return (
          <button
            key={model.id}
            type="button"
            className="harness-choice-model-row"
            data-selected={selected ? "1" : undefined}
            onClick={() => onPick(model.id)}
          >
            <span className="harness-choice-model-main">
              <span className="harness-choice-model-label">{model.label}</span>
              {defaultRow && <span className="harness-choice-model-default">Default</span>}
            </span>
            {description.length > 0 && (
              <span className="harness-choice-model-sub">{description}</span>
            )}
          </button>
        );
      })}
    </div>
  );
}

/**
 * The models list's empty text (the desktop dialog's arms): the load's
 * failure, its in-flight word, or the honest "lists none". Pure.
 */
export function modelListEmptyText(error: string | null, loading: boolean): string {
  return error !== null
    ? `Couldn't load the models: ${error}`
    : loading
      ? "Loading models…"
      : "This harness lists no models.";
}

/**
 * The harness chips row's empty text — the same three arms, with the
 * caller's "none offered" word (the fork dialog says "No harnesses are
 * available on this device.", the Continue card "No other harnesses are
 * available on this device."). Pure.
 */
export function harnessChipsEmptyText(load: DialogLoad<unknown>, none: string): string {
  const error = dialogError(load);
  return error !== null
    ? `Couldn't load the harnesses: ${error}`
    : dialogLoading(load)
      ? "Loading this device's harnesses…"
      : none;
}

import type { Model, ReasoningLevel } from "@roboco/proto";

/** `pickers.rs::reasoning_label` — the ladder's display names. */
const REASONING_LABELS: Record<ReasoningLevel, string> = {
  minimal: "Minimal",
  low: "Low",
  medium: "Medium",
  high: "High",
  xhigh: "X-High",
  max: "Max",
  ultra: "Ultra",
  ultracode: "Ultracode",
  ultrathink: "Ultrathink",
};

export function reasoningLabel(level: ReasoningLevel): string {
  return REASONING_LABELS[level] ?? level;
}

/**
 * The identity chip's muted second tone — a port of
 * `pickers.rs::traits_summary`.
 *
 * The effective reasoning level plus every model option's effective choice
 * (the explicit pick when one is saved and the model still offers it, else the
 * option's default), joined with " · ": "High · 1M · Fast", or Cursor's
 * "Agent · Balance". Defaults are spelled out rather than hidden, so the run's
 * configuration reads without opening anything. `null` only when the model has
 * nothing to describe — no ladder and no options.
 */
export function traitsSummary(
  model: Model | undefined,
  reasoning: ReasoningLevel | null,
  selections: Readonly<Record<string, unknown>>,
): string | null {
  const parts: string[] = [];
  if (reasoning !== null) {
    parts.push(reasoningLabel(reasoning));
  }
  for (const option of model?.options ?? []) {
    const saved = selections[option.id];
    const picked =
      typeof saved === "string" && option.choices.some((choice) => choice.id === saved)
        ? saved
        : option.defaultChoice;
    const choice = option.choices.find((candidate) => candidate.id === picked);
    if (choice !== undefined) {
      parts.push(choice.label);
    }
  }
  return parts.length === 0 ? null : parts.join(" · ");
}

/**
 * True when any part of the summary departs from its default — the desktop
 * brightens the suffix in that case (`traits_active`) so a non-default run
 * reads louder than a default one.
 */
export function traitsActive(
  model: Model | undefined,
  reasoning: ReasoningLevel | null,
  selections: Readonly<Record<string, unknown>>,
): boolean {
  const defaultLevel = model?.reasoningLevels[0] ?? null;
  if (reasoning !== null && defaultLevel !== null && reasoning !== defaultLevel) {
    return true;
  }
  return (model?.options ?? []).some((option) => {
    const saved = selections[option.id];
    return (
      typeof saved === "string" &&
      saved !== option.defaultChoice &&
      option.choices.some((choice) => choice.id === saved)
    );
  });
}

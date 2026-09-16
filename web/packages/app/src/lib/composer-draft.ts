import type { Chat, HarnessDescriptor, HarnessId, Model } from "@roboco/proto";
import type { DraftConfig } from "./composer-actions";
import { buildChatConfig } from "./composer-actions";

/**
 * Sensible defaults when a fresh chat has no `ChatConfig` yet, derived from
 * the loaded harness/model catalogs. Picked fields are intentionally
 * narrow: the user's first picker choice is the first enabled harness and
 * its first model, with `medium` reasoning and `workspace-write` sandbox.
 * The composer only invokes this when the catalog has actually loaded.
 */
export function defaultDraft(catalog: readonly HarnessDescriptor[], models: readonly Model[]): DraftConfig {
  const harness = catalog.find((row) => row.enabled !== false) ?? catalog[0];
  const harnessId: HarnessId = harness?.id ?? "claude-code";
  const model = models[0]?.id ?? null;
  const reasoning = models[0]?.reasoningLevels[0] ?? "medium";
  return {
    harness: harnessId,
    model,
    reasoning,
    sandbox: "workspace-write",
    modelOptions: {},
  };
}

/** Initialize the composer's draft from the chat's persisted ChatConfig (may be null). */
export function draftFromChat(
  chat: Chat,
  catalog: readonly HarnessDescriptor[],
  models: readonly Model[],
): DraftConfig {
  const config = chat.config;
  if (config === null) {
    return defaultDraft(catalog, models);
  }
  const harnessId: HarnessId = config.harness;
  const reasoning = config.reasoning;
  const modelOptions: Record<string, unknown> = { ...(config.modelOptions ?? {}) };
  return {
    harness: harnessId,
    model: config.model,
    reasoning,
    sandbox: config.sandbox,
    modelOptions,
  };
}

/** True when a chat has a persisted ChatConfig (locks the harness picker). */
export function isHarnessLocked(chat: Chat | null): boolean {
  return chat !== null && chat.config !== null;
}

/** Two drafts differ when any user-facing field changed. */
export function draftsEqual(a: DraftConfig, b: DraftConfig): boolean {
  return JSON.stringify(buildChatConfig(a)) === JSON.stringify(buildChatConfig(b));
}
import { describe, expect, it } from "vitest";
import type { ChatConfig, HarnessDescriptor, Model } from "@roboco/proto";
import { defaultDraft, draftFromChat, draftsEqual, isHarnessLocked } from "../src/lib/composer-draft";

function chat(overrides: Partial<{ config: ChatConfig | null }> = {}): Parameters<typeof draftFromChat>[0] {
  return {
    id: "chat-1",
    deviceId: "device-1",
    title: null,
    archived: false,
    cwd: "/Users/me/proj",
    branch: null,
    checkoutId: null,
    config: overrides.config !== undefined ? overrides.config : null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-09-16T10:00:00Z",
  };
}

const HARNESSES: readonly HarnessDescriptor[] = [
  { id: "claude-code", name: "Claude Code", supportsSteering: true, steeringMode: "step-boundary", reasoningLevels: ["low", "medium", "high"], installed: true, enabled: true },
  { id: "codex", name: "Codex", supportsSteering: false, steeringMode: "turn-boundary", reasoningLevels: ["medium"], installed: true, enabled: true },
];

const MODELS: readonly Model[] = [
  { id: "sonnet", label: "Sonnet", reasoningLevels: ["low", "medium", "high"], options: [] },
];

describe("defaultDraft", () => {
  it("picks the first harness + first model + first reasoning + workspace-write sandbox", () => {
    const draft = defaultDraft(HARNESSES, MODELS);
    expect(draft.harness).toBe("claude-code");
    expect(draft.model).toBe("sonnet");
    expect(draft.reasoning).toBe("low");
    expect(draft.sandbox).toBe("workspace-write");
  });

  it("falls back to a sensible harness + null model when the catalog is empty", () => {
    const draft = defaultDraft([], []);
    expect(draft.harness).toBe("claude-code");
    expect(draft.model).toBeNull();
    expect(draft.reasoning).toBe("medium");
    expect(draft.sandbox).toBe("workspace-write");
  });
});

describe("draftFromChat", () => {
  it("replays the persisted ChatConfig when one exists", () => {
    const persisted: ChatConfig = {
      harness: "codex",
      model: "gpt-5",
      reasoning: "medium",
      modelOptions: { mode: "fast" },
      sandbox: "read-only",
    };
    expect(draftFromChat(chat({ config: persisted }), HARNESSES, MODELS)).toEqual({
      harness: "codex",
      model: "gpt-5",
      reasoning: "medium",
      modelOptions: { mode: "fast" },
      sandbox: "read-only",
    });
  });

  it("falls back to defaults when ChatConfig is null", () => {
    expect(draftFromChat(chat(), HARNESSES, MODELS)).toEqual({
      harness: "claude-code",
      model: "sonnet",
      reasoning: "low",
      modelOptions: {},
      sandbox: "workspace-write",
    });
  });
});

describe("isHarnessLocked", () => {
  it("locks the harness when the chat row carries a ChatConfig", () => {
    expect(isHarnessLocked(chat({ config: { harness: "claude-code", model: null, reasoning: null, modelOptions: {}, sandbox: "workspace-write" } }))).toBe(true);
  });

  it("unlocks the harness for a fresh chat (no ChatConfig yet)", () => {
    expect(isHarnessLocked(chat({ config: null }))).toBe(false);
  });

  it("treats a null chat as unlocked (the engine is unavailable)", () => {
    expect(isHarnessLocked(null)).toBe(false);
  });
});

describe("draftsEqual", () => {
  it("matches structurally equal drafts", () => {
    expect(
      draftsEqual(
        { harness: "claude-code", model: "sonnet", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
        { harness: "claude-code", model: "sonnet", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
      ),
    ).toBe(true);
  });

  it("differs on any effective field", () => {
    expect(
      draftsEqual(
        { harness: "claude-code", model: "sonnet", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
        { harness: "codex", model: "sonnet", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
      ),
    ).toBe(false);
    expect(
      draftsEqual(
        { harness: "claude-code", model: "sonnet", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
        { harness: "claude-code", model: "opus", reasoning: "low", modelOptions: {}, sandbox: "workspace-write" },
      ),
    ).toBe(false);
  });
});

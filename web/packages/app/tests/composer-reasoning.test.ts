// @vitest-environment jsdom

/**
 * Ticket 77 — the model picker's Reasoning section over the EFFECTIVE ladder
 * (model levels when nonempty, else the matching harness descriptor's), the
 * real click → commit → draft path, and the composer's model/descriptor
 * reconciliation owner. The real ComposerPickers mounts against a real
 * PickerCatalog driven by a controllable fake client; the composer's
 * reconciliation runs through its extracted hook (the owner composer.tsx
 * wires). No JSX (createElement), per-file jsdom pragma only — the same
 * mounted-suite idiom as session-provider.test.ts (ticket 67).
 */

import { act, createElement, useState } from "react";
import { createRoot } from "react-dom/client";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import type { ChatConfig, HarnessDescriptor, Model } from "@roboco/proto";
import type { DraftConfig } from "../src/lib/composer-actions";
import { ComposerPickers } from "../src/components/composer-pickers";
import { useDraftModelReconciliation } from "../src/lib/composer-reconciliation";
import { PickerCatalog } from "../src/state/picker-catalog";

// ── jsdom gaps the mounted card hits ────────────────────────────────────────
// matchMedia (useIsPhone in PickerCard), ResizeObserver (MenuScrollbar),
// scrollIntoView (the cursor list's scroll effect / anchorCursor).

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
  if (typeof Element.prototype.scrollIntoView !== "function") {
    Element.prototype.scrollIntoView = () => {};
  }
  if (typeof globalThis.requestAnimationFrame !== "function") {
    globalThis.requestAnimationFrame = ((callback: FrameRequestCallback) => {
      callback(0);
      return 0;
    }) as typeof requestAnimationFrame;
  }
});

afterAll(() => {
  delete (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT;
});

// ── Controllable catalog ────────────────────────────────────────────────────

/** Fake engine client: ListHarnesses/ListModels resolve from seeded fields. */
class FakeClient {
  harnesses: HarnessDescriptor[] = [];
  readonly modelsByHarness = new Map<string, Model[]>();

  async call<T>(method: string, params?: unknown): Promise<T> {
    if (method === "ListHarnesses") {
      return this.harnesses as unknown as T;
    }
    if (method === "ListModels") {
      const harness = (params as { harness: string }).harness;
      return (this.modelsByHarness.get(harness) ?? []) as unknown as T;
    }
    return {} as T;
  }
}

/** Claude's descriptor shape: harness-advertised levels (registry.rs:447-452). */
const CLAUDE: HarnessDescriptor = {
  id: "claude-code",
  name: "Claude",
  supportsSteering: true,
  steeringMode: "step-boundary",
  reasoningLevels: ["low", "medium", "high"],
  installed: true,
  enabled: true,
};

/** A descriptor with NO advertised reasoning ladder. */
const BARE: HarnessDescriptor = {
  id: "codex",
  name: "Codex",
  supportsSteering: false,
  steeringMode: "turn-boundary",
  reasoningLevels: [],
  installed: true,
  enabled: true,
};

/**
 * Haiku's catalog shape (claude/catalog.rs:144-148): an EMPTY model reasoning
 * list plus a thinking option — the reported "no reasoning selector" shape.
 */
const HAIKU: Model = {
  id: "haiku",
  label: "Haiku 4.5",
  description: null,
  reasoningLevels: [],
  options: [
    {
      id: "thinking",
      label: "Thinking",
      choices: [
        { id: "off", label: "Off" },
        { id: "on", label: "On" },
      ],
      defaultChoice: "off",
    },
  ],
};

/** A model with its OWN nonempty ladder (descriptor must not merge in). */
const OPUS: Model = {
  id: "opus",
  label: "Opus",
  description: null,
  reasoningLevels: ["low", "high"],
  options: [],
};

function draft(overrides: Partial<DraftConfig> = {}): DraftConfig {
  return {
    harness: "claude-code",
    model: "haiku",
    reasoning: "low",
    sandbox: "workspace-write",
    modelOptions: {},
    ...overrides,
  };
}

// ── Mounted-picker harness ──────────────────────────────────────────────────

interface MountedPicker {
  readonly catalog: PickerCatalog;
  readonly observed: { current: DraftConfig };
  /** Every draft the picker's commit delivered through onDraft. */
  readonly drafts: DraftConfig[];
  /** Every draft the picker's commit delivered through onPersist. */
  readonly persists: DraftConfig[];
  readonly container: HTMLElement;
  unmount(): void;
}

const mounted: MountedPicker[] = [];

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  document.body.replaceChildren();
});

function mountPicker(options: {
  client: FakeClient;
  initial: DraftConfig;
  chatConfig?: ChatConfig | null;
}): MountedPicker {
  const catalog = new PickerCatalog(options.client);
  const observed: { current: DraftConfig } = { current: options.initial };
  const drafts: DraftConfig[] = [];
  const persists: DraftConfig[] = [];
  function Host() {
    const [current, setCurrent] = useState(options.initial);
    observed.current = current;
    return createElement(ComposerPickers, {
      catalog,
      draft: current,
      chatConfig: options.chatConfig ?? null,
      onDraft: (next: DraftConfig) => {
        drafts.push(next);
        setCurrent(next);
      },
      onPersist: (next: DraftConfig) => {
        persists.push(next);
      },
      escapeFocusTarget: () => null,
    });
  }
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(Host));
  });
  let unmounted = false;
  const handle: MountedPicker = {
    catalog,
    observed,
    drafts,
    persists,
    container,
    unmount() {
      if (unmounted) {
        return;
      }
      unmounted = true;
      act(() => {
        root.unmount();
      });
      container.remove();
      catalog.dispose();
    },
  };
  mounted.push(handle);
  return handle;
}

/** Flush the catalog's load promises and the resulting re-renders. */
async function flush(): Promise<void> {
  await act(async () => {});
}

async function openCard(handle: MountedPicker): Promise<void> {
  const trigger = handle.container.querySelector<HTMLElement>("#picker-model");
  expect(trigger).not.toBeNull();
  await act(async () => {
    trigger!.click();
  });
  await flush();
}

function reasoningRow(level: string): HTMLElement | null {
  // The card renders through a portal — query the document, not the container.
  // The nested tray (upstream 9a4757be): reasoning choices live under the
  // Reasoning trigger's expanded menu, keyed `setting-choice-reasoning-<level>`.
  return document.querySelector<HTMLElement>(
    `.model-traits [data-rb-row-key="setting-choice-reasoning-${level}"]`,
  );
}

function traitTriggers(): string[] {
  return Array.from(document.querySelectorAll(".model-traits .model-setting-row .menu-row-label")).map(
    (el) => el.textContent ?? "",
  );
}

function settingTrigger(id: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(
    `.model-traits [data-rb-row-key="model-setting-${id}"]`,
  );
}

function settingChoice(id: string, value: string): HTMLElement | null {
  return document.querySelector<HTMLElement>(
    `.model-traits [data-rb-row-key="setting-choice-${id}-${value}"]`,
  );
}

/** Click a settings trigger open (the nested menu expands inline). */
async function openSetting(id: string): Promise<void> {
  await act(async () => {
    settingTrigger(id)!.click();
  });
}

/** The card-level keydown path (the capture-phase window listener). Each key
 *  flushes React so the next key runs against the re-armed listener. */
function pressKey(key: string): void {
  act(() => {
    window.dispatchEvent(new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true }));
  });
}

describe("ComposerPickers reasoning over the effective ladder", () => {
  it("shows the descriptor's ladder for an empty model list and keeps the click through the draft", async () => {
    // §2.3 step 1's ready-catalog fixture: selected model has [], the
    // matching descriptor has [low, medium, high], the selected preference is
    // low. The tray's Reasoning trigger must offer all three choices and
    // selecting high must update the draft to high.
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const handle = mountPicker({ client, initial: draft() });
    await flush();
    await openCard(handle);

    // The descriptor-backed ladder trigger renders beneath the model list,
    // ahead of the model's own options.
    expect(traitTriggers()).toEqual(["Reasoning", "Thinking"]);
    await openSetting("reasoning");
    expect(reasoningRow("low")).not.toBeNull();
    expect(reasoningRow("medium")).not.toBeNull();
    expect(reasoningRow("high")).not.toBeNull();
    expect(reasoningRow("low")?.getAttribute("aria-selected")).toBe("true");
    // The native default (High) carries the Default badge.
    expect(reasoningRow("high")?.textContent).toContain("Default");

    // The real click path: remember + commit → applyDraftUpdate → onDraft.
    await act(async () => {
      reasoningRow("high")!.click();
    });
    expect(handle.drafts).toHaveLength(1);
    expect(handle.drafts[0]!.reasoning).toBe("high");
    expect(handle.observed.current.reasoning).toBe("high");
    // New chat: the draft is retained, nothing persists yet.
    expect(handle.persists).toHaveLength(0);

    // The pick closes the nested menu but keeps the card open for
    // multi-adjust (`activate_setting_choice`).
    expect(reasoningRow("high")).toBeNull();
    expect(settingTrigger("reasoning")).not.toBeNull();

    // Reopening lands the check on the picked level.
    await openSetting("reasoning");
    expect(reasoningRow("high")?.getAttribute("aria-selected")).toBe("true");
    expect(handle.container.querySelector(".identity-chip-suffix")?.textContent).toContain("High");
  });

  it("persists the picked fallback level on an established chat", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const persisted: ChatConfig = {
      harness: "claude-code",
      model: "haiku",
      reasoning: "low",
      modelOptions: {},
      sandbox: "workspace-write",
    };
    const handle = mountPicker({ client, initial: draft(), chatConfig: persisted });
    await flush();
    await openCard(handle);
    await openSetting("reasoning");

    await act(async () => {
      reasoningRow("high")!.click();
    });
    expect(handle.drafts[0]!.reasoning).toBe("high");
    // The established chat's existing persistence path carries the choice.
    expect(handle.persists).toHaveLength(1);
    expect(handle.persists[0]!.reasoning).toBe("high");
  });

  it("a nonempty model ladder wins over the descriptor's (no union)", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE, { ...BARE, reasoningLevels: ["medium", "max"] }];
    client.modelsByHarness.set("codex", [OPUS]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "opus", reasoning: null }),
    });
    await flush();
    await openCard(handle);

    expect(traitTriggers()).toEqual(["Reasoning"]);
    await openSetting("reasoning");
    // Only the model's own levels, in its advertised order.
    expect(reasoningRow("low")).not.toBeNull();
    expect(reasoningRow("high")).not.toBeNull();
    expect(reasoningRow("medium")).toBeNull();
    expect(reasoningRow("max")).toBeNull();
  });

  it("omits only the Reasoning section when both lists are empty", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE, BARE];
    client.modelsByHarness.set("codex", [{ ...HAIKU }]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "haiku", reasoning: null }),
    });
    await flush();
    await openCard(handle);

    // Model options still render independently; no Reasoning trigger appears.
    expect(traitTriggers()).toEqual(["Thinking"]);
    expect(settingTrigger("reasoning")).toBeNull();
    await openSetting("thinking");
    expect(settingChoice("thinking", "off")).not.toBeNull();
    expect(settingChoice("thinking", "on")).not.toBeNull();
  });

  it("keeps the picked level through an equivalent catalog refresh while open", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const handle = mountPicker({ client, initial: draft() });
    await flush();
    await openCard(handle);
    await openSetting("reasoning");
    await act(async () => {
      reasoningRow("high")!.click();
    });
    expect(handle.observed.current.reasoning).toBe("high");

    // A refresh lands equal content under fresh identities (the picker's own
    // forced reload path): the selection must survive.
    client.modelsByHarness.set("claude-code", [{ ...HAIKU }]);
    await act(async () => {
      await handle.catalog.loadModels("claude-code", { force: true });
    });
    await flush();
    expect(handle.observed.current.reasoning).toBe("high");
    await openSetting("reasoning");
    expect(reasoningRow("high")?.getAttribute("aria-selected")).toBe("true");
  });
});

// ── Nested settings: keyboard + independent choices (9a4757be) ──────────────

/** Two-option model — the desktop nested test's `contextWindow`/`serviceTier`. */
const GPT: Model = {
  id: "gpt-5.4",
  label: "GPT-5.4",
  description: null,
  reasoningLevels: ["low", "high"],
  options: [
    {
      id: "contextWindow",
      label: "Context window",
      defaultChoice: "standard",
      choices: [
        { id: "standard", label: "Standard" },
        { id: "extended", label: "Extended" },
      ],
    },
    {
      id: "serviceTier",
      label: "Service tier",
      defaultChoice: "auto",
      choices: [
        { id: "auto", label: "Standard" },
        { id: "fast", label: "Fast" },
      ],
    },
  ],
};

describe("ComposerPickers nested model settings", () => {
  async function mountGptPicker(): Promise<MountedPicker> {
    const client = new FakeClient();
    client.harnesses = [BARE];
    client.modelsByHarness.set("codex", [GPT]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "gpt-5.4", reasoning: null }),
    });
    await flush();
    await openCard(handle);
    return handle;
  }

  it("keyboard walk continues into the triggers and → opens the nested menu", async () => {
    const handle = await mountGptPicker();
    expect(traitTriggers()).toEqual(["Reasoning", "Context window", "Service tier"]);

    // One ↓ moves the cursor from the selected model row onto the first
    // settings trigger; → opens it (on_key_down's right arm).
    pressKey("ArrowDown");
    pressKey("ArrowRight");
    expect(settingChoice("reasoning", "low")).not.toBeNull();

    // Enter applies the highlighted choice — the anchored selected one —
    // and closes just the nested menu.
    pressKey("Enter");
    expect(handle.observed.current.reasoning).toBe("low");
    expect(settingChoice("reasoning", "low")).toBeNull();
    expect(settingTrigger("reasoning")).not.toBeNull();
  });

  it("navigate_and_preserve_independent_choices (the desktop port)", async () => {
    const handle = await mountGptPicker();

    // Open Context window via the keyboard, step to Extended, apply.
    pressKey("ArrowDown");
    pressKey("ArrowDown");
    pressKey("ArrowRight");
    expect(settingChoice("contextWindow", "standard")).not.toBeNull();
    pressKey("ArrowDown");
    pressKey("Enter");
    expect(handle.observed.current.modelOptions.contextWindow).toBe("extended");

    // The same walk on Service tier: an independent pick.
    pressKey("ArrowDown");
    pressKey("ArrowRight");
    pressKey("ArrowDown");
    pressKey("Enter");
    expect(handle.observed.current.modelOptions.serviceTier).toBe("fast");

    // Reopening Context window anchors on its kept choice; restoring the
    // default does not reset the sibling option.
    await act(async () => {
      settingTrigger("contextWindow")!.click();
    });
    expect(settingChoice("contextWindow", "extended")?.getAttribute("aria-selected")).toBe("true");
    pressKey("ArrowUp");
    pressKey("Enter");
    expect(handle.observed.current.modelOptions.contextWindow).toBeUndefined();
    expect(handle.observed.current.modelOptions.serviceTier).toBe("fast");
    expect(settingTrigger("contextWindow")).not.toBeNull();
  });

  it("escape closes only the nested menu; the card stays open", async () => {
    const handle = await mountGptPicker();
    await openSetting("reasoning");
    expect(settingChoice("reasoning", "low")).not.toBeNull();

    pressKey("Escape");
    expect(settingChoice("reasoning", "low")).toBeNull();
    // The card itself is still up (the desktop's escape arm in the
    // setting_menu branch).
    expect(settingTrigger("reasoning")).not.toBeNull();
    expect(handle.observed.current.reasoning).toBeNull();
  });

  it("a model pick closes any open nested menu", async () => {
    await mountGptPicker();
    await openSetting("reasoning");
    expect(settingChoice("reasoning", "low")).not.toBeNull();

    await act(async () => {
      document
        .querySelector<HTMLElement>('.model-list-scroll [data-model-index="0"] .model-row')!
        .click();
    });
    expect(settingChoice("reasoning", "low")).toBeNull();
    expect(settingTrigger("reasoning")).not.toBeNull();
  });
});

// ── The composer's reconciliation owner, mounted ────────────────────────────

interface MountedProbe {
  readonly observed: { current: DraftConfig };
  readonly renders: { count: number };
  render(props: { models: readonly Model[]; harnesses: readonly HarnessDescriptor[] }): void;
  unmount(): void;
}

const probes: MountedProbe[] = [];

afterEach(() => {
  while (probes.length > 0) {
    probes.pop()!.unmount();
  }
});

/**
 * A bare probe running the composer's real reconciliation hook (the owner
 * composer.tsx wires) over controllable model/descriptor rows — the mounted
 * harness for "late descriptor / refresh" reconciliation without pulling the
 * whole composer (and its layout machinery) into jsdom.
 */
function mountProbe(initial: DraftConfig): MountedProbe {
  const observed: { current: DraftConfig } = { current: initial };
  const renders = { count: 0 };
  function Probe(props: { models: readonly Model[]; harnesses: readonly HarnessDescriptor[] }) {
    const [current, setCurrent] = useState(initial);
    useDraftModelReconciliation(props.models, props.harnesses, setCurrent);
    observed.current = current;
    renders.count += 1;
    return null;
  }
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  let props = { models: [] as readonly Model[], harnesses: [] as readonly HarnessDescriptor[] };
  let unmounted = false;
  const handle: MountedProbe = {
    observed,
    renders,
    render(next) {
      props = next;
      act(() => {
        root.render(createElement(Probe, props));
      });
    },
    unmount() {
      if (unmounted) {
        return;
      }
      unmounted = true;
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
  probes.push(handle);
  handle.render(props);
  return handle;
}

describe("useDraftModelReconciliation (the composer's reconciliation owner)", () => {
  it("retains a picked fallback level while the descriptor is pending, and after it lands", async () => {
    // Model arrives BEFORE the descriptor: the empty model list alone must
    // not erase the stored preference; the late descriptor then re-resolves
    // the SAME selection (low is offered by the fallback ladder).
    const probe = mountProbe(draft({ reasoning: "low" }));
    probe.render({ models: [HAIKU], harnesses: [] });
    expect(probe.observed.current).toEqual(draft({ reasoning: "low" }));
    probe.render({ models: [HAIKU], harnesses: [CLAUDE] });
    expect(probe.observed.current.reasoning).toBe("low");
    expect(probe.observed.current.model).toBe("haiku");
  });

  it("heals an absent level to the native default once the descriptor lands", async () => {
    const probe = mountProbe(draft({ reasoning: null }));
    probe.render({ models: [HAIKU], harnesses: [] });
    // No effective ladder yet: the (absent) preference is retained, not
    // replaced by a synthetic value.
    expect(probe.observed.current.reasoning).toBeNull();
    probe.render({ models: [HAIKU], harnesses: [CLAUDE] });
    // The effective ladder resolves to the descriptor's; null heals to High.
    expect(probe.observed.current.reasoning).toBe("high");
  });

  it("clamps against the model's own ladder when it is nonempty", async () => {
    const probe = mountProbe(draft({ model: "opus", reasoning: "medium" }));
    probe.render({ models: [OPUS], harnesses: [{ ...CLAUDE, reasoningLevels: ["medium", "max"] }] });
    // medium is descriptor-only: the model ladder [low, high] wins and the
    // foreign level heals to the model's native default (High).
    expect(probe.observed.current.reasoning).toBe("high");
  });

  it("seeds the harness default model and the effective default reasoning", async () => {
    const probe = mountProbe(draft({ model: null, reasoning: null }));
    probe.render({ models: [HAIKU], harnesses: [CLAUDE] });
    expect(probe.observed.current.model).toBe("haiku");
    expect(probe.observed.current.reasoning).toBe("high");
  });

  it("returns the prior draft on an equivalent refresh — no update loop", async () => {
    const probe = mountProbe(draft({ reasoning: "low" }));
    probe.render({ models: [HAIKU], harnesses: [CLAUDE] });
    const settled = probe.observed.current;
    const settledRenders = probe.renders.count;
    // Fresh identities, equal content: the effect re-runs but the draft is
    // returned unchanged (identity), so React bails — exactly one render for
    // the rerender itself, no cascade.
    probe.render({ models: [{ ...HAIKU }], harnesses: [{ ...CLAUDE }] });
    expect(probe.observed.current).toBe(settled);
    expect(probe.renders.count).toBe(settledRenders + 1);
  });

  it("composer.tsx wires this owner with both live inputs", () => {
    // The wiring pin (the dock-glide suite's idiom): the reconciliation
    // logic above is the code the composer actually runs — a reverted
    // model-only effect cannot pass this suite unnoticed.
    // (cwd is the app package root under vitest; jsdom rewrites import.meta.url)
    const source = readFileSync(join(process.cwd(), "src/components/composer.tsx"), "utf8");
    expect(source).toContain("useDraftModelReconciliation(models.rows, harnesses.rows, setDraft)");
  });
});

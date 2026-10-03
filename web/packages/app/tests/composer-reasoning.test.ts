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
 *
 * Ticket 08 — the traits tray's choices ride `NestedMenu` (ticket 01's
 * primitive): a flyout PORTALED to the body on desktop (bug 9's fix — the
 * inline expansion lived inside the card's clip box), the in-sheet
 * drill-down on phone (ticket 15's pattern). The trigger row's press and
 * the keyboard walk behave exactly as before; only where the choices
 * paint changed.
 *
 * Ticket 15 folds the phone arm in: the same mounted picker under a
 * controllable `(max-width: 768px)` matchMedia — the card opens as the
 * bottom sheet (PickerCard's phone arm) and the traits tray's setting
 * rows ride `NestedMenu`'s phone arm: the choices DRILL DOWN in place
 * under a back header inside the sheet, never a side flyout.
 */

import { act, createElement, useState } from "react";
import { createRoot } from "react-dom/client";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { ChatConfig, HarnessDescriptor, Model } from "@roboco/proto";
import type { DraftConfig } from "../src/lib/composer-actions";
import { ComposerPickers } from "../src/components/composer-pickers";
import { composerDefaults, rememberedReasoningFor } from "../src/lib/composer-draft";
import { useDraftModelReconciliation } from "../src/lib/composer-reconciliation";
import { PickerCatalog } from "../src/state/picker-catalog";
import { uiSettings } from "../src/state/ui-settings";
import { emitShortcut } from "../src/state/shortcuts";

// ── jsdom gaps the mounted card hits ────────────────────────────────────────
// matchMedia (useIsPhone in PickerCard/NestedMenu), ResizeObserver
// (MenuScrollbar), scrollIntoView (the cursor list's scroll effect /
// anchorCursor). The matchMedia answer is controllable so the phone arm
// can be armed per test (ticket 15).

/** The useIsPhone answer for every mount in this file (PHONE_QUERY match). */
let phoneMode = false;

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  // The compact model picker is on by default (upstream #471); this suite's
  // identity-card tests exercise the opt-out presentation, so start it off.
  uiSettings.updateImmediate({ compactModelPicker: false });
  window.matchMedia = ((query: string) => ({
    // `(max-width: 768px)` matches in phone mode; `(min-width: 769px)` in
    // desktop mode — the exact pair `state/media.ts` derives from the one
    // breakpoint, so the two queries can never disagree.
    matches: query.startsWith("(max-width") === phoneMode,
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
  /** Ticket 02's hook: the NEXT `ListModels` for this harness rejects once —
   *  a refresh failure after a successful load (the stale-rows state). */
  failNextModels: string | null = null;
  /** Every `ListModels` call's harness, in order (the re-force probe). */
  readonly listModelsCalls: string[] = [];

  async call<T>(method: string, params?: unknown): Promise<T> {
    if (method === "ListHarnesses") {
      return this.harnesses as unknown as T;
    }
    if (method === "ListModels") {
      const harness = (params as { harness: string }).harness;
      this.listModelsCalls.push(harness);
      if (this.failNextModels === harness) {
        this.failNextModels = null;
        throw new Error("model refresh failed");
      }
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
  canInstall: false,
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
  canInstall: false,
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


describe("ComposerPickers compact model card (upstream #471)", () => {
  // The compact presentation replaces the identity card while the General
  // setting is on: the panel page drives effort and fast, the models page
  // picks, the provider page switches. This block re-arms the setting; the
  // file's beforeAll turned it off for the identity-card suites above.
  beforeEach(() => {
    uiSettings.updateImmediate({ compactModelPicker: true });
  });
  afterEach(() => {
    uiSettings.updateImmediate({ compactModelPicker: false });
  });

  it("the panel names the model, drives the effort, and the list picks", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE, BARE];
    client.modelsByHarness.set("claude-code", [HAIKU, OPUS]);
    client.modelsByHarness.set("codex", [
      { id: "gpt", label: "GPT", description: null, reasoningLevels: [], options: [] },
    ]);
    const handle = mountPicker({ client, initial: draft({ model: "haiku", reasoning: "medium" }) });
    await flush();
    await openCard(handle);

    // The panel shows the selected model's name, and the descriptor-backed
    // ladder drives the slider's labels (Haiku's own list is empty).
    const panel = document.querySelector(".compact-panel");
    expect(panel).not.toBeNull();
    expect(panel!.querySelector(".compact-model-name")?.textContent).toContain("Haiku 4.5");
    const labels = Array.from(panel!.querySelectorAll(".compact-effort-label")).map(
      (el) => el.textContent ?? "",
    );
    expect(labels).toEqual(["Low", "Medium", "High"]);
    // The slider owns the ladder: no Reasoning row, only Haiku's thinking
    // option (26px rows, the compact tray).
    expect(
      Array.from(panel!.querySelectorAll(".compact-options .menu-row-label")).map(
        (el) => el.textContent ?? "",
      ),
    ).toEqual(["Thinking"]);

    // Home picks the lowest level through the real key path.
    pressKey("Home");
    expect(handle.observed.current.reasoning).toBe("low");
    // Right steps up one advertised level.
    pressKey("ArrowRight");
    expect(handle.observed.current.reasoning).toBe("medium");
    // The pick remembered the level for THIS model.
    expect(rememberedReasoningFor("claude-code", "haiku")).toBe("medium");

    // Down opens the model list on the row beside the selected one; Enter
    // picks it and lands back on the panel.
    pressKey("ArrowDown");
    expect(document.querySelector(".compact-list-page")).not.toBeNull();
    pressKey("Enter");
    expect(document.querySelector(".compact-list-page")).toBeNull();
    expect(handle.observed.current.model).toBe("opus");
    // A model with its own ladder keeps its own remembered level: haiku
    // remembered medium, opus starts at the native default.
    expect(handle.observed.current.reasoning).toBe("high");
  });

  it("the provider page lists Starred first and picks the provider's last model", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE, BARE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    client.modelsByHarness.set("codex", [OPUS]);
    const handle = mountPicker({ client, initial: draft({ model: "haiku" }) });
    await flush();
    await openCard(handle);

    await act(async () => {
      document.querySelector<HTMLElement>(".compact-provider")!.click();
    });
    expect(document.querySelector(".compact-list-page")).not.toBeNull();
    const names = Array.from(document.querySelectorAll(".compact-provider-name")).map(
      (el) => el.textContent ?? "",
    );
    expect(names).toEqual(["Claude", "Codex"]);

    await act(async () => {
      const codexRow = Array.from(document.querySelectorAll<HTMLElement>(".compact-provider-row")).find(
        (el) => el.textContent?.includes("Codex"),
      );
      codexRow!.click();
    });
    expect(handle.observed.current.harness).toBe("codex");
    // A pick lands back on the panel.
    expect(document.querySelector(".compact-list-page")).toBeNull();
    expect(document.querySelector(".compact-panel")).not.toBeNull();
  });

  it("Tab on the panel cycles providers in place", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE, BARE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    client.modelsByHarness.set("codex", [OPUS]);
    const handle = mountPicker({ client, initial: draft({ model: "haiku" }) });
    await flush();
    await openCard(handle);

    pressKey("Tab");
    expect(handle.observed.current.harness).toBe("codex");
    pressKey("Shift+Tab");
    expect(handle.observed.current.harness).toBe("claude-code");
  });
});

// ── Ticket 02: picker list truth — the refresh-retry row + selected_only ─────

describe("ComposerPickers picker list truth (ticket 02)", () => {
  it("keeps stale rows with the refresh-retry row between search and list; Retry re-forces the fetch", async () => {
    // A refresh failure on a loaded slot must NOT blank the list (the
    // desktop's `model_refresh_errors` row, pickers.rs:4056-4075): the stale
    // rows stay, the retry row renders BETWEEN the search row and the list
    // band — outside the virtualizer's 216px host — and its Retry click
    // re-forces the fetch so the fresh catalog heals the error in place.
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU, OPUS]);
    const handle = mountPicker({ client, initial: draft({ model: "haiku" }) });
    await flush();
    await openCard(handle);
    expect(document.querySelectorAll(".model-list-sizer .model-row-item")).toHaveLength(2);
    expect(document.querySelector(".error-row")).toBeNull();

    client.failNextModels = "claude-code";
    await act(async () => {
      await handle.catalog.loadModels("claude-code", { force: true });
    });
    await flush();

    // The stale rows persist — the empty-state takeover never fires.
    expect(document.querySelectorAll(".model-list-sizer .model-row-item")).toHaveLength(2);
    expect(document.querySelector(".menu-scroll-fallback")).toBeNull();
    // The retry row renders with the error message…
    const errorRow = document.querySelector<HTMLElement>(".error-row");
    expect(errorRow).not.toBeNull();
    expect(errorRow!.textContent).toContain("model refresh failed");
    // …directly between the search row and the list host, OUTSIDE the list
    // band so the virtualizer is untouched (the card grows ~28px instead).
    expect(errorRow!.previousElementSibling?.classList.contains("model-search-row")).toBe(true);
    expect(errorRow!.nextElementSibling?.id).toBe("model-list-scroll-host");
    expect(errorRow!.closest("#model-list-scroll-host")).toBeNull();

    const callsBefore = client.listModelsCalls.length;
    await act(async () => {
      errorRow!.querySelector<HTMLElement>(".error-row-retry")!.click();
    });
    await flush();
    // The Retry re-forced the fetch and the fresh catalog landed: the error
    // cleared while the rows stayed.
    expect(client.listModelsCalls.length).toBeGreaterThan(callsBefore);
    expect(document.querySelector(".error-row")).toBeNull();
    expect(document.querySelectorAll(".model-list-sizer .model-row-item")).toHaveLength(2);
    expect(handle.observed.current.model).toBe("haiku");
  });

  describe("compact arm", () => {
    beforeEach(() => {
      uiSettings.updateImmediate({ compactModelPicker: true });
    });
    afterEach(() => {
      uiSettings.updateImmediate({ compactModelPicker: false });
    });

    it("the compact models page keeps its rows on a refresh failure and shows the retry row at the top", async () => {
      // The compact `modelsFor` dropped stale rows on a slot error — the
      // models page went blank. It must keep them and show the same retry
      // row at the top of the page (under the header, above the list band).
      const client = new FakeClient();
      client.harnesses = [CLAUDE];
      client.modelsByHarness.set("claude-code", [HAIKU, OPUS]);
      const handle = mountPicker({ client, initial: draft({ model: "haiku" }) });
      await flush();
      await openCard(handle);
      await act(async () => {
        document.querySelector<HTMLElement>(".compact-model")!.click();
      });
      expect(document.querySelector(".compact-list-page")).not.toBeNull();
      expect(document.querySelectorAll(".model-list-sizer .model-row-item")).toHaveLength(2);

      client.failNextModels = "claude-code";
      await act(async () => {
        await handle.catalog.loadModels("claude-code", { force: true });
      });
      await flush();

      // Rows kept (the old error arm dropped them), retry row on top.
      expect(document.querySelectorAll(".model-list-sizer .model-row-item")).toHaveLength(2);
      const errorRow = document.querySelector<HTMLElement>(".error-row");
      expect(errorRow).not.toBeNull();
      expect(errorRow!.textContent).toContain("model refresh failed");
      expect(errorRow!.previousElementSibling?.classList.contains("compact-list-header")).toBe(true);
      expect(errorRow!.nextElementSibling?.classList.contains("compact-list-host")).toBe(true);
      expect(errorRow!.closest(".compact-list-host")).toBeNull();
    });
  });

  it("renders the selected-absent row: unclickable, anchored at 0, starless — the chip keeps the remembered label", async () => {
    // A chat whose model is absent from the fresh catalog: the harness tab
    // unshifts the synthetic `selected_only` row at index 0 (pickers
    // 1996-2033) — unclickable (2085-2087), the anchored selected row, star
    // suppressed — while the chip names the remembered label, never the
    // first catalog row's.
    resetDefaults();
    composerDefaults.update({ modelLabels: { "gone-model": "Gone model" } });
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const chat: ChatConfig = {
      harness: "claude-code",
      model: "gone-model",
      reasoning: "high",
      modelOptions: {},
      sandbox: "workspace-write",
    };
    const handle = mountPicker({
      client,
      initial: draft({ model: "gone-model", reasoning: "high" }),
      chatConfig: chat,
    });
    await flush();
    await openCard(handle);

    // Row 0: the remembered label, the verbatim description in the
    // attribution slot, and NO star.
    const row0 = document.querySelector<HTMLElement>('.model-list-scroll [data-model-index="0"]');
    expect(row0).not.toBeNull();
    expect(row0!.querySelector(".model-row-label")?.textContent).toBe("Gone model");
    expect(row0!.querySelector(".model-row-attribution")?.textContent).toBe(
      "Selected in this chat; absent from the current model list",
    );
    expect(row0!.querySelector(".model-row-star")).toBeNull();
    // The catalog row keeps its star.
    const row1 = document.querySelector<HTMLElement>('[data-model-index="1"]');
    expect(row1!.querySelector(".model-row-star")).not.toBeNull();

    // Row 0 is the anchored SELECTED row; the fallback catalog row never
    // paints as the selection.
    expect(row0!.querySelector(".model-row")!.classList.contains("model-row-selected")).toBe(true);
    expect(row0!.querySelector(".model-row")!.getAttribute("aria-selected")).toBe("true");
    expect(row1!.querySelector(".model-row")!.classList.contains("model-row-selected")).toBe(false);

    // Clicking (and Enter on the anchored cursor) does not pick — the
    // synthetic row is a no-op, never a new choice.
    await act(async () => {
      row0!.querySelector<HTMLElement>(".model-row")!.click();
    });
    pressKey("Enter");
    expect(handle.drafts).toHaveLength(0);
    expect(handle.persists).toHaveLength(0);
    expect(handle.observed.current.model).toBe("gone-model");

    // The chip names the remembered pick — never "Haiku 4.5".
    expect(document.querySelector("#picker-model .identity-chip-model")?.textContent).toBe(
      "Gone model",
    );
  });
});

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  document.body.replaceChildren();
  // The suite's default layer is the desktop arm; phone tests re-arm it.
  phoneMode = false;
});

function mountPicker(options: {
  client: FakeClient;
  initial: DraftConfig;
  chatConfig?: ChatConfig | null;
  /** The unsaved-side-chat harness window (upstream #590); default false. */
  sideChatHarnessEditable?: boolean;
  /** Mount under the phone arm (≤768px) — the drawer sheet + drill-downs. */
  phone?: boolean;
}): MountedPicker {
  phoneMode = options.phone ?? false;
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
      sideChatHarnessEditable: options.sideChatHarnessEditable ?? false,
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
  // The choices ride the NESTED menu (ticket 08): portaled to the body on
  // desktop (bug 9's fix — the old inline `.model-setting-choices` lived
  // inside the card's overflow: hidden clip box), drilled in the sheet on
  // phone. Either way the document, not the card's portal tree, owns them;
  // the row keys stay `setting-choice-<group>-<level>`.
  return document.querySelector<HTMLElement>(
    `[data-rb-row-key="setting-choice-reasoning-${level}"]`,
  );
}

function traitTriggers(): string[] {
  return Array.from(document.querySelectorAll(".model-traits .model-setting-row .menu-row-label")).map(
    (el) => el.textContent ?? "",
  );
}

function settingTrigger(id: string): HTMLElement | null {
  // The trigger rows stay inside the card's own portal (the traits tray).
  return document.querySelector<HTMLElement>(
    `.model-traits [data-rb-row-key="model-setting-${id}"]`,
  );
}

function settingChoice(id: string, value: string): HTMLElement | null {
  // The choices ride the nested flyout's body portal (desktop) or the
  // drill body (phone) — document-wide, like `reasoningRow`.
  return document.querySelector<HTMLElement>(
    `[data-rb-row-key="setting-choice-${id}-${value}"]`,
  );
}

/** The nested flyout's portaled card (desktop arm only; phone = the drill). */
function nestedFlyout(): HTMLElement | null {
  // The parent identity card is `.rb-popover-popup … identity-card`; the
  // nested flyout is a separate `.rb-popover-popup` without that class.
  return Array.from(document.querySelectorAll<HTMLElement>(".rb-popover-popup")).find(
    (el) => !el.classList.contains("identity-card"),
  ) ?? null;
}

/** Click a settings trigger open (the nested menu opens: flyout on desktop,
 *  drill-down on phone). */
async function openSetting(id: string): Promise<void> {
  await act(async () => {
    settingTrigger(id)!.click();
  });
}

/** The card-level keydown path (the capture-phase window listener). Each key
 *  flushes React so the next key runs against the re-armed listener. */
function pressKey(key: string): void {
  // A "Shift+X" prefix sets shiftKey (the compact card's Tab cycling).
  const shift = key.startsWith("Shift+");
  const raw = shift ? key.slice(6) : key;
  act(() => {
    window.dispatchEvent(
      new KeyboardEvent("keydown", { key: raw, shiftKey: shift, bubbles: true, cancelable: true }),
    );
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

// ── Harness facet on new side chats (upstream #590) ─────────────────────────

/** The parent-model row: the side chat's inherited claude-code catalog. */
const PARENT: Model = {
  id: "parent-model",
  label: "Parent model",
  description: null,
  reasoningLevels: ["low", "medium", "high"],
  options: [],
};

/** An inherited side-chat config (the local row's, before any pick). */
const INHERITED: ChatConfig = {
  harness: "claude-code",
  model: "parent-model",
  reasoning: "high",
  modelOptions: { context: "1m" },
  sandbox: "read-only",
};

/** Reset the sticky picks the side-chat pick reads (deterministic mounts). */
function resetDefaults(): void {
  composerDefaults.update({
    harness: null,
    modelByHarness: {},
    reasoning: null,
    modelOptionsByModel: {},
    favorites: [],
  });
}

/** A harness rail tab by index (0 = claude, 1 = codex; favorites excluded). */
function harnessTab(ix: number): HTMLElement | null {
  const tabs = Array.from(document.querySelectorAll<HTMLElement>(".model-tab")).filter(
    (el) => el.id !== "model-tab-favorites",
  );
  return tabs[ix] ?? null;
}

function mountSideChatPicker(sideChatHarnessEditable: boolean): MountedPicker {
  const client = new FakeClient();
  client.harnesses = [CLAUDE, BARE];
  client.modelsByHarness.set("claude-code", [PARENT]);
  // Codex's list is deliberately NOT seeded: the fake resolves it empty,
  // the pre-catalog window the desktop's pick must survive.
  return mountPicker({
    client,
    initial: draft({
      harness: "claude-code",
      model: "parent-model",
      reasoning: "high",
      modelOptions: { context: "1m" },
      sandbox: "read-only",
    }),
    chatConfig: INHERITED,
    sideChatHarnessEditable,
  });
}

describe("ComposerPickers harness facet on new side chats (upstream #590)", () => {
  it("an unsaved side chat can pick another harness before its catalog loads — only the draft moves", async () => {
    resetDefaults();
    const handle = mountSideChatPicker(true);
    await flush();
    await openCard(handle);

    // The rail offers every harness (rail_descriptors, pickers.rs): the
    // codex tab is present and NOT locked.
    const codexTab = harnessTab(1);
    expect(codexTab).not.toBeNull();
    expect(codexTab!.classList.contains("model-tab-locked")).toBe(false);

    await act(async () => {
      codexTab!.click();
    });
    expect(handle.drafts).toHaveLength(1);
    const picked = handle.drafts[0]!;
    // Only the harness swapped in name: the inherited provider settings are
    // replaced with the new harness's remembered picks — none remembered,
    // so model/reasoning null and options empty — while the inherited
    // sandbox is preserved (the desktop's `update_chat_config` copy).
    expect(picked.harness).toBe("codex");
    expect(picked.model).toBeNull();
    expect(picked.reasoning).toBeNull();
    expect(picked.modelOptions).toEqual({});
    expect(picked.sandbox).toBe("read-only");
    // The pickers hand the picked config to the persist path (the
    // established-chat contract — `chatConfig` is non-null on the inherited
    // row); the composer's `persistDraft` is what drops it while unsaved —
    // the desktop's "until then all choices stay local" early return.
    expect(handle.persists).toHaveLength(1);
    expect(handle.persists[0]!.harness).toBe("codex");
    expect(handle.observed.current.harness).toBe("codex");
  });

  it("seeds the remembered model, level and option picks for the new harness", async () => {
    resetDefaults();
    composerDefaults.update({
      modelByHarness: { codex: { id: "codex-model", label: "Codex model" } },
      reasoning: "low",
      modelOptionsByModel: { "codex/codex-model": { serviceTier: "fast" } },
    });
    const handle = mountSideChatPicker(true);
    await flush();
    await openCard(handle);

    await act(async () => {
      harnessTab(1)!.click();
    });
    const picked = handle.drafts[0]!;
    expect(picked.harness).toBe("codex");
    // The remembered model rides even though the codex catalog is still
    // empty (the desktop stamps `defaults.model_for(harness)` — "still send
    // the id we know"), with its remembered options and level.
    expect(picked.model).toBe("codex-model");
    expect(picked.reasoning).toBe("low");
    expect(picked.modelOptions).toEqual({ serviceTier: "fast" });
  });

  it("re-picking the inherited harness is a no-op; the lock re-engages once saved or in flight", async () => {
    resetDefaults();
    const handle = mountSideChatPicker(true);
    await flush();
    await openCard(handle);

    // The inherited harness is already effective — the desktop's
    // `effective_harness != Some(harness)` guard makes this a no-op.
    await act(async () => {
      harnessTab(0)!.click();
    });
    expect(handle.drafts).toHaveLength(0);
    expect(handle.observed.current.harness).toBe("claude-code");
    // One picker at a time: the portaled card's tabs would otherwise answer
    // the next mount's `harnessTab` queries.
    handle.unmount();

    // Saved (or the first send in flight): the inherited config locks the
    // facet again — the desktop's `side_chat_harness_editable` is false for
    // forks and pending sends alike.
    const locked = mountSideChatPicker(false);
    await flush();
    await openCard(locked);
    const lockedTab = harnessTab(1);
    expect(lockedTab).not.toBeNull();
    expect(lockedTab!.classList.contains("model-tab-locked")).toBe(true);
    await act(async () => {
      lockedTab!.click();
    });
    expect(locked.drafts).toHaveLength(0);
    expect(locked.observed.current.harness).toBe("claude-code");
  });

  it("composer.tsx wires the unsaved side-chat window into the pickers", () => {
    // The wiring pin (the reconciliation suite's idiom): the editable flag
    // is the desktop's `side_chat_harness_editable` — unsaved, and dark
    // while the first send (uploads included) is in flight — and
    // `persistDraft` keeps dropping setChatConfig while the chat is unsaved
    // (the desktop's update_chat_config early return, upstream #590).
    const source = readFileSync(join(process.cwd(), "src/components/composer.tsx"), "utf8");
    expect(source).toContain("sideChatHarnessEditable={isUnsavedSideChat(chat.id) && !busy}");
    expect(source).toContain('if (chat.id !== "" && isUnsavedSideChat(chat.id)) {');
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

  it("the open-model-picker shortcut opens the card and never closes it", async () => {
    // Upstream faac7432 + 9abe0167: OpenModelPicker routes to the composer's
    // picker (open_model_menu) — open only, so a second press does not
    // toggle — and the mount transfers focus into the search input even
    // though the press landed on the host's editor focus.
    const client = new FakeClient();
    client.harnesses = [BARE];
    client.modelsByHarness.set("codex", [GPT]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "gpt-5.4", reasoning: null }),
    });
    await flush();
    // Card closed: the trigger click never happened.
    expect(settingTrigger("reasoning")).toBeNull();

    await act(async () => {
      emitShortcut("open-model-picker");
    });
    await flush();
    expect(settingTrigger("reasoning")).not.toBeNull();
    // Keyboard focus follows the mount: the search input owns it, so down/
    // enter route to the picker (the 9abe0167 regression).
    const input = document.querySelector<HTMLInputElement>(".model-search-row input");
    expect(input).not.toBeNull();
    expect(document.activeElement).toBe(input);

    await act(async () => {
      emitShortcut("open-model-picker");
    });
    await flush();
    expect(settingTrigger("reasoning")).not.toBeNull();
    expect(handle.observed.current.model).toBe("gpt-5.4");
  });
});

// ── Ticket 08: the traits tray's nested flyout (bug 9) ──────────────────────

/** A real press pair on `target`: pointerdown (marks the press) then click. */
function press(target: HTMLElement): void {
  act(() => {
    target.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, button: 0 }));
    target.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }));
  });
}

describe("ComposerPickers traits tray nested menu (ticket 08)", () => {
  it("portals the choices beside the trigger — outside the card's clip box, with the group heading", async () => {
    // Bug 9's fix: the old inline `.model-setting-choices` lived inside the
    // card's `overflow: hidden` popup — the same clip that swallowed bug
    // 5b's view submenu. The choices now ride the portaled nested flyout
    // (`NestedMenu`, the desktop's `popover::nested_menu`, pickers.rs:4089).
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const handle = mountPicker({ client, initial: draft() });
    await flush();
    await openCard(handle);
    await openSetting("reasoning");

    const flyout = nestedFlyout();
    expect(flyout).not.toBeNull();
    // Portaled to the body — NOT inside the identity card's own portal tree.
    expect(flyout!.closest(".rb-popover-popup.identity-card")).toBeNull();
    expect(document.body.contains(flyout!)).toBe(true);
    // The choices live in the flyout, never in the tray's inline DOM.
    expect(
      flyout!.querySelector('[data-rb-row-key="setting-choice-reasoning-low"]'),
    ).not.toBeNull();
    expect(document.querySelector(".model-traits .model-setting-choice-row")).toBeNull();
    // The flyout's heading — the desktop's `menu_heading` (pickers.rs:4036).
    expect(flyout!.querySelector(".menu-heading")?.textContent).toBe("Reasoning");
    // The trigger row keeps its summary (the ticket's invariant): label,
    // current value, chevron — and the expanded state Base UI merges on.
    const trigger = settingTrigger("reasoning")!;
    expect(trigger.querySelector(".menu-row-label")?.textContent).toBe("Reasoning");
    expect(trigger.querySelector(".model-setting-value")?.textContent).toBe("Low");
    expect(trigger.querySelector(".model-setting-chevron")).not.toBeNull();
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
  });

  it("selecting from the flyout applies and dismisses it — the card stays open for multi-adjust", async () => {
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
    expect(nestedFlyout()).toBeNull();
    expect(settingTrigger("reasoning")).not.toBeNull();
    // The trigger's summary now carries the picked value.
    expect(
      settingTrigger("reasoning")!.querySelector(".model-setting-value")?.textContent,
    ).toBe("High");
  });

  it("a press inside the parent card but outside the flyout closes just the nested menu", async () => {
    // The desktop's `on_mouse_down_out` arm (pickers.rs:3949-3962): the
    // trigger dismisses its own child; elsewhere in the parent, close the
    // child and let that control receive the same click.
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const handle = mountPicker({ client, initial: draft() });
    await flush();
    await openCard(handle);
    await openSetting("reasoning");
    expect(nestedFlyout()).not.toBeNull();
    // The parent card's search row: inside the card, outside the flyout.
    press(document.querySelector<HTMLInputElement>(".model-search-row input")!);
    expect(nestedFlyout()).toBeNull();
    expect(settingTrigger("reasoning")).not.toBeNull();
    expect(handle.observed.current.reasoning).toBe("low");
  });
});

// ── Ticket 08, phone arm: the traits tray drills in the sheet (ticket 15) ────

describe("ComposerPickers traits tray on phone (ticket 15's drill-down)", () => {
  it("drills the choices in the sheet — never a flyout — and the back header closes", async () => {
    const client = new FakeClient();
    client.harnesses = [CLAUDE];
    client.modelsByHarness.set("claude-code", [HAIKU]);
    const handle = mountPicker({ client, initial: draft(), phone: true });
    await flush();
    await openCard(handle);
    await openSetting("reasoning");

    // The drill-down, not a flyout: the phone arm renders plain DOM in the
    // sheet under the row — no Base UI popover mounts for the choices.
    expect(nestedFlyout()).toBeNull();
    const drill = document.querySelector<HTMLElement>(".rb-submenu-drill");
    expect(drill).not.toBeNull();
    const header = drill!.querySelector<HTMLElement>(".rb-submenu-drill-header");
    expect(header).not.toBeNull();
    // The back affordance carries the group's name (ticket 15's pattern).
    expect(header!.textContent).toContain("Reasoning");
    expect(
      drill!.querySelector('[data-rb-row-key="setting-choice-reasoning-high"]'),
    ).not.toBeNull();
    expect(settingTrigger("reasoning")!.getAttribute("aria-expanded")).toBe("true");

    // A pick applies through the drill and collapses it; the sheet stays.
    await act(async () => {
      reasoningRow("high")!.click();
    });
    expect(handle.observed.current.reasoning).toBe("high");
    expect(document.querySelector(".rb-submenu-drill")).toBeNull();
    expect(
      settingTrigger("reasoning")!.querySelector(".model-setting-value")?.textContent,
    ).toBe("High");

    // Reopen: the back header closes the drill without picking.
    await openSetting("reasoning");
    await act(async () => {
      document.querySelector<HTMLElement>(".rb-submenu-drill-header")!.click();
    });
    expect(document.querySelector(".rb-submenu-drill")).toBeNull();
    expect(handle.observed.current.reasoning).toBe("high");
  });
});

// ── Ticket 15: the phone arm — the traits tray drills down inside the sheet ─

describe("ComposerPickers phone arm (ticket 15)", () => {
  async function mountGptPickerAtPhone(): Promise<MountedPicker> {
    const client = new FakeClient();
    client.harnesses = [BARE];
    client.modelsByHarness.set("codex", [GPT]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "gpt-5.4", reasoning: null }),
      phone: true,
    });
    await flush();
    await openCard(handle);
    return handle;
  }

  it("the card opens as the bottom sheet — the traits tray renders inside it", async () => {
    await mountGptPickerAtPhone();
    const sheet = document.querySelector<HTMLElement>(".rb-drawer-card");
    expect(sheet).not.toBeNull();
    expect(sheet!.hasAttribute("data-open")).toBe(true);
    expect(sheet!.contains(settingTrigger("reasoning")!)).toBe(true);
    // The floating card never mounts at phone.
    expect(document.querySelector(".rb-popover-popup")).toBeNull();
    expect(traitTriggers()).toEqual(["Reasoning", "Context window", "Service tier"]);
  });

  it("a setting row drills its choices down in place under a back header — never the inline expansion", async () => {
    await mountGptPickerAtPhone();
    await openSetting("reasoning");
    const drill = document.querySelector<HTMLElement>(".rb-submenu-drill");
    expect(drill).not.toBeNull();
    // In place, inside the sheet — not the old inline position directly
    // under the tray's own section (ticket 08 repurposed
    // `.model-setting-choices` into the choices region the drill
    // carries), not any portaled flyout.
    expect(document.querySelector(".rb-drawer-card")!.contains(drill!)).toBe(true);
    expect(document.querySelector(".model-traits-section > .model-setting-choices")).toBeNull();
    expect(drill!.querySelector(".model-setting-choices")).not.toBeNull();
    expect(document.querySelector(".rb-popover-popup")).toBeNull();
    // The back affordance: the header reads the group's name, the choices
    // ride the drill body.
    expect(drill!.querySelector(".rb-submenu-drill-header")!.textContent).toContain("Reasoning");
    expect(reasoningRow("low")).not.toBeNull();
    expect(settingTrigger("reasoning")!.getAttribute("aria-expanded")).toBe("true");
  });

  it("the back header closes the drill; the sheet stays up", async () => {
    await mountGptPickerAtPhone();
    await openSetting("reasoning");
    await act(async () => {
      document
        .querySelector<HTMLElement>(".rb-submenu-drill .rb-submenu-drill-header")!
        .click();
    });
    expect(document.querySelector(".rb-submenu-drill")).toBeNull();
    expect(reasoningRow("low")).toBeNull();
    expect(settingTrigger("reasoning")!.getAttribute("aria-expanded")).toBe("false");
    // The sheet itself is still up — the drill is a local dismissal.
    expect(document.querySelector(".rb-drawer-card")!.hasAttribute("data-open")).toBe(true);
  });

  it("a choice pick applies through the draft and closes the drill (multi-adjust keeps the sheet)", async () => {
    const handle = await mountGptPickerAtPhone();
    await openSetting("reasoning");
    await act(async () => {
      reasoningRow("high")!.click();
    });
    expect(handle.observed.current.reasoning).toBe("high");
    // The nested menu closes on the pick (activate_setting_choice); the
    // sheet stays open for the next adjustment.
    expect(document.querySelector(".rb-submenu-drill")).toBeNull();
    expect(document.querySelector(".rb-drawer-card")!.hasAttribute("data-open")).toBe(true);
  });

  it("desktop regression: the same picker at ≥769px portals the choices — no drill, no sheet", async () => {
    // The desktop arm stays ticket 08's: the choices fly out through the
    // portaled nested menu beside the row — never the phone drill, never
    // the sheet.
    const client = new FakeClient();
    client.harnesses = [BARE];
    client.modelsByHarness.set("codex", [GPT]);
    const handle = mountPicker({
      client,
      initial: draft({ harness: "codex", model: "gpt-5.4", reasoning: null }),
    });
    await flush();
    await openCard(handle);
    expect(document.querySelector(".rb-drawer-card")).toBeNull();
    await openSetting("reasoning");
    expect(document.querySelector(".rb-submenu-drill")).toBeNull();
    const flyout = nestedFlyout();
    expect(flyout).not.toBeNull();
    expect(flyout!.querySelector(".model-setting-choices")).not.toBeNull();
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

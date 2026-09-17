import { describe, expect, it } from "vitest";
import {
  RightPaneStore,
  panelKey,
  pushUniqueRightSurface,
  resolvedActive,
  surfaceKey,
  workspaceFileTitle,
  type RightSurface,
} from "../src/state/right-pane";
import { dropIndex, slideOffset } from "../src/components/right-tab-strip";

/**
 * The right pane's surface model, against the desktop's (`shell.rs:457-532`,
 * `:1901-1922`, `:2284-3004`). Tests mirror the Rust ones by name; each gets
 * a fresh store so the per-chat maps never leak between cases.
 */

function fresh(): RightPaneStore {
  return new RightPaneStore();
}

describe("panel keys", () => {
  it("keys the new-chat canvas per space", () => {
    // `panel_key()` — one shared key made a canvas toggle read as global
    // state across unrelated spaces (user report, `shell.rs:1901-1913`).
    expect(panelKey("chat-1", "sp-a")).toBe("chat-1");
    expect(panelKey(null, "sp-a")).toBe("space-canvas:sp-a");
    expect(panelKey(null, "sp-b")).toBe("space-canvas:sp-b");
  });
});

describe("session_panels_default_closed_per_chat", () => {
  it("starts every chat closed, unexpanded, on the picker, with no tabs", () => {
    const store = fresh();
    for (const chatId of ["chat-1", "chat-2"]) {
      const pane = store.stateFor(chatId);
      expect(pane.open).toBe(false);
      expect(pane.expanded).toBe(false);
      expect(pane.tabs).toEqual([]);
      expect(pane.active).toEqual({ kind: "picker" });
      expect(resolvedActive(pane)).toEqual({ kind: "picker" });
    }
  });
});

describe("session_panels_flags_are_chat_scoped", () => {
  it("opening one chat's pane leaves every other chat closed", () => {
    const store = fresh();
    store.toggle("chat-1");
    expect(store.stateFor("chat-1").open).toBe(true);
    expect(store.stateFor("chat-2").open).toBe(false);
  });
});

describe("session_panels_both_flags_coexist_per_chat", () => {
  it("open and expanded ride together on one chat without crossing chats", () => {
    const store = fresh();
    store.toggle("chat-1");
    store.toggleExpanded("chat-1");
    const pane = store.stateFor("chat-1");
    expect(pane.open).toBe(true);
    expect(pane.expanded).toBe(true);
    // Closing always leaves takeover mode (`toggle_right_pane`, P19) —
    // reopening after a takeover close lands in normal mode.
    store.toggle("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, expanded: false });
    store.toggle("chat-1");
    expect(store.stateFor("chat-1").expanded).toBe(false);
    // The other chat never saw any of it.
    expect(store.stateFor("chat-2")).toMatchObject({ open: false, expanded: false });
  });
});

describe("session_panels_update_tracks_right_surfaces", () => {
  it("resolvedActive follows the live tab list and falls back to the picker", () => {
    const store = fresh();
    store.addFilesSurface("chat-1");
    store.addTerminalSurface("chat-1");
    // Terminal is single-instance in the pane but still a tab.
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual({ kind: "terminal", id: "t1" });

    // The stored pick goes stale when its tab closes — never render a dead
    // surface; the first remaining tab wins.
    store.closeSurface("chat-1", { kind: "terminal", id: "t1" });
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual({ kind: "files" });

    // Emptied, the pane lands on the picker — it does not close.
    store.closeSurface("chat-1", { kind: "files" });
    const pane = store.stateFor("chat-1");
    expect(pane.open).toBe(true);
    expect(resolvedActive(pane)).toEqual({ kind: "picker" });
  });
});

describe("files_surface_is_single_instance_per_tab_list", () => {
  it("repeat opens focus the one Files tab instead of adding a second", () => {
    const store = fresh();
    store.addFilesSurface("chat-1");
    store.addDiffSurface("chat-1", "diff");
    store.addFilesSurface("chat-1");
    const pane = store.stateFor("chat-1");
    expect(pane.tabs.filter((tab) => tab.kind === "files")).toHaveLength(1);
    expect(resolvedActive(pane)).toEqual({ kind: "files" });
  });
});

describe("file_editors_are_distinct_surface_tabs_with_stable_titles", () => {
  it("one tab per path, basename titles, ids stable across reorder and reopen", () => {
    const store = fresh();
    store.addFileSurface("chat-1", "src/lib/shell.rs");
    store.addFileSurface("chat-1", "web/packages/app/src/main.tsx");
    let pane = store.stateFor("chat-1");
    expect(pane.tabs).toHaveLength(2);

    const [first, second] = pane.tabs as [{ kind: "file"; id: string }, { kind: "file"; id: string }];
    expect(first.id).not.toBe(second.id);
    expect(store.describe(first)?.title).toBe("shell.rs");
    expect(store.describe(second)?.title).toBe("main.tsx");
    // The tooltip/aria detail is the full workspace path.
    expect(store.describe(first)?.detail).toBe("src/lib/shell.rs");

    // Reopening the same path activates the existing tab — no duplicate.
    store.addFileSurface("chat-1", "src/lib/shell.rs");
    pane = store.stateFor("chat-1");
    expect(pane.tabs).toHaveLength(2);
    expect(resolvedActive(pane)).toEqual(first);

    // Reorder: the tab keeps its id and position only moves.
    store.moveTab("chat-1", 0, 1);
    pane = store.stateFor("chat-1");
    expect(pane.tabs[1]).toEqual(first);
    expect(store.describe(first)?.title).toBe("shell.rs");

    // A rename keeps the id and the position; only the title changes.
    store.renameFileSurface(first.id, "src/lib/shell.rs", "src/lib/shell2.rs");
    expect(store.describe(first)?.title).toBe("shell2.rs");
    expect(store.stateFor("chat-1").tabs[1]).toEqual(first);
    // And the old path is gone from the one-tab-per-path index: a fresh
    // open of the old path mints a NEW tab.
    store.addFileSurface("chat-1", "src/lib/shell.rs");
    expect(store.stateFor("chat-1").tabs).toHaveLength(3);
  });
});

describe("surface keys and value equality", () => {
  it("compares surfaces by kind + id", () => {
    expect(surfaceKey({ kind: "files" })).toBe("files");
    expect(surfaceKey({ kind: "file", id: "f1" })).toBe("file:f1");
    expect(pushUniqueRightSurface([{ kind: "files" }], { kind: "files" })).toBe(false);
    const tabs: RightSurface[] = [{ kind: "files" }];
    expect(pushUniqueRightSurface(tabs, { kind: "terminal", id: "t1" })).toBe(true);
    expect(tabs).toEqual([{ kind: "files" }, { kind: "terminal", id: "t1" }]);
  });

  it("derives the basename title from either separator shape", () => {
    expect(workspaceFileTitle("src/lib/shell.rs")).toBe("shell.rs");
    expect(workspaceFileTitle("src\\lib\\shell.rs")).toBe("shell.rs");
    expect(workspaceFileTitle("shell.rs")).toBe("shell.rs");
  });
});

describe("tab drag geometry", () => {
  it("drop index picks the slot under the pointer", () => {
    // CHIP_SLOT is 116: x 0…115 is slot 0, 116…231 slot 1, and past the end
    // clamps to the last slot (`terminal::panel::drop_index`).
    expect(dropIndex(0, 3)).toBe(0);
    expect(dropIndex(115, 3)).toBe(0);
    expect(dropIndex(116, 3)).toBe(1);
    expect(dropIndex(300, 3)).toBe(2);
    expect(dropIndex(10_000, 3)).toBe(2);
    expect(dropIndex(-20, 3)).toBe(0);
    expect(dropIndex(500, 0)).toBe(0);
  });

  it("siblings slide one slot toward the vacated index", () => {
    const drag = { from: 0, over: 2 };
    expect(slideOffset(drag, 0)).toBe(0);
    // 1 and 2 shift left one slot to open the gap at 2.
    expect(slideOffset(drag, 1)).toBe(-116);
    expect(slideOffset(drag, 2)).toBe(-116);
    expect(slideOffset(drag, 3)).toBe(0);

    const back = { from: 2, over: 0 };
    expect(slideOffset(back, 0)).toBe(116);
    expect(slideOffset(back, 1)).toBe(116);
    expect(slideOffset(back, 2)).toBe(0);

    expect(slideOffset(null, 1)).toBe(0);
    expect(slideOffset({ from: 1, over: 1 }, 1)).toBe(0);
  });
});

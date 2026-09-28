import { describe, expect, it } from "vitest";
import {
  RightPaneStore,
  cycleRightTabTarget,
  panelKey,
  pushUniqueRightSurface,
  resolvedActive,
  surfaceKey,
  workspaceFileTitle,
  type PaneTerminalSource,
  type RightSurface,
  type SideChatEntitySource,
} from "../src/state/right-pane";
import { dropIndex, slideOffset } from "../src/components/right-tab-strip";
import { uiSettings } from "../src/state/ui-settings";

/**
 * The right pane's surface model, against the desktop's (`shell.rs:457-532`,
 * `:1901-1922`, `:2284-3004`). Tests mirror the Rust ones by name; each gets
 * a fresh store so the per-chat maps never leak between cases.
 */

/**
 * The pane's embedded terminal host, faked: xterm must not load in the node
 * environment, and a fake keeps the terminal tab entities chat-local and
 * inspectable. Titles follow the desktop's `tab_summaries` (the OSC/shell
 * label, "Terminal N" fallback — here "term" prefixed by the key).
 */
class FakePaneTerminal implements PaneTerminalSource {
  readonly openTabs: string[] = [];
  closed: string[] = [];

  openTabFor(chatId: string, key: string): boolean {
    void chatId;
    this.openTabs.push(key);
    return true;
  }

  closeTab(chatId: string, key: string): void {
    void chatId;
    this.closed.push(key);
    this.openTabs.splice(this.openTabs.indexOf(key), 1);
  }

  tabTitle(chatId: string, key: string): string | null {
    void chatId;
    return this.openTabs.includes(key) ? `term-${key}` : null;
  }
}

function fresh(): { store: RightPaneStore; terminals: FakePaneTerminal } {
  const terminals = new FakePaneTerminal();
  return { store: new RightPaneStore(terminals), terminals };
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
    const { store } = fresh();
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
    const { store } = fresh();
    store.toggle("chat-1");
    expect(store.stateFor("chat-1").open).toBe(true);
    expect(store.stateFor("chat-2").open).toBe(false);
  });
});

describe("session_panels_both_flags_coexist_per_chat", () => {
  it("open and expanded ride together on one chat without crossing chats", () => {
    const { store } = fresh();
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

describe("close_resets_logical_flags_immediately (ticket 72)", () => {
  it("close and toggle clear open+expanded in the same commit — no presentation state in the store", () => {
    const { store } = fresh();
    store.setSurfacesOpen("chat-1", true);
    store.toggleExpanded("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: true, expanded: true });

    // `close()` (Escape / backdrop): the flags reset synchronously — the
    // phone close's width hold is component presentation, never a delayed
    // or deferred flag here.
    store.close("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, expanded: false });

    // `toggle()` out of takeover resets the same way, and the reopen after
    // either close lands in normal mode.
    store.toggle("chat-1");
    store.toggleExpanded("chat-1");
    store.toggle("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, expanded: false });
    store.toggle("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: true, expanded: false });

    // The state shape is exactly the logical model — the transient close
    // presentation lives in `RightPane`, not in the pane store.
    expect(Object.keys(store.stateFor("chat-1")).sort()).toEqual([
      "active",
      "expanded",
      "filesOpen",
      "open",
      "tabs",
      "width",
    ]);
  });
});

describe("session_panels_update_tracks_right_surfaces", () => {
  it("resolvedActive follows the live tab list and falls back to the picker", () => {
    const { store } = fresh();
    store.addFileSurface("chat-1", "src/main.rs");
    store.addTerminalSurface("chat-1");
    // The surface id IS the embedded terminal tab's key.
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual({ kind: "terminal", id: "t1" });

    // The stored pick goes stale when its tab closes — never render a dead
    // surface; the first remaining tab wins.
    store.closeSurface("chat-1", { kind: "terminal", id: "t1" });
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual({ kind: "file", id: "f1" });

    // Emptied, the surface host collapses (fe45a1cd collapse_surfaces_if_empty)
    // unless the docked explorer keeps the pane alive.
    store.closeSurface("chat-1", { kind: "file", id: "f1" });
    let pane = store.stateFor("chat-1");
    expect(pane.open).toBe(false);
    expect(resolvedActive(pane)).toEqual({ kind: "picker" });
    store.openFilesPanel("chat-1");
    store.addTerminalSurface("chat-1");
    store.closeSurface("chat-1", { kind: "terminal", id: "t2" });
    pane = store.stateFor("chat-1");
    expect(pane.open).toBe(true);
    expect(pane.filesOpen).toBe(true);
  });
});

describe("terminal_surfaces_are_per_instance (add_terminal_surface, shell.rs:2634-2650)", () => {
  it("every click opens a FRESH embedded terminal tab addressing its own PTY", () => {
    const { store, terminals } = fresh();
    store.addTerminalSurface("chat-1");
    store.addTerminalSurface("chat-1");
    const pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([{ kind: "terminal", id: "t1" }, { kind: "terminal", id: "t2" }]);
    expect(terminals.openTabs).toEqual(["t1", "t2"]);
    expect(resolvedActive(pane)).toEqual({ kind: "terminal", id: "t2" });

    // The chip title is the terminal tab's own live label, and closing the
    // surface closes THAT tab (close_tab_by_key) — the sibling stays.
    expect(store.describe({ kind: "terminal", id: "t1" }, "chat-1")?.title).toBe("term-t1");
    store.closeSurface("chat-1", { kind: "terminal", id: "t1" });
    expect(terminals.closed).toEqual(["t1"]);
    expect(store.describe({ kind: "terminal", id: "t1" }, "chat-1")).toBeNull();
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual({ kind: "terminal", id: "t2" });

    // A tab that vanished under the pane (its entity gone) disappears from
    // the rows entirely — right_surface_rows' skip signal.
    expect(store.surfaceRows("chat-1")).toHaveLength(1);
  });

  it("without a terminal host wired, the mint is a no-op", () => {
    const store = new RightPaneStore(null);
    store.addTerminalSurface("chat-1");
    expect(store.stateFor("chat-1").tabs).toEqual([]);
  });
});

describe("explorer_is_a_docked_portion_of_one_right_pane (tickets 22/23)", () => {
  it("the files toggle opens the pane alone; the pane toggle drives only the surface host", () => {
    const { store } = fresh();
    // The explorer toggle opens the pane with only its portion.
    store.toggleFilesPanel("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, filesOpen: true });
    // The pane toggle drives only the host: the explorer stays docked.
    store.toggle("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: true, filesOpen: true });
    store.toggle("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, filesOpen: true });
    store.toggleFilesPanel("chat-1");
    expect(store.stateFor("chat-1")).toMatchObject({ open: false, filesOpen: false });
    // The Files chord routes to the docked portion, never a tab.
    store.revealSurface("chat-1", "files");
    expect(store.stateFor("chat-1").tabs).toHaveLength(0);
    expect(store.stateFor("chat-1").filesOpen).toBe(true);
  });

  it("programmatic file opens never close an open pane (set_surfaces_open)", () => {
    const { store } = fresh();
    store.openFilesPanel("chat-1");
    store.addFileSurface("chat-1", "src/main.rs");
    expect(store.stateFor("chat-1")).toMatchObject({ open: true, filesOpen: true });
    store.setSurfacesOpen("chat-1", true);
    expect(store.stateFor("chat-1")).toMatchObject({ open: true, filesOpen: true });
  });

  it("reveal requests dock the explorer and queue one reveal per path", () => {
    const { store } = fresh();
    store.revealInFilesPanel("chat-1", "src/lib.rs");
    expect(store.stateFor("chat-1").filesOpen).toBe(true);
    expect(store.pendingFilesReveal()).toMatchObject({ chatId: "chat-1", path: "src/lib.rs" });
    store.clearFilesReveal();
    expect(store.pendingFilesReveal()).toBeNull();
  });

  it("the files panel width clamps into the drag bounds", () => {
    const { store } = fresh();
    store.setFilesPanelWidth(40);
    expect(uiSettings.getSnapshot().filesPanelWidth).toBe(220);
    store.setFilesPanelWidth(9000);
    expect(uiSettings.getSnapshot().filesPanelWidth).toBe(440);
  });
});

describe("commit_diff_surfaces_are_independent_pinned_tabs", () => {
  it("each click mints a fresh tab titled with the commit's subject", () => {
    const store = fresh().store;
    store.addDiffSurface("chat-1", "history");
    store.addCommitDiffSurface("chat-1", { sha: "896e31f0abcd", subject: "Merge branch 'feature'" });
    store.addCommitDiffSurface("chat-1", { sha: "216321b0cdef", subject: "  " });
    const pane = store.stateFor("chat-1");
    expect(pane.tabs).toHaveLength(3);

    const [history, first, second] = pane.tabs as [
      { kind: "diff"; id: string },
      { kind: "diff"; id: string },
      { kind: "diff"; id: string },
    ];
    // The titles: the trimmed subject, else the first 7 sha chars
    // (`tab_title`, changes.rs:1725).
    expect(store.describe(first)?.title).toBe("Merge branch 'feature'");
    expect(store.describe(second)?.title).toBe("216321b");
    expect(store.describe(history)?.title).toBe("History");
    expect(store.describe(history)?.isHistory).toBe(true);
    expect(store.describe(first)?.isHistory).toBe(false);

    // The pins the surfaces mount with (`Changes::for_commit`).
    expect(store.diffMetaOf(first.id)).toMatchObject({ flavor: "commit", commitSha: "896e31f0abcd" });
    expect(store.diffMetaOf(second.id)).toMatchObject({ flavor: "commit", commitSha: "216321b0cdef" });

    // Closing a pinned tab drops only its own meta — the others stay; the
    // stored pick resets and the first remaining tab (History) resolves.
    store.closeSurface("chat-1", second);
    expect(store.diffMetaOf(second.id)).toBeNull();
    expect(store.diffMetaOf(first.id)?.commitSha).toBe("896e31f0abcd");
    expect(resolvedActive(store.stateFor("chat-1"))).toEqual(history);
  });
});

describe("file_editors_are_distinct_surface_tabs_with_stable_titles", () => {
  it("one tab per path, basename titles, ids stable across reorder and reopen", () => {
    const { store } = fresh();
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

describe("chat_file_links_carry_their_line (d1010657 add_file_surface_at)", () => {
  it("a located open records a pending line navigation for the tab it lands on", () => {
    const { store } = fresh();
    // A fresh open mints the tab and the pending navigation targets it.
    store.addFileSurface("chat-1", "src/lib.rs", "chat-1", { line: 42, column: 7 });
    const pane = store.stateFor("chat-1");
    const tab = pane.tabs[0] as { kind: "file"; id: string };
    expect(resolvedActive(pane)).toEqual(tab);
    expect(store.pendingFileLine()).toEqual({ surfaceId: tab.id, line: 42, column: 7, seq: 1 });

    // The consuming surface clears it.
    store.clearFileLine();
    expect(store.pendingFileLine()).toBeNull();

    // A located re-open of the SAME path re-targets the existing tab —
    // no duplicate tab, and the navigation bumps its seq so a repeat jump
    // to the same line re-runs.
    store.addFileSurface("chat-1", "src/lib.rs", "chat-1", { line: 42, column: null });
    expect(store.stateFor("chat-1").tabs).toHaveLength(1);
    expect(store.pendingFileLine()).toEqual({ surfaceId: tab.id, line: 42, column: null, seq: 2 });

    // A plain (lineless) open never records a navigation.
    store.clearFileLine();
    store.addFileSurface("chat-1", "src/lib.rs");
    expect(store.pendingFileLine()).toBeNull();
  });

  it("closing a tab drops a pending navigation that targets it", () => {
    const { store } = fresh();
    store.addFileSurface("chat-1", "src/lib.rs", "chat-1", { line: 10, column: null });
    const tab = store.stateFor("chat-1").tabs[0] as { kind: "file"; id: string };
    expect(store.pendingFileLine()?.surfaceId).toBe(tab.id);
    store.closeSurface("chat-1", tab);
    expect(store.pendingFileLine()).toBeNull();
  });
});

describe("surface keys and value equality", () => {
  it("compares surfaces by kind + id", () => {
    expect(surfaceKey({ kind: "picker" })).toBe("picker");
    expect(surfaceKey({ kind: "file", id: "f1" })).toBe("file:f1");
    expect(pushUniqueRightSurface([{ kind: "file", id: "f1" }], { kind: "file", id: "f1" })).toBe(false);
    const tabs: RightSurface[] = [{ kind: "file", id: "f1" }];
    expect(pushUniqueRightSurface(tabs, { kind: "terminal", id: "t1" })).toBe(true);
    expect(tabs).toEqual([{ kind: "file", id: "f1" }, { kind: "terminal", id: "t1" }]);
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

/** The side-chat entity source, faked — the boot injection's test double. */
class FakeSideChats implements SideChatEntitySource {
  readonly drafts = new Set<string>();
  disposed: string[] = [];

  hasDraft(chatId: string): boolean {
    return this.drafts.has(chatId);
  }

  dispose(chatId: string): void {
    this.drafts.delete(chatId);
    this.disposed.push(chatId);
  }
}

/**
 * Side-chat surfaces (ticket 10) — the web port of `open_side_chat` /
 * `close_right_surface`'s SideChat arm / `remove_deleted_side_chats`
 * (crates/ui/src/shell/side_chats.rs, 731697b6). One entity per chat id;
 * a close with a draft keeps it detached; the prune drops deleted chats'
 * entities and tabs everywhere.
 */
describe("side_chat_surfaces (side_chats.rs)", () => {
  it("mints a tab per chat id, activates it, and titles from the entity", () => {
    const { store } = fresh();
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "Investigate caching" });
    let pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([{ kind: "sidechat", id: "c1" }]);
    expect(pane.open).toBe(true);
    expect(resolvedActive(pane)).toEqual({ kind: "sidechat", id: "c1" });
    expect(store.describe({ kind: "sidechat", id: "c1" }, "chat-1")?.title).toBe(
      "Investigate caching",
    );
    // The strip's icon comes from the registry, but the facts' shape must
    // be plain like every other kind's.
    expect(store.describe({ kind: "sidechat", id: "c1" }, "chat-1")).toMatchObject({
      detail: null,
      isHistory: false,
      isDirty: false,
    });
    // The chat ids the pane knows (the prune input's superset).
    expect(store.sideChatChatIds()).toEqual(new Set(["side-a"]));

    // A second chat mints a second entity and appends its tab.
    store.addSideChatSurface("chat-1", { chatId: "side-b", title: "New side chat" });
    pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([
      { kind: "sidechat", id: "c1" },
      { kind: "sidechat", id: "c2" },
    ]);
    expect(resolvedActive(pane)).toEqual({ kind: "sidechat", id: "c2" });
    expect(store.sideChatChatIds()).toEqual(new Set(["side-a", "side-b"]));
  });

  it("re-attaches the kept entity by chat id — never a duplicate tab", () => {
    const { store } = fresh();
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    // Reopening the same chat id finds the entity and re-activates it.
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A (retitled)" });
    const pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([{ kind: "sidechat", id: "c1" }]);
    expect(resolvedActive(pane)).toEqual({ kind: "sidechat", id: "c1" });
    // The entity's title is whatever it was opened with (the surface
    // refreshes it as the row changes — `updateSideChatTitle`).
    expect(store.describe({ kind: "sidechat", id: "c1" }, "chat-1")?.title).toBe("A");
    store.updateSideChatTitle("c1", "A (retitled)");
    expect(store.describe({ kind: "sidechat", id: "c1" }, "chat-1")?.title).toBe(
      "A (retitled)",
    );
    // A no-op title write never bumps the version.
    const version = store.getVersion();
    store.updateSideChatTitle("c1", "A (retitled)");
    expect(store.getVersion()).toBe(version);
  });

  it("a close with a draft keeps the entity; a reopen restores the same tab", () => {
    const { store } = fresh();
    const sideChats = new FakeSideChats();
    store.setSideChatEntitySource(sideChats);
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    const surface: RightSurface = { kind: "sidechat", id: "c1" };
    sideChats.drafts.add("side-a");

    store.closeSurface("chat-1", surface);
    // The tab is gone (the pane collapsed — nothing else keeps it open),
    // but the entity stays for the reopen.
    expect(store.stateFor("chat-1").tabs).toEqual([]);
    expect(store.stateFor("chat-1").open).toBe(false);
    expect(store.sideChatSurfaceOf("c1")).toEqual({ chatId: "side-a", title: "A" });

    // Reopening re-attaches the KEPT entity — the same surface id.
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    const pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([surface]);
    expect(resolvedActive(pane)).toEqual(surface);
    expect(sideChats.disposed).toEqual([]);
  });

  it("a close without a draft drops the entity with the tab", () => {
    const { store } = fresh();
    const sideChats = new FakeSideChats();
    store.setSideChatEntitySource(sideChats);
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    const surface: RightSurface = { kind: "sidechat", id: "c1" };

    store.closeSurface("chat-1", surface);
    expect(store.stateFor("chat-1").tabs).toEqual([]);
    expect(store.sideChatSurfaceOf("c1")).toBeNull();
    expect(store.describe(surface, "chat-1")).toBeNull();
    // The backing draft store entry went with it (the dispose hook).
    expect(sideChats.disposed).toEqual(["side-a"]);

    // A later open mints a FRESH entity for the same chat.
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    expect(store.stateFor("chat-1").tabs).toEqual([{ kind: "sidechat", id: "c2" }]);
  });

  it("remove_deleted_side_chats drops deleted chats' tabs everywhere and collapses empty panes", () => {
    const { store } = fresh();
    const sideChats = new FakeSideChats();
    store.setSideChatEntitySource(sideChats);
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    // The same entity attached under a second pane (open_side_chat on
    // another panel key — the entity is global, the tabs are per pane).
    store.addSideChatSurface("chat-2", { chatId: "side-a", title: "A" });
    store.addSideChatSurface("chat-1", { chatId: "side-b", title: "B" });

    // chat-1's pane stays open through the prune (side-b remains); the
    // active pick lands on the first remaining tab.
    store.setActive("chat-1", { kind: "sidechat", id: "c1" });
    store.pruneSideChats(new Set(["chat-1", "side-b", "chat-2"]));
    expect(store.sideChatSurfaceOf("c1")).toBeNull();
    expect(store.sideChatChatIds()).toEqual(new Set(["side-b"]));
    expect(store.sideChatSurfaceOf("c2")?.chatId).toBe("side-b");
    const pane1 = store.stateFor("chat-1");
    expect(pane1.tabs).toEqual([{ kind: "sidechat", id: "c2" }]);
    expect(resolvedActive(pane1)).toEqual({ kind: "sidechat", id: "c2" });
    expect(pane1.open).toBe(true);
    // chat-2's pane collapsed with its only tab gone; the disposed chat's
    // draft went with it.
    const pane2 = store.stateFor("chat-2");
    expect(pane2.tabs).toEqual([]);
    expect(pane2.open).toBe(false);
    expect(resolvedActive(pane2)).toEqual({ kind: "picker" });
    expect(sideChats.disposed).toEqual(["side-a"]);

    // A prune with nothing to do changes nothing.
    const version = store.getVersion();
    store.pruneSideChats(new Set(["chat-1", "side-b", "chat-2"]));
    expect(store.getVersion()).toBe(version);
  });

  it("a deleted side chat that was the last tab hands the active pick to the picker and can keep the pane open through the explorer", () => {
    const { store } = fresh();
    store.setSideChatEntitySource(new FakeSideChats());
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    // The docked explorer keeps the pane alive (collapse_surfaces_if_empty).
    store.openFilesPanel("chat-1");
    store.pruneSideChats(new Set(["chat-1"]));
    const pane = store.stateFor("chat-1");
    expect(pane.tabs).toEqual([]);
    expect(pane.open).toBe(true);
    expect(pane.filesOpen).toBe(true);
    expect(resolvedActive(pane)).toEqual({ kind: "picker" });
  });
});

// ---------------------------------------------------------------------------
// Focus-following right-tab cycling (upstream a1ccea18, shell/tabs.rs)
// ---------------------------------------------------------------------------

describe("cycleRightTabTarget (a1ccea18)", () => {
  const d1: RightSurface = { kind: "diff", id: "d1" };
  const s1: RightSurface = { kind: "subagent", id: "s1" };
  const c1: RightSurface = { kind: "sidechat", id: "c1" };

  it("steps through the strip order and wraps at both ends", () => {
    const rows = [d1, s1, c1];
    expect(cycleRightTabTarget(rows, d1, true)).toEqual(s1);
    expect(cycleRightTabTarget(rows, s1, true)).toEqual(c1);
    // The strip's displayed order — a drag reorder changes the walk.
    expect(cycleRightTabTarget(rows, c1, true)).toEqual(d1);
    expect(cycleRightTabTarget(rows, d1, false)).toEqual(c1);
    expect(cycleRightTabTarget(rows, c1, false)).toEqual(s1);
  });

  it("a pane with zero or one tab has nothing to cycle", () => {
    // The desktop's rows.len() <= 1 early return: the binding is consumed
    // without leaving the pane.
    expect(cycleRightTabTarget([], { kind: "picker" }, true)).toBeNull();
    expect(cycleRightTabTarget([d1], d1, true)).toBeNull();
    expect(cycleRightTabTarget([d1], d1, false)).toBeNull();
  });

  it("an active pick that is not in the list enters at the matching end", () => {
    // The empty picker (or a stale pick) is treated like session cycling's
    // missing selection: forward enters at the first tab, backward at the
    // last, rather than dead-ending.
    expect(cycleRightTabTarget([d1, s1, c1], { kind: "picker" }, true)).toEqual(d1);
    expect(cycleRightTabTarget([d1, s1, c1], { kind: "picker" }, false)).toEqual(c1);
    // Value equality, not identity: a structurally-equal pick from a fresh
    // render (resolvedActive's stored active) finds its slot.
    expect(cycleRightTabTarget([d1, s1, c1], { kind: "subagent", id: "s1" }, true)).toEqual(c1);
  });

  it("the store walk: live rows only, the resolved active as the anchor", () => {
    // The composition the AppShell listener runs — right_surface_rows
    // (stale ids dropped) feeding the cycle with resolved_active's pick.
    const { store } = fresh();
    store.addDiffSurface("chat-1", "diff");
    store.addDiffSurface("chat-1", "diff", "second");
    const first: RightSurface = { kind: "diff", id: "d1" };
    const second: RightSurface = { kind: "diff", id: "d2" };
    store.setActive("chat-1", first);
    store.closeSurface("chat-1", second);
    const surfaces = store.surfaceRows("chat-1").map((row) => row.surface);
    expect(surfaces).toEqual([first]);
    // One live tab left: nothing to cycle, focus stays in the pane.
    expect(cycleRightTabTarget(surfaces, resolvedActive(store.stateFor("chat-1")), true)).toBeNull();
    store.addSideChatSurface("chat-1", { chatId: "side-a", title: "A" });
    // Anchor the walk at the diff tab (the add activated the side chat).
    store.setActive("chat-1", first);
    const live = store.surfaceRows("chat-1").map((row) => row.surface);
    expect(cycleRightTabTarget(live, resolvedActive(store.stateFor("chat-1")), true)).toEqual({
      kind: "sidechat",
      id: "c1",
    });
  });
});

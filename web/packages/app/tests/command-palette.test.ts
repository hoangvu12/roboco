import { afterEach, describe, expect, it, vi } from "vitest";
import type { Chat, Device, Space } from "@roboco/proto";
import { readFileSync } from "node:fs";
import { actionsFor, matchesQuery, paletteChats } from "../src/lib/command-palette";
import { actionBadge } from "../src/components/command-palette";
import { defaultKeymap, type KeymapConfig } from "../src/state/ui-settings";
import {
  commandPaletteStore,
  toggleCommandPalette,
} from "../src/state/command-palette";
import { addSpaceStore } from "../src/state/add-space";

/**
 * Ports of the desktop's command palette tests
 * (`crates/ui/src/shell/command_palette.rs` test module) plus the store's
 * open/close/activate lifecycle: `action_search_hides_empty_section_and_
 * preserves_order`, the action rows' shortcut badges (`actionBadge`,
 * command_palette.rs:273-293), `search_matches_words_across_chat_metadata`,
 * the global chat-history search (title/project/device/branch/PR targets,
 * archived included, the 30-row cap after sorting), the activation
 * intents (chat rows carry the chat id they launch — upstream 62c52329's
 * argument fix, web shape), `hover_command`'s highlight semantics
 * (command_palette.rs:154-163), and the archived rows' icon muting
 * (shell.rs:6154-6161, 6590-6598, guarded at the CSS level like
 * sidebar-fade.test.ts).
 */

function chat(partial: Partial<Chat> & { readonly id: string }): Chat {
  return {
    deviceId: "local",
    spaceId: "project",
    // The desktop fixture's convention: the chat's title is its id
    // (command_palette.rs:597-605), so id queries match by title.
    title: partial.title ?? partial.id,
    archived: false,
    createdAt: "2026-09-01T00:00:00Z",
    lastMessageAt: null,
    lastSeenAt: null,
    config: null,
    sourceContext: null,
    ...partial,
  } as Chat;
}

describe("action_search_hides_empty_section_and_preserves_order (command_palette.rs)", () => {
  it("an empty query lists every action in order, dark resolving the theme action to light", () => {
    expect(actionsFor("", true).map((action) => action.id)).toEqual([
      "new-chat",
      "new-project",
      "settings",
      "theme",
    ]);
    expect(actionsFor("", true).at(-1)?.theme).toBe("light");
  });

  it("matching actions filter in place; a miss hides the section", () => {
    expect(actionsFor("new", true).map((action) => action.id)).toEqual(["new-chat", "new-project"]);
    expect(actionsFor("settings", true).map((action) => action.id)).toEqual(["settings"]);
    expect(actionsFor("deployment", true)).toEqual([]);
  });
});

describe("theme_action_targets_the_opposite_resolved_appearance (b4dd24d7)", () => {
  it("dark resolves to the light theme; light resolves to dark", () => {
    expect(actionsFor("theme", true).map((action) => action.theme)).toEqual(["light"]);
    expect(actionsFor("theme", false).map((action) => action.theme)).toEqual(["dark"]);
    expect(actionsFor("light", true).map((action) => action.theme)).toEqual(["light"]);
    expect(actionsFor("dark", false).map((action) => action.theme)).toEqual(["dark"]);
  });
});

describe("search_matches_words_across_chat_metadata (command_palette.rs)", () => {
  it("every word must appear, case-insensitively", () => {
    expect(matchesQuery("mac auth", "Fix authentication Roboco @ MacBook main")).toBe(true);
    expect(matchesQuery("  ", "Any chat")).toBe(true);
    expect(matchesQuery("mac windows", "Roboco @ MacBook")).toBe(false);
  });
});

describe("action_badge (command_palette.rs:273-293)", () => {
  it("new chat and new project badge their keymap binding, rebound or default", () => {
    const rebound: KeymapConfig = { ...defaultKeymap(false), newSession: "mod-shift-p" };
    expect(actionBadge("new-chat", rebound, true)).toBe("⇧⌘P");
    expect(actionBadge("new-chat", rebound, false)).toBe("Ctrl+Shift+P");
    const defaults = defaultKeymap(false);
    expect(actionBadge("new-project", defaults, true)).toBe("⇧⌘N");
    expect(actionBadge("new-project", defaults, false)).toBe("Ctrl+Shift+N");
  });

  it("an unparseable binding falls back to the default combo", () => {
    const junk: KeymapConfig = { ...defaultKeymap(false), newSession: "nonsense-!!" };
    expect(actionBadge("new-chat", junk, true)).toBe("⌘N");
    expect(actionBadge("new-chat", junk, false)).toBe("Ctrl+N");
    const unbound: KeymapConfig = { ...defaultKeymap(false), newSession: "" };
    expect(actionBadge("new-chat", unbound, false)).toBe("Ctrl+N");
  });

  it("open settings badges the hardcoded mod-,; the theme action gets none", () => {
    const keymap = defaultKeymap(false);
    expect(actionBadge("settings", keymap, true)).toBe("⌘,");
    expect(actionBadge("settings", keymap, false)).toBe("Ctrl+,");
    expect(actionBadge("theme", keymap, true)).toBeNull();
    expect(actionBadge("theme", keymap, false)).toBeNull();
  });
});

describe("paletteChats (command_entries)", () => {
  const spaces: Space[] = [
    { id: "project", deviceId: "local", path: "/tmp/fieldnotes", name: null } as unknown as Space,
  ];
  const devices: Device[] = [
    { id: "local", name: "This device", platform: "windows", lastSeenAt: null } as unknown as Device,
  ];
  const chats: Chat[] = [
    chat({ id: "a", title: "Fix authentication redirects" }),
    chat({ id: "b", title: "Add deployment status", deviceId: "remote" }),
    chat({ id: "c", title: "Untouched", archived: true }),
  ];

  it("searches title, project, device, and branch metadata", () => {
    const rows = paletteChats({
      chats,
      spaces,
      statuses: [],
      devices,
      changeRequests: new Map(),
      now: Date.parse("2026-09-22T00:00:00Z"),
      query: "fieldnotes auth",
    });
    // "b" has no space and the "remote" device is unknown; only "a" matches.
    expect(rows.map((row) => row.chat.id)).toEqual(["a"]);
    expect(rows[0]!.folder).toBe("fieldnotes @ This device");
    // Every row carries the archived flag the row component mutes from
    // (lib/command-palette.ts:114 supplies it for archived and live alike).
    expect(rows[0]!.archived).toBe(false);
  });

  it("archived chats remain searchable", () => {
    const rows = paletteChats({
      chats,
      spaces,
      statuses: [],
      devices,
      changeRequests: new Map(),
      now: Date.parse("2026-09-22T00:00:00Z"),
      query: "untouched",
    });
    expect(rows.map((row) => row.chat.id)).toEqual(["c"]);
    expect(rows[0]!.archived).toBe(true);
  });

  it("a PR's number, title, and refs are search targets", () => {
    const changeRequests = new Map([
      [
        "a",
        {
          number: 421,
          title: "Palette polish",
          headRef: "wing/palette",
          baseRef: "main",
          state: "open" as const,
          url: "https://example.test/pr/421",
          provider: "github",
        },
      ],
    ]);
    const byNumber = paletteChats({
      chats,
      spaces,
      statuses: [],
      devices,
      changeRequests,
      now: 0,
      query: "421 palette",
    });
    expect(byNumber.map((row) => row.chat.id)).toEqual(["a"]);
    expect(byNumber[0]!.changeRequest?.number).toBe(421);
  });

  it("caps the history at 30 matching chats after sorting", () => {
    const many: Chat[] = [];
    for (let ix = 0; ix < 50; ix += 1) {
      const seconds = String(ix).padStart(2, "0");
      many.push(chat({ id: `row-${ix}`, title: `Chat ${ix}`, createdAt: `2026-09-01T00:00:${seconds}Z` }));
    }
    const rows = paletteChats({
      chats: many,
      spaces,
      statuses: [],
      devices,
      changeRequests: new Map(),
      now: 0,
      query: "",
    });
    expect(rows).toHaveLength(30);
    // lastUpdated sort: newest first.
    expect(rows[0]!.chat.id).toBe("row-49");
  });

  it("an unknown project reads as ~ (no space id) or ? (dangling id)", () => {
    const rows = paletteChats({
      chats: [
        chat({ id: "home", spaceId: null }),
        chat({ id: "dangling", spaceId: "missing" }),
      ],
      spaces,
      statuses: [],
      devices,
      changeRequests: new Map(),
      now: 0,
      query: "",
    });
    const byId = new Map(rows.map((row) => [row.chat.id, row.project]));
    expect(byId.get("home")).toBe("~");
    expect(byId.get("dangling")).toBe("?");
  });

  // Upstream #651 (`history_excludes_child_chats_with_and_without_search`
  // and `child_chats_do_not_consume_history_result_slots`, command_palette.rs):
  // child chats never enter the palette's global history, by title search or
  // otherwise, and cannot consume the 30-row cap.
  it("excludes child chats with and without a search", () => {
    const mixed: Chat[] = [
      chat({ id: "manual-sidechat", parentChatId: "main-session", createdAt: "2026-09-01T00:00:00Z" }),
      chat({ id: "archived-sidechat", parentChatId: "main-session", archived: true, createdAt: "2026-09-01T00:00:01Z" }),
      chat({ id: "orphan-sidechat", parentChatId: "deleted-parent", createdAt: "2026-09-01T00:00:02Z" }),
      chat({ id: "main-session", createdAt: "2026-09-01T00:00:04Z" }),
      chat({ id: "archived-session", archived: true, createdAt: "2026-09-01T00:00:03Z" }),
    ];
    const search = (query: string): string[] =>
      paletteChats({
        chats: mixed,
        spaces,
        statuses: [],
        devices,
        changeRequests: new Map(),
        now: 0,
        query,
      }).map((row) => row.chat.id);
    expect(search("")).toEqual(["main-session", "archived-session"]);
    for (const query of ["manual-sidechat", "archived-sidechat", "orphan-sidechat"]) {
      expect(search(query)).toEqual([]);
    }
    expect(search("main-session")).toEqual(["main-session"]);
  });

  it("child chats do not consume the 30-row cap", () => {
    const T0 = Date.parse("2026-09-01T00:30:00Z");
    // Seconds ago, mirroring the desktop test's ages: children are NEWER
    // than every session, so without the exclusion they would own the top
    // 30 slots entirely.
    const iso = (secondsAgo: number): string =>
      new Date(T0 - secondsAgo * 1000).toISOString();
    const children: Chat[] = Array.from({ length: 30 }, (_, ix) =>
      chat({ id: `child-${ix}`, parentChatId: "session-0", createdAt: iso(ix) }),
    );
    const sessions: Chat[] = Array.from({ length: 31 }, (_, ix) =>
      chat({ id: `session-${ix}`, createdAt: iso(30 + ix) }),
    );
    const search = (query: string): string[] =>
      paletteChats({
        chats: [...children, ...sessions],
        spaces,
        statuses: [],
        devices,
        changeRequests: new Map(),
        now: 0,
        query,
      }).map((row) => row.chat.id);
    expect(search("")).toEqual(Array.from({ length: 30 }, (_, ix) => `session-${ix}`));
    expect(search("session-30")).toEqual(["session-30"]);
  });
});

describe("CommandPaletteStore (toggle/close/activate)", () => {
  afterEach(() => {
    commandPaletteStore.forceClose();
    addSpaceStore.forceClose();
  });

  it("toggle opens, then closes; open replaces the add-space palette", () => {
    addSpaceStore.open();
    toggleCommandPalette();
    expect(commandPaletteStore.getSnapshot().status).toBe("open");
    // The add-space card left the open state (its exit window may still be
    // fading — the web's close drains through `[data-closed]`).
    expect(addSpaceStore.getSnapshot().status).not.toBe("open");
    toggleCommandPalette();
    expect(commandPaletteStore.getSnapshot().status).toBe("closing");
  });

  it("a search edit resets the highlight; move wraps at both ends", () => {
    commandPaletteStore.open();
    commandPaletteStore.setQuery("new");
    expect(commandPaletteStore.getSnapshot().active).toBe(0);
    commandPaletteStore.move(1, 3);
    commandPaletteStore.move(1, 3);
    expect(commandPaletteStore.getSnapshot().active).toBe(2);
    commandPaletteStore.move(1, 3);
    expect(commandPaletteStore.getSnapshot().active).toBe(0);
    commandPaletteStore.move(-1, 3);
    expect(commandPaletteStore.getSnapshot().active).toBe(2);
  });

  // `hover_command` (command_palette.rs:154-163): pointer motion moves the
  // highlight so hover and the keyboard never light two rows.
  it("hover moves the highlight; the keyboard continues from the hovered row", () => {
    commandPaletteStore.open();
    commandPaletteStore.move(1, 3);
    expect(commandPaletteStore.getSnapshot().active).toBe(1);
    commandPaletteStore.hover(2);
    expect(commandPaletteStore.getSnapshot().active).toBe(2);
    // Last-writer-wins with the keyboard: move steps from the hovered row.
    commandPaletteStore.move(1, 3);
    expect(commandPaletteStore.getSnapshot().active).toBe(0);
  });

  it("hovering the active row commits nothing; a closed store ignores hover", () => {
    commandPaletteStore.open();
    // The observable no-op contract is the store's own seam: no listener
    // fires (and the highlight stays put) when the hovered row is already
    // the active one.
    const listener = vi.fn();
    const unsubscribe = commandPaletteStore.subscribe(listener);
    commandPaletteStore.hover(0);
    expect(listener).not.toHaveBeenCalled();
    expect(commandPaletteStore.getSnapshot().active).toBe(0);
    unsubscribe();
    commandPaletteStore.forceClose();
    commandPaletteStore.hover(2);
    expect(commandPaletteStore.getSnapshot()).toEqual({
      status: "closed",
      query: "",
      active: 0,
    });
  });

  it("unmounted drops the query and highlight", () => {
    commandPaletteStore.open();
    commandPaletteStore.setQuery("auth");
    commandPaletteStore.move(1, 3);
    commandPaletteStore.close();
    commandPaletteStore.unmounted();
    expect(commandPaletteStore.getSnapshot()).toEqual({
      status: "closed",
      query: "",
      active: 0,
    });
  });

  it("chat entries carry the chat id they launch (62c52329, web shape)", () => {
    const openChat = vi.fn();
    commandPaletteStore.attach({
      session: null,
      goToCanvas: () => {},
      openChat,
      openSettings: () => {},
    });
    commandPaletteStore.open();
    commandPaletteStore.activateEntry({ kind: "chat", chatId: "chat-7" });
    expect(openChat).toHaveBeenCalledWith("chat-7");
    expect(commandPaletteStore.getSnapshot().status).not.toBe("open");
  });

  it("New project closes the palette and opens the add-space flow", () => {
    commandPaletteStore.open();
    commandPaletteStore.activateEntry({ kind: "new-project" });
    expect(commandPaletteStore.getSnapshot().status).not.toBe("open");
    expect(addSpaceStore.getSnapshot().status).toBe("open");
  });

  it("New chat and Open settings route through the attached context", () => {
    const goToCanvas = vi.fn();
    const openSettings = vi.fn();
    commandPaletteStore.attach({
      session: null,
      goToCanvas,
      openChat: () => {},
      openSettings,
    });
    commandPaletteStore.open();
    commandPaletteStore.activateEntry({ kind: "new-chat" });
    commandPaletteStore.open();
    commandPaletteStore.activateEntry({ kind: "settings" });
    expect(goToCanvas).toHaveBeenCalledTimes(1);
    expect(openSettings).toHaveBeenCalledTimes(1);
  });

  it("the theme action keeps the palette open and switches the mode (b4dd24d7)", () => {
    const setTheme = vi.fn();
    commandPaletteStore.open();
    commandPaletteStore.activateEntry({ kind: "theme", setTheme });
    expect(setTheme).toHaveBeenCalledTimes(1);
    // The palette stays open so the action updates to its next state.
    expect(commandPaletteStore.getSnapshot().status).toBe("open");
  });
});

describe("archived rows mute their icons (shell.rs:6154-6161, 6590-6598)", () => {
  const css = readFileSync(new URL("../src/styles/app.css", import.meta.url), "utf8");

  /** One CSS rule block, asserted to exist (sidebar-fade.test.ts's idiom). */
  const rule = (selector: string): string => {
    const pattern = selector
      .split(" ")
      .map((part) => part.replace(/\./g, "\\."))
      .join("\\s+");
    const block = css.match(new RegExp(`${pattern}\\s*\\{[^}]*\\}`))?.[0];
    expect(block, `${selector} rule`).toBeDefined();
    return block!;
  };

  it("dims the harness icon to 40%; the active row restores it", () => {
    // The desktop's `archived_muted` (icons at 40%, the harness mark's
    // rest alpha being 0.8); selection is never muted, and hover =
    // motion = the active row in the palette.
    expect(rule(".command-chat-row-archived .command-chat-harness")).toMatch(/opacity:\s*0\.4;/);
    expect(rule(".command-chat-row-archived.command-chat-row-active .command-chat-harness")).toMatch(
      /opacity:\s*0\.8;/,
    );
  });

  it("mirrors the sidebar's 55% archived title, restored under active", () => {
    // No title muting on the desktop — the 55% dim is the web sidebar's
    // own treatment (app.css's `.archived .chat-row-item .chat-row-title`),
    // scoped to the title line so the muted folder/branch lines keep theirs.
    expect(rule(".command-chat-row-archived .command-chat-line-2 .command-chat-label")).toMatch(
      /color-mix\(in srgb, var\(--rb-text\) 55%, transparent\)/,
    );
    expect(
      rule(".command-chat-row-archived.command-chat-row-active .command-chat-line-2 .command-chat-label"),
    ).toMatch(/color:\s*var\(--rb-text\);/);
  });
});

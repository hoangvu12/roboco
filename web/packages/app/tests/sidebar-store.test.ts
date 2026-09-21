import { describe, expect, it } from "vitest";
import type { StorageLike } from "../src/lib/engine-store";
import { SidebarStore } from "../src/lib/sidebar-store";

function memoryStorage(): StorageLike {
  const map = new Map<string, string>();
  return {
    getItem: (key) => (map.has(key) ? map.get(key)! : null),
    setItem: (key, value) => void map.set(key, value),
    removeItem: (key) => void map.delete(key),
  };
}

describe("SidebarStore", () => {
  it("starts unfiltered with the archived shelf closed", () => {
    const store = new SidebarStore({ storage: memoryStorage() });
    expect(store.getSnapshot()).toEqual({
      spaceFilter: null,
      lastSpaceId: null,
      archivedOpen: false,
      pinnedSessionIds: [],
      // The five view options ride along at their desktop defaults
      // (settings.rs:658-665) — ticket 10's menu writes them.
      organization: "inOneList",
      sort: "lastUpdated",
      showHarness: true,
      showBranch: true,
      showPullRequest: true,
    });
  });

  it("persists the filter and the last selected space across reloads", () => {
    const storage = memoryStorage();
    const first = new SidebarStore({ storage });
    first.setSpaceFilter("space-1");
    first.setSpaceFilter("space-2");
    first.setSpaceFilter(null);
    const second = new SidebarStore({ storage });
    // "All projects" is the filter; the last picked space survives as the
    // new-chat fallback, exactly like the desktop's last_space_id.
    expect(second.getSnapshot().spaceFilter).toBe(null);
    expect(second.getSnapshot().lastSpaceId).toBe("space-2");
  });

  it("keeps the archived disclosure in memory only", () => {
    const storage = memoryStorage();
    const first = new SidebarStore({ storage });
    first.setArchivedOpen(true);
    expect(first.getSnapshot().archivedOpen).toBe(true);
    expect(new SidebarStore({ storage }).getSnapshot().archivedOpen).toBe(false);
  });

  it("ignores corrupted legacy state without destroying it", () => {
    // Storage moved to the consolidated ui-settings key; the legacy key is a
    // one-time migration source now, so a corrupt one heals to the default and
    // is left exactly where it is for a rollback to find.
    const storage = memoryStorage();
    storage.setItem("roboco.sidebar.v1", "{not json");
    expect(new SidebarStore({ storage }).getSnapshot().spaceFilter).toBe(null);
    expect(storage.getItem("roboco.sidebar.v1")).toBe("{not json");
  });

  it("notifies subscribers on actual changes only", () => {
    const store = new SidebarStore({ storage: memoryStorage() });
    let fired = 0;
    store.subscribe(() => {
      fired += 1;
    });
    store.setSpaceFilter("space-1");
    store.setSpaceFilter("space-1");
    store.setArchivedOpen(true);
    store.setArchivedOpen(true);
    expect(fired).toBe(2);
  });

  it("set_chat_pinned: pins append in click order, unpins leave the rest", () => {
    const store = new SidebarStore({ storage: memoryStorage() });
    store.setChatPinned("a", true);
    store.setChatPinned("b", true);
    store.setChatPinned("c", true);
    expect(store.getSnapshot().pinnedSessionIds).toEqual(["a", "b", "c"]);
    store.setChatPinned("b", false);
    expect(store.getSnapshot().pinnedSessionIds).toEqual(["a", "c"]);
  });

  it("pin no-ops write nothing and notify nobody", () => {
    const store = new SidebarStore({ storage: memoryStorage() });
    store.setChatPinned("a", true);
    let fired = 0;
    store.subscribe(() => {
      fired += 1;
    });
    store.setChatPinned("a", true);
    store.setChatPinned("ghost", false);
    store.replacePinnedSessionIds(["a"]);
    expect(fired).toBe(0);
    expect(store.getSnapshot().pinnedSessionIds).toEqual(["a"]);
  });

  it("persists the pin order across reloads, device-local only", () => {
    const storage = memoryStorage();
    const first = new SidebarStore({ storage });
    first.setChatPinned("a", true);
    first.setChatPinned("b", true);
    first.replacePinnedSessionIds(["b", "a"]);
    const second = new SidebarStore({ storage });
    expect(second.getSnapshot().pinnedSessionIds).toEqual(["b", "a"]);
  });

  it("retain_known_pins: archived ids survive, deletions prune, a no-op stays silent", () => {
    const store = new SidebarStore({ storage: memoryStorage() });
    store.setChatPinned("active", true);
    store.setChatPinned("archived", true);
    store.setChatPinned("deleted", true);
    let fired = 0;
    store.subscribe(() => {
      fired += 1;
    });
    store.pruneUnknownPins(new Set(["active", "archived"]));
    expect(store.getSnapshot().pinnedSessionIds).toEqual(["active", "archived"]);
    expect(fired).toBe(1);
    store.pruneUnknownPins(new Set(["active", "archived"]));
    expect(fired).toBe(1);
  });
});

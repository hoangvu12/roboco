// @vitest-environment jsdom

/**
 * Ticket 26 — the settings dialog: the routed `settings-*.tsx` pages
 * reshaped into one portal + anchored-card overlay. The mounted contracts
 * pinned here (the real TanStack router with a memory history, the real
 * `SettingsLayout`, the real `SettingsDialog`, marker pages for the
 * sections — the section pages' own suites own their content):
 *
 * - **The section set and its shape**: every `/settings/<slug>` route opens
 *   the dialog on that section (deep-link parity), the nav lists
 *   `SettingsSection::ALL` minus Appshots in the desktop's order with the
 *   desktop's labels ("Providers", not "Agents"), grouped by spacing alone
 *   (the group-start classes on Harnesses and Files).
 * - **Focus lands on the dialog, not a first control** (the desktop's
 *   `settings_focus`): `document.activeElement` is the dialog card after
 *   mount, never a nav row or a page control.
 * - **Escape closes, Back closes**: both route through the shared close
 *   target — the nearest chat the nav stack holds, never history-back.
 * - **⌘/Ctrl+, toggles**: `toggleSettings` opens by navigating to
 *   `/settings` (the remembered-section redirect) and closes while open.
 * - **Arrow keys roam the nav AND navigate** — the desktop's roving
 *   section tabs.
 *
 * The mounted idiom follows settings-dialogs.test.ts: no JSX, per-file
 * jsdom pragma, Base UI in jsdom behind the matchMedia / ResizeObserver /
 * scrollIntoView / rAF stubs. The ui-settings store is the REAL one over
 * jsdom localStorage — reset between tests so the remembered-section
 * redirect is deterministic.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  RouterProvider,
} from "@tanstack/react-router";
import { SettingsLayout } from "../src/components/settings-layout";
import { settingsCloseTarget, toggleSettings } from "../src/lib/settings-close";
import { settingsIndexTarget } from "../src/state/settings-section";
import { uiSettings, UI_SETTINGS_STORAGE_KEY } from "../src/state/ui-settings";
import { navHistory, NavHistoryStore } from "../src/state/nav-history";

// ── jsdom gaps the mounted dialog hits (settings-dialogs.test.ts's set) ─────

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  window.matchMedia = ((query: string) => ({
    matches: query.startsWith("(max-width") === false,
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

// ── The mounted router harness ─────────────────────────────────────────────

/** One marker page per section: <div data-settings-page="<slug>" />. */
function SectionMarker(props: { readonly slug: string }) {
  return createElement("div", { "data-settings-page": props.slug }, `page:${props.slug}`);
}

function ChatMarker() {
  return createElement("div", { "data-chat-page": "1" }, "chat");
}

interface MountedRouter {
  /** The live router — structurally typed (createRouter's generics are internal). */
  readonly router: {
    readonly state: { readonly location: { readonly pathname: string } };
    readonly history: { push: (path: string) => void };
  };
  readonly container: HTMLDivElement;
  unmount(): void;
}

const mounted: MountedRouter[] = [];

/** The settings tree: /, /chat/$chatId, and /settings/* through the real layout. */
function mountSettingsRouter(initial: string): MountedRouter {
  const rootRoute = createRootRoute();
  const shellRoute = createRoute({
    getParentRoute: () => rootRoute,
    id: "shell",
    component: () => createElement(Outlet),
  });
  const indexRoute = createRoute({ getParentRoute: () => shellRoute, path: "/", component: ChatMarker });
  const chatRoute = createRoute({
    getParentRoute: () => shellRoute,
    path: "/chat/$chatId",
    component: ChatMarker,
  });
  const settingsRoute = createRoute({ getParentRoute: () => shellRoute, path: "/settings", component: SettingsLayout });
  const settingsIndexRoute = createRoute({
    getParentRoute: () => settingsRoute,
    path: "/",
    beforeLoad: () => {
      throw redirect({ href: settingsIndexTarget(uiSettings.getSnapshot().settingsSection) });
    },
  });
  const slugs = [
    "general",
    "appearance",
    "notifications",
    "shortcuts",
    "harnesses",
    "accounts",
    "devices",
    "remote-access",
    "files",
    "archived",
  ] as const;
  const sectionRoutes = slugs.map((slug) =>
    createRoute({
      getParentRoute: () => settingsRoute,
      path: `/${slug}`,
      component: () => createElement(SectionMarker, { slug }),
    }),
  );
  const routeTree = rootRoute.addChildren([
    shellRoute.addChildren([
      indexRoute,
      chatRoute,
      settingsRoute.addChildren([settingsIndexRoute, ...sectionRoutes]),
    ]),
  ]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initial] }),
  });
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(createElement(RouterProvider, { router }));
  });
  const handle: MountedRouter = {
    router,
    container,
    unmount() {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
  mounted.push(handle);
  return handle;
}

beforeEach(() => {
  localStorage.removeItem(UI_SETTINGS_STORAGE_KEY);
  // The nav-history singleton is real module state (the app-shell's route
  // effect is what feeds it in the app; the mounted tree here drives it by
  // hand) — reset to the untouched boot canvas so close targets are
  // deterministic per test.
  navHistory.resetForTest();
  // The ui-settings singleton is real (module-level, localStorage-backed);
  // pin the remembered section per test so the index redirect is
  // deterministic — default General unless a test pins its own.
  uiSettings.updateImmediate({ settingsSection: "general" });
});

afterEach(() => {
  while (mounted.length > 0) {
    mounted.pop()!.unmount();
  }
  document.body.replaceChildren();
});

// ── The dialog's section set, focus, and dismissal ─────────────────────────

describe("the settings dialog (ticket 26)", () => {
  it("opens on the section a deep link names — the route IS the dialog's open state", async () => {
    const h = mountSettingsRouter("/settings/devices");
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/devices");
    expect(document.querySelector('[data-settings-page="devices"]')).not.toBeNull();
    // The overlay exists: the glass card with the scrim sibling.
    expect(document.querySelector(".settings-dialog-card")).not.toBeNull();
    expect(document.querySelector(".modal-glass-backdrop")).not.toBeNull();
  });

  it("focus lands on the dialog card, never a first control", async () => {
    const h = mountSettingsRouter("/settings/general");
    await act(async () => {});
    const card = document.querySelector<HTMLElement>(".settings-dialog-card");
    expect(card).not.toBeNull();
    expect(document.activeElement).toBe(card);
    // Not the Back row, not a nav link, not a page control.
    expect(document.activeElement?.tagName).toBe("DIV");
    expect((document.activeElement as HTMLElement).className).toContain("settings-dialog-card");
  });

  it("lists the desktop's section set in its order and grouping", async () => {
    mountSettingsRouter("/settings/general");
    await act(async () => {});
    const rows = Array.from(document.querySelectorAll<HTMLAnchorElement>(".settings-dialog-sections .settings-nav-link"));
    expect(rows.map((row) => row.textContent?.trim())).toEqual([
      "General",
      "Appearance",
      "Notifications",
      "Shortcuts",
      "Providers",
      "Accounts",
      "Devices",
      "Remote access",
      "Files",
      "Archived sessions",
    ]);
    // Group starts: Harnesses ("Providers") and Files — spacing alone.
    const groupStarts = rows.filter((row) => row.classList.contains("settings-nav-group-start"));
    expect(groupStarts.map((row) => row.textContent?.trim())).toEqual(["Providers", "Files"]);
    // The active row is the section the URL names.
    expect(rows.find((row) => row.classList.contains("settings-nav-active"))?.textContent?.trim()).toBe("General");
    // Back is pinned above the sections.
    const back = document.querySelector<HTMLButtonElement>(".settings-dialog-back");
    expect(back?.textContent).toContain("Back");
  });

  it("a nav row's click switches the section under the dialog without closing it", async () => {
    const h = mountSettingsRouter("/settings/general");
    await act(async () => {});
    const row = Array.from(document.querySelectorAll<HTMLAnchorElement>(".settings-nav-link")).find(
      (candidate) => candidate.textContent?.trim() === "Shortcuts",
    )!;
    await act(async () => {
      row.click();
    });
    expect(h.router.state.location.pathname).toBe("/settings/shortcuts");
    expect(document.querySelector('[data-settings-page="shortcuts"]')).not.toBeNull();
    // The dialog survived the switch — it is the route shell, not a page.
    expect(document.querySelector(".settings-dialog-card")).not.toBeNull();
    // The remembered section follows the visit.
    expect(uiSettings.getSnapshot().settingsSection).toBe("shortcuts");
  });

  it("Escape closes the dialog through the close target — the nearest chat, never history-back", async () => {
    const h = mountSettingsRouter("/chat/abc");
    await act(async () => {});
    // The app-shell's route effect, by hand: every navigation the test
    // makes is a visit (the mounted tree has no AppShell).
    navHistory.visit({ kind: "chat", chatId: "abc" });
    // Open via the ⌘, toggle's open arm: /settings (remembered redirect).
    await act(async () => {
      toggleSettings(h.router.state.location.pathname, h.router);
    });
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/general");
    navHistory.visit({ kind: "settings", section: "general" });
    // A second chat below the settings entry — the close target's nearest.
    await act(async () => {
      h.router.history.push("/chat/def");
    });
    await act(async () => {});
    navHistory.visit({ kind: "chat", chatId: "def" });
    await act(async () => {
      h.router.history.push("/settings/files");
    });
    await act(async () => {});
    navHistory.visit({ kind: "settings", section: "files" });
    // Escape — Base UI's document-level dialog dismissal (the pipeline the
    // rename/delete dialogs' suite drives the same way).
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    });
    await act(async () => {});
    expect(document.querySelector(".settings-dialog-card")).toBeNull();
    expect(h.router.state.location.pathname).toBe("/chat/def");
    expect(document.querySelector('[data-chat-page="1"]')).not.toBeNull();
  });

  it("the Back row closes through the same target", async () => {
    const h = mountSettingsRouter("/settings/notifications");
    await act(async () => {});
    // No chat in the stack: the blank canvas is the target.
    await act(async () => {
      document.querySelector<HTMLButtonElement>(".settings-dialog-back")!.click();
    });
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/");
  });

  it("arrow keys roam the nav AND navigate — the desktop's roving section tabs", async () => {
    const h = mountSettingsRouter("/settings/general");
    await act(async () => {});
    const nav = document.querySelector<HTMLElement>(".settings-dialog-nav")!;
    const rows = () => Array.from(document.querySelectorAll<HTMLAnchorElement>(".settings-dialog-sections .settings-nav-link"));
    // Focus the General row, ArrowDown: Appearance opens and takes focus.
    rows()[0]!.focus();
    await act(async () => {
      nav.dispatchEvent(
        new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }),
      );
    });
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/appearance");
    expect(document.activeElement).toBe(rows()[1]);
    // End: the last section.
    await act(async () => {
      nav.dispatchEvent(new KeyboardEvent("keydown", { key: "End", bubbles: true }));
    });
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/archived");
    // Home: the first.
    await act(async () => {
      nav.dispatchEvent(new KeyboardEvent("keydown", { key: "Home", bubbles: true }));
    });
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/general");
  });

  it("the /settings index redirects to the remembered section (deep-link parity for generic entries)", async () => {
    uiSettings.updateImmediate({ settingsSection: "remote-access" });
    const h = mountSettingsRouter("/settings");
    await act(async () => {});
    expect(h.router.state.location.pathname).toBe("/settings/remote-access");
    expect(document.querySelector('[data-settings-page="remote-access"]')).not.toBeNull();
  });
});

// ── The toggle and the close target (pure over the nav-history store) ──────

describe("toggleSettings (the ⌘/Ctrl+, dispatch)", () => {
  it("opens by pushing /settings when not already open — the index redirect takes over", () => {
    const pushed: string[] = [];
    toggleSettings("/chat/abc", { history: { push: (path) => pushed.push(path) } });
    expect(pushed).toEqual(["/settings"]);
  });

  it("closes through the shared close target while open", () => {
    const pushed: string[] = [];
    toggleSettings("/settings/general", { history: { push: (path) => pushed.push(path) } });
    // No chat in the live store: the blank canvas. (The requestAnimationFrame
    // composer focus runs against the unmounted page — harmless.)
    expect(pushed.length).toBe(1);
  });
});

describe("settingsCloseTarget (close_settings, shell.rs:3281-3286)", () => {
  it("returns the nearest chat at or behind the cursor", () => {
    const history = new NavHistoryStore({ kind: "chat", chatId: "abc" });
    history.push({ kind: "chat", chatId: "def" });
    history.push({ kind: "settings", section: "general" });
    expect(settingsCloseTarget(history)).toBe("/chat/def");
  });

  it("skips earlier settings visits to the chat below them", () => {
    const history = new NavHistoryStore({ kind: "chat", chatId: "abc" });
    history.push({ kind: "settings", section: "general" });
    history.push({ kind: "settings", section: "files" });
    expect(settingsCloseTarget(history)).toBe("/chat/abc");
  });

  it("falls back to the blank canvas when no chat was ever opened", () => {
    const history = new NavHistoryStore({ kind: "chat", chatId: "" });
    history.push({ kind: "settings", section: "general" });
    expect(settingsCloseTarget(history)).toBe("/");
  });
});

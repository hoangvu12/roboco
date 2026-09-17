import { useSyncExternalStore } from "react";
import { rightPaneMaxWidth, rightPaneTakeoverWidth } from "./layout";
import { RIGHT_PANE_DEFAULT, RIGHT_PANE_MIN, uiSettings } from "./ui-settings";

/**
 * The right pane's open/active state — the desktop's per-chat right-pane
 * flags (`shell.rs`: `right_pane_open`, `right_pane_expanded`,
 * `resolved_right_active`).
 *
 * The pane is chat-scoped chrome: each chat remembers whether its pane was
 * open, which surface was active, and whether it had taken over the window,
 * so returning to a chat restores what you left. Settings never renders it.
 *
 * Width is the one exception, and it follows the desktop: `rightPaneWidth` is
 * a single GLOBAL preference in `ui-settings.ts`, not a per-chat value. A chat
 * opening its pane for the first time inherits the width last dragged
 * anywhere, and a drag writes that width back for every chat that has not
 * diverged within the session. Open/expanded/active/tabs stay in memory —
 * the desktop's persisted `right_pane_open` is legacy and unread.
 */

/** The surfaces the web client can host. The desktop's `RightSurface` also
 *  carries File, Subagent, and Browser tabs, which have no web peer yet. */
export type RightSurface = "changes" | "files" | "terminal" | "preview";

export const RIGHT_SURFACES: readonly RightSurface[] = ["changes", "files", "terminal", "preview"];

/** Tab labels, matching the desktop's surface titles. */
export const SURFACE_TITLES: Record<RightSurface, string> = {
  changes: "Changes",
  files: "Files",
  terminal: "Terminal",
  preview: "Preview",
};

/**
 * Tab glyphs — `shell.rs`'s `icon_path` table: a diff is the list glyph,
 * Files the folder-with-files, Terminal and Browser their own marks.
 */
export const SURFACE_ICONS: Record<RightSurface, "list" | "folderWithFiles" | "terminal" | "globe"> = {
  changes: "list",
  files: "folderWithFiles",
  terminal: "terminal",
  preview: "globe",
};

export interface ChatPaneState {
  readonly open: boolean;
  /** Takeover: the pane's width derives from the viewport, not the drag. */
  readonly expanded: boolean;
  readonly active: RightSurface;
  /** Tab order, reorderable by drag like the desktop's strip. */
  readonly tabs: readonly RightSurface[];
  readonly width: number;
}

/**
 * `settings.rs` RIGHT_PANE_DEFAULT / _MIN, re-exported from the settings store
 * that owns them.
 */
export { RIGHT_PANE_DEFAULT, RIGHT_PANE_MIN };

function initial(): ChatPaneState {
  return {
    open: false,
    expanded: false,
    active: "changes",
    tabs: RIGHT_SURFACES,
    // The persisted global width — what was last dragged, healed to its floor.
    width: uiSettings.getSnapshot().rightPaneWidth,
  };
}

class RightPaneStore {
  #byChat = new Map<string, ChatPaneState>();
  #version = 0;
  readonly #listeners = new Set<() => void>();

  getVersion = (): number => this.#version;

  subscribe = (listener: () => void): (() => void) => {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  };

  stateFor(chatId: string): ChatPaneState {
    return this.#byChat.get(chatId) ?? initial();
  }

  #update(chatId: string, next: (current: ChatPaneState) => ChatPaneState): void {
    this.#byChat.set(chatId, next(this.stateFor(chatId)));
    this.#version += 1;
    for (const listener of this.#listeners) {
      listener();
    }
  }

  /** The titlebar's one trailing control (`toggle-changes`). */
  toggle(chatId: string): void {
    this.#update(chatId, (current) => ({ ...current, open: !current.open }));
  }

  /**
   * Open the pane on a given surface — what a shortcut or a link into a
   * surface does. Re-picking the active surface while open closes the pane,
   * matching the desktop's toggle semantics for its panel shortcuts.
   */
  show(chatId: string, surface: RightSurface): void {
    this.#update(chatId, (current) =>
      current.open && current.active === surface
        ? { ...current, open: false }
        : { ...current, open: true, active: surface },
    );
  }

  setActive(chatId: string, surface: RightSurface): void {
    this.#update(chatId, (current) => ({ ...current, open: true, active: surface }));
  }

  toggleExpanded(chatId: string): void {
    this.#update(chatId, (current) => ({ ...current, expanded: !current.expanded }));
  }

  close(chatId: string): void {
    this.#update(chatId, (current) => ({ ...current, open: false, expanded: false }));
  }

  /**
   * A drag sample. `max` is the room left by the sidebar and the
   * conversation's floor (`rightPaneMaxWidth`) — the desktop's
   * `on_right_pane_drag`: clamp into [MIN, max] when both fit, and when they
   * cannot, hand the scarce space to the conversation and let the pane sit
   * below its own minimum.
   */
  setWidth(chatId: string, width: number, max: number): void {
    const clamped =
      max >= RIGHT_PANE_MIN ? Math.min(max, Math.max(RIGHT_PANE_MIN, width)) : max;
    this.#update(chatId, (current) => ({ ...current, width: clamped }));
    // The drag also moves the global default, coalesced into one write.
    uiSettings.update({ rightPaneWidth: clamped }, "debounced");
  }

  /** Double-clicking the seam restores the default (`shell.rs:7953`). */
  resetWidth(chatId: string): void {
    this.#update(chatId, (current) => ({ ...current, width: RIGHT_PANE_DEFAULT }));
    uiSettings.update({ rightPaneWidth: RIGHT_PANE_DEFAULT }, "immediate");
  }

  /** Drag-reorder in the tab strip. */
  moveTab(chatId: string, from: number, to: number): void {
    this.#update(chatId, (current) => {
      if (from === to || from < 0 || from >= current.tabs.length) {
        return current;
      }
      const tabs = [...current.tabs];
      const [moved] = tabs.splice(from, 1);
      if (moved === undefined) {
        return current;
      }
      tabs.splice(Math.max(0, Math.min(tabs.length, to)), 0, moved);
      return { ...current, tabs };
    });
  }
}

export const rightPaneStore = new RightPaneStore();

/**
 * The pane's laid-out width — the desktop's `shell.rs::right_target`. The
 * stored width is what the user dragged; what it resolves to depends on the
 * window and the sidebar, so a narrowing window shrinks the pane without
 * destroying the width the user chose.
 */
export function resolvePaneWidth(
  pane: ChatPaneState,
  viewport: number,
  sidebar: number,
): number {
  if (!pane.open) {
    return 0;
  }
  if (pane.expanded) {
    return rightPaneTakeoverWidth(viewport, sidebar);
  }
  return Math.min(pane.width, rightPaneMaxWidth(viewport, sidebar));
}

export function useRightPane(chatId: string): ChatPaneState {
  useSyncExternalStore(rightPaneStore.subscribe, rightPaneStore.getVersion);
  return rightPaneStore.stateFor(chatId);
}

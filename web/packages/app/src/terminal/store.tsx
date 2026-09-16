import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { Terminal as XTerm } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import type { EngineSession } from "../state/engine-session";
import { useEngineSession } from "../state/session-provider";
import { TerminalSessionController } from "./session";
import { currentTerminalTheme } from "./theme";
import {
  TERMINAL_DEFAULT_HEIGHT,
  activeAfterClose,
  activeAfterReorder,
  clampTerminalHeight,
  reorderTabs,
  shellTitle,
} from "./tabs";

/**
 * The terminal dock's state, the web peer of the desktop's `TerminalPanel`
 * entity (`crates/ui/src/terminal/panel.rs`): tabs are per selected chat and
 * restored on return — the provider that owns this store lives above the
 * route outlet, so chat navigation keeps PTYs, emulators, and panel flags
 * alive (detach is not close).
 *
 * Lifetime: one store per provider mount, bound to the current engine
 * session. A session swap (engine switch, re-pair) closes every tab — the
 * PTYs belong to the old connection's watches; the desktop never faces this
 * because its panel's targets float across devices.
 *
 * React reads the store through a version counter (useSyncExternalStore);
 * tab records hold the mutable xterm instances and are never recreated.
 */
export interface TerminalTabRecord {
  readonly key: number;
  /** Fallback label: "Terminal N", then the shell basename once open answers. */
  title: string;
  /** The live OSC 0/2 title when the running program set one — it wins. */
  oscTitle: string | null;
  exited: boolean;
  readonly term: XTerm;
  readonly fitter: FitAddon;
  readonly controller: TerminalSessionController;
  /** `OpenTerminal` was requested (guards double-open on re-attach). */
  openRequested: boolean;
}

export interface ChatTerminals {
  open: boolean;
  height: number;
  tabs: TerminalTabRecord[];
  active: number;
  nextKey: number;
}

export type TerminalStoreListener = () => void;

export class TerminalStore {
  #session: EngineSession | null = null;
  readonly #chats = new Map<string, ChatTerminals>();
  readonly #listeners = new Set<TerminalStoreListener>();
  #version = 0;

  subscribe = (listener: TerminalStoreListener): (() => void) => {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  };

  getVersion = (): number => this.#version;

  /** Bind to a new engine session, closing everything the old one owned. */
  bindSession(session: EngineSession | null): void {
    if (session === this.#session) {
      return;
    }
    this.#session = session;
    for (const chat of this.#chats.values()) {
      for (const tab of chat.tabs) {
        tab.controller.close();
        tab.term.dispose();
      }
    }
    this.#chats.clear();
    this.#bump();
  }

  dispose(): void {
    this.bindSession(null);
    this.#listeners.clear();
  }

  stateFor(chatId: string): ChatTerminals | undefined {
    return this.#chats.get(chatId);
  }

  /** mod-j / header button: toggle the dock; opening an empty chat spawns
   *  its first tab (desktop `ensure_tab`). */
  toggle(chatId: string): void {
    const chat = this.#chat(chatId);
    chat.open = !chat.open;
    if (chat.open && chat.tabs.length === 0) {
      this.#addTab(chatId, chat);
    }
    this.#bump();
  }

  setHeight(chatId: string, height: number, viewportH: number): void {
    const chat = this.#chat(chatId);
    const clamped = clampTerminalHeight(height, viewportH);
    if (clamped !== chat.height) {
      chat.height = clamped;
      this.#bump();
    }
  }

  /** The "+" button. The PTY opens when the dock mounts the tab's host. */
  addTab(chatId: string): void {
    const chat = this.#chat(chatId);
    this.#addTab(chatId, chat);
    this.#bump();
  }

  selectTab(chatId: string, index: number): void {
    const chat = this.#chats.get(chatId);
    if (chat !== undefined && index < chat.tabs.length && chat.active !== index) {
      chat.active = index;
      this.#bump();
    }
  }

  /** The tab's ×, or middle-click. Closing the last tab collapses the dock —
   *  an empty dock is dead space (desktop close_tab dispatches the toggle). */
  closeTab(chatId: string, key: number): void {
    const chat = this.#chats.get(chatId);
    if (chat === undefined) {
      return;
    }
    const ix = chat.tabs.findIndex((tab) => tab.key === key);
    if (ix === -1) {
      return;
    }
    const [tab] = chat.tabs.splice(ix, 1);
    tab!.controller.close();
    tab!.term.dispose();
    chat.active = activeAfterClose(chat.active, ix, chat.tabs.length);
    if (chat.tabs.length === 0 && chat.open) {
      chat.open = false;
    }
    this.#bump();
  }

  /** Drag-reorder commit (desktop commit_reorder). */
  reorderTab(chatId: string, from: number, to: number): void {
    const chat = this.#chats.get(chatId);
    if (chat === undefined || from === to) {
      return;
    }
    reorderTabs(chat.tabs, from, to);
    chat.active = activeAfterReorder(chat.active, from, to);
    this.#bump();
  }

  /**
   * Mount (or re-mount) a tab's host element: the xterm element moves into
   * it, the fitter measures, and the first mount fires `OpenTerminal` with
   * the real grid size. Re-mounts (chat navigation, panel reopen) only
   * re-attach and re-fit — the PTY and emulator state persist.
   */
  attachTab(chatId: string, key: number, host: HTMLElement): void {
    const chat = this.#chats.get(chatId);
    const tab = chat?.tabs.find((candidate) => candidate.key === key);
    if (chat === undefined || tab === undefined) {
      return;
    }
    const element = tab.term.element;
    if (element === undefined) {
      tab.term.open(host);
    } else if (element.parentElement !== host) {
      host.appendChild(element);
    }
    this.fitActive(chatId);
    if (!tab.openRequested && this.#session !== null) {
      tab.openRequested = true;
      void tab.controller.open(tab.term.cols, tab.term.rows).then(() => {
        const shell = tab.controller.shell;
        if (shell !== null) {
          tab.title = shellTitle(shell);
          this.#bump();
        }
      });
    }
  }

  /** Fit the active tab's emulator to its host and debounce the resize RPC
   *  (the emulator itself resizes immediately, desktop on_grid_metrics). */
  fitActive(chatId: string): void {
    const chat = this.#chats.get(chatId);
    const tab = chat?.tabs[chat.active];
    if (chat === undefined || tab === undefined || tab.term.element === undefined) {
      return;
    }
    tab.fitter.fit();
    tab.controller.resize(tab.term.cols, tab.term.rows);
  }

  /** Focus the active tab's input (tab select, panel open, "+" click). */
  focusActive(chatId: string): void {
    const chat = this.#chats.get(chatId);
    chat?.tabs[chat.active]?.term.focus();
  }

  /** Re-apply the installed variant's terminal roles to every emulator. */
  retheme(): void {
    const theme = currentTerminalTheme();
    if (theme === undefined) {
      return;
    }
    for (const chat of this.#chats.values()) {
      for (const tab of chat.tabs) {
        tab.term.options.theme = theme;
      }
    }
  }

  #chat(chatId: string): ChatTerminals {
    let chat = this.#chats.get(chatId);
    if (chat === undefined) {
      chat = { open: false, height: TERMINAL_DEFAULT_HEIGHT, tabs: [], active: 0, nextKey: 1 };
      this.#chats.set(chatId, chat);
    }
    return chat;
  }

  #addTab(chatId: string, chat: ChatTerminals): void {
    if (this.#session === null) {
      return;
    }
    const client = this.#session.client;
    const key = chat.nextKey++;
    const theme = currentTerminalTheme();
    const term = new XTerm({
      fontFamily: '"Geist Mono", ui-monospace, monospace',
      fontSize: 13,
      lineHeight: 18 / 13,
      cursorStyle: "block",
      theme,
    });
    const fitter = new FitAddon();
    term.loadAddon(fitter);
    const tab: TerminalTabRecord = {
      key,
      title: `Terminal ${chat.tabs.length + 1}`,
      oscTitle: null,
      exited: false,
      term,
      fitter,
      controller: new TerminalSessionController({
        client,
        chatId,
        sink: {
          write: (bytes) => term.write(bytes),
          exited: () => {
            tab.exited = true;
            this.#bump();
          },
        },
      }),
      openRequested: false,
    };
    term.onData((data) => tab.controller.input(data));
    term.onTitleChange((title) => {
      tab.oscTitle = title;
      this.#bump();
    });
    chat.tabs.push(tab);
    chat.active = chat.tabs.length - 1;
  }

  #bump(): void {
    this.#version += 1;
    for (const listener of this.#listeners) {
      listener();
    }
  }
}

/** A tab's display label: the OSC title when set, else the fallback. */
export function displayTitle(tab: TerminalTabRecord): string {
  const osc = tab.oscTitle?.trim();
  return osc !== undefined && osc.length > 0 ? osc : tab.title;
}

const TerminalStoreContext = createContext<TerminalStore | null>(null);

/**
 * Owns the terminal store above the route outlet (mirroring the desktop's
 * shell-level panel entity): chat navigation keeps tabs alive. Bound to the
 * current engine session; a session swap closes every tab. Also tracks the
 * installed theme variant — `<html>`'s style attribute is where
 * `installThemeVariant` writes, so a variant change re-themes every emulator.
 */
export function TerminalProvider({ children }: { children: ReactNode }) {
  const session = useEngineSession();
  const [store] = useState(() => new TerminalStore());

  useEffect(() => {
    store.bindSession(session);
  }, [store, session]);

  useEffect(() => {
    store.retheme();
    const observer = new MutationObserver(() => store.retheme());
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["style"] });
    return () => observer.disconnect();
  }, [store]);

  useEffect(() => () => store.dispose(), [store]);

  return <TerminalStoreContext.Provider value={store}>{children}</TerminalStoreContext.Provider>;
}

export function useTerminalStore(): TerminalStore {
  const store = useContext(TerminalStoreContext);
  if (store === null) {
    throw new Error("useTerminalStore outside TerminalProvider");
  }
  return store;
}

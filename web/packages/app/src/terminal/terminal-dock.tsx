import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { PointerEvent as ReactPointerEvent, KeyboardEvent as ReactKeyboardEvent } from "react";
import "@xterm/xterm/css/xterm.css";
import type { ChatTerminals, TerminalStore, TerminalTabRecord } from "./store";
import { displayTitle } from "./store";
import { TAB_WIDTH, dropIndex, slideOffset } from "./tabs";

/**
 * The terminal dock: a bottom panel per selected chat with tabs over engine
 * PTYs — the web peer of the desktop's terminal panel (feature-inventory
 * §1.10). The tab bar keeps the desktop's fixed-width tabs, pointer
 * drag-reorder with sliding transforms, middle-click close, and a "+" new-tab
 * button; mod-j toggles the panel. A drag handle on the top edge resizes the
 * dock (160 px … 55 % of the viewport, like the desktop clamp).
 *
 * State lives in the `TerminalStore` above the route outlet, so navigating
 * between chats detaches the dock without closing the PTYs.
 */
export function TerminalDock({
  store,
  chatId,
  docked = false,
}: {
  store: TerminalStore;
  chatId: string;
  /**
   * Rendered as the right pane's Terminal surface rather than as a bottom
   * dock: it fills its host, so it drops the height drag and the fixed
   * height. The desktop's terminal is a pane surface too — the bottom dock is
   * the web's phone-width form.
   */
  docked?: boolean;
}) {
  useSyncExternalStore(store.subscribe, store.getVersion);
  const chat = store.stateFor(chatId);

  // Mod+J is bound by the chat page, above the pane: this component only
  // mounts while its own tab is active, so a binding here could not reveal it.

  if (chat === undefined || !chat.open) {
    return null;
  }
  return <DockBody store={store} chatId={chatId} chat={chat} docked={docked} />;
}

function DockBody({
  store,
  chatId,
  chat,
  docked,
}: {
  store: TerminalStore;
  chatId: string;
  chat: ChatTerminals;
  docked: boolean;
}) {
  const bodyRef = useRef<HTMLDivElement | null>(null);

  // Keep the active emulator fitted to the body: drag-resizes, window
  // resizes, and tab switches all land here. The emulator resizes
  // immediately; `ResizeTerminal` debounces inside the controller.
  useEffect(() => {
    const body = bodyRef.current;
    if (body === null) {
      return;
    }
    store.fitActive(chatId);
    store.focusActive(chatId);
    const observer = new ResizeObserver(() => store.fitActive(chatId));
    observer.observe(body);
    return () => observer.disconnect();
  }, [store, chatId, chat.active, chat.height]);

  const startHeightDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const startY = event.clientY;
    const startHeight = chat.height;
    const onMove = (move: PointerEvent) => {
      store.setHeight(chatId, startHeight + (startY - move.clientY), window.innerHeight);
    };
    const onUp = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  return (
    <section
      className={`term-dock ${docked ? "term-dock-surface" : ""}`}
      style={docked ? undefined : { height: chat.height }}
      aria-label="Terminal"
    >
      {!docked && <div className="term-dock-handle" onPointerDown={startHeightDrag} />}
      <TabBar store={store} chatId={chatId} chat={chat} />
      <div className="term-body" ref={bodyRef}>
        {chat.tabs.map((tab, ix) => (
          <div
            key={tab.key}
            className={ix === chat.active ? "term-host" : "term-host term-host-hidden"}
            ref={(host) => {
              if (host !== null) {
                store.attachTab(chatId, tab.key, host);
              }
            }}
          />
        ))}
      </div>
    </section>
  );
}

interface DragState {
  readonly from: number;
  readonly over: number;
  /** Pointer travel in px — the dragged tab follows the cursor. */
  readonly dx: number;
}

function TabBar({ store, chatId, chat }: { store: TerminalStore; chatId: string; chat: ChatTerminals }) {
  const [drag, setDrag] = useState<DragState | null>(null);
  const stripRef = useRef<HTMLDivElement | null>(null);
  // A committed drag is followed by a click carrying the pre-drag index —
  // swallow it (the reorder already tracked the active tab).
  const suppressClick = useRef(false);

  const startDrag = (event: ReactPointerEvent, from: number) => {
    if (event.button !== 0) {
      return;
    }
    event.preventDefault();
    const strip = stripRef.current;
    if (strip === null) {
      return;
    }
    let moved = false;
    const startX = event.clientX;
    const onMove = (move: PointerEvent) => {
      const relX = move.clientX - strip.getBoundingClientRect().left;
      const over = dropIndex(relX, TAB_WIDTH, chat.tabs.length);
      moved = moved || Math.abs(move.clientX - startX) > 4;
      // Keep the dragged tab inside the strip.
      const dx = Math.min(Math.max(move.clientX - startX, -from * TAB_WIDTH), (chat.tabs.length - 1 - from) * TAB_WIDTH);
      setDrag((current) =>
        current !== null && current.from === from && current.over === over && current.dx === dx
          ? current
          : { from, over, dx },
      );
    };
    const onUp = (up: PointerEvent) => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      const relX = up.clientX - strip.getBoundingClientRect().left;
      const over = dropIndex(relX, TAB_WIDTH, chat.tabs.length);
      setDrag(null);
      if (moved) {
        suppressClick.current = true;
        store.reorderTab(chatId, from, over);
      }
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  const select = (ix: number) => {
    if (suppressClick.current) {
      suppressClick.current = false;
      return;
    }
    store.selectTab(chatId, ix);
    store.focusActive(chatId);
  };

  return (
    <div className="term-tabbar">
      <div className="term-tabstrip" ref={stripRef}>
        {chat.tabs.map((tab, ix) => {
          const dragging = drag !== null && drag.from === ix;
          const slide = drag === null || dragging ? 0 : slideOffset(ix, drag.from, drag.over);
          return (
            <TabChip
              key={tab.key}
              tab={tab}
              selected={ix === chat.active}
              transform={dragging ? `translateX(${drag.dx}px)` : slide === 0 ? undefined : `translateX(${slide * TAB_WIDTH}px)`}
              dragging={dragging}
              onSelect={() => select(ix)}
              onClose={() => store.closeTab(chatId, tab.key)}
              onDragStart={(event) => startDrag(event, ix)}
            />
          );
        })}
        <button
          type="button"
          className="term-tab-add"
          aria-label="New terminal"
          title="New terminal"
          onClick={() => {
            store.addTab(chatId);
            store.focusActive(chatId);
          }}
        >
          +
        </button>
      </div>
      <button
        type="button"
        className="term-tab-add"
        aria-label="Close terminal panel"
        title="Close panel"
        onClick={() => store.toggle(chatId)}
      >
        ×
      </button>
    </div>
  );
}

function TabChip({
  tab,
  selected,
  transform,
  dragging,
  onSelect,
  onClose,
  onDragStart,
}: {
  tab: TerminalTabRecord;
  selected: boolean;
  transform: string | undefined;
  dragging: boolean;
  onSelect: () => void;
  onClose: () => void;
  onDragStart: (event: ReactPointerEvent) => void;
}) {
  return (
    <div
      className={`term-tab${selected ? " term-tab-active" : ""}${dragging ? " term-tab-dragging" : ""}`}
      style={{ transform }}
      role="tab"
      aria-selected={selected}
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event: ReactKeyboardEvent) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onSelect();
        }
      }}
      onPointerDown={onDragStart}
      onAuxClick={(event) => {
        if (event.button === 1) {
          event.preventDefault();
          onClose();
        }
      }}
      title={displayTitle(tab)}
    >
      <span className="term-tab-title">{displayTitle(tab)}</span>
      {tab.exited ? <span className="term-tab-exited" aria-label="exited" /> : null}
      <button
        type="button"
        className="term-tab-close"
        aria-label="Close terminal"
        onClick={(event) => {
          event.stopPropagation();
          onClose();
        }}
        onPointerDown={(event) => event.stopPropagation()}
      >
        ×
      </button>
    </div>
  );
}

/**
 * A tiny module-level event bus for cross-component shortcuts. The keyboard
 * layer in `AppShell` dispatches named actions (e.g. "new-chat"); the
 * components that own those actions subscribe here. Keeps the keyboard
 * layer free of component imports and lets the components own their own
 * execution paths — the same trick the desktop uses for its menu actions.
 */

/**
 * `"open-engines"` opens the engine drawer. It is a shortcut rather than a
 * prop because the drawer's open flag lives in `AppShell` while its trigger
 * lives in the sidebar's user menu — the desktop reaches Devices the same
 * way, from the user menu rather than the titlebar.
 */
export type ShortcutEvent = "new-chat" | "open-engines";

const listeners = new Map<ShortcutEvent, Set<() => void>>();

export function onShortcut(event: ShortcutEvent, listener: () => void): () => void {
  let set = listeners.get(event);
  if (set === undefined) {
    set = new Set();
    listeners.set(event, set);
  }
  set.add(listener);
  return () => {
    set!.delete(listener);
    if (set!.size === 0) {
      listeners.delete(event);
    }
  };
}

export function emitShortcut(event: ShortcutEvent): void {
  const set = listeners.get(event);
  if (set === undefined) {
    return;
  }
  for (const listener of [...set]) {
    try {
      listener();
    } catch {
      // A misbehaving listener should not stop the rest from running.
    }
  }
}

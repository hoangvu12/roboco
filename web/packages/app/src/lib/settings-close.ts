import { navHistory, navEntryPath, type NavHistoryStore } from "../state/nav-history";

/**
 * The settings dialog's close target — the web peer of the desktop's
 * `close_settings` (shell.rs:3878-3885): Back from Settings is NOT
 * history-back, it is an unconditional return to the active chat (the
 * nearest chat entry the nav stack holds; the blank canvas when there is
 * none). The dialog's every exit (Escape, ⌘/Ctrl+,, the Back row) routes
 * through here, exactly as the old settings-nav's Back row did.
 */

/**
 * The path the dialog closes to: the nearest chat at or behind the nav
 * cursor, or the blank canvas when Settings was opened from nowhere
 * (a pasted deep link on a fresh tab). Pure over the nav-history store so
 * the suite can drive the whole truth table.
 */
export function settingsCloseTarget(history: NavHistoryStore = navHistory): string {
  const target = history.nearestChat();
  return target === null ? "/" : navEntryPath(target);
}

/**
 * Close the settings dialog: return to the active chat and hand focus to
 * the composer. The chat page mounts across the navigation, so the
 * composer needs a frame before it can take focus — the same deferred
 * handoff the old settings-nav's Back row (and the terminal dock's focus
 * return) used.
 */
export function closeSettingsDialog(router: { history: { push: (path: string) => void } }): void {
  router.history.push(settingsCloseTarget());
  requestAnimationFrame(() => {
    document.querySelector<HTMLTextAreaElement>(".composer-input")?.focus();
  });
}

/**
 * The ⌘/Ctrl+, toggle (`toggle_settings`, shell.rs:3934-3941): open from
 * anywhere by navigating to `/settings` — whose index redirect reopens the
 * remembered section — and close while open through the shared close target.
 * Raw `router.history.push`, not `navigate({to})`: the app-shell dispatch,
 * the back/forward walk, and the settings suite all drive the same raw path
 * (a computed route path cannot ride the typed `to` union anyway).
 */
export function toggleSettings(
  pathname: string,
  router: { history: { push: (path: string) => void } },
): void {
  if (pathname.startsWith("/settings")) {
    closeSettingsDialog(router);
  } else {
    router.history.push("/settings");
  }
}

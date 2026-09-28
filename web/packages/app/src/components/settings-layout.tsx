import { useCallback, useEffect } from "react";
import { Outlet, useRouter, useRouterState } from "@tanstack/react-router";
import { SettingsDialog } from "./settings-dialog";
import { closeSettingsDialog } from "../lib/settings-close";
import { settingsSectionFromPath } from "../state/settings-section";
import { uiSettings } from "../state/ui-settings";

/**
 * The settings route shell — the dialog overlay, not a page takeover. The
 * `/settings/<section>` routes stay the URL surface (shareable deep links,
 * the remembered-section redirect on the index), but what they render now
 * is the settings DIALOG: a portal + anchored-card overlay over the dimmed
 * app (the web's own dialog grammar, the desktop's modal redesign —
 * upstream b782d043, ticket 26). The routed page (the `Outlet`) renders
 * INSIDE the dialog's page column; the section nav is the dialog's own
 * left column, not a sidebar swap (the desktop retired its settings-mode
 * sidebar with the modal).
 *
 * Every section shown is the one to reopen: like the desktop's
 * `remember_settings_section` (upstream d268830b), the current section is
 * written through the settings store (debounced; `pagehide` flushes it), so
 * the `/settings` index — where ⌘/Ctrl+,, the palette's "Open settings" and
 * every other generic entry point land — reopens it after Back or a reload.
 *
 * Closing the dialog returns to the active conversation
 * (`close_settings`, shell.rs:3281-3286): the nearest chat the nav stack
 * holds, the blank canvas when there is none — never history-back.
 */
export function SettingsLayout() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const router = useRouter();
  useEffect(() => {
    const section = settingsSectionFromPath(pathname);
    if (section !== null) {
      uiSettings.updateDebounced({ settingsSection: section });
    }
  }, [pathname]);
  const onClose = useCallback(() => {
    closeSettingsDialog(router);
  }, [router]);
  return (
    <>
      {/*
        The dimmed app under the dialog: a neutral underlay — the
        conversation column is not mounted under Settings (the desktop's
        modal replaces the window too; the conversation state survives in
        the stores and Back returns to it).
      */}
      <div className="settings-underlay" aria-hidden="true" />
      <SettingsDialog onClose={onClose}>
        <Outlet />
      </SettingsDialog>
    </>
  );
}

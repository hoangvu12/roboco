import { useEffect } from "react";
import { Outlet, useRouterState } from "@tanstack/react-router";
import { settingsSectionFromPath } from "../state/settings-section";
import { uiSettings } from "../state/ui-settings";

/**
 * The settings route shell — just the scrolling section outlet. The section
 * nav is the SIDEBAR's content on `/settings/*` (the desktop's
 * `render_settings_nav` swap, shell.rs:993-1008 — see
 * `components/settings-nav.tsx`); the main column carries only the pages,
 * padded below the overlaid titlebar like every other route.
 *
 * Every section shown is the one to reopen: like the desktop's
 * `remember_settings_section` (upstream d268830b), the current section is
 * written through the settings store (debounced; `pagehide` flushes it), so
 * the `/settings` index — where the palette's "Open settings" and every other
 * generic entry point land — reopens it after Back or a reload.
 */
export function SettingsLayout() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  useEffect(() => {
    const section = settingsSectionFromPath(pathname);
    if (section !== null) {
      uiSettings.updateDebounced({ settingsSection: section });
    }
  }, [pathname]);
  return (
    <div className="settings-scroll">
      <Outlet />
    </div>
  );
}

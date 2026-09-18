import { useLocation } from "@tanstack/react-router";
/**
 * Ticket 29's route placeholders. Ticket 28's nav must link all 9 web
 * sections; the six whose pages ticket 29 owns (Devices, Agents, Files,
 * Notifications, Shortcuts, Archived) mount this stub so their paths exist
 * and the typed `Link to=` in the settings nav resolves. Each route swaps
 * its component in ticket 29 — nothing here is meant to survive it.
 */

const TITLES: Record<string, string> = {
  "/settings/devices": "Devices",
  "/settings/harnesses": "Agents",
  "/settings/files": "Files",
  "/settings/notifications": "Notifications",
  "/settings/shortcuts": "Shortcuts",
  "/settings/archived": "Archived sessions",
};

export function SettingsStubPage() {
  const pathname = useLocation({ select: (location) => location.pathname });
  return (
    <div className="settings-page">
      <h1 className="settings-title">{TITLES[pathname] ?? "Settings"}</h1>
      <p className="settings-subtitle">This settings section is coming soon.</p>
    </div>
  );
}

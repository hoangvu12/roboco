import { Link, Outlet } from "@tanstack/react-router";

/**
 * The settings route shell: a section nav beside the scrolling page outlet,
 * mirroring the desktop's settings sidebar nav (shell.rs render_settings_
 * nav) at the three sections the web client ships. Below the desktop width
 * the nav collapses to a wrapping row over the page.
 */
export function SettingsLayout() {
  return (
    <div className="settings-layout">
      <nav className="settings-nav" aria-label="Settings">
        <span className="settings-nav-title">Settings</span>
        <Link
          to="/settings/remote-access"
          className="settings-nav-link"
          activeProps={{ className: "settings-nav-link settings-nav-active" }}
        >
          Remote access
        </Link>
        <Link
          to="/settings/accounts"
          className="settings-nav-link"
          activeProps={{ className: "settings-nav-link settings-nav-active" }}
        >
          Accounts
        </Link>
        <Link
          to="/settings/appearance"
          className="settings-nav-link"
          activeProps={{ className: "settings-nav-link settings-nav-active" }}
        >
          Appearance
        </Link>
      </nav>
      <div className="settings-scroll">
        <Outlet />
      </div>
    </div>
  );
}

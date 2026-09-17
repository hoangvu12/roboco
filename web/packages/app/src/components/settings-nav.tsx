import { Link, useRouter } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { navEntryPath, navHistory } from "../state/nav-history";

/**
 * The settings sidebar — the desktop's `render_settings_nav`
 * (shell.rs:4309-4429), rendered inside the persistent sidebar column in
 * place of the chat sidebar (`SidebarPane::render`'s route match,
 * shell.rs:993-1008). It inherits the column's dragged width and titlebar
 * pad and owns neither a width nor a border of its own; the shell's seam
 * keeps it resizable and collapsible exactly like the chat sidebar.
 *
 * The full 9-row nav (Devices, Agents, Files, Notifications, Shortcuts,
 * Archived) stays ticket 28's — this pull-forward renders the three sections
 * the web router actually defines and routes to them.
 */

/** The sections the router defines, in desktop nav order. */
const SECTIONS = [
  { to: "/settings/remote-access", label: "Remote access", icon: "keyMinimalistic" },
  { to: "/settings/accounts", label: "Accounts", icon: "keyMinimalistic" },
  { to: "/settings/appearance", label: "Appearance", icon: "tuning" },
] as const;

export function SettingsNavBody() {
  const router = useRouter();

  // `close_settings` (shell.rs:3281-3286): Back is NOT history-back — it is
  // an unconditional return to the active chat (the nearest chat entry the
  // nav stack holds; the blank canvas when there is none) that pushes a NEW
  // history entry, then focuses the composer. Raw path rather than
  // `navigate({to})`: a `NavEntry` names a route computed at runtime, which
  // the typed router's literal `to` union cannot express (the shell's
  // back/forward walk navigates the same way).
  const onBack = (): void => {
    const target = navHistory.nearestChat();
    router.history.push(target === null ? "/" : navEntryPath(target));
    // The chat page mounts across the navigation; the composer needs a
    // frame before it can take focus (the terminal dock's focus handoff
    // defers for the same reason).
    requestAnimationFrame(() => {
      document.querySelector<HTMLTextAreaElement>(".composer-input")?.focus();
    });
  };

  return (
    <nav className="settings-nav" aria-label="Settings">
      <div className="settings-nav-sections">
        <span className="settings-nav-title">Settings</span>
        {SECTIONS.map((section) => (
          <Link
            key={section.to}
            to={section.to}
            className="settings-nav-link"
            activeProps={{ className: "settings-nav-link settings-nav-active" }}
          >
            {/* The icon is ALWAYS muted, selected or not (shell.rs:4392-4394). */}
            <Icon name={section.icon} size={16} className="settings-nav-icon" />
            {section.label}
          </Link>
        ))}
      </div>
      <div className="settings-back">
        {/*
          The chevron, deliberately not the history-back arrow
          (shell.rs:4419-4420): Back returns to the chat unconditionally.
        */}
        <button type="button" className="settings-back-inner" onClick={onBack}>
          <Icon name="altArrowLeft" size={16} className="settings-nav-icon" />
          Back
        </button>
      </div>
    </nav>
  );
}

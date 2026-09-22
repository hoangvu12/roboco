import { useEffect, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useEngineSessions } from "../state/session-provider";
import { useFleet } from "../state/fleet";
import { useWatchSnapshot } from "../state/hooks";

/**
 * The sidebar's bottom identity control — the desktop's `render_user_menu`.
 *
 * Upstream f9563394 compacts it: the row's label and subline collapse into
 * a 21px circular avatar button carrying the name's initial (13px white
 * circle, 9px mono semibold) with the name in the aria label — the menu
 * opens to the RIGHT of the trigger (the desktop's anchored_menu_right),
 * bottom-aligned with it.
 *
 * The menu carries exactly two things — desktop parity (`shell.rs`): the
 * muted "Stored on this device" identity line, then the single "Settings"
 * row, which lands on the Devices section (`SettingsSection::Devices`),
 * the desktop's landing row. Engine management lives in Settings →
 * Devices (ticket 45 folded the old web-only Engines drawer there).
 */
export function AccountRow() {
  // The ACTIVE engine's session carries this row's identity — the desktop's
  // user line is the local device, and the web's "home" engine is the one
  // new chats land on.
  const sessions = useEngineSessions();
  const fleet = useFleet();
  const session = fleet.active === null ? null : sessions.get(fleet.active) ?? null;
  const snapshot = useWatchSnapshot(session);
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

  // The connected engine's own device is the identity this row carries — the
  // desktop's user line is the local device, not the transport. Falls back to
  // the engine label while the devices stream is still filling; the subline
  // is gone (f9563394) — the name rides the trigger's aria label alone.
  const engine = fleet.engines.find((candidate) => candidate.baseUrl === fleet.active) ?? null;
  const deviceId = session?.client.engineInfo?.deviceId ?? null;
  const device =
    deviceId === null ? undefined : snapshot?.devices.rows.find((row) => row.id === deviceId);
  const name = device?.name ?? engine?.label ?? "Roboco";
  const initial = (name.trim()[0] ?? "?").toUpperCase();

  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (event: MouseEvent): void => {
      if (rootRef.current !== null && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("mousedown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function goSettings(): void {
    setOpen(false);
    void navigate({ to: "/settings/devices" });
  }

  return (
    <div className="user-menu" ref={rootRef}>
      {open && (
        <div className="user-menu-card" role="menu">
          <div className="user-menu-identity">Stored on this device</div>
          <button type="button" className="menu-item" role="menuitem" onClick={goSettings}>
            <Icon name="settingsMinimalistic" size={16} />
            Settings
          </button>
        </div>
      )}
      <button
        type="button"
        className={`user-menu-trigger ${open ? "user-menu-trigger-open" : ""}`}
        onClick={() => setOpen((current) => !current)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account menu: ${name}`}
      >
        <span className="avatar" aria-hidden="true">
          {initial}
        </span>
      </button>
    </div>
  );
}

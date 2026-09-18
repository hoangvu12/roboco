import { useEffect, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useEngineSessions } from "../state/session-provider";
import { useFleet } from "../state/fleet";
import { useWatchSnapshot } from "../state/hooks";
import { emitShortcut } from "../state/shortcuts";

/**
 * The sidebar's bottom identity row — the desktop's `render_user_menu`.
 *
 * Trigger geometry is the desktop's: a 28px white avatar circle carrying the
 * name's initial in near-black, then the engine's name over an optional
 * status line, on an 8px radius with 8px padding and a 10px gap. The wash is
 * quiet until hovered and settles one step stronger while the menu is open.
 *
 * The menu opens UPWARD with a 6px gap, exactly as wide as the trigger row,
 * and carries: the muted "Stored on this device" identity line, the
 * web-only Engines row (the pairing entry point — the desktop has no
 * per-device identity concept here, and the user menu is where its one
 * settings entry lives), then the single "Settings" row, which lands on the
 * Devices section (`SettingsSection::Devices`) — the web's remote-access
 * page until ticket 28 builds the real Devices section.
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
  // the grant label while the devices stream is still filling.
  const engine = fleet.engines.find((candidate) => candidate.baseUrl === fleet.active) ?? null;
  const deviceId = session?.client.engineInfo?.deviceId ?? null;
  const device =
    deviceId === null ? undefined : snapshot?.devices.rows.find((row) => row.id === deviceId);
  const name = device?.name ?? engine?.label ?? "Roboco";
  const subline = device?.version ?? null;
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
    void navigate({ to: "/settings/remote-access" });
  }

  return (
    <div className="user-menu" ref={rootRef}>
      {open && (
        <div className="user-menu-card" role="menu">
          <div className="user-menu-identity">Stored on this device</div>
          {/*
            Devices/engines are reached from the user menu, as on the desktop —
            never from the titlebar, whose only trailing control is the right
            pane's toggle. The web keeps this row as its pairing entry point.
          */}
          <button
            type="button"
            className="menu-item"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              emitShortcut("open-engines");
            }}
          >
            <Icon name="monitor" size={16} />
            Engines
          </button>
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
      >
        <span className="avatar" aria-hidden="true">
          {initial}
        </span>
        <span className="user-menu-text">
          <span className="user-menu-name">{name}</span>
          {subline !== null && <span className="user-menu-sub">{subline}</span>}
        </span>
      </button>
    </div>
  );
}

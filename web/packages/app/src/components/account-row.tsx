import { useEffect, useRef, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import { Icon } from "@roboco/icons";
import { useEngineSession } from "../state/session-provider";
import { useFleet } from "../state/fleet";
import { useWatchSnapshot } from "../state/hooks";
import { emitShortcut } from "../state/shortcuts";

/**
 * The sidebar's bottom identity row — the desktop's `render_user_menu`.
 *
 * Trigger geometry is the desktop's: a 28px white avatar circle carrying the
 * name's initial in near-black, then the engine's name over an optional
 * status line, on an 8px radius with 8px padding and a 10px gap. The wash is
 * quiet until hovered and settles one step stronger while the menu is open
 * (user-menu.tsx `bg-white/[0.04]` → `[0.06]`).
 *
 * The menu itself carries what the desktop's does minus the desktop-only
 * entries: Settings, and the appearance shortcut.
 */
export function AccountRow() {
  const session = useEngineSession();
  const fleet = useFleet();
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

  function go(to: "/settings" | "/settings/appearance"): void {
    setOpen(false);
    void navigate({ to });
  }

  return (
    <div className="user-menu" ref={rootRef}>
      {open && (
        <div className="user-menu-card" role="menu">
          {/*
            Devices/engines are reached from the user menu, as on the desktop —
            never from the titlebar, whose only trailing control is the right
            pane's toggle.
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
            <Icon name="monitor" size={15} />
            Engines
          </button>
          <button type="button" className="menu-item" role="menuitem" onClick={() => go("/settings/appearance")}>
            <Icon name="tuning" size={15} />
            Appearance
          </button>
          <div className="menu-sep" />
          <button type="button" className="menu-item" role="menuitem" onClick={() => go("/settings")}>
            <Icon name="settingsMinimalistic" size={15} />
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

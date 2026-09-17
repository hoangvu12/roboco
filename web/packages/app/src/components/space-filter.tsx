import { useEffect, useRef, useState } from "react";
import { Icon } from "@roboco/icons";
import type { Device } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useNow, useWatchSnapshot } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { deviceOnline, healedSpaceFilter, spaceDisplayName, spacesSorted } from "../lib/view";

/**
 * The sidebar's space header — the desktop's space-filter row: a 29px
 * disclosure trigger reading "All projects" (or the picked space) with a
 * folder mark, an "@ device" tag hugging the name, an offline glyph when
 * the space's host is stale, and a quiet caret.
 *
 * The picked space both filters the chat list and targets the new-chat flow;
 * a dangling pick — space deleted, or an engine switch — heals to "All
 * projects" at read (`shell.rs`), so the header never shows a name the fleet
 * no longer has.
 *
 * The dropdown the trigger opens is the pre-parity listbox, kept functional
 * until ticket 10 ports `render_spaces_menu`; this ticket owns only the
 * trigger's own chrome. The sort/view-options button is NOT rebuilt —
 * ticket 04 removed the inert one ("an inert control is worse than no
 * control") and ticket 10 will mount the real trigger beside its menu.
 */
export function SpaceFilter() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();
  const now = useNow(30_000);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);

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

  if (snapshot === null || !snapshot.spaces.loaded || snapshot.spaces.rows.length === 0) {
    return null;
  }
  const spaces = spacesSorted(snapshot.spaces.rows);
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  const picked = filter === null ? null : spaces.find((space) => space.id === filter) ?? null;
  const label = picked === null ? "All projects" : spaceDisplayName(picked);
  // The "@ device" tag rides the trigger only under a picked space, with
  // the disconnected GLYPH — never words — when its host reads offline.
  const deviceTag = picked === null ? null : spaceDeviceTag(picked, snapshot.devices.rows, now);

  function pick(id: string | null): void {
    sidebarStore.setSpaceFilter(id);
    setOpen(false);
  }

  return (
    <div className="space-filter" ref={rootRef}>
      <button
        type="button"
        className={`space-filter-trigger ${open ? "space-filter-trigger-open" : ""}`}
        onClick={() => setOpen((current) => !current)}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <Icon name="folder" size={16} className="space-filter-icon" />
        <span className="space-filter-label">
          <span className="space-filter-name">{label}</span>
          {deviceTag !== null && (
            <>
              <span className="space-filter-tag">{deviceTag.tag}</span>
              {!deviceTag.online && <Icon name="wifiOff" size={12} className="space-filter-offline" />}
            </>
          )}
        </span>
        <Icon name="altArrowDown" size={14} className="space-filter-caret" />
      </button>
      {open && (
        <div className="space-filter-menu" role="listbox">
          <button
            type="button"
            role="option"
            aria-selected={filter === null}
            className={`menu-item ${filter === null ? "menu-item-picked" : ""}`}
            onClick={() => pick(null)}
          >
            <Icon name="list" size={15} />
            All projects
          </button>
          {spaces.map((space) => (
            <button
              key={space.id}
              type="button"
              role="option"
              aria-selected={space.id === filter}
              className={`menu-item ${space.id === filter ? "menu-item-picked" : ""}`}
              onClick={() => pick(space.id)}
            >
              <Icon name="folder" size={15} />
              {spaceDisplayName(space)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * The picked space's host tag (`state.rs::space_device_tag`):
 * `"@ {device}"` plus its presence, a missing device row reading online.
 */
function spaceDeviceTag(
  space: { deviceId: string },
  devices: readonly Device[],
  now: number,
): { tag: string; online: boolean } {
  const device = devices.find((row) => row.id === space.deviceId);
  return {
    tag: `@ ${device?.name ?? "Unknown device"}`,
    online: deviceOnline(device, now),
  };
}

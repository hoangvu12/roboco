import { useEffect, useRef, useState } from "react";
import { Icon } from "@roboco/icons";
import { useEngineSession } from "../state/session-provider";
import { useWatchSnapshot } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { healedSpaceFilter, spaceDisplayName, spacesSorted } from "../lib/view";

/**
 * The sidebar's space header — the desktop's space-filter row: a quiet
 * disclosure trigger reading "All projects" (or the picked space) with a
 * chevron, and the sort control at the right edge.
 *
 * The picked space both filters the chat list and targets the new-chat flow;
 * a dangling pick — space deleted, or an engine switch — heals to "All
 * projects" at read (`shell.rs`), so the header never shows a name the fleet
 * no longer has.
 */
export function SpaceFilter() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();
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
        <span className="space-filter-label">{label}</span>
        <Icon name="altArrowDown" size={14} className={`chevron ${open ? "chevron-open" : ""}`} />
      </button>
      <button type="button" className="space-filter-sort" aria-label="Sort chats" title="Sort chats">
        <Icon name="sortVertical" size={14} />
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

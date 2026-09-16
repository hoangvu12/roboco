import { useEngineSession } from "../state/session-provider";
import { useWatchSnapshot } from "../state/hooks";
import { sidebarStore, useSidebar } from "../state/sidebar";
import { healedSpaceFilter, spaceDisplayName, spacesSorted } from "../lib/view";

/**
 * The sidebar's space switcher — the desktop's space-filter dropdown ("All
 * projects" + per-space, spaces_sorted order). The picked space both
 * filters the chat list and targets the new-chat flow; a dangling pick
 * (space deleted, or an engine switch) heals to "All projects" at read.
 */
export function SpaceFilter() {
  const session = useEngineSession();
  const snapshot = useWatchSnapshot(session);
  const sidebar = useSidebar();

  if (snapshot === null || !snapshot.spaces.loaded || snapshot.spaces.rows.length === 0) {
    return null;
  }
  const spaces = spacesSorted(snapshot.spaces.rows);
  const filter = healedSpaceFilter(sidebar.spaceFilter, snapshot.spaces.rows);
  return (
    <select
      className="input space-filter"
      aria-label="Space"
      value={filter ?? ""}
      onChange={(event) => sidebarStore.setSpaceFilter(event.target.value === "" ? null : event.target.value)}
    >
      <option value="">All projects</option>
      {spaces.map((space) => (
        <option key={space.id} value={space.id}>
          {spaceDisplayName(space)}
        </option>
      ))}
    </select>
  );
}

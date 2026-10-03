import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type DragEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
  type RefObject,
} from "react";
import { Icon } from "@roboco/icons";
import type { Appearance } from "@roboco/theme";
import type { WorkspaceFileSearchMatch } from "@roboco/proto";
import { buildSearchTree, isSearchNodeExpanded, toggleSearchNode, type SearchTree, type SearchTreeRow } from "../../lib/file-search-tree";
import { resolveDirectoryIcon, resolveFileIcon } from "../../lib/file-icons";
import {
  absoluteWorkspacePath,
  destinationPath,
  nameSelection,
  renamedPath,
  type FileTreeModel,
  type FileTreeSnapshot,
  type TreeRow,
} from "../../lib/file-tree";
import type { WorkspaceFilesClient } from "../../lib/files-client";
import { describeFilesError } from "../../lib/files-client";
import { useResolvedAppearance } from "../../state/appearance";
import { uiSettings, useUiSettings } from "../../state/ui-settings";
import { Tooltip, TOOLTIP_VIEW_OPTIONS_MS } from "../ui/Tooltip";
import { FileIcon } from "./file-icon";

/** `search.rs:317-318` — the 200ms debounce after the last keystroke. */
const SEARCH_DEBOUNCE_MS = 200;
/** `search.rs:26` — the result cap the "showing first N" banner reports. */
const SEARCH_RESULT_LIMIT = 200;

/** Desktop `tree.rs:18` — the indent step shared by tree and search rows. */
const TREE_INDENT = 14;

/**
 * The tree pane — the desktop's browser-mode `tree_pane` (mod.rs):
 * `render_header` (the `surface_chrome` toolbar with the search field and
 * the show-all-files toggle), the watch-error banner with its "Refresh
 * now", and below it the search results (query non-empty) or the lazy
 * directory tree — 27px rows, file-type icons, keyboard navigation,
 * drag-out, paged "Load more" rows, error rows with retry.
 */

/**
 * The search keyboard surface — what the header's input talks to while
 * results are mounted (the desktop's `ComposerInput` mention events fanned
 * into `FileSearchState`). Arrows move the active row; Enter activates it.
 */
export interface SearchKeyboard {
  onArrow(delta: number): void;
  onEnter(): void;
}

export function FileTreePanel({
  model,
  client,
  onOpenFile,
  gitStatus,
  workspaceRoot = null,
  onAddToChat,
}: {
  model: FileTreeModel;
  client: WorkspaceFilesClient;
  onOpenFile: (path: string) => void;
  /**
   * The shared remote-safe Git status stream (b25dd404 parity): subscribe
   * through the files client and feed frames to the model. The host owns
   * the engine session wiring; omitting it leaves the tree uncolored.
   */
  gitStatus?: (handlers: {
    onItem: (frame: { status: { files: { path: string; index: string; worktree: string }[] } | null }) => void;
    onEnd?: (error?: unknown) => void;
  }) => { cancel(): void };
  /** The owning workspace's root (`chat.cwd`) — Copy path builds the full
   *  path in the HOST's format, even when browsing a remote workspace. */
  workspaceRoot?: string | null;
  /** `Add to chat` (context_menu.rs): insert a workspace reference into the
   *  chat's draft. The host page wires the composer insert. */
  onAddToChat?: (path: string, isDirectory: boolean) => void;
}) {
  const subscribe = useCallback((listener: () => void) => model.subscribe(listener), [model]);
  const getSnapshot = useCallback(() => model.getSnapshot(), [model]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);
  const appearance = useResolvedAppearance();
  const settings = useUiSettings();
  const [query, setQuery] = useState("");
  const searchKeyboard = useRef<SearchKeyboard | null>(null);

  // The show-all-files preference is stored, not local (the desktop's
  // `ShowAllFilesChanged` → settings → `set_show_all_files` fan-out): the
  // toggle writes the store, and every mounted Files surface re-applies it
  // here. Applying also clears the search box (mod.rs apply_show_all_files).
  useEffect(() => {
    if (model.includeIgnored() !== settings.filesShowAll) {
      model.setIncludeIgnored(settings.filesShowAll);
      setQuery("");
    }
  }, [model, settings.filesShowAll]);

  // The Git status stream (b25dd404): frames decorate the tree rows; an
  // unavailable status clears the decorations (never reads as clean).
  useEffect(() => {
    if (gitStatus === undefined) {
      return;
    }
    const handle = gitStatus({
      onItem: (frame) => model.applyGitStatus(frame.status === null ? null : frame.status.files),
    });
    return () => {
      handle.cancel();
      model.applyGitStatus(null);
    };
  }, [gitStatus, model]);

  const includeIgnored = snapshot.includeIgnored;
  const trimmed = query.trim();
  const [menu, setMenu] = useState<{ path: string; x: number; y: number } | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  // The directory a compatible drag currently hovers (drag.rs TreeDrag):
  // null while no drop target is active.
  const [dropDirectory, setDropDirectory] = useState<string | null>(null);

  /** The input's search keys (the ComposerInput's mention/submit events). */
  const onSearchKeyDown = (event: ReactKeyboardEvent<HTMLInputElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      setQuery("");
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      event.stopPropagation();
      searchKeyboard.current?.onArrow(event.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      event.stopPropagation();
      searchKeyboard.current?.onEnter();
    }
  };

  return (
    <div className="files-tree-panel">
      <div className="surface-toolbar files-header" role="toolbar" aria-label="Files">
        <div className="surface-input files-search">
          <Icon name="magnifer" size={12} className="files-search-icon" />
          <input
            type="text"
            placeholder="Search files"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onSearchKeyDown}
            autoComplete="off"
            spellCheck={false}
            aria-label="Search files"
          />
        </div>
        <Tooltip
          label={includeIgnored ? "Hide hidden and ignored files" : "Show all files (even hidden)"}
          delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <button
              type="button"
              className={`files-toggle-ignored${includeIgnored ? " files-toggle-ignored-on" : ""}`}
              aria-pressed={includeIgnored}
              aria-label={includeIgnored ? "Hide hidden and ignored files" : "Show all files (even hidden)"}
              onClick={() => uiSettings.updateImmediate({ filesShowAll: !includeIgnored })}
            >
              <Icon name={includeIgnored ? "eye" : "eyeClosed"} size={14} />
            </button>
          }
        />
      </div>
      {snapshot.watchError !== null && (
        <div className="files-watch-note" role="status">
          <Icon name="refresh" size={11} className="files-watch-icon" />
          <span className="files-watch-message">{snapshot.watchError}</span>
          <button type="button" className="files-watch-refresh" onClick={() => model.refresh()}>
            Refresh now
          </button>
        </div>
      )}
      {snapshot.mutationError !== null && (
        <div className="files-mutation-error" role="alert">
          <span>{snapshot.mutationError}</span>
          <button type="button" className="files-mutation-dismiss" onClick={() => model.dismissMutationError()}>
            Dismiss
          </button>
        </div>
      )}
      {menu !== null && (
        <TreeContextMenu
          model={model}
          path={menu.path}
          position={{ x: menu.x, y: menu.y }}
          workspaceRoot={workspaceRoot}
          onAddToChat={onAddToChat}
          onRename={() => setRenaming(menu.path)}
          onDelete={() => setDeleting(menu.path)}
          onClose={() => setMenu(null)}
        />
      )}
      {deleting !== null && (
        <TreeDeleteDialog
          model={model}
          path={deleting}
          onCancel={() => setDeleting(null)}
        />
      )}
      {trimmed.length > 0 ? (
        <SearchResults
          model={model}
          client={client}
          query={trimmed}
          includeIgnored={includeIgnored}
          appearance={appearance}
          onOpenFile={onOpenFile}
          onDismiss={() => setQuery("")}
          keyboardRef={searchKeyboard}
        />
      ) : snapshot.rootError !== null && !snapshot.rootLoaded ? (
        <div className="files-root-error">
          <p className="files-root-error-message">{snapshot.rootError}</p>
          <button type="button" className="files-root-retry" onClick={() => model.retryRoot()}>
            Retry
          </button>
        </div>
      ) : !snapshot.rootLoaded ? (
        <div className="files-placeholder" />
      ) : (
        <TreeList
          model={model}
          snapshot={snapshot}
          appearance={appearance}
          onOpenFile={onOpenFile}
          renaming={renaming}
          onRename={(path) => setRenaming(path)}
          onRenameEnd={() => setRenaming(null)}
          onDelete={(path) => setDeleting(path)}
          onContextMenu={(path, x, y) => {
            setRenaming(null);
            setMenu({ path, x, y });
          }}
          dropDirectory={dropDirectory}
          setDropDirectory={setDropDirectory}
        />
      )}
    </div>
  );
}

// ── The tree ───────────────────────────────────────────────────────────────

function TreeList({
  model,
  snapshot,
  appearance,
  onOpenFile,
  renaming,
  onRename,
  onRenameEnd,
  onDelete,
  onContextMenu,
  dropDirectory,
  setDropDirectory,
}: {
  model: FileTreeModel;
  snapshot: FileTreeSnapshot;
  appearance: Appearance;
  onOpenFile: (path: string) => void;
  /** The path being renamed inline, or null. */
  renaming: string | null;
  onRename: (path: string) => void;
  onRenameEnd: () => void;
  onDelete: (path: string) => void;
  onContextMenu: (path: string, x: number, y: number) => void;
  dropDirectory: string | null;
  setDropDirectory: (directory: string | null) => void;
}) {
  const listRef = useRef<HTMLUListElement | null>(null);

  // The tree's edge fades (tree.rs:212-221, `tree_scroll_overflow`): each
  // edge fades only while content extends past it — read live from the
  // scroll offset with the desktop's 0.5px dead-zone. The mask itself lives
  // on `.files-tree` in app.css, gated by the two custom properties this
  // effect sets; the search-results list never sets them, so it stays
  // unfaded exactly as the desktop's search surface is.
  useEffect(() => {
    const list = listRef.current;
    if (list === null) {
      return;
    }
    let raf = 0;
    const apply = (): void => {
      raf = 0;
      const top = list.scrollTop > 0.5;
      const bottom =
        list.scrollTop < list.scrollHeight - list.clientHeight - 0.5;
      list.style.setProperty("--rb-files-fade-top", top ? "1" : "0");
      list.style.setProperty("--rb-files-fade-bottom", bottom ? "1" : "0");
    };
    const schedule = (): void => {
      if (raf === 0) {
        raf = requestAnimationFrame(apply);
      }
    };
    const observer = new ResizeObserver(schedule);
    observer.observe(list);
    list.addEventListener("scroll", schedule, { passive: true });
    apply();
    return () => {
      observer.disconnect();
      list.removeEventListener("scroll", schedule);
      if (raf !== 0) {
        cancelAnimationFrame(raf);
      }
    };
  }, []);

  // `reveal_tree_selection` — keep the selected row in view, both for the
  // keyboard walk and for the search reveal that lands on it.
  const selected = snapshot.selected;
  const rows = snapshot.rows;
  useEffect(() => {
    if (selected === null) {
      return;
    }
    const index = rows.findIndex((row) => row.path === selected);
    if (index >= 0) {
      listRef.current
        ?.querySelector<HTMLElement>(`[data-row-index="${index}"]`)
        ?.scrollIntoView({ block: "nearest" });
    }
  }, [selected, rows]);

  /** `on_tree_key_down` (tree.rs:300-357, plus the mutation keys). */
  const onKeyDown = (event: ReactKeyboardEvent<HTMLUListElement>): void => {
    let handled = false;
    switch (event.key) {
      case "F2": {
        const path = model.selected();
        if (path !== null) {
          onRename(path);
        }
        handled = true;
        break;
      }
      case "Delete": {
        const path = model.selected();
        if (path !== null) {
          onDelete(path);
        }
        handled = true;
        break;
      }
      case "ContextMenu": {
        const path = model.selected();
        if (path !== null) {
          const rect = listRef.current?.getBoundingClientRect();
          onContextMenu(path, rect?.left ?? 0, rect?.top ?? 0);
        }
        handled = true;
        break;
      }
      case "ArrowUp":
        model.selectPrevious();
        handled = true;
        break;
      case "ArrowDown":
        model.selectNext();
        handled = true;
        break;
      case "ArrowLeft": {
        const path = model.selected();
        if (path !== null) {
          if (model.isExpanded(path)) {
            model.toggleExpanded(path);
          } else {
            model.selectParent();
          }
        }
        handled = true;
        break;
      }
      case "ArrowRight": {
        const path = model.selected();
        const entry = path !== null ? model.entry(path) : undefined;
        if (path !== null && entry !== undefined && entry.kind === "directory") {
          if (model.isExpanded(path)) {
            model.selectFirstChild();
          } else {
            model.toggleExpanded(path);
          }
        }
        handled = true;
        break;
      }
      case "Enter":
      case " ": {
        const path = model.selected();
        if (path !== null) {
          activateTreePath(model, rows, path, onOpenFile);
        }
        handled = true;
        break;
      }
      default:
        break;
    }
    if (handled) {
      event.preventDefault();
      event.stopPropagation();
    }
  };

  return (
    <ul
      ref={listRef}
      className="files-tree"
      role="tree"
      aria-label="Files"
      tabIndex={0}
      onKeyDown={onKeyDown}
      onDragOver={(event) => {
        if (!treeDropActive(event)) {
          return;
        }
        // The empty space below the rows targets the workspace root
        // (drag.rs drop_directory_at).
        if (event.target === event.currentTarget) {
          setDropDirectory("");
        }
      }}
      onDragLeave={(event) => {
        if (event.target === event.currentTarget) {
          setDropDirectory(null);
        }
      }}
      onDrop={(event) => {
        const source = treeDropPayload(event);
        if (source === null) {
          return;
        }
        event.preventDefault();
        event.stopPropagation();
        setDropDirectory(null);
        const destination = destinationPath(source.path, "", source.isDirectory);
        if (destination !== null) {
          void model.moveEntry(source.path, destination);
        }
      }}
    >
      <li role="none" className={dropDirectory === "" ? "files-row files-tree-root-target files-row-drop-target" : "files-row files-tree-root-target"} aria-hidden={false}>
        <span className="files-tree-root-label">
          {dropDirectory !== null ? "Move to workspace root" : "Workspace root"}
        </span>
      </li>
      {rows.map((row, index) => (
        <TreeRowView
          key={row.path}
          row={row}
          index={index}
          model={model}
          snapshot={snapshot}
          appearance={appearance}
          onOpenFile={onOpenFile}
          renaming={renaming}
          onRename={onRename}
          onRenameEnd={onRenameEnd}
          onContextMenu={onContextMenu}
          dropDirectory={dropDirectory}
          setDropDirectory={setDropDirectory}
        />
      ))}
    </ul>
  );
}

/**
 * `activate_tree_path` (tree.rs:268): select, then toggle a directory or
 * open a file; the LoadMore row's activation is its own load.
 */
function activateTreePath(
  model: FileTreeModel,
  rows: readonly TreeRow[],
  path: string,
  onOpenFile: (path: string) => void,
): void {
  const row = rows.find((candidate) => candidate.path === path);
  if (row === undefined) {
    return;
  }
  model.select(path);
  if (row.kind === "loadMore") {
    model.loadMore(row.directory);
  } else if (row.kind === "entry") {
    if (row.entry.kind === "directory") {
      model.toggleExpanded(path);
    } else {
      onOpenFile(path);
    }
  }
}

function TreeRowView({
  row,
  index,
  model,
  snapshot,
  appearance,
  onOpenFile,
  renaming,
  onRename,
  onRenameEnd,
  onContextMenu,
  dropDirectory,
  setDropDirectory,
}: {
  row: TreeRow;
  index: number;
  model: FileTreeModel;
  snapshot: FileTreeSnapshot;
  appearance: Appearance;
  onOpenFile: (path: string) => void;
  renaming: string | null;
  onRename: (path: string) => void;
  onRenameEnd: () => void;
  onContextMenu: (path: string, x: number, y: number) => void;
  dropDirectory: string | null;
  setDropDirectory: (directory: string | null) => void;
}) {
  const selected = snapshot.selected === row.path;
  switch (row.kind) {
    case "entry": {
      const entry = row.entry;
      const isDirectory = entry.kind === "directory";
      const git = snapshot.gitStatus.get(row.path);
      const isDropTarget = isDirectory && dropDirectory === row.path;
      const classes = [
        "files-row",
        selected ? "files-row-active" : "",
        entry.ignored ? "files-row-ignored" : "",
        isDropTarget ? "files-row-drop-target" : "",
      ]
        .filter((name) => name.length > 0)
        .join(" ");
      return (
        <li role="treeitem" aria-expanded={isDirectory ? row.expanded : undefined} aria-selected={selected}>
          {renaming === row.path && row.kind === "entry" ? (
            <InlineRename model={model} row={row} onDone={onRenameEnd} />
          ) : (
          <button
            type="button"
            className={classes}
            style={{ paddingLeft: `${8 + row.depth * TREE_INDENT}px` }}
            data-row-index={index}
            data-git={git ?? undefined}
            draggable
            onDragStart={(event) => beginRowDrag(event, row.path, isDirectory, appearance)}
            onClick={() => activateTreePath(model, snapshot.rows, row.path, onOpenFile)}
            onContextMenu={(event) => {
              event.preventDefault();
              event.stopPropagation();
              model.select(row.path);
              onContextMenu(row.path, event.clientX, event.clientY);
            }}
            onDragOver={(event) => {
              if (!treeDropActive(event)) {
                return;
              }
              setDropDirectory(isDirectory ? row.path : (parentOf(row.path) ?? ""));
            }}
            onDragLeave={() => setDropDirectory(null)}
            onDrop={(event) => {
              const source = treeDropPayload(event);
              if (source === null) {
                return;
              }
              event.preventDefault();
              event.stopPropagation();
              setDropDirectory(null);
              const directory = isDirectory ? row.path : (parentOf(row.path) ?? "");
              const destination = destinationPath(source.path, directory, source.isDirectory);
              if (destination !== null) {
                void model.moveEntry(source.path, destination);
              }
            }}
          >
            {/* Theme-aware indentation guides (c4d63fa8, tree.rs). */}
            {row.depth > 0 && (
              <span className="files-row-guides" aria-hidden>
                {Array.from({ length: row.depth }, (_, level) => (
                  <span key={level} className="files-row-guide" />
                ))}
              </span>
            )}
            <span className="files-chevron" aria-hidden>
              {isDirectory ? <Icon name={row.expanded ? "altArrowDown" : "altArrowRight"} size={11} /> : null}
            </span>
            <FileIcon className="files-row-icon" kind={entry.kind} name={entry.name} expanded={row.expanded} appearance={appearance} />
            <span className="files-row-name">{entry.name}</span>
          </button>
          )}
        </li>
      );
    }
    case "loading":
      return <StatusRow index={index} depth={row.depth} label="Loading…" noteClass="files-row-note" />;
    case "empty":
      return <StatusRow index={index} depth={row.depth} label="Empty folder" noteClass="files-row-note files-row-empty" />;
    case "loadMore":
      return (
        <li>
          <button
            type="button"
            className="files-row files-row-note files-row-action"
            style={{ paddingLeft: `${8 + (row.depth + 1) * TREE_INDENT}px` }}
            data-row-index={index}
            onClick={() => model.loadMore(row.directory)}
          >
            <span className="files-row-name">Load more…</span>
          </button>
        </li>
      );
    case "error":
      return (
        <li>
          <button
            type="button"
            className="files-row files-row-error"
            style={{ paddingLeft: `${8 + (row.depth + 1) * TREE_INDENT}px` }}
            data-row-index={index}
            onClick={() => model.retryDirectory(row.directory)}
          >
            <span className="files-row-name">{row.message} — Retry</span>
          </button>
        </li>
      );
  }
}

/** `status_row` (tree.rs:374-395): 10.5px faint status text at depth+1. */
function StatusRow({
  index,
  depth,
  label,
  noteClass,
}: {
  index: number;
  depth: number;
  label: string;
  noteClass: string;
}) {
  return (
    <li
      className={`files-row files-row-status ${noteClass}`}
      style={{ paddingLeft: `${8 + (depth + 1) * TREE_INDENT}px` }}
      data-row-index={index}
    >
      <span className="files-row-name">{label}</span>
    </li>
  );
}

// ── Drag-out (WorkspacePathDrag + its ghost) ───────────────────────────────

/**
 * `WorkspacePathDrag::new` + `workspace_path_drag_ghost` (mod.rs:67-137):
 * the payload is the workspace-relative path and an isDirectory flag; the
 * ghost is the compact pill (24px, ≤220px, raised surface, strong border,
 * 11.5px, opacity 0.85) the composer's drop target turns into a file
 * mention. `setDragImage` snapshots the detached node, which is removed
 * after the drag starts.
 */
function beginRowDrag(
  event: DragEvent<HTMLElement>,
  path: string,
  isDirectory: boolean,
  appearance: Appearance,
): void {
  event.dataTransfer.setData(WORKSPACE_DRAG_MIME, JSON.stringify({ path, isDirectory }));
  event.dataTransfer.setData("text/plain", path);
  event.dataTransfer.effectAllowed = "copyLink";

  const title = path.trimEnd().split("/").pop() ?? path;
  const ghost = document.createElement("div");
  ghost.className = "files-drag-ghost";
  const icon = document.createElement("img");
  icon.src = isDirectory ? resolveDirectoryIcon(title, appearance) : resolveFileIcon(title, appearance);
  icon.width = 14;
  icon.height = 14;
  icon.alt = "";
  icon.draggable = false;
  const label = document.createElement("span");
  label.textContent = title;
  ghost.append(icon, label);
  document.body.append(ghost);
  event.dataTransfer.setDragImage(ghost, 10, 12);
  window.setTimeout(() => ghost.remove(), 0);
}

// ── Tree mutations: context menu, rename, delete, drop targets ─────────────

/** The workspace-path drag's payload (the desktop's `WorkspacePathDrag`
 *  JSON). Our own rows write it in `beginRowDrag`. */
interface WorkspaceDragPayload {
  readonly path: string;
  readonly isDirectory: boolean;
}

const WORKSPACE_DRAG_MIME = "application/x-roboco-workspace-path";

function treeDropPayload(event: DragEvent<HTMLElement>): WorkspaceDragPayload | null {
  const raw = event.dataTransfer.getData(WORKSPACE_DRAG_MIME);
  if (raw.length === 0) {
    return null;
  }
  try {
    const value = JSON.parse(raw) as Partial<WorkspaceDragPayload>;
    if (typeof value.path !== "string" || typeof value.isDirectory !== "boolean") {
      return null;
    }
    return { path: value.path, isDirectory: value.isDirectory };
  } catch {
    return null;
  }
}

function treeDropActive(event: DragEvent<HTMLElement>): boolean {
  return event.dataTransfer.types.includes(WORKSPACE_DRAG_MIME);
}

function parentOf(path: string): string | null {
  const slash = path.lastIndexOf("/");
  return slash > 0 ? path.slice(0, slash) : path.includes("/") ? "" : null;
}

/** `begin_tree_rename`/`render_tree_rename` (rename.rs): a single-line input
 *  in the row, seeded with the name (basename selected, extension kept). */
function InlineRename({
  model,
  row,
  onDone,
}: {
  model: FileTreeModel;
  row: TreeRow & { kind: "entry" };
  onDone: () => void;
}) {
  const entry = row.entry;
  const isDirectory = entry.kind === "directory";
  const [value, setValue] = useState(entry.name);
  const inputRef = useRef<HTMLInputElement | null>(null);
  useEffect(() => {
    const input = inputRef.current;
    if (input === null) {
      return;
    }
    input.focus();
    const selection = nameSelection(entry.name, isDirectory);
    input.setSelectionRange(selection.start, selection.end);
  }, [entry.name, isDirectory]);

  const submit = (): void => {
    const destination = renamedPath(row.path, value.trim());
    if (destination === null || destination === row.path) {
      onDone();
      return;
    }
    onDone();
    void model.moveEntry(row.path, destination);
  };

  return (
    <input
      ref={inputRef}
      className="files-tree-rename"
      style={{ marginLeft: `${8 + row.depth * TREE_INDENT}px` }}
      value={value}
      aria-label={`Rename ${entry.name}`}
      spellCheck={false}
      autoComplete="off"
      onChange={(event) => setValue(event.target.value)}
      onBlur={submit}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          event.stopPropagation();
          submit();
        } else if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          onDone();
        }
      }}
      onClick={(event) => event.stopPropagation()}
    />
  );
}

/** The shared tree context menu (context_menu.rs): Add to chat, Copy path,
 *  Rename…, Delete… — delete styled destructive, mutation rows disabled
 *  without capability/revision. */
function TreeContextMenu({
  model,
  path,
  position,
  workspaceRoot,
  onAddToChat,
  onRename,
  onDelete,
  onClose,
}: {
  model: FileTreeModel;
  path: string;
  position: { x: number; y: number };
  workspaceRoot: string | null;
  onAddToChat?: (path: string, isDirectory: boolean) => void;
  onRename: () => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  const entry = model.entry(path);
  // Close on outside pointer or Escape, like the desktop's dismissal.
  useEffect(() => {
    const onPointerDown = (event: PointerEvent): void => {
      if (ref.current !== null && !ref.current.contains(event.target as Node)) {
        onClose();
      }
    };
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("pointerdown", onPointerDown, true);
    window.addEventListener("keydown", onKeyDown, true);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown, true);
      window.removeEventListener("keydown", onKeyDown, true);
    };
  }, [onClose]);

  const isDirectory = entry?.kind === "directory";
  const canRename = model.canMutate(path, false);
  const canDelete = model.canMutate(path, true);
  const copy = (): void => {
    const full =
      workspaceRoot !== null
        ? absoluteWorkspacePath(workspaceRoot, path)
        : path;
    void navigator.clipboard?.writeText(full);
    onClose();
  };

  return (
    <div
      ref={ref}
      className="files-tree-menu"
      role="menu"
      aria-label="File actions"
      style={{ left: position.x, top: position.y }}
    >
      <button
        type="button"
        role="menuitem"
        className="files-tree-menu-row"
        onClick={() => {
          onAddToChat?.(path, isDirectory);
          onClose();
        }}
      >
        <Icon name="chatRoundLine" size={16} />
        <span>Add to chat</span>
      </button>
      <button
        type="button"
        role="menuitem"
        className="files-tree-menu-row"
        title="Copy full path"
        onClick={copy}
      >
        <Icon name="copy" size={16} />
        <span>Copy path</span>
      </button>
      <div className="files-tree-menu-separator" role="separator" />
      <button
        type="button"
        role="menuitem"
        className="files-tree-menu-row"
        disabled={!canRename}
        onClick={() => {
          onRename();
          onClose();
        }}
      >
        <Icon name="pen" size={16} />
        <span>Rename…</span>
      </button>
      <button
        type="button"
        role="menuitem"
        className="files-tree-menu-row files-tree-menu-danger"
        disabled={!canDelete}
        onClick={() => {
          onDelete();
          onClose();
        }}
      >
        <Icon name="trashBinMinimalistic" size={16} />
        <span>Delete…</span>
      </button>
    </div>
  );
}

/** `render_tree_delete` (rename.rs): permanent-delete confirmation, cancel
 *  initially selected, only the entry NAME in the copy. */
function TreeDeleteDialog({
  model,
  path,
  onCancel,
}: {
  model: FileTreeModel;
  path: string;
  onCancel: () => void;
}) {
  const entry = model.entry(path);
  const isDirectory = entry?.kind === "directory";
  const name = path.split("/").pop() ?? path;
  const [confirmRef, setConfirmRef] = useState<HTMLButtonElement | null>(null);
  useEffect(() => {
    confirmRef?.focus();
  }, [confirmRef]);

  const confirm = (): void => {
    onCancel();
    void model.deleteEntry(path);
  };

  return (
    <div className="files-tree-dialog-backdrop" role="presentation">
      <div
        className="files-tree-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="files-tree-dialog-title"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            onCancel();
          } else if (event.key === "Enter") {
            event.preventDefault();
            event.stopPropagation();
            confirm();
          }
        }}
      >
        <h3 id="files-tree-dialog-title">Delete permanently?</h3>
        <p>
          Permanently delete {name}?{" "}
          {isDirectory ? "All current folder contents will be deleted. " : "This cannot be undone. "}
          Open editor buffers will be kept for recovery.
        </p>
        <div className="files-tree-dialog-actions">
          <button type="button" className="files-tree-dialog-cancel" autoFocus onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            className="files-tree-dialog-delete"
            ref={setConfirmRef}
            onClick={confirm}
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Search ─────────────────────────────────────────────────────────────────

type SearchState =
  | { readonly kind: "searching" }
  | { readonly kind: "loaded"; readonly matches: readonly WorkspaceFileSearchMatch[] }
  | { readonly kind: "error"; readonly message: string };

function SearchResults({
  model,
  client,
  query,
  includeIgnored,
  appearance,
  onOpenFile,
  onDismiss,
  keyboardRef,
}: {
  model: FileTreeModel;
  client: WorkspaceFilesClient;
  query: string;
  includeIgnored: boolean;
  appearance: Appearance;
  onOpenFile: (path: string) => void;
  onDismiss: () => void;
  keyboardRef: RefObject<SearchKeyboard | null>;
}) {
  const [state, setState] = useState<SearchState>({ kind: "searching" });
  const [tree, setTree] = useState<SearchTree | null>(null);
  const [active, setActive] = useState(0);
  const requestRef = useRef(0);
  const listRef = useRef<HTMLUListElement | null>(null);

  // 200ms debounce → `SearchWorkspaceFiles` (on_search_edited, search.rs:270).
  useEffect(() => {
    const request = ++requestRef.current;
    setState({ kind: "searching" });
    setTree(null);
    setActive(0);
    const timer = setTimeout(() => {
      void client
        .search(query, includeIgnored, SEARCH_RESULT_LIMIT)
        .then((matches) => {
          if (requestRef.current === request) {
            setState({ kind: "loaded", matches });
            setTree(buildSearchTree(matches));
            setActive(0);
          }
        })
        .catch((error: unknown) => {
          if (requestRef.current === request) {
            setState({ kind: "error", message: describeFilesError(error) });
          }
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [client, query, includeIgnored]);

  const rows = tree?.rows ?? [];

  /** `activate_search_result` (search.rs:357-388). */
  const activateRow = useCallback(
    (row: SearchTreeRow): void => {
      if (tree === null) {
        return;
      }
      if (row.kind === "directory" && row.hasChildren) {
        const next = toggleSearchNode(tree, row.path);
        if (next !== null) {
          setTree(next);
          const index = next.rows.findIndex((candidate) => candidate.path === row.path);
          setActive(index >= 0 ? index : 0);
        }
        return;
      }
      void model.revealInTree(row.path).then((error) => {
        if (error !== null) {
          setState({ kind: "error", message: error });
          return;
        }
        onDismiss();
        if (row.kind !== "directory") {
          onOpenFile(row.path);
        }
      });
    },
    [tree, model, onDismiss, onOpenFile],
  );

  // The header input's arrow/enter reach the results through the keyboard
  // ref — the web shape of the ComposerInput's mention events.
  useEffect(() => {
    keyboardRef.current = {
      onArrow: (delta) => {
        if (rows.length === 0) {
          return;
        }
        setActive((current) => Math.max(0, Math.min(rows.length - 1, current + delta)));
      },
      onEnter: () => {
        const row = rows[active];
        if (row !== undefined) {
          activateRow(row);
        }
      },
    };
    return () => {
      keyboardRef.current = null;
    };
  }, [keyboardRef, rows, active, activateRow]);

  // `search_list.scroll_to_reveal_item` — the active row stays in view.
  useEffect(() => {
    if (rows.length === 0) {
      return;
    }
    listRef.current
      ?.querySelector<HTMLElement>(`[data-row-index="${active}"]`)
      ?.scrollIntoView({ block: "nearest" });
  }, [active, rows]);

  const banner =
    state.kind === "loaded" && state.matches.length >= SEARCH_RESULT_LIMIT ? (
      <div className="files-search-banner">Showing the first {SEARCH_RESULT_LIMIT} matches</div>
    ) : null;

  let body: ReactNode;
  if (state.kind === "error") {
    body = <div className="files-search-message files-search-message-error">{state.message}</div>;
  } else if (rows.length === 0) {
    body = <div className="files-search-message">{state.kind === "searching" ? "Searching…" : "No files found."}</div>;
  } else {
    body = (
      <ul ref={listRef} className="files-tree files-search-results" role="tree" aria-label="Search results">
        {rows.map((row, index) => (
          <SearchRowView
            key={row.path}
            row={row}
            index={index}
            active={index === active}
            appearance={appearance}
            expanded={tree !== null && isSearchNodeExpanded(tree, row.path)}
            onSelect={() => setActive(index)}
            onActivate={() => activateRow(row)}
          />
        ))}
      </ul>
    );
  }

  return (
    <div className="files-search-results-panel">
      {banner}
      {body}
    </div>
  );
}

/** `render_search_row` (search.rs:516-605). */
function SearchRowView({
  row,
  index,
  active,
  appearance,
  expanded,
  onSelect,
  onActivate,
}: {
  row: SearchTreeRow;
  index: number;
  active: boolean;
  appearance: Appearance;
  expanded: boolean;
  onSelect: () => void;
  onActivate: () => void;
}) {
  const isDirectory = row.kind === "directory";
  return (
    <li role="treeitem" aria-expanded={row.hasChildren ? expanded : undefined} aria-selected={active}>
      <button
        type="button"
        className={`files-row files-row-search${active ? " files-row-active" : ""}`}
        style={{ paddingLeft: `${8 + row.depth * TREE_INDENT}px` }}
        data-row-index={index}
        draggable
        onDragStart={(event) => beginRowDrag(event, row.path, isDirectory, appearance)}
        onClick={() => {
          onSelect();
          onActivate();
        }}
      >
        <span className="files-chevron" aria-hidden>
          {row.hasChildren ? <Icon name={expanded ? "altArrowDown" : "altArrowRight"} size={11} /> : null}
        </span>
        <FileIcon className="files-row-icon" kind={row.kind} name={row.name} expanded={expanded} appearance={appearance} />
        <span className={`files-row-name${isDirectory ? " files-row-name-directory" : ""}`}>{row.name}</span>
      </button>
    </li>
  );
}

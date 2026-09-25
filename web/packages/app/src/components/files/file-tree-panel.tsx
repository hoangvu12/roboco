import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type RefObject,
} from "react";
import { FileTree as TreesView, useFileTree } from "@pierre/trees/react";
import type { WorkspaceFileSearchMatch, WorkspaceGitStatusFrame } from "@roboco/proto";
import type { WatchHandle, WatchHandlers } from "@roboco/engine-client";
import { Icon } from "@roboco/icons";
import { directoryWorkspacePath, searchTreePaths } from "../../lib/tree-adapters";
import { FILE_TREE_DENSITY, FILE_TREE_ROW_HEIGHT, TREE_GUIDE_UNSAFE_CSS, treeFileIcons } from "../../lib/tree-icons";
import type { WorkspaceTreeModel } from "../../lib/workspace-tree";
import type { WorkspaceFilesClient } from "../../lib/files-client";
import { describeFilesError } from "../../lib/files-client";
import { uiSettings, useUiSettings } from "../../state/ui-settings";
import { Tooltip, TOOLTIP_VIEW_OPTIONS_MS } from "../ui/Tooltip";

/** `search.rs:317-318` — the 200ms debounce after the last keystroke. */
const SEARCH_DEBOUNCE_MS = 200;
/** `search.rs:26` — the result cap the "showing first N" banner reports. */
const SEARCH_RESULT_LIMIT = 200;

/**
 * The tree pane on the trees library (ticket 06): the `surface_chrome`
 * toolbar (search field + show-all-files toggle), the watch-error banner,
 * and below it either the search results or the lazy tree — rendered by
 * `<FileTree model={model.tree}>` in shadow DOM. The data layer (listings,
 * watches, resync, pagination, git status, dimming) stays in
 * `WorkspaceTreeModel`; this component is chrome and interaction wiring:
 * row clicks open files (through the host element, since the library owns
 * the rows), Enter/Space activate the focused row, and the search input
 * drives the RPC search.
 */

/**
 * The search keyboard surface — what the header's input talks to while
 * results are mounted. Arrows move the result tree's focus; Enter
 * activates the focused row.
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
}: {
  model: WorkspaceTreeModel;
  client: WorkspaceFilesClient;
  onOpenFile: (path: string) => void;
  /**
   * The shared remote-safe Git status stream (b25dd404 parity): subscribe
   * through the files client and feed `WorkspaceGitStatusFrame`s to the
   * model. The host owns the engine session wiring; omitting it leaves the
   * tree uncolored.
   */
  gitStatus?: (handlers: WatchHandlers<WorkspaceGitStatusFrame>) => WatchHandle;
}) {
  const subscribe = useCallback((listener: () => void) => model.subscribe(listener), [model]);
  const getSnapshot = useCallback(() => model.getSnapshot(), [model]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);
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
      {trimmed.length > 0 ? (
        <SearchResults
          model={model}
          client={client}
          query={trimmed}
          includeIgnored={includeIgnored}
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
        <TreeHost model={model} onOpenFile={onOpenFile} />
      )}
    </div>
  );
}

// ── The tree ───────────────────────────────────────────────────────────────

/** One clicked tree row, read back out of the shadow DOM via `composedPath()`. */
interface TreeRowHit {
  /** The row's canonical path (directories carry a trailing slash). */
  readonly path: string;
  readonly kind: "file" | "folder";
}

function treeRowFromEvent(event: ReactMouseEvent<HTMLElement>): TreeRowHit | null {
  const path = event.nativeEvent.composedPath();
  for (const node of path) {
    if (node instanceof HTMLElement && node.dataset.itemPath !== undefined) {
      return {
        path: node.dataset.itemPath,
        kind: node.dataset.itemType === "folder" ? "folder" : "file",
      };
    }
  }
  return null;
}

function TreeHost({
  model,
  onOpenFile,
}: {
  model: WorkspaceTreeModel;
  onOpenFile: (path: string) => void;
}) {
  // Click-to-open: the library owns the rows (per-item interactive content
  // is unsupported on the beta), but click events are composed — they
  // bubble out of the shadow DOM, and the row's data attributes carry the
  // path. Directories toggle natively; a FAILED directory row's click is
  // its retry; a file row opens (a load-more marker loads its page).
  const onRowClick = (event: ReactMouseEvent<HTMLElement>): void => {
    const row = treeRowFromEvent(event);
    if (row === null) {
      return;
    }
    if (row.kind === "folder") {
      model.retryDirectory(directoryWorkspacePath(row.path));
      return;
    }
    if (model.loadMoreIfMarker(row.path)) {
      return;
    }
    onOpenFile(row.path);
  };

  // Keyboard: the library's a11y tree owns the arrows; Enter/Space do not
  // activate files there (plain Space is its scroll key, Enter only its
  // rename/search commits), so the host completes the desktop's
  // `on_tree_key_down` activation: Enter/Space on the focused row toggles
  // a directory, activates a marker, or opens a file.
  const onKeyDown = (event: ReactKeyboardEvent<HTMLElement>): void => {
    if (event.key !== "Enter" && event.key !== " " && event.key !== "Spacebar") {
      return;
    }
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    const focused = model.tree.getFocusedItem();
    if (focused === null) {
      return;
    }
    const path = focused.getPath();
    if ("toggle" in focused) {
      focused.toggle();
    } else if (!model.loadMoreIfMarker(path)) {
      onOpenFile(path);
    }
    event.preventDefault();
    event.stopPropagation();
  };

  return (
    <TreesView
      model={model.tree}
      className="files-tree-host"
      aria-label="Files"
      onClick={onRowClick}
      onKeyDown={onKeyDown}
    />
  );
}

// ── Search ─────────────────────────────────────────────────────────────────

type SearchState =
  | { readonly kind: "searching" }
  | { readonly kind: "loaded"; readonly matches: readonly WorkspaceFileSearchMatch[] }
  | { readonly kind: "error"; readonly message: string };

/**
 * The RPC search results, rendered through a second trees model — the
 * ticket's latitude decision: the built-in search only filters paths
 * already loaded, so the contract stays the old one (`SearchWorkspaceFiles`
 * over the whole checkout, 200ms debounce, 200-result cap). The matched
 * paths (with their implied ancestors) become the result tree's path list,
 * ordered by the desktop's bestScore rule; activating a file reveals it in
 * the main tree and opens it.
 */
function SearchResults({
  model,
  client,
  query,
  includeIgnored,
  onOpenFile,
  onDismiss,
  keyboardRef,
}: {
  model: WorkspaceTreeModel;
  client: WorkspaceFilesClient;
  query: string;
  includeIgnored: boolean;
  onOpenFile: (path: string) => void;
  onDismiss: () => void;
  keyboardRef: RefObject<SearchKeyboard | null>;
}) {
  const [state, setState] = useState<SearchState>({ kind: "searching" });
  // The rank map the result tree's comparator reads (replaced per results
  // — the comparator itself is fixed at construction, so it reads the ref).
  const rankRef = useRef<ReadonlyMap<string, number>>(new Map());
  const { model: searchTree } = useFileTree({
    paths: [],
    flattenEmptyDirectories: false,
    sort: (left, right) => {
      const rank = rankRef.current;
      const leftRank = rank.get(left.path);
      const rightRank = rank.get(right.path);
      if (leftRank !== undefined && rightRank !== undefined) {
        return leftRank - rightRank;
      }
      return left.path < right.path ? -1 : left.path > right.path ? 1 : 0;
    },
    itemHeight: FILE_TREE_ROW_HEIGHT,
    density: FILE_TREE_DENSITY,
    icons: treeFileIcons,
    unsafeCSS: TREE_GUIDE_UNSAFE_CSS,
  });
  const requestRef = useRef(0);

  // 200ms debounce → `SearchWorkspaceFiles` (on_search_edited, search.rs:270).
  useEffect(() => {
    const request = ++requestRef.current;
    setState({ kind: "searching" });
    const timer = setTimeout(() => {
      void client
        .search(query, includeIgnored, SEARCH_RESULT_LIMIT)
        .then((matches) => {
          if (requestRef.current === request) {
            setState({ kind: "loaded", matches });
            const { orderedPaths, directoryPaths, rank } = searchTreePaths(matches);
            rankRef.current = rank;
            searchTree.resetPaths(orderedPaths, { initialExpandedPaths: directoryPaths });
            searchTree.focusFirstItem();
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
  }, [client, query, includeIgnored, searchTree]);

  /** `activate_search_result` (search.rs:357-388) — reveal in the tree, then open. */
  const activateResult = useCallback(
    (path: string): void => {
      void model.revealInTree(path).then((error) => {
        if (error !== null) {
          setState({ kind: "error", message: error });
          return;
        }
        onDismiss();
        onOpenFile(path);
      });
    },
    [model, onDismiss, onOpenFile],
  );

  // A result row click: directories toggle in-model (the library's row
  // behavior); files reveal + open + dismiss the search.
  const onRowClick = (event: ReactMouseEvent<HTMLElement>): void => {
    const row = treeRowFromEvent(event);
    if (row === null || row.kind === "folder") {
      return;
    }
    activateResult(row.path);
  };

  // The header input's arrow/enter reach the results through the keyboard
  // ref — the web shape of the ComposerInput's mention events.
  useEffect(() => {
    keyboardRef.current = {
      onArrow: (delta) => {
        if (delta > 0) {
          searchTree.focusNextItem();
        } else {
          searchTree.focusPreviousItem();
        }
      },
      onEnter: () => {
        const focused = searchTree.getFocusedItem();
        if (focused === null) {
          return;
        }
        if ("toggle" in focused) {
          focused.toggle();
        } else {
          activateResult(focused.getPath());
        }
      },
    };
    return () => {
      keyboardRef.current = null;
    };
  }, [keyboardRef, searchTree, activateResult]);

  const banner =
    state.kind === "loaded" && state.matches.length >= SEARCH_RESULT_LIMIT ? (
      <div className="files-search-banner">Showing the first {SEARCH_RESULT_LIMIT} matches</div>
    ) : null;

  const loaded = state.kind === "loaded";
  const empty = loaded && state.matches.length === 0;

  return (
    <div className="files-search-results-panel">
      {banner}
      {state.kind === "error" ? (
        <div className="files-search-message files-search-message-error">{state.message}</div>
      ) : empty ? (
        <div className="files-search-message">No files found.</div>
      ) : !loaded ? (
        <div className="files-search-message">Searching…</div>
      ) : (
        <TreesView model={searchTree} className="files-tree-host files-search-host" aria-label="Search results" onClick={onRowClick} />
      )}
    </div>
  );
}

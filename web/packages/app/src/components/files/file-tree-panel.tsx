import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import type { WorkspaceFileSearchMatch } from "@roboco/proto";
import type { FileTreeModel, TreeRow } from "../../lib/file-tree";
import type { WorkspaceFilesClient } from "../../lib/files-client";
import { describeFilesError } from "../../lib/files-client";
import { formatBytes } from "../../lib/files";

const SEARCH_DEBOUNCE_MS = 250;
const SEARCH_RESULT_LIMIT = 200;

/**
 * The tree pane: the lazy directory listing with expand chevrons, paged
 * "Load more" rows, error rows with retry, and the search field whose
 * results replace the tree while a query is present (desktop parity:
 * `SearchWorkspaceFiles` renders in place of the listing).
 */
export function FileTreePanel({
  model,
  client,
  selectedPath,
  onOpenFile,
}: {
  model: FileTreeModel;
  client: WorkspaceFilesClient;
  selectedPath: string | null;
  onOpenFile: (path: string) => void;
}) {
  const subscribe = useCallback((listener: () => void) => model.subscribe(listener), [model]);
  const getSnapshot = useCallback(() => model.getSnapshot(), [model]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);
  const [query, setQuery] = useState("");

  return (
    <div className="files-tree-panel">
      <div className="files-search">
        <input
          className="input"
          type="search"
          placeholder="Search files…"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          autoComplete="off"
          spellCheck={false}
          aria-label="Search files"
        />
        <button
          type="button"
          className={`btn btn-ghost ${snapshot.includeIgnored ? "files-toggle-on" : ""}`}
          aria-pressed={snapshot.includeIgnored}
          title="Show ignored files"
          onClick={() => model.setIncludeIgnored(!snapshot.includeIgnored)}
        >
          Ignored
        </button>
      </div>
      {snapshot.watchError !== null && <p className="files-watch-note">File updates interrupted — retrying.</p>}
      {query.trim().length > 0 ? (
        <SearchResults client={client} query={query.trim()} includeIgnored={snapshot.includeIgnored} onOpenFile={onOpenFile} />
      ) : (
        <ul className="files-tree" role="tree" aria-label="Files">
          {snapshot.rows.map((row) => (
            <TreeRowView key={row.path} row={row} model={model} selectedPath={selectedPath} onOpenFile={onOpenFile} />
          ))}
        </ul>
      )}
    </div>
  );
}

function TreeRowView({
  row,
  model,
  selectedPath,
  onOpenFile,
}: {
  row: TreeRow;
  model: FileTreeModel;
  selectedPath: string | null;
  onOpenFile: (path: string) => void;
}) {
  const indent = { paddingLeft: `${8 + row.depth * 14}px` };
  switch (row.kind) {
    case "entry": {
      const entry = row.entry;
      if (entry.kind === "directory") {
        return (
          <li role="treeitem" aria-expanded={row.expanded}>
            <button type="button" className="files-row" style={indent} onClick={() => model.toggleExpanded(row.path)}>
              <span className={`files-chevron ${row.expanded ? "files-chevron-open" : ""}`}>▸</span>
              <span className={`files-row-name ${entry.ignored ? "files-row-ignored" : ""}`}>{entry.name}</span>
            </button>
          </li>
        );
      }
      return (
        <li role="treeitem">
          <button
            type="button"
            className={`files-row ${selectedPath === row.path ? "files-row-active" : ""}`}
            style={indent}
            onClick={() => onOpenFile(row.path)}
            title={row.path}
          >
            <span className="files-chevron" aria-hidden />
            <span className={`files-row-name ${entry.ignored ? "files-row-ignored" : ""}`}>
              {entry.kind === "symlink" ? `${entry.name} ↪` : entry.name}
            </span>
            {typeof entry.size === "number" && <span className="files-row-size">{formatBytes(entry.size)}</span>}
          </button>
        </li>
      );
    }
    case "loading":
      return (
        <li className="files-row files-row-note" style={indent}>
          Loading…
        </li>
      );
    case "empty":
      return (
        <li className="files-row files-row-note" style={indent}>
          Empty
        </li>
      );
    case "loadMore":
      return (
        <li>
          <button type="button" className="files-row files-row-note files-row-action" style={indent} onClick={() => model.loadMore(row.directory)}>
            Load more
          </button>
        </li>
      );
    case "error":
      return (
        <li className="files-row files-row-error" style={indent}>
          <span className="files-row-name">{row.message}</span>
          <button type="button" className="btn btn-ghost" onClick={() => model.retryDirectory(row.directory)}>
            Retry
          </button>
        </li>
      );
  }
}

type SearchState =
  | { readonly kind: "searching" }
  | { readonly kind: "loaded"; readonly matches: readonly WorkspaceFileSearchMatch[] }
  | { readonly kind: "error"; readonly message: string };

function SearchResults({
  client,
  query,
  includeIgnored,
  onOpenFile,
}: {
  client: WorkspaceFilesClient;
  query: string;
  includeIgnored: boolean;
  onOpenFile: (path: string) => void;
}) {
  const [state, setState] = useState<SearchState>({ kind: "searching" });
  const requestRef = useRef(0);

  useEffect(() => {
    const request = ++requestRef.current;
    setState({ kind: "searching" });
    const timer = setTimeout(() => {
      void client
        .search(query, includeIgnored, SEARCH_RESULT_LIMIT)
        .then((matches) => {
          if (requestRef.current === request) {
            setState({ kind: "loaded", matches });
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

  const body = useMemo(() => {
    switch (state.kind) {
      case "searching":
        return <li className="files-row files-row-note">Searching…</li>;
      case "error":
        return <li className="files-row files-row-error">{state.message}</li>;
      case "loaded":
        if (state.matches.length === 0) {
          return <li className="files-row files-row-note">No matches.</li>;
        }
        return state.matches.map((match) => (
          <li key={match.path}>
            <button
              type="button"
              className="files-row"
              onClick={() => {
                if (match.kind === "file") {
                  onOpenFile(match.path);
                }
              }}
              disabled={match.kind !== "file"}
              title={match.path}
            >
              <span className="files-row-name">{match.name}</span>
              <span className="files-row-path">{match.path}</span>
            </button>
          </li>
        ));
    }
  }, [state, onOpenFile]);

  return <ul className="files-tree">{body}</ul>;
}

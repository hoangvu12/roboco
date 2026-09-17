import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearch } from "@tanstack/react-router";
import type { Space } from "@roboco/proto";
import { useEngineSession } from "../state/session-provider";
import { useEngineStatus, useWatchSnapshot } from "../state/hooks";
import { spaceDisplayName } from "../lib/view";
import { WorkspaceFilesClient } from "../lib/files-client";
import { FileTreeModel, type FileWatchEvent } from "../lib/file-tree";
import { FileDocument } from "../lib/file-document";
import { isImagePath } from "../lib/files";
import { FileTreePanel } from "../components/files/file-tree-panel";
import { FileViewer } from "../components/files/file-viewer";
import { filesRoute } from "../router";

/**
 * The space's files: a lazy tree beside a preview/editor pane (the desktop's
 * Files right-pane surface, promoted to a page). The target is the selected
 * space's checkout; selection lives in the URL (`?space=`/`?path=`) so a
 * file link is shareable within the origin. At phone widths the panes
 * stack: opening a file swaps the tree for the viewer, "‹ Files" returns.
 */
/**
 * The routed page: selection lives in the URL (`?space=`/`?path=`) so a file
 * link is shareable within the origin. Kept so `/files` links stay valid; the
 * right pane hosts the same body through `FilesSurface`.
 */
export function FilesPage() {
  const search = useSearch({ from: filesRoute.id });
  const navigate = useNavigate();
  return (
    <FilesBody
      requestedSpace={search.space ?? null}
      path={search.path ?? null}
      onOpen={(space, path) =>
        void navigate({ to: "/files", search: { space: space ?? undefined, ...(path === null ? {} : { path }) } })
      }
    />
  );
}

/**
 * The right pane's Files surface. The pane is chat-scoped chrome with no URL
 * of its own, so the space and the open path live in local state here.
 */
export function FilesSurface() {
  const [space, setSpace] = useState<string | null>(null);
  const [path, setPath] = useState<string | null>(null);
  return (
    <FilesBody
      requestedSpace={space}
      path={path}
      onOpen={(nextSpace, nextPath) => {
        setSpace(nextSpace);
        setPath(nextPath);
      }}
    />
  );
}

interface FilesBodyProps {
  readonly requestedSpace: string | null;
  readonly path: string | null;
  /** `path === null` closes the viewer and returns to the tree. */
  readonly onOpen: (space: string | null, path: string | null) => void;
}

function FilesBody({ requestedSpace, path, onOpen }: FilesBodyProps) {
  const session = useEngineSession();
  const status = useEngineStatus(session);
  const snapshot = useWatchSnapshot(session);

  const deviceId = status?.state === "connected" ? status.info.deviceId : null;
  const spaces = snapshot?.spaces.rows ?? [];
  const owned = spaces.filter((space) => deviceId !== null && space.deviceId === deviceId);
  const spaceId =
    requestedSpace !== null && owned.some((space) => space.id === requestedSpace)
      ? requestedSpace
      : (owned[0]?.id ?? null);

  const client = useMemo(
    () => (session !== null && spaceId !== null ? new WorkspaceFilesClient(session.client, { spaceId }) : null),
    [session, spaceId],
  );

  const [model, setModel] = useState<FileTreeModel | null>(null);
  const [document, setDocument] = useState<FileDocument | null>(null);
  const documentRef = useRef<FileDocument | null>(null);
  documentRef.current = document;

  // The tree model (and its workspace change watch) lives as long as the
  // (session, space) pair it browses; StrictMode-safe create/dispose here.
  useEffect(() => {
    if (client === null || session === null) {
      setModel(null);
      return;
    }
    const created = new FileTreeModel({
      client,
      watch: (handlers) => client.watchFiles(session.client, handlers),
      onFileEvent: (event) => forwardFileEvent(documentRef.current, event),
    });
    created.start();
    setModel(created);
    return () => {
      created.dispose();
      setModel((current) => (current === created ? null : current));
    };
  }, [client, session]);

  // The open document follows the URL path; images need no document.
  useEffect(() => {
    if (client === null || path === null || isImagePath(path)) {
      setDocument(null);
      return;
    }
    const created = new FileDocument(client, path);
    created.load();
    setDocument(created);
    return () => {
      created.dispose();
      setDocument((current) => (current === created ? null : current));
    };
  }, [client, path]);

  const openPath = (next: string) => onOpen(spaceId, next);
  const closePath = () => onOpen(spaceId, null);

  if (snapshot === null) {
    return (
      <div className="empty-state">
        <p>Pair an engine to browse its files.</p>
        <Link to="/pair" className="btn btn-solid">
          Pair an engine
        </Link>
      </div>
    );
  }
  if (!snapshot.spaces.loaded) {
    return (
      <div className="empty-state">
        <p>Loading spaces…</p>
      </div>
    );
  }
  if (owned.length === 0) {
    return (
      <div className="empty-state">
        <p>This engine has no spaces yet.</p>
      </div>
    );
  }

  return (
    <div className={`files-page ${path !== null ? "files-page-open" : ""}`}>
      <header className="files-toolbar">
        <SpacePicker
          spaces={owned}
          spaceId={spaceId}
          onChange={(next) => onOpen(next, null)}
        />
      </header>
      <div className="files-body">
        <aside className="files-tree-pane panel">
          {model !== null && client !== null ? (
            <FileTreePanel model={model} client={client} selectedPath={path} onOpenFile={openPath} />
          ) : (
            <p className="files-note">Loading…</p>
          )}
        </aside>
        <section className="files-viewer-pane">
          {path !== null && client !== null ? (
            <FileViewer client={client} path={path} document={document} onOpenPath={openPath} onClose={closePath} />
          ) : (
            <div className="empty-state">
              <p>Pick a file to read or edit.</p>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function SpacePicker({
  spaces,
  spaceId,
  onChange,
}: {
  spaces: readonly Space[];
  spaceId: string | null;
  onChange: (spaceId: string) => void;
}) {
  return (
    <select
      className="input files-space-picker"
      aria-label="Space"
      value={spaceId ?? ""}
      onChange={(event) => onChange(event.target.value)}
    >
      {spaces.map((space) => (
        <option key={space.id} value={space.id}>
          {spaceDisplayName(space)}
        </option>
      ))}
    </select>
  );
}

/** The watch outcomes the open document cares about (desktop watch.rs). */
function forwardFileEvent(document: FileDocument | null, event: FileWatchEvent): void {
  if (document === null) {
    return;
  }
  switch (event.kind) {
    case "created":
      if (event.path === document.path) {
        document.restore();
      }
      break;
    case "modified":
      if (event.path === document.path) {
        document.reconcile();
      }
      break;
    case "removed":
      if (event.path === document.path) {
        document.markDeleted();
      }
      break;
    case "renamed":
      // The desktop follows the rename onto the new path; web v1 marks the
      // old path deleted instead (the buffer is preserved either way).
      if (event.oldPath === document.path) {
        document.markDeleted();
      }
      break;
    case "resync":
      document.reconcile();
      break;
  }
}

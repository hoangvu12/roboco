import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { FileDocument, type FileDocumentSnapshot } from "../../lib/file-document";
import { describeFilesError, type WorkspaceFilesClient } from "../../lib/files-client";
import { fileName, formatBytes, isImagePath, isMarkdownPath, readOnlyMessage } from "../../lib/files";
import { parseMarkdown } from "../../lib/markdown";
import { MarkdownView } from "./markdown-view";

/**
 * The viewer pane: breadcrumb, the write-outcome banners (desktop
 * preview.rs copy), and the content — image preview, markdown preview with
 * an edit toggle, or the plain-text editor. Mod-S saves, as on desktop.
 * The page owns the FileDocument so watch events can reconcile it.
 */
export function FileViewer({
  client,
  path,
  document,
  onOpenPath,
  onClose,
}: {
  client: WorkspaceFilesClient;
  path: string;
  document: FileDocument | null;
  onOpenPath: (path: string) => void;
  onClose: () => void;
}) {
  if (isImagePath(path)) {
    return <ImageViewer client={client} path={path} onClose={onClose} />;
  }
  if (document === null || document.path !== path) {
    return (
      <div className="files-viewer">
        <p className="files-note">Loading…</p>
      </div>
    );
  }
  return <TextViewer document={document} path={path} onOpenPath={onOpenPath} onClose={onClose} />;
}

function ViewerHeader({
  path,
  file,
  dirty,
  onClose,
  actions,
}: {
  path: string;
  file: { size: number; modifiedAt?: string | null } | null;
  dirty: boolean;
  onClose: () => void;
  actions?: React.ReactNode;
}) {
  return (
    <header className="files-viewer-header">
      <button type="button" className="btn btn-ghost files-back" onClick={onClose} aria-label="Back to files">
        ‹ Files
      </button>
      <div className="files-breadcrumb" title={path}>
        {path.split("/").map((part, index, parts) => (
          <span key={index} className="files-crumb">
            {index > 0 && <span className="files-crumb-sep">›</span>}
            {part}
            {index === parts.length - 1 && dirty && <span className="files-dirty"> · Unsaved</span>}
          </span>
        ))}
      </div>
      {file !== null && <span className="files-viewer-meta">{formatBytes(file.size)}</span>}
      {actions}
    </header>
  );
}

// ── Text and markdown ───────────────────────────────────────────────────

function TextViewer({
  document,
  path,
  onOpenPath,
  onClose,
}: {
  document: FileDocument;
  path: string;
  onOpenPath: (path: string) => void;
  onClose: () => void;
}) {
  const subscribe = useCallback((listener: () => void) => document.subscribe(listener), [document]);
  const getSnapshot = useCallback(() => document.getSnapshot(), [document]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);
  const markdown = isMarkdownPath(path);
  const [mode, setMode] = useState<"preview" | "edit">("preview");
  useEffect(() => {
    setMode("preview");
  }, [path]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === "s") {
        event.preventDefault();
        document.save();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [document]);

  const canSave = document.canSave();
  const saving = snapshot.phase.kind === "saving";
  const showEditor = snapshot.editable && (!markdown || mode === "edit");
  // The preview renders the live buffer, like the desktop's markdown view.
  const markdownBlocks = useMemo(
    () => (markdown && mode === "preview" ? parseMarkdown(snapshot.text) : null),
    [markdown, mode, snapshot.text],
  );

  return (
    <div className="files-viewer">
      <ViewerHeader
        path={path}
        file={snapshot.file}
        dirty={snapshot.dirty}
        onClose={onClose}
        actions={
          <>
            {markdown && snapshot.editable && (
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setMode(mode === "preview" ? "edit" : "preview")}
              >
                {mode === "preview" ? "Edit" : "Preview"}
              </button>
            )}
            {snapshot.editable && (
              <button type="button" className="btn btn-solid" disabled={!canSave || saving} onClick={() => document.save()}>
                {saving ? "Saving…" : "Save"}
              </button>
            )}
          </>
        }
      />
      <PhaseBanner snapshot={snapshot} document={document} />
      <div className="files-viewer-body">
        {snapshot.phase.kind === "loading" && <p className="files-note">Loading…</p>}
        {snapshot.phase.kind === "error" && (
          <div className="files-note">
            <p>{snapshot.phase.message}</p>
            <button type="button" className="btn btn-ghost" onClick={() => document.load()}>
              Retry
            </button>
          </div>
        )}
        {snapshot.file !== null && snapshot.phase.kind !== "loading" && snapshot.phase.kind !== "error" && (
          <>
            {snapshot.phase.kind === "readOnly" && snapshot.file.text == null ? (
              <p className="files-note">{readOnlyMessage(snapshot.phase.reason)}</p>
            ) : markdownBlocks !== null ? (
              <div className="files-markdown-scroll">
                <MarkdownView blocks={markdownBlocks} documentPath={path} onOpenPath={onOpenPath} />
              </div>
            ) : showEditor ? (
              <textarea
                className="files-editor"
                value={snapshot.text}
                onChange={(event) => document.edit(event.target.value)}
                spellCheck={false}
                aria-label={`Edit ${fileName(path)}`}
              />
            ) : snapshot.file.text != null ? (
              <pre className="files-text-preview">{snapshot.file.text}</pre>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

/** The desktop's write-outcome strip (preview.rs save_status), verbatim copy. */
function PhaseBanner({ snapshot, document }: { snapshot: FileDocumentSnapshot; document: FileDocument }) {
  switch (snapshot.phase.kind) {
    case "saveFailed":
      return (
        <div className="files-banner files-banner-danger" role="alert">
          <span className="files-banner-title">Save failed</span>
          <span className="files-banner-detail">{snapshot.phase.message}</span>
          <button type="button" className="btn btn-ghost" onClick={() => document.save()} disabled={!document.canSave()}>
            Try again
          </button>
        </div>
      );
    case "conflict":
      return (
        <div className="files-banner files-banner-warning" role="alert">
          <span className="files-banner-title">Save conflict</span>
          <span className="files-banner-detail">The file changed on disk. Your editor buffer was preserved.</span>
          <ReloadFromDisk document={document} dirty={snapshot.dirty} />
        </div>
      );
    case "externallyModified":
      return (
        <div className="files-banner files-banner-warning" role="status">
          <span className="files-banner-title">Changed on disk</span>
          <span className="files-banner-detail">The file changed on disk. Review it before saving.</span>
          <ReloadFromDisk document={document} dirty={snapshot.dirty} />
        </div>
      );
    case "deletedOnDisk":
      return (
        <div className="files-banner files-banner-warning" role="alert">
          <span className="files-banner-title">Deleted on disk</span>
          <span className="files-banner-detail">The file was removed on disk. Your editor buffer was preserved.</span>
        </div>
      );
    case "readOnly":
      // With text to show, the reason rides a banner above it; without any,
      // it is the centered state (desktop preview.rs centered_state).
      if (snapshot.file?.text == null) {
        return null;
      }
      return (
        <div className="files-banner" role="status">
          <span className="files-banner-detail">{readOnlyMessage(snapshot.phase.reason)}</span>
        </div>
      );
    default:
      return null;
  }
}

/** Reload discards the buffer, so a dirty buffer asks twice (desktop's confirm dialog). */
function ReloadFromDisk({ document, dirty }: { document: FileDocument; dirty: boolean }) {
  const [confirming, setConfirming] = useState(false);
  if (!dirty) {
    return (
      <button type="button" className="btn btn-ghost" onClick={() => document.reloadFromDisk()}>
        Reload from disk
      </button>
    );
  }
  if (!confirming) {
    return (
      <button type="button" className="btn btn-ghost" onClick={() => setConfirming(true)}>
        Reload from disk
      </button>
    );
  }
  return (
    <button type="button" className="btn btn-danger-ghost" onClick={() => document.reloadFromDisk()}>
      Discard & reload?
    </button>
  );
}

// ── Images ──────────────────────────────────────────────────────────────

type ImageState =
  | { readonly kind: "loading" }
  | { readonly kind: "loaded"; readonly url: string; readonly size: number }
  | { readonly kind: "error"; readonly message: string };

function ImageViewer({ client, path, onClose }: { client: WorkspaceFilesClient; path: string; onClose: () => void }) {
  const [state, setState] = useState<ImageState>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    let url: string | null = null;
    setState({ kind: "loading" });
    void (async () => {
      try {
        // The read resolves the checkout identity the image RPC pins to —
        // the desktop's fallback when chat metadata carries no checkout id.
        const file = await client.readFile(path);
        const image = await client.readImage(path, file.checkoutId);
        if (cancelled) {
          return;
        }
        url = URL.createObjectURL(new Blob([image.bytes as BlobPart], { type: image.mimeType }));
        setState({ kind: "loaded", url, size: image.bytes.length });
      } catch (error) {
        if (!cancelled) {
          setState({ kind: "error", message: describeFilesError(error) });
        }
      }
    })();
    return () => {
      cancelled = true;
      if (url !== null) {
        URL.revokeObjectURL(url);
      }
    };
  }, [client, path]);

  return (
    <div className="files-viewer">
      <ViewerHeader
        path={path}
        file={state.kind === "loaded" ? { size: state.size } : null}
        dirty={false}
        onClose={onClose}
      />
      <div className="files-viewer-body files-image-body">
        {state.kind === "loading" && <p className="files-note">Loading…</p>}
        {state.kind === "error" && <p className="files-note">{state.message}</p>}
        {state.kind === "loaded" && <img className="files-image" src={state.url} alt={fileName(path)} />}
      </div>
    </div>
  );
}

import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { Icon } from "@roboco/icons";
import { File as LibraryFile, Virtualizer, type FileOptions } from "@pierre/diffs/react";
import { FileDocument, type FileDocumentSnapshot } from "../../lib/file-document";
import { WorkspaceFilesClient } from "../../lib/files-client";
import { fileName, isImagePath, isMarkdownPath, readOnlyMessage, truncatedMessage } from "../../lib/files";
import { documentFileContents } from "../../lib/file-view";
import { registerRobocoDiffsTheme, robocoDiffsThemes } from "../../lib/pierre-theme";
import { WorkspaceTreeModel } from "../../lib/workspace-tree";
import { clipMarkdownBytes, parseMarkdown, type TaskMarker } from "../../lib/markdown-doc";
import { useResolvedAppearance } from "../../state/appearance";
import { fileDocuments, type FileSurfaceEntry } from "../../state/file-documents";
import { rightPaneStore } from "../../state/right-pane";
import { onShortcut } from "../../state/shortcuts";
import { uiSettings, useUiSettings } from "../../state/ui-settings";
import { useEngineSession } from "../../state/session-provider";
import { previewLineHeight, previewTextSize } from "../../lib/typography";
import { Tooltip, TOOLTIP_VIEW_OPTIONS_MS } from "../ui/Tooltip";
import { FileIcon } from "./file-icon";
import { ImageView, loadWorkspaceImage, type WorkspaceImageLoad } from "./image-view";
import { MarkdownView } from "./markdown-view";

/**
 * The right pane's File surface body — the web peer of the desktop's
 * promoted editor presentation (crates/ui/src/files/preview.rs): the
 * breadcrumb toolbar, the write-outcome banners, and the document body
 * (code view, markdown preview, or image). The tree sidebar split is gone
 * (07aaa418): the explorer is a docked portion of the pane and reveals go
 * through it. One FileDocument per tab, driven by its own chat-scoped
 * watch; Mod-S saves through the shortcut bus; a close of a dirty tab goes
 * through `prepareClose` (allow / pending / blocked) with the
 * Retry/Keep Open/Discard banner instead of discarding edits.
 *
 * Web-pierre-adoption (ticket 05, ADR 0008): the code body is the diffs
 * library's read-only `File` — highlighted, line-numbered, virtualized —
 * fed by the document machinery (`lib/file-view.ts` maps the read outcome
 * onto `FileContents`, content hash as the highlight cache key). Web file
 * EDITING is suspended until ticket 07 bridges the library's edit mode onto
 * the same machinery: the deferral notice stands where the editor's
 * affordances used to be, so the surface never presents a dead editor. The
 * save/autosave/conflict machinery stays live underneath (the markdown
 * preview's task checkboxes still write through it, and 07 restores the
 * code arm); the hand-rolled textarea-overlay editor and its tokenizer are
 * deleted.
 */

registerRobocoDiffsTheme();

export function FileSurface({ chatId, surfaceId }: { chatId: string; surfaceId: string }) {
  const session = useEngineSession();
  // The surface's path moves with the tab (renames); read it per store
  // version so the body follows.
  useSyncExternalStore(rightPaneStore.subscribe, rightPaneStore.getVersion);
  const path = rightPaneStore.filePathOf(surfaceId);

  const client = useMemo(
    () => (session !== null && path !== null ? new WorkspaceFilesClient(session.client, { chatId }) : null),
    [session, chatId, path],
  );

  // The surface's live entry — document plus tree sidebar — lives in the
  // registry, OUTLIVING this component (the pane host mounts one surface at
  // a time; a tab switch must never discard the buffer — the desktop's
  // `file_surfaces` entities stay alive the same way). First mount creates
  // and loads; later mounts reuse.
  const [entry, setEntry] = useState<FileSurfaceEntry | null>(null);
  useEffect(() => {
    if (client === null || session === null || path === null || isImagePath(path)) {
      setEntry(null);
      return;
    }
    const existing = fileDocuments.entryFor(surfaceId);
    if (existing !== null && existing.path === path) {
      setEntry(existing);
      return;
    }
    const document = new FileDocument(client, path, {
      autosaveDelayMs: uiSettings.getSnapshot().filesAutosaveDelayMs,
    });
    document.load();
    const model = new WorkspaceTreeModel({
      client,
      watch: (handlers) => client.watchFiles(session.client, handlers),
      includeIgnored: uiSettings.getSnapshot().filesShowAll,
      onFileEvent: (event) => {
        if (document.path !== path) {
          return;
        }
        if (event.kind === "modified") {
          document.reconcile();
        } else if (event.kind === "removed") {
          document.markDeleted();
        } else if (event.kind === "created") {
          document.restore();
        }
        // `renamed` handling is not wired on web yet — the tab title keeps
        // the old path until the surface reopens (see ticket Comments).
      },
    });
    model.start();
    const created: FileSurfaceEntry = { document, model, path };
    setEntry(created);
    const detach = fileDocuments.attach(surfaceId, created);
    // Unmount only detaches the listener — the entry (and the buffer)
    // survives until the tab actually closes (`disposeSurface`).
    return () => {
      detach();
    };
  }, [client, session, path, surfaceId]);

  if (path === null) {
    return (
      <div className="files-viewer">
        <p className="files-note">Loading file…</p>
      </div>
    );
  }

  // Images fetch through their own RPC path and never mint a document.
  const isImage = isImagePath(path);

  return (
    <div className="files-viewer-pane">
      {isImage ? (
        <ImageViewer
          chatId={chatId}
          client={client}
          path={path}
        />
      ) : (
        <TextViewer
          doc={entry?.document ?? null}
          path={path}
          chatId={chatId}
          surfaceId={surfaceId}
          client={client}
        />
      )}
    </div>
  );
}

// ── The breadcrumb toolbar (render_breadcrumb, preview.rs:2281-2520) ──────

function ViewerToolbar({
  path,
  markdown,
  showingMarkdown,
  snapshot,
  onToggleMarkdown,
  onReveal,
  onToggleWordWrap,
  wordWrap,
  onRetrySave,
}: {
  readonly path: string;
  readonly markdown: boolean;
  readonly showingMarkdown: boolean;
  readonly snapshot: FileDocumentSnapshot | null;
  readonly onToggleMarkdown: (() => void) | null;
  readonly onReveal: () => void;
  readonly onToggleWordWrap: () => void;
  readonly wordWrap: boolean;
  readonly onRetrySave: (() => void) | null;
}) {
  const appearance = useResolvedAppearance();
  const parts = path.split("/");
  return (
    <div className="files-breadcrumb-bar">
      <FileIcon kind="file" name={path} size={14} className="files-breadcrumb-icon" />
      <Tooltip
        label={path}
        delay={TOOLTIP_VIEW_OPTIONS_MS}
        trigger={
          <div className="files-breadcrumb">
            {parts.map((part, index) => (
              <span key={index} className="files-crumb">
                {index > 0 && <span className="files-crumb-sep">›</span>}
                <span className={index + 1 === parts.length ? "files-crumb-final" : "files-crumb-part"}>{part}</span>
              </span>
            ))}
          </div>
        }
      />
      {markdown && onToggleMarkdown !== null && (
        <Tooltip
          label={showingMarkdown ? "Show Markdown code" : "Preview Markdown"}
          delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <button
              type="button"
              className={`files-toolbar-button${showingMarkdown ? " files-toolbar-button-on" : ""}`}
              aria-pressed={showingMarkdown}
              aria-label={showingMarkdown ? "Show Markdown code" : "Preview Markdown"}
              onClick={onToggleMarkdown}
            >
              <Icon name={showingMarkdown ? "fileCode" : "eye"} size={14} />
            </button>
          }
        />
      )}
      {snapshot !== null && <SaveStatusPill snapshot={snapshot} onRetry={onRetrySave} />}
      <Tooltip
        label="Reveal file in tree"
        delay={TOOLTIP_VIEW_OPTIONS_MS}
        trigger={
          <button
            type="button"
            className="files-toolbar-button"
            aria-label="Reveal file in tree"
            onClick={onReveal}
          >
            <Icon name="folder" size={14} />
          </button>
        }
      />
      <Tooltip
        label={wordWrap ? "Disable word wrap" : "Enable word wrap"}
        delay={TOOLTIP_VIEW_OPTIONS_MS}
        trigger={
          <button
            type="button"
            className={`files-toolbar-button${wordWrap ? " files-toolbar-button-on" : ""}`}
            aria-pressed={wordWrap}
            aria-label={wordWrap ? "Disable word wrap" : "Enable word wrap"}
            onClick={onToggleWordWrap}
          >
            <Icon name="list" size={14} />
          </button>
        }
      />
    </div>
  );
}

/**
 * The save-status pill (`files-save-status`, preview.rs:2380-2428):
 * phase-colored, `saveFailed` clickable as the retry, tooltips after 350ms.
 */
function SaveStatusPill({
  snapshot,
  onRetry,
}: {
  readonly snapshot: FileDocumentSnapshot;
  readonly onRetry: (() => void) | null;
}) {
  switch (snapshot.phase.kind) {
    case "saveFailed":
      return (
        <Tooltip label={snapshot.phase.message} delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <button
              type="button"
              className="files-save-pill files-save-pill-danger"
              aria-label="Save file"
              onClick={() => onRetry?.()}
            >
              Save failed
            </button>
          }
        />
      );
    case "conflict":
      return (
        <Tooltip label="The file changed on disk. Your editor buffer was preserved." delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <span className="files-save-pill files-save-pill-warning" role="status">Save conflict</span>
          }
        />
      );
    case "deletedOnDisk":
      return (
        <Tooltip label="The file was removed on disk. Your editor buffer was preserved." delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <span className="files-save-pill files-save-pill-warning" role="status">Deleted on disk</span>
          }
        />
      );
    case "externallyModified":
      return (
        <Tooltip label="The file changed on disk. Review it before saving." delay={TOOLTIP_VIEW_OPTIONS_MS}
          trigger={
            <span className="files-save-pill files-save-pill-warning" role="status">Changed on disk</span>
          }
        />
      );
    default:
      return null;
  }
}

// ── Text and markdown ───────────────────────────────────────────────────

function TextViewer({
  doc,
  path,
  chatId,
  surfaceId,
  client,
}: {
  readonly doc: FileDocument | null;
  readonly path: string;
  readonly chatId: string;
  readonly surfaceId: string;
  readonly client: WorkspaceFilesClient | null;
}) {
  const settings = useUiSettings();
  // The pre-attach snapshot (no document yet): cached so the store hook's
  // getSnapshot stays referentially stable while the entry arrives.
  const pendingSnapshot = useMemo<FileDocumentSnapshot>(
    () => ({
      phase: { kind: "loading" },
      text: "",
      file: null,
      editable: false,
      dirty: false,
      showMarkdown: isMarkdownPath(path),
    }),
    [path],
  );
  const subscribe = useCallback(
    (listener: () => void) => (doc === null ? () => {} : doc.subscribe(listener)),
    [doc],
  );
  const getSnapshot = useCallback(
    () => doc?.getSnapshot() ?? pendingSnapshot,
    [doc, pendingSnapshot],
  );
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const markdown = isMarkdownPath(path);
  const [confirmingReload, setConfirmingReload] = useState(false);

  // Autosave configuration follows the settings store (desktop
  // set_autosave_enabled / set_autosave_delay_ms fan-out).
  useEffect(() => {
    doc?.configureAutosave(settings.filesAutosaveEnabled, settings.filesAutosaveDelayMs);
  }, [doc, settings.filesAutosaveEnabled, settings.filesAutosaveDelayMs]);

  // A pending destructive-reload confirmation pauses autosave so it cannot
  // race the user's choice (preview.rs request_reload).
  useEffect(() => {
    doc?.setAutosavePaused(confirmingReload);
  }, [doc, confirmingReload]);

  // Mod-S saves through the shortcut bus (`state/shortcuts.ts` — the
  // app-shell guards it to a chat route with the pane on a Files/File
  // surface), never a component-local keydown listener.
  useEffect(
    () =>
      onShortcut("save-file", () => {
        doc?.save();
      }),
    [doc],
  );

  // The pane host's close request: complete once the saves land.
  const surface = useMemo<{ kind: "file"; id: string }>(() => ({ kind: "file", id: surfaceId }), [surfaceId]);
  const closeRequested = rightPaneStore.isCloseRequested(surface);
  useEffect(() => {
    if (closeRequested && !snapshot.dirty) {
      rightPaneStore.completeFileClose(chatId, surface);
    }
  }, [closeRequested, snapshot.dirty, chatId, surface]);

  const markdownClip = useMemo(
    () => clipMarkdownBytes(snapshot.text),
    [snapshot.text],
  );
  const markdownBlocks = useMemo(
    () => (markdown && snapshot.showMarkdown ? parseMarkdown(markdownClip.text) : null),
    [markdown, snapshot.showMarkdown, markdownClip.text],
  );

  const checkoutId = snapshot.file?.checkoutId ?? null;
  const loadImage = useCallback(
    (imagePath: string): Promise<WorkspaceImageLoad> => {
      if (client === null || checkoutId === null) {
        return Promise.reject(new Error("Workspace image connection unavailable"));
      }
      return loadWorkspaceImage(client, imagePath);
    },
    [client, checkoutId],
  );

  const onToggleTask = useCallback(
    (marker: TaskMarker, next: boolean): void => {
      if (doc === null || !snapshot.editable) {
        return;
      }
      // Only when the parsed source still matches the buffer (the offsets
      // must stay honest), and only through a single edit.
      const source = doc.getSnapshot().text;
      if (source !== markdownClip.text) {
        return;
      }
      doc.edit(
        source.slice(0, marker.offset) + (next ? "[x]" : "[ ]") + source.slice(marker.offset + 3),
      );
    },
    [doc, snapshot.editable, markdownClip.text],
  );

  // Web file editing is suspended (ADR 0008; ticket 07 restores it through
  // the library's edit mode) — the code body below is read-only. The
  // editor-side review wiring (ticket 23's floating cards/drafts over the
  // editable arm) goes dark with it, the same interim pattern the Changes
  // pane's comments rode through 02→03: the review store's editor-draft
  // methods and the editor-comment components stay intact for 07 to mount
  // over the library's editor surface.

  const toolbar = (
    <ViewerToolbar
      path={path}
      markdown={markdown}
      showingMarkdown={snapshot.showMarkdown}
      snapshot={doc === null ? null : snapshot}
      onToggleMarkdown={
        doc !== null
          ? () => {
              doc.setShowMarkdown(!snapshot.showMarkdown);
            }
          : null
      }
      onReveal={() => {
        // `files-reveal-active` → RevealFile: dock the explorer and reveal
        // there (the editor surface carries no tree of its own anymore).
        rightPaneStore.revealInFilesPanel(chatId, path);
      }}
      onToggleWordWrap={() => {
        uiSettings.updateImmediate({ filesWordWrap: !settings.filesWordWrap });
      }}
      wordWrap={settings.filesWordWrap}
      onRetrySave={doc === null ? null : () => doc.save()}
    />
  );

  let body: React.ReactNode;
  if (snapshot.phase.kind === "loading") {
    body = <p className="files-note files-note-faint">Loading file…</p>;
  } else if (snapshot.phase.kind === "error") {
    body = (
      <div className="files-note">
        <p className="files-note-error">{snapshot.phase.message}</p>
        <button type="button" className="btn btn-ghost" onClick={() => doc?.load()}>
          Retry
        </button>
      </div>
    );
  } else if (snapshot.file !== null && snapshot.phase.kind === "readOnly" && snapshot.file.text == null) {
    body = <p className="files-note files-note-faint">{readOnlyMessage(snapshot.phase.reason)}</p>;
  } else if (markdownBlocks !== null) {
    body = (
      <div className="files-markdown-scroll">
        <MarkdownView
          blocks={markdownBlocks}
          documentPath={path}
          truncated={markdownClip.truncated}
          editable={snapshot.editable}
          onToggleTask={onToggleTask}
          onOpenPath={(target) => rightPaneStore.addFileSurface(chatId, target)}
          loadImage={client !== null && checkoutId !== null ? loadImage : null}
        />
      </div>
    );
  } else {
    // The code body: read-only through the library (ADR 0008 — editing
    // returns with ticket 07). The truncation banner is unchanged; the
    // deferral notice stands where the editor's affordances used to be,
    // only for documents that WOULD have opened in the editor (read-only
    // files already carry their reason).
    const truncated = snapshot.file !== null ? truncatedMessage(snapshot.file) : null;
    body = (
      <div className="files-editor-body">
        {snapshot.editable && (
          <div className="files-readonly-note" role="note">
            Web file editing isn’t available yet — this view is read-only.
          </div>
        )}
        {truncated !== null && <div className="files-truncated-banner" role="status">{truncated}</div>}
        <ReadOnlyCodeBody
          path={path}
          snapshot={snapshot}
          wordWrap={settings.filesWordWrap}
          codeFontSize={settings.codeFontSize}
        />
      </div>
    );
  }

  return (
    <div className="files-viewer-column">
      <div className="surface-toolbar files-viewer-toolbar" role="toolbar" aria-label="File viewer">
        <div className="files-viewer-toolbar-leading">{toolbar}</div>
      </div>
      <div className="files-viewer">
        <CloseLifecycleBanner
          chatId={chatId}
          surface={surface}
          snapshot={snapshot}
          doc={doc}
        />
        <PhaseBanner
          snapshot={snapshot}
          doc={doc}
          confirmingReload={confirmingReload}
          onRequestReload={() => {
            // request_reload: dirty buffers ask before discarding.
            if (snapshot.dirty) {
              setConfirmingReload(true);
            } else {
              doc?.reloadFromDisk();
            }
          }}
          onCancelReload={() => setConfirmingReload(false)}
          onConfirmReload={() => {
            setConfirmingReload(false);
            doc?.reloadFromDisk();
          }}
          onKeepEditing={() => {
            setConfirmingReload(false);
            doc?.keepEditing();
          }}
        />
        <div className="files-viewer-body">{body}</div>
      </div>
    </div>
  );
}

/**
 * The read-only code body (web-pierre-adoption, ticket 05): the diffs
 * library’s virtualized `File` inside a `Virtualizer` scroll container —
 * highlighted by the registered Roboco theme pair (themeType following the
 * resolved appearance), line numbers on, word wrap per the setting, the
 * content hash as the highlight cache key. The library renders in shadow
 * DOM; our host class (`.files-code-host`, app.css) owns the layout and the
 * `--diffs-*` token mapping exactly like the Changes pane’s host.
 */
function ReadOnlyCodeBody({
  path,
  snapshot,
  wordWrap,
  codeFontSize,
}: {
  readonly path: string;
  readonly snapshot: FileDocumentSnapshot;
  readonly wordWrap: boolean;
  readonly codeFontSize: number;
}) {
  const appearance = useResolvedAppearance();
  const file = useMemo(() => documentFileContents(path, snapshot), [path, snapshot]);
  const options = useMemo<FileOptions<undefined, undefined>>(
    () => ({
      theme: robocoDiffsThemes(),
      themeType: appearance,
      overflow: wordWrap ? "wrap" : "scroll",
      stickyHeader: true,
    }),
    [appearance, wordWrap],
  );
  return (
    <Virtualizer className="files-code-host">
      <LibraryFile
        className="files-code-file"
        style={{
          ["--diffs-font-size" as string]: `${previewTextSize(codeFontSize)}px`,
          ["--diffs-line-height" as string]: `${previewLineHeight(codeFontSize)}px`,
        }}
        file={file}
        options={options}
      />
    </Virtualizer>
  );
}

/**
 * The close-request banner (preview.rs:2082-2140): pending saves say so;
 * a blocked close offers Retry / Keep Open / Discard Changes.
 */
function CloseLifecycleBanner({
  chatId,
  surface,
  snapshot,
  doc,
}: {
  readonly chatId: string;
  readonly surface: { kind: "file"; id: string };
  readonly snapshot: FileDocumentSnapshot;
  readonly doc: FileDocument | null;
}) {
  if (!rightPaneStore.isCloseRequested(surface)) {
    return null;
  }
  const blocked = doc !== null && doc.blocksLifecycleClose();
  return (
    <div className="files-banner files-banner-lifecycle" role="alert">
      <span className="files-banner-detail">
        {blocked ? "Changes could not be saved safely." : "Saving changes before closing…"}
      </span>
      {blocked && (
        <span className="files-banner-actions">
          <button type="button" className="files-banner-action" onClick={() => doc?.save()}>
            Retry
          </button>
          <button
            type="button"
            className="files-banner-action files-banner-action-muted"
            onClick={() => rightPaneStore.cancelFileClose(chatId, surface)}
          >
            Keep Open
          </button>
          <button
            type="button"
            className="files-banner-action files-banner-action-danger"
            onClick={() => {
              doc?.discardChanges();
              rightPaneStore.completeFileClose(chatId, surface);
            }}
          >
            Discard Changes
          </button>
        </span>
      )}
    </div>
  );
}

/**
 * The external-change / reload-confirmation banner (preview.rs:2181-2241):
 * "This file changed outside Roboco." with Keep Editing / Reload from Disk,
 * or the two-step "Discard unsaved changes?" with Cancel / Discard & Reload.
 */
function PhaseBanner({
  snapshot,
  doc,
  confirmingReload,
  onRequestReload,
  onCancelReload,
  onConfirmReload,
  onKeepEditing,
}: {
  readonly snapshot: FileDocumentSnapshot;
  readonly doc: FileDocument | null;
  readonly confirmingReload: boolean;
  readonly onRequestReload: () => void;
  readonly onCancelReload: () => void;
  readonly onConfirmReload: () => void;
  readonly onKeepEditing: () => void;
}) {
  const external =
    snapshot.phase.kind === "externallyModified" || snapshot.phase.kind === "conflict";
  if (!external && !confirmingReload) {
    return null;
  }
  return (
    <div className="files-banner files-banner-external" role={snapshot.phase.kind === "conflict" ? "alert" : "status"}>
      <span className="files-banner-detail">
        {confirmingReload ? "Discard unsaved changes?" : "This file changed outside Roboco."}
      </span>
      <span className="files-banner-actions">
        {confirmingReload ? (
          <>
            <button type="button" className="files-banner-action files-banner-action-muted" onClick={onCancelReload}>
              Cancel
            </button>
            <button type="button" className="files-banner-action" onClick={onConfirmReload}>
              Discard & Reload
            </button>
          </>
        ) : (
          <>
            <button type="button" className="files-banner-action files-banner-action-muted" onClick={onKeepEditing}>
              Keep Editing
            </button>
            <button type="button" className="files-banner-action" onClick={onRequestReload}>
              Reload from Disk
            </button>
          </>
        )}
      </span>
    </div>
  );
}

// ── Images ──────────────────────────────────────────────────────────────

type ImageState =
  | { readonly kind: "loading" }
  | { readonly kind: "loaded"; readonly load: WorkspaceImageLoad }
  | { readonly kind: "error"; readonly message: string };

function ImageViewer({
  chatId,
  client,
  path,
}: {
  readonly chatId: string;
  readonly client: WorkspaceFilesClient | null;
  readonly path: string;
}) {
  const settings = useUiSettings();
  const [state, setState] = useState<ImageState>({ kind: "loading" });

  useEffect(() => {
    if (client === null) {
      return;
    }
    let cancelled = false;
    let url: string | null = null;
    setState({ kind: "loading" });
    void (async () => {
      try {
        const load = await loadWorkspaceImage(client, path);
        if (cancelled) {
          URL.revokeObjectURL(load.url);
          return;
        }
        url = load.url;
        setState({ kind: "loaded", load });
      } catch (error) {
        if (!cancelled) {
          setState({ kind: "error", message: error instanceof Error ? error.message : String(error) });
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

  const toolbar = (
    <ViewerToolbar
      path={path}
      markdown={false}
      showingMarkdown={false}
      snapshot={null}
      onToggleMarkdown={null}
      onReveal={() => {
        rightPaneStore.revealInFilesPanel(chatId, path);
      }}
      onToggleWordWrap={() => {
        uiSettings.updateImmediate({ filesWordWrap: !settings.filesWordWrap });
      }}
      wordWrap={settings.filesWordWrap}
      onRetrySave={null}
    />
  );

  return (
    <div className="files-viewer-column">
      <div className="surface-toolbar files-viewer-toolbar" role="toolbar" aria-label="File viewer">
        <div className="files-viewer-toolbar-leading">{toolbar}</div>
      </div>
      <div className="files-viewer">
        <div className="files-viewer-body files-image-body">
          {state.kind === "loading" && <p className="files-note files-note-faint">Loading image…</p>}
          {state.kind === "error" && <p className="files-note files-note-error">{state.message}</p>}
          {state.kind === "loaded" && (
            <ImageView
              src={state.load.url}
              natural={{ width: state.load.width, height: state.load.height }}
              alt={fileName(path)}
            />
          )}
        </div>
      </div>
    </div>
  );
}

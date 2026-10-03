import { useCallback, useEffect, useRef, useState } from "react";
import { Icon } from "@roboco/icons";
import { formatByName, stageBytes, stageFile, type StagedAttachment } from "../../lib/attachments";
import { chatFileInserts } from "../../lib/chat-insert";
import { workspaceDragActive, workspaceDragPayload } from "../../lib/workspace-drag";
import { Lightbox } from "../lightbox";

/**
 * The composer's staged-attachment strip — the web port of
 * `composer.rs::render_attachment_strip` (:4636). A wrap grid of bare 56px
 * thumbnails: no filenames, no attach button (the paperclip in the actions
 * cluster is the ONE attach affordance, driven through `pickerRef`), no
 * progress bar (upload progress renders in the TRANSCRIPT, as a ring over
 * the optimistic echo's thumbnails). The remove button hides until hover.
 *
 * The staging sources: the hidden picker input (paperclip), clipboard paste
 * (also forwarded from the composer's textarea), and an OS file drop on the
 * WHOLE conversation column — the desktop's `shell.rs #chat-dropzone`. The
 * VEIL is the shell's (app-shell.tsx's `#attachment-drop-overlay`, ticket
 * 06); this component stages the dropped files by listening for the drop on
 * its `.chat-column` ancestor, which every drop inside the column bubbles
 * through. Non-image files are skipped silently (`add_paths`); genuine
 * failures (oversize, unreadable bytes) surface through `onError`.
 *
 * The same dropzone also accepts the INTERNAL workspace-path drag (tree and
 * search rows' `application/x-roboco-workspace-path` payload, the desktop's
 * `WorkspacePathDrag`): a drop converts into a file mention in THIS
 * column's composer through the `chatFileInserts` hand-off — the tree's
 * "Add to chat" row and the composer's `insert_dropped_mention` peer — so
 * referencing a file never requires typing its path. The side-chat panes
 * have no `.chat-column` ancestor; their composer column (`.side-chat-
 * composer`) is a drop zone on its own, so a side chat's composer takes the
 * same drops. The drag-over is canceled for the internal MIME only (an OS
 * file drag keeps the shell's own overlay semantics) — that cancellation is
 * what makes the column a valid drop target for our own drags.
 */

interface AttachmentStripProps {
  readonly chatId: string;
  readonly staged: readonly StagedAttachment[];
  readonly disabled?: boolean;
  readonly onStage: (attachments: readonly StagedAttachment[]) => void;
  readonly onRemove: (id: string) => void;
  readonly onError: (message: string) => void;
  /**
   * Filled with the strip's own picker opener so the composer's paperclip —
   * which lives in the actions cluster, as on the desktop — can drive it.
   */
  readonly pickerRef?: React.MutableRefObject<(() => void) | null>;
}

export function AttachmentStrip({
  chatId,
  staged,
  disabled,
  onStage,
  onRemove,
  onError,
  pickerRef,
}: AttachmentStripProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const [preview, setPreview] = useState<StagedAttachment | null>(null);
  // The dropzone is the strip's `.chat-column` ancestor — the whole
  // conversation column (transcript + composer), like `#chat-dropzone` —
  // or, in a side-chat pane, the composer column hosting this strip (there
  // is no `.chat-column` there; the pane's composer column is the zone).
  // Re-resolved on mount since the column re-mounts per chat page.
  const [dropZone, setDropZone] = useState<HTMLElement | null>(null);

  // Resets the file input whenever the chat changes so the same file can
  // be re-picked (browsers refuse to fire `change` for an identical pick).
  useEffect(() => {
    if (fileInputRef.current !== null) {
      fileInputRef.current.value = "";
    }
  }, [chatId]);

  // Re-resolved on mount since the column re-mounts per chat page.
  useEffect(() => {
    const zone = wrapperRef.current?.closest(".chat-column, .side-chat-composer") ?? null;
    setDropZone(zone instanceof HTMLElement ? zone : null);
  }, [chatId]);

  /** Stage `File` objects picked up from a picker / drop / paste event. */
  const ingest = useCallback(
    async (files: FileList | File[]) => {
      const next: StagedAttachment[] = [];
      for (const file of Array.from(files)) {
        // `add_paths` skips unsupported formats SILENTLY (the browser's own
        // picker already filtered; a phone photo-roll drag can sneak a
        // non-image through, which just doesn't stage).
        if (formatByName(file.name) === null) {
          continue;
        }
        try {
          next.push(await stageFile(file));
        } catch (error) {
          // Genuine failures (oversize, unreadable bytes) set `failure`.
          onError(error instanceof Error ? error.message : String(error));
        }
      }
      if (next.length > 0) {
        onStage(next);
      }
    },
    [onStage, onError],
  );

  const ingestRef = useRef(ingest);
  ingestRef.current = ingest;

  // `#chat-dropzone`'s `on_drop::<ExternalPaths>` (shell.rs:5998-6003): every
  // drop inside the conversation column stages its image files here. The
  // shell's own listener on `main.panel` swallows the browser default; this
  // one, on the column the composer lives in, is the staging half. The
  // internal workspace-path drag (tree/search rows) lands on the SAME zone
  // (`attach_workspace_drag` → `add_workspace_path`): the drag-over is
  // canceled for our MIME only — that is what keeps the drag alive over the
  // column — and the drop hands the payload to THIS column's composer as a
  // file mention through the shared `chatFileInserts` store, reusing the
  // tree's "Add to chat" hand-off rather than a parallel mechanism.
  useEffect(() => {
    const zone = dropZone;
    if (zone === null) {
      return;
    }
    const onDragOver = (event: DragEvent): void => {
      if (!workspaceDragActive(event)) {
        return;
      }
      event.preventDefault();
      if (event.dataTransfer !== null) {
        event.dataTransfer.dropEffect = "copy";
      }
    };
    const onDrop = (event: DragEvent): void => {
      const mention = workspaceDragPayload(event);
      if (mention !== null) {
        // A file mention (never a staging): consume the drag so the payload's
        // text/plain half cannot also paste the path into the textarea.
        event.preventDefault();
        if (disabled !== true) {
          chatFileInserts.insert(chatId, mention);
        }
        return;
      }
      if (!Array.from(event.dataTransfer?.types ?? []).includes("Files")) {
        return;
      }
      event.preventDefault();
      const files = event.dataTransfer?.files;
      if (files === undefined || files.length === 0 || disabled === true) {
        return;
      }
      void ingestRef.current(files);
    };
    zone.addEventListener("dragover", onDragOver);
    zone.addEventListener("drop", onDrop);
    return () => {
      zone.removeEventListener("dragover", onDragOver);
      zone.removeEventListener("drop", onDrop);
    };
  }, [dropZone, disabled, chatId]);

  const onPickerChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const files = event.target.files;
      if (files === null || files.length === 0) {
        return;
      }
      void ingest(files);
    },
    [ingest],
  );

  const openPicker = useCallback(() => {
    if (disabled === true) {
      return;
    }
    fileInputRef.current?.click();
  }, [disabled]);

  useEffect(() => {
    if (pickerRef === undefined) {
      return;
    }
    pickerRef.current = openPicker;
    return () => {
      pickerRef.current = null;
    };
  }, [pickerRef, openPicker]);

  // Clipboard paste landing inside the strip wrapper (the textarea's own
  // paste is forwarded by the composer). Image files stage; anything else
  // falls through to the native text paste.
  const onPaste = useCallback(
    (event: React.ClipboardEvent<HTMLDivElement>) => {
      if (disabled === true) {
        return;
      }
      const items = event.clipboardData?.items;
      if (items === undefined) {
        return;
      }
      const files: File[] = [];
      for (const item of Array.from(items)) {
        if (item.kind !== "file") {
          continue;
        }
        const file = item.getAsFile();
        if (file !== null) {
          files.push(file);
        }
      }
      if (files.length > 0) {
        event.preventDefault();
        void ingest(files);
      }
    },
    [ingest, disabled],
  );

  return (
    <div className="composer-attachments" ref={wrapperRef} onPaste={onPaste}>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml,image/bmp,image/tiff"
        multiple
        className="composer-attachments-input"
        onChange={onPickerChange}
        disabled={disabled === true}
        tabIndex={-1}
        aria-hidden
      />
      {staged.length > 0 && (
        <div className="composer-attachments-strip">
          {staged.map((att) => (
            <StagedAttachmentRow
              key={att.id}
              attachment={att}
              onRemove={() => onRemove(att.id)}
              onPreview={() => setPreview(att)}
            />
          ))}
        </div>
      )}
      {preview !== null && (
        <Lightbox
          name={preview.name}
          src={preview.previewUrl}
          onClose={() => setPreview(null)}
        />
      )}
    </div>
  );
}

/**
 * One staged thumbnail (composer.rs:4654-4723): the 56px frame (click opens
 * the lightbox) plus the hover-revealed remove button that floats over its
 * corner. No filename, ever.
 */
function StagedAttachmentRow({
  attachment,
  onRemove,
  onPreview,
}: {
  attachment: StagedAttachment;
  onRemove: () => void;
  onPreview: () => void;
}) {
  return (
    <div className="composer-staged-row">
      <button
        type="button"
        className="composer-staged-thumb"
        onClick={onPreview}
        aria-label={`Preview ${attachment.name}`}
      >
        {/* Explicit 54px dims (56 − the 1px borders) with the img's own 7px
            radius: the frame's rounding clips rectangularly, and a percent
            height would let a tall photo grow past the frame. */}
        <img
          className="composer-staged-thumb-img"
          src={attachment.previewUrl}
          alt=""
          draggable={false}
        />
      </button>
      <button
        type="button"
        className="composer-staged-remove"
        onClick={(event) => {
          // The button overhangs the thumbnail's hitbox — don't let the same
          // click also open the preview (composer.rs:4711-4716).
          event.stopPropagation();
          onRemove();
        }}
        aria-label={`Remove ${attachment.name}`}
      >
        <Icon name="closeCircle" size={14} />
      </button>
    </div>
  );
}

/** Test-only: a no-side-effect way to stage raw bytes (clipboard paste path). */
export const __stageBytesForTests = stageBytes;

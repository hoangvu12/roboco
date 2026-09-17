import { useCallback, useEffect, useRef, useState } from "react";
import { formatByName, stageBytes, stageFile, type StagedAttachment } from "../../lib/attachments";

/**
 * The composer's attachment strip — the web port of `crates/ui/src/composer.rs`
 * `add_paths` / `stage_file` / the 56px-thumbnail strip. Holds staged
 * attachments locally (the bytes travel only when the user actually sends);
 * each row shows a thumbnail, a per-row progress ring, and an × button.
 *
 * Drop zone: phone browsers expose the camera button on the picker (the
 * `accept="image/..."` hint) and desktop browsers expose drag-and-drop
 * here. Clipboard paste lands here too.
 *
 * The strip's drop target is the row (the attach button + the thumbnails).
 * The composer can opt to broaden the drop area to its full extent by
 * forwarding paste events from the textarea.
 */

interface AttachmentStripProps {
  readonly chatId: string;
  readonly staged: readonly StagedAttachment[];
  /** Progress 0..1 across the WHOLE send — the strip paints a single bar
   *  under the row instead of one ring per row. Optional. */
  readonly uploadProgress: number | null;
  readonly disabled?: boolean;
  readonly onStage: (attachments: readonly StagedAttachment[]) => void;
  readonly onRemove: (id: string) => void;
  readonly onError: (message: string) => void;
  /**
   * Filled with the strip's own picker opener so the composer's paperclip —
   * which lives in the actions cluster, as on the desktop — can drive it.
   * When set, the strip drops its inline Attach button: two attach
   * affordances in one pill is one more than the desktop has.
   */
  readonly pickerRef?: React.MutableRefObject<(() => void) | null>;
}

/** Stage `File` objects picked up from a picker / drop / paste event. */
export function AttachmentStrip({
  chatId,
  staged,
  uploadProgress,
  disabled,
  onStage,
  onRemove,
  onError,
  pickerRef,
}: AttachmentStripProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const dragCounter = useRef(0);

  // Resets the file input whenever the chat changes so the same file can
  // be re-picked (browsers refuse to fire `change` for an identical pick).
  useEffect(() => {
    if (fileInputRef.current !== null) {
      fileInputRef.current.value = "";
    }
  }, [chatId]);

  const ingest = useCallback(
    async (files: FileList | File[]) => {
      const next: StagedAttachment[] = [];
      for (const file of Array.from(files)) {
        // The browser already filters by `accept`, but a drag from a phone's
        // photo roll can sneak non-images through; check the extension as
        // a final guard before reading the (potentially large) bytes.
        if (formatByName(file.name) === null) {
          onError(`${file.name} is not a supported image.`);
          continue;
        }
        try {
          const staged = await stageFile(file);
          next.push(staged);
        } catch (error) {
          onError(error instanceof Error ? error.message : String(error));
        }
      }
      if (next.length > 0) {
        onStage(next);
      }
    },
    [onStage, onError],
  );

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

  const onDrop = useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      setDragOver(false);
      dragCounter.current = 0;
      if (disabled === true) {
        return;
      }
      const files = event.dataTransfer.files;
      if (files.length === 0) {
        return;
      }
      void ingest(files);
    },
    [ingest, disabled],
  );

  const onDragOver = useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      if (dragOver) {
        return;
      }
      setDragOver(true);
    },
    [dragOver],
  );

  const onDragEnter = useCallback(() => {
    dragCounter.current += 1;
    setDragOver(true);
  }, []);
  const onDragLeave = useCallback(() => {
    dragCounter.current = Math.max(0, dragCounter.current - 1);
    if (dragCounter.current === 0) {
      setDragOver(false);
    }
  }, []);

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

  const hasStaged = staged.length > 0;

  return (
    <div
      className={`composer-attachments ${dragOver ? "composer-attachments-dropping" : ""}`}
      onDrop={onDrop}
      onDragOver={onDragOver}
      onDragEnter={onDragEnter}
      onDragLeave={onDragLeave}
      onPaste={onPaste}
    >
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
      {/*
        No drop veil and no inline attach button here: the drop overlay belongs
        to the shell's `#chat-dropzone`, and the composer's ONE attach
        affordance is the paperclip in its actions cluster (`pickerRef`).
      */}
      <div className="composer-attachments-row">
        {hasStaged && (
          <div className="composer-attachments-strip">
            {staged.map((att) => (
              <StagedAttachmentRow
                key={att.id}
                attachment={att}
                onRemove={() => onRemove(att.id)}
              />
            ))}
          </div>
        )}
      </div>
      {/*
        No progress bar in the strip: the desktop publishes upload progress
        into `AppState.begin_upload_progress` and renders it elsewhere. The
        `uploadProgress` plumbing stays (ticket 17 picks where it surfaces).
      */}
    </div>
  );
}

function StagedAttachmentRow({
  attachment,
  onRemove,
}: {
  attachment: StagedAttachment;
  onRemove: () => void;
}) {
  return (
    <div className="composer-staged-row" title={attachment.name}>
      <img
        className="composer-staged-thumb"
        src={attachment.previewUrl}
        alt=""
        draggable={false}
      />
      <button
        type="button"
        className="composer-staged-remove"
        onClick={onRemove}
        aria-label={`Remove ${attachment.name}`}
        title="Remove"
      >
        ×
      </button>
      {/* No filename caption: the desktop's strip shows thumbs only. */}
    </div>
  );
}

/** Test-only: a no-side-effect way to stage raw bytes (clipboard paste path). */
export const __stageBytesForTests = stageBytes;
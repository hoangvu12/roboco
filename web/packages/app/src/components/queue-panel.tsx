import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { QueuedMessage } from "@roboco/proto";
import { useQueueStore } from "../state/queue-store-context";
import { sidebarNotice } from "../state/notice";
import { describeQueueError, mintEditorInstanceId } from "../lib/queue-actions";

/**
 * The message-queue panel — web peer of `crates/ui/src/queue.rs`. Docked
 * directly above the composer; each row exposes Send now / Edit / Remove
 * actions and shows delivery-gate state (Editing on another device;
 * Review required after an expired lease). Editing moves the
 * row's text into the composer (the chat page handles the lease lifecycle
 * and feeds the committed text back into the row).
 *
 * Reorder is drag-by-row: a row drops at the gap between two siblings or
 * at the panel edges. The desktop's invisible-drag with a 150ms slide is
 * approximated with an instant `MoveQueuedMessage` — the host is the
 * single source of truth and re-renders the rows on the next snapshot.
 */

interface QueuePanelProps {
  /** The host's verified device id — used as the edit-lease owner. */
  readonly editorDeviceId: string;
  /** Open the composer for editing this row's text. */
  readonly onEditRow: (message: QueuedMessage) => void;
  /** The currently-edited row id (the composer is feeding text into it). */
  readonly editingRowId: string | null;
}

const ROW_HEIGHT_PX = 36;
const ROW_GAP_PX = 4;
const MAX_VISIBLE_ROWS = 5;

export function QueuePanel({ editorDeviceId, onEditRow, editingRowId }: QueuePanelProps) {
  const store = useQueueStore();
  const snapshot = store.getSnapshot();
  const rows = snapshot.rows;
  const busy = useRef(false);
  const [, force] = useState(0);

  // Tick the panel so lease countdowns and stale-gate notices repaint.
  useEffect(() => {
    if (rows.length === 0 && snapshot.editLease === null) {
      return;
    }
    const timer = setInterval(() => force((value) => value + 1), 1000);
    return () => clearInterval(timer);
  }, [rows.length, snapshot.editLease]);

  const editingByOther = useCallback(
    (row: QueuedMessage): boolean => {
      const gate = row.deliveryGate ?? null;
      if (gate === null || gate.kind !== "editing") {
        return false;
      }
      if (gate.ownerDeviceId === editorDeviceId && snapshot.editLease?.messageId === row.id) {
        return false;
      }
      return gate.expiresAtMs > Date.now();
    },
    [editorDeviceId, snapshot.editLease],
  );

  const reviewRequired = useCallback((row: QueuedMessage): boolean => {
    const gate = row.deliveryGate ?? null;
    return gate !== null && gate.kind === "reviewRequired";
  }, []);

  const onSendNow = useCallback(
    async (row: QueuedMessage) => {
      if (busy.current) {
        return;
      }
      busy.current = true;
      try {
        const sent = await store.sendNow(row.id);
        if (!sent) {
          sidebarNotice.set("That message was already drained by another device.");
        }
      } catch (error) {
        sidebarNotice.set(`Could not send now: ${describeQueueError(error)}`);
      } finally {
        busy.current = false;
      }
    },
    [store],
  );

  const onEdit = useCallback(
    async (row: QueuedMessage) => {
      if (busy.current) {
        return;
      }
      busy.current = true;
      try {
        const instanceId = mintEditorInstanceId();
        const outcome = await store.beginEdit(row.id, instanceId);
        if (outcome.kind === "locked") {
          sidebarNotice.set(`Editing on another device until ${formatTime(outcome.expiresAtMs)}.`);
          return;
        }
        if (outcome.kind === "missing") {
          sidebarNotice.set("That message is no longer queued.");
          return;
        }
        onEditRow({ ...row, text: outcome.text });
      } catch (error) {
        sidebarNotice.set(`Could not edit: ${describeQueueError(error)}`);
      } finally {
        busy.current = false;
      }
    },
    [onEditRow, store],
  );

  const onRemove = useCallback(
    async (row: QueuedMessage) => {
      if (busy.current) {
        return;
      }
      busy.current = true;
      try {
        await store.remove(row.id);
      } catch (error) {
        sidebarNotice.set(`Could not remove: ${describeQueueError(error)}`);
      } finally {
        busy.current = false;
      }
    },
    [store],
  );

  const onDrop = useCallback(
    async (fromId: string, toIndex: number) => {
      if (busy.current) {
        return;
      }
      busy.current = true;
      try {
        await store.move(fromId, toIndex);
      } catch (error) {
        sidebarNotice.set(`Could not reorder: ${describeQueueError(error)}`);
      } finally {
        busy.current = false;
      }
    },
    [store],
  );

  if (rows.length === 0 && snapshot.editLease === null) {
    return null;
  }

  const scrollable = rows.length > MAX_VISIBLE_ROWS;
  const maxHeight = scrollable ? `${ROW_HEIGHT_PX * MAX_VISIBLE_ROWS + ROW_GAP_PX * (MAX_VISIBLE_ROWS - 1)}px` : undefined;

  return (
    <div className="queue-panel" role="list" aria-label="Queued messages">
      <div className="queue-panel-list" style={maxHeight !== undefined ? { maxHeight, overflowY: "auto" } : undefined}>
        {rows.map((row) => (
          <QueueRow
            key={row.id}
            row={row}
            lockedByOther={editingByOther(row)}
            reviewRequired={reviewRequired(row)}
            isLocalEditing={editingRowId === row.id}
            onSendNow={() => void onSendNow(row)}
            onEdit={() => void onEdit(row)}
            onRemove={() => void onRemove(row)}
            onDrop={(toIndex) => void onDrop(row.id, toIndex)}
          />
        ))}
      </div>
    </div>
  );
}

interface QueueRowProps {
  readonly row: QueuedMessage;
  readonly lockedByOther: boolean;
  readonly reviewRequired: boolean;
  readonly isLocalEditing: boolean;
  readonly onSendNow: () => void;
  readonly onEdit: () => void;
  readonly onRemove: () => void;
  readonly onDrop: (toIndex: number) => void;
}

function QueueRow(props: QueueRowProps) {
  const { row, lockedByOther, reviewRequired, isLocalEditing, onSendNow, onEdit, onRemove, onDrop } = props;
  const [dragOver, setDragOver] = useState<number | null>(null);
  const singleLineText = useMemo(() => collapseWhitespace(row.text), [row.text]);
  const hasAttachments = (row.attachments?.length ?? 0) > 0;
  const summary = hasAttachments && row.text.trim().length === 0 ? "See the attached image(s)." : singleLineText;

  const draggable = !lockedByOther && !isLocalEditing;
  return (
    <div
      className={`queue-row ${lockedByOther ? "queue-row-locked" : ""} ${reviewRequired ? "queue-row-review" : ""} ${isLocalEditing ? "queue-row-editing" : ""} ${dragOver === 0 ? "queue-row-drop-top" : ""}`}
      role="listitem"
      draggable={draggable}
      onDragStart={(event) => {
        if (!draggable) {
          event.preventDefault();
          return;
        }
        event.dataTransfer.setData("application/x-roboco-queue-id", row.id);
        event.dataTransfer.effectAllowed = "move";
      }}
      onDragOver={(event) => {
        if (!draggable) {
          return;
        }
        event.preventDefault();
        const rect = event.currentTarget.getBoundingClientRect();
        const half = rect.top + rect.height / 2;
        setDragOver(event.clientY < half ? 0 : 1);
      }}
      onDragLeave={() => setDragOver(null)}
      onDrop={(event) => {
        event.preventDefault();
        const sourceId = event.dataTransfer.getData("application/x-roboco-queue-id");
        const target = dragOver ?? 0;
        setDragOver(null);
        if (sourceId.length === 0 || sourceId === row.id) {
          return;
        }
        const allRows = event.currentTarget.parentElement?.querySelectorAll("[data-queue-id]") ?? null;
        const siblings = allRows !== null ? Array.from(allRows) : [];
        const myIndex = siblings.findIndex((node) => (node as HTMLElement).dataset["queueId"] === row.id);
        if (myIndex < 0) {
          return;
        }
        const toIndex = target === 0 ? myIndex : myIndex + 1;
        onDrop(toIndex);
      }}
      data-queue-id={row.id}
    >
      <div className="queue-row-text" title={row.text}>
        {summary}
      </div>
      <div className="queue-row-meta">
        {/*
          `hold_for_turn_end` is engine-side only — it gates auto-drain and the
          desktop's row never shows it. It stays on the data model, unrendered.
        */}
        {lockedByOther && <span className="queue-row-chip queue-row-chip-locked">Editing</span>}
        {reviewRequired && <span className="queue-row-chip queue-row-chip-review">Review</span>}
        {isLocalEditing && <span className="queue-row-chip queue-row-chip-editing">Editing here</span>}
      </div>
      <div className="queue-row-actions">
        <button
          type="button"
          className="btn btn-ghost queue-row-action"
          onClick={onSendNow}
          disabled={lockedByOther || reviewRequired}
          title="Send now (interrupt)"
        >
          Send now
        </button>
        <button
          type="button"
          className="btn btn-ghost queue-row-action"
          onClick={onEdit}
          disabled={lockedByOther}
          title="Edit (acquires an edit lease)"
        >
          Edit
        </button>
        <button
          type="button"
          className="btn btn-ghost queue-row-action queue-row-remove"
          onClick={onRemove}
          disabled={lockedByOther}
          title="Remove from queue"
          aria-label="Remove from queue"
        >
          ×
        </button>
      </div>
    </div>
  );
}

/** Whitespace-collapsed single line (proto view::single_line). */
function collapseWhitespace(text: string): string {
  return text
    .split(/\s+/)
    .filter((part) => part.length > 0)
    .join(" ");
}

function formatTime(epochMs: number): string {
  const date = new Date(epochMs);
  const hours = date.getHours().toString().padStart(2, "0");
  const minutes = date.getMinutes().toString().padStart(2, "0");
  return `${hours}:${minutes}`;
}
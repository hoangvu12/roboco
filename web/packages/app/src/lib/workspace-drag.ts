/**
 * The internal workspace-path drag — the web shape of the desktop's
 * `WorkspacePathDrag` (files/mod.rs): tree and search rows write a JSON
 * `{path, isDirectory}` payload under a private MIME type, and every
 * consumer (the tree's own mutation drop targets, the conversation
 * column's mention drop) reads it back. Extracted here so the composer's
 * drop wiring and the tree share ONE payload definition.
 */

/** The private MIME type carrying `{path, isDirectory}` between surfaces. */
export const WORKSPACE_DRAG_MIME = "application/x-roboco-workspace-path";

/** The workspace-path drag's payload (the desktop's `WorkspacePathDrag`
 *  JSON). Our own rows write it in `beginRowDrag`. */
export interface WorkspaceDragPayload {
  readonly path: string;
  readonly isDirectory: boolean;
}

/** The drag-event surface the payload helpers read — satisfied by both the
 *  DOM `DragEvent` and React's synthetic `DragEvent<T>`. */
export interface DragEventLike {
  readonly dataTransfer: DataTransfer | null;
}

/**
 * The drop-side read: the internal payload, or null when the drag carries
 * none (an OS file drag, a sidebar row drag) or a malformed one. Strict on
 * shape — a path must be a string and the directory flag a boolean.
 */
export function workspaceDragPayload(event: DragEventLike): WorkspaceDragPayload | null {
  const raw = event.dataTransfer?.getData(WORKSPACE_DRAG_MIME) ?? "";
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

/** Whether a drag-over carries the internal payload (the drop-target
 *  activation check: cancel the drag-over only for our own drags). */
export function workspaceDragActive(event: DragEventLike): boolean {
  return event.dataTransfer?.types.includes(WORKSPACE_DRAG_MIME) ?? false;
}

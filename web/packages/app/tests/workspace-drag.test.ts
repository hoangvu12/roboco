import { describe, expect, it } from "vitest";
import {
  WORKSPACE_DRAG_MIME,
  workspaceDragActive,
  workspaceDragPayload,
  type DragEventLike,
} from "../src/lib/workspace-drag";

/**
 * The internal workspace-path drag payload — the desktop's
 * `WorkspacePathDrag` JSON under a private MIME type, shared by the tree
 * rows (the writers) and the conversation column / side-chat panes (the
 * mention-drop readers).
 */

function dataTransfer(types: readonly string[], data: Record<string, string> = {}): DataTransfer {
  return {
    types: [...types],
    getData: (mime: string) => data[mime] ?? "",
  } as unknown as DataTransfer;
}

function eventOf(types: readonly string[], data: Record<string, string> = {}): DragEventLike {
  return { dataTransfer: dataTransfer(types, data) };
}

describe("workspaceDragPayload", () => {
  it("reads the tree rows' JSON payload", () => {
    const event = eventOf([WORKSPACE_DRAG_MIME, "text/plain"], {
      [WORKSPACE_DRAG_MIME]: JSON.stringify({ path: "src/lib.rs", isDirectory: false }),
    });
    expect(workspaceDragPayload(event)).toEqual({ path: "src/lib.rs", isDirectory: false });
    expect(workspaceDragActive(event)).toBe(true);
  });

  it("returns null for foreign drags, absent payloads, and malformed JSON", () => {
    expect(workspaceDragPayload({ dataTransfer: null })).toBeNull();
    expect(workspaceDragPayload(eventOf(["Files"]))).toBeNull();
    expect(workspaceDragActive(eventOf(["Files"]))).toBe(false);
    expect(
      workspaceDragPayload(eventOf([WORKSPACE_DRAG_MIME], { [WORKSPACE_DRAG_MIME]: "not json" })),
    ).toBeNull();
    expect(
      workspaceDragPayload(
        eventOf([WORKSPACE_DRAG_MIME], {
          [WORKSPACE_DRAG_MIME]: JSON.stringify({ path: 3, isDirectory: "yes" }),
        }),
      ),
    ).toBeNull();
  });
});

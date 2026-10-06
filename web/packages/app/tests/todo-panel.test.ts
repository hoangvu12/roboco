import { describe, expect, it } from "vitest";
import type { SessionMessageEntry, TodoItem } from "@roboco/proto";
import {
  type WireTodoStatus,
  effectiveTodoStatus,
  latestTodo,
  todoFocusWindow,
  todoHeadline,
  todoRows,
  todoSignature,
  todoSummary,
} from "../src/lib/todo-panel-logic";

/**
 * Ports of `crates/ui/src/todo_panel.rs`'s pure-half tests: which list is
 * current, what the header reports, which rows a long list folds to.
 */

const item = (text: string, done: boolean, status?: WireTodoStatus): TodoItem => ({
  text,
  done,
  ...(status === undefined ? {} : { status }),
});

function todoPart(items: readonly TodoItem[]): SessionMessageEntry["parts"][number] {
  return {
    kind: "tool",
    id: "t",
    call: { kind: "todo", items: items as TodoItem[] },
    isError: false,
    resolved: true,
  } as SessionMessageEntry["parts"][number];
}

function entry(role: "assistant" | "user", parts: SessionMessageEntry["parts"]): SessionMessageEntry {
  return {
    id: "m",
    role,
    parts,
    createdAt: 0,
    deviceId: "d",
  } as SessionMessageEntry;
}

describe("effectiveTodoStatus (todo_panel.rs)", () => {
  it("done wins, then the explicit status, else pending", () => {
    expect(effectiveTodoStatus(item("a", true))).toBe("completed");
    expect(effectiveTodoStatus(item("b", false))).toBe("pending");
    expect(effectiveTodoStatus(item("c", false, "inProgress"))).toBe("inProgress");
    // A stale status cannot resurrect a completed item.
    expect(effectiveTodoStatus(item("d", true, "inProgress"))).toBe("completed");
  });
});

describe("latestTodo (todo_panel.rs)", () => {
  it("is the last write across entries, assistant only, non-empty", () => {
    const first = entry("assistant", [todoPart([item("a", false), item("b", false)])]);
    const second = entry("assistant", [
      todoPart([item("a", true), item("b", false, "inProgress")]),
      { kind: "text", id: "t", text: "working" } as SessionMessageEntry["parts"][number],
    ]);
    const list = latestTodo([first, second]);
    expect(list).not.toBe(null);
    expect(list!.map((i) => i.text)).toEqual(["a", "b"]);
    // An empty list clears the panel; user rows never carry one.
    expect(latestTodo([entry("assistant", [todoPart([])])])).toBe(null);
    expect(latestTodo([entry("user", [todoPart([item("a", true)])])])).toBe(null);
  });
});

describe("todoSummary and folds (todo_panel.rs)", () => {
  it("the summary reports done, active and next; the headline prefers active", () => {
    const items = [
      item("done", true),
      item("active", false, "inProgress"),
      item("pending", false),
    ];
    const summary = todoSummary(items);
    expect(summary).toEqual({ total: 3, done: 1, active: 1, next: 1 });
    expect(todoHeadline(summary)).toBe(1);
    expect(todoSummary([item("a", true), item("b", false)])).toEqual({
      total: 2,
      done: 1,
      active: null,
      next: 1,
    });
  });

  it("short lists never fold; long lists center the current item", () => {
    const short = [item("a", false), item("b", true), item("c", false), item("d", false), item("e", false), item("f", false)];
    expect(todoFocusWindow(short)).toEqual([0, 6]);
    const long = Array.from({ length: 12 }, (_, ix) =>
      ix < 4 ? item(`i${ix}`, true) : item(`i${ix}`, false, ix === 4 ? "inProgress" : undefined),
    );
    expect(todoFocusWindow(long)).toEqual([3, 6]);
    // A finished list shows its last three.
    const finished = Array.from({ length: 12 }, (_, ix) => item(`i${ix}`, true));
    expect(todoFocusWindow(finished)).toEqual([9, 12]);
  });

  it("rows place fold toggles at the edges they stand for", () => {
    const items = Array.from({ length: 12 }, (_, ix) => item(`i${ix}`, ix < 4));
    const collapsed = todoRows(items, false, false);
    // The first unfinished item (4) centers the window: 3..6 — three
    // visible items, three folded off each end.
    expect(collapsed[0]).toEqual({ row: "fold", side: "earlier", count: 3, open: false });
    expect(collapsed.filter((r) => r.row === "item")).toHaveLength(3);
    expect(collapsed[collapsed.length - 1]).toEqual({ row: "fold", side: "later", count: 6, open: false });
    // Opening the earlier fold reveals its three items; the later six stay
    // folded behind their own toggle.
    const opened = todoRows(items, true, false);
    expect(opened.filter((r) => r.row === "item")).toHaveLength(3 + 3);
    expect(opened[0]).toEqual({ row: "fold", side: "earlier", count: 3, open: true });
    // Both open: the whole list, in order, and each toggle still present
    // so it can be closed again (rows() keeps the fold rows).
    const openedAll = todoRows(items, true, true);
    expect(openedAll.filter((r) => r.row === "item")).toHaveLength(12);
    expect(openedAll[0]).toEqual({ row: "fold", side: "earlier", count: 3, open: true });
    expect(openedAll[openedAll.length - 1]).toEqual({ row: "fold", side: "later", count: 6, open: true });
  });

  it("a dismissal signature covers text and effective status", () => {
    const a = [item("read", false, "inProgress"), item("fix", false)];
    expect(todoSignature(a)).not.toBe(todoSignature([item("read", true), item("fix", false)]));
    expect(todoSignature(a)).toBe(
      todoSignature([item("read", false, "inProgress"), item("fix", false, "pending")]),
    );
  });
});

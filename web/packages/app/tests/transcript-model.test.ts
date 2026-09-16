import { describe, expect, it } from "vitest";
import type { MessagePart, SessionMessageEntry, ToolCall, TranscriptFrame } from "@roboco/proto";
import { parseMarkdown } from "../src/lib/markdown";
import {
  applyTranscriptFrame,
  TranscriptDesync,
  assistantCopyText,
  callBlock,
  diffRows,
  formatTimestamp,
  rowsForEntry,
  singleLine,
  toolChipContent,
  toolGroupSummary,
  toolGroupTitle,
  topGapFor,
  userMessageNeedsCollapse,
  type TranscriptRow,
} from "../src/lib/transcript";

const parse = (_key: string, text: string, live: boolean) => parseMarkdown(text, live);

function entry(id: string, parts: MessagePart[], fields: Partial<SessionMessageEntry> = {}): SessionMessageEntry {
  return { id, role: "assistant", parts, createdAt: 1758000000000, deviceId: "dev", status: null, ...fields };
}

function textPart(id: string, text: string): MessagePart {
  return { kind: "text", id, text };
}

function toolPart(id: string, call: ToolCall, fields: Partial<Extract<MessagePart, { kind: "tool" }>> = {}): MessagePart {
  return { kind: "tool", id, call, isError: false, resolved: true, ...fields };
}

function exec(command: string): ToolCall {
  return { kind: "exec", command };
}

// ---------------------------------------------------------------------------
// Delta application (port of doc/src/transcript_delta.rs apply tests)
// ---------------------------------------------------------------------------

describe("applyTranscriptFrame", () => {
  it("reset replaces the transcript", () => {
    const a = entry("a", [textPart("t0", "hello")]);
    expect(applyTranscriptFrame([], { reset: [a] })).toEqual([a]);
  });

  it("a streaming tick appends text without re-sending the entry", () => {
    const a = entry("a", [textPart("t0", "prompt")]);
    const b0 = entry("b", [textPart("t0", "streaming…")]);
    const frame: TranscriptFrame = { upsert: [], append: [{ entry: "b", part: "t0", text: " more", len: 15 }], remove: [], count: 2 };
    const next = applyTranscriptFrame([a, b0], frame);
    const part = next[1]!.parts[0];
    if (part === undefined) {
      throw new Error("expected part");
    }
    expect(part.kind).toBe("text");
    if (part.kind !== "text") {
      throw new Error("expected text part");
    }
    expect(part.text).toBe("streaming… more");
    // The untouched entry keeps its identity (React memoization holds).
    expect(next[0]).toBe(a);
  });

  it("upserts anchor after the given entry and replace in place", () => {
    const a = entry("a", [textPart("t0", "1")]);
    const b = entry("b", [textPart("t0", "2")]);
    const c = entry("c", [textPart("t0", "3")]);
    // Mid-list insert (a Loro merge landing b between a and c).
    const inserted = applyTranscriptFrame([a, c], { upsert: [{ after: "a", entry: b }], append: [], remove: [], count: 3 });
    expect(inserted.map((e) => e.id)).toEqual(["a", "b", "c"]);
    // Replace: same id re-upserts at the same position.
    const b2 = entry("b", [textPart("t0", "2!")]);
    const replaced = applyTranscriptFrame(inserted, { upsert: [{ after: "a", entry: b2 }], append: [], remove: [], count: 3 });
    expect(replaced.map((e) => e.id)).toEqual(["a", "b", "c"]);
    expect(replaced[1]).toBe(b2);
  });

  it("removes entries and validates the count tripwire", () => {
    const a = entry("a", [textPart("t0", "1")]);
    const b = entry("b", [textPart("t0", "2")]);
    const c = entry("c", [textPart("t0", "3")]);
    const next = applyTranscriptFrame([a, b, c], { upsert: [], append: [], remove: ["b"], count: 2 });
    expect(next.map((e) => e.id)).toEqual(["a", "c"]);
    expect(() => applyTranscriptFrame([a], { upsert: [], append: [], remove: [], count: 5 })).toThrow(TranscriptDesync);
  });

  it("append length mismatch is a desync (resubscribe tripwire)", () => {
    const a = entry("a", [textPart("t0", "hello")]);
    const frame: TranscriptFrame = { upsert: [], append: [{ entry: "a", part: "t0", text: "x", len: 99 }], remove: [], count: 1 };
    expect(() => applyTranscriptFrame([a], frame)).toThrow(TranscriptDesync);
  });

  it("a missing anchor is a desync", () => {
    const x = entry("x", [textPart("t0", "1")]);
    const frame: TranscriptFrame = { upsert: [{ after: "missing", entry: x }], append: [], remove: [], count: 2 };
    expect(() => applyTranscriptFrame([], frame)).toThrow(TranscriptDesync);
  });

  it("a reset preserves identities of unchanged entries (cache-swap case)", () => {
    const a = entry("a", [textPart("t0", "hello")]);
    const b = entry("b", [textPart("t0", "world")]);
    const current = [a, b];
    const again = applyTranscriptFrame(current, { reset: [entry("a", [textPart("t0", "hello")]), b] });
    expect(again[0]).toBe(a);
    expect(again[1]).toBe(b);
    expect(applyTranscriptFrame(current, { reset: [a, b] })).toBe(current);
  });
});

// ---------------------------------------------------------------------------
// Row model (port of transcript.rs rows_for_entry)
// ---------------------------------------------------------------------------

describe("rowsForEntry", () => {
  it("a user entry is one bubble row with the timestamp strip", () => {
    const user = entry("u1", [textPart("t0", "hello there")], { role: "user" });
    const rows = rowsForEntry(user, { parse });
    expect(rows.length).toBe(1);
    expect(rows[0]!.id).toBe("u1");
    expect(rows[0]!.turnStart).toBe(true);
    expect(rows[0]!.timestamp).toBe(user.createdAt);
    expect(rows[0]!.copyText).toBe("hello there");
    expect(rows[0]!.rowKind).toMatchObject({ kind: "user", text: "hello there", pending: false });
  });

  it("assistant text splits one row per top-level markdown block", () => {
    const e = entry("a1", [textPart("p0", "# Title\n\nsome text\n\n```ts\nconst x = 1;\n```")]);
    const rows = rowsForEntry(e, { parse });
    expect(rows.map((row) => row.id)).toEqual(["a1#p0.0", "a1#p0.1", "a1#p0.2"]);
    expect(rows.map((row) => row.rowKind.kind)).toEqual(["markdown", "markdown", "markdown"]);
    // Only the first row of the entry opens the turn.
    expect(rows.map((row) => row.turnStart)).toEqual([true, false, false]);
    // The settled entry's last row carries the timestamp strip and copy text.
    expect(rows[2]!.timestamp).toBe(e.createdAt);
    expect(rows[2]!.copyText).toContain("some text");
    expect(rows[0]!.timestamp).toBeNull();
  });

  it("streaming text rows are liveMarkdown with identical ids", () => {
    const live = entry("a1", [textPart("p0", "one\n\ntwo")], { status: "streaming" });
    const rows = rowsForEntry(live, { parse });
    expect(rows.map((row) => row.id)).toEqual(["a1#p0.0", "a1#p0.1"]);
    expect(rows.every((row) => row.rowKind.kind === "liveMarkdown")).toBe(true);
    // No timestamp hover mid-stream.
    expect(rows.every((row) => row.timestamp === null)).toBe(true);
  });

  it("consecutive tools fold into one group; text flushes it", () => {
    const e = entry("a1", [
      textPart("t0", "looking"),
      toolPart("c0", exec("ls")),
      toolPart("c1", exec("pwd")),
      textPart("t1", "done"),
    ]);
    const rows = rowsForEntry(e, { parse });
    const group = rows.find((row) => row.rowKind.kind === "toolGroup");
    expect(group).toBeDefined();
    expect(group!.id).toBe("a1#g0");
    if (group!.rowKind.kind === "toolGroup") {
      expect(group!.rowKind.tools.length).toBe(2);
      expect(group!.rowKind.autoOpen).toBe(false);
    }
    // Text around the tools splits into separate markdown rows.
    expect(rows[0]!.rowKind.kind).toBe("markdown");
    expect(rows[rows.length - 1]!.rowKind.kind).toBe("markdown");
  });

  it("a streaming entry's tail tool group auto-opens", () => {
    const e = entry("a1", [toolPart("c0", exec("ls"), { resolved: false })], { status: "streaming" });
    const rows = rowsForEntry(e, { parse });
    const group = rows[0]!;
    expect(group.rowKind.kind === "toolGroup" && group.rowKind.autoOpen).toBe(true);
  });

  it("agent spawn chips never share a fold with ordinary tools", () => {
    const spawn: ToolCall = { kind: "unknown", name: "Agent: scan repo" };
    const e = entry("a1", [toolPart("c0", exec("ls")), toolPart("c1", spawn), toolPart("c2", exec("pwd"))]);
    const rows = rowsForEntry(e, { parse });
    // The genus flips twice: [exec] · [spawn] · [exec].
    const groups = rows.filter((row) => row.rowKind.kind === "toolGroup");
    expect(groups.length).toBe(3);
    const agentGroup = groups[1]!;
    if (agentGroup.rowKind.kind === "toolGroup") {
      expect(agentGroup.rowKind.tools.length).toBe(1);
      expect(agentGroup.rowKind.tools[0]!.call).toEqual(spawn);
    }
  });

  it("reasoning rides the tool group as a thought chip", () => {
    const e = entry("a1", [
      toolPart("c0", exec("ls")),
      { kind: "reasoning", id: "r0", text: "thinking about it" },
    ]);
    const rows = rowsForEntry(e, { parse });
    const group = rows[0]!;
    expect(group.rowKind.kind).toBe("toolGroup");
    if (group.rowKind.kind === "toolGroup") {
      expect(group.rowKind.tools.length).toBe(2);
      expect(group.rowKind.tools[1]!.isThought).toBe(true);
      expect(group.rowKind.tools[1]!.resolved).toBe(true);
    }
  });

  it("a live tail thought is unresolved and keeps the tail when truncated", () => {
    const long = Array.from({ length: 40 }, (_, ix) => `line ${ix} of thinking`).join("\n\n");
    const e = entry("a1", [{ kind: "reasoning", id: "r0", text: long }], { status: "streaming" });
    const rows = rowsForEntry(e, { parse });
    const group = rows[0]!;
    if (group.rowKind.kind !== "toolGroup") {
      throw new Error("expected a tool group");
    }
    const thought = group.rowKind.tools[0]!;
    expect(thought.isThought).toBe(true);
    expect(thought.resolved).toBe(false);
    expect(thought.detail).not.toBeNull();
    if (thought.detail !== null && thought.detail.kind === "thought") {
      expect(thought.detail.lines.length).toBeLessThanOrEqual(24);
      expect(thought.detail.truncatedBy).toBeGreaterThan(0);
      // Live thoughts keep the TAIL (the fresh thinking is the signal).
      const flat = thought.detail.lines.map((line) => line.map((run) => run.text).join("")).join("\n");
      expect(flat).toContain("line 39");
    }
  });

  it("input and error parts render as chips", () => {
    const e = entry("a1", [
      { kind: "input", id: "i0", requestId: "req", questions: [{ id: "q", header: "Pick one", question: "?", options: ["a"], multiSelect: false }], resolved: false },
      { kind: "error", id: "e0", message: "boom\nstack" },
    ]);
    const rows = rowsForEntry(e, { parse });
    expect(rows[0]!.rowKind).toEqual({ kind: "inputChip", header: "Pick one", resolved: false });
    expect(rows[1]!.rowKind).toEqual({ kind: "errorChip", message: "boom stack" });
  });

  it("empty text parts produce no rows", () => {
    const e = entry("a1", [textPart("t0", "   ")]);
    expect(rowsForEntry(e, { parse })).toEqual([]);
  });
});

describe("topGapFor / diffRows", () => {
  const row = (id: string, kind: TranscriptRow["rowKind"]["kind"], turnStart = false): TranscriptRow => {
    const rowKind: TranscriptRow["rowKind"] =
      kind === "markdown" || kind === "liveMarkdown"
        ? { kind, tree: { blocks: [] }, blockIx: 0 }
        : kind === "toolGroup"
          ? { kind, tools: [], autoOpen: false }
          : kind === "user"
            ? { kind, text: "", pending: false }
            : kind === "inputChip"
              ? { kind, header: "", resolved: false }
              : { kind, message: "" };
    return {
      id,
      version: 0,
      turnStart,
      rowKind,
      entryId: id,
      timestamp: null,
      copyText: null,
    };
  };

  it("turn starts get the large gap; same-part blocks the block gap", () => {
    expect(topGapFor(null, row("a", "errorChip", true))).toBe(16);
    const mdA = row("e#p.0", "errorChip");
    const mdB = row("e#p.1", "errorChip");
    expect(topGapFor(mdA, mdB)).toBe(12);
    expect(topGapFor(row("x", "toolGroup"), row("y", "errorChip"))).toBe(8);
    expect(topGapFor(row("x", "errorChip"), row("y", "toolGroup"))).toBe(12);
  });

  it("diffRows finds the minimal splice", () => {
    const a = row("a", "errorChip");
    const b = row("b", "errorChip");
    const c = row("c", "errorChip");
    expect(diffRows([a, b, c], [a, b, c])).toBeNull();
    expect(diffRows([a, b, c], [a, c])).toEqual([1, 1, 0]);
    expect(diffRows([a], [a, b])).toEqual([1, 0, 1]);
    const b2 = { ...b, version: 9 };
    expect(diffRows([a, b, c], [a, b2, c])).toEqual([1, 1, 1]);
  });
});

// ---------------------------------------------------------------------------
// Tool chips (port of view.rs tool_chip_content / tool_group_summary)
// ---------------------------------------------------------------------------

describe("toolChipContent", () => {
  it("names every kind like the desktop", () => {
    expect(toolChipContent(exec("ls -la"))).toEqual({ label: "Run", detail: "ls -la" });
    expect(toolChipContent({ kind: "readFile", path: "a/b.ts" })).toEqual({ label: "Read", detail: "a/b.ts" });
    expect(toolChipContent({ kind: "writeFile", path: "a/b.ts" })).toEqual({ label: "Write", detail: "a/b.ts" });
    expect(toolChipContent({ kind: "editFile", path: "a/b.ts" })).toEqual({ label: "Edit", detail: "a/b.ts" });
    expect(toolChipContent({ kind: "applyPatch", path: null })).toEqual({ label: "Patch", detail: "workspace" });
    expect(toolChipContent({ kind: "search", pattern: "foo", path: "src" })).toEqual({ label: "Search", detail: "foo in src" });
    expect(toolChipContent({ kind: "search", pattern: "foo", path: null })).toEqual({ label: "Search", detail: "foo" });
    expect(toolChipContent({ kind: "glob", pattern: "**/*.ts" })).toEqual({ label: "Glob", detail: "**/*.ts" });
    expect(toolChipContent({ kind: "webFetch", url: "https://x.dev", prompt: null })).toEqual({ label: "Fetch", detail: "https://x.dev" });
    expect(toolChipContent({ kind: "webSearch", query: "roboco" })).toEqual({ label: "Web", detail: "roboco" });
    expect(toolChipContent({ kind: "todo", items: [{ text: "a", done: true }, { text: "b", done: false }] })).toEqual({ label: "Todo", detail: "1/2 done" });
    expect(toolChipContent({ kind: "mcp", server: "fs", tool: "read", input: null })).toEqual({ label: "MCP", detail: "fs · read" });
    expect(toolChipContent({ kind: "unknown", name: "Agent: scan repo" })).toEqual({ label: "Agent", detail: "scan repo" });
    expect(toolChipContent({ kind: "unknown", name: "Agent" })).toEqual({ label: "Agent", detail: "" });
    expect(toolChipContent({ kind: "unknown", name: "custom_tool" })).toEqual({ label: "Tool", detail: "custom_tool" });
  });

  it("collapses the detail to one line", () => {
    expect(toolChipContent(exec("ls\n-la   --all")).detail).toBe("ls -la --all");
    expect(singleLine(" a\n\tb  c ")).toBe("a b c");
  });
});

describe("toolGroupSummary", () => {
  const pair = (call: ToolCall, isError = false) => ({ call, isError });

  it("summarizes like the desktop", () => {
    expect(toolGroupSummary([pair(exec("ls")), pair(exec("pwd")), pair(exec("cd"))])).toBe("Ran 3 commands");
    expect(
      toolGroupSummary([
        pair(exec("ls")),
        pair({ kind: "editFile", path: "a" }),
        pair({ kind: "writeFile", path: "b" }),
        pair({ kind: "editFile", path: "a" }),
      ]),
    ).toBe("Ran 1 command · edited 2 files");
    expect(toolGroupSummary([pair({ kind: "readFile", path: "x" })])).toBe("Read 1 file");
    expect(toolGroupSummary([pair({ kind: "glob", pattern: "*" }), pair({ kind: "webSearch", query: "q" })])).toBe("Searched 2 times");
    expect(toolGroupSummary([pair({ kind: "webFetch", url: "u", prompt: null })])).toBe("Fetched 1 page");
    expect(toolGroupSummary([pair({ kind: "todo", items: [] })])).toBe("Updated todos");
    expect(toolGroupSummary([pair(exec("ls"), true)])).toBe("Ran 1 command · 1 failed");
    expect(toolGroupSummary([])).toBe("0 tools");
  });

  it("names thought chips on the collapsed line", () => {
    const thought = { isThought: true } as never;
    const tool = { isThought: false, call: exec("ls"), isError: false } as never;
    expect(toolGroupTitle([thought])).toBe("Thought process");
    expect(toolGroupTitle([thought, thought])).toBe("Thought 2 times");
    expect(toolGroupTitle([thought, tool])).toBe("Thought · Ran 1 command");
    expect(toolGroupTitle([thought, thought, tool])).toBe("Thought 2 times · Ran 1 command");
    expect(toolGroupTitle([tool])).toBe("Ran 1 command");
  });
});

describe("callBlock", () => {
  it("carries the full invocation, wrapped at 80 columns", () => {
    const block = callBlock(exec("run this"));
    expect(block).not.toBeNull();
    expect(block!.kind === "output" && block!.lines).toEqual(["run this"]);
    const long = callBlock(exec("x".repeat(200)));
    expect(long!.kind === "output" && long!.lines.every((line) => [...line].length <= 80)).toBe(true);
    expect(long!.kind === "output" && long!.lines.join("")).toBe("x".repeat(200));
  });

  it("renders todo items one per line and mcp input as JSON", () => {
    const todo = callBlock({ kind: "todo", items: [{ text: "a", done: true }, { text: "b", done: false }] });
    expect(todo!.kind === "output" && todo!.lines).toEqual(["[x] a", "[ ] b"]);
    const mcp = callBlock({ kind: "mcp", server: "fs", tool: "read", input: { path: "/x" } });
    expect(mcp!.kind === "output" && mcp!.lines[0]).toBe("fs · read");
    expect(mcp!.kind === "output" && mcp!.lines.join("\n")).toContain('"path": "/x"');
  });
});

// ---------------------------------------------------------------------------
// Entry helpers
// ---------------------------------------------------------------------------

describe("entry helpers", () => {
  it("assistantCopyText joins text parts and excludes tools", () => {
    const e = entry("a", [textPart("t0", "one"), toolPart("c0", exec("ls")), textPart("t1", "two")]);
    expect(assistantCopyText(e)).toBe("one\n\ntwo");
    expect(assistantCopyText(entry("b", [toolPart("c0", exec("ls"))]))).toBeNull();
  });

  it("userMessageNeedsCollapse mirrors the desktop proxy", () => {
    expect(userMessageNeedsCollapse("short")).toBe(false);
    expect(userMessageNeedsCollapse(Array.from({ length: 6 }, (_, i) => `line ${i}`).join("\n"))).toBe(true);
    expect(userMessageNeedsCollapse("x".repeat(401))).toBe(true);
  });

  it("formatTimestamp matches the desktop shape", () => {
    // Local-time rendering: assert the shape, not a zone-specific hour.
    expect(formatTimestamp(Date.parse("2026-07-01T15:45:00"))).toMatch(/^[A-Z][a-z]{2} 1, \d{1,2}:45 [AP]M$/);
    expect(formatTimestamp(Number.NaN)).toBe("");
  });
});

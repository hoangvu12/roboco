import { describe, expect, it } from "vitest";
import type { MessagePart, SessionMessageEntry, ToolCall, TranscriptFrame } from "@roboco/proto";
import { parseMarkdown, type InlineRun } from "../src/lib/markdown";
import { bodyHeight } from "../src/lib/diff";
import {
  CHIPS_TOP_PAD,
  CHIP_HEIGHT,
  CHIP_GAP,
  TranscriptDesync,
  applyTranscriptFrame,
  assistantCopyText,
  blobDetail,
  callBlock,
  chipsHeight,
  detailHeight,
  diffRows,
  fileBadgeName,
  formatKb,
  formatTimestamp,
  rowsForEntry,
  singleLine,
  stripSpawnPrefix,
  subagentTabTitle,
  thoughtLines,
  toolChipContent,
  toolDetail,
  toolGroupSummary,
  toolGroupTitle,
  topGapFor,
  userMessageNeedsCollapse,
  visibleRowWindow,
  type TranscriptRow,
} from "../src/lib/transcript";
import {
  ACTIVITY_BEND_RADIUS,
  ACTIVITY_BRANCH_END_X,
  ACTIVITY_TRUNK_X,
  activityBranchPoints,
  railPath,
  toolConnectorContinuation,
  toolConnectorParts,
  toolTitleShimmerAmount,
  toolTitleShimmerPhase,
} from "../src/lib/tool-motion";
import {
  jumpVisibility,
  shouldAnchorLiveStream,
  shouldRestick,
} from "../src/lib/stick-spring";

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
            ? { kind, text: "", mentions: [], badges: [], pending: false, attachments: [] }
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
    // split_sibling_gaps_match_live_internal_spacing: the markdown clause
    // requires BOTH rows to be markdown kinds — a chip after a block keeps
    // the small step even with a shared part prefix.
    const mdA = row("e#p.0", "markdown");
    const mdB = row("e#p.1", "markdown");
    expect(topGapFor(mdA, mdB)).toBe(12);
    expect(topGapFor(row("e#p.0", "errorChip"), row("e#p.1", "errorChip"))).toBe(8);
    expect(topGapFor(mdA, row("e#p.1", "errorChip"))).toBe(8);
    expect(topGapFor(row("x", "toolGroup"), row("y", "errorChip"))).toBe(12);
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
  it("tool_chip_labels_per_kind", () => {
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

  it("single_line_collapses_all_whitespace_runs", () => {
    expect(singleLine(" a\n\tb  c ")).toBe("a b c");
    expect(singleLine("plain")).toBe("plain");
    expect(singleLine("")).toBe("");
    expect(singleLine("\n\n")).toBe("");
  });

  it("multiline_command_flattens_to_one_chip_line", () => {
    // The user's breaker: a multi-line script in a Run chip. The detail must
    // come out as ONE sanitized line — the chip's fixed card then truncates
    // it with an ellipsis.
    const { label, detail } = toolChipContent(exec('set -e\nfixture_in_original=0\n\tgrep -c  "x"'));
    expect(label).toBe("Run");
    expect(detail).toBe('set -e fixture_in_original=0 grep -c "x"');
    expect(detail.includes("\n")).toBe(false);
    // The chip row height is a constant, independent of content shape.
    expect(chipsHeight(1)).toBe(CHIPS_TOP_PAD + CHIP_HEIGHT);
    // Every detail kind is sanitized (MCP inputs / queries are model text).
    expect(toolChipContent({ kind: "webSearch", query: "line one\nline two" }).detail).toBe("line one line two");
  });

  it("file_action_badges_show_only_the_file_name", () => {
    expect(fileBadgeName("/Users/me/project/src/main.rs")).toBe("main.rs");
    expect(fileBadgeName("crates/ui/src/transcript.rs")).toBe("transcript.rs");
    expect(fileBadgeName("C:\\project\\src\\main.rs")).toBe("main.rs");
    expect(fileBadgeName("src/components/")).toBe("components");
    expect(fileBadgeName("main.rs")).toBe("main.rs");
    expect(fileBadgeName("")).toBe("");
  });
});

describe("toolGroupSummary", () => {
  const pair = (call: ToolCall, isError = false) => ({ call, isError });

  it("tool_group_summaries", () => {
    expect(toolGroupSummary([pair(exec("ls")), pair(exec("pwd")), pair(exec("cd"))])).toBe("Ran 3 commands");
    expect(
      toolGroupSummary([
        pair(exec("ls")),
        pair({ kind: "editFile", path: "a" }),
        pair({ kind: "writeFile", path: "b" }),
        pair({ kind: "editFile", path: "a" }),
      ]),
    ).toBe("Ran 1 command · edited 2 files");
    // Distinct-path dedupe: editing one file twice counts once.
    expect(toolGroupSummary([pair({ kind: "editFile", path: "a" }), pair({ kind: "editFile", path: "a" })])).toBe("Edited 1 file");
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
  it("call_block_carries_the_full_invocation", () => {
    // Multi-line command: verbatim lines, not the flattened chip line.
    const block = callBlock(exec("set -e\ncargo test"));
    expect(block!.kind === "output" && block!.truncatedBy).toBe(0);
    expect(block!.kind === "output" && block!.lines).toEqual(["set -e", "cargo test"]);
    const long = callBlock(exec("x".repeat(200)));
    expect(long!.kind === "output" && long!.lines.every((line) => [...line].length <= 80)).toBe(true);
    expect(long!.kind === "output" && long!.lines.join("")).toBe("x".repeat(200));
    // A long single-line command soft-wraps instead of ellipsizing: 200
    // chars at 80 columns is 3 chunks.
    expect(long!.kind === "output" && long!.lines.length).toBe(3);
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
// Tool groups (ticket 19 — transcript.rs tests :10662-11200)
// ---------------------------------------------------------------------------

describe("tool details (ticket 19)", () => {
  it("tool_diff_builds_real_hunks_with_context_and_numbers", () => {
    const oldLines = Array.from({ length: 20 }, (_, i) => `line ${i + 1}`);
    const newLines = [...oldLines];
    newLines[9] = "LINE 10";
    const diff = { path: "/w/a.rs", oldText: oldLines.join("\n") + "\n", newText: newLines.join("\n") + "\n" };
    const detail = toolDetail(null, diff, null);
    expect(detail).not.toBeNull();
    expect(detail!.kind).toBe("diff");
    const file = detail!.kind === "diff" ? detail!.file : null;
    expect(file).not.toBeNull();
    // One hunk: the change plus 3 context lines each side, real numbers.
    expect(file!.hunks.length).toBe(1);
    const hunk = file!.hunks[0]!;
    expect(hunk.header).toBe("@@ -7,7 +7,7 @@");
    expect(hunk.lines.length).toBe(8); // 6 context + 1 del + 1 add
    const del = hunk.lines.find((line) => line.kind === "del");
    expect(del).toBeDefined();
    expect(del!.oldNo).toBe(10);
    expect(del!.newNo).toBeNull();
    expect(del!.text).toBe("line 10");
    const add = hunk.lines.find((line) => line.kind === "add");
    expect(add).toBeDefined();
    expect(add!.newNo).toBe(10);
    expect(add!.oldNo).toBeNull();
    expect(add!.text).toBe("LINE 10");
    expect(file!.additions).toBe(1);
    expect(file!.deletions).toBe(1);
    // New files carry Added status (and no old numbers).
    const created = toolDetail(null, { path: "/w/new.txt", oldText: null, newText: "only\n" }, null);
    expect(created!.kind === "diff" && created!.file.status).toBe("added");
    expect(created!.kind === "diff" && created!.file.hunks[0]!.lines.every((line) => line.oldNo === null)).toBe(true);
    // Output: verbatim lines (indentation intact), counted-tail cap.
    const output = Array.from({ length: 40 }, (_, i) => `    indented ${i}`).join("\n");
    const outDetail = toolDetail(output, null, null);
    expect(outDetail!.kind === "output" && outDetail!.lines.length).toBe(24);
    expect(outDetail!.kind === "output" && outDetail!.truncatedBy).toBe(16);
    expect(outDetail!.kind === "output" && outDetail!.lines[0]).toBe("    indented 0");
    // Diff wins over stats wins over output.
    const stats = [{ path: "a", additions: 1, deletions: 2 }];
    expect(toolDetail("out", diff, stats)!.kind).toBe("diff");
    expect(toolDetail("out", null, stats)!.kind).toBe("stats");
    // Nothing → no detail.
    expect(toolDetail(null, null, null)).toBeNull();
    expect(toolDetail("\n\n", null, null)).toBeNull();
  });

  it("blob_detail parses diff JSON and renders uncapped output", () => {
    const diffJson = JSON.stringify({ path: "/w/a.rs", oldText: null, newText: "a\nb\n" });
    const fromBlob = blobDetail(diffJson, true);
    expect(fromBlob!.kind).toBe("diff");
    // Output blobs cap at the defensive FULL_OUTPUT_MAX_LINES ceiling.
    const longOutput = Array.from({ length: 500 }, (_, i) => `line ${i}`).join("\n");
    const out = blobDetail(longOutput, false);
    expect(out!.kind === "output" && out!.lines.length).toBe(400);
    expect(out!.kind === "output" && out!.truncatedBy).toBe(100);
    expect(blobDetail("  \n\n ", false)).toBeNull();
    expect(blobDetail("{not json", true)).toBeNull();
  });

  it("chips_height_is_analytic", () => {
    expect(chipsHeight(0)).toBe(0);
    expect(chipsHeight(1)).toBe(CHIPS_TOP_PAD + CHIP_HEIGHT);
    expect(chipsHeight(3)).toBe(CHIPS_TOP_PAD + 3 * CHIP_HEIGHT + 2 * CHIP_GAP);
  });

  it("detail_height is analytic per kind", () => {
    const output = toolDetail("a\nb", null, null)!;
    // 2 lines + no tail row → 2·18 + py(6)×2, plus the separator.
    expect(detailHeight(output)).toBe(1 + 2 * 18 + 12);
    const truncated = toolDetail(Array.from({ length: 30 }, () => "x").join("\n"), null, null)!;
    expect(detailHeight(truncated)).toBe(1 + (24 + 1) * 18 + 12);
    const stats = toolDetail(null, null, [{ path: "a", additions: 1, deletions: 0 }])!;
    expect(detailHeight(stats)).toBe(1 + 1 * 18 + 12);
    const diff = toolDetail(null, { path: "a.rs", oldText: null, newText: "one\ntwo\n" }, null)!;
    expect(detailHeight(diff)).toBe(1 + bodyHeight(diff.kind === "diff" ? diff.file : null!));
  });

  it("formatKb never shows decimals", () => {
    expect(formatKb(512)).toBe("512 B");
    expect(formatKb(0)).toBe("0 B");
    expect(formatKb(1023)).toBe("1023 B");
    expect(formatKb(1024)).toBe("1 KB");
    expect(formatKb(12288)).toBe("12 KB");
    expect(formatKb(12500)).toBe("13 KB");
  });
});

describe("subagent tab titles (transcript.rs :7188)", () => {
  it("subagent_tab_title fallbacks and caps", () => {
    // The tab is the BARE task — the "Agent:" genus is stripped.
    expect(subagentTabTitle({ kind: "unknown", name: "Agent: scan repo", input: null })).toBe("scan repo");
    // A bare "Task" digs the description out of the call input.
    expect(
      subagentTabTitle({
        kind: "unknown",
        name: "Task",
        input: { description: "Agent: audit the auth flow", prompt: "very long instructions…" },
      }),
    ).toBe("audit the auth flow");
    // Word boundaries only — a name that merely STARTS with the genus keeps
    // itself.
    expect(subagentTabTitle({ kind: "unknown", name: "Taskmaster", input: null })).toBe("Taskmaster");
    // A bare "agent" strips to "" and falls through to the generic label.
    expect(subagentTabTitle({ kind: "unknown", name: "agent", input: null })).toBe("Subagent");
    // Absurd lengths cap with an ellipsis.
    const long = subagentTabTitle({ kind: "unknown", name: "x".repeat(120), input: null });
    expect([...long].length).toBe(41);
    expect(long.endsWith("…")).toBe(true);
    // Multiline names keep only their first line.
    expect(subagentTabTitle({ kind: "unknown", name: "Agent: one\ntwo", input: null })).toBe("one");
    // Non-spawn-shaped calls stay generic.
    expect(subagentTabTitle(exec("ls"))).toBe("Subagent");
  });

  it("strip_spawn_prefix only strips real word boundaries", () => {
    expect(stripSpawnPrefix("Agent: scan")).toBe("scan");
    expect(stripSpawnPrefix("task  cleanup")).toBe("cleanup");
    expect(stripSpawnPrefix("Taskmaster")).toBe("Taskmaster");
    expect(stripSpawnPrefix("Agent")).toBe("");
    expect(stripSpawnPrefix("plain")).toBe("plain");
  });
});

// ---------------------------------------------------------------------------
// Thought details (transcript.rs :8721-8818)
// ---------------------------------------------------------------------------

describe("thought details (ticket 19)", () => {
  const thoughtOf = (text: string): InlineRun[][] => thoughtLines(parseMarkdown(text, false));
  const lineString = (line: InlineRun[]): string => line.map((run) => run.text).join("");
  const lineChars = (line: InlineRun[]): number => [...lineString(line)].length;

  it("codex_summary_paragraphs_render_as_separate_styled_lines", () => {
    const lines = thoughtOf("**Implementing file badges**\n\n**Preparing fixture screenshots**");
    expect(lines.map(lineString)).toEqual(["Implementing file badges", "", "Preparing fixture screenshots"]);
    for (const ix of [0, 2]) {
      expect(lines[ix]!.every((run) => run.text.trim().length === 0 || run.style.bold === true)).toBe(true);
    }
  });

  it("thought_wrap_is_word_aware_and_bounded", () => {
    const lines = thoughtOf("one two three");
    expect(lines.length).toBe(1);
    expect(lineString(lines[0]!)).toBe("one two three");
    const long = "word ".repeat(200);
    const wrapped = thoughtOf(long);
    expect(wrapped.every((line) => lineChars(line) <= 96)).toBe(true);
    expect(wrapped.length).toBeGreaterThan(5);
    const pathological = "x".repeat(300);
    expect(thoughtOf(pathological).every((line) => lineChars(line) <= 96)).toBe(true);
    // A word glued across style boundaries wraps as ONE unit — no line may
    // split inside `**bold**tail`.
    const glued = `${"word ".repeat(30)} **bold**tail`;
    const joined = thoughtOf(glued).map(lineString);
    expect(joined.some((line) => line.endsWith("boldtail"))).toBe(true);
  });

  it("thought_markdown_styles_instead_of_literal_markers", () => {
    // The exact user report: `**bold**` markers showed as glyphs.
    const lines = thoughtOf("**Planning rollback** then *checking* `parse` [docs](https://d)");
    expect(lines.length).toBe(1);
    const flat = lineString(lines[0]!);
    expect(flat.includes("*")).toBe(false);
    expect(flat.includes("`")).toBe(false);
    expect(flat.includes("[")).toBe(false);
    const line = lines[0]!;
    expect(line.some((run) => run.style.bold === true && run.text.includes("Planning rollback"))).toBe(true);
    expect(line.some((run) => run.style.italic === true && run.text.includes("checking"))).toBe(true);
    expect(line.some((run) => run.style.code === true && run.text.includes("parse"))).toBe(true);
    expect(line.some((run) => run.style.link !== null && run.text.includes("docs"))).toBe(true);
  });

  it("thought_blocks_flatten_structurally", () => {
    const lines = thoughtOf("# Head\n\npara\n\n- one\n- two\n\n```rust\nlet x = 1;\n```");
    const flat = lines.map(lineString);
    // Heading renders bold, same size (one 18px row).
    expect(lines[0]!.some((run) => run.style.bold === true && run.text.includes("Head"))).toBe(true);
    // Blank separator rows between top-level blocks; tight list inside.
    expect(flat[1]).toBe("");
    expect(flat[2]).toBe("para");
    expect(flat[4]).toBe("• one");
    expect(flat[5]).toBe("• two");
    // Code lines verbatim, styled as code (mono at render).
    const last = lines[lines.length - 1]!;
    expect(last.some((run) => run.style.code === true && run.text === "let x = 1;")).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Tool motion + rail geometry (transcript.rs :7960-8000, :11127-11200)
// ---------------------------------------------------------------------------

describe("tool motion (ticket 19)", () => {
  it("connector_intersection_is_tessellated_only_once", () => {
    // The web contract: trunk + branch ride ONE <path> with fill-rule
    // nonzero — two contours in a single `d`, never two strokes (stroke
    // tessellation would double-blend the fork).
    const d = railPath({
      bendRowHeight: 32,
      canvasHeight: 32,
      hasPredecessor: false,
      continues: true,
      connectorReveal: 1,
      continuationReveal: 1,
    });
    expect(d).not.toBeNull();
    const starts = d!.split(" ").filter((token) => token === "M").length;
    expect(starts).toBe(2);
    expect(d!.includes("Z")).toBe(true);
    // A row that has not started reveals nothing.
    expect(
      railPath({ bendRowHeight: 32, canvasHeight: 32, hasPredecessor: true, continues: true, connectorReveal: 0, continuationReveal: 0 }),
    ).toBeNull();
  });

  it("tool_branch_reveal_tracks_distance_through_the_bend", () => {
    const length = (points: readonly { x: number; y: number }[]): number => {
      let total = 0;
      for (let ix = 1; ix < points.length; ix += 1) {
        total += Math.hypot(points[ix]!.x - points[ix - 1]!.x, points[ix]!.y - points[ix - 1]!.y);
      }
      return total;
    };
    const full = activityBranchPoints(1);
    expect(activityBranchPoints(0)).toEqual([{ x: 0, y: 0 }]);
    const end = full[full.length - 1]!;
    expect(end.x).toBeCloseTo(ACTIVITY_BRANCH_END_X - ACTIVITY_TRUNK_X, 4);
    expect(end.y).toBeCloseTo(ACTIVITY_BEND_RADIUS, 4);
    for (const progress of [0.1, 0.25, 0.5, 0.75, 0.9]) {
      const partial = activityBranchPoints(progress);
      expect(length(partial) / length(full)).toBeCloseTo(progress, 4);
      for (let ix = 1; ix < partial.length; ix += 1) {
        expect(partial[ix]!.x).toBeGreaterThanOrEqual(partial[ix - 1]!.x);
        expect(partial[ix]!.y).toBeGreaterThanOrEqual(partial[ix - 1]!.y);
      }
    }
  });

  it("tool_connector_parts split one arrival into phases", () => {
    expect(toolConnectorParts(0, false)).toEqual({ incoming: 0, branch: 0 });
    expect(toolConnectorParts(0.44, true)).toEqual({ incoming: 0, branch: 0 });
    expect(toolConnectorContinuation(0)).toBe(0);
    expect(toolConnectorContinuation(0.3)).toBeGreaterThan(0);
    expect(toolConnectorContinuation(0.45)).toBe(1);
    expect(toolConnectorContinuation(null)).toBe(0);
    const { incoming, branch } = toolConnectorParts(0.6, true);
    expect(incoming).toBeGreaterThan(0);
    expect(incoming).toBeLessThan(1);
    expect(branch).toBe(0);
    expect(toolConnectorParts(1, true)).toEqual({ incoming: 1, branch: 1 });
  });

  it("tool_title_shimmer_crosses_the_title_without_a_loop_seam", () => {
    expect(toolTitleShimmerAmount(0.5, 0.5)).toBe(1);
    expect(toolTitleShimmerAmount(0, 0.5)).toBe(0);
    expect(toolTitleShimmerAmount(1, 0.5)).toBe(0);
    expect(toolTitleShimmerAmount(0.3, 0.5)).toBeGreaterThan(0.4);
    expect(toolTitleShimmerAmount(0.7, 0.5)).toBeGreaterThan(0.4);
    for (const x of [0, 0.25, 0.5, 0.75, 1]) {
      expect(toolTitleShimmerAmount(x, 0)).toBe(toolTitleShimmerAmount(x, 1));
    }
    expect(toolTitleShimmerPhase(0)).toBe(0);
    expect(toolTitleShimmerPhase(3400)).toBe(0);
    expect(toolTitleShimmerPhase(1700)).toBeCloseTo(0.5, 6);
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

// ---------------------------------------------------------------------------
// Virtualizer window
describe("visibleRowWindow", () => {
  // Five 100px rows: positions 0,100,200,300,400; total 500.
  const positions = [0, 100, 200, 300, 400];
  const heights = [100, 100, 100, 100, 100];
  const OVERDRAW = 320;

  it("mounts every row while the whole list fits the window", () => {
    // top 0, height 600: window [-320, 920] covers everything.
    expect(visibleRowWindow(positions, heights, 0, 600, OVERDRAW)).toEqual({ first: 0, last: 4 });
  });

  it("skips rows entirely above the overdraw window", () => {
    // top 500, height 200: window [180, 1020]. Row 0's bottom (100) is above
    // 180, so first is 1 — the top spacer replaces row 0. The old dead
    // `ix < first` condition mounted ALL rows from 0 on every render.
    expect(visibleRowWindow(positions, heights, 500, 200, OVERDRAW)).toEqual({ first: 1, last: 4 });
  });

  it("treats a bottom exactly at the window start as crossing", () => {
    // top 520: windowStart 200 — row 1's bottom is exactly 200, which counts
    // as crossing (>=), so first is 1, not 2.
    expect(visibleRowWindow(positions, heights, 520, 200, OVERDRAW)).toEqual({ first: 1, last: 4 });
  });

  it("skips rows entirely below the window plus overdraw", () => {
    // top 0, height 10: window [-320, 330]. Row 4's top (400) is past 330,
    // so last is 3.
    expect(visibleRowWindow(positions, heights, 0, 10, OVERDRAW)).toEqual({ first: 0, last: 3 });
  });

  it("returns an empty window for an empty list", () => {
    expect(visibleRowWindow([], [], 0, 600, OVERDRAW)).toEqual({ first: 0, last: -1 });
  });

  it("pins first to the last row when a stale top sits past the content", () => {
    // A view.top the scroller has not clamped yet (content shrank): every
    // row is above the window. first pins to the last row so the spacer
    // math stays inside positions[]; the next scroll event corrects.
    expect(visibleRowWindow(positions, heights, 2000, 600, OVERDRAW)).toEqual({ first: 4, last: 4 });
  });

  it("treats the first row whose bottom crosses the window start as first", () => {
    // top 450, height 200: window [130, 970]. Row 0's bottom (100) is above
    // 130, row 1's (200) is not — first is 1.
    expect(visibleRowWindow(positions, heights, 450, 200, OVERDRAW)).toEqual({ first: 1, last: 4 });
  });

  it("uses per-row heights, not a uniform estimate", () => {
    // One tall row then short rows: positions 0,300,340,380,420.
    const uneven = [0, 300, 340, 380, 420];
    const unevenHeights = [300, 40, 40, 40, 40];
    // top 650, height 200: window [330, 1170]. The tall row's bottom (300)
    // is above 330, so first is 1 — with uniform 100px rows the same window
    // would start at 3.
    expect(visibleRowWindow(uneven, unevenHeights, 650, 200, OVERDRAW)).toEqual({ first: 1, last: 4 });
    const uniform = [0, 100, 200, 300, 400];
    expect(visibleRowWindow(uniform, [100, 100, 100, 100, 100], 650, 200, OVERDRAW)).toEqual({ first: 3, last: 4 });
  });
});

// ---------------------------------------------------------------------------
// Ticket 18 � transcript rows (ports of the desktop test names in �6)
// ---------------------------------------------------------------------------

import {
  FLAVOUR_WORDS,
  PendingQueuedTurns,
  SavedViewportCache,
  captureSavedViewport,
  captureViewportAnchor,
  flavourSeed,
  flavourWord,
  formatElapsed,
  ownTurnObservesPrompt,
  ownTurnReleasedForRestore,
  parseForRow,
  resolveViewportAnchor,
  selectionScrollStep,
  sendingBridge,
  sentMentionDisplay,
  userResizeCurve,
  userResizeDurationMs,
  type OwnTurnAnchor,
  type SavedViewport,
  type TranscriptRow as Row18,
} from "../src/lib/transcript";

describe("working trailer helpers (transcript.rs:1886-1952)", () => {
  it("flavour_words_rotate_every_seven_seconds", () => {
    const seed = flavourSeed("chat-1");
    // The index advances one step per FLAVOUR_ROTATE_SECS of elapsed time.
    expect(flavourWord(seed, 0)).toBe(flavourWord(seed, 6));
    expect(flavourWord(seed, 7)).not.toBe(flavourWord(seed, 0));
    expect(flavourWord(seed, 7)).toBe(FLAVOUR_WORDS[(((seed + 1) % 21) + 21) % 21]);
    // The full 21-word cycle repeats after 147s.
    expect(flavourWord(seed, 147)).toBe(flavourWord(seed, 0));
    // Negative elapsed clamps to zero, never wraps backwards.
    expect(flavourWord(seed, -30)).toBe(flavourWord(seed, 0));
  });

  it("format_elapsed matches the desktop shape", () => {
    expect(formatElapsed(0)).toBe("0s");
    expect(formatElapsed(42)).toBe("42s");
    expect(formatElapsed(92)).toBe("1m 32s");
    expect(formatElapsed(-5)).toBe("0s");
  });

  it("sending_bridge_holds_until_the_turn_outdates_the_send", () => {
    // No send in flight: never sending.
    expect(sendingBridge(null, null)).toBe(false);
    expect(sendingBridge(null, 1000)).toBe(false);
    // A send with no turn row: the round-trip window � sending.
    expect(sendingBridge(1000, null)).toBe(true);
    // The turn predates the send (the row still carries the PREVIOUS turn):
    // sending until the new turn actually begins.
    expect(sendingBridge(1000, 999)).toBe(true);
    expect(sendingBridge(1000, 1000)).toBe(true);
    expect(sendingBridge(1000, 1001)).toBe(false);
  });
});

describe("selectionScrollStep (transcript.rs:142)", () => {
  const bounds = { top: 0, bottom: 600 };

  it("selection_scroll_ramps_at_viewport_edges", () => {
    // Dead centre: no scroll.
    expect(selectionScrollStep(bounds, { x: 10, y: 300 })).toBe(0);
    // Top edge: negative (toward the document top), ramping as t�.
    expect(selectionScrollStep(bounds, { x: 10, y: 0 })).toBe(-24);
    expect(selectionScrollStep(bounds, { x: 10, y: 18 })).toBe(-24 * 0.25);
    expect(selectionScrollStep(bounds, { x: 10, y: 36 })).toBe(0);
    // Bottom edge: positive.
    expect(selectionScrollStep(bounds, { x: 10, y: 600 })).toBe(24);
    expect(selectionScrollStep(bounds, { x: 10, y: 582 })).toBe(24 * 0.25);
    // The edge band is capped at a third of the viewport.
    expect(selectionScrollStep({ top: 0, bottom: 60 }, { x: 0, y: 0 })).toBe(-24);
    expect(selectionScrollStep({ top: 0, bottom: 0 }, { x: 0, y: 0 })).toBe(0);
  });
});

describe("user fold resize spec (transcript.rs:1178-1192)", () => {
  it("user_resize_duration_scales_with_distance_and_stays_bounded", () => {
    expect(userResizeDurationMs(0)).toBe(220);
    expect(userResizeDurationMs(100)).toBe(252);
    expect(userResizeDurationMs(-50)).toBe(220);
    expect(userResizeDurationMs(2000)).toBe(850);
    // Short folds ease-out; large folds ease-in-out.
    expect(userResizeCurve(400)).toBe("easeOut");
    expect(userResizeCurve(501)).toBe("easeInOut");
  });

  it("long_prompts_collapse_and_short_ones_do_not", () => {
    expect(userMessageNeedsCollapse("short")).toBe(false);
    expect(userMessageNeedsCollapse("five\nlines\nexactly\nhere\nnow")).toBe(false);
    expect(userMessageNeedsCollapse("six\nlines\nright\nhere\nnow\nok")).toBe(true);
    expect(userMessageNeedsCollapse("x".repeat(401))).toBe(true);
  });
});

describe("jump / restick / live-anchor gates (transcript.rs:74, :199, :3113)", () => {
  it("jump_button_stays_available_when_scrolling_down_until_near_bottom", () => {
    // Hidden: not yet past the 320px offering threshold.
    expect(jumpVisibility(false, 320)).toBe(false);
    expect(jumpVisibility(false, 500)).toBe(true);
    // Hysteresis: once shown, it stays until AT_BOTTOM_PX (2).
    expect(jumpVisibility(true, 320)).toBe(true);
    expect(jumpVisibility(true, 3)).toBe(true);
    expect(jumpVisibility(true, 2)).toBe(false);
    expect(jumpVisibility(true, 1)).toBe(false);
  });

  it("restick_is_direction_aware", () => {
    // Returning toward the bottom inside the 70px band re-sticks.
    expect(shouldRestick(60, 100)).toBe(true);
    expect(shouldRestick(0, 5)).toBe(true);
    // A small wheel-up notch NEAR the bottom stays inside the band but moves
    // AWAY from it: re-sticking would make the pin unbreakable.
    expect(shouldRestick(20, 10)).toBe(false);
    expect(shouldRestick(2, 0)).toBe(false);
    // Past the band, direction is irrelevant.
    expect(shouldRestick(71, 100)).toBe(false);
  });

  it("only_a_stream_at_the_bottom_gets_a_hard_end_anchor", () => {
    expect(shouldAnchorLiveStream(true, 2, true)).toBe(true);
    // Unpinned, or gliding back toward the bottom: the normal spring.
    expect(shouldAnchorLiveStream(false, 2, true)).toBe(false);
    expect(shouldAnchorLiveStream(true, 3, true)).toBe(false);
    // Not streaming: no hard anchor.
    expect(shouldAnchorLiveStream(true, 2, false)).toBe(false);
  });
});

describe("diffRows (transcript.rs:1651)", () => {
  const mk = (id: string, version = 0): Row18 => ({
    id,
    version,
    turnStart: false,
    rowKind: { kind: "errorChip", message: id },
    entryId: "e",
    timestamp: null,
    copyText: null,
  });

  it("diff_rows_appends_and_middle_edits", () => {
    expect(diffRows([mk("a")], [mk("a")])).toBeNull();
    expect(diffRows([mk("a")], [mk("a"), mk("b")])).toEqual([1, 0, 1]);
    expect(diffRows([mk("a"), mk("b"), mk("c")], [mk("a"), mk("c")])).toEqual([1, 1, 0]);
    // An in-place content change: same ids, new version ? splice of one.
    expect(diffRows([mk("a"), mk("b"), mk("c")], [mk("a"), mk("b", 7), mk("c")])).toEqual([1, 1, 1]);
    // Same id/version but different timestamp content (the settle bit):
    const settled = { ...mk("b"), version: mk("b").version ^ 0x40000000 };
    expect(diffRows([mk("a"), mk("b"), mk("c")], [mk("a"), settled, mk("c")])).toEqual([1, 1, 1]);
  });

  it("diff_handles_live_to_split_growth", () => {
    // A streaming tail splits into block rows while the prefix stays put:
    // live rows e#p.0 / e#p.0.0, then one more block appears.
    const live = [mk("e#p.0"), mk("e#p.0.0")];
    const grown = [mk("e#p.0"), mk("e#p.0.0"), mk("e#p.0.1")];
    expect(diffRows(live, grown)).toEqual([2, 0, 1]);
    // The live?complete flip changes every version but no id: an in-place
    // remeasure, not a splice.
    const flipped = grown.map((row) => ({ ...row, version: row.version + 1 }));
    expect(diffRows(grown, flipped)).toEqual([0, 3, 3]);
  });

  it("timestamp_strip_lands_on_the_last_settled_row", () => {
    const source = "# T\n\nbody";
    const streaming = entry("a1", [textPart("p0", source)], { status: "streaming" });
    const live = rowsForEntry(streaming, { parse });
    expect(live.every((row) => row.timestamp === null)).toBe(true);
    expect(live.every((row) => row.copyText === null)).toBe(true);
    const settled = rowsForEntry(entry("a1", [textPart("p0", source)]), { parse });
    // Only the LAST settled row carries the timestamp + copy affordance.
    expect(settled.map((row) => row.timestamp === null)).toEqual([true, false]);
    expect(settled[settled.length - 1]!.copyText).toBe(source);
    // Identical settles are diff-stable (the cache case).
    const settledAgain = rowsForEntry(entry("a1", [textPart("p0", source)]), { parse });
    expect(diffRows(settled, settledAgain)).toBeNull();
    // The live→settled flip keeps every id but changes the diff keys (the
    // streaming bit, the settle bit): an in-place remeasure, never a splice.
    const flipped = diffRows(live, settled);
    expect(flipped).toEqual([0, live.length, settled.length]);
  });
});

describe("sentMentionDisplay (composer.rs:1316, projected chips)", () => {
  const link = (path: string, label = path.split("/").pop()!) =>
    `[${label}](roboco-file:${encodeURIComponent(path).replaceAll("%2F", "/")})`;

  it("user_rows_project_file_mentions_into_chips", () => {
    // Plain prompts take the zero-work path.
    expect(sentMentionDisplay("no mentions here")).toBeNull();
    const raw = `look at ${link("src/lib/foo.ts")} please`;
    const projected = sentMentionDisplay(raw);
    expect(projected).not.toBeNull();
    // The chip: non-breaking side bearings around `@basename`.
    expect(projected!.display).toBe("look at \u00a0@foo.ts\u00a0 please");
    expect(projected!.mentions).toHaveLength(1);
    expect(projected!.mentions[0]).toMatchObject({ path: "src/lib/foo.ts", isDir: false });
    // The chip's range covers exactly the projected label run.
    const span = projected!.mentions[0]!;
    expect(projected!.display.slice(span.start, span.end)).toBe("\u00a0@foo.ts\u00a0");
    // The row model carries the projection.
    const user = entry("u1", [textPart("t0", raw)], { role: "user" });
    const [row] = rowsForEntry(user, { parse });
    expect(row!.rowKind).toMatchObject({ kind: "user" });
    if (row!.rowKind.kind === "user") {
      expect(row!.rowKind.text).toBe(projected!.display);
      expect(row!.rowKind.mentions).toHaveLength(1);
    }
  });

  it("duplicate basenames take the shortest unique suffix; dirs keep their slash", () => {
    const raw = `${link("a/util.ts")} and ${link("b/util.ts")}`;
    const projected = sentMentionDisplay(raw);
    expect(projected!.display).toContain("\u00a0@a/util.ts\u00a0");
    expect(projected!.display).toContain("\u00a0@b/util.ts\u00a0");
    const dir = sentMentionDisplay(link("src/lib/", "lib"));
    expect(dir!.mentions[0]).toMatchObject({ path: "src/lib/", isDir: true });
    // Non-mention links and unsafe paths are left untouched.
    expect(sentMentionDisplay("see [docs](https://x.dev)")).toBeNull();
    expect(sentMentionDisplay("[evil](roboco-file:..%2F..%2Fetc)")).toBeNull();
  });

  it("message_copy_keeps_authored_text_and_excludes_tool_traces", () => {
    const e = entry("a", [textPart("t0", "one"), toolPart("c0", exec("ls")), textPart("t1", "  two  ")]);
    // Authored bytes preserved, blank parts dropped, tools excluded.
    expect(assistantCopyText(e)).toBe("one\n\n  two  ");
    expect(assistantCopyText(entry("b", [toolPart("c0", exec("ls"))]))).toBeNull();
    // A mention-carrying prompt copies its PROJECTED text.
    const user = entry("u1", [textPart("t0", `hi ${link("src/a.ts")}`)], { role: "user" });
    const [row] = rowsForEntry(user, { parse });
    expect(row!.copyText).toBe(row!.rowKind.kind === "user" ? row!.rowKind.text : null);
  });
});

describe("parseForRow (transcript.rs:1557-1650)", () => {
  it("streaming parses mended, settling hands off the exact live tree", () => {
    const state = new Map();
    // A fresh live part: one full parse, nothing stable yet.
    const first = parseForRow(state, "k", "one\n\ntwo", true);
    expect(first.outcome.kind).toBe("incremental");
    if (first.outcome.kind === "incremental") {
      expect(first.outcome.parsedBytes).toBe("one\n\ntwo".length);
      expect(first.outcome.stablePrefixBlocks).toBe(0);
    }
    // A prefix extension: the reparse tail is the appended bytes, and the
    // leading paragraph block survives untouched.
    const second = parseForRow(state, "k", "one\n\ntwo three", true);
    expect(second.outcome.kind).toBe("incremental");
    if (second.outcome.kind === "incremental") {
      expect(second.outcome.parsedBytes).toBe(" three".length);
      expect(second.outcome.stablePrefixBlocks).toBe(1);
    }
    // Identical re-delivery keeps tree identity (no re-render).
    expect(parseForRow(state, "k", "one\n\ntwo three", true).tree).toBe(second.tree);
    // The live→complete handoff ADOPTS the live tree when sources match —
    // the split rows then share the tree the unsplit row painted.
    const settled = parseForRow(state, "k", "one\n\ntwo three", false);
    expect(settled.outcome.kind).toBe("handoff");
    expect(settled.tree).toBe(second.tree);
    // And the settled entry then serves from cache.
    expect(parseForRow(state, "k", "one\n\ntwo three", false).outcome.kind).toBe("cached");
    // A changed settled source re-parses in full, unmended.
    const fresh = parseForRow(state, "k", "plain *now*", false);
    expect(fresh.outcome.kind).toBe("full");
    // The streaming path mends hanging markers (display-only closers).
    const mended = parseForRow(new Map(), "m", "**bold", true);
    const runs = mended.tree.blocks[0]?.block;
    expect(runs !== undefined && runs.kind === "paragraph").toBe(true);
    if (runs !== undefined && runs.kind === "paragraph") {
      expect(runs.runs.some((run) => run.style.bold === true)).toBe(true);
    }
  });
});

describe("ViewportAnchor resolve (transcript.rs:2349-2395)", () => {
  const rows: Row18[] = [
    { id: "a#0", entryId: "a", turnStart: true, rowKind: { kind: "errorChip", message: "" }, version: 0, timestamp: null, copyText: null },
    { id: "a#1", entryId: "a", turnStart: false, rowKind: { kind: "errorChip", message: "" }, version: 0, timestamp: null, copyText: null },
    { id: "b#0", entryId: "b", turnStart: true, rowKind: { kind: "errorChip", message: "" }, version: 0, timestamp: null, copyText: null },
    { id: "c#0", entryId: "c", turnStart: true, rowKind: { kind: "errorChip", message: "" }, version: 0, timestamp: null, copyText: null },
  ];

  it("captures the first row crossing the viewport top, then resolves exactly", () => {
    const anchor = captureViewportAnchor(rows, 150, [0, 100, 200, 300], [100, 100, 100, 100]);
    expect(anchor).toMatchObject({ rowId: "a#1", fallbackIx: 1, offsetInRow: 50 });
    const resolved = resolveViewportAnchor(anchor!, rows, false);
    expect(resolved).toEqual({ itemIx: 1, offsetInItem: 50 });
  });

  it("falls back to the same entry's nearest row, then the clamped index", () => {
    // The anchored row disappeared (a streaming block reshaped).
    const anchor = { rowId: "a#1", entryId: "a", fallbackIx: 1, offsetInRow: 50 };
    const reshaped = rows.filter((row) => row.id !== "a#1");
    // While the replay is pending, fallbacks are disabled: no restore.
    expect(resolveViewportAnchor(anchor, reshaped, false)).toBeNull();
    const fallback = resolveViewportAnchor(anchor, reshaped, true);
    expect(fallback).toEqual({ itemIx: 0, offsetInItem: 0 });
    // Entry gone entirely: the clamped index.
    const gone = resolveViewportAnchor({ rowId: "x", entryId: "zz", fallbackIx: 9, offsetInRow: 4 }, rows, true);
    expect(gone).toEqual({ itemIx: 3, offsetInItem: 0 });
    expect(resolveViewportAnchor({ rowId: "x", entryId: "zz", fallbackIx: 0, offsetInRow: 0 }, [], true)).toBeNull();
  });

  it("saved viewports follow the tail when pinned, anchor otherwise", () => {
    const positions = [0, 100, 200, 300];
    const heights = [100, 100, 100, 100];
    expect(captureSavedViewport([], 0, positions, heights, true, 0, null)).toBeNull();
    const pinned = captureSavedViewport(rows, 0, positions, heights, true, 0, null);
    expect(pinned).toEqual({ kind: "followTail" });
    const own: OwnTurnAnchor = { chatId: "c1", messageId: "m1", held: true, positioned: true, seenPrompt: true };
    const escaped = captureSavedViewport(rows, 150, positions, heights, false, 700, own);
    expect(escaped).toMatchObject({ kind: "anchored", distanceFromBottom: 700, ownTurn: own });
    if (escaped !== null && escaped.kind === "anchored") {
      // Restoring releases the hold � the reservation, not the auto-follow.
      expect(ownTurnReleasedForRestore(escaped.ownTurn!)).toMatchObject({
        held: false,
        positioned: false,
        seenPrompt: true,
      });
    }
  });

  it("own_turn keeps the runway while the prompt was never seen", () => {
    const anchor: OwnTurnAnchor = { chatId: "c", messageId: "m", held: true, positioned: false, seenPrompt: false };
    expect(ownTurnObservesPrompt(anchor, false)).toBe(true);
    const seen: OwnTurnAnchor = { ...anchor, seenPrompt: true };
    expect(ownTurnObservesPrompt(seen, true)).toBe(true);
    // Once seen, a later disappearance is terminal.
    expect(ownTurnObservesPrompt(seen, false)).toBe(false);
  });
});

describe("PendingQueuedTurns (transcript.rs:2307)", () => {
  const rowOf = (entryId: string, turnStart: boolean): Row18 => ({
    id: entryId,
    version: 0,
    turnStart,
    rowKind: { kind: "errorChip", message: "" },
    entryId,
    timestamp: null,
    copyText: null,
  });

  it("registers inert and consumes the newest materialized row", () => {
    const turns = new PendingQueuedTurns();
    turns.register("c1", "m1");
    turns.register("c1", "m2");
    expect(turns.size).toBe(2);
    // Nothing materialized yet: inert — the visible turn is untouched.
    expect(turns.takeLatestMaterialized("c1", [rowOf("other", true)])).toBeNull();
    expect(turns.size).toBe(2);
    // Both land in one doc frame: the newest (the last registered) owns the
    // runway, matching consecutive immediate sends.
    const rows = [rowOf("m1", true), rowOf("m2", true)];
    expect(turns.takeLatestMaterialized("c1", rows)).toBe("m2");
    expect(turns.size).toBe(0);
    // Re-registering an existing id refreshes it to the back (newest).
    turns.register("c1", "m1");
    turns.register("c1", "m2");
    turns.register("c1", "m1");
    expect(turns.takeLatestMaterialized("c1", rows)).toBe("m1");
    // A non-turn-start row with the same entry id does not materialize.
    turns.register("c2", "q1");
    expect(turns.takeLatestMaterialized("c2", [rowOf("q1", false)])).toBeNull();
    expect(turns.size).toBe(1);
    // Another chat's rows never match.
    expect(turns.takeLatestMaterialized("c3", [rowOf("q1", true)])).toBeNull();
  });

  it("bounds itself to MAX_PENDING_QUEUED_TURNS", () => {
    const turns = new PendingQueuedTurns();
    for (let ix = 0; ix < 300; ix++) {
      turns.register("c", `m${ix}`);
    }
    expect(turns.size).toBe(256);
  });
});

describe("SavedViewportCache (transcript.rs:2401)", () => {
  it("is per-chat, LRU-bounded, and refreshes on save", () => {
    const cache = new SavedViewportCache();
    const tail: SavedViewport = { kind: "followTail" };
    for (let ix = 0; ix < 256; ix++) {
      cache.save(`chat-${ix}`, tail);
    }
    cache.save("chat-old", tail);
    expect(cache.get("chat-old")).toEqual(tail);
    // Saving enough to overflow evicts the least-recently-used — chat-0
    // goes while chat-old (saved most recently) stays.
    for (let ix = 0; ix < 255; ix++) {
      cache.save(`other-${ix}`, tail);
    }
    expect(cache.get("chat-0")).toBeUndefined();
    expect(cache.get("chat-old")).toEqual(tail);
    cache.clear();
    expect(cache.get("chat-old")).toBeUndefined();
  });
});

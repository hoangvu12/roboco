/**
 * The transcript's pure model, ported 1:1 from the desktop:
 * - row model (`crates/ui/src/transcript.rs` `rows_for_entry`, `RowKind`,
 *   `top_gap_for`, `diff_rows`) — block-granularity rows with stable ids, so
 *   the virtualizer's row identity is continuous across the live→settled flip;
 * - tool chips (`roboco_proto::view` `tool_chip_content`/`tool_group_summary`,
 *   `ToolCall::is_subagent_spawn` from `crates/proto/src/agent.rs`);
 * - delta application (`crates/doc/src/transcript_delta.rs`
 *   `apply_transcript_frame`), made immutable so React can memoize per entry.
 */

import type {
  MessagePart,
  SessionMessageEntry,
  ToolCall,
  ToolDiffStat,
  TranscriptFrame,
} from "@roboco/proto";
import { blockFlatText, parseMarkdown, type Block, type BlockTree, type InlineRun, type InlineStyle } from "./markdown";
import { parseUserMessageImages, type UserImageAttachment } from "./attachments";

// ---------------------------------------------------------------------------
// Shared view helpers (crates/proto/src/view.rs)
// ---------------------------------------------------------------------------

/** Collapse model-generated text onto ONE line for single-line surfaces. */
export function singleLine(text: string): string {
  return text.split(/\s+/).filter((piece) => piece.length > 0).join(" ");
}

function plural(n: number, one: string, many: string): string {
  return n === 1 ? `${n} ${one}` : `${n} ${many}`;
}

/** Per-kind chip label + one-line detail (view.rs `tool_chip_content`). */
export function toolChipContent(call: ToolCall): { label: string; detail: string } {
  const [label, detail] = toolChipContentRaw(call);
  return { label, detail: singleLine(detail) };
}

function toolChipContentRaw(call: ToolCall): [string, string] {
  switch (call.kind) {
    case "exec":
      return ["Run", call.command];
    case "readFile":
      return ["Read", call.path];
    case "writeFile":
      return ["Write", call.path];
    case "editFile":
      return ["Edit", call.path];
    case "applyPatch":
      return ["Patch", call.path ?? "workspace"];
    case "search":
      return ["Search", call.path !== null && call.path !== undefined ? `${call.pattern} in ${call.path}` : call.pattern];
    case "glob":
      return ["Glob", call.pattern];
    case "webFetch":
      return ["Fetch", call.url];
    case "webSearch":
      return ["Web", call.query];
    case "todo": {
      const done = call.items.filter((item) => item.done).length;
      return ["Todo", `${done}/${call.items.length} done`];
    }
    case "mcp":
      return ["MCP", `${call.server} · ${call.tool}`];
    case "unknown": {
      // Subagent spawns decode as Unknown named "Agent[: <description>]":
      // label them "Agent" with the description as the detail.
      if (call.name.startsWith("Agent: ")) {
        return ["Agent", call.name.slice("Agent: ".length)];
      }
      if (call.name === "Agent") {
        return ["Agent", ""];
      }
      return ["Tool", call.name];
    }
  }
}

/** The ToolGroup summary line — "Ran 3 commands · edited 2 files". */
export function toolGroupSummary(tools: readonly { call: ToolCall; isError: boolean }[]): string {
  let commands = 0;
  const edited: string[] = [];
  let reads = 0;
  let searches = 0;
  let fetches = 0;
  let todos = 0;
  let other = 0;
  let failed = 0;
  for (const { call, isError } of tools) {
    if (isError) {
      failed++;
    }
    switch (call.kind) {
      case "exec":
        commands++;
        break;
      case "writeFile":
      case "editFile":
        if (!edited.includes(call.path)) {
          edited.push(call.path);
        }
        break;
      case "applyPatch": {
        const path = call.path ?? "patch";
        if (!edited.includes(path)) {
          edited.push(path);
        }
        break;
      }
      case "readFile":
        reads++;
        break;
      case "search":
      case "glob":
      case "webSearch":
        searches++;
        break;
      case "webFetch":
        fetches++;
        break;
      case "todo":
        todos++;
        break;
      case "mcp":
      case "unknown":
        other++;
        break;
    }
  }
  const segments: string[] = [];
  if (commands > 0) {
    segments.push(`ran ${plural(commands, "command", "commands")}`);
  }
  if (edited.length > 0) {
    segments.push(`edited ${plural(edited.length, "file", "files")}`);
  }
  if (reads > 0) {
    segments.push(`read ${plural(reads, "file", "files")}`);
  }
  if (searches > 0) {
    segments.push(`searched ${plural(searches, "time", "times")}`);
  }
  if (fetches > 0) {
    segments.push(`fetched ${plural(fetches, "page", "pages")}`);
  }
  if (todos > 0) {
    segments.push("updated todos");
  }
  if (other > 0) {
    segments.push(`called ${plural(other, "tool", "tools")}`);
  }
  if (segments.length === 0) {
    segments.push(plural(tools.length, "tool", "tools"));
  }
  if (failed > 0) {
    segments.push(`${failed} failed`);
  }
  const summary = segments.join(" · ");
  // Capitalize the first segment only (roboco's style).
  return summary.length > 0 ? summary[0]!.toUpperCase() + summary.slice(1) : summary;
}

/**
 * The group header line (transcript.rs `tool_group_summary`): thought chips
 * are UI-synthesized, so the shared summary never sees them — name them on
 * the collapsed line instead ("Thought · Ran 2 commands").
 */
export function toolGroupTitle(tools: readonly ToolItem[]): string {
  const pairs = tools.filter((tool) => !tool.isThought);
  const thoughts = tools.length - pairs.length;
  const base = pairs.length === 0 ? "" : toolGroupSummary(pairs);
  if (thoughts === 0) {
    return base;
  }
  if (base.length === 0) {
    return thoughts === 1 ? "Thought process" : `Thought ${thoughts} times`;
  }
  return thoughts === 1 ? `Thought · ${base}` : `Thought ${thoughts} times · ${base}`;
}

// ---------------------------------------------------------------------------
// Subagent genus (crates/proto/src/agent.rs)
// ---------------------------------------------------------------------------
/**
 * A subagent SPAWN call — the `Agent[: <description>]` naming convention
 * every driver decodes its spawn tool into. The single genus gate for
 * subagent binding; see the Rust doc for why the call, never the ref, decides.
 */
export function isSubagentSpawn(call: ToolCall): boolean {
  const name = call.kind === "unknown" ? call.name : call.kind === "mcp" ? call.tool : null;
  return name !== null && (name === "Agent" || name.startsWith("Agent: "));
}

/** Spawn-input keys that carry a child model, in precedence order. */
const SUBAGENT_MODEL_KEYS = ["model", "model_id", "modelId", "subagent_model"];

/** The model a subagent spawn was given, when the spawn named one. */
export function subagentModel(call: ToolCall): string | null {
  if (!isSubagentSpawn(call)) {
    return null;
  }
  const input = call.kind === "unknown" || call.kind === "mcp" ? call.input : null;
  if (typeof input !== "object" || input === null) {
    return null;
  }
  for (const key of SUBAGENT_MODEL_KEYS) {
    const value = (input as Record<string, unknown>)[key];
    if (typeof value === "string" && value.trim().length > 0) {
      return value.trim();
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Tool detail payloads (transcript.rs tool_detail / call_block / thought_item)
// ---------------------------------------------------------------------------

/** Max verbatim output lines per chip before the counted tail row. */
export const OUTPUT_DETAIL_MAX_LINES = 24;
/** Columns at which an invocation line soft-wraps into continuation lines. */
export const CALL_WRAP_COLS = 80;
/** Column budget for soft-wrapping thought text into detail lines. */
export const THOUGHT_WRAP_COLS = 96;

/** A chip's expandable detail payload. */
export type ToolDetail =
  | { readonly kind: "output"; readonly lines: readonly string[]; readonly truncatedBy: number }
  | { readonly kind: "thought"; readonly lines: readonly (readonly InlineRun[])[]; readonly truncatedBy: number }
  | { readonly kind: "stats"; readonly stats: readonly ToolDiffStat[] };

/** One tool invocation (or a reasoning part riding the group) inside a row. */
export interface ToolItem {
  readonly call: ToolCall;
  readonly isError: boolean;
  readonly resolved: boolean;
  readonly detail: ToolDetail | null;
  /** The full-invocation block: complete command/pattern/URL/input JSON. */
  readonly invocation: ToolDetail | null;
  /** Sidecar key of the full output (`{chatId}/{partId}`). */
  readonly outputRef: string | null;
  /** Full-output size, for "Show full output (12 KB)". */
  readonly outputBytes: number | null;
  /** Sidecar key of the full diff JSON. */
  readonly diffRef: string | null;
  /** The spawned subagent's doc id — the chip IS the index. */
  readonly subagentRef: string | null;
  /** Subagent lifecycle, distinct from `resolved` (eager-done). */
  readonly subagentStatus: "running" | "done" | "failed" | null;
  /** A reasoning part riding the tool group as a chip. */
  readonly isThought: boolean;
}

/** A reasoning part flattened into styled, wrapped detail lines. */
export function thoughtDetail(text: string, live: boolean): ToolDetail | null {
  let lines = thoughtLines(parseMarkdown(text, live));
  const truncatedBy = Math.max(0, lines.length - OUTPUT_DETAIL_MAX_LINES);
  if (truncatedBy > 0) {
    if (live) {
      // Keep the TAIL while streaming (the fresh thinking is the signal);
      // settled thoughts keep the head like tool outputs do.
      lines = lines.slice(truncatedBy);
      // The cut can land on a block separator — drop the orphan blank.
      while (lines.length > 0 && lines[0]!.every((run) => run.text.trim().length === 0)) {
        lines = lines.slice(1);
      }
    } else {
      lines = lines.slice(0, OUTPUT_DETAIL_MAX_LINES);
    }
  }
  return lines.length > 0 ? { kind: "thought", lines, truncatedBy } : null;
}

/** Flatten a thought's parsed markdown into wrapped, styled detail lines. */
export function thoughtLines(tree: BlockTree): InlineRun[][] {
  const out: InlineRun[][] = [];
  for (const top of tree.blocks) {
    if (out.length > 0) {
      // One blank separator row between top-level blocks.
      out.push([]);
    }
    thoughtBlockLines(top.block, 0, out);
  }
  while (out.length > 0 && out[out.length - 1]!.every((run) => run.text.trim().length === 0)) {
    out.pop();
  }
  return out;
}

function indentRun(indent: number): InlineRun[] {
  return [{ text: " ".repeat(indent), style: {} }];
}

function pushStyled(line: InlineRun[], text: string, style: InlineStyle): void {
  if (text.length === 0) {
    return;
  }
  const last = line[line.length - 1];
  if (last !== undefined && styleEqual(last.style, style)) {
    line[line.length - 1] = { text: last.text + text, style: last.style };
    return;
  }
  line.push({ text, style });
}

function styleEqual(a: InlineStyle, b: InlineStyle): boolean {
  return (
    a.bold === b.bold &&
    a.italic === b.italic &&
    a.code === b.code &&
    a.strikethrough === b.strikethrough &&
    a.link === b.link &&
    a.image === b.image
  );
}

function finishLine(indent: number, line: InlineRun[]): InlineRun[] {
  return [...indentRun(indent), ...line];
}

/** Word-wrap styled runs at the thought column budget (port of wrap_styled_runs). */
function wrapStyledRuns(runs: readonly InlineRun[], indent: number, out: InlineRun[][]): void {
  const budget = Math.max(THOUGHT_WRAP_COLS - indent, 16);
  // Segments split at hard breaks (`\n` runs).
  const segments: InlineRun[][] = [[]];
  for (const run of runs) {
    const pieces = run.text.split("\n");
    for (let ix = 0; ix < pieces.length; ix++) {
      if (ix > 0) {
        segments.push([]);
      }
      if (pieces[ix]!.length > 0) {
        segments[segments.length - 1]!.push({ text: pieces[ix]!, style: run.style });
      }
    }
  }
  for (const segment of segments) {
    // Tokens: maximal non-whitespace piece lists, glued across run boundaries.
    const tokens: InlineRun[][] = [];
    let inToken = false;
    for (const run of segment) {
      const text = run.text;
      let pos = 0;
      while (pos < text.length) {
        const rest = text.slice(pos);
        const ws = /^\s/.test(rest);
        const match = /^\s+|\S+/.exec(rest)!;
        const end = pos + match[0].length;
        if (ws) {
          inToken = false;
        } else {
          if (!inToken) {
            tokens.push([]);
            inToken = true;
          }
          pushStyled(tokens[tokens.length - 1]!, text.slice(pos, end), run.style);
        }
        pos = end;
      }
    }
    let line: InlineRun[] = [];
    let len = 0;
    for (const token of tokens) {
      const tokLen = [...token.map((r) => r.text).join("")].length;
      if (tokLen > budget) {
        // Hard-split a pathological token at the budget.
        if (len > 0) {
          out.push(finishLine(indent, line));
          line = [];
          len = 0;
        }
        for (const piece of token) {
          let chars = [...piece.text];
          while (chars.length > 0) {
            const chunk = chars.slice(0, budget - len).join("");
            if (chunk.length === 0) {
              break;
            }
            chars = chars.slice([...chunk].length);
            len += [...chunk].length;
            pushStyled(line, chunk, piece.style);
            if (len === budget) {
              out.push(finishLine(indent, line));
              line = [];
              len = 0;
            }
          }
        }
        continue;
      }
      if (len > 0 && len + 1 + tokLen > budget) {
        out.push(finishLine(indent, line));
        line = [];
        len = 0;
      }
      if (len > 0) {
        const last = line[line.length - 1];
        if (last !== undefined) {
          line[line.length - 1] = { text: last.text + " ", style: last.style };
        }
        len++;
      }
      for (const piece of token) {
        pushStyled(line, piece.text, piece.style);
      }
      len += tokLen;
    }
    if (len > 0) {
      out.push(finishLine(indent, line));
    }
  }
}

/** Soft-wrap one raw line into `cols`-char chunks (port of wrap_cols). */
function wrapCols(line: string, cols: number): string[] {
  const chars = [...line];
  if (chars.length <= cols) {
    return [line];
  }
  const out: string[] = [];
  for (let i = 0; i < chars.length; i += cols) {
    out.push(chars.slice(i, i + cols).join(""));
  }
  return out;
}

/** One markdown block into thought detail lines, `indent` spaces deep. */
function thoughtBlockLines(block: Block, indent: number, out: InlineRun[][]): void {
  switch (block.kind) {
    case "paragraph":
      wrapStyledRuns(block.runs, indent, out);
      break;
    case "heading":
      // Headings keep the detail's single type size — bold is the cue.
      wrapStyledRuns(block.runs.map((run) => ({ ...run, style: { ...run.style, bold: true } })), indent, out);
      break;
    case "codeBlock": {
      const style: InlineStyle = { code: true };
      for (const line of block.code.split("\n")) {
        for (const chunk of wrapCols(line, Math.max(THOUGHT_WRAP_COLS - indent, 16))) {
          const row = indentRun(indent);
          if (chunk.length > 0) {
            row.push({ text: chunk, style });
          }
          out.push(row);
        }
      }
      break;
    }
    case "list": {
      // Tight rendering: no blank rows inside a list.
      block.items.forEach((item, ix) => {
        const marker = block.orderedStart !== null ? `${block.orderedStart + ix}. ` : "• ";
        const inner = indent + [...marker].length;
        const mark = out.length;
        for (const child of item.blocks) {
          thoughtBlockLines(child, inner, out);
        }
        if (out.length === mark) {
          out.push(indentRun(inner));
        }
        // The item's first line trades its indent spaces for the marker.
        const first = out[mark]![0];
        if (first !== undefined) {
          out[mark]![0] = { text: `${" ".repeat(indent)}${marker}${first.text.slice(indent)}`, style: first.style };
        }
      });
      break;
    }
    case "blockQuote": {
      const mark = out.length;
      block.children.forEach((child, ix) => {
        if (ix > 0) {
          out.push([]);
        }
        thoughtBlockLines(child, indent + 2, out);
      });
      // Trade the two quote-indent spaces for the bar on every quoted line.
      for (let k = mark; k < out.length; k++) {
        const first = out[k]![0];
        if (first !== undefined && first.text.length >= indent + 2) {
          out[k]![0] = {
            text: `${first.text.slice(0, indent)}│ ${first.text.slice(indent + 2)}`,
            style: first.style,
          };
        }
      }
      break;
    }
    case "table": {
      // A thought is a record, not a layout surface: cells joined with a dot
      // separator, header bold — no column machinery.
      const join = (cells: readonly (readonly InlineRun[])[], bold: boolean): InlineRun[] => {
        const line: InlineRun[] = [];
        cells.forEach((cell, ix) => {
          if (ix > 0) {
            pushStyled(line, " · ", {});
          }
          for (const run of cell) {
            pushStyled(line, run.text, bold ? { ...run.style, bold: true } : run.style);
          }
        });
        return line;
      };
      wrapStyledRuns(join(block.header, true), indent, out);
      for (const row of block.rows) {
        wrapStyledRuns(join(row, false), indent, out);
      }
      break;
    }
    case "rule": {
      const row = indentRun(indent);
      row.push({ text: "———", style: {} });
      out.push(row);
      break;
    }
  }
}

/**
 * Build a tool part's expandable detail. A diff (or its stats) wins over raw
 * output; trailing blank lines are trimmed so the block hugs its content.
 */
export function toolDetail(
  output: string | null | undefined,
  diffStats: readonly ToolDiffStat[] | null | undefined,
): ToolDetail | null {
  if (diffStats !== null && diffStats !== undefined && diffStats.length > 0) {
    return { kind: "stats", stats: diffStats };
  }
  if (output === null || output === undefined) {
    return null;
  }
  const lines = output.split("\n");
  while (lines.length > 0 && lines[lines.length - 1]!.trim().length === 0) {
    lines.pop();
  }
  if (lines.length === 0) {
    return null;
  }
  const truncatedBy = Math.max(0, lines.length - OUTPUT_DETAIL_MAX_LINES);
  return { kind: "output", lines: lines.slice(0, OUTPUT_DETAIL_MAX_LINES), truncatedBy };
}

/**
 * Build a chip's full-invocation block — the complete tool call the header
 * truncates to one line: the whole command, pattern, or URL, todo items one
 * per line, MCP/unknown input as pretty-printed JSON (port of `call_block`).
 */
export function callBlock(call: ToolCall): ToolDetail | null {
  let text: string;
  switch (call.kind) {
    case "exec":
      text = call.command;
      break;
    case "readFile":
      text = call.path;
      break;
    case "writeFile":
      text = call.content !== null && call.content !== undefined ? `${call.path}\n${call.content}` : call.path;
      break;
    case "editFile":
      text = call.path;
      break;
    case "applyPatch":
      text = call.path ?? "workspace";
      break;
    case "search":
      text = call.path !== null && call.path !== undefined ? `${call.pattern} in ${call.path}` : call.pattern;
      break;
    case "glob":
      text = call.pattern;
      break;
    case "webFetch":
      text = call.prompt !== null && call.prompt !== undefined ? `${call.url}\n${call.prompt}` : call.url;
      break;
    case "webSearch":
      text = call.query;
      break;
    case "todo":
      text = call.items.map((item) => `${item.done ? "[x]" : "[ ]"} ${item.text}`).join("\n");
      break;
    case "mcp": {
      const pretty = call.input !== null && call.input !== undefined ? safePretty(call.input) : null;
      text = pretty !== null ? `${call.server} · ${call.tool}\n${pretty}` : `${call.server} · ${call.tool}`;
      break;
    }
    case "unknown": {
      const pretty = call.input !== null && call.input !== undefined ? safePretty(call.input) : null;
      text = pretty !== null ? `${call.name}\n${pretty}` : call.name;
      break;
    }
  }
  const lines = text.split("\n").flatMap((line) => wrapCols(line, CALL_WRAP_COLS));
  while (lines.length > 0 && lines[lines.length - 1]!.trim().length === 0) {
    lines.pop();
  }
  if (lines.length === 0) {
    return null;
  }
  const truncatedBy = Math.max(0, lines.length - OUTPUT_DETAIL_MAX_LINES);
  return { kind: "output", lines: lines.slice(0, OUTPUT_DETAIL_MAX_LINES), truncatedBy };
}

function safePretty(input: unknown): string | null {
  try {
    return JSON.stringify(input, null, 2);
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Entry helpers
// ---------------------------------------------------------------------------

/** User prompts clamp to this many wrapped lines until expanded. */
export const USER_COLLAPSED_LINES = 5;
/** Conservative char-count proxy for the fold affordance. */
export const USER_COLLAPSE_CHARS = 400;

/** Whether a prompt may need a fold affordance (first-frame proxy). */
export function userMessageNeedsCollapse(text: string): boolean {
  return text.split("\n").length > USER_COLLAPSED_LINES || [...text].length > USER_COLLAPSE_CHARS;
}

/**
 * Clipboard payload for an assistant/system entry: authored text parts in
 * document order, preserving Markdown while excluding tool traces.
 */
export function assistantCopyText(entry: SessionMessageEntry): string | null {
  const text = entry.parts
    .filter((part): part is Extract<MessagePart, { kind: "text" }> => part.kind === "text" && part.text.trim().length > 0)
    .map((part) => part.text)
    .join("\n\n");
  return text.length > 0 ? text : null;
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Absolute hover-timestamp label, e.g. "Jul 1, 3:45 PM" (local timezone). */
export function formatTimestamp(ms: number): string {
  const date = new Date(ms);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const hours = date.getHours();
  const hour12 = hours % 12 === 0 ? 12 : hours % 12;
  const minutes = String(date.getMinutes()).padStart(2, "0");
  const ampm = hours < 12 ? "AM" : "PM";
  return `${MONTHS[date.getMonth()]} ${date.getDate()}, ${hour12}:${minutes} ${ampm}`;
}

/** FNV-1a over UTF-16 code units, folded to 32 bits — the row-version hash. */
export function fnv1a(text: string): number {
  let hash = 0xcbf29ce484222325n;
  const prime = 0x1000001b3n;
  for (let i = 0; i < text.length; i++) {
    const unit = text.charCodeAt(i);
    hash ^= BigInt(unit & 0xff);
    hash = BigInt.asUintN(64, hash * prime);
    hash ^= BigInt(unit >> 8);
    hash = BigInt.asUintN(64, hash * prime);
  }
  // 32 bits keep `version * 2 + bit` and `version ^ (1 << 30)` exact in JS.
  return Number(BigInt.asIntN(32, hash));
}

// ---------------------------------------------------------------------------
// Row model (transcript.rs rows_for_entry)
// ---------------------------------------------------------------------------

export type TranscriptRowKind =
  | {
      readonly kind: "user";
      readonly text: string;
      /** Optimistic echo not yet confirmed by a doc frame. */
      readonly pending: boolean;
      /** Attachment refs parsed out of the message's refs trailer
       *  (composer/use-attachments.ts `withAttachments`). The transcript
       *  renders a thumbnail strip above the bubble when non-empty. */
      readonly attachments: readonly UserImageAttachment[];
    }
  /** One top-level markdown block of a completed message. */
  | { readonly kind: "markdown"; readonly tree: BlockTree; readonly blockIx: number }
  /** One top-level block of a STREAMING message (same split as settled rows). */
  | { readonly kind: "liveMarkdown"; readonly tree: BlockTree; readonly blockIx: number }
  | { readonly kind: "toolGroup"; readonly tools: readonly ToolItem[]; readonly autoOpen: boolean }
  | { readonly kind: "inputChip"; readonly header: string; readonly resolved: boolean }
  | { readonly kind: "errorChip"; readonly message: string };

/** A transcript row: stable id + content version (diff key) + block payload. */
export interface TranscriptRow {
  readonly id: string;
  readonly version: number;
  /** First row of its message entry (gets the turn gap). */
  readonly turnStart: boolean;
  readonly rowKind: TranscriptRowKind;
  /** The owning message entry (hover reveals its timestamp strip). */
  readonly entryId: string;
  /** Epoch-ms for the hover-timestamp strip UNDER this row (settled last row). */
  readonly timestamp: number | null;
  /** Text copied by the entry-level hover action (settled last row only). */
  readonly copyText: string | null;
}

export interface RowsOptions {
  /** Optimistic echo flag for user entries. */
  readonly pending?: boolean;
  /** Maps `(partKey, text, live)` to a block tree (a MarkdownCache parse). */
  readonly parse: (key: string, text: string, live: boolean) => BlockTree;
}

function isAgentCall(call: ToolCall): boolean {
  return isSubagentSpawn(call);
}

function isAgentTool(item: ToolItem): boolean {
  return isAgentCall(item.call);
}

/**
 * Build the block rows of one (already continuation-joined) entry — a direct
 * port of `rows_for_entry`: user entries are one bubble row; assistant/system
 * entries split into one row per top-level markdown block with consecutive
 * same-genus tools folded into group rows; input and error parts are chips.
 */
export function rowsForEntry(entry: SessionMessageEntry, options: RowsOptions): TranscriptRow[] {
  const pending = options.pending ?? false;
  const streaming = entry.status === "streaming";
  const rows: TranscriptRow[] = [];

  if (entry.role === "user") {
    const raw = entry.parts
      .filter((part): part is Extract<MessagePart, { kind: "text" }> => part.kind === "text")
      .map((part) => part.text)
      .join("\n\n");
    const parsed = parseUserMessageImages(raw);
    const copyText = parsed.text.trim().length > 0 ? parsed.text : null;
    // `raw.length << 1 | pending` on the desktop; BigInt-free equivalent.
    return [
      {
        id: entry.id,
        version: raw.length * 2 + (pending ? 1 : 0),
        turnStart: true,
        rowKind: { kind: "user", text: parsed.text, pending, attachments: parsed.attachments },
        entryId: entry.id,
        // User rows always carry the strip (the optimistic echo included).
        timestamp: entry.createdAt,
        copyText,
      },
    ];
  }

  // Assistant/system: split parts into block rows, folding consecutive
  // ordinary tools. Agent/spawn chips flush into their own group so they
  // never share a collapse with Reads/Runs.
  const lastPartIx = entry.parts.length - 1;
  let groupIx = 0;
  let pendingGroup: ToolItem[] = [];
  let groupLastPartIx = 0;

  const flushGroup = (): void => {
    if (pendingGroup.length === 0) {
      return;
    }
    const tools = pendingGroup;
    pendingGroup = [];
    const autoOpen = streaming && groupLastPartIx === lastPartIx;
    rows.push({
      id: `${entry.id}#g${groupIx}`,
      version: toolFingerprint(tools, autoOpen),
      turnStart: false,
      rowKind: { kind: "toolGroup", tools, autoOpen },
      entryId: entry.id,
      timestamp: null,
      copyText: null,
    });
    groupIx++;
  };

  entry.parts.forEach((part, partIx) => {
    if (part.kind === "tool") {
      const item: ToolItem = {
        call: part.call,
        isError: part.isError,
        resolved: part.resolved,
        detail: toolDetail(part.output, part.diffStats),
        invocation: callBlock(part.call),
        outputRef: part.outputRef ?? null,
        outputBytes: part.outputBytes ?? null,
        diffRef: part.diffRef ?? null,
        subagentRef: part.subagentRef ?? null,
        subagentStatus: part.subagentStatus ?? null,
        isThought: false,
      };
      // Agent chips don't share a fold with ordinary tools: flush whenever
      // the genus flips so each group is uniform.
      const head = pendingGroup[0];
      if (head !== undefined && isAgentTool(head) !== isAgentTool(item)) {
        flushGroup();
      }
      pendingGroup.push(item);
      groupLastPartIx = partIx;
      return;
    }
    if (part.kind === "reasoning") {
      if (part.text.trim().length === 0) {
        return;
      }
      // Live only while it is the tail of a streaming reply.
      const live = streaming && partIx === lastPartIx;
      const detail = thoughtDetail(part.text, live);
      const item: ToolItem = {
        call: { kind: "unknown", name: "Thought process" },
        isError: false,
        resolved: !live,
        detail,
        invocation: null,
        outputRef: null,
        outputBytes: null,
        diffRef: null,
        subagentRef: null,
        subagentStatus: null,
        isThought: true,
      };
      // Thoughts join ordinary tool groups; agent groups stay pure.
      if (pendingGroup[0] !== undefined && isAgentTool(pendingGroup[0])) {
        flushGroup();
      }
      pendingGroup.push(item);
      groupLastPartIx = partIx;
      return;
    }

    flushGroup();
    if (part.kind === "text") {
      if (part.text.trim().length === 0) {
        return;
      }
      const key = `${entry.id}#${part.id}`;
      const tree = options.parse(key, part.text, streaming);
      // Live and completed parts split identically — one row per top-level
      // block, same ids, so the live→complete handoff never changes identity.
      for (let blockIx = 0; blockIx < tree.blocks.length; blockIx++) {
        const top = tree.blocks[blockIx]!;
        const bytes = part.text.slice(Math.min(top.start, part.text.length), Math.min(top.end, part.text.length));
        rows.push({
          id: `${key}.${blockIx}`,
          version: fnv1a(bytes) * 2 + (streaming ? 1 : 0),
          turnStart: false,
          entryId: entry.id,
          timestamp: null,
          copyText: null,
          rowKind: streaming
            ? { kind: "liveMarkdown", tree, blockIx }
            : { kind: "markdown", tree, blockIx },
        });
      }
      return;
    }
    if (part.kind === "input") {
      const header = singleLine(part.questions[0]?.header ?? "Question");
      rows.push({
        id: `${entry.id}#${part.id}`,
        version: fnv1a(header) * 2 + (part.resolved ? 1 : 0),
        turnStart: false,
        rowKind: { kind: "inputChip", header, resolved: part.resolved },
        entryId: entry.id,
        timestamp: null,
        copyText: null,
      });
      return;
    }
    if (part.kind === "error") {
      rows.push({
        id: `${entry.id}#${part.id}`,
        version: part.message.length,
        turnStart: false,
        // Harness-generated; the chip is one line.
        rowKind: { kind: "errorChip", message: singleLine(part.message) },
        entryId: entry.id,
        timestamp: null,
        copyText: null,
      });
    }
  });
  flushGroup();

  if (rows.length > 0) {
    rows[0] = { ...rows[0]!, turnStart: true };
  }
  // Timestamp strip under the entry's LAST row once the turn has settled
  // ("No timestamp hover mid-stream").
  if (!streaming && rows.length > 0) {
    const last = rows[rows.length - 1]!;
    rows[rows.length - 1] = {
      ...last,
      timestamp: entry.createdAt,
      copyText: assistantCopyText(entry),
      version: last.version ^ 0x40000000,
    };
  }
  return rows;
}

/** Content fingerprint for a tool group row (the diff key). */
function toolFingerprint(tools: readonly ToolItem[], autoOpen: boolean): number {
  let acc = "";
  for (const tool of tools) {
    const { label, detail } = toolChipContent(tool.call);
    acc += label;
    acc += String(detail.length);
    acc += String(Number(tool.isError) | (Number(tool.resolved) << 1));
    if (tool.detail === null) {
      acc += "0";
    } else if (tool.detail.kind === "output") {
      acc += `1${tool.detail.lines.length},${tool.detail.truncatedBy},${tool.detail.lines.join("").length}`;
    } else if (tool.detail.kind === "thought") {
      acc += `4${tool.detail.lines.length},${tool.detail.truncatedBy},${tool.detail.lines
        .map((line) => line.map((run) => run.text).join(""))
        .join("")
        .length}`;
    } else {
      acc += `5${tool.detail.stats.length}`;
    }
    if (tool.invocation !== null && tool.invocation.kind === "output") {
      acc += `i${tool.invocation.lines.length}`;
    }
    acc += tool.outputRef ?? "";
    acc += tool.subagentRef ?? "";
    acc += tool.subagentStatus ?? "";
  }
  acc += String(Number(autoOpen));
  return fnv1a(acc);
}

// ---------------------------------------------------------------------------
// Row spacing and diffs
// ---------------------------------------------------------------------------

const SPACE_SM = 8;
const SPACE_MD = 12;
const SPACE_LG = 16;
/** Gap between sibling markdown block rows (render.rs MD_BLOCK_GAP). */
export const MD_BLOCK_GAP = 12;

/** Markdown row ids are `{entry}#{part}.{blockIx}` — the part prefix. */
function partPrefix(id: string): string {
  const dot = id.lastIndexOf(".");
  return dot < 0 ? id : id.slice(0, dot);
}

/**
 * Vertical gap opening `row` given its predecessor: turn gap at turn starts;
 * the markdown block gap between sibling rows of the same part; tool groups
 * open with the medium step (their own padding handles the trailing side)
 * and close into the small step.
 */
export function topGapFor(prev: TranscriptRow | null, row: TranscriptRow): number {
  if (row.turnStart) {
    return SPACE_LG;
  }
  const samePart = prev !== null && partPrefix(prev.id) === partPrefix(row.id);
  if (samePart) {
    return MD_BLOCK_GAP;
  }
  if (row.rowKind.kind === "toolGroup") {
    return SPACE_MD;
  }
  if (prev?.rowKind.kind === "toolGroup") {
    return SPACE_SM;
  }
  return SPACE_SM;
}

/**
 * Minimal splice for a row-set change: `[start, deleteCount, insertCount]`,
 * or `null` when the sets are identical by (id, version).
 */
export function diffRows(
  oldRows: readonly TranscriptRow[],
  newRows: readonly TranscriptRow[],
): [number, number, number] | null {
  const eq = (a: TranscriptRow, b: TranscriptRow): boolean => a.id === b.id && a.version === b.version;
  let prefix = 0;
  const maxPrefix = Math.min(oldRows.length, newRows.length);
  while (prefix < maxPrefix && eq(oldRows[prefix]!, newRows[prefix]!)) {
    prefix++;
  }
  if (prefix === oldRows.length && prefix === newRows.length) {
    return null;
  }
  let suffix = 0;
  const maxSuffix = Math.min(oldRows.length - prefix, newRows.length - prefix);
  while (suffix < maxSuffix && eq(oldRows[oldRows.length - 1 - suffix]!, newRows[newRows.length - 1 - suffix]!)) {
    suffix++;
  }
  return [prefix, oldRows.length - suffix - prefix, newRows.length - suffix - prefix];
}

// ---------------------------------------------------------------------------
// Delta application (doc/src/transcript_delta.rs apply_transcript_frame)
// ---------------------------------------------------------------------------

/** A frame that could not be applied cleanly — resubscribe for a reset. */
export class TranscriptDesync extends Error {
  constructor(message: string) {
    super(`transcript delta desync: ${message}`);
    this.name = "TranscriptDesync";
  }
}

/**
 * Apply a frame immutably: the returned array is new only when something
 * changed, and entries the frame doesn't touch keep their object identity so
 * React rows memoize across stream ticks. Throws `TranscriptDesync` on any
 * inconsistency — the consumer's copy has diverged and it must resubscribe.
 */
export function applyTranscriptFrame(
  current: readonly SessionMessageEntry[],
  frame: TranscriptFrame,
): readonly SessionMessageEntry[] {
  if ("reset" in frame) {
    return preserveIdentity(current, frame.reset);
  }
  const { upsert, append, remove, count } = frame;
  let next: SessionMessageEntry[] | null = null;
  const ensure = (): SessionMessageEntry[] => {
    if (next === null) {
      next = [...current];
    }
    return next;
  };

  if (remove.length > 0) {
    const gone = new Set(remove);
    const list = ensure().filter((entry) => !gone.has(entry.id));
    next = list;
  }
  for (const { after, entry } of upsert) {
    const list = ensure();
    const existing = list.findIndex((candidate) => candidate.id === entry.id);
    if (existing >= 0) {
      list.splice(existing, 1);
    }
    let at = 0;
    if (after !== null) {
      const anchor = list.findIndex((candidate) => candidate.id === after);
      if (anchor < 0) {
        throw new TranscriptDesync(`missing anchor ${after}`);
      }
      at = anchor + 1;
    }
    list.splice(at, 0, entry);
  }
  for (const { entry: entryId, part: partId, text, len } of append) {
    const list = ensure();
    const index = list.findIndex((candidate) => candidate.id === entryId);
    if (index < 0) {
      throw new TranscriptDesync(`missing append entry ${entryId}`);
    }
    const target = list[index]!;
    const partIndex = target.parts.findIndex(
      (candidate) => (candidate.kind === "text" || candidate.kind === "reasoning") && candidate.id === partId,
    );
    if (partIndex < 0) {
      throw new TranscriptDesync(`missing append part ${partId}`);
    }
    const oldPart = target.parts[partIndex] as Extract<MessagePart, { kind: "text" | "reasoning" }>;
    const grown = oldPart.text + text;
    if (grown.length !== len) {
      throw new TranscriptDesync(
        `append length mismatch on ${entryId}#${partId}: have ${grown.length}, expected ${len}`,
      );
    }
    const parts = [...target.parts];
    parts[partIndex] = { ...oldPart, text: grown };
    list[index] = { ...target, parts };
    next = list;
  }
  const result = next ?? current;
  if (result.length !== count) {
    throw new TranscriptDesync(`count mismatch: have ${result.length}, expected ${count}`);
  }
  return result;
}

/** Reuse entry identities across a reset when deep-equal (cache-swap case). */
function preserveIdentity(
  current: readonly SessionMessageEntry[],
  incoming: readonly SessionMessageEntry[],
): readonly SessionMessageEntry[] {
  const byId = new Map(current.map((entry) => [entry.id, entry]));
  let changed = incoming.length !== current.length;
  const next = incoming.map((entry) => {
    const existing = byId.get(entry.id);
    if (existing !== undefined && jsonEqual(existing, entry)) {
      return existing;
    }
    changed = true;
    return entry;
  });
  // Order is authoritative too: same identities in a new order must rebuild.
  const reordered = !changed && next.some((entry, ix) => current[ix] !== entry);
  return changed || reordered ? next : current;
}

function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) {
    return true;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((value, index) => jsonEqual(value, b[index]));
  }
  if (typeof a === "object" && a !== null && typeof b === "object" && b !== null) {
    const keys = Object.keys(a as Record<string, unknown>);
    const other = b as Record<string, unknown>;
    return (
      keys.length === Object.keys(other).length &&
      keys.every((key) => Object.hasOwn(other, key) && jsonEqual((a as Record<string, unknown>)[key], other[key]))
    );
  }
  return false;
}

/** The text a streaming row veils: the block's flat visible text. */
export function rowFlatText(row: TranscriptRow): string | null {
  if (row.rowKind.kind !== "liveMarkdown" && row.rowKind.kind !== "markdown") {
    return null;
  }
  const top = row.rowKind.tree.blocks[row.rowKind.blockIx];
  return top === undefined ? null : blockFlatText(top.block);
}

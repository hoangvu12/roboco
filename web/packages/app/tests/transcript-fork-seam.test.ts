import { describe, expect, it } from "vitest";
import type { MessagePart, SessionMessageEntry } from "@roboco/proto";
import { parseMarkdown } from "../src/lib/markdown";
import { fnv1a, rowsForEntry, singleLine } from "../src/lib/transcript";

/**
 * The fork seam (ticket 10) — `MessagePart::Fork` → the `ForkMarker` row
 * (the desktop's transcript.rs:1574-1594 arm + `RowKind::ForkMarker`
 * :1040-1046): one row per fork part, id-keyed, version-hashed off the
 * source title, one-lined so a long title can never widen the divider.
 * The seam is the FIRST entry of a forked chat's doc (system role,
 * complete) — copied history above it, the fork's own turns below.
 */

const parse = (_key: string, text: string, live: boolean) => parseMarkdown(text, live);

function forkPart(id: string, sourceChatId: string, sourceTitle: string): MessagePart {
  return { kind: "fork", id, sourceChatId, sourceTitle };
}

function entry(id: string, parts: MessagePart[], fields: Partial<SessionMessageEntry> = {}): SessionMessageEntry {
  return {
    id,
    role: "system",
    parts,
    createdAt: 1758000000000,
    deviceId: "dev",
    status: "complete",
    continuationOf: null,
    durationMs: null,
    ...fields,
  };
}

describe("the fork seam row", () => {
  it("emits one forkMarker row carrying the source chat id and title", () => {
    const rows = rowsForEntry(
      entry("fork-1", [forkPart("fork:chat-9", "chat-9", "The source conversation")]),
      { parse },
    );
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.rowKind).toEqual({
      kind: "forkMarker",
      sourceChatId: "chat-9",
      sourceTitle: "The source conversation",
    });
    // The row id is entry#part — stable across replays. The seam is the
    // entry's first row, so it carries the turn gap like every first row
    // (the desktop's generic `first.turn_start = true` applies).
    expect(row.id).toBe("fork-1#fork:chat-9");
    expect(row.turnStart).toBe(true);
    // No message metadata lane: a quiet divider, never a hover target —
    // the desktop excludes ForkMarker from the last-row timestamp strip.
    expect(row.timestamp).toBeNull();
    expect(row.copyText).toBeNull();
    expect(row.compactFold).toBeNull();
  });

  it("versions off the source title — a rename re-keys the row, nothing else", () => {
    const first = rowsForEntry(entry("e", [forkPart("f", "s", "Old title")]), { parse })[0]!;
    const renamed = rowsForEntry(entry("e", [forkPart("f", "s", "New title")]), { parse })[0]!;
    expect(renamed.version).not.toBe(first.version);
    expect(renamed.version).toBe(fnv1a("New title"));
    // Same title, same part: identical version (the diff key is stable) —
    // no timestamp-XOR epilogue touches the seam.
    const replay = rowsForEntry(entry("e", [forkPart("f", "s", "New title")]), { parse })[0]!;
    expect(replay.version).toBe(renamed.version);
  });

  it("one-lines the source title so a long one cannot widen the divider", () => {
    const long = "A title that runs long\nand wraps onto a second line";
    const rows = rowsForEntry(entry("e", [forkPart("f", "s", long)]), { parse });
    expect(rows[0]!.rowKind).toEqual({
      kind: "forkMarker",
      sourceChatId: "s",
      sourceTitle: singleLine(long),
    });
  });

  it("sits alongside the copied history and the fork's own turns, in document order", () => {
    const history: MessagePart = { kind: "text", id: "h1", text: "Copied turn" };
    const seam: MessagePart = forkPart("fork:c2", "c1", "Source");
    const own: MessagePart = { kind: "text", id: "o1", text: "The fork's own reply" };
    const rows = [
      ...rowsForEntry(entry("e1", [history], { role: "assistant" }), { parse }),
      ...rowsForEntry(entry("e2", [seam], { role: "system" }), { parse }),
      ...rowsForEntry(entry("e3", [own], { role: "user" }), { parse }),
    ];
    const kinds = rows.map((row) =>
      row.rowKind.kind === "forkMarker" ? "fork" : row.rowKind.kind === "markdown" ? "text" : row.rowKind.kind,
    );
    expect(kinds).toEqual(["text", "fork", "user"]);
  });

  it("renders in compact mode too — the seam is never a folded work part", () => {
    const rows = rowsForEntry(
      entry("e", [forkPart("f", "s", "Source")], { role: "assistant" }),
      { parse, compact: true },
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.rowKind.kind).toBe("forkMarker");
    expect(rows[0]!.compactFold).toBeNull();
  });
});

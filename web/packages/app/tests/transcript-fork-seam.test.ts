import { describe, expect, it } from "vitest";
import type { MessagePart, SessionMessageEntry } from "@roboco/proto";
import { parseMarkdown } from "../src/lib/markdown";
import { fnv1a, rowsForEntry, singleLine, switchedToLabel } from "../src/lib/transcript";

/**
 * The fork seam (ticket 10) — `MessagePart::Fork` → the `ForkMarker` row
 * (the desktop's transcript.rs:1574-1594 arm + `RowKind::ForkMarker`
 * :1040-1046): one row per fork part, id-keyed, version-hashed off the
 * source title, one-lined so a long title can never widen the divider.
 * The seam is the FIRST entry of a forked chat's doc (system role,
 * complete) — copied history above it, the fork's own turns below.
 *
 * The same file carries the seams and hover actions of orchestration v2's
 * tickets 21+24: the "Switched to <harness>" divider and the
 * `forkEntry` stamp every settled assistant reply's hover strip offers.
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

// ── The harness-switch seam (ticket 24) ─────────────────────────────────

describe("the harness-switch seam row", () => {
  const switchPart = (id: string, harness: "codex" | "pi"): MessagePart => ({
    kind: "harnessSwitch",
    id,
    harness,
  });

  it("emits one harnessSwitchMarker row with the pre-resolved label", () => {
    const rows = rowsForEntry(entry("switch-1", [switchPart("switch:chat-9", "codex")]), {
      parse,
    });
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.rowKind).toEqual({ kind: "harnessSwitchMarker", label: "Switched to Codex" });
    expect(row.id).toBe("switch-1#switch:chat-9");
    expect(row.turnStart).toBe(true);
    // No message metadata lane: a quiet divider, never a hover target —
    // exactly the fork seam's exclusion.
    expect(row.timestamp).toBeNull();
    expect(row.copyText).toBeNull();
    expect(row.forkEntry).toBeUndefined();
  });

  it("versions off the label and one-lines it — the seam is fixed once written", () => {
    expect(switchedToLabel("pi")).toBe("Switched to Pi");
    expect(switchedToLabel("claude-code")).toBe("Switched to Claude Code");
    const first = rowsForEntry(entry("e", [switchPart("f", "codex")]), { parse })[0]!;
    const replay = rowsForEntry(entry("e", [switchPart("f", "codex")]), { parse })[0]!;
    expect(replay.version).toBe(first.version);
    expect(replay.version).toBe(fnv1a("Switched to Codex"));
    const other = rowsForEntry(entry("e", [switchPart("f", "pi")]), { parse })[0]!;
    expect(other.version).not.toBe(first.version);
  });

  it("sits between the old harness's turns and the new one's, in document order", () => {
    const before: MessagePart = { kind: "text", id: "t1", text: "An old turn" };
    const seam: MessagePart = switchPart("switch:c2", "pi");
    const after: MessagePart = { kind: "text", id: "t2", text: "A new turn" };
    const rows = [
      ...rowsForEntry(entry("e1", [before], { role: "assistant" }), { parse }),
      ...rowsForEntry(entry("e2", [seam]), { parse }),
      ...rowsForEntry(entry("e3", [after], { role: "user" }), { parse }),
    ];
    const kinds = rows.map((row) =>
      row.rowKind.kind === "harnessSwitchMarker"
        ? "switch"
        : row.rowKind.kind === "markdown"
          ? "text"
          : row.rowKind.kind === "user"
            ? "user"
            : row.rowKind.kind,
    );
    expect(kinds).toEqual(["text", "switch", "user"]);
  });

  it("renders in compact mode too — the seam is never a folded work part", () => {
    const rows = rowsForEntry(entry("e", [switchPart("f", "codex")]), { parse, compact: true });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.rowKind.kind).toBe("harnessSwitchMarker");
  });
});

// ── "Fork from here" (ticket 21): the settled assistant rows' stamp ─────

describe("forkEntry — the hover action's stamp", () => {
  const reply = (id: string, status: "complete" | "streaming" | "aborted"): SessionMessageEntry =>
    entry(id, [{ kind: "text", id: `${id}#t`, text: "A reply" }], {
      role: "assistant",
      status,
    });

  it("lands on the settled assistant reply's last row, beside the timestamp lane", () => {
    const rows = rowsForEntry(reply("a1", "complete"), { parse });
    expect(rows).toHaveLength(1);
    expect(rows[0]!.forkEntry).toBe("a1");
    expect(rows[0]!.timestamp).not.toBeNull();
    // Errored and interrupted replies (aborted status) qualify too.
    expect(rowsForEntry(reply("a2", "aborted"), { parse })[0]!.forkEntry).toBe("a2");
  });

  it("a streaming reply never carries it — the run is still writing it", () => {
    const rows = rowsForEntry(reply("a3", "streaming"), { parse });
    expect(rows[0]!.forkEntry).toBeUndefined();
    expect(rows[0]!.timestamp).toBeNull();
  });

  it("user and system entries never carry it", () => {
    const user = entry("u1", [{ kind: "text", id: "u1#t", text: "A question" }], {
      role: "user",
    });
    expect(rowsForEntry(user, { parse })[0]!.forkEntry).toBeUndefined();
    const seam = entry("s1", [forkPart("f", "src", "Source")], { role: "system" });
    expect(rowsForEntry(seam, { parse })[0]!.forkEntry).toBeUndefined();
  });

  it("compact mode's folded work rows never carry it — only the entry's own last settled row", () => {
    const withWork: SessionMessageEntry = entry(
      "a4",
      [
        { kind: "tool", id: "a4#tool", call: { kind: "exec", command: "ls" }, isError: false, resolved: true },
        { kind: "text", id: "a4#t", text: "The reply" },
      ],
      { role: "assistant", status: "complete" },
    );
    const rows = rowsForEntry(withWork, { parse, compact: true });
    // The folded work rows are tagged with the fold and stripped.
    const folded = rows.filter((row) => row.compactFold !== null);
    expect(folded.length).toBeGreaterThan(0);
    expect(folded.every((row) => row.forkEntry === undefined)).toBe(true);
    // The visible reply rows keep it only on the entry's LAST row.
    const visible = rows.filter((row) => row.compactFold === null);
    expect(visible[visible.length - 1]!.forkEntry).toBe("a4");
  });
});

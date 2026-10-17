import { describe, expect, it } from "vitest";
import type { MessagePart, NoticeSide, SessionMessageEntry } from "@roboco/proto";
import { parseMarkdown } from "../src/lib/markdown";
import {
  fnv1a,
  noticeFingerprint,
  noticeOutcomeLine,
  noticeOutcomeVerb,
  rowsForEntry,
  shortChatId,
  singleLine,
} from "../src/lib/transcript";

/**
 * The completion notice (orchestration v2 ticket 15) — the `completionNotice`
 * marker part → the `completionNotice` row (the desktop's transcript.rs
 * `MessagePart::CompletionNotice` arm + `RowKind::CompletionNotice`): ONE
 * distinct system-notice card per marker, carrying the side-chat rows; the
 * entry's plain-text sibling part — the same notice as prose, kept in the doc
 * so older layouts stay readable — renders no row beside it. A marker with
 * no sides (a partial row off a lenient doc decode) renders nothing, and the
 * text sibling then carries the notice as plain text.
 */

const parse = (_key: string, text: string, live: boolean) => parseMarkdown(text, live);

function side(
  chatId: string,
  title: string,
  outcome: NoticeSide["outcome"],
  detail?: string,
): NoticeSide {
  return { chatId, title, outcome, detail: detail ?? null };
}

function noticePart(id: string, sides: NoticeSide[]): MessagePart {
  return { kind: "completionNotice", id, sides };
}

function textPart(id: string, text: string): MessagePart {
  return { kind: "text", id, text };
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

describe("the completion notice row", () => {
  it("emits one card row carrying the sides; the text sibling renders nothing", () => {
    const rows = rowsForEntry(
      entry("notice:1", [
        noticePart("n0", [
          side("chat-side-1234", "Implement login", "completed"),
          side("chat-side-5678", "Flaky test", "errored", "the build command timed out"),
        ]),
        textPart(
          "t0",
          '2 side chats reached outcomes:\n- "Implement login" (chat-si) finished.\n- "Flaky test" (chat-si) failed: the build command timed out.',
        ),
      ]),
      { parse },
    );
    // One row — the card. The notice reads once, not twice.
    expect(rows).toHaveLength(1);
    const row = rows[0]!;
    expect(row.rowKind).toEqual({
      kind: "completionNotice",
      sides: [
        { chatId: "chat-side-1234", title: "Implement login", outcome: "completed", detail: null },
        {
          chatId: "chat-side-5678",
          title: "Flaky test",
          outcome: "errored",
          detail: "the build command timed out",
        },
      ],
    });
    // The row id is entry#part — stable across replays — and the card is the
    // entry's first row, so it carries the turn gap like every first row.
    expect(row.id).toBe("notice:1#n0");
    expect(row.turnStart).toBe(true);
    // Engine structure, not a hover target: no timestamp strip, no copy.
    expect(row.timestamp).toBeNull();
    expect(row.copyText).toBeNull();
    expect(row.compactFold).toBeNull();
  });

  it("versions off the sides — a changed outcome or detail re-keys the card", () => {
    const base = [side("chat-side-1234", "Implement login", "completed")];
    const first = rowsForEntry(entry("e", [noticePart("n0", base), textPart("t0", "text")]), { parse })[0]!;
    expect(first.version).toBe(noticeFingerprint(base));

    const renamed = rowsForEntry(
      entry("e", [noticePart("n0", [side("chat-side-1234", "Renamed", "completed")]), textPart("t0", "text")]),
      { parse },
    )[0]!;
    expect(renamed.version).not.toBe(first.version);

    const errored = rowsForEntry(
      entry("e", [
        noticePart("n0", [side("chat-side-1234", "Implement login", "errored", "provider 502")]),
        textPart("t0", "text"),
      ]),
      { parse },
    )[0]!;
    expect(errored.version).not.toBe(first.version);

    // Identical sides re-key identically: a replay is a no-op splice.
    const replay = rowsForEntry(entry("e", [noticePart("n0", base), textPart("t0", "text")]), { parse })[0]!;
    expect(replay.version).toBe(first.version);
    // The fingerprint is the sides' presentation fields, joined with NULs so
    // no field can bleed into the next one.
    expect(first.version).toBe(
      fnv1a("chat-side-1234\u0000Implement login\u0000completed\u0000\u0000"),
    );
  });

  it("a marker without sides falls back to its plain text — the notice stays readable", () => {
    const rows = rowsForEntry(
      entry("notice:2", [
        noticePart("n0", []),
        textPart("t0", 'Side chat "Migration" (chat-si) was stopped.\nRead the full result.'),
      ]),
      { parse },
    );
    // No card without sides: plain markdown rows carry the notice as prose —
    // exactly what an older layout (or a lenient decode of a partial row)
    // reads.
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((row) => row.rowKind.kind === "markdown")).toBe(true);
    // The settled last row carries the metadata strip again, like any
    // text-bearing system entry.
    expect(rows[rows.length - 1]!.timestamp).toBe(1758000000000);
  });

  it("renders in compact mode too — the card is never a folded work part", () => {
    const rows = rowsForEntry(
      entry("notice:3", [
        noticePart("n0", [side("chat-side-1234", "Implement login", "waitingForInput", "Bump the major version?")]),
        textPart("t0", 'Side chat "Implement login" is waiting for input.'),
      ]),
      { parse, compact: true },
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]!.rowKind.kind).toBe("completionNotice");
    expect(rows[0]!.compactFold).toBeNull();
  });

  it("sits alongside the parent's own turns, in document order", () => {
    const rows = [
      ...rowsForEntry(entry("e1", [textPart("u1", "go")], { role: "user" }), { parse }),
      ...rowsForEntry(
        entry("e2", [
          noticePart("n0", [side("chat-side-1234", "Implement login", "completed")]),
          textPart("t0", "text"),
        ]),
        { parse },
      ),
      ...rowsForEntry(entry("e3", [textPart("a1", "the reply")], { role: "assistant" }), { parse }),
    ];
    const kinds = rows.map((row) =>
      row.rowKind.kind === "completionNotice"
        ? "notice"
        : row.rowKind.kind === "user"
          ? "user"
          : "text",
    );
    expect(kinds).toEqual(["user", "notice", "text"]);
  });
});

describe("the notice side-row helpers", () => {
  it("shortens an id to eight characters without splitting a character", () => {
    expect(shortChatId("chat-side-1234")).toBe("chat-sid");
    expect(shortChatId("short")).toBe("short");
    // A surrogate pair never splits.
    const wide = "😀😀😀😀😀😀😀😀😀";
    expect(shortChatId(wide).length).toBeLessThanOrEqual(16);
    expect(Array.from(shortChatId(wide))).toHaveLength(8);
  });

  it("maps every outcome to a verb, matching the notice's own text", () => {
    expect(noticeOutcomeVerb(side("c", "t", "completed"))).toBe("finished");
    expect(noticeOutcomeVerb(side("c", "t", "errored"))).toBe("failed");
    expect(noticeOutcomeVerb(side("c", "t", "stopped"))).toBe("was stopped");
    expect(noticeOutcomeVerb(side("c", "t", "interrupted"))).toBe("was interrupted");
    expect(noticeOutcomeVerb(side("c", "t", "waitingForInput"))).toBe("is waiting for input");
  });

  it("appends the one-line reason, and a blank reason reads as none", () => {
    expect(noticeOutcomeLine(side("c", "t", "errored", "provider 502: bad gateway"))).toBe(
      "failed: provider 502: bad gateway",
    );
    expect(noticeOutcomeLine(side("c", "t", "completed"))).toBe("finished");
    expect(noticeOutcomeLine(side("c", "t", "errored", "   "))).toBe("failed");
    // The title is one-lined for the card, like the fork seam's source title.
    expect(singleLine("A title\nthat wraps")).toBe("A title that wraps");
  });
});

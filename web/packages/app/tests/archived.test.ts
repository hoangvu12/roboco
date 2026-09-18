import { describe, expect, it } from "vitest";
import type { Chat, Device } from "@roboco/proto";
import { archivedChats, chatLocation } from "../src/lib/archived";
import { archivedRows } from "../src/lib/view";

const NOW = 1_800_000_000_000;

function chat(fields: Partial<Chat>): Chat {
  return {
    id: "chat",
    deviceId: "dev-1",
    title: null,
    archived: true,
    cwd: null,
    branch: null,
    checkoutId: null,
    sourceContext: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: new Date(NOW - 3_600_000).toISOString(),
    createdAt: new Date(NOW - 86_400_000).toISOString(),
    harnessSessionId: null,
    harnessSessionCwd: null,
    spaceId: null,
    lastSeenAt: null,
    roomGen: null,
    ...fields,
  };
}

function device(id: string, name: string): Device {
  return { id, name, platform: "windows", lastSeenAt: null, createdAt: null };
}

describe("archivedChats (archived.rs:15-17, page-shaped)", () => {
  it("shows every archived chat, unscoped by the sidebar's space filter", () => {
    const rows = archivedChats(
      [
        chat({ id: "a", archived: false }),
        chat({ id: "b", spaceId: "space-1" }),
        chat({ id: "c", spaceId: "space-2" }),
      ],
      [],
      NOW,
    );
    // The shelf (archivedRows) would scope to one space's rows; the page
    // shows both — same comparator, no filter.
    expect(rows.map((row) => row.chat.id)).toEqual(["b", "c"]);
    expect(archivedRows([chat({ id: "b", spaceId: "space-1" }), chat({ id: "c", spaceId: "space-2" })], "space-1", NOW).map((r) => r.chat.id)).toEqual(["b"]);
  });

  it("carries the fuller per-row content the page renders", () => {
    const rows = archivedChats(
      [chat({ id: "b", title: "  Fix the parser  ", cwd: "/home/u/repo", branch: "main" })],
      [device("dev-1", "Studio desktop")],
      NOW,
    );
    expect(rows[0]!.title).toBe("  Fix the parser  ");
    expect(rows[0]!.device).toBe("Studio desktop");
    expect(rows[0]!.location).toBe("repo · main");
    expect(rows[0]!.timeAgo).toBe("1h");
  });

  it("falls back to Untitled session and omits unknown devices entirely", () => {
    const rows = archivedChats([chat({ title: null })], [], NOW);
    expect(rows[0]!.title).toBe("Untitled session");
    expect(rows[0]!.device).toBe(null);
    expect(rows[0]!.location).toBe(null);
  });

  it("sorts in the sidebar's recency order (lastUpdated)", () => {
    const older = chat({ id: "older", lastMessageAt: new Date(NOW - 10 * 3_600_000).toISOString() });
    const newer = chat({ id: "newer", lastMessageAt: new Date(NOW - 1_000).toISOString() });
    expect(archivedChats([older, newer], [], NOW).map((row) => row.chat.id)).toEqual(["newer", "older"]);
    expect(archivedChats([older, newer], [], NOW, "created").map((row) => row.chat.id)).toEqual(["newer", "older"]);
  });
});

describe("chatLocation (proto view.rs:252-270)", () => {
  it("joins project and branch, either alone, or neither", () => {
    expect(chatLocation(chat({ cwd: "/home/u/repo", branch: "main" }))).toBe("repo · main");
    expect(chatLocation(chat({ cwd: "/home/u/repo" }))).toBe("repo");
    expect(chatLocation(chat({ branch: "main" }))).toBe("main");
    expect(chatLocation(chat({}))).toBe(null);
    expect(chatLocation(chat({ cwd: "~", branch: "  " }))).toBe(null);
  });
});

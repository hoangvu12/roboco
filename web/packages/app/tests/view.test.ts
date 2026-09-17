import { describe, expect, it } from "vitest";
import type { Chat, Space } from "@roboco/proto";
import type { ChatStatus } from "@roboco/engine-client";
import {
  attentionRank,
  chatIndicator,
  chatListRows,
  displayStatus,
  effectiveIndicator,
  mostUrgent,
  projectLabel,
  sortRows,
  spaceDisplayName,
  statusWord,
  timeAgo,
  unseen,
  type ChatRow,
} from "../src/lib/view";

const NOW = Date.parse("2026-09-16T12:00:00Z");

function chat(fields: Partial<Chat>): Chat {
  return {
    id: "chat",
    deviceId: "device-1",
    title: null,
    archived: false,
    cwd: null,
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-09-16T10:00:00Z",
    ...fields,
  };
}

function status(fields: Partial<ChatStatus>): ChatStatus {
  return {
    chatId: "chat",
    deviceId: "device-1",
    status: "working",
    startedAt: null,
    updatedAt: "2026-09-16T11:59:30Z",
    lastCompletedTurn: null,
    ...fields,
  };
}

describe("unseen", () => {
  it("is true when the last message is newer than the seen marker", () => {
    expect(unseen(chat({ lastMessageAt: "2026-09-16T11:00:00Z", lastSeenAt: "2026-09-16T10:00:00Z" }))).toBe(true);
  });

  it("is true with activity and no seen marker", () => {
    expect(unseen(chat({ lastMessageAt: "2026-09-16T11:00:00Z" }))).toBe(true);
  });

  it("is false without activity or once seen", () => {
    expect(unseen(chat({}))).toBe(false);
    expect(unseen(chat({ lastMessageAt: "2026-09-16T11:00:00Z", lastSeenAt: "2026-09-16T11:00:00Z" }))).toBe(false);
    expect(unseen(chat({ lastMessageAt: "2026-09-16T11:00:00Z", lastSeenAt: "2026-09-16T11:30:00Z" }))).toBe(false);
  });
});

describe("effectiveIndicator", () => {
  it("carries working and awaiting input inside the staleness window", () => {
    expect(effectiveIndicator(status({ status: "working" }), NOW)).toBe("working");
    expect(effectiveIndicator(status({ status: "awaitingInput" }), NOW)).toBe("awaitingInput");
  });

  it("treats a session older than 45s as dead", () => {
    const stale = status({ status: "working", updatedAt: "2026-09-16T11:59:00Z" });
    expect(effectiveIndicator(stale, NOW)).toBe("none");
    expect(effectiveIndicator(status({ status: "awaitingInput", updatedAt: "2026-09-16T11:59:00Z" }), NOW)).toBe("none");
  });

  it("keeps errored and drops idle", () => {
    expect(effectiveIndicator(status({ status: "errored", updatedAt: "2026-09-16T08:00:00Z" }), NOW)).toBe("errored");
    expect(effectiveIndicator(status({ status: "idle" }), NOW)).toBe("none");
    expect(effectiveIndicator(undefined, NOW)).toBe("none");
  });
});

describe("chatIndicator", () => {
  it("prefers the live states", () => {
    expect(chatIndicator(chat({}), status({ status: "working" }))).toBe("working");
    expect(chatIndicator(chat({}), status({ status: "awaitingInput" }))).toBe("awaitingInput");
  });

  it("reads errored only while unseen, then falls back to completed", () => {
    const errored = status({ status: "errored" });
    expect(chatIndicator(chat({ lastMessageAt: "2026-09-16T11:00:00Z" }), errored)).toBe("errored");
    expect(chatIndicator(chat({ lastMessageAt: "2026-09-16T11:00:00Z", lastSeenAt: "2026-09-16T11:30:00Z" }), errored)).toBe("idle");
  });

  it("maps unseen to completed and everything else to idle", () => {
    expect(chatIndicator(chat({ lastMessageAt: "2026-09-16T11:00:00Z" }), undefined)).toBe("completed");
    expect(chatIndicator(chat({ lastSeenAt: "2026-09-16T11:00:00Z" }), undefined)).toBe("idle");
  });
});

describe("displayStatus", () => {
  it("drops a stale working session back to the seen-marker derivation", () => {
    const staleWorking = chat({ lastMessageAt: "2026-09-16T11:00:00Z" });
    const session = status({ status: "working", updatedAt: "2026-09-16T11:00:00Z" });
    expect(displayStatus(staleWorking, session, NOW)).toBe("completed");
  });

  it("keeps a fresh working session working", () => {
    expect(displayStatus(chat({}), status({ status: "working" }), NOW)).toBe("working");
  });
});

describe("attentionRank", () => {
  it("matches the desktop buckets — lower is more urgent", () => {
    expect(attentionRank("awaitingInput")).toBeLessThan(attentionRank("errored"));
    expect(attentionRank("errored")).toBeLessThan(attentionRank("working"));
    expect(attentionRank("working")).toBeLessThan(attentionRank("completed"));
    expect(attentionRank("completed")).toBeLessThan(attentionRank("idle"));
  });

  it("mostUrgent picks the min-rank status", () => {
    expect(mostUrgent(["idle", "completed", "working"])).toBe("working");
    expect(mostUrgent(["idle", "errored", "awaitingInput"])).toBe("awaitingInput");
    expect(mostUrgent(["idle", "idle"])).toBe("idle");
    expect(mostUrgent([])).toBe(null);
  });
});

describe("statusWord", () => {
  it("mirrors the desktop corner words", () => {
    expect(statusWord("working")).toBe("Working");
    expect(statusWord("awaitingInput")).toBe("Input");
    expect(statusWord("errored")).toBe("Failed");
    expect(statusWord("completed")).toBe("Done");
    expect(statusWord("idle")).toBe(null);
  });
});

describe("sortRows", () => {
  it("orders by lastMessageAt desc with createdAt fallback and id tiebreak", () => {
    const rows = [
      { chat: chat({ id: "a", createdAt: "2026-09-16T09:00:00Z" }) },
      { chat: chat({ id: "b", createdAt: "2026-09-16T10:00:00Z" }) },
      { chat: chat({ id: "c", createdAt: "2026-09-16T08:00:00Z", lastMessageAt: "2026-09-16T11:30:00Z" }) },
    ];
    expect(sortRows(rows).map((row) => row.chat.id)).toEqual(["c", "b", "a"]);
  });

  it("breaks full ties by id ascending", () => {
    const rows = [
      { chat: chat({ id: "z", createdAt: "2026-09-16T09:00:00Z" }) },
      { chat: chat({ id: "a", createdAt: "2026-09-16T09:00:00Z" }) },
    ];
    expect(sortRows(rows).map((row) => row.chat.id)).toEqual(["a", "z"]);
  });
});

describe("timeAgo", () => {
  it("buckets like the desktop's format_time_ago", () => {
    expect(timeAgo("2026-09-16T11:59:40Z", NOW)).toBe("now");
    expect(timeAgo("2026-09-16T11:55:00Z", NOW)).toBe("5m");
    expect(timeAgo("2026-09-16T09:00:00Z", NOW)).toBe("3h");
    expect(timeAgo("2026-09-14T12:00:00Z", NOW)).toBe("2d");
    expect(timeAgo("2026-09-09T12:00:00Z", NOW)).toBe("1w");
    expect(timeAgo("2026-08-19T12:00:00Z", NOW)).toBe("4w");
    expect(timeAgo("2026-08-01T12:00:00Z", NOW)).toBe("1mo");
    expect(timeAgo("2024-09-16T12:00:00Z", NOW)).toBe("2y");
  });

  it("clamps future timestamps to now", () => {
    expect(timeAgo("2026-09-16T12:00:30Z", NOW)).toBe("now");
  });
});

describe("projectLabel and spaceDisplayName", () => {
  it("labels from the cwd basename, treating home as unlabeled", () => {
    expect(projectLabel("C:\\dev\\roboco")).toBe("roboco");
    expect(projectLabel("/home/me/project")).toBe("project");
    expect(projectLabel("~")).toBe(null);
    expect(projectLabel(null)).toBe(null);
  });

  it("prefers the rename, then the folder basename", () => {
    expect(spaceDisplayName({ id: "s", deviceId: "d", path: "/srv/app", name: "App", gitDetected: false, createdAt: "2026-01-01T00:00:00Z" })).toBe("App");
    expect(spaceDisplayName({ id: "s", deviceId: "d", path: "/srv/app", name: null, gitDetected: false, createdAt: "2026-01-01T00:00:00Z" })).toBe("app");
  });
});

describe("chatListRows", () => {
  const space = (id: string, name: string | null): Space => ({
    id,
    deviceId: "device-1",
    path: `/srv/${id}`,
    name,
    gitDetected: false,
    createdAt: "2026-01-01T00:00:00Z",
  });

  it("streams live statuses onto the rows and keeps recency order", () => {
    const chats = [
      chat({ id: "fresh", lastMessageAt: "2026-09-16T11:00:00Z" }),
      chat({ id: "busy", lastMessageAt: "2026-09-16T11:30:00Z" }),
      chat({ id: "old", createdAt: "2026-09-16T09:00:00Z" }),
    ];
    const statuses = [
      status({ chatId: "busy", status: "working", updatedAt: "2026-09-16T11:59:30Z" }),
      status({ chatId: "fresh", status: "awaitingInput", updatedAt: "2026-09-16T11:59:30Z" }),
    ];
    const rows = chatListRows(chats, [], statuses, NOW);
    expect(rows.map((row) => row.chat.id)).toEqual(["busy", "fresh", "old"]);
    expect(rows.map((row) => row.status)).toEqual(["working", "awaitingInput", "idle"]);
  });

  it("hides archived chats and chats of unknown spaces, keeps project-less ones", () => {
    const chats = [
      chat({ id: "gone", archived: true }),
      chat({ id: "dangling", spaceId: "missing" }),
      chat({ id: "loose", spaceId: null }),
      chat({ id: "spaced", spaceId: "s1" }),
    ];
    const rows = chatListRows(chats, [space("s1", "Engine work")], [], NOW);
    expect(rows.map((row) => row.chat.id)).toEqual(["loose", "spaced"]);
    expect(rows[1]!.project).toBe("Engine work");
  });

  it("labels project-less rows ~ and stamps branches from the source context", () => {
    const rows = chatListRows(
      [
        chat({
          id: "a",
          cwd: "/home/me/roboco",
          branch: "legacy-scalar",
          sourceContext: { checkoutId: "c1", repoRoot: "/home/me/roboco", cwd: "/home/me/roboco", branch: "feat/web", observedAt: "2026-09-16T10:00:00Z" },
        }),
        chat({ id: "b", cwd: "~", branch: null }),
      ],
      [],
      [],
      NOW,
    );
    // Project-less sessions read as "~" (spaces.rs:1387), and only the
    // conversation-owned source context's branch counts — the legacy scalar
    // cannot prove a worktree has not switched since it was written.
    expect(rows[0]!.project).toBe("~");
    expect(rows[0]!.branch).toBe("feat/web");
    expect(rows[1]!.project).toBe("~");
    expect(rows[1]!.branch).toBe(null);
  });

  it("marks unseen chats completed and seen ones idle", () => {
    const rows = chatListRows(
      [chat({ id: "unseen", lastMessageAt: "2026-09-16T11:00:00Z" }), chat({ id: "seen", lastMessageAt: "2026-09-16T11:00:00Z", lastSeenAt: "2026-09-16T11:30:00Z" })],
      [],
      [],
      NOW,
    ) as ChatRow[];
    expect(rows.find((row) => row.chat.id === "unseen")!.status).toBe("completed");
    expect(rows.find((row) => row.chat.id === "seen")!.status).toBe("idle");
  });
});

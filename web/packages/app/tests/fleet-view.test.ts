import { describe, expect, it } from "vitest";
import type { Chat, Device, Space } from "@roboco/proto";
import type { ChatStatus } from "@roboco/engine-client";
import { encodeScopedId, projectRegistrySnapshot } from "@roboco/engine-client";
import { fleetEngine, fleetRegistry } from "./helpers/fleet-fixtures";
import { mergedFleetSnapshot } from "../src/state/fleet";
import {
  archivedRows,
  chatPageRow,
  fleetSpaceRows,
  healedSpaceFilter,
  singleLine,
  spacesSorted,
} from "../src/lib/view";

const NOW = Date.parse("2026-09-16T12:00:00Z");

describe("fleetSpaceRows", () => {
  const OVH = "http://ovh.local";
  const THREADRIPPER = "http://threadripper.local";

  it("shows a shared project only on its host engine, leaving distinct projects intact", () => {
    // The upstream case (zeron `5cd23bd7`), adapted to roboco's fleet keys:
    // two engines in one synced workspace, each advertising BOTH projects,
    // the `deviceId` field naming the owning device. `useFleetSnapshot()`
    // renders exactly these rows — one per project, on the owner's engine.
    const ovh = { ...space("shared", "OVH project"), deviceId: "ovh" };
    const threadripper = { ...space("local", "Threadripper project"), deviceId: "threadripper" };
    const registry = fleetRegistry(
      fleetEngine(OVH, "ovh", { spaces: [ovh, threadripper] }),
      fleetEngine(THREADRIPPER, "threadripper", { spaces: [ovh, threadripper] }),
    );
    const projected = projectRegistrySnapshot(registry);
    expect(projected.spaces).toHaveLength(4);
    expect(fleetSpaceRows(registry, projected.spaces).map((row) => row.id)).toEqual([
      encodeScopedId(OVH, "shared"),
      encodeScopedId(THREADRIPPER, "local"),
    ]);
  });

  it("keeps a row whose engine host is not known yet — seeded offline rows must not blank projects", () => {
    // An engine entry starts `info: null` and seeds its offline cache
    // before the first handshake (registry.ts `#seed`), so rows can exist
    // while the host device id is still unknown. The mirror cannot be
    // proven then; a project shown twice beats a project vanished.
    const owned = { ...space("seeded", "Seeded project"), deviceId: "ovh" };
    const registry = fleetRegistry(fleetEngine(OVH, null, { spaces: [owned] }));
    const projected = projectRegistrySnapshot(registry);
    expect(fleetSpaceRows(registry, projected.spaces)).toHaveLength(1);
  });

  it("keeps unscoped rows verbatim — a lone engine's ids carry no scope to check", () => {
    const registry = fleetRegistry(fleetEngine(OVH, "ovh", {}));
    const rows = [space("bare", "Bare project")];
    expect(fleetSpaceRows(registry, rows)).toEqual(rows);
  });

  it("keeps rows with malformed scoped ids rather than blanking them", () => {
    // A corrupted id must not blank a project or crash the sidebar — the
    // defensive `catch` arm of the filter (upstream has no equivalent;
    // roboco's ids are client-minted, so the arm exists).
    const ovh = { ...space("shared", "OVH project"), deviceId: "ovh" };
    const registry = fleetRegistry(fleetEngine(OVH, "ovh", {}));
    const corrupted = { ...ovh, id: "engine:v1:!!!" };
    expect(fleetSpaceRows(registry, [corrupted])).toEqual([corrupted]);
  });
});

describe("mergedFleetSnapshot (useFleetSnapshot's merge core)", () => {
  const OVH = "http://ovh.local";
  const THREADRIPPER = "http://threadripper.local";

  it("renders one space row per project and one device row per engine host from a mirrored workspace", () => {
    // Ticket 87's acceptance, driven through the exact merge the
    // `useFleetSnapshot()` memo runs: both engines mirror the whole
    // workspace (both projects, both devices); the merged snapshot still
    // lists each project once (on its owner's engine) and each engine
    // host's device row once.
    const ovh = { ...space("shared", "OVH project"), deviceId: "dev-ovh" };
    const local = { ...space("local", "Threadripper project"), deviceId: "dev-thread" };
    const devices = [device("dev-ovh", "OVH build server"), device("dev-thread", "Threadripper")];
    const registry = fleetRegistry(
      fleetEngine(OVH, "dev-ovh", { spaces: [ovh, local], devices }),
      fleetEngine(THREADRIPPER, "dev-thread", { spaces: [ovh, local], devices }),
    );
    const snapshot = mergedFleetSnapshot(registry, OVH);
    expect(snapshot.spaces.rows.map((row) => row.id)).toEqual([
      encodeScopedId(OVH, "shared"),
      encodeScopedId(THREADRIPPER, "local"),
    ]);
    expect(snapshot.devices.rows.map((row) => row.id)).toEqual([
      encodeScopedId(OVH, "dev-ovh"),
      encodeScopedId(THREADRIPPER, "dev-thread"),
    ]);
  });
});

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

function space(id: string, name: string | null): Space {
  return {
    id,
    deviceId: "device-1",
    path: `/srv/${id}`,
    name,
    gitDetected: false,
    createdAt: "2026-01-01T00:00:00Z",
  };
}

function device(id: string, name: string): Device {
  return { id, name, platform: "linux", lastSeenAt: null, createdAt: null };
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

describe("singleLine", () => {
  it("collapses all whitespace runs like the desktop", () => {
    expect(singleLine("a\nb")).toBe("a b");
    expect(singleLine("  a\t\t b \r\n c  ")).toBe("a b c");
    expect(singleLine("plain")).toBe("plain");
    expect(singleLine("")).toBe("");
    expect(singleLine("\n\n")).toBe("");
  });
});

describe("spacesSorted", () => {
  it("orders case-insensitively by display name with an id tiebreak", () => {
    const spaces = [space("b", "Beta"), space("a", null), space("c", "alpha"), space("d", "Alpha")];
    // display names: Beta, b (basename of /srv/b... no — id "a" → path /srv/a → "a"), alpha, Alpha
    const sorted = spacesSorted(spaces);
    expect(sorted.map((row) => row.id)).toEqual(["a", "c", "d", "b"]);
  });

  it("breaks display-name ties by id", () => {
    const spaces = [space("z", "Same"), space("y", "Same")];
    expect(spacesSorted(spaces).map((row) => row.id)).toEqual(["y", "z"]);
  });
});

describe("healedSpaceFilter", () => {
  it("keeps a live filter and heals a dangling one to All projects", () => {
    const spaces = [space("s1", null)];
    expect(healedSpaceFilter("s1", spaces)).toBe("s1");
    expect(healedSpaceFilter("gone", spaces)).toBe(null);
    expect(healedSpaceFilter(null, spaces)).toBe(null);
  });
});

describe("archivedRows", () => {
  it("keeps only archived chats, under the filter scoped to that space", () => {
    const chats = [
      chat({ id: "live", spaceId: "s1" }),
      chat({ id: "arch", archived: true, spaceId: "s1" }),
      chat({ id: "arch-other", archived: true, spaceId: "s2" }),
      chat({ id: "arch-loose", archived: true, spaceId: null }),
    ];
    expect(archivedRows(chats, null, NOW).map((row) => row.chat.id).sort()).toEqual(["arch", "arch-loose", "arch-other"]);
    expect(archivedRows(chats, "s2", NOW).map((row) => row.chat.id)).toEqual(["arch-other"]);
  });

  it("orders by recency with the createdAt and id tiebreaks (sort_chats)", () => {
    const chats = [
      chat({ id: "b", archived: true, createdAt: "2026-09-16T09:00:00Z" }),
      chat({ id: "a", archived: true, createdAt: "2026-09-16T09:00:00Z" }),
      chat({ id: "fresh", archived: true, lastMessageAt: "2026-09-16T11:00:00Z" }),
      chat({ id: "newer-created", archived: true, createdAt: "2026-09-16T10:00:00Z" }),
    ];
    expect(archivedRows(chats, null, NOW).map((row) => row.chat.id)).toEqual(["fresh", "newer-created", "a", "b"]);
  });

  it("single-lines titles, falls back to New session, and stamps time-ago", () => {
    const rows = archivedRows(
      [
        chat({ id: "t", archived: true, title: "  multi\nline\ttitle  " }),
        chat({ id: "u", archived: true, title: null, createdAt: "2026-09-16T11:00:00Z" }),
      ],
      null,
      NOW,
    );
    const titled = rows.find((row) => row.chat.id === "t")!;
    expect(titled.title).toBe("multi line title");
    const untitled = rows.find((row) => row.chat.id === "u")!;
    expect(untitled.title).toBe("New session");
    expect(untitled.timeAgo).toBe("1h");
  });
});

describe("chatPageRow", () => {
  it("finds any chat by id, archived included", () => {
    const chats = [
      chat({ id: "live", title: "Live" }),
      chat({ id: "arch", title: "Old", archived: true, spaceId: "s1" }),
    ];
    const spaces = [space("s1", "Engine work")];
    const row = chatPageRow("arch", chats, spaces, [], NOW);
    expect(row?.chat.title).toBe("Old");
    expect(row?.chat.archived).toBe(true);
    expect(row?.project).toBe("Engine work");
  });

  it("returns undefined for unknown ids and dangling spaces", () => {
    const chats = [chat({ id: "dangling", spaceId: "gone" })];
    expect(chatPageRow("missing", chats, [], [], NOW)).toBe(undefined);
    expect(chatPageRow("dangling", chats, [], [], NOW)).toBe(undefined);
  });

  it("derives the live status like the sidebar rows", () => {
    const chats = [chat({ id: "busy" })];
    const statuses = [status({ chatId: "busy", status: "working", updatedAt: "2026-09-16T11:59:30Z" })];
    expect(chatPageRow("busy", chats, [], statuses, NOW)?.status).toBe("working");
  });
});

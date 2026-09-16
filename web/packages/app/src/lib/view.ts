import type { Chat, Space } from "@roboco/proto";
import type { ChatStatus } from "@roboco/engine-client";

/**
 * The desktop's view derivations, ported 1:1 from `roboco_proto::view` and
 * `roboco_proto::entities::chat_indicator` (crates/proto/src/view.rs) so the
 * sidebar reads identically on both surfaces: same status dots, same
 * recency order, same attention buckets. Pure; unit-tested against the
 * Rust cases.
 */

export const SESSION_STALE_MS = 45_000;

export type ChatIndicator = "working" | "awaitingInput" | "errored" | "completed" | "idle";

/** The live status dot: none | working | awaitingInput | errored. */
export type Indicator = "none" | "working" | "awaitingInput" | "errored";

/** One sidebar row, ready to draw: statuses, project line, branch, time. */
export interface ChatRow {
  readonly chat: Chat;
  readonly status: ChatIndicator;
  /** Line 1 left — the space's display name, or the cwd label, or "~". */
  readonly project: string;
  /** Line 3 — the stamped branch, when present. */
  readonly branch: string | null;
  /** The corner's relative time, shown while idle. */
  readonly timeAgo: string;
}

/** The corner's status word, mirroring the desktop (Idle shows time-ago). */
export function statusWord(status: ChatIndicator): string | null {
  switch (status) {
    case "working":
      return "Working";
    case "awaitingInput":
      return "Input";
    case "errored":
      return "Failed";
    case "completed":
      return "Done";
    case "idle":
      return null;
  }
}

/** True when a chat has activity the user hasn't seen on any device. */
export function unseen(chat: Chat): boolean {
  const message = chat.lastMessageAt;
  if (message === null || message === undefined) {
    return false;
  }
  const seen = chat.lastSeenAt;
  return seen === null || seen === undefined || compareIso(message, seen) > 0;
}

/**
 * Staleness-checked indicator: a Working/AwaitingInput session row older
 * than SESSION_STALE_MS is dead — a crashed backend must never show an
 * eternal "Working". Errored is exempt; Idle is none.
 */
export function effectiveIndicator(session: ChatStatus | undefined, now: number): Indicator {
  if (session === undefined) {
    return "none";
  }
  switch (session.status) {
    case "idle":
      return "none";
    case "errored":
      return "errored";
    case "working":
    case "awaitingInput": {
      const updated = Date.parse(session.updatedAt);
      if (!Number.isFinite(updated) || now - updated > SESSION_STALE_MS) {
        return "none";
      }
      return session.status;
    }
  }
}

/** chat_indicator: live states win, then the seen marker decides. */
export function chatIndicator(chat: Chat, live: ChatStatus | undefined): ChatIndicator {
  switch (live?.status) {
    case "working":
      return "working";
    case "awaitingInput":
      return "awaitingInput";
    case "errored":
      return unseen(chat) ? "errored" : "idle";
    default:
      return unseen(chat) ? "completed" : "idle";
  }
}

/** The full display status for a chat row: live, staleness-gated, derived. */
export function displayStatus(chat: Chat, session: ChatStatus | undefined, now: number): ChatIndicator {
  const live = session !== undefined && effectiveIndicator(session, now) !== "none" ? session : undefined;
  return chatIndicator(chat, live);
}

/** Attention bucket — lower is more urgent (view.rs attention_rank). */
export function attentionRank(status: ChatIndicator): number {
  switch (status) {
    case "awaitingInput":
      return 0;
    case "errored":
      return 1;
    case "working":
      return 2;
    case "completed":
      return 3;
    case "idle":
      return 4;
  }
}

/**
 * The most attention-demanding status among rows (min rank) — the same
 * aggregation the desktop's space rows use for their urgency dot.
 */
export function mostUrgent(statuses: readonly ChatIndicator[]): ChatIndicator | null {
  let best: ChatIndicator | null = null;
  let bestRank = Number.POSITIVE_INFINITY;
  for (const status of statuses) {
    const rank = attentionRank(status);
    if (rank < bestRank) {
      best = status;
      bestRank = rank;
    }
  }
  return best;
}

function recencyKey(chat: Chat): string {
  return chat.lastMessageAt ?? chat.createdAt;
}

/**
 * Sidebar order (sort_active): pure recency — `lastMessageAt` desc with
 * `createdAt` fallback, id tiebreak so the sort is total. Status drives
 * the dot, never the position.
 */
export function sortRows<T extends { chat: Chat }>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => {
    const byRecency = compareIso(recencyKey(b.chat), recencyKey(a.chat));
    if (byRecency !== 0) {
      return byRecency;
    }
    return a.chat.id < b.chat.id ? -1 : a.chat.id > b.chat.id ? 1 : 0;
  });
}

/**
 * The sidebar's chat list (overview_chats): every non-archived chat of a
 * live space — or no space at all — idle included, display statuses and
 * project lines attached, in pure recency order. Chats whose spaceId
 * points at a missing space row stay hidden.
 */
export function chatListRows(
  chats: readonly Chat[],
  spaces: readonly Space[],
  statuses: readonly ChatStatus[],
  now: number,
): ChatRow[] {
  const statusByChat = new Map(statuses.map((row) => [row.chatId, row]));
  const spaceById = new Map(spaces.map((space) => [space.id, space]));
  const rows: ChatRow[] = [];
  for (const chat of chats) {
    if (chat.archived) {
      continue;
    }
    const row = toChatRow(chat, spaceById, statusByChat, now);
    if (row !== null) {
      rows.push(row);
    }
  }
  return sortRows(rows);
}

/**
 * One chat's row by id — the chat page's lookup. Unlike the sidebar list
 * this includes archived chats (archiving never closes an open chat);
 * chats whose spaceId dangles stay hidden, exactly as in the list.
 */
export function chatPageRow(
  chatId: string,
  chats: readonly Chat[],
  spaces: readonly Space[],
  statuses: readonly ChatStatus[],
  now: number,
): ChatRow | undefined {
  const chat = chats.find((candidate) => candidate.id === chatId);
  if (chat === undefined) {
    return undefined;
  }
  const spaceById = new Map(spaces.map((space) => [space.id, space]));
  const statusByChat = new Map(statuses.map((row) => [row.chatId, row]));
  return toChatRow(chat, spaceById, statusByChat, now) ?? undefined;
}

function toChatRow(
  chat: Chat,
  spaceById: ReadonlyMap<string, Space>,
  statusByChat: ReadonlyMap<string, ChatStatus>,
  now: number,
): ChatRow | null {
  const space =
    chat.spaceId !== null && chat.spaceId !== undefined ? spaceById.get(chat.spaceId) : undefined;
  if (chat.spaceId !== null && chat.spaceId !== undefined && space === undefined) {
    return null;
  }
  const branch = chat.branch !== null && chat.branch !== undefined && chat.branch.trim().length > 0 ? chat.branch : null;
  return {
    chat,
    status: displayStatus(chat, statusByChat.get(chat.id), now),
    project: space !== undefined ? spaceDisplayName(space) : projectLabel(chat.cwd) ?? "~",
    branch,
    timeAgo: timeAgo(recencyKey(chat), now),
  };
}

/** Display name of a space: the rename, else the folder basename. */
export function spaceDisplayName(space: Space): string {
  const name = space.name;
  if (name !== null && name !== undefined && name.trim().length > 0) {
    return name;
  }
  return basename(space.path) ?? space.path;
}

/**
 * Spaces in display order — case-insensitive display name, id tiebreak
 * (state.rs spaces_sorted). The order both space selectors list rows in.
 */
export function spacesSorted(spaces: readonly Space[]): Space[] {
  return [...spaces].sort((a, b) => {
    const an = spaceDisplayName(a).toLowerCase();
    const bn = spaceDisplayName(b).toLowerCase();
    if (an !== bn) {
      return an < bn ? -1 : 1;
    }
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

/**
 * Dangling-filter healing (shell.rs): a filter naming a space that no
 * longer exists — deleted, or from another engine after a switch — reads
 * as "All projects" rather than filtering everything out.
 */
export function healedSpaceFilter(filter: string | null, spaces: readonly Space[]): string | null {
  if (filter === null) {
    return null;
  }
  return spaces.some((space) => space.id === filter) ? filter : null;
}

/** Whitespace-collapsed single line (proto view::single_line). */
export function singleLine(text: string): string {
  return text
    .split(/\s+/)
    .filter((part) => part.length > 0)
    .join(" ");
}

/** One archived-shelf row: single-line title + relative time. */
export interface ArchivedRow {
  readonly chat: Chat;
  readonly title: string;
  readonly timeAgo: string;
}

/**
 * The sidebar's archived shelf (render_archived_section): archived chats of
 * the filter scope — all spaces under "All" — in recency order
 * (view.rs sort_chats: recency desc, createdAt desc tiebreak, id last).
 */
export function archivedRows(chats: readonly Chat[], spaceFilter: string | null, now: number): ArchivedRow[] {
  const rows = chats.filter(
    (chat) =>
      chat.archived &&
      (spaceFilter === null || (chat.spaceId !== undefined && chat.spaceId === spaceFilter)),
  );
  rows.sort((a, b) => {
    const byRecency = compareIso(recencyKey(b), recencyKey(a));
    if (byRecency !== 0) {
      return byRecency;
    }
    const byCreated = compareIso(b.createdAt, a.createdAt);
    if (byCreated !== 0) {
      return byCreated;
    }
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
  return rows.map((chat) => {
    const title = chat.title === null ? "" : singleLine(chat.title);
    return {
      chat,
      title: title.length > 0 ? title : "New session",
      timeAgo: timeAgo(recencyKey(chat), now),
    };
  });
}

/** Project label from a cwd (project_label): its basename, or null. */
export function projectLabel(cwd: string | null | undefined): string | null {
  const trimmed = cwd?.trim();
  if (trimmed === undefined || trimmed.length === 0 || trimmed === "~" || trimmed === "~/") {
    return null;
  }
  return basename(trimmed) ?? trimmed;
}

function basename(path: string): string | null {
  const withoutTrailing = path.replace(/[\\/]+$/, "");
  const slash = Math.max(withoutTrailing.lastIndexOf("/"), withoutTrailing.lastIndexOf("\\"));
  const name = slash >= 0 ? withoutTrailing.slice(slash + 1) : withoutTrailing;
  return name.length > 0 ? name : null;
}

/**
 * Compact relative time — "now", "5m", "3h", "2d", "1w", "2mo", "1y" —
 * port of view.rs format_time_ago (no "ago" suffix; negative ages clamp
 * to "now").
 */
export function timeAgo(iso: string, now: number): string {
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) {
    return "";
  }
  const seconds = Math.max(0, Math.floor((now - then) / 1000));
  if (seconds < 60) {
    return "now";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h`;
  }
  const days = Math.floor(hours / 24);
  if (days < 7) {
    return `${days}d`;
  }
  const weeks = Math.floor(days / 7);
  if (weeks < 5) {
    return `${weeks}w`;
  }
  const months = Math.floor(days / 30);
  if (months < 12) {
    return `${months}mo`;
  }
  return `${Math.floor(days / 365)}y`;
}

/**
 * Chronological comparison of the engine's RFC 3339 timestamps: epoch-ms
 * first, lexicographic fallback for sub-millisecond ties.
 */
function compareIso(a: string, b: string): number {
  const ta = Date.parse(a);
  const tb = Date.parse(b);
  if (!Number.isFinite(ta) || !Number.isFinite(tb)) {
    return a === b ? 0 : a < b ? -1 : 1;
  }
  if (ta !== tb) {
    return ta - tb;
  }
  return a === b ? 0 : a < b ? -1 : 1;
}

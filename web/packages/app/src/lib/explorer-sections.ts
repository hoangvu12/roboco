import type { ChangeRequestSummary, Chat, SessionMessageEntry } from "@roboco/proto";
import type { ChatStatus } from "@roboco/engine-client";
import { displayStatus, timeAgo, type ChatIndicator } from "./view";
import { isSubagentSpawn, subagentTabTitle } from "./transcript";

/**
 * The explorer footer's pure model — the web port of
 * `crates/ui/src/files/sections.rs` (731697b6). Two collapsible sections
 * docked under the file tree:
 *
 * - **Subagents** — the spawn chips of the active chat's transcript, with
 *   their live status. The chip IS the index: only genuine spawn calls with
 *   a stamped doc ref qualify (a stray ref on a non-Agent tool must not
 *   surface as a phantom subagent).
 * - **Chats** — the side chats hanging off the active chat: forks, and
 *   chats an agent spawned through the Roboco MCP server. Live (unarchived)
 *   children, most recent activity first.
 *
 * The height math (`contentHeight`, `bodyBudget`) is pure and
 * unit-testable; the component (`components/files/explorer-sections.tsx`)
 * owns only the open/shown/scroll state and the rows' chrome.
 */

// ── The desktop's constants (sections.rs:38-71) ─────────────────────────────

/** `SECTION_HEADER_HEIGHT` — a section header's height. */
export const SECTION_HEADER_HEIGHT = 28;
/** `SECTION_BODY_INSET` — the list's top padding inside the body. */
export const SECTION_BODY_INSET = 4;
/** `ROW_HEIGHT` — the compact row height (the sidebar's session row). */
export const ROW_HEIGHT = 29;
/** `ROW_GAP` — the gap between rows (the sidebar's list gap). */
export const ROW_GAP = 2;
/** `EMPTY_COPY_HEIGHT` — the empty state's copy block. */
export const EMPTY_COPY_HEIGHT = 36;
/** `EMPTY_ACTIONS_HEIGHT` — the Chats empty state's row of pill actions. */
export const EMPTY_ACTIONS_HEIGHT = 40;
/** `EMPTY_PAD` — the empty state's vertical padding. */
export const EMPTY_PAD = 10;
/** `LIST_FADE_BAND` — the fade band under a section list's edges. */
export const LIST_FADE_BAND = 16;
/** `INITIAL_ROWS` — rows a section shows before "Show more" pages it. */
export const INITIAL_ROWS = 10;
/** `PAGE_ROWS` — the "Show more" page size. */
export const PAGE_ROWS = 10;
/** `MIN_BODY_HEIGHT` — an open section never shrinks below this. */
export const MIN_BODY_HEIGHT = 120;
/** `FOOTER_PAD_TOP` — the footer's top padding. */
export const FOOTER_PAD_TOP = 4;
/** `FOOTER_PAD_BOTTOM` — the footer's bottom padding. */
export const FOOTER_PAD_BOTTOM = 6;
/** `FOOTER_HEIGHT` — the footer's height budget; shorter content shrinks it. */
export const FOOTER_HEIGHT = 510;

// ── Sections ────────────────────────────────────────────────────────────────

/** Which footer section (`sections.rs::Section`). */
export type Section = "subagents" | "chats";

/** `Section::label()`. */
export function sectionLabel(section: Section): string {
  return section === "subagents" ? "Subagents" : "Chats";
}

/**
 * The collapsed header carries the count; open, the rows speak
 * (`render_section`'s label rule).
 */
export function sectionHeaderLabel(section: Section, open: boolean, count: number): string {
  return open || count === 0 ? sectionLabel(section) : `${sectionLabel(section)} (${count})`;
}

// ── Subagent rows ───────────────────────────────────────────────────────────

/** A spawn chip of the active transcript, as the footer lists it. */
export interface SubagentRow {
  readonly docId: string;
  readonly title: string;
  readonly status: "running" | "done" | "failed" | null;
  /** Epoch-ms of the latest turn that spawned (or steered) it — the
   *  subagent's "last updated". */
  readonly spawnedAt: number;
}

/** A settled subagent is frozen: the tab reads its blob first. */
export function subagentFrozen(row: SubagentRow): boolean {
  return row.status === "done" || row.status === "failed";
}

/** `SubagentRow::indicator()` — the row's status-dot state. */
export function subagentIndicator(row: SubagentRow): ChatIndicator {
  switch (row.status) {
    case "running":
      return "working";
    case "done":
      return "completed";
    case "failed":
      return "errored";
    default:
      return "idle";
  }
}

/**
 * The active chat's subagents, most recently updated first (later spawns
 * lead within one turn), one row per subagent doc — `subagent_rows`
 * (sections.rs:190-227, upstream #568). A reopened (steered) subagent
 * updates its row in place. The caller passes the ACTIVE chat's transcript
 * entries (the desktop's `state.selected_chat` gate is the host's store
 * choice); only genuine spawn chips with a stamped doc ref qualify.
 */
export function subagentRows(entries: readonly SessionMessageEntry[]): SubagentRow[] {
  const rows: SubagentRow[] = [];
  for (const entry of entries) {
    for (const part of entry.parts) {
      if (part.kind !== "tool" || part.subagentRef == null) {
        continue;
      }
      if (!isSubagentSpawn(part.call)) {
        continue;
      }
      const row: SubagentRow = {
        docId: part.subagentRef,
        title: subagentTabTitle(part.call),
        status: part.subagentStatus ?? null,
        spawnedAt: entry.createdAt,
      };
      const existing = rows.find((candidate) => candidate.docId === row.docId);
      if (existing !== undefined) {
        rows[rows.indexOf(existing)] = row;
      } else {
        rows.push(row);
      }
    }
  }
  // Stable sort over the reversed spawn order: ties keep the later spawn
  // on top (sections.rs, upstream #568).
  rows.reverse();
  rows.sort((a, b) => b.spawnedAt - a.spawnedAt);
  return rows;
}

// ── Child chat rows ─────────────────────────────────────────────────────────

/** A side chat of the active chat, as the footer lists it. */
export interface ChildChatRow {
  readonly chatId: string;
  readonly title: string;
  readonly status: ChatIndicator;
  readonly timeAgo: string;
  /** The chat's linked pull request, drawn as the sidebar's badge. */
  readonly changeRequest: ChangeRequestSummary | null;
  /** `last_message_at` else `created_at` — the sort key, newest first. */
  readonly activity: number;
}

/**
 * The live (unarchived) children of `chatId`, most recent activity first —
 * `child_chat_rows` (sections.rs:239-272), the same order the sidebar's
 * Sessions list keeps. `statusFor` looks up the chat's live session row
 * (the desktop's `display_status_for`); `changeRequests`, when given, is
 * keyed by chat id (the fleet's change-request resolution).
 */
export function childChatRows(
  chats: readonly Chat[],
  chatId: string,
  now: number,
  statusFor: (chat: Chat) => ChatStatus | undefined = () => undefined,
  changeRequests: ReadonlyMap<string, ChangeRequestSummary> | null = null,
): ChildChatRow[] {
  const rows = chats
    .filter((chat) => !chat.archived && chat.parentChatId === chatId)
    .map((chat) => {
      const activityIso = chat.lastMessageAt ?? chat.createdAt;
      return {
        chatId: chat.id,
        title: childChatTitle(chat),
        status: displayStatus(chat, statusFor(chat), now),
        timeAgo: timeAgo(activityIso, now),
        changeRequest: changeRequests?.get(chat.id) ?? null,
        activity: Date.parse(activityIso),
      };
    });
  rows.sort((a, b) => b.activity - a.activity);
  return rows;
}

/**
 * A side chat titles itself on its first turn; until then the preview or a
 * placeholder stands in — `child_chat_title` (sections.rs:275-282).
 */
export function childChatTitle(chat: Chat): string {
  return chat.title ?? chat.lastMessagePreview ?? "New side chat";
}

// ── Fingerprint + heights ───────────────────────────────────────────────────

/**
 * What the footer would draw, as one stable string — `fingerprint`
 * (sections.rs:295-319) so a state observer only re-renders the explorer
 * when a section's contents actually changed, not on every streamed
 * transcript delta. Membership, titles, statuses and time labels all ride.
 */
export function sectionsFingerprint(
  subagents: readonly SubagentRow[],
  chats: readonly ChildChatRow[],
): string {
  const subagent = subagents
    .map((row) => `${row.docId}|${row.title}|${row.status ?? "-"}`)
    .join(";");
  const chat = chats
    .map(
      (row) =>
        `${row.chatId}|${row.title}|${row.status}|${row.timeAgo}|${
          row.changeRequest !== null ? `${row.changeRequest.number}#${row.changeRequest.state}` : "-"
        }`,
    )
    .join(";");
  return `${subagent} ${chat}`;
}

/**
 * The height a section body wants for `count` rows with `shown` revealed —
 * `content_height` (sections.rs:331-356): inset, the visible rows, and a
 * "Show more" row while more remain. Empty sections want their empty-state
 * copy (Chats adds its action row). Floored at `MIN_BODY_HEIGHT` so one or
 * two rows still leave the section room to breathe.
 */
export function contentHeight(section: Section, count: number, shown: number): number {
  return Math.max(contentHeightUnfloored(section, count, shown), MIN_BODY_HEIGHT);
}

/** `content_height_unfloored` — the raw want, before the floor. */
export function contentHeightUnfloored(section: Section, count: number, shown: number): number {
  if (count === 0) {
    return (
      SECTION_BODY_INSET +
      EMPTY_PAD * 2 +
      EMPTY_COPY_HEIGHT +
      (section === "chats" ? EMPTY_ACTIONS_HEIGHT : 0)
    );
  }
  const visible = Math.min(count, shown);
  const more = count > shown ? 1 : 0;
  const slots = visible + more;
  return SECTION_BODY_INSET + slots * ROW_HEIGHT + Math.max(slots - 1, 0) * ROW_GAP;
}

/**
 * Split the footer's body budget between two open sections — `body_budget`
 * (sections.rs:359-371): each may take what it wants, and a short one hands
 * its slack to the other. Closed sections get 0. Pure.
 */
export function bodyBudget(budget: number, wants: readonly [number, number], open: readonly [boolean, boolean]): [number, number] {
  const clamped = Math.max(budget, 0);
  const a = open[0] === true ? wants[0]! : 0;
  const b = open[1] === true ? wants[1]! : 0;
  if (a + b <= clamped) {
    return [a, b];
  }
  const half = clamped / 2;
  const first = Math.min(a, clamped - Math.min(b, half));
  const second = Math.min(b, clamped - first);
  return [first, second];
}

/**
 * The footer's chrome outside the bodies — `chrome_height`: padding and the
 * two headers.
 */
export function chromeHeight(): number {
  return FOOTER_PAD_TOP + FOOTER_PAD_BOTTOM + 2 * SECTION_HEADER_HEIGHT;
}

/**
 * `ExplorerSections::shown` — rows revealed per section: the stored value,
 * never below `INITIAL_ROWS` (the "Show more" page step floors here too).
 */
export function effectiveShown(shown: number | undefined): number {
  return Math.max(shown ?? INITIAL_ROWS, INITIAL_ROWS);
}

/**
 * The "Show more" click — `shown + PAGE_ROWS`, floored at `INITIAL_ROWS`
 * (the desktop's `render_show_more` listener).
 */
export function pageShown(shown: number | undefined): number {
  return effectiveShown(shown) + PAGE_ROWS;
}

/** The "Show more" label — `Show N more`, capped at one page. */
export function showMoreLabel(remaining: number): string {
  return `Show ${Math.min(remaining, PAGE_ROWS)} more`;
}

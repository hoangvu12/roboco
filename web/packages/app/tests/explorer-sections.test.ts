import { describe, expect, it } from "vitest";
import type { Chat, MessagePart, SessionMessageEntry, ToolCall } from "@roboco/proto";
import {
  bodyBudget,
  childChatRows,
  childChatTitle,
  chromeHeight,
  contentHeight,
  contentHeightUnfloored,
  effectiveShown,
  EMPTY_ACTIONS_HEIGHT,
  FOOTER_HEIGHT,
  INITIAL_ROWS,
  LIST_FADE_BAND,
  MIN_BODY_HEIGHT,
  pageShown,
  ROW_GAP,
  ROW_HEIGHT,
  SECTION_BODY_INSET,
  SECTION_HEADER_HEIGHT,
  sectionsFingerprint,
  sectionHeaderLabel,
  showMoreLabel,
  subagentFrozen,
  subagentIndicator,
  subagentRows,
} from "../src/lib/explorer-sections";

/**
 * The explorer footer's pure model, against the desktop's
 * `crates/ui/src/files/sections.rs` (731697b6) — the Rust suite's cases
 * ported by name, plus the paging helpers the component rides.
 */

/** Ages are measured against `now + 5s` so a slow test run cannot drift a
 * label across a minute boundary mid-assertion. */
const NOW = Date.now() + 5_000;

function chat(id: string, parent: string | null, minutesAgo: number, fields: Partial<Chat> = {}): Chat {
  return {
    id,
    deviceId: "dev",
    title: null,
    archived: false,
    cwd: null,
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: new Date(NOW - minutesAgo * 60_000).toISOString(),
    ...(parent === null ? {} : { parentChatId: parent }),
    ...fields,
  };
}

function spawn(
  id: string,
  name: string,
  doc: string | null,
  status: "running" | "done" | "failed" | null,
): MessagePart {
  return {
    kind: "tool",
    id,
    call: {
      kind: "unknown",
      name,
      input: { description: "verify the marker pipeline" },
    } as ToolCall,
    isError: false,
    resolved: true,
    output: null,
    diff: null,
    outputRef: null,
    outputBytes: null,
    diffRef: null,
    diffStats: null,
    ...(doc === null ? {} : { subagentRef: doc }),
    ...(status === null ? {} : { subagentStatus: status }),
    subagentTail: null,
  };
}

function entry(parts: MessagePart[], minutesAgo = 3): SessionMessageEntry {
  return {
    id: "e1",
    role: "assistant",
    parts,
    createdAt: Date.now() - minutesAgo * 60_000,
    deviceId: "dev",
    status: null,
    continuationOf: null,
    durationMs: null,
  };
}

describe("subagent_rows_list_only_stamped_spawn_chips", () => {
  it("lists one row per genuine spawn chip, in spawn order, updated in place", () => {
    const rows = subagentRows([
      entry([
        spawn("t1", "Agent: verify", "main--sub--t1", "running"),
        // No doc ref yet: the engine stamps it asynchronously.
        spawn("t2", "Agent: later", null, null),
        // A stray ref on a non-spawn tool never surfaces.
        spawn("t3", "Read", "main--sub--t3", "done"),
        spawn("t4", "Agent: done", "main--sub--t4", "done"),
      ]),
    ]);
    expect(rows.map((row) => row.docId)).toEqual(["main--sub--t1", "main--sub--t4"]);
    // The bare task, genus stripped — the same title the tab wears.
    expect(rows[0]!.title).toBe("verify");
    expect(subagentFrozen(rows[0]!)).toBe(false);
    expect(subagentFrozen(rows[1]!)).toBe(true);
    expect(subagentIndicator(rows[0]!)).toBe("working");
    expect(subagentIndicator(rows[1]!)).toBe("completed");
    // Spawn time comes from the turn that carried the chip.
    expect(Date.now() - rows[0]!.spawnedAt).toBeGreaterThanOrEqual(2 * 60_000);
  });

  it("a reopened (steered) subagent updates its row in place", () => {
    const rows = subagentRows([
      entry([spawn("t1", "Agent: one", "d1", "done")]),
      entry([spawn("t1b", "Agent: one again", "d1", "running")]),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]!.status).toBe("running");
    expect(rows[0]!.title).toBe("one again");
  });
});

describe("child_chat_rows_are_live_children_newest_first", () => {
  it("filters to live children of the chat, most recent activity first", () => {
    const now = NOW;
    const titled = chat("b", "main", 30, { title: "Investigate caching" });
    const chats = [
      chat("main", null, 60),
      chat("a", "main", 5),
      titled,
      chat("unrelated", "elsewhere", 2),
      chat("old", "main", 1, { archived: true }),
    ];
    const rows = childChatRows(chats, "main", now);
    expect(rows.map((row) => row.chatId)).toEqual(["a", "b"]);
    expect(rows[0]!.title).toBe("New side chat");
    expect(rows[1]!.title).toBe("Investigate caching");
    expect(rows[0]!.status).toBe("idle");
    // The time label rides the row (the sidebar's timeAgo ladder).
    expect(rows[0]!.timeAgo).toBe("5m");
  });

  it("child_chat_title falls back title → preview → placeholder", () => {
    expect(childChatTitle(chat("a", null, 1, { title: "Named" }))).toBe("Named");
    expect(
      childChatTitle(chat("b", null, 1, { lastMessagePreview: "A preview stands in" })),
    ).toBe("A preview stands in");
    expect(childChatTitle(chat("c", null, 1))).toBe("New side chat");
    // A title wins over a preview.
    expect(
      childChatTitle(chat("d", null, 1, { title: "Named", lastMessagePreview: "preview" })),
    ).toBe("Named");
  });
});

describe("fingerprint_tracks_membership_and_status", () => {
  it("changes when rows, titles or statuses change; stable otherwise", () => {
    const now = NOW;
    const empty = sectionsFingerprint(subagentRows([]), childChatRows([chat("main", null, 60)], "main", now));
    const one = sectionsFingerprint(
      subagentRows([]),
      childChatRows([chat("main", null, 60), chat("a", "main", 5)], "main", now),
    );
    expect(empty).not.toBe(one);
    expect(one).toBe(
      sectionsFingerprint(
        subagentRows([]),
        childChatRows([chat("main", null, 60), chat("a", "main", 5)], "main", now),
      ),
    );
    // A status flip changes it; a time-label change changes it too.
    const working = sectionsFingerprint(
      subagentRows([]),
      childChatRows([chat("main", null, 60), chat("a", "main", 5)], "main", now, () => ({
        chatId: "a",
        deviceId: "dev",
        status: "working",
        startedAt: null,
        lastCompletedTurn: null,
        updatedAt: new Date(now).toISOString(),
      })),
    );
    expect(working).not.toBe(one);
    const later = sectionsFingerprint(
      subagentRows([]),
      childChatRows([chat("main", null, 60), chat("a", "main", 90)], "main", now),
    );
    expect(later).not.toBe(one);
    // The subagent side rides too.
    const withSubagent = sectionsFingerprint(
      subagentRows([entry([spawn("t1", "Agent: verify", "main--sub--t1", "running")])]),
      childChatRows([chat("main", null, 60)], "main", now),
    );
    expect(withSubagent).not.toBe(empty);
  });
});

describe("content_height_pages_at_ten_rows_and_counts_the_show_more_row", () => {
  it("floors short lists, pages at ten, and counts the tail slot", () => {
    // Short lists are floored so a section keeps its presence.
    const one = contentHeight("chats", 1, INITIAL_ROWS);
    expect(one).toBe(MIN_BODY_HEIGHT);
    const ten = contentHeight("chats", 10, INITIAL_ROWS);
    // Eleven rows: ten visible plus the "Show more" slot.
    const eleven = contentHeight("chats", 11, INITIAL_ROWS);
    expect(eleven - ten).toBe(ROW_HEIGHT + ROW_GAP);
    // Paging once reveals up to 20 rows before the next "Show more".
    const paged = contentHeight("chats", 40, INITIAL_ROWS + 10);
    expect(paged).toBe(SECTION_BODY_INSET + 21 * ROW_HEIGHT + 20 * ROW_GAP);
    // Empty sections want their icon + copy; Chats adds the action row.
    expect(
      contentHeightUnfloored("chats", 0, INITIAL_ROWS) -
        contentHeightUnfloored("subagents", 0, INITIAL_ROWS),
    ).toBe(EMPTY_ACTIONS_HEIGHT);
    expect(contentHeight("subagents", 0, INITIAL_ROWS)).toBeGreaterThanOrEqual(MIN_BODY_HEIGHT);
  });
});

describe("body_budget_shares_the_footer_and_hands_slack_across", () => {
  it("splits the budget exactly like the Rust (sections.rs:359-371)", () => {
    // Both fit: each takes what it wants.
    expect(bodyBudget(300, [100, 100], [true, true])).toEqual([100, 100]);
    // Closed sections take nothing.
    expect(bodyBudget(300, [100, 100], [false, true])).toEqual([0, 100]);
    // Both oversubscribed: an even split.
    expect(bodyBudget(200, [500, 500], [true, true])).toEqual([100, 100]);
    // A short second section hands its slack to the first.
    expect(bodyBudget(200, [500, 40], [true, true])).toEqual([160, 40]);
    // A short first section hands its slack to the second.
    expect(bodyBudget(200, [40, 500], [true, true])).toEqual([40, 160]);
    // Negative budgets clamp to nothing.
    expect(bodyBudget(-5, [40, 500], [true, true])).toEqual([0, 0]);
  });
});

describe("paging helpers", () => {
  it("the shown count never drops below the initial page", () => {
    expect(effectiveShown(undefined)).toBe(INITIAL_ROWS);
    expect(effectiveShown(0)).toBe(INITIAL_ROWS);
    expect(effectiveShown(14)).toBe(14);
    expect(pageShown(undefined)).toBe(INITIAL_ROWS + 10);
    expect(pageShown(14)).toBe(24);
    expect(pageShown(0)).toBe(INITIAL_ROWS + 10);
  });

  it("the show-more label caps at one page", () => {
    expect(showMoreLabel(3)).toBe("Show 3 more");
    expect(showMoreLabel(40)).toBe("Show 10 more");
  });

  it("the collapsed header carries the count; open, the bare label", () => {
    expect(sectionHeaderLabel("subagents", true, 12)).toBe("Subagents");
    expect(sectionHeaderLabel("subagents", false, 12)).toBe("Subagents (12)");
    // Collapsed and empty: the rows speak even collapsed.
    expect(sectionHeaderLabel("chats", false, 0)).toBe("Chats");
  });
});

describe("the desktop's constants ride verbatim", () => {
  it("matches sections.rs:38-71", () => {
    expect(SECTION_HEADER_HEIGHT).toBe(28);
    expect(SECTION_BODY_INSET).toBe(4);
    expect(ROW_HEIGHT).toBe(29);
    expect(ROW_GAP).toBe(2);
    expect(MIN_BODY_HEIGHT).toBe(120);
    expect(FOOTER_HEIGHT).toBe(510);
    expect(INITIAL_ROWS).toBe(10);
    expect(LIST_FADE_BAND).toBe(16);
    expect(chromeHeight()).toBe(4 + 6 + 2 * SECTION_HEADER_HEIGHT);
    expect(FOOTER_HEIGHT - chromeHeight()).toBe(444);
  });
});

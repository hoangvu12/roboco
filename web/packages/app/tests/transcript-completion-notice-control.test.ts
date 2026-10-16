// @vitest-environment jsdom

/**
 * The completion notice's open control (orchestration v2 ticket 15): each
 * side row is a BUTTON that calls `onOpenChat` with THAT row's side-chat id;
 * without a host handler (the read-only subagent pane) the row renders inert
 * — a plain row, never a dead control. The REAL `CompletionNoticeRow`
 * mounts (the mounted-suite idiom, account-row.test.ts): the notice data is
 * the engine writer's own shape (completion_notices.rs `notice_entry`), the
 * label/short-id/outcome lines come from the lib helpers, and the click is
 * a real dispatchEvent through React's root. No JSX (createElement),
 * per-file jsdom pragma only.
 */

import { act, createElement } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { NoticeSide } from "@roboco/proto";
import { CompletionNoticeRow } from "../src/components/transcript";

let root: Root | null = null;
let container: HTMLDivElement | null = null;

beforeEach(() => {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(() => {
  act(() => {
    root?.unmount();
  });
  container?.remove();
  root = null;
  container = null;
});

const sides: readonly NoticeSide[] = [
  { chatId: "chat-side-1234", title: "Implement login", outcome: "completed", detail: null },
  {
    chatId: "chat-side-5678",
    title: "Flaky test",
    outcome: "errored",
    detail: "the build command timed out",
  },
];

describe("the completion notice's open control", () => {
  it("clicking a side row opens that side chat — its own id, not the first's", () => {
    const opened: string[] = [];
    act(() => {
      root?.render(
        createElement(CompletionNoticeRow, {
          sides,
          onOpenChat: (chatId: string) => {
            opened.push(chatId);
          },
        }),
      );
    });
    const rows = Array.from(
      container!.querySelectorAll("button.completion-notice-side"),
    );
    expect(rows).toHaveLength(2, "one control per side chat");
    act(() => {
      rows[1]!.dispatchEvent(
        new MouseEvent("click", { bubbles: true, cancelable: true, detail: 1 }),
      );
    });
    expect(opened).toEqual(["chat-side-5678"]);
  });

  it("renders inert without a host handler — no button, no fake control", () => {
    act(() => {
      root?.render(createElement(CompletionNoticeRow, { sides }));
    });
    expect(
      container!.querySelectorAll("button.completion-notice-side"),
    ).toHaveLength(0);
    expect(container!.querySelectorAll(".completion-notice-side")).toHaveLength(2);
  });

  it("paints the card: the header label, and per side the title, short id and outcome line", () => {
    act(() => {
      root?.render(
        createElement(CompletionNoticeRow, {
          sides,
          onOpenChat: () => {
            // wired, not clicked here
          },
        }),
      );
    });
    expect(
      container!.querySelector(".completion-notice-label")?.textContent,
    ).toBe("Completion notice");
    const titles = Array.from(
      container!.querySelectorAll(".completion-notice-side-title"),
    ).map((node) => node.textContent);
    expect(titles).toEqual(["Implement login", "Flaky test"]);
    const ids = Array.from(
      container!.querySelectorAll(".completion-notice-side-id"),
    ).map((node) => node.textContent);
    expect(ids).toEqual(["chat-si", "chat-si"]);
    const outcomes = Array.from(
      container!.querySelectorAll(".completion-notice-side-outcome"),
    ).map((node) => node.textContent);
    expect(outcomes).toEqual(["finished", "failed: the build command timed out"]);
    // The outcome's own tone rides the row's class, one per outcome kind.
    const classes = Array.from(
      container!.querySelectorAll(".completion-notice-side"),
    ).map((node) => node.className);
    expect(classes[0]).toContain("completion-notice-side-completed");
    expect(classes[1]).toContain("completion-notice-side-errored");
  });
});

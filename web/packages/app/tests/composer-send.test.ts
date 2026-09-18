import { describe, expect, it } from "vitest";
import {
  beginInterrupt,
  composerHasContent,
  interruptParams,
  messageEnterBindings,
  modifiedSubmitTarget,
  platformModifierCombo,
  retainLiveInterrupts,
  sendBlocked,
  sendButtonMode,
  shouldPublishOptimisticEcho,
} from "../src/lib/composer-send";

/**
 * The composer's send-path decisions — each describe named after the
 * `composer.rs` unit test it mirrors: the Send/Queue/Stop button mode, the
 * content rule that keeps a comment-only stage from interrupting a live
 * run, the Mod+Enter target, the optimistic-echo gate, and the interrupt
 * tracking.
 */

describe("staged_comments_alone_are_content", () => {
  it("attachments and comments each count as content on their own", () => {
    expect(composerHasContent("   ", 0, 0)).toBe(false);
    expect(composerHasContent("hi", 0, 0)).toBe(true);
    expect(composerHasContent("", 1, 0)).toBe(true);
    expect(composerHasContent("", 0, 1)).toBe(true);
  });
});

describe("send_button_morph", () => {
  it("Send / Queue / Stop follow (run_live, has_text)", () => {
    expect(sendButtonMode(false, false)).toBe("send");
    expect(sendButtonMode(false, true)).toBe("send");
    expect(sendButtonMode(true, true)).toBe("queue");
    expect(sendButtonMode(true, false)).toBe("stop");
  });
});

describe("a_comment_only_stage_queues_during_a_live_run", () => {
  it("comment-only submit must queue without interrupting the run", () => {
    const live = true;
    const commentOnly = composerHasContent("", 0, 2);
    expect(sendButtonMode(live, commentOnly)).toBe("queue");
    // Nothing staged at all is still the stop square.
    expect(sendButtonMode(live, composerHasContent("", 0, 0))).toBe("stop");
  });
});

describe("sendBlocked", () => {
  it("blocks on any of the four conditions", () => {
    const open = {
      queueEditFinishing: false,
      requestTargetDisconnected: false,
      reviewCommentFlushPending: false,
      newChatNoAgents: false,
    };
    expect(sendBlocked(open)).toBe(false);
    expect(sendBlocked({ ...open, queueEditFinishing: true })).toBe(true);
    expect(sendBlocked({ ...open, requestTargetDisconnected: true })).toBe(true);
    expect(sendBlocked({ ...open, reviewCommentFlushPending: true })).toBe(true);
    expect(sendBlocked({ ...open, newChatNoAgents: true })).toBe(true);
  });
});

describe("modified_submit_sends_content_and_activates_latest_queue_row_when_empty", () => {
  it("content submits; a truly empty composer activates the latest queued row", () => {
    expect(modifiedSubmitTarget(composerHasContent("message", 0, 0))).toBe("submitContent");
    expect(modifiedSubmitTarget(composerHasContent("", 1, 0))).toBe("submitContent");
    expect(modifiedSubmitTarget(composerHasContent("", 0, 1))).toBe("submitContent");
    expect(modifiedSubmitTarget(composerHasContent("  ", 0, 0))).toBe("activateLatestQueued");
  });
});

describe("queued_submit_does_not_publish_an_optimistic_transcript_echo", () => {
  it("queue rows are the queue panel's until dispatch", () => {
    expect(shouldPublishOptimisticEcho(false)).toBe(true);
    expect(shouldPublishOptimisticEcho(true)).toBe(false);
  });
});

describe("message_enter_bindings_cover_both_platform_modifiers", () => {
  it("the setting picks the bare-Enter policy; the modifier combo is verbatim", () => {
    expect(messageEnterBindings("enter", "cmd-enter")).toEqual([
      { keystroke: "enter", action: "submit" },
      { keystroke: "cmd-enter", action: "modifiedSubmit" },
    ]);
    expect(messageEnterBindings("modEnter", "cmd-enter")).toEqual([
      { keystroke: "enter", action: "newlineOrAccept" },
      { keystroke: "cmd-enter", action: "modifiedSubmit" },
    ]);
    expect(messageEnterBindings("modEnter", "ctrl-enter")).toEqual([
      { keystroke: "enter", action: "newlineOrAccept" },
      { keystroke: "ctrl-enter", action: "modifiedSubmit" },
    ]);
    expect(platformModifierCombo(true)).toBe("cmd-enter");
    expect(platformModifierCombo(false)).toBe("ctrl-enter");
  });
});

describe("message_enter_never_adds_extra_modifier_bindings", () => {
  it("exactly two bindings, no shift/alt variants", () => {
    const bindings = messageEnterBindings("modEnter", "cmd-enter");
    expect(bindings).toHaveLength(2);
    expect(
      bindings.some((binding) =>
        ["ctrl-enter", "shift-cmd-enter", "alt-cmd-enter", "shift-enter"].includes(binding.keystroke),
      ),
    ).toBe(false);
  });
});

describe("interrupt_tracking_is_idempotent_per_chat", () => {
  it("a second Stop for the same chat is a no-op", () => {
    const pending = new Set<string>();
    expect(beginInterrupt(pending, "chat-a")).toBe(true);
    expect(beginInterrupt(pending, "chat-a")).toBe(false);
    expect(beginInterrupt(pending, "chat-b")).toBe(true);
    expect(pending.size).toBe(2);
  });
});

describe("interrupt_tracking_releases_only_settled_chats", () => {
  it("the set retains chats still live and frees the settled one", () => {
    const pending = new Set<string>(["chat-a", "chat-b"]);
    retainLiveInterrupts(pending, (chatId) => chatId === "chat-b");
    expect([...pending]).toEqual(["chat-b"]);
    expect(beginInterrupt(pending, "chat-a")).toBe(true);
  });
});

describe("interrupt_payload_keeps_the_captured_chat", () => {
  it("the params carry the chat id and the interrupt command", () => {
    const params = interruptParams("chat-a");
    expect(params["chatId"]).toBe("chat-a");
    expect((params["command"] as Record<string, unknown>)["kind"]).toBe("interrupt");
  });
});

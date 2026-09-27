import { describe, expect, it } from "vitest";
import type { Chat } from "@roboco/proto";
import { chatCopyPath, isHostAbsolutePath } from "../src/lib/chat-copy-path";

/**
 * Ticket 14 web parity — the sidebar chat menu's Copy Path (upstream
 * cfe91887, #245), the pure half of the desktop's shell.rs tests: the chat's
 * cwd as the host spells it, absolute on the host whatever the viewer's OS,
 * unavailable for projectless/relative paths.
 */

function chatWith(cwd: string | null): Chat {
  return {
    id: "chat",
    deviceId: "remote-device",
    title: null,
    archived: false,
    cwd,
    branch: null,
    checkoutId: null,
    config: null,
    lastMessagePreview: null,
    lastMessageAt: null,
    createdAt: "2026-09-25T00:00:00.000Z",
    spaceId: null,
  };
}

describe("chatCopyPath", () => {
  it("copies the chat cwd, not the canonical repo root", () => {
    expect(chatCopyPath(chatWith("/remote/repo/packages/app"))).toBe("/remote/repo/packages/app");
    // The Windows host's verbatim-prefixed canonical form never appears —
    // the row copies the host-spelled cwd.
    expect(chatCopyPath(chatWith("C:\\Users\\me\\repo"))).toBe("C:\\Users\\me\\repo");
  });

  it("accepts host-absolute paths from any OS", () => {
    for (const cwd of ["/home/me/repo", "C:\\Users\\me\\repo", "D:/work/repo", "\\\\server\\share\\repo"]) {
      expect(chatCopyPath(chatWith(cwd))).toBe(cwd);
    }
  });

  it("is unavailable without an absolute path", () => {
    for (const cwd of [null, "", "  ", "~", "~/repo", ".", "C:"]) {
      expect(chatCopyPath(chatWith(cwd))).toBeNull();
    }
  });
});

describe("isHostAbsolutePath", () => {
  it("recognizes every host-absolute spelling", () => {
    for (const path of ["/", "/x", "\\\\srv", "C:\\x", "d:/y", "Z:/mixed\\separators"]) {
      expect(isHostAbsolutePath(path)).toBe(true);
    }
  });

  it("rejects relative and bare-drive paths", () => {
    for (const path of ["", "x", "~/repo", ".", "C:", "1:\\x", "a:b"]) {
      expect(isHostAbsolutePath(path)).toBe(false);
    }
  });
});

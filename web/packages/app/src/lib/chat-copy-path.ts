import type { Chat } from "@roboco/proto";

/**
 * The chat context menu's Copy Path (upstream cfe91887, #245 — the desktop's
 * `chat_copy_path`/`is_host_absolute_path` in shell.rs): the chat's working
 * directory as the HOST device spells it — the folder the harness runs in (a
 * worktree chat's worktree). Projectless `~` chats have none, and the path
 * must be absolute on the host (whatever the viewer's OS: a remote engine may
 * hand a POSIX path to a Windows viewport or a drive path to a POSIX one —
 * `Path.isAbsolute` would answer for the wrong filesystem).
 */

/**
 * Absolute on the HOST, whatever the viewer's OS: POSIX root, UNC share, or a
 * drive-letter path (`C:\…` / `D:/…`).
 */
export function isHostAbsolutePath(path: string): boolean {
  if (path.startsWith("/") || path.startsWith("\\\\")) {
    return true;
  }
  if (path.length < 3) {
    return false;
  }
  const a = path.charCodeAt(0);
  const isAlpha = (a >= 0x41 && a <= 0x5a) || (a >= 0x61 && a <= 0x7a);
  return isAlpha && path.charCodeAt(1) === 0x3a && (path.charCodeAt(2) === 0x5c || path.charCodeAt(2) === 0x2f);
}

/**
 * The chat's copyable working directory, or null when the chat is projectless
 * (`~`) or its cwd is relative/blank. Deliberately NOT the canonicalized
 * `source_context.repo_root` (symlinks resolved, `\\?\` verbatim prefix on
 * Windows hosts) — the row copies the folder as the host spells it.
 */
export function chatCopyPath(chat: Chat): string | null {
  const cwd = typeof chat.cwd === "string" ? chat.cwd.trim() : "";
  return cwd !== "" && isHostAbsolutePath(cwd) ? cwd : null;
}

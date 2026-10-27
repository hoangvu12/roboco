# 07 — Command tool titles without shell launch wrappers (A12)

**Lane:** 1b Fixes: Windows. **Effort:** M.

**What to build:** Codex "Run" titles on Windows read
`"C:\…\powershell.exe" -Command '…'` instead of the command. On Unix
they read `bash -lc '…'`. Add one shared unwrapping helper in
`crates/proto` and use it on desktop and Android: `/bin/bash -lc`,
`sh -c`, `zsh -lc`, `cmd /c`, `powershell(.exe) [-NoProfile] -Command`
and `pwsh -c`, with quoting undone. Apply the review feedback on #742:
don't match the shell by file name alone. Require the invocation shape
(shell + its command flag + one script argument), so a real
`powershell.exe some-script.ps1` stays as is. The full raw command stays
available in the expanded tool view and in copy.

**Upstream:** open PR #742 (+601/−95). Closed PR #741 is the same fix,
so port only one.

**Roboco files:** `crates/proto/src/view.rs:530` (title), new helper
beside it, `crates/ui/src/transcript.rs:864`,
`crates/mobile/src/layout/tools.rs:171`.

**Excluded:** nothing known.

**Proto change:** no wire type (a helper fn in `crates/proto`).

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A12; `part-prs-B.md` #742; `part-closed.md` #741.

- [ ] Unwrap table test: every wrapper above, nested quotes, and the negative cases (script files, extra flags)
- [ ] Desktop and Android titles show the inner command; the expanded view and copy keep the raw line
- [ ] Commit records PR #742 (head commit read) and the review-feedback adaptation

## Comments

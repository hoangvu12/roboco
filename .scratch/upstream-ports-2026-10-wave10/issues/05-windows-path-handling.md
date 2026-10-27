# 05 — Windows paths: terminal cwd, drive-letter case, `\\?\`, slash styles, folder hints (A8, A33, A21, #659)

**Lane:** 1b Fixes: Windows. **Effort:** M (four S pieces).

**What to build:**
1. **A8: terminals open in `C:\Windows`** (remainder of #528; supersedes
   wave-7 17's #528 half). `crates/engine/src/terminals/windows.rs:292`
   `plain_current_dir` only knows upper-case `\\?\UNC\` and strips any
   `\\?\`. Port upstream: fold lower-case `\\?\unc\`; reject
   `\\?\Volume{…}`, `GLOBALROOT`, `\\.\`, `\??\` and malformed UNC with
   `InvalidInput` instead of letting cmd.exe fall back to `C:\Windows`.
   Port both tests. Reference: our own zeron PR branch
   `fix/windows-terminal-cwd-verbatim` (`099f1274`). Optional: the
   ConPTY test infra from `35a9139a1` (launcher pid, native liveness,
   `wait_for_shell_prompt`; `terminals.rs:871-935`), if it makes the new
   tests reliable.
2. **A33: New project doesn't recognise `c:\users\…` or `\\?\C:\…` as
   inside Home.** Port `folder_path_key` (drive-letter case fold, `\\?\`
   treated as plain; it arrived in #856 `3487b507f`) and use it in
   `same_folder_path` (`crates/ui/src/pickers.rs:677`) and in the Home /
   crumb logic (`crates/ui/src/shell/spaces.rs:2546-2564`). The test
   assertion at `spaces.rs:2481` flips. Port the three #998 tests (Tab
   trap, ↵ during Ctrl+↵, home answers last).
3. **A21 (open PR #791): re-adding a project with the other slash style
   duplicates it.** `spaces.rs:6647` compares `s.path == path` exactly.
   Compare by `folder_path_key`. Existing duplicates created before
   `22ba72f8a` just stop multiplying; don't migrate stored data.
4. **#659 (closed-unmerged): the "No folders match" hint suggests
   `~/code or /mnt` on Windows** (`spaces.rs:7613`). Give a Windows hint
   (e.g. `C:\Users\you\code`) on Windows.

**Upstream:** `88a07863f` (#528), `77a3c0ff1` (#998) +
`folder_path_key` from `3487b507f` (#856), open PR #791, closed PR #659.
Optional `35a9139a1` (#389) test infra.

**Roboco files:** `crates/engine/src/terminals/windows.rs`
(+ `terminals.rs` tests), `crates/ui/src/pickers.rs`,
`crates/ui/src/shell/spaces.rs`.

**Excluded:** the rest of #856 (tickets 69/70). Take only the
`folder_path_key` helper here; 69 reuses it.

**Proto change:** no.

**Blocked by:** 01.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A8, A33, A21, §A closed-unmerged #659;
`part-03.md` `88a07863f`; `pass2-big-2.md` gaps 6, 13, 15.

- [ ] Odd verbatim/volume paths fail with `InvalidInput`; lower-case `\\?\unc\` resolves (ported tests, run on Windows)
- [ ] `c:\users\me\x` and `\\?\C:\Users\me\x` show as `~\x` with Home crumbs; same-folder check holds (tests)
- [ ] Adding `C:/a/b` when `C:\a\b` exists selects the existing project (test)
- [ ] The Windows hint text is right (test or screenshot)
- [ ] Commit records `88a07863f`, `77a3c0ff1`, the `3487b507f` helper slice, PRs #791/#659, and `099f1274` as reference

## Comments

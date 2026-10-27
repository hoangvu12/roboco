# 06 — File links in replies: Windows drive/UNC paths, inline-code paths, chat-scoped bare names, Codex citations (A11, A39, #851, #933)

**Lane:** 1b Fixes: Windows. **Effort:** M.

**What to build:**
1. **A11 + A39: Windows paths become file links.** `C:\…\x.rs`,
   `C:/…`, `\\server\share\…`, and the same inside inline code, are
   plain text today. `crates/ui/src/workspace_links.rs:193-197` says
   "File links are POSIX-only", and the engine's absolute read requires a
   leading `/` (`crates/engine/src/workspace_files.rs:460,483`
   `resolve_absolute_read`). Extend the link grammar (decode once,
   `#L`/`:line:col`, root widening) and the engine's absolute read to
   Windows drive and UNC paths. Keep the same rules: editable inside
   the chat's folder, read-only outside, `MAX_ABSOLUTE_PATH_BYTES`. Do
   the same for the inline-code link cache (`InlineCodeLinkCache`,
   `PathProbes`). Open PR #796 is the starting point for the transcript
   side. Upstream itself is POSIX-only, so A39 is Roboco's own work. Our
   `workspace_links.rs` diverged (`cc84b5f9a`, `4927527af`), so this is a
   hand port, not a cherry-pick.
2. **#851: a bare file name opens another project's file.**
   `crates/ui/src/state.rs:1255-1282` `file_link_roots` falls through to
   every project root on the device. Resolve bare names only in the
   linking chat's folder (the PR says to port it together with #796).
3. **#933 (draft PR): Codex file citations.**
   `:codex-file-citation{path=…}` renders raw (no match for
   `codex-file-citation` anywhere). Make it a file link in
   `crates/markdown`, which fixes desktop and Android at once. It's a
   draft, so check the directive shape against a live Codex reply first.
   Drop this piece (and note why) if live Codex no longer emits it.

**Upstream:** open PRs #796, #851, #933 (draft). The Windows grammar and
the inline-code half are Roboco's own work (pass2-big-1 note on
`2a38baa4e`).

**Roboco files:** `crates/ui/src/workspace_links.rs`,
`crates/ui/src/markdown/link_interaction.rs` (menu rows unchanged),
`crates/ui/src/state.rs`, `crates/engine/src/workspace_files.rs`,
`crates/markdown/src/parser.rs` (citation directive).

**Excluded:** nothing upstream. Don't add links for paths on a
different device's drive. Links stay chat-local, as today.

**Proto change:** no (`WorkspaceReadOnlyReason::OutsideWorkspace`
already exists).

**Blocked by:** 01. Ticket 02 also edits `crates/markdown/src/parser.rs`.
Land 02 first if both are in flight.

**Status:** ready-for-agent

**Source:** `2026-10-28.md` A11, A39; `part-prs-B.md` #796, #851, #933;
`pass2-big-1.md` `2a38baa4e` note.

- [ ] `C:\…`, `c:/…`, `\\server\share\…` and `\\?\` paths in replies and inline code link to the file; `:12:3` suffixes work (link-grammar tests)
- [ ] The engine reads Windows absolute paths with the same jail and read-only rules (engine unit tests, run on Windows)
- [ ] A bare `README.md` resolves only in the linking chat's folder (test)
- [ ] Codex citations render as links on desktop and Android, or the drop is recorded with evidence
- [ ] Commit records PRs #796, #851, #933 and the own-work note

## Comments

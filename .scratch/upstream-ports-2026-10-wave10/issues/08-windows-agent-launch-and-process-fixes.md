# 08 — Windows agent launch: Devin probe reaped, Antigravity no-op browser, native `claude.exe`, preview discovery cost (A32, #617, N8b, #917)

**Lane:** 1b Fixes: Windows. **Effort:** S–M.

**What to build:**
1. **A32: Devin model probe outlives its timeout.** A timed-out
   `devin models list` keeps running past its execution lease, so a
   queued CLI update fails with "file in use" on Windows. Reap the probe
   before releasing the lease
   (`crates/harness/src/acp/devin_models.rs:95`; upstream `:153,734`) and
   port `timed_out_catalog_probe_is_reaped_before_returning`.
2. **#617 Windows slice: Antigravity opens a browser behind the user.**
   Roboco's `noop_browser()` returns `Err` on Windows
   (`crates/harness/src/acp/mod.rs:3308`). Port upstream's shape: a
   hidden `--noop-browser` early exit in `apps/roboco/src/main.rs`, and
   the app executable launched as `BROWSER` on Windows. Linux and macOS
   keep the existing `.noop-browser` script. This was skipped in wave 5
   as a ticket-level deviation, not a user decision.
3. **N8b: native `claude.exe` beside the npm shim.** Newer Claude Code
   npm packages ship `node_modules/@anthropic-ai/claude-code/bin/claude.exe`.
   Upstream (`claude/mod.rs:84-113` `native_beside_npm_shim`) launches it
   instead of the `.cmd` shim. **Check a current npm install first.**
   Roboco's `process/cmd_shim.rs` already unwraps shims to
   `node cli.js`, so this only matters if the package ships the exe
   without `cli.js`. Record what the install contains. Port only if it
   changes behaviour.
4. **#917 (closed-unmerged): Windows preview discovery cost.**
   `crates/preview/src/discovery.rs:847` reads every process's command
   line, cwd and environment every 2 s, all day. **Measure first**
   (CPU over 10 minutes with ~200 processes, release build). Port #917's
   narrowing only if the cost is material, and record the numbers either
   way. Previews stay in product.

**Upstream:** `35a9139a1` (#389) probe slice; `d1dc29f6c` (#617) Windows
slice; `2e151ca65` (#977) `native_beside_npm_shim`; closed PR #917.

**Roboco files:** `crates/harness/src/acp/{devin_models,mod}.rs`,
`apps/roboco/src/main.rs`, `crates/harness/src/claude/mod.rs`
(executable resolution), `crates/preview/src/discovery.rs`.

**Excluded:** macOS hunks of #617.

**Proto change:** no.

**Blocked by:** 01. Ticket 11 also edits `claude/mod.rs`; land item 3
after 11 if both are open.

**Status:** ready-for-agent (run on the Windows checkout; items 3 and 4
are measure-first)

**Source:** `2026-10-28.md` A32, §B smaller partials (#617), A45 (second
half), closed #917; `pass2-big-2.md` gap 2; `part-05.md` `d1dc29f6c`;
`pass2-features-desktop.md` N8(b).

- [ ] Timed-out Devin probe reaped before the lease is released (ported test)
- [ ] A stale Google login under Antigravity on Windows opens nothing; prompt watchers still stop the run
- [ ] `claude.exe` finding recorded; ported or explicitly not needed
- [ ] #917 CPU measurement recorded; narrowing ported only if it helps
- [ ] Commit records `35a9139a1`, `d1dc29f6c` (Windows slice), the `2e151ca65` slice if taken, PR #917

## Comments

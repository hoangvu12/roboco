# Prompt for `/implement-spec` — web parity

Paste the block below as the argument to `/implement-spec`. Change the WAVE
line between runs. Recommended: run the orchestrator on Opus; implementer
subagents on Opus for tickets 07, 13, 18, 19, 27 and Sonnet elsewhere.

```
/implement-spec .scratch/web-parity/spec.md

WAVE: 1   (tickets 01–12; re-run with WAVE 2 = 13–21, WAVE 3 = 22–27, WAVE 4 = 28–31)

Spec: .scratch/web-parity/spec.md (goal, six settled decisions, conventions, ticket map with blockers).
Tickets: .scratch/web-parity/issues/NN-*.md — one per ticket, self-contained. "Blocked by" in each file is the task graph. Work the frontier; a ticket starts when every blocker is merged to the PR branch.
Research (depth only, tickets already inline what they need): .scratch/web-client/research/.

Scope of this run: only the WAVE's tickets. Do not start a ticket outside the wave even if its blockers are done. Tickets are desktop widths only; the phone layer must keep working (spec decision 5). Skip everything a ticket's "Do not" section lists.

Branch and PR: branch from `web-client` (not `main`); name it `web-parity/wave-N`. Open a draft PR against `web-client` on hoangvu12/roboco. Do not touch `zeron/main` or the upstream mirror. Do not push force.

Implementer subagents, one per ticket, each in its own worktree on its own branch off the PR branch. Give each ONLY: the ticket path, the spec path, the PR branch name, and the pointers below. Do not restate ticket content.
- Worktree setup on this Windows machine: after `git worktree add`, run `pnpm install` inside `<worktree>/web` (node_modules are not shared), and add the worktree root to `basedirs` in `%APPDATA%\Mozilla\sccache\config\config` then `sccache --stop-server; sccache --start-server` (AGENTS.md "Windows development"); otherwise Rust builds lose the cache.
- Verify with: `pnpm -r build` (typecheck, run from `web/`), the touched package's vitest, and for any visual ticket the screenshot pair the ticket names. Web captures need the engine rebuilt after `pnpm build` because the bundle is embedded at compile time: `cargo build -p roboco-engine --example web_smoke`, then run it and capture with the use-browser skill. Desktop captures: `.scratch/web-client/parity/shot.ps1`.
- Rust changes are allowed only where a ticket names them (ticket 01: `crates/engine/examples/web_smoke.rs` + a smoke registry; ticket 02: `crates/theme` artifact export, then regenerate `@roboco/theme` and keep `theme-artifact.yml` green). Nothing else in `crates/`.
- Every ticket ends by appending a short note under its `## Comments` heading: what landed, what was skipped and why, screenshot paths. Set the ticket's Status line to `done` on merge.
- Commit messages: `feat(web): <ticket NN> <title>` / `fix(web): …`; end with the attribution lines from the conversation's system-reminder.

Merger subagent: most tickets touch `web/packages/app/src/styles/app.css` and a few shared files (`app-shell.tsx`, `composer.tsx`, `transcript.tsx`, `state/layout.ts`). Resolve conflicts by intent per the two tickets, never by picking a side; after each merge re-run `pnpm -r build` on the PR branch before starting dependents.

Ordering hints for WAVE 1: 01, 02, 03, 04, 05 are all unblocked — start all five at once. 04 (deletion pass) and 05 (state fixes) both edit `composer-actions.ts`/`app-shell.tsx`; merge 04 first. 09 depends only on 02; 06/08 on 02+03+05; 07 on 02+03; 10 on 09; 11 on 09; 12 on 06+07.

When the wave's tickets are merged: run /code-review on the PR branch (Standards + Spec, the spec being spec.md plus each merged ticket), fix everything in one implementer subagent, run the full web test suite once, mark the PR ready, clean up worktrees, and report: tickets done, tickets skipped with reason, screenshot pairs taken, open Comments that need a human.
```

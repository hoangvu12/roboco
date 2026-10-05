# Upstream ports 2026-10 wave 5: zeron v0.2.103 (69e64ef5 → 9b377308)

Status: ready-for-agent

Source: `.scratch/upstream-drift/2026-10-05.md` — the drift review of the
v0.2.103 window. The mirror was refreshed to `9b377308` after the ancestry
check passed (no-diff + tracking verified). Window: 12 commits, 11
non-merge, 1 merge (`1c83cda2`, #751). Nine port candidates, one version
bump (excluded — Roboco versions independently; v0.8.0 shipped
2026-10-04), plus the #707 carry-over deferred from wave 4. Wave 4's own
follow-ups are all resolved: #706 → `11972489`, picker SHAs →
`9ab5a364`/`74abedd5`, #630 stayed a review (ticket 30, `812cf4fa`).

## Problem Statement

Roboco main (`85b1d97f`, post-v0.8.0) lacks, verified against the tree
with file:line evidence in the drift note:

1. **Windows drive paths break the add-space folder picker** — the
   folder-browser helpers (`crates/ui/src/pickers.rs:321,373,399`) are
   POSIX-only: `D:\` produces a bogus `/D:\` crumb that fails in the
   engine with "os error 123", typed `D:\x` queries are treated as name
   filters, and the web twin shares the bug line-for-line.
2. **Windows clipboard BMP images are invisible to agents** — staging
   carries BMP end-to-end but no harness inlines it; decode/re-encode
   exists only for previews.
3. **Preview probes break agent-CLI OAuth sign-ins** — the discovery
   cycle HTTP-probes every project-owned loopback listener, including
   single-use login callbacks.
4. **Shift+Backspace is a dead key** in the composer and palette inputs
   (only bare `backspace` is bound).
5. **Activity loaders freeze at phase 0 under reduced motion**, so
   "working" indicators look dead.
6. **Chat rename still uses a modal** instead of inline row rename.
7. **No per-project new-chat button** on sidebar group headers; the
   archive pill has no tooltip.
8. **Mermaid fences in chat replies render as plain code blocks** — the
   files-preview mermaid substrate exists but the transcript passes
   `media: None` (`transcript.rs:6713,6774`).
9. **No todo panel** (wave-4 deferral), **no git-identity project
   grouping**, and **stale GPUI pins** — upstream zui main is now
   `0966d065`, which contains everything our fork ever carried (backdrop
   `c2d273dc` merged via zui#10, drag threshold, per-edge fade bands)
   plus exactly one new commit: `0966d06` "Transformed and blurred
   monochrome glyphs" — the enabler #799's rolling labels need.

## Solution

Eleven tickets under `issues/` (01–11). Decisions resolved up front
(user, 2026-10-05):

1. **Zeron Icons: SKIPPED.** Roboco keeps its Solar Icons set. The
   icons.zeron.sh adoption in #799 (`icons.rs` ±78, `assets/icons/*`,
   the generated web icon set, ARCHITECTURE.md icons prose) is not
   ported; no THIRD_PARTY_NOTICES change. Git-identity grouping and
   rolling labels still port.
2. **Verification is deferred entirely to one wave-final batched pass.**
   NO builds, tests, or codegen during ticket work — no `cargo`, no
   `nextest`, no `pnpm`, no `wiregen`. The tickets' "Verification
   budget" lines define WHAT the final pass covers; this spec defines
   WHEN (once, at wave end, over the merged branch). Sole exception: if
   genuinely blocked, one narrow `cargo check -p <crate>`, noted in the
   ticket's Comments.
3. **Execution: a single implementer session** on branch `wave5/ports`
   off `main`, one commit per ticket, in ticket order (respect
   `Blocked by` lines). Merge into `main` only after the wave-final pass
   is green. Never push the branch; never merge or move the mirror.
4. **zui sync is ticket 01** (prep): pin zeronsh/zui `0966d065` through
   our fork + bump gpui-component to `4764fd00` — the AGENTS.md pin rule
   (top-level gpui pin rev == `[patch]` rev, always) applies verbatim.
5. **#799 ports the merged `dbb639be` content only.** Upstream's
   `zenon/git-identity-project-grouping` branch is still under active
   review (10+ unmerged commits at `c3af3110`); its follow-ups
   (blur/tilt glyphs, trunk root-commit identity, compact-list changes)
   ride future drift windows, not this wave. Excluded hunks:
   `apps/ios/*`, `crates/client/*`, `crates/mobile/*`, Zeron Icons, and
   the PR's own Cargo.toml/lock pin bumps (ticket 01 owns the pins).

## User Stories

1. As a Windows user, I want drive paths (`D:\`, `D:/x`, typed
   `D:\projects\`) to work in the add-space folder picker — crumbs,
   parent navigation, typed jumps — on desktop and web, so adding
   projects on other disks works without "os error 123".
2. As a Windows user pasting screenshots, I want BMP clipboard images
   converted to PNG at staging (off the UI thread) so every harness can
   see them.
3. As an agent-CLI user signing in, I want preview discovery to leave my
   OAuth callback listeners unprobed so logins succeed.
4. As a composer user, I want Shift+Backspace to delete backwards like
   every other text input.
5. As a reduced-motion user, I want activity loaders to keep a gentle
   brightness pulse when the *system* asks for less motion — while
   explicit On and background pause stay frozen — and the web to match
   by recorded decision.
6. As a sidebar user, I want inline rename (double-click, menu, `/rename`)
   with reveal-and-scroll to the row, and a per-project `+` new-chat
   button with tooltips above the archive pill and the `+`.
7. As a chat reader, I want ```mermaid fences in replies rendered as
   diagrams (streaming-safe, budgeted, lightbox) using the renderer we
   already ship for file previews.
8. As an agent user, I want the todo panel: the agent's checklist as a
   dedicated panel across proto, doc, all six harness normalizers, UI
   and web.
9. As a multi-checkout user, I want projects grouped by git identity —
   clones and worktrees of one repository folded into one group, the
   new-session composer picking the project (repository) before the
   device — with rolling overflow labels, no new icon set.
10. As a contributor, I want each port commit to record its upstream
    SHA and deliberate exclusions.

## Implementation Decisions

- Process: `docs/reference/upstream-ports.md` by intent — read upstream
  commits with `git show <sha>` (upstream fetched in `~/roboco-dev`;
  mirror `zeron/main` at `9b377308`). Carry into `wave5/ports` off
  `main`, one commit per ticket. Rebrand mapping per AGENTS.md
  (`ZERON_PULSE` → `ROBOCO_PULSE` in ticket 06 is this wave's only new
  identifier rename; crate paths are unchanged except `apps/zeron` →
  `apps/roboco` which no ticket touches).
- **Build-cycle economy (binding user directive):** the 2026-10-05
  decision is stricter than wave 4's lane checkpoints — zero cargo/pnpm
  invocations during ticket work. The wave-final pass is the only
  compile/test gate. Port quality rides on careful diff reading, not
  compilation; when uncertain about an API, read the surrounding code
  and the consumers instead of building.
- Wave-4's box discipline applies to the final pass: `source
  ~/.bashrc_pi`, `-j 3`, no `cargo clean`, sccache, manifest-level
  `[profile.dev] debug = "line-tables-only"` (never override per-shell).
  See `.scratch/upstream-ports-2026-10-wave4/handoff-wave4-continuation.md`
  for the full environmental notes (nextest target selection, known
  pre-existing failures, fmt drift).
- **Web parity is a deliverable:** ticket 03 must land the Rust helpers
  and the `add-space.ts` twin together; 07/08 port the web sidebar
  counterparts (`chat-list.tsx`, `archived-section.tsx`,
  `surface-registry.tsx` dialogs); 10 ports the web todo panel. Ticket
  09 EXTENDS the recorded web divergence (web renders mermaid as source
  — `markdown.tsx:759-763`, `.scratch/web-parity-fixes/spec.md:303`)
  instead of adding Mermaid.js; do not add a web mermaid renderer.
  Tickets 02, 04, 05, 06, 11 web counterparts: 11 ports grouping +
  rolling labels to the web sidebar (no icons); 02/04/05 are
  desktop-only by design; 06 records the web CSS decision
  (gentle-pulse-vs-frozen) and applies it.
- Wire-surface changes: ticket 10 (todo doc/proto surface) and 11
  (repository id in proto) regenerate wire types — but only during the
  wave-final pass (`wiregen` + `pnpm -r build` there), keeping the
  no-build directive intact for ticket work.
- Upstream's `docs/reference/windows-development.md` hunks in ticket 06
  port into our (diverged) doc by intent.

## Ticket table

| # | Ticket | Upstream | Size | Blocked by |
|---|---|---|---|---|
| 01 | zui + gpui-component pin sync | (upstream zui `0966d065`, gpui-component `4764fd00`) | pins + lock | — |
| 02 | Shift+Backspace bindings | `2a884777` (#757) | 1 file, +50 | — |
| 03 | Windows drive paths in folder picker | `edac0d7d` (#727) | +124/−23 + web twin | — |
| 04 | BMP→PNG at staging | `f9a4a18b` (#739) | 4 files, +192/−40 | — |
| 05 | Auth callbacks unprobed | `612df512` (#763) | 4 files, +202/−4 | — |
| 06 | Activity pulse under reduced motion | `e96eccb1` (#754) | 4 files, +236/−6 | — |
| 07 | Inline thread rename | `c78bb1c1`+`46bedeca` (#751) | 9 files, +1050/−121 | — |
| 08 | Per-project new-chat + archive tooltip | `e2a7706f` (#737) | 5 files, +176/−22 | 07 |
| 09 | Mermaid in chat replies | `9e1a1115` (#760) | 11 files, +1399/−142 | — |
| 10 | Todo panel (wave-4 carry-over) | `1f7b74a7` (#707) | ~1.7k, 26 files | — |
| 11 | Git-identity project grouping | `dbb639be` (#799 subset) | ~2.7k, 58 files (subset) | 01 |

In-lane order = ticket order 01 → 11. 08 after 07 (same sidebar
surfaces, upstream merge order); 11 after 01 (rolling labels ride the
new glyphs).

## Out of scope (recorded)

- Zeron Icons (user decision 2026-10-05): `icons.rs`, `assets/icons/*`,
  web generated icon set, ARCHITECTURE.md icons prose.
- `9b377308` version bump; upstream `edge/*` branch activity
  (`cut-chatroom-row-writes`, `drop-stored-heads` — cloud, ADR 0003).
- `apps/ios/*`, `crates/client/*`, `crates/mobile/*` hunks of #799
  (including its demo-workspace clone fixtures and iOS session flow).
- #799's own Cargo.toml/Cargo.lock pin hunks (ticket 01 owns pins).
- The unmerged `zeron/git-identity-project-grouping` follow-ups
  (`c3af3110` lineage: blur/tilt rolling glyphs, trunk root-commit
  identity, compact model list changes, stale harness list fix) — next
  drift window re-triages them.
- Any web mermaid renderer.

## Wave-final verification pass (the only one)

Run over `wave5/ports` after ticket 11, fix fallout in a batched fixup
commit (wave-4 precedent: `cf94f415`), then merge to `main` and append
the completion record here. Under the worker env (`source
~/.bashrc_pi`, `-j 3`):

1. `cargo check --workspace --examples` — first compile of the wave;
   catches pin-rule violations (two GPUI copies ≈ 50 type-mismatch
   errors), ticket 01 fallout, and port breakage. Refresh `Cargo.lock`
   here if ticket 01 deferred it.
2. `cargo nextest run` per surface with explicit target selection:
   `-p roboco-ui --lib`, `-p roboco-engine`, `-p roboco-harness --lib`,
   `-p roboco-mcp`, `-p roboco-voice`, `-p roboco-theme`; plus
   `-p roboco-preview` (ticket 05's discovery tests).
3. `wiregen --check` (tickets 10, 11 proto/doc surfaces) and
   `roboco-theme-export --check`; `pnpm -r build` (web) and the app +
   engine-client vitest suites.
4. Known pre-existing failures — do NOT chase (wave-4 baselines):
   engine `previews::preview_watch_follows_the_session_checkout_and_owning_device`;
   harness `acp::tests::antigravity_named_home_settings_preserve_business_auth`
   (uid-1001 environmental); the three broken harness examples
   (`grok_subagent_probe`, `opencode_subagent_probe`,
   `opencode_turn_probe`); ~200 pre-existing fmt drift hunks (keep only
   NEW code fmt-clean).
5. Windows parity rides `windows.yml` on push/PR as designed.

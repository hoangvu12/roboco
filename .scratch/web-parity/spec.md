# Web parity — make the web client a 1:1 copy of the desktop

Status: ready-for-agent
Supersedes: `.scratch/web-client/issues/18-parity-polish-smoke.md`.
Research (the actual spec): `.scratch/web-client/research/00-index.md` and
its 16 surface files. Every ticket under `issues/` inlines the parts of that
research it needs; the research files are the source of truth when a ticket
and the research disagree.

## Goal

The web client (`web/packages/app`) renders and behaves exactly like the
desktop client (`crates/ui`) at desktop widths: same geometry, same colors
(through the shared `--rb-*` tokens), same motion (durations and curves
from the shared catalog), same strings, same keyboard, same state rules.
Not "inspired by". Copied.

## Decisions (settled; do not reopen in tickets)

1. **Fleet.** The web connects to every paired engine simultaneously, like
   the desktop. Ticket 31, last.
2. **Right-pane tabs are multi-instance.** N file tabs, N commit-diff tabs,
   subagent tabs, one Files, one Terminal, like the desktop's `RightSurface`.
   Ticket 7 generalizes the model; later pane tickets depend on it.
3. **Steer-now does not exist on web.** The composer offers Send / Queue /
   Stop exactly as the desktop does. The queue panel offers Send now only.
4. **Invented web-only UI is deleted, not polished.** Ticket 4 removes every
   item marked INVENTED in the research; later tickets must not re-add.
5. **Phone widths are out of scope.** Tickets target desktop widths
   (≥ 768px). The existing phone layer (drawer sidebar, docked composer)
   stays as is; do not break it, do not extend it.
6. **Desktop-only stays desktop-only.** Native captions, vibrancy, embedded
   browser tabs, appshots, native menus, macOS notification plumbing. Each
   research file's §6 lists them.

## Conventions every ticket follows

- **Tokens.** Colors are `var(--rb-<role>)`; neutral washes are
  `rgb(var(--rb-wash) / a)`, `rgb(var(--rb-ink) / a)`,
  `rgb(var(--rb-hairline) / a)`; spacing/radii are `var(--rb-space-*)`,
  `var(--rb-radius-*)`; motion is `var(--rb-motion-<spec>)` +
  `var(--rb-ease-<curve>)`. No literal hex. Ticket 2 adds the missing ones.
- **Numbers come from the ticket.** Every px, alpha, ms, and string in a
  ticket was transcribed from the Rust with a `file:line`. Implement the
  number in the ticket; if it looks wrong, check the cited line, then the
  research file, then say so in the ticket's Comments.
- **Reduced motion.** Where the desktop snaps under reduced motion, the web
  honors `prefers-reduced-motion: reduce` the same way.
- **Verification.** Each ticket ends with a desktop/web screenshot pair for
  the states it names. Desktop captures: `.scratch/web-client/parity/shot.ps1`.
  Web captures: the `use-browser` skill against `web_smoke`
  (`cargo build -p roboco-engine --example web_smoke` after `pnpm build`; the
  engine embeds the bundle at compile time). Ticket 1 makes the smoke able to
  show a transcript.
- **Tests.** Pure logic ported from the desktop gets unit tests named after
  the desktop test they mirror (each research file's §4 lists them). Run
  `pnpm -r build` (typecheck) and the package's vitest before marking done.
- **Vocabulary.** `CONTEXT.md`: chat (not session/thread), harness (not
  provider), engine, space, Session only for the pairing credential.

## Ticket map

Numbered in dependency order. "Blocked by" is authoritative in each file.

| # | Ticket | Blocked by |
| --- | --- | --- |
| 01 | Smoke fixture renders a transcript | — |
| 02 | Foundation tokens | — |
| 03 | Client settings store | — |
| 04 | Deletion pass (INVENTED items) | — |
| 05 | State fixes: nav history, send ids, optimistic echo | — |
| 06 | Titlebar and main-column chrome | 02, 03, 05 |
| 07 | Right pane host and multi-instance tabs | 02, 03 |
| 08 | Sidebar | 02, 03, 05 |
| 09 | Popover primitive | 02 |
| 10 | Pickers and menus | 09 |
| 11 | Add-space palette | 09 |
| 12 | Keyboard | 06, 07 |
| 13 | Composer core | 02, 03, 05, 10 |
| 14 | Completions and wizard | 09, 13 |
| 15 | New-thread route | 10, 13 |
| 16 | Queue panel | 13 |
| 17 | Attachments | 13 |
| 18 | Transcript rows | 01, 02, 05 |
| 19 | Tool groups | 18, 22 |
| 20 | Rail, badges, loaders | 18 |
| 21 | Markdown parity | 02, 18 |
| 22 | Changes pane | 07 |
| 23 | Review comments | 13, 18, 22 |
| 24 | Files tree and search | 03, 07 |
| 25 | Files preview and editor | 24 |
| 26 | Terminal | 03, 07 |
| 27 | History pane | 07, 22 |
| 28 | Settings shell and Appearance | 02, 03 |
| 29 | Remaining settings sections | 03, 12, 28 |
| 30 | Sounds and notifications | 03, 05 |
| 31 | Fleet | 05, 08 |

Work the frontier: any ticket whose blockers are all done. `/implement` one
ticket per fresh context window.

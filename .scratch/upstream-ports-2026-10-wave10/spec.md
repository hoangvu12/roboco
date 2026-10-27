# Upstream ports 2026-10 wave 10: the full re-audit (78 tickets)

Status: **PLANNED (2026-10-28), not started.** Planning only: no ports
have run and the mirror isn't recreated yet (ticket 01). 75 tickets are
open for work: 73 `ready-for-agent`, plus 2 `ready-for-human` (44, the
background engine design; 57, the GPUI fork push). Three tickets (76–78)
are `needs-triage` and wait on product calls the user hasn't made yet.

Source: the full re-audit `.scratch/upstream-drift/2026-10-28.md`
(plain overview: `2026-10-28-summary.md`). It covers every upstream
commit from the fork point through `upstream/main` = `31d26eeb6`
(v0.2.127, including the late window `5d2b91901..31d26eeb6`), all open and
closed-unmerged upstream PRs, and a feature-level pass comparing desktop
and iPhone to Android. Evidence rows live in
`.scratch/upstream-drift/audit-2026-10-28/` (`part-*.md`, `pass2-*.md`,
`pass3-*.md`). Audited against Roboco `85be4485a`; `main` is now
`1ccaa3a8b`, which adds only the PR #34 merge and one CI timeout commit,
so every row still holds. Item ids (A1–A47, A36b, N1–N8, I1–I3, G1–G2)
are the audit's ids. Each ticket cites the ids it carries.

Unlike waves 3–9, this wave is not one upstream window. It is the
remainder after a re-check of everything: about 25 bugs, about 15
features, about 15 Android gaps, the open wave-7/8/9 tickets (absorbed
here, see "Absorbed tickets"), and the #977 slices that wave-9 06
reported as landed but that are still missing (A37).

## Lanes

| Lane | Tickets | Branch (suggested) | What |
|---|---|---|---|
| 0 Setup | 01 | none | Recreate `zeron/main` before any port |
| 1a Fixes: crashes + security | 02–03 | `wave10/fixes-ui` (02), `wave10/fixes-engine` (03) | Markdown/editor crashes, IPC token, sign-in URL guard |
| 1b Fixes: Windows | 04–08 | `wave10/fixes-ui` (04–07), `wave10/fixes-engine` (08) | captions, banners, shortcuts, paths, file links, command titles, agent launch |
| 1c Fixes: agents + orchestration | 09–20 | `wave10/fixes-engine` | MCP, A37 split per harness, engine lifecycle, Claude/OpenCode/Pi smalls |
| 1d Fixes: sidebar + chat | 21–28 | `wave10/fixes-ui` | sidebar, pending sends, forks, transcript/composer/notice smalls, images |
| 2 Messages | 29–32 | `wave10/messages` | captions #1052 → Markdown bubbles #1067 → Android #1062 → one-line sends #1068 |
| 3 Android parity | 33–43 | `wave10/android` | wave-9 18–21 remainder, Add account, relay, picker, providers, drag, smalls |
| 4a Features: engine + agents | 44–56 | `wave10/features-engine` | background engine, Claude subagents, show_image, questions, native-subagents switch, Devin Fusion |
| 4b Features: desktop UI | 57–75 | `wave10/features-ui` (59 rides `wave10/android`) | pin sync, first-run, settings, smooth scroll, rail, hover card, terminal, ⌘K search, panels, #799 half, leftovers |
| 5 Parked | 76–78 | none | needs-triage: waits on user decisions |

## Decisions (recorded user decisions; none new in this wave)

1. **Web client and in-app browser are being removed** (separate spec
   `.scratch/web-removal/`). Ports that need the browser are out (list
   below). Previews stay. Web-only slices are out, as is any web
   fixture that only exists to keep `web/` building. Where a ticket would
   touch `web/` (03's IPC token fixtures, any proto change), it does so
   only if `web/` still exists when the ticket lands.
2. **CI goes to the CI spec** (`.scratch/ci-speed/`). That covers §D, A47,
   the #1051 cursor-shim 30 s first frame, A24 (glibc floor of the
   release tarball, `release.yml` only) and the `3cb03faab` test flake.
   They're referenced here and not ticketed.
3. **Recorded declines stand:** Muse (#978/#891), Android terminals +
   CompanionStrip + the mobile-core terminal (wave-9 22/23), commit
   credit (wave-9 24), zatex/LaTeX math (#912), Zeron Icons, the #975
   WebRTC P2P slice. **Exception, by user decision:** #912's Markdown
   crash and streaming fixes (A36 + A36b) are IN (ticket 02). Math stays out.
4. **Side chats keep their current shape** (wave-9 23). Side-chat polish
   (I3 fork feedback, N6 spawn-card details, the G2 haptic) is ticket
   77, `needs-triage`.
5. **Parked until the user decides** (`needs-triage`, not planned in
   detail): PR board (#484/#866, wave-7 21), native Codex voice
   (#819/#834), native Luau visuals (#900) and the Cursor SDK CI bot
   (`1d00f2960`), all in ticket 76. Open-PR feature candidates (§E) and
   the smaller product calls are in ticket 78.
6. **Open wave-7/8/9 tickets are absorbed** (see the table below). Old
   ticket files are not edited; this spec records their corrections.
7. **wiregen is going away** with the web removal. Tickets say
   "Proto change: yes". If `crates/wiregen` still exists when such a
   ticket lands, run `cargo run -p wiregen -- --check` as before.
   Android sees proto changes through UniFFI, so any FFI-visible change
   also needs `cargo xtask android-core` to build.

## Proto changes

10 (`last_turn_outcome`, `NativeTurnOutcome`), 25 (#780
`CheckoutGitStatus.branch`), 36 (`RelayAgentLoginCallback`), 46
(`SetViewportFocus`), 47 (#1042 additive `proto/agent.rs` +
`doc/schema.rs`), 50 (`ReadChatImageChunk`/`PublishImage`), 52 (`answers`
on Input parts, `view.rs`), 54 (`UserInputQuestion.prefill/multiline`),
55 (`Get/SetNativeSubagents`), 66 (`TerminalSession.host_replies`), 68
(`Session.terminal_activity`, `ProgramStatus`), 69
(`SearchHomeFolders`/`SearchWorkspaceContent`/`WarmWorkspaceSearch`/
`ReleaseHomeFolderSearch`). Ticket 29 adds `crates/proto/src/agent_messages.rs`,
a shared helper module. It has no wire type.

FFI-only (mobile core, no wire type): 33 (none, `delete_session` exists),
34 (`device_login_*`, `agent_login_rules`), 39
(`HostCapabilities.provider_switch`), 40 (`set_harness_enabled`), 41
(`pin_session_at`/`place_session`).

## Defaults (the wave-4–9 rhythm, adjusted; the user may override)

1. One implementer session per ticket, on its lane branch off `main`
   (table above), one commit per ticket (or a numbered series for a long
   one, like wave-9 06's `n/n`). Merge a lane into `main` only after its
   end-of-pass verification. Never push lane branches. Never move the
   mirror except per ticket 01's refresh rule.
2. Targeted checks per ticket while working. One full end-of-pass batch
   per lane: `cargo check --workspace`, the touched crates' suites, the
   ui suite for UI lanes, and Android unit tests + goldens for the
   Android lane.
3. Every port commit records the upstream SHAs/PRs it carries and any
   deliberately excluded behaviour (`docs/reference/upstream-ports.md`).
   Renames follow AGENTS.md (`roboco-*`, `ROBOCO_*`, `sh.roboco.*`).
   Cloud/account-sync/edge/iOS hunks are excluded by intent. iOS
   behaviour is carried into `apps/android` per ADR 0009.
4. **Open upstream PRs are not on the mirror.** Read them with
   `gh pr diff <n> -R zeronsh/zeron` or `git fetch upstream
   pull/<n>/head:upstream-pr/<n>`. Port by intent: there's no upstream SHA
   to cite, so record the PR number and the head commit read.
5. The Windows checkout is the primary host. Windows-only behaviour
   (tickets 04, 05, 06, 08) is verified on it, not inferred from cfg
   gating.

## Order

**Phase 1: fixes.** 01 first (mirror). Then crashes and security: 02,
03. Then Windows: 04–08. Then agents: 09, 10; the A37 split 11
(Claude), 12 (Codex), 13 → 14 → 15 (OpenCode, same file, in that
order), 16 (engine), 17, 18, 19, 20. Then sidebar and chat: 21–28.

**Phase 2: messages.** 29 → 30 → 31 → 32. Ticket 30 needs 02 (both
edit `crates/markdown/src/parser.rs`). Ticket 31 needs 29's proto
parser. Ticket 32 needs 30 and 31.

**Phase 3: Android.** 33 and 34 can start any time. 34 → 35, 36. 37
after 33 (shared sessions screen + goldens). 38 → 39. 40, 41, 42, 43
any time. 59 (Android first-run) after 40 and 58.

**Phase 4: features.** Engine: 44 (needs 03) → 45 → 46; 47 → 48 → 49
(strict order); 50 → 51; 52 → 53; 54 (after 53, same question
surfaces); 55; 56. UI: 57 (pin sync) early, because 62, 63, 66, 67, 69,
70, 71 and 72 run after it. 58; 60 and 55 before 61 (search anchors);
61 before 62. 63 after 30 (it takes #1067's rail hunk). 64, 65 any time.
66 → 67; 68 after 66. 69 → 70. 71 → 72. 73, 74, 75 any time.

**Same-file sequencing.** These files collect many tickets.
- `crates/ui/src/transcript.rs`: 24, 27, 28, 29, 30, 32, 51, 53, 62.
  Land them in number order within a lane. Rebase across lanes.
- `crates/engine/src/sessions.rs`: 16 before 47 and 55.
- `crates/harness/src/opencode/mod.rs`: 13 → 14 → 15.
- `crates/harness/src/claude/{mod,normalize}.rs`: 11 → 18 → 47–49.
- `crates/harness/src/codex/mod.rs`: 09 and 12. Land 12 first if both
  are open.
- `crates/mobile/src/layout/rows.rs`: 31 → 32 → 53.
- `crates/ui/src/composer.rs`: 25, 28, 53.
- `crates/ui/src/state.rs`: 22, 23.

## Absorbed tickets (corrections from audit §G; old files left as is)

| Old ticket | Old label | Correct state | Wave 10 |
|---|---|---|---|
| wave-9 04 (#970), wave-9 10 (#966 provider switch) | open | **done in code** | none |
| wave-9 06 (#977) | ready-for-human, "all landed" | **PARTIAL**: 14 slices missing (A37, N8, A26) | 11, 12, 13, 14, 16, 17 (+ 08 for `claude.exe`) |
| wave-9 11 (pin sync to `0dc69aa25`) | ready-for-agent | target stale: upstream now pins zui `b564a34`/gpui-component `59f89243` | 57 |
| wave-9 12 (rail) | ready-for-agent | open | 63 |
| wave-9 17 (terminal overhaul) | ready-for-agent | open; L, split | 66, 67 |
| wave-9 18 (Android error cards + delete) | ready-for-agent | card **rendering landed** in PR #34; rest open | 33, 35 (+34 plumbing) |
| wave-9 19 (bottom bar) | ready-for-agent | open | 37 |
| wave-9 20 (model picker) | ready-for-agent | open, plus three unlisted needs and I2 | 38, 39 |
| wave-9 21 (Android smalls) | ready-for-agent | #980 already on Android; #1010 icon has **no Android target** (CompanionStrip); only #1014 left | 42 |
| wave-9 22 (mobile-core terminal) | file still says ready-for-agent | **wontfix** (declined with 23) | none |
| wave-7 08 (shared-width panels) | ready-for-agent | open; L, split | 71, 72 |
| wave-7 13 (#552) | ready-for-agent | **close: cloud no-op** (`watch_connectivity` only publishes the default) | none |
| wave-7 14 (#400) | ready-for-agent | **close: superseded** by `672cc1444` | none |
| wave-7 15 (Cursor rich refs) | ready-for-agent | **done** in `aade2ce02` | none |
| wave-7 16 (#544) | ready-for-agent | narrow to the boot salvage sweep (A16) | 16 |
| wave-7 17 (#528/#535) | ready-for-agent | #528 remainder = A8; #535 remainder = metadata caps + quiescence re-check | 05, 75 |
| wave-7 19 (pin sync) | ready-for-agent | superseded twice | 57 |
| wave-7 20 (#856 search) | ready-for-agent | open; L, split | 69, 70 |
| wave-7 21 (PR board) | needs-triage | parked | 76 |
| wave-7 22 (WebView2) | needs-info | **out**: in-app browser removal | none (non-browser #813 slices → 28) |
| wave-8 13 (hover card) | ready-for-agent (gated) | **unblocked** (wave-7 07 landed in `189c3d5a3`) | 64 |
| wave 3–6 tickets still labelled `ready-for-*` | open | verified ported (audit parts 03–05) | none |

Also from §G: `docs/harness-skill-completion.md:48-49` names
"Settings → Agents"; it should be Settings → Shortcuts → Composer
completion (ticket 75).

## Out of scope (with reasons)

- **Browser-dependent (removal):** #813 Windows WebView2 host (wave-7
  22), #1039 WPE/dma-buf, #814 element annotations, #573 persistent
  profiles, #1012's browser half (`local_file_address`), A34
  `browser-fixture` build, #1026's PR-link destinations (in-app browser
  + parked PR view), #1066's browser-fixture typing race, #1005's
  `browser/{macos,view}.rs` hunks, #1018's browser anchors.
- **Web-only (removal):** A35's web half (update-failure wording/Retry),
  A15's web half, web parity, web fixtures.
- **CI (→ `.scratch/ci-speed/`):** §D (Windows `push` trigger,
  `codex_login_resolution` + `test-windows-startup.ps1` (#313), `save-if`
  (#864/`710b06ff8`), nextest + shared build, Windows job split, LLD/Dev
  Drive (#1081), SHA-pinned actions + Dependabot, `actions/*@v4` bump
  (#976), `3cb03faab`), A47 `mobile-bindgen`, #1051 cursor-shim first
  frame, A24 glibc floor.
- **Declined:** see Decisions 3. Also the Pi native-RPC rewrite (wave-4
  #30) and Pi's `/steering` + `/follow-up` built-ins (they change Pi's
  global modes).
- **Cloud/edge (ADR 0004):** #966's cloud parts, #1060's `edge/` half,
  #1038's edge connectivity states, ~90% of #544, #1076's wake/heartbeat.
- **macOS-only:** #1013 key-equivalent table. Ticket 04 does one manual
  AZERTY check on Windows.
- **Not taken:** cancelling a pinned-session drag on a remote reorder
  (pass3-skips-1 G1). Roboco applies the drop to the new order, which is
  arguably better. Duplicates: #741 (= #742), #629 (= #738; both
  superseded by #813's merged answer card, ticket 28).

## Questions for the user

1. **Background engine (44):** should quitting the window keep agents
   running by default, or should that be opt-in? `roboco engine` is
   taken by pairing; the proposed name is a hidden `roboco
   background-engine` subcommand plus `ROBOCO_MANAGED_ENGINE`.
2. **Pin sync (57):** take upstream zui `b564a34` whole (it includes the
   dma-buf atlas work for the WPE browser we're removing), or
   cherry-pick only `361ff20` + `0dc69aa`? Either way, the push to
   `hoangvu12/zui` is yours to approve.
3. **Login relay (36):** a phone relays a sign-in callback to the
   computer it's paired with, over the normal client RPC. Is that fine
   under ADR 0011 ("reachability only, never data")? The proposed
   reading: yes. It's a direct paired client, not a fleet introduction.
4. **Android layout (42):** I1 keeps a landscape phone on the phone
   layout. Should a portrait tablet stay on the phone layout too (today
   it does, at < 840 dp)? Should #942's uneven insets go in at all?
5. **Parked items (76–78):** PR board, voice, Luau visuals, Cursor SDK
   bot, side-chat polish, open-PR candidates, #449 skill-completion
   default.

## User decisions (2026-10-28)

1. **Background engine (44):** on **by default**: closing the window keeps agents running, with a setting to turn it off. The hidden command `roboco background-engine` and `ROBOCO_MANAGED_ENGINE` are fine.
2. **GPUI pin (57):** take upstream zui (and gpui-component) **whole** at the current pin, keeping `hoangvu12/zui` a mirror of upstream. Pushing to the forks still needs the user's OK at that time.
3. **Sign-in relay (36):** agreed, it fits ADR 0011 (a direct pairing; reachability only).
4. **Android layout (42):** a tablet held upright follows the iPad (split when wide enough). Skip #942's uneven insets.
5. **Parked (76–78):** no decision yet; they stay needs-triage.
6. **A24** lives in the CI spec (ticket 21 there).
- Implementation starts only on the user's explicit go.

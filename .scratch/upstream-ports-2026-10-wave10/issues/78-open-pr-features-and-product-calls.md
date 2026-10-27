# 78 — Open-PR feature candidates and smaller product calls (decision needed)

**Lane:** 5 Parked. **Effort:** n/a until picked.

Unmerged upstream PRs that add features (audit §E), plus merged or
closed items that are product calls rather than bugs. Not planned in
detail. The user picks; picks become tickets. Full rows:
`audit-2026-10-28/part-prs-A.md`, `part-prs-B.md`, `part-closed.md`.

**Higher value (§E):**
- Opening and launching: `roboco <path>` + single instance + PATH (#854);
  keep the computer awake during runs (#701); launch args for chats
  (#926); Claude in Chrome (#928).
- Search: find-in-thread + message search (#1080; engine-local FTS5);
  chat-content search in Ctrl+K and as an agent tool (#516/#640). This
  overlaps ticket 70.
- Workflows: workflow runs as subagents (#784) and their transcripts (#785).
- Smaller: Ctrl+click terminal links (#717), interface scaling (#705),
  idle-close setting (#960), long pastes become chips (#782), right-pane
  tab number shortcuts (#790), "Side chat" in the right pane's + menu
  (#908), explorer refresh button (#788), a hover button for the project
  menu (#859), rebind the palette shortcut (#879), harness mark on chat
  chips (#925), Shift+Ctrl+Backspace (#932).

**Medium:** sidebar usage cards (#1053), split panes (#939), move a side
chat to the sidebar (#987), session notes (#965), MCP `add_project`
(#904), project from a GitHub repo (#897), any ACP agent (#884),
multiple Antigravity accounts (#880), per-chat token totals (#783),
"What's new" window (#698), a live files-changed pill (#731), editable
path bar (#674), add-to-chat on selection (#618). Browser-tab mention
(#946) is out (browser removal).

**Product calls found among fixes:**
- #449: the `$`-skills + separate `/` completion default for every
  harness (`settings.rs:834` is Codex-only today).
- #938: neutral inline-code styling ("personal preference").
- #99: show an ErrorChip when the quiesce watchdog force-settles a
  silent prompt turn (`engine/src/sessions.rs:5051-5061`).
- #1033 (draft): show when a reply *finished*, not when it started
  (cheap: `created_at + duration_ms`).
- #755: OpenCode credentials/discovery/Windows-startup bundle. L and
  unreviewed; its OpenCode 2.x claims need verifying before any slice.
- Closed-unmerged, lower priority: #427 zoom shortcuts, #9
  PageUp/PageDown/Home/End in the transcript, #22 terminal tab shortcuts
  (Ctrl+Shift chords), #709 nested login-shell probe, #919 inactive-window
  blur and paused animations.

**Low or risky (recommend no):** libghostty terminal (#1006, needs Zig),
Oh My Pi (#930/#87), GitLab (#865), desktop pets/music (#944/#822).
The permissions/plan/sandbox series (#708–#724) is unmerged upstream, so
wait.

**Blocked by:** user picks.

**Status:** needs-triage

**Source:** `2026-10-28.md` §E, §A closed-unmerged; `part-prs-A.md`,
`part-prs-B.md`, `part-closed.md`.

- [ ] User picks recorded; each pick becomes a ticket in this wave or the next

## Comments

# 12 — Explorer: group finished subagents, running counts (upstream #647) — SUPERSEDED, folded into ticket 07

**Status:** superseded (2026-10-22) — upstream #895 (`591824881`)
deleted this feature's files-panel surface (`files/subagents.rs`) as
the session card absorbed it. This ticket's surviving intent — the
`Session.running_subagents` wire entity and as much of the pill shape
as #895 consumes — folds into ticket 07's superset. Do not start this
ticket; see `07-session-card-titlebar-toggles.md` and the 2026-10-22
drift note.

**What to build:** Port `0c4835d2` (#647): the Subagents section lists
running subagents as plain rows and folds everything finished into a
Finished dropdown (closed by default) with Completed and Failed lists,
each collapsing on its own and paging at ten rows. A "● N" running-count
pill appears on sidebar chat rows (in the timestamp's place while
compact, leading the status label otherwise), as a breathing count badge
on the titlebar's explorer button, and beside the Subagents title. To
badge unopened chats, `Session` gains `running_subagents: u32` (optional
on the wire, stale rows count as zero), published by the engine as
subagents open and settle; a live subagent doubles as a session
heartbeat.

**Why now (verified):** no `running_subagents` anywhere in
`crates/proto/src/entities.rs`/`view.rs` or engine `sessions.rs`; no
`running_pill.rs`, no `files/subagents.rs`; the explorer's subagent rows
list everything flat (`subagent_rows`,
`crates/ui/src/files/sections.rs:256`, `Section::Subagents` at
`sections.rs:67`); the titlebar explorer slot
(`crates/ui/src/shell/tabs.rs:524-527`) has no count badge. Carried over
from the 2026-10-09 window; a wire change.

**Steps:**
1. `git show 0c4835d2` (26 files, +1219/−88).
2. Port the proto `Session.running_subagents` field (optional) and the
   engine publication in `sessions.rs`/`workspace_host.rs`.
3. Port the explorer grouping (`sections.rs`, new `subagents.rs`) and
   the pill (`running_pill.rs`), the sidebar chat-row pills, and the
   titlebar badge.
4. Port the tests; run the wiregen gate.

**Excluded upstream hunks:** `mcp/tools.rs` 1-liner and doc/workspace
hunks by intent; cloud surfaces if any.

**Blocked by:** None (sequence after 06/07 to avoid sidebar/tab badge
conflicts with the same rows).

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-proto -p roboco-engine
-p roboco-ui` + explorer tests; **wiregen gate must run**. Source:
`.scratch/upstream-drift/2026-10-09.md` § `0c4835d2`.

- [ ] `Session.running_subagents` on the wire; engine publishes it
- [ ] Finished/Completed/Failed grouping with paging
- [ ] Running-count pills (sidebar rows, titlebar badge, section title)
- [ ] Wiregen gate green
- [ ] Commit records upstream SHA `0c4835d2` (#647)

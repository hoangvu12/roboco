# 13 — Avoid transient connection notices when reopening dormant chats (upstream #552)

**What to build:** Port `45d34678` (#552): reopening a dormant chat no
longer flashes transient connection notices. 4 files, +255/−33:
`crates/engine/src/doc_host.rs` (+131), `crates/proto/src/entities.rs`
(+19), `crates/ui/src/shell.rs` (+62), `crates/ui/src/state.rs` (+76).

**Why now:** an older genuinely-missed fix (2026-09-25) from the
pre-wave-6 windows; roboco holds all four surfaces and the notice-flash
behavior rides the same state machinery.

**Steps:**
1. `git show 45d34678` — port the engine's dormant-reopen suppression
   and the UI notice gating.
2. Adapt proto deltas onto roboco's `entities.rs` shape; run the wiregen
   gate if wire types moved.
3. Port the tests.

**Excluded upstream hunks:** none expected; verify no chat2-room/cloud
semantics sneak in via `doc_host.rs` (adapt by intent).

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine -p roboco-proto
-p roboco-ui` + doc_host/state tests; wiregen check if proto moved.
Source: `.scratch/upstream-drift/2026-10-11.md` § C.

- [ ] Dormant-reopen notices suppressed end to end
- [ ] Tests ported; wiregen green
- [ ] Commit records upstream SHA `45d34678` (#552)

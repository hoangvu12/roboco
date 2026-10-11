# 10 — Queue polish, drag a chat from the sidebar to mention it (upstream #855)

**What to build:** Port `b65b4753` (#855): queue polish plus dragging a
chat from the sidebar into the composer mentions it. The mention
substrate is new proto (`chat_mentions.rs`, `invocation.rs`) and
composer chip work (`composer/chip.rs`) — it builds directly on the #775
chips ported in ticket 05. 11 files, +652/−71.

**Why now:** the chips substrate (ticket 05) is the prerequisite; with
it landed, this is the natural follow-on and part of the queue/composer
parity run.

**Steps:**
1. `git show b65b4753` — port the proto mention deltas (adapted onto
   ticket 05's landed shapes), the drag-source/drop-target wiring in
   `shell.rs`/`shell/spaces.rs`/`shell/chat_dropzone.rs`, and the queue
   polish.
2. Port the tests (drag-to-mention, queue restore interplay with chips).

**Excluded upstream hunks:** cloud/sync hunks if any.

**Blocked by:** 05.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-proto -p roboco-ui` +
queue/dropzone tests; wiregen check if proto shapes moved. Source:
`.scratch/upstream-drift/2026-10-11.md` § `b65b4753`.

- [ ] Drag-to-mention ported end to end
- [ ] Queue polish carried by intent
- [ ] Tests ported; wiregen green
- [ ] Commit records upstream SHA `b65b4753` (#855)

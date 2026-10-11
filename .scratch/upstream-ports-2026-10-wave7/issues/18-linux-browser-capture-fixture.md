# 18 — Linux browser-capture fixture: wait for mapped and presented frames (upstream #777 slice)

**What to build:** Port the one roboco-relevant slice of `e799aecc`
(#777): Linux browser captures now wait for mapped and presented frames
— `capture_frame` refreshes, draws, presents and pauses before reading
native pixels, and `wait_for_visible_window` yields until X11 maps the
window (bounded 10 s). Roboco holds the same fixture with the same env
var (`ROBOCO_BROWSER_CAPTURE_WINDOW`,
`crates/ui/examples/browser-fixture.rs`, `browser-fixture/linux.rs`);
the fixture flakiness this fixes applies to our linux-browser CI lane
directly.

**Why now:** carried over from the 2026-10-09 window; small, fixes CI
flakiness. The rest of #777 (durable remote sends, host wakes) is
cloud-coupled and excluded by ADR 0003/0004.

**Steps:**
1. `git show e799aecc -- crates/ui/examples` — port the
   `browser-fixture.rs` (+51) and `linux.rs` (+49) hunks (and the
   `transcript_links.rs` +13 if it belongs to the fixture).
2. Adapt `ROBOCO_*` naming; skip `edge/` and `scripts/` hunks unless the
   script line is ours.

**Excluded upstream hunks:** everything except the fixture files —
apps/ios, crates/client, edge/*, doc-host wake persistence are cloud
surfaces roboco does not have.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** the linux-browser fixture lane
(`scripts/test-linux-browser.sh` if present locally, else compile-only
`cargo check -p roboco-ui --examples`). Source:
`.scratch/upstream-drift/2026-10-09.md` § `e799aecc`.

- [ ] Capture waits for mapped/presented frames; window-mapped wait bounded
- [ ] Commit records upstream SHA `e799aecc` (#777 fixture slice only)

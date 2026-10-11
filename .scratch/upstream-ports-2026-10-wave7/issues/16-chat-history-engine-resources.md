# 16 — Prevent chat history from exhausting engine resources (upstream #544)

**What to build:** Port `ed3df8fb` (#544): chat history no longer
exhausts engine resources. The upstream change is engine-robustness work
centered on `crates/engine/src/doc_host.rs` (+947) with
`chat2_host.rs` (+20), `chat_persistence.rs` (+8), `http_error.rs`,
`lib.rs`, and a new `doc_host_sync_tests.rs` (+436). Port by intent
onto roboco's doc_host (which the #748/#818 slices already reshaped) —
reconcile with our diverged test fixtures, never by replacing whole
files.

**Why now (verified):** the biggest quiet miss of the older windows
(2026-09-24); roboco inherits the resource-exhaustion surfaces
(long-lived chat history reads on the doc host) and none of the wave
windows ticketed it. The perf wave (#818/#820) addressed boot/memory
and snapshots, not history-driven exhaustion.

**Steps:**
1. `git show ed3df8fb` — read the full change; identify which resource
   fronts apply to engine-local roboco (no edge/chat2-room transports).
2. Port the doc_host history-read bounding/paging behavior and the
   persistence guards; adapt chat2-host hunks onto roboco's engine-local
   chat transport.
3. Port the sync tests; reconcile with roboco's existing doc_host tests.

**Excluded upstream hunks:** any chat2-room/edge delivery hunks; adapt
http_error coupling to roboco's error surfaces.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine` + doc_host
tests. Source: `.scratch/upstream-drift/2026-10-11.md` § C.

- [ ] History-read bounding/paging ported onto doc_host
- [ ] Persistence guards ported
- [ ] Sync tests ported and passing
- [ ] Commit records upstream SHA `ed3df8fb` (#544)

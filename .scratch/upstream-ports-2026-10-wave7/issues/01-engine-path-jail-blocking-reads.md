# 01 — Engine: lexical path jail and blocking reads (upstream #813 engine slice)

**What to build:** Port the engine half of `e5466c53` (#813): requested
paths are lexically jailed in the engine **before any filesystem call** —
upstream's bug canonicalized `\\host\share` UNC paths, which opens an SMB
session and leaks the user's NTLM hash before the jail check runs. UNC,
device, and drive-relative paths are refused outright; `read_chunk` moves
to the blocking pool; image decodes bounded by the shared raster limits
and base64 moved off the UI thread ride along where the engine owns them.

**Why now (verified 2026-10-11):** `crates/engine/src/uploads.rs:335`
runs `std::fs::canonicalize(path)` *before* comparing against the
canonicalized roots (`uploads.rs:345`) — exactly the leak shape upstream
fixed — and `read_chunk` runs synchronously on the RPC worker
(`crates/engine/src/rpc.rs:2920`, no `spawn_blocking`). Recommended
"port now-ish" on 2026-10-09 and skipped by the perf wave; it is the top
security item in the backlog.

**Steps:**
1. `git show e5466c53 -- crates/engine` — the jail hunks in
   `uploads.rs` (+ the UNC/device/drive-relative refusal) and the
   `rpc.rs` read_chunk `spawn_blocking` move, with tests.
2. Port the jail: reject UNC (`\\`-prefixed), device paths
   (`\\.\`, `\\?\`), and drive-relative forms (`C:foo`) lexically;
   compare the requested path against the allowed roots by
   lexical/normalized prefix, and only canonicalize *after* the path is
   already inside a root — never as the gate itself.
3. Move `read_chunk` (`rpc.rs:2920`) onto `tokio::task::spawn_blocking`,
   matching the existing blocking-call pattern in the file.
4. Port/adaptive-write the upstream tests for the jail (UNC refused,
   device refused, drive-relative refused, traversal blocked, in-root
   reads still work) into roboco's engine test surface.

**Excluded upstream hunks:** everything outside `crates/engine` — the
Windows WebView2 browser host, tool-image previews, and selectable tool
output belong to ticket 22 / the UI side; `apps/ios` and `crates/client`
hunks are cloud surfaces roboco does not have.

**Blocked by:** None.

**Status:** ready-for-agent

**Verification budget:** `cargo check -p roboco-engine` +
`cargo nextest run -p roboco-engine --lib` (uploads/jail tests) inside
the ticket; no wire change. Source:
`.scratch/upstream-drift/2026-10-09.md` § `e5466c53` quick-port slice,
`.scratch/upstream-drift/2026-10-11.md` carry-over table.

- [ ] Lexical jail + refusal tests ported into `uploads.rs`
- [ ] `read_chunk` on the blocking pool
- [ ] Commit records upstream SHA `e5466c53` (#813 engine slice)

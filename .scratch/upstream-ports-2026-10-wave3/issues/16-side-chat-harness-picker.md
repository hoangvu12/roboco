# 16 — Allow choosing a harness for new side chats

**What to build:** New side chats offer a harness picker; the inherited
config survives the discovery checks (only the harness swaps).

**Blocked by:** 15.

**Status:** ready-for-agent

**Upstream SHAs:** `f739b5de` (#590) — `crates/ui/src/composer.rs` (43+),
`crates/ui/src/pickers.rs` (170+), `crates/ui/src/state.rs`. Web parity:
harness selection on the web side-chat composer if the web picker supports
it (mirror the desktop semantics).

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted ui
picker/composer tests; web touched suites.

- [ ] Harness picker on new side chats (desktop; web where supported)
- [ ] Tests green
- [ ] Port commit records upstream SHA

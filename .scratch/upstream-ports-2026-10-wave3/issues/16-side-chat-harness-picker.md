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

## Comments

**Branch:** `wave3/16-harness-picker` → merged into main `Merge wave3/16`. Commits `0bb95154` (desktop: side_chat_harness_editable, pickers exempt the editable window + replace inherited provider settings, create_side_chat stamps resolved config pre-create; ticket-15's test extended to upstream's #590 end-state) + `fc041238` (web: sideChatHarnessEditable prop, pickHarness side-chat branch, isHarnessLocked reshaped). Post-merge re-verified in the 19 boundary run.

**Judgment calls:** pickers commit still hands the draft (composer guards the RPC — matches desktop split); isHarnessLocked reshape (old form had no consumer); noted stale modelOptions on web new-chat canvas (one-liner follow-up candidate, out of scope).

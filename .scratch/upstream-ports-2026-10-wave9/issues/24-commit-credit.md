# 24 — Commit credit: agent commits co-authored by the app (upstream #972, `e680d5c98`) — DECISION

**What to build:** If the user takes it: port `e680d5c98`'s engine core —
new `engine/src/commit_credit.rs` (+368): every agent spawn's environment
points `core.hooksPath` at wrappers in the engine's data dir
(GIT_CONFIG_COUNT, appended to any already set); each wrapper runs the
repository's own hook first, then commit-msg appends a
`Co-authored-by:` trailer once per message. Per-device Get/SetCommitCredit
RPCs → a plain roboco settings toggle (single device; the device switcher
adapts out). UI hunk lands where roboco's General settings live.

**Decision needed (spec § Decisions):** the trailer identity
(`roboco[bot]`?) and the default (upstream: off on a user's device, always
on for cloud — roboco has no cloud, so: on or off?).

**Excluded upstream hunks:** `cloud_runner.rs` test, cloud "always on"
machinery, iOS files.

**Blocked by:** the user's decision.

**Status:** wontfix (declined by the user, 2026-10-25)

**Verification budget:** engine commit_credit tests. Source:
`.scratch/upstream-drift/2026-10-25.md` § follow-ups.

- [x] Decision recorded (identity + default; yes → port / no → wontfix)
- [ ] If yes: hooks-path engine core + settings toggle ported
- [ ] Commit records upstream SHA `e680d5c98` (#972)

## Comments
- 2026-10-25: **DECLINED by the user** — no commit-credit trailer.

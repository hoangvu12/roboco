# 02 — PR badges: always show the PR icon, drop the # prefix

**What to build:** Pull-request badge rows always render the PR icon (it
previously vanished in some states) and drop the `#` prefix from the number.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `f8f9c97f` — `crates/ui/src/change_requests.rs` only. The
`apps/ios/Zeron/Views/PullRequestBadge.swift` hunk is iOS-only: skip.

**Verification budget:** `cargo check -p roboco-ui -j 3`; targeted nextest
`test(change_request) or test(pr_badge)` in ui. Trivial port.

- [ ] PR icon always shown, no # prefix
- [ ] UI tests green
- [ ] Port commit records upstream SHA

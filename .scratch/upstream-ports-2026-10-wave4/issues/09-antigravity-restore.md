# 09 — Antigravity: restore detection, updates, sign-in suppression

**What to build:** Restore antigravity support end-to-end: resolve the
managed acp server for update checks (not path only); read the release
from the server build label (not the branch revision); check the acp
registry for new releases and install them from roboco — pinned sha-512
everywhere, google code signature on windows (macos cfg carried verbatim
per upstream, compile-gated), and the reported build label must match the
registry version; launch the newest trusted install and prune superseded
versions no running process uses; stop background probes and chat turns
when the server prompts for google sign-in on stdout/stderr, without
retrying the probe; suppress the sign-in browser on windows and fall back
to our noop-browser where no true binary exists.

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `d1dc29f6` (#617) — 11 files. Port targets here:
`crates/harness/src/{acp/mod.rs, archive_install.rs, catalog.rs, lib.rs,
Cargo.toml}`, `crates/engine/src/harness_updates.rs`,
`crates/harness/tests/acp.rs` + `fixtures/fake-antigravity-acp.sh` (new).
Upstream also creates `crates/harness/src/code_signature.rs` — create it
here (google authenticode leaf on windows, macos cfg verbatim, linux
stays digest-only). Skip: `apps/zeron/src/main.rs` +
`apps/zeron/tests/noop_browser.rs` hunks — our noop-browser lives
engine-side (`ensure_noop_browser` in `crates/engine/src/agent_accounts.rs`);
carry the suppression assertion into an engine-side test instead.

**Verification budget:** `cargo check -p roboco-harness -p roboco-engine -j 3`;
targeted nextest `-p roboco-harness acp` + fake-antigravity fixtures;
windows cross-check for `windows_process.rs` stdio types (upstream noted
import fixes).

- [ ] Detection + registry-checked updates with pinned sha-512 and
      windows signature verification
- [ ] Newest trusted install launched; superseded versions pruned
- [ ] Sign-in prompt stops probes/turns without retries; browser
      suppressed via noop-browser
- [ ] Fake-antigravity fixture tests green
- [ ] Port commit records upstream SHA

## Comments

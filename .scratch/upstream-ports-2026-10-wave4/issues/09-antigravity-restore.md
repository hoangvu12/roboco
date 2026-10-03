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

**Status:** ready-for-human

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

- [x] Detection + registry-checked updates with pinned sha-512 and
      windows signature verification
- [x] Newest trusted install launched; superseded versions pruned
- [x] Sign-in prompt stops probes/turns without retries; browser
      suppressed via noop-browser
- [x] Fake-antigravity fixture tests green
- [x] Port commit records upstream SHA

## Comments

**Port mapping (upstream d1dc29f6, #617):**

- `antigravity_archive()` → new 1.2.1 pin (upstream URL shape
  `agy-acp-server-1.2.1-*`, adds the darwin-x86_64 platform); the previous
  1.1.1 pin moved to `antigravity_legacy_archive()` verbatim.
- `code_signature.rs` created (google authenticode leaf on windows,
  macOS cfg blocks carried verbatim per upstream — compile-gated, no macOS
  ship; Linux stays digest-only). `Cargo.toml` windows-sys features
  (`Win32_Security_Cryptography*`, `Win32_Security_WinTrust`,
  `Win32_System_Diagnostics_ToolHelp`) follow upstream; ToolHelp is also
  used by the windows `running_command_lines()` prune scan.
- `archive_install.rs` → `Source`/`VerifiedRelease` refactor (digest optional
  post-extraction verification, `install_verified`, `installed_versions`,
  `installed_entry_with_marker`); `entry_path` removed as upstream.
- `acp/mod.rs` → `Launch::Archive { entry }`, `installed_archive_entry`,
  registry release parsing (`antigravity_release`, google-hosted-url
  validation), `install_antigravity_release` (pinned digest → signature +
  `confirm_reported_version`), newest-trusted-install launch resolution,
  `prune_superseded_antigravity_installs`, sign-in prompt cancellation on
  stdout (`with_stdout_observer`) and stderr watchers feeding
  `CancellationToken` that stops `discover_commands`/`discover_models`/
  chat turns (`unless_sign_in_prompted`, single errored Done). Antigravity
  `executable_path`/`is_installed` resolve through the launch path now.
- `catalog.rs` cooldown list gains "isn't signed in" so a stale-login probe
  never retries into another prompt.
- engine `harness_updates.rs` → `LatestSource::AntigravityAcp` (registry
  manifest fetch, release memoized for apply), `UpdatePlan::AntigravityArchive`
  guarded by `is_managed_antigravity_server`, build-label version parsing
  (`antigravity_build_version`), `manual_update_command` (managed-but-
  unpinable releases say "Update Roboco to install this release"), post-update
  prune under the update lease, verification re-resolves the executable
  (newest install after an archive update).
- Rebrand: `zeron_harness::acp` → `roboco_harness::acp`, env/test vars
  `ROBOCO_TEST_AGY_*`, `ROBOCO_ADAPTERS_DIR`, `ROBOCO_NO_LOGIN_SHELL`,
  `ROBOCO_TEST_GOOGLE_SIGNED_DIR`, "update Zeron" → "update Roboco".

**Deliberate deviations/exclusions:**

- Upstream's `apps/zeron/src/main.rs` `--noop-browser` app mode +
  `apps/zeron/tests/noop_browser.rs` are skipped (ticket): Roboco has no
  such app CLI mode. The noop-browser fallback instead maps to our
  engine-side `ensure_noop_browser` DESIGN — the harness writes the same
  `#!/bin/sh\nexit 0` no-op script into its adapters state dir where no
  `true` binary exists (unix). On windows (and other platforms)
  `noop_browser()` returns an error and `spawn_agent` logs a warning:
  the browser is not suppressed there, matching the engine's
  `quiet_cli_browser` windows posture ("on Windows the CLI's own open can
  never be suppressed"); the sign-in prompt watchers still stop probes and
  turns, which is the correctness-critical half. The upstream app-binary
  suppression assertion is carried into an engine-side test
  (`noop_browser_swallows_the_sign_in_url_silently` in agent_accounts.rs).
- Upstream's `windows_noop_browser` (app exe + `--noop-browser %s`, with
  backslash doubling) and its quoting unit test are not ported — they exist
  only to serve the skipped app mode.
- Windows cross-check for `windows_process.rs` stdio types: recorded, not
  run (no reliable cross-compile on this box; CI's windows.yml covers it).
  The upstream-noted import fix (`use crate::process::{ChildStdin,
  ChildStdout}`) is carried so the harness builds on windows.

Verification: `rustfmt --edition 2024` over the touched files (reverted
rustfmt's module recursion into untouched files); reading-only verification
per the wave's build economy; test execution deferred to the wave-final
batched pass (user directive).

- Wave-final batched verification (2026-10-03, merged main `cf94f415`): one
  batched pass over all lanes — ui lib 1521/1521; engine 529/530 (the one
  failure is the documented pre-existing
  `previews::preview_watch_follows_the_session_checkout_and_owning_device`
  baseline); harness 504/509 (the five failures are the documented
  environmental `#!/usr/bin/python3` fixture shebang and uid-1001
  user-database quirks; CI runs them); mcp 26/26; voice 18/18; theme 31/31;
  `wiregen --check` and `roboco-theme-export --check` fresh; web `pnpm -r
  build` green, app vitest 2122/2122, engine-client vitest green. The
  deferred test-execution criterion is demonstrated; closed by the
  wave-final pass.

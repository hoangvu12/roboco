# 08 — Providers: update Homebrew-installed CLIs with brew

**What to build:** A provider CLI installed as a Homebrew cask or formula
(`Caskroom/<token>/<version>/…` or
`Cellar/<token>/<version>/{bin,libexec}/…`) gets an Update action that
runs `<prefix>/bin/brew upgrade --cask|--formula <token>` as argv — the
brew binary is the one in the prefix that owns the CLI. The installable
version comes from formulae.brew.sh and `brew info`; when an upstream
release is newer than Homebrew's, the row names the brew command (Update
still runs it; idle auto-update waits for Homebrew). npm globals under
Homebrew's Node keep their own updaters. Detection is macOS/Linux only.

**Blocked by:** 09 — both touch `harness_updates.rs`; #617's
registry/release machinery lands before #661's brew policy rows.

**Status:** ready-for-agent

**Upstream SHAs:** `651aa7a9` (#661) — 2 files, 34 insertions:
`crates/harness/src/harness_updates.rs` region + catalog. We ported the
agent-CLI update lifecycle from upstream `35a9139a` in wave 3
(`7811c20f`), so the detection/update-policy rows map directly.

**Verification budget:** `cargo check -p roboco-harness -j 3`; targeted
nextest harness_updates tests (Caskroom/Cellar path parsing, brew argv
shape, version-name behavior).

- [x] Caskroom/Cellar CLIs detected; brew prefix resolved
- [x] Update runs brew with the right verb + token; newer-upstream rows
      name the command; idle waits for Homebrew
- [ ] Tests green
- [x] Port commit records upstream SHA

## Comments

**Port mapping (upstream 651aa7a9, #661):**

- Engine `harness_updates.rs`: `HomebrewPackage` (brew prefix + token taken
  from the `Caskroom`/`Cellar` layout, never installer output; token guarded
  against brew options and local `.rb`/`.json` file loads),
  `UpdatePlan::Homebrew`, `homebrew_package` detection ahead of every other
  plan (npm globals under a keg-only Node runtime deliberately stay npm's),
  `finish_homebrew_check` (formulae.brew.sh API + `brew info --json=v2`
  without tap refresh, upstream feed consulted only for the
  "published upstream, not yet in Homebrew" note), apply running
  `<prefix>/bin/brew upgrade --cask|--formula <token>` as argv with
  `NONINTERACTIVE`/`HOMEBREW_NO_*` env, lag-aware verification errors, and
  `automatic_update_ready` refusing to auto-install a release Homebrew has
  not published (the Update button still runs brew).
- `run_command_output_env` splits out of `run_command_output` for the brew
  env; `claude_package_manager_command` now resolves homebrew ownership
  first, so Claude's cask rows say `brew upgrade --cask claude-code[@latest]`
  instead of the old hand-written command, and the cask channel detection
  stays for the channel column.
- UI `crates/ui/src/settings/harnesses.rs`: an Available row names the brew
  command even when the update is applicable (Homebrew lagging upstream);
  the existing label test gains that assertion (Roboco addition — upstream
  changed the label without a test).
- Rebrand: `/tmp/roboco-not-installed/codex` in the ported test; everything
  else is upstream verbatim (no zeron naming in the new code).

**Exclusions:** none — upstream's #661 is fully engine + one UI label hunk;
no CI/edge/iOS/macOS-packaging hunks existed to drop. Detection is
unix-only via `cfg!(unix)` as upstream.

Verification: `rustfmt --edition 2024` on the two touched files (drift
hunks from module recursion reverted by hand); reading-only verification
per the wave's build economy; test execution deferred to the wave-final
batched pass (user directive).

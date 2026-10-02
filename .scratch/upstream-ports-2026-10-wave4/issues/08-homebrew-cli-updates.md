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

**Blocked by:** None.

**Status:** ready-for-agent

**Upstream SHAs:** `651aa7a9` (#661) — 2 files, 34 insertions:
`crates/harness/src/harness_updates.rs` region + catalog. We ported the
agent-CLI update lifecycle from upstream `35a9139a` in wave 3
(`7811c20f`), so the detection/update-policy rows map directly.

**Verification budget:** `cargo check -p roboco-harness -j 3`; targeted
nextest harness_updates tests (Caskroom/Cellar path parsing, brew argv
shape, version-name behavior).

- [ ] Caskroom/Cellar CLIs detected; brew prefix resolved
- [ ] Update runs brew with the right verb + token; newer-upstream rows
      name the command; idle waits for Homebrew
- [ ] Tests green
- [ ] Port commit records upstream SHA

## Comments

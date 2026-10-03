# 24 — Opt-in on-device dictation in the composer

**What to build:** Opt-in on-device dictation for the desktop composer:
a new `crates/voice` crate (parakeet-rs inference, cpal capture, rubato
resample, reqwest+sha2 model download with retained cleanup after
interrupted downloads, all native microphone sample formats, capture that
starts while the model loads and retains audio until it's ready, ALSA
presence detection before Linux activation), a `crates/ui/src/dictation`
module (glass surface, meter, model state, waveform; recording clock;
composer controls morphing into the dictation track while live), a
dedicated Voice settings page (mic selection, model download/verify,
retry clarity), composer integration (dictation preserved while pasted
references resolve, Escape cancels across composer controls, state
refreshed after empty undo/redo, dictated words preserved on blank
recognition, repeated Send preserves unfinished dictation), and the mic
icon. Follow-up #740: the composer mic and attach hover fades are keyed
per instance (`composer-dictation-<entity-id>`) so the main and side-chat
composers don't light each other's buttons.

**Blocked by:** 23 — both rewrite `composer.rs` heavily; #740's
per-instance hover keys build on the picker's entity-id key pattern.

**Status:** ready-for-human

**Upstream SHAs:** `80b946b1` (#591) — 46 files: `crates/voice/**` (new
crate, workspace member), `crates/ui/src/dictation*.rs` (new),
`assets/icons/microphone.svg`, `ui/build.rs` (icon embedding), composer/
queue/pickers/settings/shell integration, `scripts/package-linux.sh`
(ALSA detection). `9782693b` (#740) — `crates/ui/src/composer.rs`.
**Skip:** `dist/macos/*` entitlements + `scripts/package-macos.sh` +
`scripts/run-macos-dev.sh` + `dictation/permission.m` (macOS-only, we
ship no macOS), `edge/src/install.sh`, all `ci(voice)` bits, and
`docs/design/desktop-parakeet-v3.md` (research voice — port only
`docs/reference/desktop-dictation.md` behavior notes). `ARCHITECTURE.md`/
`README.md`/`THIRD_PARTY_NOTICES.md` hunks: carry the voice-crate rows
with rebrand. Windows: capture via cpal/WASAPI — verify the permission
prompt path works headless; note any gap.

**Verification budget:** `cargo check -p roboco-voice -p roboco-ui -j 3`;
nextest `-p roboco-voice` (session tests run with a fixture model
offline — follow upstream's example/verify harness; do not download in
CI), ui composer_dictation tests; no visual verification. **Web: out of
scope by design** — no browser surface for on-device parakeet inference
(MediaRecorder → server-side transcription would be a fresh design, not
a port; see spec).

- [x] `crates/voice` crate: capture/decode/resample/model lifecycle with
      session tests, no runtime download in tests
- [x] Composer mic + waveform + morph; all the interaction fixes
- [x] Voice settings page (mic selection, model state)
- [x] Per-instance hover keys (#740)
- [x] ALSA detection in package-linux.sh; no macOS bits ported
- [x] Tests green
- [x] Port commit records upstream SHAs

## Comments

Port mapping (upstream `80b946b1` + `9782693b` → this tree, all rebranded):

- `crates/voice/**` → `crates/voice/**` as `roboco-voice` (`lib.rs`,
  `resample.rs`, `session_tests.rs`, `model.json`, `NOTICE.md`,
  `examples/verify.rs`); workspace member added to the root `Cargo.toml`,
  path-depended on by `crates/ui`.
- `crates/ui/src/dictation*.rs` → `crates/ui/src/dictation{.rs,/glass.rs,
  /meter.rs,/model.rs,/waveform.rs}`; `crates/ui/src/composer_dictation_tests.rs`
  → same path (`mod dictation_tests` appended to `composer.rs`).
- Composer integration (actions!, `DictationInputEvent`, hold-to-talk state,
  morph/track render, Escape/undo/redo/paste/IME/navigation cancellations,
  Submit-after-finalization) carried into our post-#471 composer layout
  (model chip beside mic+Send, utility gap unchanged).
- Shell: #591's three-way `UiSettings::merge_changes` replaces the
  hand-kept `sync_independent_settings` list (`pull_settings` +
  `settings_base`), fixing the same bug ticket 23's
  `shell_saves_never_revert_settings_written_outside_the_shell` regression
  test asserts; upstream's own regression test ported alongside.
- Settings: `dictation_enabled`/`dictation_input` fields,
  `ShortcutId::ToggleDictation` (`mod-d`) + `KeymapConfig::toggle_dictation`,
  binding in `apply_keymap` (MessageComposer context), Shortcuts-page
  refactor (`refusal`/`binding_control`/`ShortcutField`, "Voice" group owned
  by the feature page, keymap re-read while not recording).
- Queue-edit paths finish/cancel dictation like upstream (commit, cancel,
  clear, finish).
- `microphone.svg` icon + `MICROPHONE` asset; Voice settings section
  (nav/slug/title/icon/outlet → `dictation::card`).
- `scripts/package-linux.sh`: parakeet-v3 license copy + the `--version`
  startup gate with the ALSA (libasound.so.2) hint; NOTE: overlaps ticket
  28's installer-hardening intent — both lanes port upstream's own hunks,
  rerere/merge order resolves any text conflict.
  `scripts/package-windows.ps1`: license copy only.
- Docs/notices: `docs/reference/desktop-dictation.md` (behavior notes,
  rebranded, macOS-only claims dropped), ARCHITECTURE.md crate row,
  README.md ALSA requirement, THIRD_PARTY_NOTICES.md dictation section.

Exclusions (spec/ticket, deliberately not ported):

- `dist/macos/*` entitlements/plists, `scripts/package-macos.sh`,
  `scripts/run-macos-dev.sh`, `crates/ui/src/dictation/permission.m` and its
  `build.rs` clang block — macOS-only; Roboco ships no macOS. The macOS
  permission/origin-window arms in `dictation.rs` were dropped with the
  bridge; Windows gates mic access in system privacy settings (no in-app
  prompt — a blocked mic surfaces as a capture error; noted in the doc).
- `edge/src/install.sh` hunk (edge removed upstream of this repo), the
  `ci(voice)` workflow bits, and
  `docs/design/desktop-parakeet-v3.md` (research voice — behavior notes
  only, per spec).
- Upstream's style-only formatting hunks in `settings/accounts.rs`,
  `appearance.rs`, `devices.rs`, `thread_naming.rs`, `widgets.rs` (pure
  rustfmt drift; our tree keeps its own formatting discipline).
- Web side: none by design (no browser dictation surface).

Environment note: this box had no ALSA dev headers; installed
`libasound2-dev` locally via `~/tools/apt-resolve.mjs` + `fix-dev-libs.sh`
(shared `~/tools/git-root`, pkg-config path already on the worker env) so
cargo can build `alsa-sys`. Coordinator's batched pass needs the same
worker env (`source ~/.bashrc_pi`).

Verification: `rustfmt --edition 2024` on every touched `.rs` file
(rustfmt's module recursion from `lib.rs` reformatted untouched regions —
reverted via 3-way merge against `rustfmt(HEAD)`); `cargo check -p roboco
-j 3` — green (two environment-only failures on the way: the box lacked
OpenSSL/ALSA dev headers for the new ort-sys/cpal graph — installed
`libssl-dev` + `libasound2-dev` via `~/tools/apt-resolve.mjs`; and the
documented shared-target stale-artifact quirk, resolved by touching this
tree's proto/engine sources); test execution deferred to the wave-final
batched pass (user directive).

## Comments

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

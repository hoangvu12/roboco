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

**Blocked by:** None.

**Status:** ready-for-agent

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

- [ ] `crates/voice` crate: capture/decode/resample/model lifecycle with
      session tests, no runtime download in tests
- [ ] Composer mic + waveform + morph; all the interaction fixes
- [ ] Voice settings page (mic selection, model state)
- [ ] Per-instance hover keys (#740)
- [ ] ALSA detection in package-linux.sh; no macOS bits ported
- [ ] Tests green
- [ ] Port commit records upstream SHAs

## Comments

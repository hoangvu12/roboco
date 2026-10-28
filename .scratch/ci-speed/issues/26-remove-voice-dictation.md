# 26 — Remove desktop voice dictation (`crates/voice`, ONNX Runtime) everywhere

**What to build:** Delete the desktop dictation feature on every platform. **User decision (2026-10-29): "remove everywhere"**, after ticket 21 found that the prebuilt ONNX Runtime static library that `ort-sys` downloads for `parakeet-rs` needs glibc 2.38 + GCC 13/14 libstdc++. That library is why the Linux tarballs need Ubuntu 23.10/24.04+ today (v0.14.0 already requires `GLIBC_2.38`), and it also costs build time (the ort-sys build script reruns and rebuilt `roboco-voice`/`roboco-ui`/`roboco` in Windows CI, see `windows.yml` comment near line 155).

Dictation is desktop-only and opt-in today (`dictation_enabled` defaults to false; the Parakeet model is an optional download). Android has no dictation.

Scope:
- Delete `crates/voice/` (lib, resample, tests, `examples/verify.rs`, `model.json`, `NOTICE.md`) and drop it from the workspace.
- `crates/ui`: delete `dictation.rs` and `dictation/` (`model.rs`, `meter.rs`, `waveform.rs`), `composer_dictation_tests.rs`; remove the composer mic button, waveform/meter UI and the dictation call sites in `composer.rs`, `shell.rs`, `lib.rs`.
- Settings (`crates/ui/src/settings.rs`, `settings/shortcuts.rs`): remove `dictation_enabled`, `dictation_input`, the dictation settings UI and `ShortcutId::ToggleDictation` ("Hold to dictate", `mod-d`). **Old settings files that still contain these keys (and a user-rebound `ToggleDictation` shortcut) must keep loading**: add a back-compat test like the one web-removal ticket 08 added for the removed browser keys.
- One-time cleanup: users who downloaded the model have about 600 MB in `<data_dir>/models/parakeet-tdt-0.6b-v3-int8` (plus `.part` files). Delete that directory once at startup, best effort, logging only failures. Don't touch anything else under `models/`.
- `Cargo.lock`: `parakeet-rs`, `ort`, `ort-sys`, `cpal`, `rubato` (and whatever else only they pulled in) leave the lockfile. Check `cargo tree -i ort-sys` and `-i cpal` come back empty.
- Packaging and notices: `scripts/package-linux.sh:62` and `scripts/package-windows.ps1:149` stop copying `parakeet-v3.txt`; remove the Parakeet/ONNX/cpal/rubato paragraphs from `THIRD_PARTY_NOTICES.md`; remove the `crates/voice` row from `ARCHITECTURE.md`; delete `docs/reference/desktop-dictation.md` and fix links to it; drop the ort-sys comment/workaround in `windows.yml` if it no longer applies (keep the single-invocation nextest step).
- Linux apt packages only needed by `cpal` (ALSA: `libasound2-dev`) leave CI and release apt lists if nothing else needs them (check GPUI/zui first).
- Upstream sync: future upstream dictation/voice commits become **declined** automatically. Add `crates/voice/**` and `crates/ui/src/dictation*` (upstream paths after rename) to the decline rules in `scripts/upstream/path-rules.tsv` (or wherever triage auto-declines removed areas), and record the removal in `docs/reference/upstream-ports.md`'s list of removed areas. Don't edit existing ledger rows.
- Desktop roadmap docs / CONTEXT.md: remove "dictation" mentions that describe it as a current feature.

**Acceptance criteria:**
- [ ] `crates/voice` is gone; `cargo tree -i ort-sys`, `cargo tree -i cpal`, `cargo tree -i parakeet-rs` are empty; `cargo check --workspace --all-targets` clean with no new warnings
- [ ] No dictation UI, setting or shortcut remains on desktop; `git grep -i -w dictation` only hits history (`.scratch/`, `docs/research/`, ledger rows) and the back-compat test
- [ ] An old settings file with `dictation_enabled`, `dictation_input` and a rebound `ToggleDictation` loads without error (test)
- [ ] The model directory cleanup runs once and is covered by a test (directory removed; unrelated `models/` entries untouched)
- [ ] Packaging scripts, `THIRD_PARTY_NOTICES.md`, `ARCHITECTURE.md`, docs updated; Windows `app` job (package + installer + startup) green
- [ ] The packaged Linux binary no longer needs `GLIBC_2.38`/`GLIBCXX_3.4.31` (`readelf -V` in the release dispatch log), unblocking ticket 21
- [ ] Upstream triage auto-declines voice/dictation paths (unit test in `scripts/upstream/`)

**Blocked by:** none. Coordinate with the web-removal PR #39 (it also edits `settings.rs` and `shell.rs`; whichever lands second resolves).

**Unblocks:** 21 (Ubuntu 22.04 floor via the container + guard becomes feasible again).

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md` (from ticket 21's blocker, 2026-10-29)

## Comments

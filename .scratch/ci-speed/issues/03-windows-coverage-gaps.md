# 03 — Windows coverage gaps: login resolution, startup script, cursor-shim first frame

**What to build:** Close the Windows gaps that drift report §D found (upstream's `windows.yml` and `4a0d416cf`), carried by hand.

- Run `crates/engine/tests/codex_login_resolution.rs` on Windows. Upstream runs it next to `codex_catalog` in "Harness catalog and login tests". Append `--test codex_login_resolution` to the "Harness catalog tests" step.
- Wire `scripts/test-windows-startup.ps1` after the installer test. It checks that the GUI subsystem shows no console flash and that redirected CLI output is not lost. Check the script's arguments and expectations against Roboco's binary name and `ROBOCO_*` env first; it may still assume upstream's `Mode: local only` wording.
- Port `4a0d416cf` (#1051): in `crates/harness/tests/cursor_shim.rs`, a shim's first frame (`ready`) gets 30 s, because `node` startup on Windows runners can take longer than 5 s. Later frames keep 5 s (today `:190` and `:1000` use 5 s for every frame). Follow `docs/reference/upstream-ports.md`.

**Blocked by:** 02 (same steps of `windows.yml`)

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] `codex_login_resolution` appears in the Windows nextest output and passes
- [ ] `test-windows-startup.ps1` runs in CI and passes; a deliberately broken expectation fails it (once, on a scratch branch)
- [ ] `cursor_shim` first-frame timeout is 30 s, later frames 5 s; suite green on Windows and Linux
- [ ] Measured before/after: the added steps cost ≤ 2 min on the `tests`/`app` job

## Comments

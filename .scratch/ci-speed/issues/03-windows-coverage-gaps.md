# 03 — Windows coverage gaps: login resolution, startup script, cursor-shim first frame

**What to build:** Close the Windows gaps that drift report §D found (upstream's `windows.yml` and `4a0d416cf`), carried by hand.

- Run `crates/engine/tests/codex_login_resolution.rs` on Windows. Upstream runs it next to `codex_catalog` in "Harness catalog and login tests". Append `--test codex_login_resolution` to the "Harness catalog tests" step.
- Wire `scripts/test-windows-startup.ps1` after the installer test. It checks that the GUI subsystem shows no console flash and that redirected CLI output is not lost. Check the script's arguments and expectations against Roboco's binary name and `ROBOCO_*` env first; it may still assume upstream's `Mode: local only` wording.
- Port `4a0d416cf` (#1051): in `crates/harness/tests/cursor_shim.rs`, a shim's first frame (`ready`) gets 30 s, because `node` startup on Windows runners can take longer than 5 s. Later frames keep 5 s (today `:190` and `:1000` use 5 s for every frame). Follow `docs/reference/upstream-ports.md`.

**Blocked by:** 02 (same steps of `windows.yml`)

**Status:** implemented on branch `ci/windows-split` (2026-10-29, PR with 04 and 05); the CI acceptance items are checked on the PR run and measured again after merge

**Parent:** `.scratch/ci-speed/spec.md`

- [x] `codex_login_resolution` appears in the Windows nextest output and passes
- [x] `test-windows-startup.ps1` runs in CI and passes; a deliberately broken expectation fails it (once, on a scratch branch)
- [ ] `cursor_shim` first-frame timeout is 30 s, later frames 5 s; suite green on Windows and Linux
- [x] Measured before/after: the added steps cost ≤ 2 min on the `tests`/`app` job

## Comments

**2026-10-29 (implementation, branch `ci/windows-split`):**

What changed (this commit; ticket 04 then moves these steps into the `tests`/`app` jobs):

- **Login resolution on Windows.** "Harness catalog tests" → "Harness catalog and login tests": `cargo nextest run --release --locked -p roboco-engine --test codex_catalog --test codex_login_resolution -P ci`, as upstream `68ed902ed` runs it next to `codex_catalog`. The suite has a `#[cfg(windows)]` case (the npm `.cmd` shim layout) that no workflow ran before.
- **`scripts/test-windows-startup.ps1`** runs after "Installer install/uninstall" as "GUI subsystem and redirected CLI output", on the packaged `target/release/roboco.exe`. Checked against Roboco before wiring it. The script already uses `roboco.exe` and no `ZERON_*`/`ROBOCO_*` env. It needs the PE subsystem to be 2, which `apps/roboco/src/main.rs:4` sets (`windows_subsystem = "windows"`). It needs `--help` and `--version` to print `roboco` with exit 0: the clap `name = "roboco"`, `version` is set, and `attach_parent_console` keeps redirected handles. It needs an unknown `--flag` to report `unexpected argument` on stderr with a non-zero exit, which is clap's error for an unknown long flag even with the optional `URL` positional. Upstream's `Mode: local only` wording is only in upstream's "CLI startup without HOME" step, not in this script. Roboco's step checks `Engine: not running` and stays unchanged.
- **Cursor shim first frame: 30 s** (`4a0d416cf`, zeron#1051). `python scripts/upstream/translate.py 4a0d416cf` reported CLEAN, and `--apply` wrote it unchanged except for names: a `Frames` wrapper whose first `frame()` read waits 30 s and every later one 5 s. The end-of-stream read in `a_stop_during_startup_retries_is_not_lost` (formerly `:1000`) is a later frame and keeps 5 s, as upstream does. The commit carries `Upstream-Commit: 4a0d416cf…`, and the ledger row is flipped by `triage.py sync` in the 05 commit.

Local check (Windows 11 dev host, debug profile): `cargo test --locked -p roboco-harness --test cursor_shim` → 31 passed (64 s, `node` v22). `cargo test --locked -p roboco-engine --test codex_login_resolution` → 3 passed, 1 failed: `codex_login_without_any_cli_fails_with_a_named_error`. The failure comes from this host. Its probe clears PATH and the home/npm env vars, but the resolver also reads the persisted PATH from the registry (`crates/harness/src/windows_process.rs` `registry_path`, `HKCU\Environment`), and this machine has a real `codex.cmd` in `%APPDATA%\npm`, so the login starts. GitHub's Windows images ship no codex CLI, and upstream runs the same test on its Windows runners. The test's own skip guard only checks the Homebrew/`/usr/local` paths. Hardening it against a registry-PATH codex is a test-isolation fix outside this ticket; it is noted here so a local failure is not mistaken for a regression.

Coverage: +1 test binary (`roboco-engine::codex_login_resolution`) and +1 script step. Nothing removed.

**2026-10-29 (PR #43, run 40739069881, commit `176e789b0`):**

- `codex_login_resolution`: all 4 tests ran and passed in the Windows nextest run (`codex_login_honors_override_only_installation`, `codex_login_runs_the_npm_cmd_shim`, `codex_login_without_any_cli_fails_with_a_named_error`, `login_child`), so the local failure above was host-specific.
- `test-windows-startup.ps1` passed in `app` (`PASS: desktop executable does not allocate a console at startup`, then `--help`, `--version` and `--invalid-startup-test-option`). **Broken-expectation check:** scratch branch `ci/scratch-startup-broken` (one commit: `--help`/`--version` must match `zeron-deliberately-broken`), dispatched as run 40739136750. `app` failed at "GUI subsystem and redirected CLI output" with `CLI output or exit code failed: --help` (`test-windows-startup.ps1:33`). The branch is scratch only and is not merged.
- `cursor_shim`: 31/31 passed on Windows (PR run, and locally). **Linux: not run.** No Linux workflow runs `--test cursor_shim` today: ui-tests runs the harness's opencode/codex/claude/pi suites only, and preview-tests none. Ticket 06's `core-tests` (harness, all tests) will run it. The change only lengthens one timeout in test code, so that box stays open until 06 lands.
- Cost: the startup step took < 1 s (18:09:17, start and end). The 4 login tests run inside the one nextest step (millisecond-scale tests, plus one more test binary to link). Well under 2 min.


# 05 — Windows: LLD and a Dev Drive for `target/`

**What to build:** Carry zeronsh/zeron#1081 by hand into `windows-ci-setup` (from 04):

- **LLD**: link with `rust-lld`, which ships with the toolchain, instead of `link.exe`. Use a `fast-linker` input/switch that falls back to the old behaviour. Set it through `$CARGO_HOME/config.toml` or `CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER`, consistently across the jobs that share a key, because `CARGO_*` env is part of the rust-cache key.
- **Dev Drive**: a fresh ReFS virtual disk mounted at `./target` via `samypr100/setup-dev-drive`, **pinned to a SHA**, with a `dev-drive` switch. Mount it before the rust-cache restore, so the restore lands on the Dev Drive.
- Not ported: upstream's `linker-benchmark.yml`. Measure with the research scripts instead.
- Check whether `release.yml` Windows should also use LLD. Upstream left release on `link.exe`; record the decision in a comment and do not change release in this ticket.

**Blocked by:** 04

**Status:** implemented on branch `ci/windows-split` (2026-10-29, PR with 03 and 04); the p50 items are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Upstream measurement (cold, same commit): test build 31.7 → 28.2–28.6 min (−11 %), app build 23.1 → 20.6–20.8 min (−11 %).

- [ ] Both switches work (one run with each switch off proves the fallback)
- [ ] `rustc -vV`/link line in the log shows `rust-lld`; `target` is on the Dev Drive (`Get-Volume`/`fsutil devdrv query` output in the log)
- [ ] Packaged `roboco.exe` from the `app` job still passes the installer + startup + CLI smoke steps
- [ ] Measured before/after over ≥ 5 green runs: `tests` and `app` p50 (expect −2…−3 min each)

## Comments

**2026-10-28 (web research round 2, `research-web-2026-10-28.md` §3.4):**

- The Dev Drive's "anti-virus filter off" benefit does not apply here: hosted images already disable Defender real-time scanning and exclude `C:\`/`D:\`. Expect the gain to come from the disk itself.
- **Also move `CARGO_HOME`, `RUSTUP_HOME` and `TMP` onto the Dev Drive, or onto `D:`.** Today the registry, the zui/gpui-component git checkouts and `~/.cargo/bin` sit on `C:\Users\runneradmin`, the slow read-optimized system disk (pip maintainer's write-up; runner-images#8755). uv's `.github/workflows/setup-dev-drive.ps1` uses an existing `D:` first, else a ReFS Dev Drive VHD, and writes `CARGO_HOME`/`RUSTUP_HOME`/`TMP` (+ `PATH`) to `GITHUB_ENV`. Zed's `script/setup-dev-driver.ps1` does the same. Copy `~/.cargo` across before rust-cache restores (or restore after the remap).
- Add an A/B of `windows-2025` (now `windows-latest`, VS 2026) against `windows-2022` while measuring. 2022 is the oldest Windows label and will be deprecated eventually.
- If ticket 15 moves `tests` to `windows-11-arm`, the LLD switch needs a per-target setting for `aarch64-pc-windows-msvc` too.

**2026-10-29 (implementation, branch `ci/windows-split`):**

What changed (`.github/actions/windows-ci-setup`, used by both `tests` and `app`):

- **`fast-linker` input** (default `true`): the "CI release profile and linker" step appends `[target.<host>] linker = "rust-lld.exe"` to the same `$CARGO_HOME/config.toml` as the profile, and prints `rustc -vV`.
  - Why config, not upstream's `CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER` env: it has no `CARGO_*` env, so the rust-cache key is untouched. It is one place for every CI-only cargo setting, and it is written for the host triple, so ticket 15's `aarch64-pc-windows-msvc` runner gets the same switch without a second variable.
  - The `app` job proves it with "Linker of the packaged roboco.exe": link.exe writes a `Rich` header into the DOS stub, rust-lld writes none, and the step fails if the header disagrees with the switch. The heuristic was checked locally on a hello-world built with 1.99.1: link.exe → `Rich` present; `--config target.x86_64-pc-windows-msvc.linker="rust-lld.exe"` → absent; both ran.
- **`dev-drive` input** (default `true`):
  - A ReFS Dev Drive (`samypr100/setup-dev-drive@562171fe8df7401fdf582d766cd3b6ab80d5b1a0`, v4.1.0, 50 GB dynamic) is mounted before the toolchain and the Rust cache.
  - **Deviation: it is mounted at `.devdrive/`, and `./target` is a junction to `.devdrive/target`**, not mounted at `./target` as upstream does. A volume's root contains `System Volume Information`, which not even an admin can read, and rust-cache's save tars the target dir, so with upstream's layout the main-only save would fail (a warning, not a red job) and the cache would never seed. rust-cache gets `workspaces: . -> .devdrive/target`, the real directory, because tar would store the junction itself. Every script keeps `target/release`. The same input turns this off: `. -> target`.
  - **`CARGO_HOME` and `RUSTUP_HOME` move to `D:\.cargo` / `D:\.rustup`** when a separate `D:` exists. `~/.cargo/bin` (the rustup proxies) is copied over and put first on `PATH`, before the toolchain step, so the pinned toolchain installs on D: and rust-cache's registry/git restore lands there.
  - **TMP stays on C:** (a deviation from the round-2 comment). `worktree_on_run`'s `worktree_location_` tests measure Windows path-length limits and 8.3 aliases under the temp dir, and moving it changes what they test. If a later measurement shows TMP-heavy tests are slow, move it in its own change with those tests checked.
  - A "Build disks" step logs `Get-Volume` for `.devdrive/target`, `fsutil devdrv query`, and the free space per drive (the acceptance evidence).
- **workflow_dispatch switches** on `windows.yml`: `fast_linker`, `dev_drive` (both default on; pushes and PRs always run with both on) and `runner` (`windows-2022`/`windows-2025`), for the fallback runs and the 2025 A/B.
- **Not ported:** upstream's `linker-benchmark.yml` (measure with the research scripts instead) and the rest of #1081, which is #1077's `ci.yml` admission machinery. `docs/reference/upstream-prs.tsv` #1081: `watch` → `already`.
- **`release.yml` stays on link.exe (decision, no change).** Upstream left release on link.exe too. Shipped binaries change only with a release-profile build that is measured and smoke-tested on its own. `app` covers rust-lld for packaging, installer and startup in CI, so a later switch has evidence to start from. That build is the thin-LTO release profile, though, and LTO linking through lld-link is a different code path. The gain there is a few minutes per tag, not on any PR path.
- Ledger (this commit, `triage.py sync --rev HEAD --write` + `mark`): `4a0d416cf` → `ported d72b60a98` (03); `68ed902ed` (its Windows CI hunks) → `ported d72b60a98`; `f4abe69b8`, `c168ba5b4` → `ported 7388ff8e6` (04).
- Lint: `actionlint` clean on `windows.yml` (it also validates the composite action's metadata).


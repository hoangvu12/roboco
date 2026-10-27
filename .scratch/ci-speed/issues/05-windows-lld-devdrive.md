# 05 — Windows: LLD and a Dev Drive for `target/`

**What to build:** Carry zeronsh/zeron#1081 by hand into `windows-ci-setup` (from 04):

- **LLD**: link with `rust-lld`, which ships with the toolchain, instead of `link.exe`. Use a `fast-linker` input/switch that falls back to the old behaviour. Set it through `$CARGO_HOME/config.toml` or `CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER`, consistently across the jobs that share a key, because `CARGO_*` env is part of the rust-cache key.
- **Dev Drive**: a fresh ReFS virtual disk mounted at `./target` via `samypr100/setup-dev-drive`, **pinned to a SHA**, with a `dev-drive` switch. Mount it before the rust-cache restore, so the restore lands on the Dev Drive.
- Not ported: upstream's `linker-benchmark.yml`. Measure with the research scripts instead.
- Check whether `release.yml` Windows should also use LLD. Upstream left release on `link.exe`; record the decision in a comment and do not change release in this ticket.

**Blocked by:** 04

**Status:** ready-for-agent

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

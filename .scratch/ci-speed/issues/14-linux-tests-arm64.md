# 14 — Linux test jobs on `ubuntu-24.04-arm` (free, ≈ 20–25 % faster here)

**What to build:** Move the Linux Rust test jobs from ticket 06 (`core-tests`, `ui-tests`, `mobile-core`) and `tailcat-adapter` to `ubuntu-24.04-arm`.

Evidence from this repo's own `release.yml`, like for like (research-web §3.1). The linux package step on a cache miss takes 13.8 min on x64 vs 10.6 on arm64 (n=18 each). On a hit, 8.0 vs 6.0 (n=4 each). That is despite x64 linking with rust-lld and arm64 with bfd. arm64 runners are free for public repos and nominally the same 4 vCPU / 16 GB.

- `runs-on: ubuntu-24.04-arm` for the four jobs. The Android jobs **stay x64**: Google's `aapt2` is x86_64-only on Linux, and the emulator needs x86 KVM.
- Linker on aarch64: `rust-lld` is the default only for `x86_64-unknown-linux-gnu`. Add `lld` to the apt list and set `CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUSTFLAGS: -Clink-arg=-fuse-ld=lld` at **job level, identical in every job that shares a key** (`CARGO_*` is part of the rust-cache key). Run once without it to measure the difference, and keep it only if faster.
- apt packages: same names on arm64 (already proven by `release.yml` linux arm64).
- Check for x86-only assumptions before switching: grep tests for `x86_64`, `target_arch`, downloaded prebuilt binaries, and fixtures that exec x64 tools. The Go Tailcat pieces build natively. `mobile-client`'s Tailcat e2e builds `libroboco_tailcat.so` for the host, so it is fine on arm64.
- Cache keys change because the architecture is part of the key. Each job's x64 key is simply replaced, with no budget increase. The first main push re-seeds.
- The nightly `--release` backstop from ticket 06 can stay on x64. That keeps one x64 Linux signal per day.

**Blocked by:** 06 (move the consolidated jobs once rather than today's five), 01 (keys)

**Status:** implemented on branch `ci/linux-arm64-glibc` (2026-10-29); the post-merge measurements below stay open

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: ticket 06's after-numbers (target core-tests 10–12, ui-tests 12–13, mobile-core 6–8, tailcat 3 min warm). Before 06 lands, the R1 numbers apply: ui-tests 21.8, engine-local 14.8, mobile-core 12.8.

- [x] Coverage list unchanged (nextest `--list` identical test set on arm64 vs x64, except tests already `cfg`-gated by arch, which are named) — junit test names identical in all three A/B runs, no arch-gated test in these suites (comment below)
- [x] One A/B pair per job on the same commit (dispatch): x64 vs arm64, lld vs default linker; attach the four timings — three dispatches, table below; lld dropped
- [ ] Measured over ≥ 10 runs after merge: each job's p50 warm and cold (target −20 % vs the 06 after-numbers), runner-min per push
- [ ] Queue time p50 stays < 1 min (arm64 pools are smaller; `analyze.py runs` queue column)

## Comments

**2026-10-29 (implementation, branch `ci/linux-arm64-glibc`):**

What landed:

- `ui-tests.yml`: `core-tests`, `ui-tests`, `mobile-core` and `tailcat-adapter` → `runs-on: ubuntu-24.04-arm`. `release-backstop` stays on `ubuntu-24.04` (the daily x64 Linux signal); android.yml untouched (x64).
- `scripts/ci/install-nextest.sh`: picks the `aarch64-unknown-linux-gnu` asset on arm64, with its own pinned SHA-256 (`7fecfd43…a407`, from the release's `.sha256` asset), beside the x86_64 one. Same version 0.9.144.
- `.github/actions/linux-ci`: the apt `.deb` cache key now names `RUNNER_ARCH` (`ImageOS` is `ubuntu24` on both images, and the .debs are per-arch, so an x64 and an arm64 job with the same list would have shared one entry).
- **No lld.** The A/B below shows no consistent gain, and lld would add an apt step (≈ 10–20 s cold) to `core-tests`/`mobile-core`, which install nothing today. The composite's comment records why. (The arm64 image ships `ld.lld-16/17/18` but no unversioned `ld.lld`, so `-fuse-ld=lld` does need the `lld` package.)
- x86-only check: no test, fixture or build script assumes x86_64. The only arch-specific code in these graphs is `crates/harness/src/acp/mod.rs`'s managed-download URL table (it already covers `linux-aarch64`) and the xtask's Android/NDK target names (cross targets, host-independent). The Tailcat Go pieces and mobile-client's host `libroboco_tailcat.so` build natively on arm64 (the runs below passed them).

A/B, one commit (scratch branch `ci/scratch-arm64-ab` = this branch + dispatch inputs for runner/lld, junit uploads, a linker probe, and `-scratch-ab` suffixed rust-cache keys so **every run is cold**; deleted afterwards). Dispatch runs are the full tier, so `mobile-core` includes the benches compile and the 6-min layout bench. Runs: x64 [40764044387](https://github.com/hoangvu12/roboco/actions/runs/40764044387), arm64 default (bfd) linker [40764049796](https://github.com/hoangvu12/roboco/actions/runs/40764049796), arm64 lld [40764055001](https://github.com/hoangvu12/roboco/actions/runs/40764055001).

| job (cold) | x64 | arm64 bfd | arm64 lld |
|---|---|---|---|
| `core-tests` job / cargo build / tests | 8.9 / 6:09 / 129 s | **7.0** / 4:24 / 122 s | 7.3 / 4:15 / 125 s |
| `ui-tests` job / cargo build / tests | 13.7 / 10:54 / 94 s | **12.8** / 9:47 / 103 s | 13.6 / 10:32 / 106 s |
| `mobile-core` job / cargo build / tests | 15.9 / 5:17 / 66 s | 15.8 / 4:44 / 68 s | 16.2 / 4:38 / 67 s |
| `mobile-core` without the full-tier benches (PR shape) | 8.7 | 9.2 | 9.6 |
| `tailcat-adapter` | 2.5 | 4.1 | 3.9 |
| runner-min, PR shape (4 jobs) | 33.8 | 33.1 | 34.4 |

Reading:

- **Compile is 10–28 % faster on arm64** (core build −28 %, mobile −10 %, ui −10 %; release-profile layout bench build 6:38 → 6:05). That is less than research-web §3.1's 20–25 % on the release profile for `ui-tests`, the critical path: −0.9 min cold (13.7 → 12.8).
- **Test execution is not faster**: the serial `roboco-ui` group takes 94 → 103 s.
- **Go is slower cold on arm64, and the comparison is unfair to it**: `setup-go`'s module/build cache is per-arch, the x64 runs restored main's x64 Go entry, the arm64 runs had none (Tailcat transport tests 0.7 → 1.9 min; mobile-core's Tailcat build 1.0 → 2.2). The first main push seeds the arm64 entry.
- lld vs bfd: −9 s core, −6 s mobile, +45 s ui. That is within single-sample noise, not a gain, so it was dropped.
- Queue: 0.1 min for every arm64 job in the A/B (the `analyze.py` check over ≥ 10 runs stays open below).
- Expectation for warm runs (to measure after merge): the cargo build is the part arm64 speeds up, and a warm cache shrinks it, so the warm gain should be smaller in absolute terms. **The −20 % per-job target is unlikely to be met for `ui-tests`**; core-tests is the job where it shows. The switch is still no slower on any Rust job and frees nothing paid, so it stays; a revert is one line per job if the warm numbers say otherwise.

Coverage (junit `testcase` names, `target/nextest/ci/junit.xml`, compared by script): x64 vs arm64-bfd and x64 vs arm64-lld are **identical sets** — core 1721 run / 13 skipped, mobile 530 / 5 skipped, ui 1733 / 0 skipped, plus the syntax subset 21 in its own run. No test in these suites is gated by `target_arch`.

Lint: actionlint 1.7.12 clean on `ui-tests.yml`; `bash -n` on `install-nextest.sh`.

Still open (post-merge): the ≥ 10-run warm/cold p50 per job and runner-min per push, against ticket 06's after-numbers; the queue-time p50; the first main push seeding the arm64 `linux-core`/`linux-ui`/`linux-mobile` entries (the x64 ones then age out by LRU; nothing else saves x64 Linux test keys — the backstop never saves).

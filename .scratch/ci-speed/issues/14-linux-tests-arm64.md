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

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: ticket 06's after-numbers (target core-tests 10–12, ui-tests 12–13, mobile-core 6–8, tailcat 3 min warm). Before 06 lands, the R1 numbers apply: ui-tests 21.8, engine-local 14.8, mobile-core 12.8.

- [ ] Coverage list unchanged (nextest `--list` identical test set on arm64 vs x64, except tests already `cfg`-gated by arch, which are named)
- [ ] One A/B pair per job on the same commit (dispatch): x64 vs arm64, lld vs default linker; attach the four timings
- [ ] Measured over ≥ 10 runs after merge: each job's p50 warm and cold (target −20 % vs the 06 after-numbers), runner-min per push
- [ ] Queue time p50 stays < 1 min (arm64 pools are smaller; `analyze.py runs` queue column)

## Comments

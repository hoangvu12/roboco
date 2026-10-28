# 21 — Linux release tarballs load on Ubuntu 22.04 (glibc 2.35 floor) — wave-10 A24

**What to build:** The wave-10 upstream spec routed A24 here (`.scratch/upstream-drift/2026-10-28.md` row A24; `audit-2026-10-28/part-prs-B.md` #197).

`release.yml:46-47` builds the Linux tarballs on `ubuntu-24.04` and `ubuntu-24.04-arm` (glibc 2.39). Rust std references `pidfd_spawnp`/`pidfd_getpid` weakly, but building against 2.39 headers writes a **non-weak** `GLIBC_2.39` entry into `.gnu.version_r`, and `ld.so` checks every entry. So the binary fails to load on Ubuntu 22.04 (glibc 2.35, standard support to 2027): `version 'GLIBC_2.39' not found`. Upstream [zeronsh/zeron#197](https://github.com/zeronsh/zeron/pull/197) (open) has the diagnosis.

Upstream's fix switches the runners to `ubuntu-22.04`/`ubuntu-22.04-arm`. **Don't copy that part.** The [Ubuntu 22.04 runner images began deprecation on 2026-09-17 and are fully unsupported from 2027-04-17](https://github.com/actions/runner-images/issues/14254), with brownouts before that, so a runner switch fixes it for ≈ 6 months and can fail a release on a brownout day. Carry the intent by hand (docs/reference/upstream-ports.md):

- **Build in a container on the current runners.** Add `container: ubuntu:22.04` to the `linux` matrix job. The image is multi-arch, so the same line gives an aarch64 22.04 userland on `ubuntu-24.04-arm`. Inside:
  - `apt-get install` the build essentials (`build-essential`, `curl`, `git`, `ca-certificates`, `pkg-config`, `cmake`) plus today's GPUI dev packages. Drop `libwebkit2gtk-4.1-dev libjson-glib-dev` if the web-removal PR has landed; otherwise keep them, since both exist in 22.04.
  - Install rustup with `--default-toolchain none`, then `rustup show active-toolchain || rustup toolchain install` to honour `rust-toolchain.toml` (ticket 12's pin).
  - `actions/setup-go` works in container jobs.
  - Check that `actions/checkout` has `git` (install it first, or checkout falls back to the REST tarball and `package-linux.sh`'s version/git logic may differ).
- **Floor guard** in `scripts/package-linux.sh`, from #197: `GLIBC_MAX="${GLIBC_MAX:-2.35}"`. Fail if `readelf -V "$BIN"` needs a newer `GLIBC_*`, and list the offending symbols with `objdump -T`. Apply the same check to the bundled Go adapter: it is `CGO_ENABLED=0`, so it has no glibc refs, but the check keeps it that way.
- **22.04 smoke**: after packaging, in the same job:
  1. `docker run --rm -v target/package:/pkg ubuntu:22.04` (or arm64 on the arm job);
  2. install only the runtime libraries a user would need (record the list: at least `libxkbcommon-x11-0 libxcb1 libfontconfig1 libfreetype6 libwayland-client0 libasound2 libvulkan1`, refined from `ldd`);
  3. extract the tarball and run `roboco --version` and `roboco status` with `ROBOCO_IPC_PORT=0`, expecting "Engine: not running", like the Windows CLI smoke.

  This makes the floor a tested contract. Write the runtime library list into the release notes template or `docs/` install section.
- Skip #197's `install.sh` loader preflight (it lives in upstream's edge, which Roboco doesn't have).
- Not chosen: cargo-zigbuild with a `.2.35` glibc suffix. GPUI's system libraries come through pkg-config and the `cc` crate, so it would need a 22.04 sysroot anyway, and the container is that sysroot. manylinux/CentOS bases are older than needed, with no Ubuntu package names.

**Build time and the arm64 job:**

- Compile speed is unchanged (same host).
- The container adds rustup install + apt ≈ +1.5–2.5 min per Linux release job.
- Ticket 01 removes release caching, so these jobs build cold either way (≈ 14 min x64, ≈ 11 min arm64 today; research-web §3.1).
- The Windows x64 job (≈ 24 min) remains the release critical path, so release wall time does not change.
- The arm64 Linux job gets the identical container line and stays the fastest Linux build.
- Add `timeout-minutes` (ticket 01 also asks for it on this matrix).

**Blocked by:** none for the container and guard. Coordinate the apt-list lines with the web-removal PR (whichever lands second rebases). Ticket 12 makes the toolchain inside the container deterministic.

**Status:** needs-info: blocked on a user decision (ONNX Runtime prebuilt needs glibc 2.38 / GCC 13–14 libstdc++; options in the 2026-10-29 comment). The attempt (container, guard, smoke; 66cb6bb98) does not link and was reverted from the ticket-14 PR (8b63937cc); it survives on `ci/scratch-glibc-floor`'s parent commit for the follow-up PR

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] `readelf -V` on both tarballs' `roboco` shows no `GLIBC_` version above 2.35 (guard output in the log)
- [ ] The 22.04 smoke passes for x86_64 and aarch64 in one `workflow_dispatch` run of `release.yml` (no tag); the runtime library list is recorded
- [ ] A deliberately raised floor (`GLIBC_MAX=2.30` on a scratch dispatch) fails the guard with the symbol list
- [ ] Measured: linux x64 and arm64 release job minutes before (R1: 14.8 / 11.9 p50) vs after (expect +1.5…+2.5 each); release workflow wall unchanged (Windows-bound)

## Comments

**2026-10-29 (implementation attempt, branch `ci/linux-arm64-glibc`, commit 66cb6bb98): blocked.**

What was built: `release.yml` `linux` in `container: ubuntu:22.04` (build tools + libssl-dev + the unchanged GPUI/webkit list, rustup `--default-toolchain none` honouring `rust-toolchain.toml`); the floor guard in `scripts/package-linux.sh` (`GLIBC_MAX`, default 2.35; lists offending versions with their `objdump -T` symbols; fails when it finds no `GLIBC_*` reference at all; requires the Go adapter to reference none), unit-tested locally against stubbed `readelf`/`objdump` (pass at 2.35, fail at 2.30 with the symbol list, fail on 2.38/2.39, fail on an unreadable binary, fail on a glibc-referencing adapter); a `linux-smoke` job (clean `ubuntu:22.04`, only the runtime packages, runs the tarball's `install.sh`, `roboco --version`, `roboco status` → scratch data dir + `Engine: not running`; `scripts/ci/smoke-linux-tarball.sh`; needed by `publish`). It is a separate job because a container job cannot `docker run`.

Dispatch [40764536963](https://github.com/hoangvu12/roboco/actions/runs/40764536963) (no tag, no release; the Windows x64 job was cancelled once Linux had failed): the container setup works (apt 0.6 min, checkout with git, rustup, node/pnpm, Go). **Both Linux builds then fail to link `roboco`** (x64 at 11.8 min, arm64 at 11.0):

- x64: `undefined symbol: __isoc23_strtol` / `__isoc23_strtoll` / `__isoc23_strtoull` (glibc **2.38**, C23 headers) and `std::__cxx11::basic_string<…>::_M_replace_cold` (libstdc++ from **GCC 13**, `GLIBCXX_3.4.31`);
- arm64: the same plus `__cxa_call_terminate` and `std::__try_use_facet` (**GCC 14** libstdc++, `GLIBCXX_3.4.33`);
- every reference is inside `libort_sys-*.rlib`, i.e. **ort-sys 2.0.0-rc.13's downloaded prebuilt ONNX Runtime static archive** (`onnxruntime::InferenceSession`, `custom_ops.cc`, nlohmann json). roboco-voice → parakeet-rs 0.3.8 → ort → ort-sys, default `download-binaries`.

The scratch negative run [40764598788](https://github.com/hoangvu12/roboco/actions/runs/40764598788) (`GLIBC_MAX=2.30`, arm64 only) failed at the same link, before the guard. So the "guard fails on a raised floor" box is only covered by the local stub test, not by CI.

**The shipped binary already needs more than #197 diagnosed.** The v0.14.0 x86_64 tarball, read with a small ELF parser (`.gnu.version_r` and `DT_NEEDED`):

- libc: up to `GLIBC_2.38` (**non-weak**; ORT's `__isoc23_*`) and `GLIBC_2.39` (**weak-flagged**: the pidfd reference #197 found);
- libm `GLIBC_2.35`; libstdc++ up to `GLIBCXX_3.4.31` / `CXXABI_1.3.11`;
- `NEEDED`: libxcb.so.1, libxkbcommon.so.0, libxkbcommon-x11.so.0, libstdc++.so.6, libasound.so.2, libc, ld-linux, libgcc_s, libm. No webkit, fontconfig, freetype, wayland or vulkan (those are dlopen'd or static), so the README's provisional runtime list is too long.

So today's tarballs need Ubuntu 23.10/24.04+ (glibc ≥ 2.38, GCC 13+ libstdc++; arm64 probably GCC 14's). Building in 22.04 cannot fix that while ORT comes from pyke's prebuilt archive.

Options (user decision):

1. **Load ONNX Runtime dynamically on Linux and bundle Microsoft's official `libonnxruntime.so`** (parakeet-rs has a `load-dynamic` feature → `ort/load-dynamic`; a Linux-only target dependency keeps Windows unchanged). Microsoft's Linux release builds target an old manylinux base, so the 22.04 floor should become reachable; this needs verifying for the exact ORT version that ort rc.13 / `api-28` expects. Cost: voice-crate init code (point ort at the bundled library), package-linux.sh downloads it with a pinned checksum and bundles it plus its license, and the tarball grows. This would be its own ticket; 21's container, guard and smoke then apply as written.
2. **Accept a 24.04 floor for now.** Keep the 24.04 runners (no container), set the guard to the real floor (`GLIBC_MAX=2.39`, or 2.38 plus a `GLIBCXX` check), smoke in `ubuntu:24.04`, and document "Ubuntu 24.04+ / Debian 13+". That lands the guard and the tested contract now and makes the 22.04 regression explicit, not silent; option 1 can follow as its own ticket.
3. Shims in the 22.04 container: gcc-13/14 from the toolchain PPA, `-static-libstdc++`, C wrappers for `__isoc23_strto*`. Not recommended: it relies on libstdc++ internals and is fragile across ORT updates.
4. Build ONNX Runtime from source in the container (ort-sys `ORT_LIB_LOCATION`): tens of minutes per arch on every release with no cache. Not recommended.
5. Drop voice from the Linux build (a product decision).

Recommendation: **2 now, 1 as a follow-up ticket** if Ubuntu 22.04 support matters.

Boxes: none ticked. The x64/arm64 release-job minutes "after" were not measured, because the jobs fail at link. For reference, the container setup took ≈ 0.6 min apt + rustup inside the existing steps, and the builds reached link at 11.8 (x64) / 11.0 (arm64) min, versus R1's 14.8 / 11.9 p50 job.

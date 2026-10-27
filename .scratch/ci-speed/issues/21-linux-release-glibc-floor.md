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

**Status:** ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

- [ ] `readelf -V` on both tarballs' `roboco` shows no `GLIBC_` version above 2.35 (guard output in the log)
- [ ] The 22.04 smoke passes for x86_64 and aarch64 in one `workflow_dispatch` run of `release.yml` (no tag); the runtime library list is recorded
- [ ] A deliberately raised floor (`GLIBC_MAX=2.30` on a scratch dispatch) fails the guard with the symbol list
- [ ] Measured: linux x64 and arm64 release job minutes before (R1: 14.8 / 11.9 p50) vs after (expect +1.5…+2.5 each); release workflow wall unchanged (Windows-bound)

## Comments

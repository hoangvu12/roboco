# CI speed: web/GitHub research round 2 (2026-10-28)

Status: DONE. Read-only research; nothing in the repo or on GitHub was changed. It builds on `research-2026-10-28.md` (call it R1) and does not repeat it. It produced new tickets 12–21 and dated comments on 01, 04, 05, 06, 07, 08 and 10.

New local evidence gathered for this round (same conventions as R1 §9):

- `overhead.py` (new, `python -I overhead.py data`): per-job fixed-overhead steps (checkout, toolchain, caches, SDKs).
- `data/log-android-ubuntu-122458316176.txt`: a green `android (ubuntu)` job log, read for Gradle phase timings.
- `data/log-emulator-122458315870.txt` (from R1): re-read for emulator, Gradle and toolchain detail.
- `gh api …/actions/caches` and `…/cache/usage` re-pulled 2026-10-28 (see §1.2).
- Rust version from the rust-cache "Environment considered" block in 9 job logs spread over the window (§1.1).

## 0. Summary: what is new, ranked

Savings are per PR push that touches `crates/**` unless stated, and are stated against the post-01…09 target layout (R1 §8), because that is what these ideas would change. "Wall" means PR critical path; "runner" means runner-minutes.

| # | idea | evidence | saving | effort | risk | ticket |
|---|---|---|---|---|---|---|
| 1 | **Fix the cache budget**: R1 counted only Rust caches. Usage is **15.9 GB**: AVD 2.75 GB (hit 4 of ~45 runs), Gradle ≈ 2.8 GB from 4 writers, Go 1.0 GB. Drop the AVD cache, keep one Gradle writer, optionally pay for a higher limit (≈ $0.07/GB-month). | §1.2 | Without it, ticket 01's main-only caches still overflow and get evicted, so its whole −60 %→≤10 % miss-rate target is at risk. Each miss costs +9…13 min per job. | S | low | **13** (amends 01) |
| 2 | **Pin the Rust toolchain** (`channel = "1.99.1"`) and let Dependabot bump it. `stable` follows each runner image, and images roll out gradually: one run on 2026-10-27 compiled with 1.99.0 in `ui-tests` and 1.99.1 in `android-emulator`. rust-cache keys hash the rustc version. | §1.1 | Removes a recurring miss wave: every Rust release invalidates every key, and keys alternate during the multi-day rollout. | S | low | **12** |
| 3 | **arm64 runners for Linux test jobs** (free on public repos). This repo's own release builds are **20–25 % faster on arm64**, like for like: Linux package 10.6 vs 13.8 min on a miss, 6.0 vs 8.0 on a hit. | §3.1 | core/ui/mobile jobs −2…−3 min each, ≈ −8 runner-min/push | S–M | low–med | **14** |
| 4 | **Windows `tests` job on `windows-11-arm`** (the PR critical path). The `app` job keeps building, packaging and installer-testing the shipped x64 build. Same evidence: Windows package 16.4 vs 21.9 min on a miss, 11.2 vs 13.9 on a hit. | §3.1 | **Wall −4…−5 min** (22 → ≈ 17) | M | med (x64 test coverage moves to main/nightly) | **15** (needs decision) |
| 5 | **Emulator job: build before boot**, then tune the emulator (`cores`, ATD image A/B, sharding if still long). Today the emulator idles for 18 min (2 vCPU, software GPU) while Gradle builds the core and APK on the same 4-vCPU box. | §4.1 | emulator job −4…−8 min (Android-PR wall) | M | low–med | **16** (promotes 08's optional item) |
| 6 | **Gradle configuration-cache reuse** (`cache-encryption-key`), fix configuration-time dependency resolution, JVM test forks, cargo-ndk via binstall | §4.2 | −2…−4 min per Gradle job (×3–4 jobs) | S–M | low | **17** (needs a repo secret) |
| 7 | **Consolidate integration-test binaries**: engine has 58 `tests/*.rs`, harness 23, mobile-client 10. Each one links the whole engine graph. | §2.3 | −2…−5 min per Windows test build, −1…−3 Linux (estimate; 18 measures it first) | M | low with nextest | **19** |
| 8 | **`cargo --timings` on main** to find the slow crates, build scripts and link time before restructuring further | §2.2 | enabler (0 directly) | S | none | **18** |
| 9 | **Linux release on a glibc 2.35 floor** (A24): build in an `ubuntu:22.04` container (the 22.04 *runners* are being retired), add a `readelf` guard and a 22.04 smoke test | §6 | correctness; ≈ +1.5–2.5 min per release Linux job | M | low | **21** |
| 10 | **Affected-package selection on PRs** (Zed's `rdeps()` pattern) | §5.2 | only PRs that touch leaf crates; a build-dominated suite needs `-p` narrowing, not just a filter | M | med (false greens on PRs; main stays full) | **20** (low priority) |

Corrections to the existing plan (details in §7 and in each ticket's `## Comments`):

- **01**: the ≈ 9 GB budget leaves out AVD, Gradle and Go caches (≈ 6.6 GB today). `cache-workspace-crates: true` buys nothing on any job, because cargo's mtime freshness rebuilds workspace crates after every checkout. Set it to false on the Android jobs too.
- **04**: hosted Windows images **already** disable Defender real-time scanning and exclude `C:\` and `D:\`, so the "Defender off" step is a no-op; don't credit it with savings.
- **05**: Dev Drive's anti-virus benefit is moot (same reason). The measurable part is the disk. Move `CARGO_HOME`, `RUSTUP_HOME` and `TMP` there too, as uv and Zed do. Measure `windows-2025` while at it.
- **06**: the workspace has **no doctests** (checked), so switching to nextest loses nothing. Consider a named CI profile in `Cargo.toml` (uv's `fast-build`) instead of `CARGO_PROFILE_*` env, which fragments rust-cache keys.
- **08**: "build before boot" deserves its own ticket (16), because the log shows the overlap. The AVD cache goes (13).
- **10**: Dependabot now supports a `rust-toolchain` ecosystem (needed by 12). The emulator runner's README now pins `setup-gradle@v5` / `actions/cache@v5`.

Evaluated and **not** recommended (§8): merge queue (not available on a user-owned repo), self-hosted runner on the 20-thread dev box, third-party/larger paid runners (for now), workspace-crate caching via mtime tricks, `-Zthreads`/cranelift/`-Zshare-generics` (nightly), nextest archive/partition (build-dominated), cargo-chef, mold on x86_64 Linux (rust-lld is already the default).

## 1. Caching: what R1 missed

### 1.1 The toolchain moves under the cache keys

`rust-toolchain.toml` says `channel = "stable"`, and every workflow runs `rustup show active-toolchain || rustup toolchain install`. With `stable` already on the image, rustup uses the **image's** stable and never updates it. So the compiler version is whatever runner image a job lands on. rust-cache prints the version it hashes into the key:

| job (id) | started | rustc |
|---|---|---|
| windows `tests` 117340446645 | 10-17 07:22 | 1.99.0 |
| android windows 120301792319 | 10-23 07:43 | 1.99.0 |
| android windows 120794714959 | 10-24 05:50 | **1.99.1** |
| ui-tests 122084373368 | 10-27 08:22 | 1.99.0 |
| android windows 122363353731 | 10-27 17:45 | 1.99.1 |
| ui-tests 122458314912 | 10-27 20:43 | **1.99.0** |
| android-emulator 122458315870 (same run as the row above) | 10-27 20:43 | **1.99.1** |

Images roll out over days, so within one run different jobs compile with different compilers. Every key saved by a 1.99.1 job is a miss for a 1.99.0 job, and the reverse. On top of that, every Rust release (every 6 weeks) invalidates all keys at once. The cache list shows the effect: `v0-rust-android-…-13f5c225` on main next to `…-8e0cfa94` on the tag (different env hashes).

Peer projects pin an exact version: Zed `channel = "1.98.1"`, uv `1.99.0`, ruff `1.99.0` (`gh api repos/<repo>/contents/rust-toolchain.toml`). [Dependabot supports `rust-toolchain.toml` bumps since 2025-08-19](https://github.blog/changelog/2025-08-19-dependabot-now-supports-rust-toolchain-updates/). Pinning to the version the images currently ship costs no install time. A bump becomes one deliberate PR, and main then re-seeds once. → **ticket 12**.

### 1.2 The cache is 15.9 GB, and a third of it isn't Rust

`gh api repos/hoangvu12/roboco/actions/cache/usage` on 2026-10-28 (later than R1's snapshot): **15.91 GB in 38 entries** (limit 10 GB, enforced hourly with LRU eviction — [changelog 2025-09-29](https://github.blog/changelog/2025-09-29-new-date-for-enforcement-of-cache-eviction-policy/)). Composition:

| kind | entries | GB | note |
|---|---|---|---|
| Rust (rust-cache) | 7 | 9.21 | 3 of them tag-scoped (`refs/tags/v0.14.0`), useless to anyone else (ticket 01 removes them) |
| **AVD snapshot** (`avd-36-pixel-7-20261012`) | 1 | **2.75** | the single largest entry |
| Gradle (setup-gradle: home, transforms, dependencies, wrapper, …) | ≈ 27 | ≈ 2.80 | 4 writers: android ubuntu 0.91, android windows 0.93, tablet 0.72, emulator 0.24 |
| Go (setup-go) | 3 | 1.04 | Linux 0.46, Windows 0.33 (+0.25 on the tag) |

**The AVD cache barely ever hits.** I measured `actions/cache` restore and save seconds over every emulator job in R1's data. Restore took 0–1 s (a miss) in all but 4 runs (21–55 s restores). The post step re-uploaded 2.75 GB (18–24 s) after most green runs. So the largest entry is rewritten repeatedly, pushes the main Rust caches out, and buys at most ≈ 40 s of boot time: a cold boot in the log took 52.7 s ("Boot completed in 52686 ms"). The emulator runner README's recipe also needs two steps (generate the snapshot, then run with `-no-snapshot-save`). Roboco folds both into one step and saves a snapshot on every exit ("Saving snapshot 'default_boot' took 6010 ms").

**Consequence for ticket 01.** Its budget is ≈ 9 GB of Rust + Go with main-only writers. Add today's AVD and Gradle entries (≈ 5.5 GB) and the total is still ≈ 14.5 GB, so main's own entries would keep evicting each other. Ticket 13 makes the budget total: drop the AVD cache, keep a single Gradle writer, and keep the Go cache only where it pays.

**Paying for more cache is now possible and cheap.** Since [2025-11-20, the cache limit can exceed 10 GB per repository](https://github.blog/changelog/2025-11-20-github-actions-cache-size-can-now-exceed-10-gb-per-repository/). It is set in the repository's Actions settings, billed pay-as-you-go, and "requires a valid Pro, Team, or Enterprise account". Budgets are supported: a reached budget turns the cache read-only. The [price is $0.07 per GB-month over the included 10 GB, billed on the hourly peak](https://docs.github.com/en/billing/concepts/product-billing/github-actions), so 10 extra GB ≈ **$0.70/month**. One avoided miss saves 9–13 runner-minutes and up to 13 min of someone's wait. This is the cheapest speed item in either research round. It is a billing decision for the user, so ticket 13 is `ready-for-human` for that part only.

### 1.3 `cache-workspace-crates: true` does nothing useful here

`windows.yml` claims caching workspace crates "makes same-SHA reruns and follow-up PR pushes near-instant". Cargo decides freshness for path crates by **mtime**. `actions/checkout` stamps every source file with the checkout time, which is newer than any cached artifact, so every workspace crate rebuilds anyway. The [rust-cache README](https://github.com/Swatinem/rust-cache) says the same, and it is why the default is off. A zccache source note describes exactly this and warns against blanket mtime-bumping, which reused artifacts for crates whose source *had* changed ([zccache `snapshot_fp.rs`](https://docs.rs/crate/zccache-cli/1.8.0/source/src/snapshot_fp.rs)). The only effect today is a bigger cache entry: the `android` key is 2.07 GB. → comment on 01; apply to the android.yml jobs as well.

(Cargo's checksum-based freshness, `-Zchecksum-freshness`, would make workspace caching sound, but it is still nightly-only, and the [Cargo team noted build-script inputs are not checksummed](https://github.qingwei-s.workers.dev/rust-lang/blog.rust-lang.org/pull/1444.diff). Revisit when it stabilizes; see §8.)

### 1.4 rust-cache behaviour worth knowing (v2.9.3, 2026-10-20)

From the [README](https://github.com/Swatinem/rust-cache):

- It sets `CARGO_INCREMENTAL=0` itself, so the `incremental = true` in `profile.mobile` and dev-profile incremental are already neutralised in CI.
- It prunes artifacts older than one week before saving. That is harmless with main-only saves.
- `cache-bin` (default true) caches `~/.cargo/bin`, which is where `cargo-ndk` survives today.
- `lookup-only` and `cache-provider: warpbuild` exist. Not needed.

Nothing here changes ticket 01 beyond §1.3.

## 2. Rust compile speed

### 2.1 Linkers

- **Linux x86_64 already links with rust-lld.** It has been the default since [Rust 1.90 (2025-09-18)](https://blog.rust-lang.org/2025/09/01/rust-lld-on-1.90.0-stable). The blog measured ripgrep's incremental link 7× faster and a clean debug build 20 % faster. The jobs run 1.99, so nothing is left to gain from switching linkers on x86_64 Linux. mold would add a little more but is a new dependency, so it is not recommended.
- **aarch64 Linux does *not* default to lld.** If Linux test jobs move to arm64 (ticket 14), configure lld there: `CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_RUSTFLAGS="-Clink-arg=-fuse-ld=lld"` with the `lld` apt package. Measure it, because test binaries are link-heavy.
- **Windows**: rust-lld is ticket 05, unchanged.

### 2.2 Measure before restructuring further: `--timings`

R1's step timings say *which step* is slow, not *which crate*. Cargo's [`--timings`](https://doc.rust-lang.org/cargo/reference/timings.html) writes `cargo-timing.html`. It shows per-unit compile time, concurrency (how often the 4 vCPUs sit idle waiting on one crate), build-script and proc-macro time, and the link-heavy test units. Run `cargo test --no-run --timings <same flags>` on main runs just before the nextest step (same fingerprint, so nextest then reuses the build) and upload the report. That gives the evidence for ticket 19 and for any later crate split of `roboco-ui`/`roboco-engine`. → **ticket 18**.

### 2.3 Too many integration-test binaries

Each `tests/*.rs` file is a separate crate that statically links its package and the whole dependency graph. Counts: `crates/engine/tests` **58**, `crates/harness/tests` **23**, `crates/mobile-client/tests` 10, `crates/text/tests` 5, `crates/preview/tests` 4, `apps/roboco/tests` 4. CI runs about 30 engine binaries on Linux (`engine-local-regressions`) and 7 on Windows, plus ≈ 9 harness binaries. Every one of them is a full link of the engine graph. On Windows these are `link.exe` links of large binaries.

Evidence for consolidating into one `tests/it/main.rs` per crate (modules instead of binaries):

- [matklad, "Delete Cargo Integration Tests"](https://matklad.github.io/2021/02/27/delete-cargo-integration-tests.html): linking dominates; cargo's own test suite compiled 3× faster after the change.
- jj merged its integration tests into one binary: recompiling all tests went from 32–50 s to ≈ 4 s (per the search summary of the jj repo history).
- `codex-rs` did the same citing matklad (reported run time ≈ 8:49 → ≈ 21 s; secondary mirror, treat as indicative).

With nextest (tickets 04/06) every test still runs in its own process, so global env-var and cwd state does not leak between former binaries. That removes the usual objection. The win for Roboco is unmeasured. My estimate is −2…−5 min per Windows test build and −1…−3 per Linux core build; ticket 18 measures link time first. → **ticket 19**.

### 2.4 Profiles

- **uv's CI test profile** ([`Cargo.toml`](https://github.com/astral-sh/uv/blob/main/Cargo.toml)): `[profile.fast-build] inherits = "dev"`, `opt-level = 1`, `lto = "off"`, `debug = 0`, `strip = "debuginfo"`. Tests run through `cargo nextest run --cargo-profile fast-build-nightly` on every OS. A named profile in `Cargo.toml` has three advantages over `CARGO_PROFILE_*` env vars (ticket 06) or a generated `$CARGO_HOME/config.toml` (ticket 04):
  1. It is tracked and reproducible locally.
  2. It does not change the rust-cache key, which hashes `CARGO_*` env.
  3. It gets its own `target/<profile>` directory, so it never fights the release build.

  For Windows, upstream's measurement (dev 31.5 vs CI-release 23.7 min) still argues for an optimized-deps profile. A named `[profile.ci] inherits = "release"` plus `lto = false` and workspace `opt-level = 0` gives the same thing. One catch: a profile's per-package override can name workspace members only one by one. Comment on 04/06; not a separate ticket.
- `-Zshare-generics`, `-Zthreads` (parallel front-end) and the cranelift backend are nightly-only. Roboco builds on stable and ships what it tests, so they are not recommended (§8).
- `debug`: the dev profile already uses `line-tables-only`, ticket 06 sets `DEBUG=0`, and the Windows release profile has no debuginfo. Nothing to add.

### 2.5 Doctests

There are none: the `///`/`//!` fences in the workspace are all closing fences of `text` blocks or non-Rust snippets (grep of `crates/` and `apps/roboco`). Switching to nextest (04/06), which does not run doctests, loses no coverage, and the `Doc-tests` rustdoc pass per crate goes away. → comment on 06.

## 3. Runners

### 3.1 arm64 is faster for this repo, and free

[Windows on Arm runners are available to all public repositories](https://blogs.windows.com/windowsdeveloper/2025/04/14/github-actions-now-supports-windows-on-arm-runners-for-all-public-repos/), and Linux arm64 likewise. Both are the same nominal 4 vCPU / 16 GB as x64 ([runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)) and run on Cobalt 100 (Neoverse N2) cores. I found no public benchmark, but `release.yml` already builds the same graph on both architectures. Grouped by rust-cache hit or miss (`python -I` inline over `data/runs-release.yml.json`, successful jobs only, p50 minutes):

| job | cache | n | package/build step | whole job |
|---|---|---|---|---|
| linux x64 (`ubuntu-24.04`) | miss | 18 | 13.8 | 15.6 |
| linux arm64 (`ubuntu-24.04-arm`) | miss | 18 | **10.6 (−23 %)** | 12.1 |
| linux x64 | hit | 4 | 8.0 | 9.7 |
| linux arm64 | hit | 4 | **6.0 (−25 %)** | 7.7 |
| windows x64 (`windows-2022`) | miss | 13 | 21.9 | 24.4 |
| windows arm64 (`windows-11-arm`) | miss | 12 | **16.4 (−25 %)** | 20.4 |
| windows x64 | hit | 4 | 13.9 | 17.2 |
| windows arm64 | hit | 6 | **11.2 (−20 %)** | 16.9 |

(The arm64 Windows job's whole-job time includes Inno Setup and LLVM setup that the x64 image already has, so the step time is the fair comparison.)

Even on Linux, where x64 already links with rust-lld and arm64 does not, arm64 builds faster. The likely reason is that a 4-vCPU x64 VM is 2 cores with SMT, while Cobalt vCPUs are full cores. That is my inference, not documented. **`release.yml`'s comment that arm64 means "slower compiles" is contradicted by its own data.**

Constraints:

- **Android stays x64.** Google's Linux `aapt2` and other build tools are x86_64-only ([Google issue 227219818 via the Kotlin Slack thread](https://slack-chats.kotlinlang.org/t/26915103/hello-has-anyone-successfully-able-to-run-android-build-in-a), [Gradle forum](https://discuss.gradle.org/t/error-tyying-to-build-a-project/52127)), and the emulator needs x86 KVM.
- `windows-11-arm` needs clang on PATH for `ring` (already handled in `release.yml`), and node and Go for the harness and Tailcat suites.
- The `windows-11-arm` label moved to the VS 2026 image in September 2026 ([runner-images announcement](https://github.com/actions/runner-images/issues?q=label%3AAnnouncement)). Treat it as a moving image and pin it like the others.
- rust-cache keys include the architecture, so moving a job replaces its key; it does not add one. Only running *both* architectures for the same suite costs budget.

→ **ticket 14** (Linux test jobs) and **ticket 15** (Windows `tests`, decision needed: x64 test coverage moves to main/nightly while the `app` job keeps building and installer-testing the shipped x64 build on every PR).

### 3.2 Larger and third-party runners (spec open question 4)

Prices per minute after GitHub's [January 2026 cut of up to 39 %](https://resources.github.com/actions/2026-pricing-changes-for-github-actions/), from the [GitHub runner pricing reference](https://docs.github.com/en/billing/reference/actions-runner-pricing): Windows x64 larger runners 4-core $0.022, **8-core $0.042**, 16-core $0.082. Windows arm64 8-core $0.026, 16-core $0.050. Linux x64 8-core $0.022. Larger runners are **not free for public repositories**. Standard runners are.

Third parties (vendor-comparison blogs; verify on vendor pages before buying):

- Blacksmith: about $0.004/min Linux, $0.008/min for 2-vCPU Windows, 3,000 free min/month ([cicdpipelinecost](https://cicdpipelinecost.com/blacksmith-pricing)).
- Namespace: about $0.002–0.003/min Linux; also Windows/macOS; Zed runs on it.
- WarpBuild: $0.004/min.
- Ubicloud: Linux only, ≈ 1,250 free min.
- Depot: $0.006/min overage; uv runs Linux there.

Source for most of these: [DEV: cheapest runners 2026](https://dev.to/nedomas/the-cheapest-github-actions-runners-in-2026-what-100000-minutes-cost-2mlc). I found no free open-source program at Namespace.

Volume after 01–09: Windows `tests` + `app` ≈ 35 runner-min per run × ≈ 6 runs/day (PR + main) ≈ 6,300 min/month on 4 vCPU. On an 8-core GitHub runner, at ≈ 60 % of the time, that is ≈ 3,800 min × $0.042 ≈ **$160/month**; arm64 8-core ≈ **$100/month**. Recommendation: do the free arm64 move (15) first and re-ask the question with its numbers. A paid Windows runner then buys roughly a further −35…−45 % on the critical path.

### 3.3 Self-hosted on the 20-thread Windows dev machine

It would be by far the fastest (5× the cores, warm local sccache), but:

- GitHub's docs [recommend self-hosted runners only for private repositories](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners): a fork PR can edit the workflow to target the self-hosted label.
- On a user-owned repo there are no runner groups to fence it off.
- The only guard would be "require approval for outside contributors", which security write-ups call weak ([community thread](https://github.community/t/self-hosted-runner-security-with-public-repositories/17860)).
- The machine is also a dev box with secrets, and is not always on.
- The self-hosted platform fee was [postponed, and never applied to public repos](https://www.theregister.com/2025/12/17/github_charge_dev_own_hardware/). Cost isn't the issue; trust is.

**Not recommended** for any `pull_request` workflow. If ever wanted: `workflow_dispatch`-only, ephemeral (`--ephemeral` runner in a VM or Windows Sandbox), never on the same account as the signing secrets. No ticket.

### 3.4 Windows images, disks and Defender

- **Defender is already off on hosted images.** `actions/runner-images` `images/windows/scripts/build/Configure-WindowsDefender.ps1` sets `DisableRealtimeMonitoring = $true` and `ExclusionPath = @("D:\", "C:\")`, and switches to passive mode ([yossarian TIL, 2026-01-07](https://yossarian.net/til/post/github-actions-disables-windows-defender)). Ticket 04's step 1 and the "AV filter off" part of a Dev Drive buy nothing on GitHub-hosted runners.
- **C: is slow, D: is fast.** On hosted Windows runners C: is a read-optimized system disk and D: is a faster scratch disk. See the [pip maintainer's write-up](https://ichard26.github.io/blog/2025/03/faster-pip-ci-on-windows-d-drive) and [runner-images#8755](https://github.com/actions/runner-images/issues/8755) (anecdotal; the 10× figure from setup-ocaml's README is unverified). The workspace is already on D:, but `CARGO_HOME` (registry, git checkouts of zui/gpui-component, `~/.cargo/bin`) and `RUSTUP_HOME` live on C:\Users\runneradmin.
  - uv's [`setup-dev-drive.ps1`](https://github.com/astral-sh/uv/blob/main/.github/workflows/setup-dev-drive.ps1) uses D: when it exists (else a ReFS Dev Drive VHD) and points `CARGO_HOME`, `RUSTUP_HOME`, `TMP` and the workspace there.
  - Zed's `script/setup-dev-driver.ps1` does the same for `CARGO_HOME`/`RUSTUP_HOME`.

  → comment on 05.
- `windows-latest` is now `windows-2025` (VS 2026). Runner-images starts deprecating the oldest label once a newer one is GA, so `windows-2022` will be retired eventually (no date announced yet). Ticket 05 should A/B `windows-2025` while it is measuring anyway.

## 4. Android

### 4.1 Emulator job: the emulator idles while Gradle builds

Timeline from `log-emulator-122458315870.txt` (green run, Rust cache miss):

| time (UTC) | event |
|---|---|
| 20:52:31 | emulator launched (`cores: 2`, the action's default; `-gpu swiftshader_indirect`) |
| 20:53:22 | boot completed (52.7 s, cold) |
| 20:53:35 | Gradle starts **inside** the emulator step (`flow-tests.sh` → `connectedDebugAndroidTest`) |
| 20:56:59 → 21:03:32 | `uniffi-bindgen` build, `mobile` profile, 6 m 35 s (ticket 07 removes most of this) |
| 21:04:05 → 21:08:03 | x86_64 NDK core, 3 m 57 s |
| 21:08:03 → 21:09:03 | Go Tailcat c-shared for Android, 60 s |
| 21:09:41 → ≈ 21:11:00 | `compileDebugKotlin`, then dex/package of app + androidTest APKs |
| 21:11:37 → 21:24:15 | instrumented tests, 12.6 min |
| 21:24:23 | snapshot saved on exit, 6 s |

For about 18 minutes, a 2-vCPU software-rendered Android guest runs next to a 4-vCPU compile. Moving the build ahead of the emulator gives Gradle and rustc the whole machine:

1. Build the fixture engine.
2. `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` with `ROBOCO_ANDROID_ABIS=x86_64`.
3. Then start the emulator step. `connectedDebugAndroidTest` finds those tasks up to date.

Ticket 08 lists this as "optional, measure first"; the log is the measurement that justifies doing it. Then, with the build out of the way:

- **`cores: 4`** for the guest during the test phase (the action's `cores` input; default 2, per the [README](https://github.com/ReactiveCircus/android-emulator-runner)). A/B it, because the three fixture engines share the host.
- **ATD system image** A/B (`target: google_atd` or `aosp_atd`, API 36 x86_64). ATD images drop SystemUI and the Settings app and render in software, so they cost less CPU and memory ([Android Developers Blog, 2021](https://android-developers.googleblog.com/2021/10/whats-new-in-scalable-automated-testing.html)). API 30–36 is listed by [emulator.wtf](https://docs.emulator.wtf/emulators/). Risk: any flow that touches system UI (permission dialogs, system bars) behaves differently. It is an experiment, not a default.
- **Sharding** (`-Pandroid.testInstrumentationRunnerArguments.numShards=2 shardIndex=N` across two jobs fed by the prebuilt APKs and fixture binary as artifacts). Only worth it if the instrumented phase stays > 10 min after the above, because each shard pays checkout + boot (≈ 2 min).
- Snapshot: with the AVD cache dropped (13) there is no snapshot. Boot stays ≈ 53 s; `-no-snapshot-save` saves the 6 s exit write.

→ **ticket 16**.

### 4.2 Gradle

From `log-android-ubuntu-122458316176.txt` (green `android (ubuntu)`, Gradle total 14 m 21 s):

- **Configuration took 2.2 min** (20:44:50 "Calculating task graph as no cached configuration is available" → 20:47:04 first app task). That splits into ≈ 50 s of `buildSrc` compile and ≈ 85 s of configuration, which logs twice "Configuration 'debugCompileClasspath' / 'debugRuntimeClasspath' was resolved during configuration time. This is a build performance and scalability issue" ([gradle#2298](https://github.com/gradle/gradle/issues/2298)).
- `gradle.properties` enables the configuration cache, and the run ends with "Configuration cache entry stored". But [setup-gradle only saves and restores configuration-cache data when a `cache-encryption-key` is supplied](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md) (the entry may contain secrets). No workflow passes one, so every CI invocation recomputes configuration. Fix: a repository secret (`openssl rand -base64 16`) passed as `cache-encryption-key`. It is unavailable to fork PRs, which is fine because they just miss. Fixing the configuration-time resolution helps local builds too.
- JVM suite: `testDebugUnitTest` 2.8 min (20:56:17 → 20:59:07) with Gradle's default `maxParallelForks = 1`. Robolectric suites usually scale with forks; A/B `maxParallelForks = 2`.
- Rust inside Gradle (warm): bindgen 2 m 08 s, host core 24 s, arm64 core 1 m 14 s, x86_64 core 1 m 10 s, Go c-shared ×2 ≈ 1.5 min (`go: downloading …`, so the Go module cache missed too).
- **cargo-ndk** is compiled from source on a `~/.cargo/bin` miss: 31 s on Linux in the emulator log, 1.27 min p50 on the Windows leg (`overhead.py`). `taiki-e/install-action` has no cargo-ndk manifest but does have `cargo-binstall`, which fetches release binaries or falls back to quickinstall.
- **NDK download every job.** The runner images [removed NDK 28 and added NDK 30 on 2026-09-17](https://github.com/actions/runner-images/issues?q=label%3AAnnouncement). `sdkmanager --install "ndk;28.2.13676358"` therefore downloads it each job (0.45 min p50 ubuntu, 0.82 Windows). Bumping the NDK is an Android toolchain decision (it changes binaries), so it is noted in ticket 17 rather than recommended.

→ **ticket 17** (≈ −2…−4 min per Gradle job; three or four Gradle jobs per Android push).

### 4.3 ABIs on PRs

The ubuntu `android` job builds `arm64-v8a,x86_64` cores (≈ 1.2 min each warm, ≈ 4 min each cold). On PRs where the emulator job also runs, the x86_64 core is built twice (cache key `android` is shared, but the target dirs differ by job). Building only arm64 there would save ≈ 1–4 min of runner time, not wall time. It overlaps 08/16; noted under 08, no ticket.

## 5. Workflow structure

### 5.1 Merge queue: not available

[Merge queues are available in public repositories owned by an **organization**](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/merging-a-pull-request-with-a-merge-queue) (or Enterprise Cloud private repos). `hoangvu12/roboco` is user-owned. This is consistent with the spec's out-of-scope decision; it would need the repo to move to an org.

### 5.2 Affected-package selection (Zed)

Zed's `run_tests.yml` `orchestrate` job:

1. diffs against the merge base;
2. maps `crates/<dir>` to package names via `cargo metadata --no-deps`;
3. passes a nextest filterset `rdeps(a)|rdeps(b)` to the test jobs;
4. falls back to the full suite on push to main and on any change to `rust-toolchain.toml`, `.cargo/`, `.github/` or `Cargo.(toml|lock)`.

Zed's suite is run-time heavy. Roboco's is **build**-heavy: a filterset only skips running tests, nextest still builds every selected package. So the gain only comes from narrowing `-p`. For example, a PR touching only `crates/ui` need not build the 30+ engine test binaries. The risk is a false green on a PR when the mapping misses a non-crate input (assets, fixtures, `build.rs` env). Main runs the full suite, so the miss surfaces on main. → **ticket 20**, low priority, after 19 (fewer binaries shrink the win).

### 5.3 nextest archive / partition, cargo-chef

- [`cargo nextest archive`](https://nexte.st/docs/ci-features/archiving/) builds once and runs on many machines, and `--partition hash:i/n` splits the run. uv partitions its Windows suite three ways on 16-core Namespace runners because its *run* time is large. Roboco's Windows run time is small next to the build (e.g. "UI headless tests" 1.0 min after its build), so partitioning would multiply checkout and download for little gain. Revisit only if the post-04 `tests` job shows > 8 min of test execution.
- cargo-chef targets Docker layer caching; rust-cache already plays that role. Not applicable.

## 6. A24: Linux release glibc floor (from the wave-10 upstream spec)

Upstream [zeronsh/zeron#197](https://github.com/zeronsh/zeron/pull/197) (open) found that a Linux tarball built on Ubuntu 24.04 fails to load on 22.04: `GLIBC_2.39 not found`. Rust std references `pidfd_spawnp`/`pidfd_getpid` weakly, but building against 2.39 headers writes a **non-weak** `GLIBC_2.39` entry into `.gnu.version_r`, and `ld.so` validates every entry there. Roboco's `release.yml:46-47` builds on `ubuntu-24.04` and `ubuntu-24.04-arm`, so it has the same bug.

Upstream fixes it by switching the runners to `ubuntu-22.04`/`-22.04-arm` and asserting the floor (`GLIBC_MAX=2.35`, `readelf -V`) in `package-linux.sh`. **But the Ubuntu 22.04 runner images [began deprecation on 2026-09-17 and are fully unsupported from 2027-04-17](https://github.com/actions/runner-images/issues/14254)**, with brownouts in between. A runner switch would only fix it for ≈ 6 months and would risk failing a release on a brownout day.

Recommended: keep the 24.04 runners and run the Linux matrix job in a **`container: ubuntu:22.04`** (glibc 2.35, multi-arch image, so the same works on `ubuntu-24.04-arm`).

- The toolchain comes from `rust-toolchain.toml` via rustup inside the container.
- The apt package list is unchanged. The webkit/json-glib packages leave with the web removal anyway, and both exist on 22.04.
- Add the upstream guard to `scripts/package-linux.sh`.
- Add a smoke step that runs the packaged binary's CLI in a clean `ubuntu:22.04` container (`docker run`) with only the runtime libraries a user would install. Upstream's report shows `libxkbcommon-x11.so.0` is a hard `DT_NEEDED`. That turns the floor into a tested contract.

cargo-zigbuild (`x86_64-unknown-linux-gnu.2.35`) was considered and rejected. GPUI's system libraries come via pkg-config and the `cc` crate, so zig would need a 22.04 sysroot anyway, and the container gives that directly.

Build-time interaction: the same hardware and compile. The container adds rustup install + apt (≈ +1.5–2.5 min per Linux release job). The Windows x64 job (≈ 24 min) stays the release critical path. The arm64 Linux job gets the identical treatment and remains the fastest Linux job (§3.1). With ticket 01, release jobs build cold anyway. → **ticket 21**.

## 7. Challenges to the existing tickets (summary; full notes under each ticket's `## Comments`)

| ticket | finding | change |
|---|---|---|
| 01 | Cache is 15.9 GB; AVD 2.75 + Gradle 2.8 + Go 1.0 are outside the plan's ≈ 9 GB budget. `cache-workspace-crates: true` does nothing (mtime freshness). The toolchain floats under the keys. `preview-tests.yml` runs on `ubuntu-latest`, which [moves to 26.04 in November 2026](https://github.com/actions/runner-images/issues?q=label%3AAnnouncement). | Budget via 13; 12 alongside; `cache-workspace-crates: false` on the android.yml jobs too |
| 04 | Defender is already disabled on hosted images | Drop or keep the step as a documented no-op; credit no savings. Consider a named `[profile.ci]` (uv precedent). arm64 for `tests` via 15. |
| 05 | Dev Drive's AV benefit is moot; CARGO_HOME/RUSTUP_HOME/TMP sit on the slow C: | Map them to the Dev Drive or D: (uv, Zed); A/B `windows-2025` |
| 06 | No doctests to lose; `CARGO_PROFILE_DEV_DEBUG=0` env changes the key for every job that sets it | Prefer a named profile; arm64 via 14 |
| 07 | Warm-cache number for the bindgen build: 2 m 08 s in the ubuntu Gradle job (cold 6 m 35 s) | Acceptance can quote both |
| 08 | "Build before boot" is justified by the log; AVD cache is net-negative | Moved to 16; AVD removal in 13 |
| 10 | Dependabot has a `rust-toolchain` ecosystem; the emulator README uses `setup-gradle@v5`/`actions/cache@v5` | Add the ecosystem when 12 lands |

## 8. Evaluated and rejected (with reason)

- **Merge queue**: needs an org-owned repo (§5.1).
- **Self-hosted runner on the dev machine**: public repo, user-owned (no runner groups), GitHub recommends against it (§3.3).
- **Paid larger/third-party runners now**: ≈ $100–160/month for Windows; do the free arm64 move first (§3.2).
- **Caching workspace crates with mtime restoration** (`git-restore-mtime` + `cache-workspace-crates: true`, as one [Zenn article](https://zenn.dev/mierune/articles/2af7b9e447712a) suggests): a PR commit's committer date can be *older* than main's cache build, so a changed file looks fresh and cargo reuses a stale artifact. That is a false green. A content-safe variant (touch only files that differ from the cache's source SHA, set everything else to an old fixed time) is sound in principle, but it relies on cargo internals. Revisit when `-Zchecksum-freshness` stabilizes.
- **Nightly-only speedups** (`-Zthreads`, `-Zshare-generics`, cranelift backend, `panic-abort-tests`): Roboco tests on stable and ships what it tests.
- **mold on x86_64 Linux**: rust-lld is already the default (1.90+).
- **sccache with the GHA backend**: R1 §6 #11 stands. Zed uses sccache with an S3/R2 bucket, which needs cloud infrastructure Roboco deliberately doesn't run (AGENTS.md: no R2).
- **nextest partition/archive, cargo-chef**: build-dominated suite (§5.3).
- **Sparse/shallow checkout, toolchain install, `rustup` minimal profile**: measured with `overhead.py`. `actions/checkout` is 0.08–0.17 min p50, the toolchain step 0.00–0.02 (preinstalled), and the whole fixed overhead is ≤ 2.4 min per Linux job. The largest pieces are the rust-cache **post-save** (0.4 Linux / 1.6 Windows, removed on PRs by 01's `save-if`), cargo-ndk and the NDK download (17). After 12 pins the toolchain, a mismatch with the image costs one `rustup toolchain install --profile minimal` (≈ 0.3–1 min) until the images catch up.

## Sources

Official docs and changelogs:

- GitHub changelog: [cache can exceed 10 GB (2025-11-20)](https://github.blog/changelog/2025-11-20-github-actions-cache-size-can-now-exceed-10-gb-per-repository/)
- GitHub changelog: [eviction enforcement (2025-09-29)](https://github.blog/changelog/2025-09-29-new-date-for-enforcement-of-cache-eviction-policy/)
- GitHub changelog: [Dependabot Rust toolchain (2025-08-19)](https://github.blog/changelog/2025-08-19-dependabot-now-supports-rust-toolchain-updates/)
- GitHub docs: [Actions billing (cache $0.07/GB-month)](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
- GitHub docs: [runner pricing](https://docs.github.com/en/billing/reference/actions-runner-pricing)
- GitHub docs: [hosted runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- GitHub docs: [merge queue availability](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/merging-a-pull-request-with-a-merge-queue)
- GitHub docs: [self-hosted runners](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners)
- GitHub: [2026 pricing changes](https://resources.github.com/actions/2026-pricing-changes-for-github-actions/)
- [Windows on Arm runners for public repos (Windows Developer Blog)](https://blogs.windows.com/windowsdeveloper/2025/04/14/github-actions-now-supports-windows-on-arm-runners-for-all-public-repos/)
- actions/runner-images: [README / labels](https://github.com/actions/runner-images), [Ubuntu 22 deprecation #14254](https://github.com/actions/runner-images/issues/14254), `images/windows/scripts/build/Configure-WindowsDefender.ps1`, [#8755](https://github.com/actions/runner-images/issues/8755)
- Rust blog: [rust-lld default on 1.90](https://blog.rust-lang.org/2025/09/01/rust-lld-on-1.90.0-stable)
- Cargo: [`--timings`](https://doc.rust-lang.org/cargo/reference/timings.html)
- nextest: [archiving](https://nexte.st/docs/ci-features/archiving/)
- [Swatinem/rust-cache README](https://github.com/Swatinem/rust-cache) (v2.9.3)
- [gradle/actions setup-gradle docs](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)
- [ReactiveCircus/android-emulator-runner README](https://github.com/ReactiveCircus/android-emulator-runner) (v2.38.0)
- [Android Developers Blog: ATD images](https://android-developers.googleblog.com/2021/10/whats-new-in-scalable-automated-testing.html)
- [emulator.wtf image list](https://docs.emulator.wtf/emulators/)

Real repos (read via `gh api`):

- zed-industries/zed: `.github/workflows/run_tests.yml` (orchestrate/rdeps, Namespace runners, self-hosted 32-vCPU Windows, sccache), `rust-toolchain.toml` (1.98.1)
- astral-sh/uv: `.github/workflows/test.yml` (Depot/Namespace runners, `fast-build` profile, Windows partitions 3, rust-workspace-cache `save-if`), `.github/workflows/setup-dev-drive.ps1`, `Cargo.toml` profiles, `rust-toolchain.toml` (1.99.0)
- astral-sh/ruff: `rust-toolchain.toml` (1.99.0)
- zeronsh/zeron: PR [#197](https://github.com/zeronsh/zeron/pull/197)

Blogs and community:

- [matklad: Delete Cargo Integration Tests](https://matklad.github.io/2021/02/27/delete-cargo-integration-tests.html)
- [yossarian: GitHub Actions disables Windows Defender](https://yossarian.net/til/post/github-actions-disables-windows-defender)
- [ichard26: faster pip CI on Windows D:](https://ichard26.github.io/blog/2025/03/faster-pip-ci-on-windows-d-drive)
- [zccache snapshot_fp.rs](https://docs.rs/crate/zccache-cli/1.8.0/source/src/snapshot_fp.rs)
- [Zenn: git-restore-mtime + rust-cache](https://zenn.dev/mierune/articles/2af7b9e447712a)
- [The Register: self-hosted fee walked back](https://www.theregister.com/2025/12/17/github_charge_dev_own_hardware/)
- [DEV: cheapest runners 2026](https://dev.to/nedomas/the-cheapest-github-actions-runners-in-2026-what-100000-minutes-cost-2mlc)
- [cicdpipelinecost: Blacksmith pricing](https://cicdpipelinecost.com/blacksmith-pricing)
- [GitHub community: self-hosted + public repos](https://github.community/t/self-hosted-runner-security-with-public-repositories/17860)
- [Gradle forum: aapt2 on arm64](https://discuss.gradle.org/t/error-tyying-to-build-a-project/52127)
- [Kotlin Slack: Android tools on Linux arm64](https://slack-chats.kotlinlang.org/t/26915103/hello-has-anyone-successfully-able-to-run-android-build-in-a)

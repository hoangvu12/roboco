# 13 — Whole-cache budget: drop the AVD cache, one Gradle writer, decide on a paid limit

**What to build:** Ticket 01 budgets only the Rust and Go caches (≈ 9 GB). The repo cache is actually **15.9 GB in 38 entries** (`gh api …/cache/usage`, 2026-10-28):

| kind | GB |
|---|---|
| Rust | 9.2 |
| AVD snapshot | 2.75 |
| Gradle (≈ 27 entries, 4 writers) | ≈ 2.8 |
| Go | 1.0 |

With main-only Rust saves, AVD and Gradle still push the total to ≈ 14.5 GB. LRU eviction would keep throwing out main's own Rust keys, and that undoes 01. Research: `research-web-2026-10-28.md` §1.2.

- **Delete the AVD cache** (`actions/cache` `avd-36-*` steps and `force-avd-creation` logic) from `android-emulator` and `android-emulator-tablet`:
  - Over the R1 window its restore took 0–1 s (a miss) in all but 4 of ≈ 45 emulator jobs.
  - The post step re-uploaded 2.75 GB after most green runs (18–24 s).
  - A cold boot takes 52.7 s ("Boot completed in 52686 ms", job 122458315870).
  - Add `-no-snapshot-save` to `emulator-options` so exit doesn't write a snapshot (6 s).
- **One Gradle cache writer**: `gradle/actions/setup-gradle` with `cache-read-only: true` on `android-emulator`, `android-emulator-tablet` and the Windows Gradle leg; only `android (ubuntu)` writes. Setup-gradle already avoids saving on non-default branches. Measure whether the Windows leg (no Linux entries to read; OS-specific) slows by more than 1 min without its own cache. If it does, let it write on main only, since ticket 08 makes it main-only.
- **Go**: keep `setup-go` caching only in the one Linux job that saves on main (`mobile-core` or the Android ubuntu job); `cache: false` elsewhere. Ticket 01 already turns it off in release.
- Record the new budget table in `docs/reference/ci.md` (ticket 09). Every entry gets a key, its single writer and its size.
- **Decision for the user (paid limit):** since [2025-11-20 a repository's cache limit can exceed 10 GB](https://github.blog/changelog/2025-11-20-github-actions-cache-size-can-now-exceed-10-gb-per-repository/). It is pay-as-you-go at [$0.07 per GB-month over 10 GB, hourly peak](https://docs.github.com/en/billing/concepts/product-billing/github-actions) and requires a Pro/Team/Enterprise account. 20 GB ≈ $0.70/month. A budget cap makes the cache read-only rather than billing more. If yes: set the limit in Settings → Actions, add a budget, and record it here. With the steps above the limit is headroom, not a requirement.

**Blocked by:** none. Do it with or right after 01: both edit `rust-cache`/`setup-gradle` lines in `android.yml`; 01 owns the Rust lines, this ticket the AVD, Gradle and Go lines.

**Status:** implemented on branch `ci/caches-windows` (2026-10-28); paid limit declined by the user; the acceptance items are measured after merge

**Parent:** `.scratch/ci-speed/spec.md`

Baseline (2026-10-28): 15.91 GB / 38 entries; `avd-36-pixel-7-20261012` 2.75 GB; emulator "Post Run actions/cache@v4" 0.30 min p50; emulator "Run actions/cache@v4" 0.01 min p50 (miss).

- [ ] No `avd-*` key is created after merge; emulator boot time in the log ≤ 70 s — measure after merge (the existing `avd-36-pixel-7-20261012` entry, 2.75 GB, ages out by LRU or can be deleted by hand)
- [ ] `gh api …/actions/caches`: Gradle entries come from one job (plus Windows only if measured necessary); total Gradle ≤ 1.2 GB — measure after merge
- [ ] `cache/usage` stays < 9.5 GB for 7 consecutive days after 01 + 13 with no main Rust key evicted (`last_accessed_at` of each shared key within its workflow's run cadence) — measure after merge; ticket 01's estimate says this likely needs the web removal too
- [ ] Measured before/after over ≥ 10 runs: emulator job p50 (expect ≈ −0.3…+0.5 min; the boot is now always cold), Windows Gradle leg p50 (≤ +1 min) — measure after merge; if the Windows leg is > 1 min slower, let it write on main (`cache-read-only: ${{ github.ref != 'refs/heads/main' }}`)
- [x] User's paid-limit decision recorded under Comments (no paid limit, 2026-10-28)

## Comments

### 2026-10-28 — user decision

No paid cache limit (and no paid runners). Keep every cache inside the free 10 GB.

### 2026-10-28 — implementation (branch `ci/caches-windows`)

- **AVD**: both `actions/cache` `avd-36-*` steps and their `force-avd-creation` expressions are gone from `android-emulator` and `android-emulator-tablet` (the action's default `force-avd-creation: true` now applies; checked in its `action.yml`). `emulator-options` gain `-no-snapshot-save`.
- **Gradle**: `setup-gradle` has `cache-read-only: true` on `android-emulator`, `android-emulator-tablet` and `android-benchmark-apk`; the `android` matrix uses `${{ runner.os == 'Windows' || github.ref != 'refs/heads/main' }}`, so only `android (ubuntu)` on main writes (the same as setup-gradle's default for that leg). `release.yml`'s setup-gradle is untouched: tag refs are already read-only by default.
- **Go — deviation from "`cache: false` elsewhere"**: `setup-go`'s key is `setup-go-<OS>-<arch>-<image>-go-<version>-<go.sum hash>` with no job component, so every Linux job already shares the one Linux entry (0.46 GB in the snapshot), and a reader that finds it never writes. Turning the cache off in the other Linux jobs would save no space and make each of them (the emulator included, on the Android critical path) download modules and rebuild the Go cache, ≈ +1 min each. So Linux jobs keep it; the Windows Gradle leg gets `cache: ${{ runner.os == 'Linux' }}` (that removes the separate 0.33 GB Windows entry), and ticket 01 turned it off in `release.yml` (the tag entry). Which Linux job writes the shared entry on main is whichever finishes first; PRs only write when `go.sum` changes.
- `docs/reference/ci.md` budget table: left to ticket 09, as written above.
- Lint: `actionlint` 1.7.12 clean on `android.yml`.

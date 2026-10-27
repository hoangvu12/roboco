# 17 — Gradle: reuse the configuration cache in CI, fix configuration-time resolution, trim Android setup

**What to build:** Every CI Gradle invocation recomputes its configuration. In `android (ubuntu)` job 122458316176 that took **2.2 min** of 14 m 21 s: 20:44:50 "Calculating task graph as no cached configuration is available" → 20:47:04 first app task. That splits into ≈ 50 s of `buildSrc` compile and ≈ 85 s of configuration, which warns twice "Configuration 'debugCompileClasspath' / 'debugRuntimeClasspath' was resolved during configuration time".

`apps/android/gradle.properties` enables the configuration cache, and the build ends with "Configuration cache entry stored", but [setup-gradle only saves and restores configuration-cache data when `cache-encryption-key` is given](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md). No workflow passes one. Research: `research-web-2026-10-28.md` §4.2.

- **Configuration-cache reuse**:
  - The user creates a repository secret, e.g. `GRADLE_ENCRYPTION_KEY` = `openssl rand -base64 16`.
  - Pass `cache-encryption-key: ${{ secrets.GRADLE_ENCRYPTION_KEY }}` to every `setup-gradle` step in `android.yml`. Not `release.yml`: keep release builds free of cached configuration.
  - Fork PRs get no secret and just miss. Check that the configuration cache key isn't invalidated per run by volatile inputs read at configuration time, such as env vars like `ROBOCO_ANDROID_ABIS`, `-ProbocoAbis` or version derivation from git. Name any that are, and either fix them or accept them.
- **Configuration-time resolution**: find what resolves `debugCompileClasspath`/`debugRuntimeClasspath` during configuration (run `--info` locally; likely buildSrc or the license/asset task wiring in `app/build.gradle.kts`) and make it lazy (providers or `incoming.artifactView` in a task action). This helps local builds as well. If it is not a small change, file it as its own Android ticket and keep only the CI key here.
- **JVM tests**: A/B `maxParallelForks = 2` on `tasks.withType<Test>` (today 1; `testDebugUnitTest` ran 2.8 min). Keep it only if green and faster. Robolectric with JDK 21 per fork; `maxHeapSize = 2g` × 2 fits the 16 GB runner.
- **cargo-ndk**: install a prebuilt binary through `taiki-e/install-action` with `tool: cargo-binstall` and `cargo binstall --no-confirm cargo-ndk@${CARGO_NDK_VERSION}`, instead of `cargo install` from source. Today a `~/.cargo/bin` miss compiles it: 31 s on Linux, 1.27 min p50 on the Windows leg (`overhead.py`). Verify binstall resolves a release binary (or quickinstall) for both OSes; otherwise keep `cargo install`.
- **NDK (decision, not change)**: the runner images [removed NDK 28 and added NDK 30 on 2026-09-17](https://github.com/actions/runner-images/issues?q=label%3AAnnouncement), so `sdkmanager --install "ndk;28.2.13676358"` downloads it in every job (0.45 min ubuntu, 0.82 Windows, p50). Moving to the preinstalled NDK is an Android toolchain change: 16 KB alignment, binary differences, the `ndkVersion` pin. Record whether to open that ticket; do not bump here.

**Blocked by:** none (the secret needs the user)

**Status:** ready-for-human (create the secret; decide on the NDK question), then ready-for-agent

**Parent:** `.scratch/ci-speed/spec.md`

Baseline: configuration phase 2.2 min (job 122458316176); `testDebugUnitTest` 2.8 min; "Install cargo-ndk" p50 0.31 (ubuntu) / 1.27 (Windows); "Install Android SDK pieces" p50 0.45 / 0.82.

- [ ] Second main run after merge logs "Reusing configuration cache" in the ubuntu `android` job and the emulator job
- [ ] No configuration-time resolution warnings in the CI log (or a filed follow-up ticket explains why not)
- [ ] JVM test A/B attached; same test count; decision recorded
- [ ] Measured over ≥ 10 runs: time from "Welcome to Gradle" to the first `:app:` task (target ≤ 0.5 min on a reuse), `android (ubuntu)` job p50, "Install cargo-ndk" p50

## Comments

### 2026-10-28 — user decision

The repository secret `GRADLE_ENCRYPTION_KEY` was created (16 random bytes, base64, never printed). The NDK 30 move is approved; do it in this ticket. The secret half of "ready-for-human" is done.

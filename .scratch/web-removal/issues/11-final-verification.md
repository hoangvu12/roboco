# 11 — Final verification (build, CI, previews on desktop + Android, pairing over LAN + Tailcat)

**What to build:** Evidence that the PR removes what it should and nothing else. Record every command and result as a comment, the way fleet-follow 02 did.

**Static sweeps (branch head)**
- `git grep -n -i "web-client\|ts_rs\|wiregen\|rust-embed\|first_frame_auth\|tailcat_relay\|browser-helper\|crate::browser\|RightSurface::Browser\|open_web_links_in_roboco\|webkit2gtk\|ROBOCO_WEB_DIST\|pnpm"` over `crates apps scripts .github Cargo.toml`. The only allowed hits are `crates/ui`'s own `rust-embed` and fixture JSON holding `"web-client"`.
- `cargo tree -i ts-rs` is empty. `cargo tree -p roboco-engine -i rust-embed` is empty.
- `ls .github/workflows` matches AGENTS.md's CI list.
- Diff stat vs `web-final`, recorded for the PR body (expect about −207k lines).

**Local builds and tests (Windows host; this host needs `cargo +1.99.0` for `-p roboco`, see fleet-follow 02's host note)**
- `cargo build --release -p roboco` with Node off `PATH` and no `ROBOCO_WEB_DIST`. Record the binary size vs the v0.14.0 `roboco.exe`.
- `cargo test --workspace` (or the `windows.yml` set).
- `cargo test --release -p roboco-ui --lib -- --test-threads=1`.
- `cargo test -p roboco --test pairing_cli --test tailcat_invite_cli --test room_cli`.
- `cd apps/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- `./scripts/package-windows.ps1 -ReleasesUrl <url>` produces a zip.
- Linux (CI or a box): the `ui-tests` job builds on a runner **without** WebKitGTK packages. `scripts/package-linux.sh` produces a tarball.

**CI (one run on the PR head)**
- Every remaining workflow is green: `windows.yml`, `ui-tests.yml`, `preview-tests.yml`, `theme-artifact.yml` and `android.yml` (including `android-emulator` → `PreviewFlowTest`).
- `release.yml` `workflow_dispatch` dry run only if the user says so, and only after v0.14.0 is out. It must not create a release. If the workflow cannot dry-run without publishing, skip it and note that the Node-free path is covered by the local release build.

**Manual: previews on desktop (Windows 11, Chrome and Edge; then Linux with `xdg-open`)**
- Local chat: start Vite from a chat terminal. In the ports card, the click opens `http://<device>.<project>.localhost:7331/` in the default browser. No Roboco tab appears, and HMR updates on save.
- Paired engine over **LAN** (a Linux server):
  - The same click opens `…localhost:7332/`. It loads and HMR works.
  - Close the card, switch chats and wait more than 60 s, then reload. It still loads.
  - Stop the server. The page fails, and within about 30 s more `127.0.0.1:7332` stops listening (`Get-NetTCPConnection -LocalPort 7332`).
- Paired engine over **Tailcat**: the same three checks.
- Device-name collision (two engines with one name): both URLs route.
- Transcript web link: a click opens the default browser. The context menu shows Open and Copy only. A workspace-file link still opens the editor.

**Manual: previews on Android (a physical phone)**
- The renamed ⋯ row (ticket 09) → panel → a LAN engine's server loads in the WebView. Back and Reload work.
- The same over Tailcat.

**Manual: pairing**
- LAN: `roboco engine pairing create --qr` → the in-app scanner pairs. Fleet follow introduces the other engines (GetFleetInvite). The desktop pairs by pasting the `/pair#token=` link.
- Tailcat: `roboco engine tailcat invite --qr` → the phone pairs. The desktop pairs with the same flow. `roboco engine tailcat connect` prints a loopback link the desktop accepts.
- Rooms: `roboco engine room invite --qr` → the phone joins.
- Refusals against the engine: `curl -i http://<engine>:<port>/` and `/pair` → 404. `curl -i -H "Origin: http://evil.example" http://<engine>:<port>/health` → 403. A WS upgrade without Bearer (`websocat ws://<engine>:<port>/`) → 401. The same upgrade on `/preview-relay` → 401.
- An existing data dir from v0.14.0 (with browser sessions or `tailcat-relays/`) starts cleanly. Old sessions are listed and revocable.

**Finish**
- One code review of the whole branch (`web-final..feature/web-removal`) with attention on the listener auth paths (04) and the watcher lifecycle (07).
- PR body: what was removed, ADR 0012, the `web-final` tag, the dropped upstream ports list, and the verification log. Open the PR only when the user asks.

**Blocked by:** 01-10

**Status:** ready-for-human (automatable parts recorded below; the manual matrix, CI and the review remain)

**Parent:** `.scratch/web-removal/spec.md`

- [x] Static sweeps are clean. The `cargo tree` checks come back empty.
- [x] Node-free release build passes on Windows. Size delta recorded.
- [ ] Local test suites are green (workspace, ui lib, CLI pairing, Android unit)
- [ ] The PR CI run is fully green, including `android-emulator`
- [ ] Desktop previews: local (7331) and paired LAN + Tailcat (7332) load in Chrome and Edge, survive card close and chat switch, and release after the server stops. No blank tabs.
- [ ] Android previews load in the WebView over LAN and Tailcat
- [ ] Pairing works over LAN and Tailcat for phone and desktop. Rooms invite works. Fleet follow is intact.
- [ ] The browser-shaped refusals are confirmed (404 / 403 / 401)
- [ ] An old v0.14.0 data dir starts cleanly
- [ ] Final review is done. The PR body is drafted (not opened without the user's go).

## Comments

- 2026-10-28 (implementer), at the branch head after merging `origin/main` (`9edbf7f0a`; CI-speed #35, mobile-bindgen #36, settings-scroll #37 and the fleet-redesign specs). Merge conflicts were `release.yml` (main dropped rust-cache, this branch dropped Node: both stay dropped), `ui-tests.yml` (main's concurrency comment kept, `ROBOCO_WEB_DIST` env dropped), `package-windows.ps1` (main's `-AdapterPath` kept, `-SkipWebBuild` dropped), and main's edits to the two deleted web workflows (they stay deleted). `cargo +1.99.0 check --workspace --all-targets` after the merge has the same warning set as before it and no errors.
- **Static sweep** over `crates apps scripts .github Cargo.toml` for `web-client|ts_rs|wiregen|rust-embed|first_frame_auth|tailcat_relay|browser-helper|crate::browser|RightSurface::Browser|open_web_links_in_roboco|webkit2gtk|ROBOCO_WEB_DIST|pnpm`. The remaining hits are all allowed:
  - stored `"web-client"` data: `crates/engine-client/src/registry/fixtures/paired-engines-v1.json`, its assertion in `registry/tests.rs`, and Android's `EngineDetailModelTest` capability lists;
  - `crates/ui/Cargo.toml`'s own `rust-embed` (file icons);
  - the new settings back-compat test's `"open_web_links_in_roboco"` key;
  - unrelated `pnpm` agent-CLI detection in `crates/harness` and `crates/engine/src/harness_updates`;
  - the three negative route assertions in `crates/engine/tests/remote_listener.rs`.
- `cargo tree -i ts-rs` → "package ID specification `ts-rs` did not match any packages". `cargo tree -p roboco-engine -i rust-embed` → same, for `rust-embed`.
- `ls .github/workflows` = android, preview-tests, release, theme-artifact, ui-tests, windows, which is exactly the AGENTS.md CI list.
- Diff stat `web-final..HEAD` (includes main's merged work): 1652 files, +4190 / −220308. Outside `.scratch`: 1594 files, +2552 / −218007. Against the merged `origin/main`, i.e. this PR alone: 1587 files, +1924 / −217903.
- **Node-free release build:** `cargo +1.99.0 build --release --locked -p roboco` with every node/npm/pnpm/nvm/bun/volta entry stripped from `PATH` (`command -v node` → none, `command -v pnpm` → none) and no `ROBOCO_WEB_DIST`: exit 0 in 18m24s. `target/release/roboco.exe` = 153,597,952 bytes. That build embeds no Tailcat adapter; release builds embed it via `ROBOCO_TAILCAT_EMBED`, and the adapter built from the pinned sources is 28,756,992 bytes. So the estimated release-shaped binary is ≈182.35 MB, against v0.14.0's `roboco-0.14.0-windows-x86_64.exe` at 191,420,416 bytes: **≈ −9.1 MB (−4.7 %)**. This is an estimate: the release used CI's toolchain, while this host built with 1.99.0 (`rust-toolchain.toml` now pins 1.99.1).
- **Local suites** (see tickets 02–09 for per-ticket runs):
  - engine native paths (pairing / remote_listener / preview_relay / rooms), `roboco-engine --lib` (only host-only failures), mobile-client including the new origin-rule test, engine-client, preview-relay, proto/doc/update/rpc, `roboco-theme`, and the `roboco` CLI tests (`pairing_cli`, `tailcat_invite_cli`, `room_cli`);
  - the full `roboco-ui --lib` (release, one thread): 1654 passed plus the fixed `round_trip`;
  - head re-run after the merge: engine pairing, remote_listener, preview_relay and rooms (3, 4, 2, 9), `roboco-theme` (5), `origin_rule_engine` (1), and `cargo +1.99.0 test --release -p roboco-ui --lib -- --test-threads=1` → **1657 passed, 0 failed, 6 ignored** (313.6 s).
- **Not run on this host:** `cargo test --workspace` in full (the touched crates' suites ran instead; CI runs `windows.yml` and `ui-tests.yml`). `./gradlew :app:assembleDebug :app:testDebugUnitTest` needs the Android SDK, which is not installed. `./scripts/package-windows.ps1` needs PowerShell 7 and Inno Setup, neither installed; `windows.yml` runs it on the PR. `scripts/package-linux.sh` and the Linux build without WebKitGTK need Linux (`ui-tests` / `release` CI). No `release.yml` dry run, per instructions.
- Left for the user: the PR CI run, every manual item above (previews on desktop and Android, pairing over LAN and Tailcat, rooms, the 404/403/401 refusals with curl/websocat, an old v0.14.0 data dir), the Roborazzi re-record from ticket 09, and the final review.
- 2026-10-29 (implementer, second pass for the orchestrator): merged `origin/main` again (`a9ad12135`: #38 wave-10 Android, #40 fleet-redesign 16/17, #41 upstream sync tools, #42 the completion_notices race fix, plus docs). Conflicts: `paired_fixture.rs` (main's `seed(&core)` kept, plus this branch's preview-relay listener), and main's edits to `crates/wiregen/src/main.rs` and four `web/packages/proto/src/generated/*.ts` files (deleted again; two of them were new from #38). #38's new `RelayAgentLoginCallback` / `RelayedLoginCallback` derives in `crates/proto/src/entities.rs` lost their `TS`. A sweep of the merged tree finds no `ts_rs`, `#[ts(`, `derive(…TS)`, `wiregen` code or `web/` files. The remaining prose hits are ADR 0012, two "Rust twin of `@roboco/engine-client`" reference comments, and two notes in main's new `docs/reference/upstream-prs.tsv` ledger.
- The PR head's red jobs: `engine-local-regressions` failed `completion_notices::a_working_parent_receives_the_notice_after_its_turn_as_its_next_turn`, the race #42 fixes; after the merge, `completion_notices` is 25/25 locally. `ui-tests` failed this branch's rewritten `context_menu_cancels_visible_and_pending_hover_tooltips`, because the headless Linux clipboard reads back `None`. The test now pins the second row as Copy by asserting it never reaches the link handler (`6267fa6a8`). `engine_info_uses_camel_case_fields` was red again because main added `provider-switch-v1` (`db091656e`).
- Local, toolchain 1.99.1: `cargo check --workspace --all-targets` shows no errors, and its only new warning is main's `mobile-client` test support. Engine `completion_notices` / `remote_listener` / `pairing` / `preview_relay` / `rooms` are green, as are `roboco-proto` (122), `origin_rule_engine`, and `cargo test --release -p roboco-ui --lib -- --test-threads=1` (1659 passed, 0 failed). `link_interaction` with `--include-ignored` (the native-desktop tests) passes 8/8 on this Windows desktop.
- 2026-10-29 (implementer): the first push of the second pass could not run CI, because main had moved again (#43, Windows split), leaving the PR conflicting. Merged `cffa95d58`: `windows.yml` kept main's new linker-check step and dropped `-SkipWebBuild`. On that head, every check was green except `android-emulator`, which hit `KeyboardFlowTest.interactiveDismissClosesPastHalfAndReopensAboveIt`, the known IME-wait flake (ci-speed 11/22); `PreviewFlowTest` passed, 52 of 53 tests passed, and only the failed job was re-run. Main then moved again (#44, CI consolidation). Merged `936ef98eb`, taking main's `ui-tests.yml` and re-removing the `ROBOCO_WEB_DIST` env and the WebKitGTK/JSON-GLib `apt-packages` (`ui-tests` and `release-backstop`). Also: `room_relay` is gone from `scripts/ci/test-core.sh`, `preview-tests.yml` stays deleted, `windows.yml`'s stale preview-tests comment points at `core-tests`, and AGENTS.md takes main's new CI bullets minus the web ones. `scripts/ci/android-changes.sh` is unchanged. Its gate only picks the emulator and Windows tiers; the JVM palette pin tests run in `android (ubuntu)` on every `android.yml` trigger, and `android.yml` already triggers on `crates/theme/**`.

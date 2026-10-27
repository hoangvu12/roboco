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

**Status:** ready-for-human (needs a phone, a second engine, Tailcat and real browsers)

**Parent:** `.scratch/web-removal/spec.md`

- [ ] Static sweeps are clean. The `cargo tree` checks come back empty.
- [ ] Node-free release build passes on Windows. Size delta recorded.
- [ ] Local test suites are green (workspace, ui lib, CLI pairing, Android unit)
- [ ] The PR CI run is fully green, including `android-emulator`
- [ ] Desktop previews: local (7331) and paired LAN + Tailcat (7332) load in Chrome and Edge, survive card close and chat switch, and release after the server stops. No blank tabs.
- [ ] Android previews load in the WebView over LAN and Tailcat
- [ ] Pairing works over LAN and Tailcat for phone and desktop. Rooms invite works. Fleet follow is intact.
- [ ] The browser-shaped refusals are confirmed (404 / 403 / 401)
- [ ] An old v0.14.0 data dir starts cleanly
- [ ] Final review is done. The PR body is drafted (not opened without the user's go).

## Comments

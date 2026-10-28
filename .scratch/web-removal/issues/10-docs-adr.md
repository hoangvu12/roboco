# 10 — Docs: ADR 0012, ADR amendments, CONTEXT.md, AGENTS.md, reference docs

**What to build:** The docs describe what ships. Two clients (desktop, Android), no web client, no in-app browser, and previews that open in the system browser on desktop and in the WebView on Android.

**New ADR — `docs/adr/0012-no-web-client-no-embedded-browser.md`** (0012 is the next free number; 0001-0011 exist)
- Title along the lines of "Roboco has two native clients and no embedded browser". State the decision: the web client (ADR 0006/0007) and the desktop in-app browser are removed. Desktop previews open in the system browser through the engine's 7331 proxy (local) or the client preview proxy on 7332 (paired engines). Android keeps its WebView preview panel.
- Why: parity cost of a third client, ts-rs on every wire type, Node in release, pre-session browser attack surface (CORS, first-frame auth, server-side Tailcat/room relays), a desktop browser that never rendered on Windows, the WebKitGTK build dependency, and upstream browser ports that would need GPUI syncs.
- Consequences:
  - Lost: zero-install browser access (iPhone/iPad, borrowed PCs, Cloudflare-tunnel-in-a-browser) and the browser room join.
  - Phone *system-camera* scans of the pairing QR now land on a 404. Pairing goes through the in-app scanner or a CLI QR.
  - The paired bind now rejects `Origin` and Bearer-less upgrades.
  - Stale `tailcat-relays/` dirs and browser sessions in `pairing.sqlite3` are inert and revocable, and no migration runs.
  - `web/…` paths cited in code comments resolve at tag `web-final`.
  - Reversal = restore from `web-final` (ADR 0006's "hosted deployment remains possible later" still holds as a recovery path).
- Supersedes: ADR 0006, ADR 0007. Match the house ADR style (short prose + `## Consequences`; see 0011).

**Amend**
- `0006` and `0007`: add a status line under the title: "Superseded by [ADR 0012](0012-…md) (2026-10-xx). Kept for history."
- `0008-tailcat-transport.md`: lines ~5, ~26-28 and ~40. Remove "Browsers pair through a loopback forwarder…" and "the browser (through the loopback forwarder)" from the clients list. Clients are the desktop and Android, and `tailcat connect` prints a loopback pairing link for an app. Add a dated amendment note pointing at 0012.
- `0011-rooms.md`: lines ~13-16 (browser storage / browsers redeem through the engine serving them) and the **stale** status note at ~128. That note says the browser relay and the 100 MB cap were "not re-landed". Both came back with v0.13.0. The relay is now removed by 0012, and the 100 MB cap stays. Fix both facts and add a dated amendment.
- `0009-android-client.md`: lines 6-8, 67 and 99 are historical comparisons. Add one sentence: "The web client referenced here was removed by ADR 0012." Do not rewrite history.

**CONTEXT.md**
- "Web client" entry (~29-31): remove it, or mark it *Retired (ADR 0012)*, following ADR 0005's glossary rules (prefer marking retired so old notes still resolve).
- "Client preview proxy" (~172-173): "binds while viewing a remote engine's previews" → "binds while a remote engine's preview opened from the ports card is running (the desktop shell holds the watcher), or while Android's panel watches". Check the wording against ticket 07's implementation.
- If ticket 09 has landed, any UI-label mention uses the chosen label. **Preview** stays the concept term.

**AGENTS.md**
- CI list: delete the `web-codegen.yml` bullet and the `web-engine-client.yml` bullet. Rewrite `theme-artifact.yml` as "Rust freshness gate for `crates/theme/artifact/artifact.json` (`roboco-theme-export --check`), which Android's palette pin tests read". Keep the others. Mention that `release.yml` needs no Node if the bullet implies otherwise.
- Intro paragraph: nothing to change unless it names the web client (it does not today).

**Reference docs**
- `docs/reference/remote-access.md`: remove browser pairing, `browser-helper`, browser room join and the ADR 0006 skew note. Pairing paths are the in-app scanner, the `--qr` CLIs and pasting a `/pair#token=` link into an app.
- `docs/preview-networking.md`: rewrite the platform-browser parts (~3-14, ~72, ~115, ~135-136 "the browser helper 7333", ~160-185 WebKit CONNECT / WebKitGTK proxy / bypass, ~241-245 the `preview-fixture` build line). Desktop opens previews in the system browser. Document the 7332 lifecycle as ticket 07 built it. Android unchanged. Note that `*.localhost` resolution relies on the browser (Chrome/Edge/Firefox resolve it to loopback).
- `docs/markdown-preview.md:5` and `docs/transcript-browser-links.md`: web links open in the default browser. There is no "Open in Roboco", no preference and no Linux runtime requirement. Fold `transcript-browser-links.md` into a short section or delete it, and fix inbound links (`git grep transcript-browser-links`).
- Delete `docs/reference/linux-browser.md` and `docs/screenshots/browser/`. Fix inbound links (README.md:50, markdown-preview.md).
- `README.md`: line 50 (the Linux browser runtime line) and line 73 ("browser pairing" → app pairing).
- `docs/mcp.md`: line 5 ("the headed app and the web client"), line 134 and line 192 ("the Ports card and browser pane remain the canonical open paths" → "the ports card").
- `docs/regressions/sidebar-pin-integrity.md:62` and `docs/reference/windows-development.md:128` ("native browser … parity"): edit or drop the web/browser mention.
- Leave `docs/research/` alone (it is historical, per AGENTS.md).

**Blocked by:** 08 (and 09 if the label has been decided by then; otherwise 09 updates its own docs)

**Status:** done

**Parent:** `.scratch/web-removal/spec.md`

- [x] ADR 0012 exists and supersedes 0006/0007. Both carry the superseded line.
- [x] 0008 and 0011 are amended, with dates. 0011's stale status note is corrected. 0009 has the one-line pointer.
- [x] CONTEXT.md "Web client" is retired. "Client preview proxy" matches ticket 07.
- [x] The AGENTS.md CI list matches `.github/workflows/` exactly (`ls .github/workflows` vs the bullets)
- [x] `git grep -n -i "web client\|browser-helper\|linux-browser\|Open in Roboco\|Open links in Roboco\|browser pane\|wiregen\|pnpm" -- docs README.md CONTEXT.md AGENTS.md ':!docs/research' ':!docs/adr/0006*' ':!docs/adr/0007*'` shows only deliberate historical mentions
- [x] No broken relative links to deleted docs. A link check over `docs/` and `README.md` (or `git grep` for each deleted filename) comes back clean.

## Comments

- 2026-10-28 (implementer): New `docs/adr/0012-no-web-client-no-embedded-browser.md` ("Roboco has two native clients and no embedded browser"). It supersedes 0006/0007 and covers: the decision; the system-browser previews, with the shell-owned 7332 watcher as built in 07; the label "Ports"; the reasons; and the consequences (lost zero-install access and browser room join, the system-camera 404, Origin 403 / Bearer-less 401 on the paired bind, stale state left inert, `web-final` for the cited `web/…` paths, reversal by restore). 0006 and 0007 carry the superseded line. 0008: the forwarder bullet says "a loopback pairing link for a Roboco app", the clients list is desktop and Android, and a dated amendment points at 0012 (Cloudflare no longer means "no client software"). 0011: browser storage and the browser relay paragraph are rewritten as history, the stale status note is **corrected** (the relay and the 100 MB cap did come back with v0.13.0; the cap stays), and a dated "no browser join" amendment is added. 0009: the one-line pointer.
- CONTEXT.md: "Web client" is marked *Retired (ADR 0012)* and points at `web-final`. "Client preview proxy" now says it binds "while an opened remote preview's server runs", with the desktop shell's watcher and Android's panel. **Preview** stays the concept.
- AGENTS.md CI list: the `web-codegen.yml` and `web-engine-client.yml` bullets are gone. `theme-artifact.yml` is the Rust freshness gate for `crates/theme/artifact/artifact.json`, which Android's pin tests read. `release.yml` says "no Node". It now matches `ls .github/workflows` (android, preview-tests, release, theme-artifact, ui-tests, windows).
- Reference docs: `remote-access.md` (no browser-helper section, no browser room join, no ADR 0006 skew note; a new paragraph on pairing paths and the 404/403/401 refusals; `tailcat connect` wording); `preview-networking.md` (system browser on desktop, `*.localhost` resolution is the browser's, the 7332 lifecycle as built in 07, no WebKit/WebKitGTK sections, no `preview-fixture` build line, the Ports name, test coverage pointing at `opened_preview_tests.rs`); `markdown-preview.md` and `transcript-browser-links.md` (links open in the default browser; Open link / Copy only; the fixture sections are gone; the file is kept so inbound links resolve); `README.md` (Linux browser runtime line gone, "app pairing"); `mcp.md` (three spots); `regressions/sidebar-pin-integrity.md` (web vitest line gone); `reference/windows-development.md` ("native browser" parity gone). `docs/reference/linux-browser.md` and `docs/screenshots/browser/` are deleted. `docs/research/` is untouched.
- Sweep: `git grep -n -i "web client\|browser-helper\|linux-browser\|Open in Roboco\|Open links in Roboco\|browser pane\|wiregen\|pnpm" -- docs README.md CONTEXT.md AGENTS.md ':!docs/research' ':!docs/adr/0006*' ':!docs/adr/0007*'`. The remaining hits are deliberate: the history in ADRs 0008/0009/0011/0012, the retired CONTEXT entry, and remote-access's "no web client" statements. There are also two unrelated `pnpm` mentions: a project-action example `"pnpm dev"` and the Windows PATH backfill list for agent CLIs.
- Link check: a script resolved every relative `](…)` target in the 68 tracked Markdown files under `docs/` (minus research) plus README/CONTEXT/AGENTS, and found none broken. `git grep` for each deleted filename → only `markdown-preview.md`'s link to the kept `transcript-browser-links.md`.

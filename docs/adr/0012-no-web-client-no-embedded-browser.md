# Roboco has two native clients and no embedded browser

Supersedes [ADR 0006](0006-engine-served-web-client.md) and
[ADR 0007](0007-web-client-react-not-wasm.md). Decided 2026-10-28.

Roboco ships two clients: the desktop app and the Android app. The
engine-served web client (ADR 0006/0007) and the desktop's in-app browser
pane are removed. Previews stay. On the desktop, clicking a server in the
ports card opens it in the system browser: a local chat's server at the
engine's own always-on proxy (`http://<host>.localhost:7331`), and a paired
engine's server at the client preview proxy (`http://<host>.localhost:7332`).
For the 7332 case the desktop shell, not a browser tab, holds the proxy's
watcher. Opening a paired engine's server starts an opened-preview watcher
keyed by engine and chat. It keeps the proxy's routes current from the chat's
`WatchPreviews` snapshots, and it lets go once that chat's servers have been
gone for the proxy's idle window, the engine leaves the registry, or the app
quits. Android keeps its WebView preview panel unchanged. The servers list is
labelled **Ports** on both clients; **Preview** remains the glossary concept
(ADR 0005).

The web client was a third client kept in parity by hand. It cost about 203k
lines, a ts-rs codegen layer on every wire type, three CI workflows and Node
in every release build. Its engine plumbing was about 1.7k lines of
pre-session browser attack surface: CORS-open redeem, first-frame WebSocket
auth, and server-side Tailcat and room relays. The user did not use it, and
nothing on the desktop or Android depended on it. The in-app browser never
rendered a page on Windows, the primary desktop platform, because no WebView2
host existed. On Linux it forced a WebKitGTK/JSON-GLib build dependency onto
CI, release and every developer machine. Every upstream browser port (WebView2,
WPE, annotations, profiles) was large and pinned on a GPUI sync.

## Consequences

- **Lost:** zero-install browser access to an engine (iPhone and iPad, which
  have no Roboco client, a borrowed or work PC, a Cloudflare tunnel opened in a
  browser) and the browser room join. Reaching an engine needs the desktop or
  Android app.
- Scanning a desktop pairing QR with a phone's *system camera* now lands on a
  404. Pairing goes through the in-app scanner or a CLI QR
  (`roboco engine pairing create --qr`, `tailcat invite --qr`,
  `room invite --qr`), or by pasting a `/pair#token=` link into an app. The
  link grammar is unchanged: it is a parseable format, not a page.
- The paired bind serves no pages: `GET /`, `/pair` and any other non-route
  path are 404. It now refuses any request carrying an `Origin` header with a
  403, the treatment the Local bind already gave, because native clients send
  none. A WebSocket upgrade without a Bearer header, on `/` or
  `/preview-relay`, is a 401 before the upgrade. `/pairing/tailcat-redeem`,
  `/pairing/room-redeem`, `/tailcat-relay/*` and `roboco engine tailcat
  browser-helper` are gone. `roboco engine tailcat connect` stays and prints a
  loopback pairing link for a Roboco app.
- Unchanged: `/pairing/redeem`, `/room/present`, `/health`, Bearer RPC at `/`,
  Bearer `/preview-relay`, `uploads-100mb-v1`, `rooms-v1`/`rooms-v2`, the
  Local-bind Origin rule, `crates/preview`, `crates/preview-relay` and the
  Rust `crates/engine-client`.
- Wire types are plain Rust/serde. There is no ts-rs, no `wiregen` and no
  `web-client` capability. Releases build without Node, and Linux builds need
  no WebKitGTK. The theme artifact moved to
  `crates/theme/artifact/artifact.json`, where Android's palette pin tests
  read it.
- Web links in transcripts open in the system browser. The "Open links in
  Roboco" preference and the browser reload shortcut are gone. Settings files
  that still carry them load as before; the unknown keys are ignored.
- Stale state stays put: old data dirs may hold `tailcat-relays/` directories
  and browser-paired sessions in `pairing.sqlite3`. No migration runs. The
  directories are inert, and the sessions stay listed and revocable.
- Code comments that cite `web/packages/…` as a parity reference resolve at
  the tag `web-final` (the last commit with the web client, its engine
  plumbing and the desktop browser).
- Reversal means restoring from `web-final`. ADR 0006's "a separately-hosted
  deployment remains possible later" still holds as a recovery path, not a
  plan.

# Android is a native client on a shared Rust mobile core

Roboco ships a native Android app (`apps/android/`, application id and package
`sh.roboco.android`, display name "Roboco"). It ports upstream Zeron's iPhone
app one to one in look and behaviour, minus everything that needed the cloud.
It is a client like the desktop and the web client: it pairs with each engine
through a pairing URL (`POST /pairing/redeem`, then `Authorization: Bearer` on
the same ndjson WebSocket RPC the web client speaks), holds one connection per
paired engine, and keeps only a disposable per-engine cache (ADR 0004). Rust
decides what to paint and where; Kotlin paints, scrolls and handles gestures.
The Rust mobile core is upstream's, renamed: `roboco-text` (measurement on the
bundled Geist bytes), `roboco-markdown` (the incremental parser) and
`roboco-mobile` (transcript layout into display lists). Its data plane is new:
a thin paired-engine client on `roboco-rpc` replaces upstream's `zeron-client`
(Loro rooms, edge, WorkOS, command ledger). UniFFI generates the Kotlin
bindings (JNA); a Jetpack Compose shell plus custom Canvas painting draws the
display lists at Rust coordinates with the same font bytes.

- Look and behaviour come from `upstream/main:apps/ios` at `edb23db4` or later,
  which carries Group by Project (#874, closed and resubmitted unchanged as
  #875, merged 2026-10-11), plus three upstream PRs kept as target behaviour:
  #817 inline Markdown images, #664 immediate model/effort refresh, #714
  anchored attach menu.
- Upstream Android PRs #609, #621 and #699 are reference, not base: borrow
  #609's build, painter and composer logic, #621's painter details, measurer,
  direct-link lessons and row visuals, and #699's haptic/sound vocabulary, by
  hand (ADR 0003). Never merge or cherry-pick those branches.
- The wire does not change for Android. Engine additions are additive and
  capability-gated (`EngineInfo.capabilities`); an installed app skews against
  engines, so `EngineInfo` also reports the engine version and protocol
  generation, and clients decode leniently.
- Dropped: WorkOS sign-in, accounts and organisations, edge URLs, APNs/edge
  push, sync rooms and outbox semantics, remote voice and Live Activity, the
  in-app Rust demo host (tests run a mock-harness engine instead), the
  on-device engine, developer tools, provider accounts, slash commands.
- Notifications v1 are local: a foreground service holds engine connections
  while any run is active and posts local notifications using the desktop's
  done / needs-input / failed rule and chimes. Engine-originated push
  (UnifiedPush, ntfy, FCM) needs its own ADR.
- Tablets get the list-detail layout iOS has (`SplitRootController`).
- Ids: `sh.roboco.android`, deep links `roboco://…`, mention links
  `roboco-file:`.
- Toolchain floor: minSdk 31, targetSdk 36, compileSdk 37 (targetSdk moves to
  37 only after `ACCESS_LOCAL_NETWORK` is tested), AGP 9, Kotlin 2.4, JDK 17
  for the build (JDK 21 only for Robolectric test JVMs, which need it for
  SDK 36+), NDK r28+ for 16 KB pages, cargo-ndk driven from one
  cross-platform `cargo xtask android-core` called by plain Gradle tasks.
  Builds work on Windows and Linux hosts.
- Transport v1 is LAN `ws://` (cleartext allowed by the network security
  config) and `wss://` through operator tunnels (Cloudflare, Tailscale).
  Tailcat on Android and upstream's SSH direct mode are out of scope.

## Considered Options

- **Rust core + Kotlin painter** (chosen): the only option that gives Android
  the exact row geometry iOS gets, and the transcript, markdown and status
  rules stay in one Rust copy instead of three (desktop Rust, web TypeScript,
  Kotlin).
- **Pure Kotlin/Compose client**: simpler toolchain, but not one to one, and a
  third re-port of the transcript row model and markdown — rejected.
- **WebView around the engine-served web client**: pairing and transcripts on
  day one, but it is the web UI, not the iOS UI; notifications still need
  native code — rejected.
- **Rust layout only, Kotlin transport**: kept as the fallback if the
  paired-engine client stalls; it can be promoted to the chosen option later.
- **Forking upstream #609 or #621**: both sit on `zeron-client` (Loro rooms,
  WorkOS, edge) and an older core; carrying them would re-import the cloud
  model ADR 0004 removed — rejected.

## Consequences

- "Upstream ports must not restore iOS" still holds: no Swift, no Xcode
  project, no Apple CI. Upstream iOS changes become Android target behaviour,
  read with `git show upstream/main:apps/ios/...`; upstream changes to
  `crates/{mobile,text,markdown}` port like any crate, with renames.
- ADR 0006's "no skew" guarantee does not cover Android. Features gate on
  capabilities, decoding tolerates unknown fields and variants, and a newer
  engine gets a non-blocking notice instead of a failure.
- No Google services: no FCM, no Play Services dependency, no Play Store or
  Firebase deployment. The app ships as signed APKs on the same GitHub Release
  as the desktop artifacts, built on Linux runners. CI stays on Windows and
  Linux runners.
- Cleartext is allowed at the platform layer because engine listeners have no
  TLS by design; the app itself decides which hosts it dials.
- User-facing copy is taken from the web client's shipped strings ("New
  session", "Pair engine", "Engines", "Projects"), even where CONTEXT.md marks
  a word as avoided. All clients move to glossary terms together in a later
  rename, rather than Android diverging alone (a deliberate, recorded
  exception to ADR 0005's rule for new UI strings).

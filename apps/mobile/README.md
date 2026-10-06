# Roboco Android

Native Expo/React Native client for Roboco's engine-local pairing and RPC.
Recovered from the Kratos `feat/mobile-ui-parity` experiment, with the connection
layer replaced by Roboco's shared `web/packages/engine-client` implementation.
Android application ID: `sh.roboco.mobile`. This is a development app, not a
published Android release.

## Build

Use Node 24, JDK 21, Android SDK 36, NDK 28.0.13004108, and the adapter's pinned
Go 1.27.1 toolchain. Install `gomobile` and `gobind` at the `golang.org/x/mobile`
version in `adapters/roboco-tailcat/go.mod`. Set `ANDROID_HOME`, `ANDROID_NDK_HOME`
and put those tools on `PATH`. On the existing development VM, `source
Native/env.sh` selects the installed tools.

```sh
cd apps/mobile
npm ci
npm run typecheck
npm test
npm run build:android
```

The self-contained APK is `android/app/build/outputs/apk/release/app-release.apk`.
Expo's generated project signs this local build with its development key. Use
separate production signing before distributing a release. Generated native
projects, APKs, node_modules, and AARs are ignored; the native module and config
plugin are tracked. Expo Go cannot load this app's native module.

For JavaScript iteration after building a development client:

```sh
npm run build:aar
npm run android
npx expo start --dev-client
```

Build scripts use `adapters/roboco-tailcat` to produce
`target/tailcat/RobocoTailcat.aar`. There is no separate mobile transport fork.
Metro reads the shared engine client's source from this repository.

## Connect

Paste either:

- An engine's `http(s)://host:port/pair#token=…` pairing link. The address must be
  the server root; use a reachable LAN address or your HTTPS tunnel.
- A `roboco-tailcat:…` invite from `roboco engine tailcat invite`. Android opens
  its own native Tailcat route; no desktop browser helper is required.

The app redeems the single-use code at `POST /pairing/redeem` using an
Authorization header, verifies `EngineInfo`, then saves the engine's revocable,
non-expiring session credential. WebSocket authentication uses a separate first
text frame, followed by Roboco's JSON RPC envelopes. Pairing codes are not saved.
Engine identities are pinned and verified on reconnect. Revoked sessions stop
reconnecting and require a fresh pairing.

Saved routes and credentials are AES-GCM encrypted with Android Keystore.
Android backups are disabled, and Tailcat identity files live in no-backup
storage. Disconnect retains the pairing; Forget removes it from this client.
To revoke access on the engine too, use desktop Settings or
`roboco engine pairing revoke SESSION_ID`.

The current app selects one engine at a time. Its chats, files, terminals, and
queue belong to that engine; foreign target device IDs are rejected. Sending is
disabled while disconnected, and active streams resubscribe after reconnection.

## Engine conformance

From the repository root, build the isolated real-engine fixture:

```sh
cargo build -p roboco-engine --example web_conformance
cd apps/mobile
npm run test:engine
```

The test resolves Cargo's configured target directory and checks pairing,
identity, chats, transcripts, queue operations, reconnection, restore, and
revocation. It uses a temporary engine profile, not your own sessions.

## Remaining development

The recovered UI includes projects, sessions, transcripts, composer/queue,
workspace files, terminal, changes, and history. See [the inventory](docs/gpui-parity.md)
for remaining UI work and [HANDOFF.md](HANDOFF.md) for verification evidence.
Android Auto is a future native integration; this port contains no car service.

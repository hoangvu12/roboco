# Android port handoff

The app now lives in Roboco at `apps/mobile` on `feat/android-expo`.
Source was recovered from `/tmp/kratos-mobile-e2e-f550f836/apps/mobile`, a retained
Kratos `feat/mobile-ui-parity` snapshot. The original checkout/Git metadata is
missing; the recovered source and MIT license are retained here.

## Authentication and transport

- `src/connection-core.ts` pairs directly with a Roboco engine and adapts the
  shared engine client to the recovered UI's call/subscribe interface.
- `src/connection.ts` supplies native encrypted storage and Tailcat routing.
- `modules/my-module` is the Android Expo module: native Tailcat route lifecycle
  and Android Keystore storage. It no longer creates Kratos peer sessions.
- `adapters/roboco-tailcat/netmon_android.go` carries the Android interface
  discovery fix; the rest of the Go transport is shared with desktop Roboco.
- `plugins/android-connection.cjs` preserves no-backup and loopback HTTP support
  whenever Expo regenerates the Android project.

Kratos profile/account auth, `kratos-pair:` invites, session renewal,
`pair/engines` discovery, and binary relay framing are removed. Current Roboco
pairing grants and text RPC are authoritative. Credentials never go into URLs.
No cloud sync or iOS target was introduced.

## Verification

Verified on the development VM during the port:

- `npm ci` from the migrated lockfile; `npm run typecheck`.
- 17 mobile tests pass (the real-engine test is opt-in in the ordinary suite).
- `npm run test:engine` passes against the actual isolated Roboco engine:
  pairing, identity, chat/transcript streams, queue mutation, connection drops,
  foreground refresh, credential restore, and revoked-session refusal.
- All 36 shared engine-client unit tests pass.
- `go test -mod=readonly ./...` passes for the shared Tailcat adapter.
- Android `RobocoTailcat.aar` and self-contained `assembleRelease` APK build.
  This local APK uses Expo's development signing key.
- Android emulator: direct network pairing, full process restart and Keystore
  credential restore, prompt submission with the user entry appearing in the
  transcript, native Tailcat invite redemption into the chat screen, and native Tailcat
  restoration after a process restart. Forget removed the test pairing.

The test engine used a temporary profile. Existing user engine data was not used.
No physical-device, provider-authentication, or Android Auto test is claimed.
Run commands are in README.md.

## Follow-up work

Keep expanding the recovered UI against the current Roboco protocol. Multi-engine
selection, persistent offline transcript caching, richer settings, attachment
flows, and Android Auto are subsequent work. The current app supports one saved
engine at a time. Native network transitions and physical-device tests still
matter beyond emulator/connection-core evidence.

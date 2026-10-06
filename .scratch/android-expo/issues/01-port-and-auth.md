# Port Expo Android and replace authentication

Status: ready-for-human

Implemented in `apps/mobile` on `feat/android-expo`. Recovered Kratos frontend;
Roboco pairing grants and shared engine client replace signed-peer auth and binary
relay RPC. Native Tailcat binds the existing Go adapter; Keystore encrypts saved
credentials and route information. One engine is saved at a time.

Validation: clean npm install, TypeScript, 17 mobile tests, the isolated real-engine
conformance test, 36 shared engine-client unit tests, desktop Go adapter tests,
Android AAR/APK builds, and Android emulator pairing/process-restart/send checks.
See `apps/mobile/HANDOFF.md` for final runtime evidence and future work.

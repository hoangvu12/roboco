# Expo Android port

Bring the recovered Kratos Expo client into Roboco and replace its authentication
with current engine-local pairing. Preserve existing UI surfaces. Share Roboco's
engine client and Tailcat Go adapter rather than creating another protocol fork.

Scope: one saved Android engine, direct HTTP(S) pairing and native Tailcat invites,
Keystore credential storage, pinned engine identity, reconnecting streams, revoked
session refusal, build/test instructions and runtime verification.

Subsequent scope: multiple saved engines, offline transcript persistence, wider UI
parity, physical-device network testing, and Android Auto.

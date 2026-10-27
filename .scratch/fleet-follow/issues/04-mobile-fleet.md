# 04 — Mobile fleet: auto-fetch after pairing, silent join, dedupe

**What to build:** Android + `crates/mobile-client`.

After a **successful engine pairing** (the existing Pair page flows), the
client calls `GetFleetInvite` on the freshly paired engine (`rooms-v2`
gate; absent capability or a null answer → silent no-op, the pairing
looks exactly like today). With an invite in hand: run the existing
silent room join (`crates/mobile-client/src/rooms.rs` — the
parse/join/heal machinery) with a progress toast, then **dedupe**: member
engines already paired on this device (match by engine identity, else by
member address) are skipped so no engine appears twice; joined members
appear as ordinary paired engines.

Re-pairing the same engine re-runs the fetch idempotently (upsert the
room record, no duplicate sessions). The existing heal-on-open covers
late members and book unions. Keystore secret storage (the `room:` blob)
is unchanged.

**Blocked by:** 01

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] Post-pairing auto-fetch: rooms-v2 + invite → silent join; null/absent → no-op; no error toasts
- [ ] Dedupe: already-paired members skipped; no duplicate engine rows (PairedEngineStore-level test)
- [ ] Idempotent re-pair/re-join: upsert, no session churn
- [ ] Secrets stay in the Keystore `room:` blob; never logged, never in plain metadata (existing test patterns in `PairedEngineStoreTest.kt`)
- [ ] JVM unit tests green on this host (Robolectric is CI-only here); instrumented expectations documented for CI

## Comments

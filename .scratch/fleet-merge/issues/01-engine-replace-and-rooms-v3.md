# 01 — Engine: `RoomJoin { replace }`, member count in `GetFleetInvite`, capability `rooms-v3`

**What to build:** Let a desktop move an engine from one fleet to another.

- `RoomJoin` gains `replace: bool` (serde default `false`, so rooms-v2 clients are unchanged). Proto change in `crates/proto` (Rust-only once web removal has landed). It reaches UniFFI only if the mobile core calls `RoomJoin` (today it doesn't; check).
- `RoomStore::join` (`crates/engine/src/rooms.rs:192`, refusal at `:206-228`): with `replace` and a **fleet** invite, when the data dir holds a different fleet, leave it the same way `room leave` does (secure delete of the retained secret, drop the row), then join, in one transaction. Without `replace`, today's `FleetConflict` refusal is unchanged. Plain rooms ignore `replace`.
- `GetFleetInvite` (`crates/engine/src/rpc.rs:1552-1570`) also returns the book's member count, so a desktop can pick the bigger fleet without decoding the invite. Additive field.
- Capability `rooms-v3` (next to `rooms-v2`) advertises both.
- CLI: `roboco engine room join --replace` for parity (help text: "move this engine from its current fleet").
- Secrets and invites never appear in logs, `Debug` or error text (the existing fixed-text patterns).

**Blocked by:** the web-removal PR (it rewrites the listener and drops ts-rs on these types)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] `replace` moves a fleet engine; afterwards the old fleet has no row and its secret is unreadable (fresh store read)
- [ ] `replace` on a plain room is a no-op; no `replace` keeps today's refusal (tests in `crates/engine/tests/rooms.rs` + store unit tests)
- [ ] `GetFleetInvite` returns the member count; `rooms-v3` is advertised; a rooms-v2 client still works (compat test)
- [ ] `room join --replace` is covered in the `room_cli` tests
- [ ] No secret in logs/Debug (existing assertion pattern)

## Comments

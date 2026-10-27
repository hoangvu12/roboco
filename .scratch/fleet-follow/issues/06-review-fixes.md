# 06 — Final review fixes before the fleet-follow release

**What to build:** Fix the findings of the final code review of
`feature/fleet-follow` (base → `a69ec35c`), each verified by the
orchestrator. One commit per finding (or per tight pair), on branch
`ff-06-review-fixes`.

Out of scope (the orchestrator reports them): `/pairing/room-redeem`
throttling, hand-re-pair vs room ownership record, `client_fleet`
stale-record cleanup, mobile identity fold on first-dial timeout.

**Blocked by:** 01–05

**Status:** ready-for-human

**Parent:** `.scratch/fleet-follow/spec.md`

- [x] **F1 (HIGH, C4a):** no fleet path calls `CreateTailcatInvite` (or any settings-writing RPC). A fleet-kind store `join` unions the engine's own recorded Tailcat address (labelled `local_device_name_at`) into the book; plain rooms are unchanged byte-for-byte. The desktop learns member addresses through the book heal; the probe's address half and its cache are gone. Regression test: `remote-access.json` byte-identical across a fleet sync (incl. a Direct-transport, loopback, `public_url` engine) plus a guard that no fleet path names `CREATE_TAILCAT_INVITE`. Docs updated.
- [x] **F2 (HIGH):** each desktop presents under a per-machine label, so two desktops in one fleet never reissue (revoke) each other's room session on a shared engine. Phone label collision assessed. *The phone half is a documented follow-up: it needs Kotlin.*
- [x] **F3 (MEDIUM):** `adopt_engine` route handling is explicit (`RouteUpdate { Keep, Set }`): a LAN re-pair clears a stale `tailcat:` route; a route-less room presentation keeps the saved one.
- [x] **F4 (MEDIUM):** room-adopted entries save `tailcat:<address>` as their route so a restart rebuilds the forwarder; the room dialer shares the per-address forwarder map with the Tailcat resolver.
- [x] **F6 (LOW):** a failed `persist()` during a reattach restores the previous keepalive (and info) instead of killing the live forwarder.
- [x] **F9 (LOW):** `RoomStore::leave` is one transaction; `join`'s one-fleet check agrees with `fleet_room()` and self-heals an orphan `fleet_rooms` row left by an older store.
- [x] Focused suites green after the last change (list in the evidence comment).

## Comments

### 2026-10-27 — implementation evidence (Windows 11 host, worktree `roboco-ff-06`, branch `ff-06-review-fixes`)

**Commits** (`git log --oneline a69ec35c..HEAD`, before this ticket commit):

```
61d4ce6b fleet: room-adopted engines save their Tailcat route; one forwarder per address (F4)
43610e5d registry: explicit route updates on adopt; roll a failed reattach back whole (F3, F6)
a21040e1 fleet: each desktop presents under its own label (F2)
f0a28279 fleet: engines introduce themselves in the book; never ask one to mint (F1)
302260d1 rooms: leave a room in one transaction and self-heal orphaned fleet secrets (F9)
```

**F1.** The root cause is as the review describes: `TailcatProbe::address` sent
`CreateTailcatInvite`, which always saves settings. The guard checked only
enabled + loopback.

Fix:
- `roboco_engine::rooms::own_member(data_dir)` returns
  `{local_device_name_at, read_recorded_address}`, or `None` when no address
  is recorded. A fleet-kind `RoomStore::join` merges it, unless an entry for
  that address already exists (for example a CLI `--label` or the desktop's
  own entry). `create`, the `RoomJoin` RPC and CLI `room join` all get this
  through `join`. Plain rooms never gain an entry.
- The desktop sync now runs: shape probe (read-only `GetRemoteAccess`) →
  lazy mint → merge the local entry → `RoomJoin` push to every eligible
  engine → `GetRoomBook` on each pushed engine → union → link →
  `heal_room_books_with(pushed keys)` pushes the union back to every pushed
  and linked engine → `join_room_members` → local store merge.
- `FleetProbe::address`, `TailcatProbe::address`, the address cache and
  `local_member_label` are gone. The desktop's own entry now comes from the
  same `own_member`, so the store and the desktop never disagree on its label.

Convergence: one sync pass with every eligible member connected leaves every
pushed member's book holding the local entry plus every pushed member's own
entry. `GetFleetInvite` on any of them therefore serves every
Tailcat-serving member. A member that is offline during a pass catches up on
the pass its first connect triggers. An engine that joined through the CLI
and is not paired to this desktop is reached through a book containing its
entry (the desktop presents to it); its own entry reaches other books when
some client merges a book that holds it.

Linking (decision, not in the brief): a pushed engine is linked to the one
book entry its push did not carry (`own_entry`). That keeps the
hand-paired-entry behaviour of no presentation, no dial and no room session.
When the difference is ambiguous (the engine's book held other entries
before) it stays unlinked. `join_room_members` then presents to its address,
and the identity dedupe reattaches the existing entry.

Deviations and limits:
- `RemoteAccessStatus` does not expose the transport, so the shape check
  cannot require Tailcat. A Direct engine on loopback still reads as
  "serving". The only consequence is a settings-free `RoomJoin`.
- A Direct engine that once ran Tailcat still has `tailcat-address.json` and
  introduces that stale address. This is harmless (books only union), but
  the desktop's dial of it fails and shows as a "Not in yet · offline" row
  for that label.
- The ADR 0011 amendment does not describe the probe mechanism, so it is
  unchanged. `docs/reference/remote-access.md` is updated in two places: the
  CLI join now introduces the engine, and the address flow under the hood.

Tests:
- `a_fleet_join_introduces_the_engine_by_its_recorded_address` (store).
- `a_fleet_sync_never_rewrites_remote_access_settings`: real engines behind
  a method spy. One has a Direct-transport, `127.0.0.1` bind, `publicUrl`
  `remote-access.json`; one has a flag-off Tailcat file. Both files stay
  byte-identical over two syncs. Zero `CreateTailcatInvite`,
  `SetRemoteAccess` and `RevokePairingSession` calls. Every book and every
  served invite holds all three addresses after one pass.
- `no_fleet_path_names_a_settings_writing_rpc` (grep guard over the three
  rooms sources).
- `the_probe_only_ever_reads_remote_access`.
- `a_pushed_engine_is_linked_only_to_the_one_entry_it_added`.
- `the_fleet_is_minted_lazily_and_pushed_idempotently`, updated: the link
  comes from the book, with no dial, no session and no mint.

**F2.** The root cause is as described: `reissue_session` is keyed by
(room, label), and every desktop presented as `"Roboco desktop"`.

Fix: `join_room_members` takes a `presenter` label. The desktop passes
`desktop_label(local_device_name_at(data_dir), local device id)`, for example
`Roboco desktop (Studio PC · 3f9a1c)`. The label is stable per machine, the
6-character id tag separates same-named hosts, and it stays inside the
256-byte cap.

Tests:
- `two_desktops_presenting_to_one_engine_both_keep_their_sessions`: both
  credentials still authenticate and both entries answer `EngineInfo`. The
  same test fails at the credential assertion when forced to a shared label
  (checked, then reverted).
- `desktop_labels_are_per_machine_and_bounded`.
- The paste-flow UI test asserts that the member's session carries
  `RoomOps::presenter_label`.

Upgrade note: sessions presented earlier under the bare `"Roboco desktop"`
label go unused. The next presentation mints a row under the new label, and
the old row stays valid until revoked.

**Phone (F2 follow-up, needs Kotlin):** the presenter label is
`sanitize_label(config.device_name)`. Kotlin's `DeviceLabel` picks it from
`Settings.Global.DEVICE_NAME`, else `Build.MODEL`. Two phones of the same
model with no device name set therefore collide and revoke each other. Rust
has no stable per-install id: `ClientConfig.data_dir` is the disposable
`noBackupFilesDir/core` cache, and the durable state lives in Kotlin's
Keystore store. The fix is to pass a stable install id from Kotlin (a new
`CoreConfig` field, or a suffix in `DeviceLabel`). This host cannot compile
Kotlin, so it is left as a follow-up.

**F3 + F6.** Root causes are as described.

Fix:
- `enum RouteUpdate { Keep, Set(Option<String>) }`. `pair()` always uses
  `Set(resolver route)`, so a LAN re-pair clears the route.
  `join_room_member(…, route)` uses `Set(Some)` when the dialer gave a
  route, else `Keep`. New entries use `Keep` → `None`.
- The reattach rollback now restores the saved record, the previous
  keepalive and the previous info. Only the fresh keepalive drops.

Tests:
- `reattaching_saves_exactly_the_route_its_caller_knows`: Keep, Set, then
  the LAN re-pair clears the route, and a restart rebuilds nothing.
- `a_failed_save_during_reattach_keeps_the_live_forwarder`: a flaky session
  store means only generation 1 drops, the file is unchanged and the entry
  is still connected.

**F4.** The root cause is as described.

Fix: `ResolvedMember.route`. `TailcatRoomDialer` uses
`tailcat_pairing::forwarder` (the shared per-address map) and saves
`tailcat_pairing::route(address)`.

Tests:
- `a_room_adopted_entry_is_rebuilt_from_its_route_after_a_restart`: the
  saved route is `route:tcB`. After a restart with the old port dead, the
  resolver restores the entry once, it connects, and nothing is presented
  again. Without the route it never reconnects.
- `fleet_join_routes_are_restored_by_the_tailcat_resolver`.

**F9.** The root cause is as described.

Fix: `leave` is one IMMEDIATE transaction. A fleet join (with
`secure_delete`) sweeps `fleet_rooms` rows without a room, and its conflict
check joins `rooms`, so it agrees with `fleet_room()`.

Tests:
- `an_orphaned_fleet_secret_never_blocks_the_next_fleet`: `create` succeeds
  over an orphan, and the orphan secret is gone from disk.
- `leaving_a_fleet_leaves_no_rows_behind`.

**Final focused runs (after the last change, this host):**

- `cargo test -p roboco-engine --lib room` → 18 passed
- `cargo test -p roboco-engine --test rooms` → 9 passed
- `cargo test -p roboco-engine-client --lib` → 32 passed
- `cargo +1.99.0 test -p roboco-ui --lib -- rooms remote_access tailcat request_routing remote_lanes` → 25 passed
- `cargo test -p roboco-mobile-client --test fleet_engine --test rooms_engine` → 5 + 8 passed
- `cargo +1.99.0 test -p roboco --test room_cli` → 5 passed (see the flake below)
- `cargo +1.99.0 check --workspace --all-targets --keep-going` → only the
  known `roboco-preview` lib-test `UnixStream` error. The only remaining
  warnings are in untouched files (`request_routing.rs`, mobile test
  support).
- `cargo run -p wiregen -- --check` → fresh (no wire types touched)

**Pre-existing flake found, not fixed (out of the finding list):**
`room_cli::the_cli_creates_and_shares_a_fleet_against_a_running_engine`
failed once. The random room id began with `-` (base64url: about 1 id in 64),
and clap parsed `room invite -rLoj…` as an unknown flag. The next three runs
passed. This is a real CLI bug as well as a test flake: users with such an
id must write `room invite -- <id>` (the same for `leave`). Suggested fix:
`allow_hyphen_values = true` on the `ROOM_ID` positionals (check that
`--json` after it still parses), or document `--`.

**Not run here:** the full `roboco-ui --lib` suite (the orchestrator runs it
in another checkout); the unix-only 0600 tests; a manual desktop + server +
phone run over real Tailcat forwarders (test engines cannot run Tailcat);
Kotlin (no Android SDK).

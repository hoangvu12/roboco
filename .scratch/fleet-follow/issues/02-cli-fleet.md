# 02 — CLI fleet: room create, room invite, --json everywhere

**Port reality (2026-10-27):** your layer's v0.13.0 files are absent from
this branch (evil merge, see spec). Port `apps/roboco/src/pairing_cli.rs`'s
room subcommands and `apps/roboco/tests/room_cli.rs` from
`git show v0.13.0:<path>`, adapting to the post-wave-9 CLI tree, then add
the fleet commands below.

**What to build:** The CLI surface in `apps/roboco/src/pairing_cli.rs` +
`tests/room_cli.rs`.

- `roboco engine room create --name <n> [--label <label>] [--json]` —
  mints a fleet room through the engine store (secret from the proto mint,
  local self-join with the engine's recorded Tailcat address when present)
  and prints the room id — never the secret.
- `roboco engine room invite ROOM_ID [--qr] [--json]` — serializes the
  fleet invite (store `fleet_invite`; plain rooms refuse with "not a fleet
  room") and prints it plus an optional terminal QR, following the
  `pairing create --qr` precedent.
- `join` / `list` / `leave` keep working unchanged in behavior; `list
  --json` gains the room `kind` and member count.
- Every new command has `--json` with stable, documented shapes so agents
  can script the fleet (the spec's agent story needs no new flag on
  `pairing create`: pairing + phone-side auto-fetch is the one-liner).

**Blocked by:** 01

**Status:** ready-for-human

**Parent:** `.scratch/fleet-follow/spec.md`

- [x] `room create` mints a fleet room; id on stdout; no secret anywhere; works against a running engine (WAL, `room_cli.rs` style)
- [x] `room create` refuses a second fleet (fixed-text error, exit code ≠ 0)
- [x] `room invite` prints the invite + optional QR; plain rooms refuse with a fixed-text error; stdout carries an invite only for this command
- [x] `join`/`list`/`leave` behavior unchanged; `list --json` includes kind + member count
- [x] `--json` shapes stable and shown in `--help`
- [x] Tests: no secret in stdout except `room invite`, no secret in logs, idempotent re-join leaves one row

## Comments

### 2026-10-27 — deviations recorded before building on them (solo, ticket 02)

1. *Exit-code contract:* 0 ok, 1 failure (malformed invite, store/I/O),
   2 clap usage error, **3 refusal** (fleet already exists, not a fleet room,
   no such room). v0.13.0's `leave` of an unknown id exited 1 with
   `no room found: <id>`; it now exits 3 with the fixed text `no such room`
   and never echoes its argument (a mistakenly pasted invite stays off
   stderr). `invite` refuses unknown ids with the same `not a fleet room`
   text as plain rooms.
2. *Additive output changes:* the room `--json` object (create/join/list
   elements) gains `memberCount`; `kind` already came from 01's
   `RoomRecord`. `list` text gains a trailing kind column
   (`id\tlabel\tN members\tkind`). `leave` gains `--json`
   (`{"roomId","left":true}`).
3. *`create --name` defaults to "Fleet"* (the spec's default name), so it is
   optional rather than required.
4. *Engine-core touch, outside the CLI layer:* the default `--label` is the
   engine's device name (what `EngineInfo.deviceName` reports), so the
   CLI-seeded book entry carries the same name clients already show for that
   engine. The derivation (`local_device_name`: `ROBOCO_DEVICE_NAME`, the OS
   friendly name, hostnames, device-id fallback) is private to
   `crates/engine/src/lib.rs`; copying it into the CLI would let the two drift.
   So `crates/engine/src/lib.rs` gains one 7-line read-only wrapper,
   `pub fn local_device_name_at(data_dir)` — it reads `device-id` if present
   and never creates the identity. No other engine change; nothing else in
   the engine calls it.
5. *No recorded Tailcat address* → `create` still mints the fleet with an
   empty book and prints a note on stderr, rather than failing.

### 2026-10-27 — implementation evidence (Windows 11 host, worktree `roboco-ff-02`, branch `ff-02-cli`)

**What landed.**

- `apps/roboco/src/pairing_cli.rs`: the v0.13.0 `engine room join/list/leave`
  port (`7c66bed8`), adapted to the post-wave-9 tree, plus
  `room create [--name] [--label] [--json]` (`RoomStore::create`; book
  seeded with `{label, read_recorded_address}` when an address is recorded)
  and `room invite ROOM_ID [--qr] [--json]` (`fleet_invite` → `encode`;
  terminal/SVG QR through `try_qr_terminal`/`try_qr_svg` like
  `tailcat invite`, since a big book can outgrow a QR). Each subcommand's
  `--help` shows its `--json` shape (verbatim doc comments) and the
  exit-code contract (`after_help`). Refusals (`FleetConflict`,
  `not a fleet room`, `no such room`) print `Error: <fixed text>` and
  exit 3. No room code path reads or writes `remote-access.json`.
- `crates/engine/src/lib.rs`: `local_device_name_at` (deviation 4 above).
- `apps/roboco/tests/room_cli.rs`: the v0.13.0 test (including `4a3451d7`'s
  walk-after-drop) plus fleet coverage. Every CLI call runs with
  `RUST_LOG=trace`, so CLI logs land in the captured streams. The in-process
  engine logs at TRACE into a captured buffer, and the test asserts that
  buffer actually holds engine output before sweeping it.

**Tests (`apps/roboco/tests/room_cli.rs`, 5 tests):**

- `the_cli_joins_lists_and_leaves_against_a_running_engine`: the v0.13.0
  flow plus `kind:"room"`/`memberCount` in join and `list --json`;
  re-join → one row; plain-room `invite` refused (exit 3);
  `leave --json` shape; second leave refused; `list --json` → `[]`;
  `remote-access.json` byte-identical; no secret on any stream or in the
  engine log; disk walk after the engine drops.
- `the_cli_creates_and_shares_a_fleet_against_a_running_engine`: create
  against a running engine (book = recorded self address); `invite --json`
  decodes to the same room id, kind fleet, same book; `--qr --json` adds
  `qrSvg`; text `--qr` prints the invite line then a terminal QR. The
  CLI-created fleet secret presents at `/room/present` on the running
  engine, and the session's `GetFleetInvite` returns the same room. A
  second create and a different fleet's join are refused with the
  `FleetConflict` text, exit 3. Re-joining the fleet's own invite with a
  bigger book → same id, `memberCount` 2, still one row. A plain room sits
  alongside: `list --json` shows both kinds and counts. `invite` on a plain
  or unknown id → `not a fleet room`, exit 3. An invite pasted as a
  `leave` id is refused without echo. leave → re-create works with the
  default name "Fleet". C4a byte-identical. The secret sweep covers every
  stream except `invite` stdout, plus the engine log. At rest, fleet
  secrets appear only in `pairing.sqlite3*`; the plain and refused-fleet
  secrets appear nowhere.
- `create_seeds_the_book_only_from_a_recorded_address`: no address →
  `memberCount` 0 plus a stderr note; with an address and no `--label`, the
  label equals `local_device_name_at`.
- `every_room_command_documents_its_json_shape_and_exit_codes`.
- `a_malformed_invite_is_refused_with_a_shape_only_error` (ported; now pins
  exit 1).

**Commands and results (this host):**

- `cargo +1.99.0 test -p roboco --test room_cli` → 5 passed
- `cargo +1.99.0 test -p roboco --test pairing_cli` → 3 passed (no
  regression); `--test tailcat_invite_cli` → 0 tests on Windows
- `cargo +1.99.0 check -p roboco --all-targets` → clean, no warnings in the
  touched files; `rustfmt --check` clean on the new code (the one remaining
  diff in `pairing_cli.rs` is pre-existing tailcat code, identical at HEAD)

**Host note (toolchain).** The repo's `rust-toolchain.toml` says `stable`,
and this host's `stable` is 1.93.0. On 1.93 the `roboco` binary does not
build: the pinned gpui (`zui` `0966d06`) uses `std::hint::cold_path`,
which is unstable there. Ticket 01 never hit this because it built only the
engine crates. I built with the already-installed 1.99.0 (`cargo +1.99.0`)
and changed no global toolchain. Anything building `-p roboco` on this host
needs the same.

**Not done / CI-only.** Nothing from this ticket is left. The 0600 check on
`pairing.sqlite3` is unix-only and belongs to ticket 01's test.
`docs/reference/remote-access.md` (which 7c66bed8 also touched) was not
updated — the docs belong to ticket 05.

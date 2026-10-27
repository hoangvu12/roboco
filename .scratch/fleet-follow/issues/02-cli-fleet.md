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

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-follow/spec.md`

- [ ] `room create` mints a fleet room; id on stdout; no secret anywhere; works against a running engine (WAL, `room_cli.rs` style)
- [ ] `room create` refuses a second fleet (fixed-text error, exit code ≠ 0)
- [ ] `room invite` prints the invite + optional QR; plain rooms refuse with a fixed-text error; stdout carries an invite only for this command
- [ ] `join`/`list`/`leave` behavior unchanged; `list --json` includes kind + member count
- [ ] `--json` shapes stable and shown in `--help`
- [ ] Tests: no secret in stdout except `room invite`, no secret in logs, idempotent re-join leaves one row

## Comments

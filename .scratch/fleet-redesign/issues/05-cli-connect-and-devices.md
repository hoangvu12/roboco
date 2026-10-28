# 05 — CLI: `roboco connect` and `roboco devices`

**What to build:** in `apps/roboco/src` (beside `pairing_cli.rs`):

- **`roboco connect`** (no argument) prints a connect code (ticket 02).
  - `--qr` adds a terminal QR; `--json` prints `{code, expiresAt}`, plus `qrSvg` with `--qr`.
  - It starts a background engine when none owns the data dir.
  - When remote access was **never configured**, it sets up Tailcat as `roboco engine tailcat invite` does today. When it was **explicitly turned off**, it refuses with one line: `remote access is off on this machine; turn it on with: roboco engine tailcat invite`.
- **`roboco connect <code>`** connects this server to the code's server (mutual; then ticket 03 brings everyone along).
  - It prints a one-line result, `Connected to SG. 3 more devices joining.`, or `--json`.
  - Old `roboco-tailcat:` invites are accepted too.
- **`roboco devices [--json]`** lists peers: name, kind, status, last seen. Routes and credentials are never printed.
- **`roboco devices remove <name|deviceId>`** calls ticket 04. An ambiguous name errors with the ids.
- **The QR fix** (from fleet-merge ticket 05):
  - dark modules on a light background with explicit colours;
  - a one-line "widen the terminal" hint when the code is wider than `$COLUMNS`.
- **Exit codes:**
  - `0` ok;
  - `1` failure;
  - `2` usage;
  - `3` refused (remote access off, code expired or used, device removed).

  Errors go to stderr, start with `Error:` and never echo the code.
- `roboco engine room *` is removed in ticket 09. `roboco engine pairing create/list/revoke` and `tailcat invite/address/connect` stay for advanced use.

**Blocked by:** 02 (`devices remove`: 04)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-redesign/spec.md`

- [ ] `apps/roboco/tests/connect_cli.rs`: connect both ways between two loopback engines; `devices --json` shape is stable; `devices remove`; exit codes
- [ ] `--qr` output decodes with `rqrr` after rendering with the explicit colours; the width hint fires below the code's width
- [ ] Refusal when remote access is explicitly off; settings are untouched
- [ ] No code, route or credential in stdout (except the code from `roboco connect` itself), stderr or logs

## Comments

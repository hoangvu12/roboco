# 04 — Desktop QR codes that scan: sized by module count, crisp, Enlarge; no fleet-invite QR

**What to build:**

- Every desktop QR tile is sized from its module count: a whole number of logical px per module, at least 4, quiet zone 4 modules. That covers the pairing link in LAN and Tailcat modes and the Tailcat invite; `crates/engine/src/remote_access.rs:15-20` has today's fixed sizes, and the settings pages render them.
- Paint the modules as GPUI quads (one quad per run of dark modules), not a rasterised SVG. GPUI rasterises SVGs at 2× regardless of display scale and filters them linearly (`zui …/platform.rs:2581-2584`, `gpui_windows/src/directx_renderer.rs:1014`).
- An **Enlarge** action shows the code centred in a full-window overlay at the largest whole px-per-module that fits. Esc closes it.
- Invites and Tailcat links use error-correction level L. LAN links keep M (they're tiny).
- **Remove the "Show fleet invite" QR** (`settings/remote_access/fleet.rs:443`). Keep Copy with the ADR 0008 caption. A short line under it says phones should scan the pairing QR, and the fleet follows.

**Blocked by:** the web-removal PR (it touches the same settings pages and drops the browser surfaces)

**Status:** ready-for-agent

**Parent:** `.scratch/fleet-merge/spec.md`

- [ ] `rqrr` decodes each tile from a rendered image at its real logical size and at 1×/1.5×/2× scale: LAN link, Tailcat link, Tailcat invite
- [ ] Assertion: at least 4 logical px per module for every payload shape
- [ ] Enlarge opens and closes (GPUI test); the fleet card has Copy and no QR
- [ ] Manual (07): a phone scans the Tailcat pairing QR on a laptop screen from about 30 cm

## Comments
